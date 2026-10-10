#!/usr/bin/env python3
"""Observe the CI emulator's actual exit without weakening Android test gates."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys


def record(path: Path, message: str) -> None:
    # Deliberately omit arguments and environment: these may contain credentials.
    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        with path.open("a", encoding="utf-8") as stream:
            stream.write(f"{timestamp} {message}\n")
            stream.flush()
    except OSError:
        print("Could not write CI emulator exit diagnostics", file=sys.stderr)


def install(sdk: Path, status_log: Path) -> None:
    emulator = sdk.resolve() / "emulator" / "emulator"
    native = emulator.with_name("emulator.unihub-native")
    temporary = emulator.with_name("emulator.unihub-monitor.new")
    if not emulator.is_file() or not os.access(emulator, os.X_OK):
        raise ValueError("The SDK emulator executable is unavailable")
    if native.exists() or temporary.exists():
        raise ValueError("Refusing to overwrite an existing emulator monitor")
    status_log = status_log.resolve()
    status_log.parent.mkdir(parents=True, exist_ok=True)
    command = [
        sys.executable,
        str(Path(__file__).resolve()),
        "run",
        "--native",
        str(native),
        "--status-log",
        str(status_log),
        "--",
    ]
    wrapper = "#!/bin/sh\nexec " + shlex.join(command) + ' "$@"\n'
    with temporary.open("x", encoding="utf-8") as stream:
        stream.write(wrapper)
    temporary.chmod(0o755)
    try:
        emulator.rename(native)
        try:
            temporary.replace(emulator)
        except OSError:
            native.rename(emulator)
            raise
    finally:
        temporary.unlink(missing_ok=True)
    print("Installed CI emulator exit monitor; native arguments and output preserved")


def monitor(native: Path, status_log: Path, arguments: list[str]) -> int:
    child: subprocess.Popen | None = None

    def forward(signum: int, _frame: object) -> None:
        record(status_log, f"MONITOR_SIGNAL name={signal.Signals(signum).name}")
        if child is not None:
            try:
                child.send_signal(signum)
            except ProcessLookupError:
                pass

    previous = {}
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        previous[signum] = signal.signal(signum, forward)
    try:
        # Preserve the launcher's argv[0] and SDK directory while observing the
        # native executable. stdout/stderr remain under the workflow's redirect.
        original_name = native.with_name("emulator")
        child = subprocess.Popen(
            [str(original_name), *arguments], executable=str(native)
        )
        record(status_log, f"START monitor_pid={os.getpid()} native_pid={child.pid}")
        return_code = child.wait()
        if return_code < 0:
            name = signal.Signals(-return_code).name
            record(status_log, f"EXIT native_pid={child.pid} return_code={return_code} signal={name}")
            return 128 - return_code
        record(status_log, f"EXIT native_pid={child.pid} return_code={return_code} signal=none")
        return return_code
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    installer = subcommands.add_parser("install")
    installer.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    installer.add_argument("--status-log", type=Path, default=Path("ui-emulator-status.log"))
    runner = subcommands.add_parser("run")
    runner.add_argument("--native", type=Path, required=True)
    runner.add_argument("--status-log", type=Path, required=True)
    runner.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == "install":
        if args.sdk is None:
            parser.error("--sdk or ANDROID_HOME is required")
        install(args.sdk, args.status_log)
        return 0
    arguments = args.arguments
    if arguments and arguments[0] == "--":
        arguments = arguments[1:]
    return monitor(args.native, args.status_log, arguments)


if __name__ == "__main__":
    raise SystemExit(main())
