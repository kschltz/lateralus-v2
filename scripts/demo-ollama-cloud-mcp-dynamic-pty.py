#!/usr/bin/env python3
"""Drive an interactive lateralus session: Ollama Cloud + dynamic MCP upsert.

Starts a local fake Streamable HTTP MCP server, then runs lateralus against
Ollama Cloud. The agent lists MCP servers (empty), upserts the fake server,
calls an MCP tool, and confirms the new tools are live — all mid-session.

Requires OLLAMA_API_KEY. Prefer this PTY driver for screen recordings.
"""
from __future__ import annotations

import os
import select
import signal
import subprocess
import sys
import time
import pty
import errno


START_MODEL = os.environ.get("START_MODEL", "deepseek-v4-flash")
BASE_URL = os.environ.get("BASE_URL", "https://ollama.com/v1")
CONFIG = os.environ.get(
    "CONFIG", "resources/lateralus/demo-ollama-cloud-mcp-dynamic.edn"
)
SERVER_ID = os.environ.get("MCP_SERVER_ID", "demo")


def start_fake_mcp() -> tuple[subprocess.Popen, str]:
    """Launch fake-mcp-http-server; return (proc, url)."""
    proc = subprocess.Popen(
        ["clojure", "-M:dev", "-m", "fake-mcp-http-server"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    deadline = time.time() + 90.0
    url = None
    while time.time() < deadline:
        line = proc.stdout.readline()
        if not line:
            if proc.poll() is not None:
                break
            time.sleep(0.05)
            continue
        line = line.strip()
        if line.startswith("http://") or line.startswith("https://"):
            url = line
            break
    if not url:
        proc.kill()
        raise RuntimeError("fake-mcp-http-server did not print a URL")
    return proc, url


def prompts_for(url: str) -> list[str]:
    upsert_args = (
        f'{{"server-id":"{SERVER_ID}",'
        f'"config":{{"transport":"http","url":"{url}",'
        f'"allow-http?":true,"allow-loopback?":true}}}}'
    )
    return [
        (
            "Call mcp_list_servers. In your final answer, report "
            "dynamic-enabled? and the server count only."
        ),
        (
            "Use mcp_upsert_server exactly once with these arguments "
            f"(JSON): {upsert_args} "
            "Then call mcp_list_servers. Final answer: the server-id and "
            "the tool names that were discovered."
        ),
        (
            f"Call the MCP tool {SERVER_ID}_echo with message "
            '"ollama-cloud-mcp-demo". Final answer: quote the echoed '
            "content only."
        ),
        (
            "Call mcp_list_servers once more. Final answer: one short "
            "sentence confirming the demo server is still connected and "
            "naming one of its tools."
        ),
    ]


def banner(url: str) -> None:
    print()
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║  lateralus — Ollama Cloud + dynamic MCP upsert demo          ║")
    print(f"║  model: {START_MODEL}")
    print(f"║  endpoint: {BASE_URL}")
    print(f"║  fake MCP: {url}")
    print(f"║  server-id: {SERVER_ID}")
    print("╚══════════════════════════════════════════════════════════════╝")
    print()
    sys.stdout.flush()


def type_slowly(fd: int, text: str, delay: float = 0.02) -> None:
    for ch in text:
        os.write(fd, ch.encode())
        time.sleep(delay)
    os.write(fd, b"\n")


def drain(fd: int, idle_s: float = 0.35, overall_s: float = 120.0) -> str:
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
            joined = "".join(buf)
            if "lateralus>" in joined[-80:] or "EOF" in joined[-40:]:
                break
            if "thinking" in joined[-120:]:
                last = time.time()
                continue
            break
    return "".join(buf)


def main() -> int:
    if not os.environ.get("OLLAMA_API_KEY"):
        print("OLLAMA_API_KEY is not set", file=sys.stderr)
        return 1

    fake = None
    try:
        fake, url = start_fake_mcp()
        banner(url)
        time.sleep(1.0)

        argv = [
            "clojure",
            "-M:dev:run",
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

        out = drain(master, idle_s=0.5, overall_s=90.0)
        if "lateralus>" not in out:
            out += drain(master, idle_s=1.0, overall_s=60.0)

        for prompt in prompts_for(url):
            time.sleep(0.8)
            type_slowly(master, prompt, delay=0.018)
            drain(master, idle_s=1.4, overall_s=240.0)

        time.sleep(0.5)
        type_slowly(master, "/quit", delay=0.03)
        drain(master, idle_s=0.8, overall_s=30.0)

        _, status = os.waitpid(pid, 0)
        print()
        print("── demo complete ──")
        sys.stdout.flush()
        return 0 if os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0 else 1
    finally:
        if fake is not None and fake.poll() is None:
            fake.send_signal(signal.SIGTERM)
            try:
                fake.wait(timeout=5)
            except subprocess.TimeoutExpired:
                fake.kill()


if __name__ == "__main__":
    raise SystemExit(main())
