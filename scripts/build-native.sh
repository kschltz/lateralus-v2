#!/usr/bin/env bash
set -euo pipefail

# Build a GraalVM native image for lateralus-v2.
#
# Usage:
#   scripts/build-native.sh [--fix-hosts] [--keep-cache] [--clean-all]
#
# Options:
#   --fix-hosts   Add the current hostname to /etc/hosts (requires sudo)
#                 This works around a Clojure CLI / Maven issue where
#                 InetAddress.getLocalHost() fails when the hostname is
#                 not mapped to a loopback address.
#   --keep-cache  Do not delete any caches. Use only when you are sure the
#                 cached classpath is valid.
#   --clean-all   Also delete ~/.clojure/.cpcache and ~/.gitlibs. This forces
#                 a full dependency re-resolve, which can be slow and may
#                 fail in restricted/sandboxed environments.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOSTS_FILE="/etc/hosts"

if [[ -z "${GRAALVM_HOME:-}" ]]; then
  echo "ERROR: GRAALVM_HOME is not set."
  echo "Install GraalVM and export GRAALVM_HOME to its Home directory, e.g.:"
  echo "  export GRAALVM_HOME=/path/to/graalvm/Contents/Home"
  exit 1
fi
GRAALVM_DIR="$GRAALVM_HOME"
# Keep all Clojure classpath caches inside the workspace so the build works
# in restricted/sandboxed environments that cannot write to ~/.clojure.
CLJ_CACHE_DIR="${REPO_ROOT}/.clojure-cache"

FIX_HOSTS=false
KEEP_CACHE=false
CLEAN_ALL=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --fix-hosts)
      FIX_HOSTS=true
      shift
      ;;
    --keep-cache)
      KEEP_CACHE=true
      shift
      ;;
    --clean-all)
      CLEAN_ALL=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 [--fix-hosts] [--keep-cache] [--clean-all]"
      exit 1
      ;;
  esac
done

# --- 1. Verify GraalVM ------------------------------------------------------

if [[ ! -d "$GRAALVM_DIR" ]]; then
  echo "ERROR: GraalVM directory not found: $GRAALVM_DIR"
  echo "Check that GRAALVM_HOME points at a GraalVM Home directory."
  exit 1
fi

export GRAALVM_HOME="$GRAALVM_DIR"
export JAVA_HOME="$GRAALVM_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
export CLJ_CACHE="$CLJ_CACHE_DIR"

mkdir -p "$CLJ_CACHE_DIR"

java -version 2>&1 | head -3

# --- 2. Install native-image if missing --------------------------------------

if [[ ! -x "$JAVA_HOME/bin/native-image" ]]; then
  echo "native-image not found, installing via GraalVM updater..."
  "$JAVA_HOME/bin/gu" install native-image
fi

# --- 3. Optionally warn about hostname resolution ----------------------------

THIS_HOST="$(hostname)"
if ! java -e 'System.out.println(java.net.InetAddress.getLocalHost());' >/dev/null 2>&1; then
  echo "WARNING: Java cannot resolve hostname '$THIS_HOST' via InetAddress.getLocalHost()."
  if [[ "$FIX_HOSTS" == true ]]; then
    echo "Adding '127.0.0.1 $THIS_HOST' to $HOSTS_FILE ..."
    sudo sh -c "printf '127.0.0.1 %s\n' '$THIS_HOST' >> '$HOSTS_FILE'"
    sudo dscacheutil -flushcache
    sudo killall -HUP mDNSResponder 2>/dev/null || true
    if ! java -e 'System.out.println(java.net.InetAddress.getLocalHost());' >/dev/null 2>&1; then
      echo "ERROR: Java still cannot resolve hostname after updating $HOSTS_FILE."
      exit 1
    fi
  else
    echo "If classpath resolution fails, run with --fix-hosts to add 127.0.0.1 $THIS_HOST to $HOSTS_FILE."
  fi
fi

# --- 4. Clean caches --------------------------------------------------------

if [[ "$KEEP_CACHE" != true ]]; then
  echo "Cleaning build output and workspace classpath cache..."
  rm -rf "$REPO_ROOT/.cpcache" "$REPO_ROOT/target" "$CLJ_CACHE_DIR"
  mkdir -p "$CLJ_CACHE_DIR"
  if [[ "$CLEAN_ALL" == true ]]; then
    echo "Also cleaning global Clojure caches..."
    rm -rf "$HOME/.clojure/.cpcache" "$HOME/.gitlibs"
  fi
fi

# --- 5. Build native image ----------------------------------------------------

echo "Building native image..."
cd "$REPO_ROOT"
clojure -T:native native

echo ""
echo "Build complete. Binary should be available at:"
ls -lh "$REPO_ROOT/target/lateralus-v2-native" 2>/dev/null || true
