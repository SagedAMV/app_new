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


def normalize_digest(value):
    """Accept a SHA-256 digest with or without separators; compare one canonical form."""
    return "".join(character for character in str(value).lower() if character in "0123456789abcdef")


# apksigner writes the same certificate under several labels depending on the build-tools
# release and the signature scheme found in the APK: "Signer #1 certificate SHA-256 digest: …"
# for v1, but "V2 Signer: certificate SHA-256 digest: …" for v2/v3 — and a report also lists
# public-key and source-stamp digests, which Android never checks at install time. Only the
# certificate line identifies the key, so it is matched by the "certificate … SHA-256" pair
# and canonicalised instead of by one fixed label.
CERTIFICATE_DIGEST = re.compile(r"\bSHA[-_ ]?256\b[^\n]{0,20}?([0-9a-fA-F:]{60,100})", re.IGNORECASE)
# احتياط: بعض الإصدارات تكسر السطر بعد الوسم وتُدرج البصمة في السطر التالي.
WRAPPED_DIGEST = re.compile(
    r"(?:Signer #\d+|V\d+ Signer)[\s\S]{0,120}?\bcertificate\b[\s\S]{0,120}?\bSHA[-_ ]?256\b[\s\S]{0,60}?"
    r"([0-9a-fA-F:]{60,100})",
    re.IGNORECASE,
)


def certificate_digest_from_report(report):
    """SHA-256 of the signing certificate named by apksigner, or None when unreported."""
    standalone = None
    for line in report.splitlines():
        folded = line.lower()
        if "certificate" not in folded or "public key" in folded or "source stamp" in folded:
            continue
        match = CERTIFICATE_DIGEST.search(line)
        if match is None:
            continue
        digest = normalize_digest(match.group(1))
        if len(digest) != 64:
            continue
        if "signer" in folded:
            return digest
        standalone = standalone or digest
    if standalone is not None:
        return standalone
    match = WRAPPED_DIGEST.search(report)
    if match is not None:
        digest = normalize_digest(match.group(1))
        if len(digest) == 64:
            return digest
    return None


def apk_signer_digest(apksigner, apk):
    """SHA-256 of the APK signing certificate, read from apksigner's own report.

    The report is read from both streams and matched case-insensitively: the exact spelling
    ("SHA-256" vs "SHA256"), the stream, and the separator style differ between
    build-tools releases, and a mismatch there must not be mistaken for a wrong key.
    """
    completed = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True
    )
    report = "\n".join(
        part for part in (completed.stdout, completed.stderr) if isinstance(part, str) and part
    )
    digest = certificate_digest_from_report(report)
    if digest is None:
        raise ValueError(
            "apksigner did not report a SHA-256 certificate digest for signer #1. "
            f"Report was: {report.strip()[:800] or '(empty)'}"
        )
    return digest


def require_expected_signer(apksigner, apk, expected):
    """Fail loudly when the signing key slipped back to an ephemeral machine key.

    Every GitHub Actions runner used to generate its own ~/.android/debug.keystore, so each
    test APK was signed by a different certificate and Android refused to install it over the
    previous one (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Pinning the digest in CI keeps that
    user-visible failure from shipping again.
    """
    wanted = normalize_digest(expected)
    if len(wanted) != 64:
        raise ValueError("The expected signer digest must be a SHA-256 certificate digest")
    actual = apk_signer_digest(apksigner, apk)
    if actual != wanted:
        raise ValueError(
            "The test APK was signed by a different key than the one committed in the repository"
            f" (expected {wanted}, found {actual}). Devices cannot install an APK signed by a new"
            " key over an installed one; check app/keystore/unihub-test.jks and its Gradle signing config."
        )
    return actual


def verify(variant, require_signed=False, require_signer=None, root=ROOT):
    if variant not in ("debug", "release", "releaseFull"):
        raise ValueError(f"Unsupported build variant: {variant}")
    apk, version = apk_from_metadata(root / "app/build/outputs/apk" / variant)
    unsigned = apk.name.endswith("-unsigned.apk")
    if require_signed and unsigned:
        raise ValueError("Release APK is unsigned. Configure keystore.properties before building a signed release.")
    if require_signer and unsigned:
        raise ValueError("An unsigned APK has no signing certificate to verify")
    tools = build_tools(sdk_directory(root))
    subprocess.run([str(tools / "zipalign"), "-c", "4", str(apk)], check=True)
    if not unsigned:
        # A file with a signed name but a bad/missing signature must fail, not fall back.
        subprocess.run([str(tools / "apksigner"), "verify", str(apk)], check=True)
    signer = require_expected_signer(tools / "apksigner", apk, require_signer) if require_signer else ""
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    status = "unsigned (sign before installing)" if unsigned else "signed and verified"
    print(f"APK: {apk.relative_to(root)}\nVersion: {version}\nStatus: {status}\nSHA-256: {digest.hexdigest()}"
          + (f"\nSigner certificate SHA-256: {signer}" if signer else ""))
    annotation("notice", f"{variant} APK", f"{status} (signer {signer})" if signer else status)
    outputs = os.environ.get("GITHUB_OUTPUT")
    if outputs:
        with Path(outputs).open("a", encoding="utf-8") as stream:
            stream.write(f"path={apk.relative_to(root)}\nsigned={str(not unsigned).lower()}\n"
                         f"sha256={digest.hexdigest()}\nsigner={signer}\n")
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
                            ("ui-emulator-status.log", "Android emulator exit"),
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
    verify_parser.add_argument("--require-signer", default=None, metavar="SHA256",
                               help="Fail unless the APK signer certificate digest matches this value")
    report_parser = commands.add_parser("report")
    report_parser.add_argument("--instrumentation", action="store_true")
    report_parser.add_argument("--minimum-tests", type=int, default=1)
    args = parser.parse_args()
    try:
        if args.command == "verify":
            verify(args.variant, args.require_signed, args.require_signer)
        else:
            report(instrumentation=args.instrumentation, minimum_tests=args.minimum_tests)
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        annotation("error", "Android verification", error)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
