#!/usr/bin/env python3
"""Static guard: every positive device-test expectation still exists in the app.

A stale expectation (`onNodeWithText("...").assertIsDisplayed()`) only fails after the
emulator boots, minutes into CI, and the message does not even name the missing string —
`assertIsDisplayed()` reports "The component is not displayed!" for a node it never found.
That is exactly how the Galaxy empty-state wording drifted away from its device test and
failed the whole run three steps after the unit tests had already gone green.

The guard compares positive expectations against the strings the app really ships:
- `assertDoesNotExist()` is intentionally ignored: those literals describe *removed*
  features (invitations, server keys) and must stay absent.
- Literals containing digits are treated as test data ("مجلد 4999"), not copy.
- Anything else must appear verbatim in app/src/main sources or resources.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEVICE_TESTS = ROOT / "app/src/androidTest"
APP_SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/main/res")
CALL = re.compile(r"onNodeWith(?:Text|Tag)\(\s*\"((?:\\.|[^\"\\\n])*)\"")


def shipped_strings() -> str:
    pool = []
    for folder in APP_SOURCES:
        for path in sorted(folder.rglob("*")):
            if path.suffix in (".kt", ".xml"):
                pool.append(path.read_text(encoding="utf-8"))
    return "\n".join(pool)


def positive_expectations(source: str):
    """Yield (line, literal) for expectations that require the node to be present."""
    for match in CALL.finditer(source):
        literal = match.group(1).replace('\\"', '"')
        line_end = source.find("\n", match.end())
        tail = source[match.end():len(source) if line_end < 0 else line_end]
        # Negative checks describe what must stay absent; only positive ones are compared.
        if "assertDoesNotExist" in tail:
            continue
        if re.search(r"\d", literal) or "$" in literal:
            continue
        yield source.count("\n", 0, match.start()) + 1, literal


def main() -> int:
    pool = shipped_strings()
    violations = []
    checked = 0
    for path in sorted(DEVICE_TESTS.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        for line, literal in positive_expectations(source):
            checked += 1
            if literal not in pool:
                violations.append(f"{path.relative_to(ROOT)}:{line}: {literal}")
    if violations:
        print("Device-test expectations that the app no longer renders:")
        print("\n".join(violations))
        return 1
    print(f"PASS: checked {checked} positive device-test expectations against the shipped copy.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
