#!/usr/bin/env python3
"""Verify AGP APK outputs and surface Android build diagnostics in GitHub Actions."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def apk_from_metadata(output_dir):
    """AGP names unsigned releases differently; its metadata is the source of truth."""
    metadata = json.loads((output_dir / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Expected exactly one universal APK in AGP output metadata")
    name = elements[0].get("outputFile", "")
    if not name or Path(name).name != name or not name.endswith(".apk"):
        raise ValueError("Invalid APK filename in AGP output metadata")
    apk = output_dir / name
    if apk.resolve().parent != output_dir.resolve() or not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError(f"APK is missing, empty, or outside its output directory: {name}")
    return apk, elements[0].get("versionName", "unknown")


def build_tools(sdk):
    """Require the real SDK verification tools instead of silently skipping checks."""
    versions = []
    for directory in (sdk / "build-tools").glob("*"):
        match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", directory.name)
        if match and all((directory / tool).is_file() for tool in ("zipalign", "apksigner")):
            versions.append((tuple(map(int, match.groups())), directory))
    if not versions:
        raise ValueError("No stable Android build-tools with zipalign and apksigner found")
    return max(versions)[1]


def sdk_directory(root=ROOT):
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    properties = root / "local.properties"
    if not value and properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("sdk.dir="):
                value = line.strip().split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                break
    if not value or not Path(value).is_dir():
        raise ValueError("Set ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in local.properties")
    return Path(value)


def annotation(level, title, message):
    # Workflow commands must escape line breaks and percent signs, even in tool output.
    def escape(text):
        return str(text).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    print(f"::{level} title={escape(title).replace(',', '%2C')}::{escape(message)}")


def verify(variant, require_signed=False, root=ROOT):
    if variant not in ("debug", "release", "releaseFull"):
        raise ValueError(f"Unsupported build variant: {variant}")
    apk, version = apk_from_metadata(root / "app/build/outputs/apk" / variant)
    unsigned = apk.name.endswith("-unsigned.apk")
    if require_signed and unsigned:
        raise ValueError("Release APK is unsigned. Configure keystore.properties before building a signed release.")
    tools = build_tools(sdk_directory(root))
    subprocess.run([str(tools / "zipalign"), "-c", "4", str(apk)], check=True)
    if not unsigned:
        # A file with a signed name but a bad/missing signature must fail, not fall back.
        subprocess.run([str(tools / "apksigner"), "verify", str(apk)], check=True)
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    status = "unsigned (sign before installing)" if unsigned else "signed and verified"
    print(f"APK: {apk.relative_to(root)}\nVersion: {version}\nStatus: {status}\nSHA-256: {digest.hexdigest()}")
    annotation("notice", f"{variant} APK", status)
    outputs = os.environ.get("GITHUB_OUTPUT")
    if outputs:
        with Path(outputs).open("a", encoding="utf-8") as stream:
            stream.write(f"path={apk.relative_to(root)}\nsigned={str(not unsigned).lower()}\nsha256={digest.hexdigest()}\n")
    return apk


def test_totals(files):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    failures = []
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
        for case in suite.iter("testcase"):
            for kind in ("failure", "error"):
                for failure in case.findall(kind):
                    message = failure.get("message", "").strip()
                    body = (failure.text or "").strip()
                    details = "\n".join(part for part in (message, body) if part)
                    if not details:
                        details = "The test runner supplied no failure details; inspect the device log."
                    failures.append(f"{case.get('classname')}.{case.get('name')}: {details[:12000]}")
    return totals, failures


def report(root=ROOT, instrumentation=False, minimum_tests=1):
    if instrumentation and minimum_tests < 1:
        raise ValueError("The device test minimum must be positive")
    summary = ["## Android UI verification" if instrumentation else "## Android build verification", ""]
    kind = "Android UI" if instrumentation else "JVM unit"
    if instrumentation:
        test_files = sorted((root / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
    else:
        test_files = sorted((root / "app/build/test-results/testReleaseFullUnitTest").glob("TEST-*.xml"))
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    if test_files:
        totals, failures = test_totals(test_files)
        counts = ", ".join(f"{value} {key}" for key, value in totals.items())
        summary.append(f"- {kind} tests: **{counts}** ({len(test_files)} test classes).")
        annotation("notice", f"{kind} test results", counts)
        for message in failures[:10]:
            annotation("error", f"{kind} test failure", message)
    else:
        summary.append(f"- No {kind} test report was produced; tests are not verified.")

    if not instrumentation:
        lint_file = root / "app/build/reports/lint-results-releaseFull.xml"
        if lint_file.is_file():
            issues = ET.parse(lint_file).getroot().findall("issue")
            errors = [issue for issue in issues if issue.get("severity") in ("Error", "Fatal")]
            warnings = sum(issue.get("severity") == "Warning" for issue in issues)
            summary.append(f"- Android lint: **{len(errors)} errors, {warnings} warnings**.")
            annotation("notice", "Android lint results", f"{len(errors)} errors, {warnings} warnings")
            for issue in errors[:10]:
                location = issue.find("location")
                where = "" if location is None else f"{location.get('file')}:{location.get('line', '?')}: "
                annotation("error", issue.get("id", "Lint error"), where + issue.get("message", ""))
        else:
            summary.append("- No Android lint report was produced; lint is not verified.")

    device_failed = instrumentation and (
        totals["tests"] < minimum_tests or any(totals[key] for key in ("failures", "errors", "skipped")))

    logs = ("ui-tests.log",) if instrumentation else (
        "unit-tests.log", "lint.log", "debug-build.log", "release-build.log", "ui-test-build.log")
    for name in logs:
        path = root / name
        if not path.is_file():
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        if device_failed:
            context = []
            for index, line in enumerate(lines):
                if re.search(r"FAILED|crash|INSTRUMENTATION|test run failed", line, re.IGNORECASE):
                    context.extend(lines[index:index + 8])
            context = list(dict.fromkeys(context))
            if context:
                annotation("warning", "Android test runner context", "\n".join(context[:80])[:12000])
        messages = [line for line in lines if line.startswith("e: ")]
        if "* What went wrong:" in lines:
            start = lines.index("* What went wrong:") + 1
            messages.extend(lines[start:start + 6])
        for message in messages[:10]:
            if message.strip():
                annotation("error", name, message)

    if instrumentation:
        summary.append(f"- Required device coverage: **at least {minimum_tests} executed tests**.")
    if device_failed:
        for name, title in (("ui-emulator.log", "Android emulator process"),
                            ("ui-device-status.log", "Android device connection"),
                            ("ui-host-memory.log", "Android host memory"),
                            ("ui-host-kernel.log", "Android host kernel")):
            path = root / name
            if path.is_file():
                content = path.read_text(encoding="utf-8", errors="replace").strip()
                if content:
                    if name == "ui-emulator.log":
                        content = "\n".join(content.splitlines()[-80:])[-12000:]
                    critical = [line for line in content.splitlines()
                                if re.search(r"out of memory|oom-kill|killed process|segfault", line, re.IGNORECASE)]
                    if name == "ui-host-kernel.log" and critical:
                        annotation("error", title, "\n".join(critical[-60:])[:12000])
                    else:
                        annotation("warning", title, content[:12000])
        logcat = root / "ui-logcat.log"
        if logcat.is_file():
            runtime = [line for line in logcat.read_text(encoding="utf-8", errors="replace").splitlines()
                       if re.search(r"\b[EF]\s+(?:AndroidRuntime|DEBUG|libc)\s*:", line)]
            if any(marker in line for line in runtime
                   for marker in ("FATAL EXCEPTION", "Fatal signal", "Abort message")):
                annotation("error", "Android process crash", "\n".join(runtime[-100:]))

    text = "\n".join(summary) + "\n"
    print(text)
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with Path(summary_file).open("a", encoding="utf-8") as stream:
            stream.write(text)
    if device_failed:
        raise ValueError(f"Android UI verification requires at least {minimum_tests} executed tests "
                         f"with no failures, errors, or skips; got {totals['tests']} tests")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("variant", choices=("debug", "release", "releaseFull"))
    verify_parser.add_argument("--require-signed", action="store_true")
    report_parser = commands.add_parser("report")
    report_parser.add_argument("--instrumentation", action="store_true")
    report_parser.add_argument("--minimum-tests", type=int, default=1)
    args = parser.parse_args()
    try:
        if args.command == "verify":
            verify(args.variant, args.require_signed)
        else:
            report(instrumentation=args.instrumentation, minimum_tests=args.minimum_tests)
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        annotation("error", "Android verification", error)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
