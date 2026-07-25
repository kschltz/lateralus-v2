#!/usr/bin/env python3
"""Drive an interactive lateralus session on a real PTY with visible typing."""
from __future__ import annotations

import os
import select
import sys
import time
import pty
import errno


START_MODEL = os.environ.get("START_MODEL", "deepseek-v4-flash")
NEXT_MODEL = os.environ.get("NEXT_MODEL", "gpt-oss:20b")
BASE_URL = os.environ.get("BASE_URL", "https://ollama.com/v1")
CONFIG = os.environ.get(
    "CONFIG", "resources/lateralus/demo-ollama-cloud-config.edn"
)

PROMPTS = [
    (
        "Call the self_status tool. In your final answer, quote only "
        "configuration.model and configuration.base-url."
    ),
    (
        f"Use set_llm_config to change the session model to {NEXT_MODEL}. "
        "Then call self_status again. Final answer: old model, new model, "
        f"and confirm base-url is still {BASE_URL}."
    ),
    (
        "Reply with one short sentence that includes the exact model id "
        "from self_status."
    ),
]


def banner() -> None:
    print()
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║  lateralus — Ollama Cloud model switch demo                  ║")
    print(f"║  start: {START_MODEL}")
    print(f"║  switch → {NEXT_MODEL}")
    print(f"║  endpoint: {BASE_URL}")
    print("╚══════════════════════════════════════════════════════════════╝")
    print()
    sys.stdout.flush()


def type_slowly(fd: int, text: str, delay: float = 0.02) -> None:
    for ch in text:
        os.write(fd, ch.encode())
        time.sleep(delay)
    os.write(fd, b"\n")


def drain(fd: int, idle_s: float = 0.35, overall_s: float = 120.0) -> str:
    """Read PTY output until idle, returning collected text."""
    buf = []
    deadline = time.time() + overall_s
    last = time.time()
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try:
                chunk = os.read(fd, 4096)
            except OSError as e:
                if e.errno == errno.EIO:
                    break
                raise
            if not chunk:
                break
            os.write(1, chunk)
            buf.append(chunk.decode(errors="replace"))
            last = time.time()
        elif time.time() - last >= idle_s and buf:
            # Idle after some output — likely waiting at the next prompt.
            joined = "".join(buf)
            if "lateralus>" in joined[-80:] or "EOF" in joined[-40:]:
                break
            # Still running (thinking spinner); keep waiting.
            if "thinking" in joined[-120:]:
                last = time.time()
                continue
            break
    return "".join(buf)


def main() -> int:
    if not os.environ.get("OLLAMA_API_KEY"):
        print("OLLAMA_API_KEY is not set", file=sys.stderr)
        return 1

    banner()
    time.sleep(1.0)

    argv = [
        "clojure",
        "-M:run",
        "-i",
        "--config",
        CONFIG,
        "--base-url",
        BASE_URL,
        "--model",
        START_MODEL,
    ]

    pid, master = pty.fork()
    if pid == 0:
        os.execvp(argv[0], argv)

    # Wait for first prompt
    out = drain(master, idle_s=0.5, overall_s=90.0)
    if "lateralus>" not in out:
        # Give startup more time
        out += drain(master, idle_s=1.0, overall_s=60.0)

    for prompt in PROMPTS:
        time.sleep(0.8)
        type_slowly(master, prompt, delay=0.022)
        drain(master, idle_s=1.2, overall_s=180.0)

    time.sleep(0.5)
    type_slowly(master, "/quit", delay=0.03)
    drain(master, idle_s=0.8, overall_s=30.0)

    _, status = os.waitpid(pid, 0)
    print()
    print("── demo complete ──")
    sys.stdout.flush()
    return 0 if os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
