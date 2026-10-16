#!/usr/bin/env python3
"""Lightweight static guard: visible action labels and user-facing errors stay concise."""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app/src/main/java/com/unihub/app"


def mask_comments(source: str) -> str:
    """Replace Kotlin comments with spaces without disturbing source offsets."""
    out = list(source)
    i = 0
    state = "code"
    block_depth = 0
    while i < len(source):
        if state == "code":
            if source.startswith("//", i):
                out[i] = out[i + 1] = " "
                i += 2
                state = "line"
                continue
            if source.startswith("/*", i):
                out[i] = out[i + 1] = " "
                i += 2
                state = "block"
                block_depth = 1
                continue
            if source.startswith('"""', i):
                i += 3
                state = "raw"
                continue
            if source[i] == '"':
                i += 1
                state = "string"
                continue
            if source[i] == "'":
                i += 1
                state = "char"
                continue
            i += 1
        elif state == "line":
            if source[i] == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
        elif state == "block":
            if source.startswith("/*", i):
                out[i] = out[i + 1] = " "
                i += 2
                block_depth += 1
            elif source.startswith("*/", i):
                out[i] = out[i + 1] = " "
                i += 2
                block_depth -= 1
                if block_depth == 0:
                    state = "code"
            else:
                if source[i] != "\n":
                    out[i] = " "
                i += 1
        elif state == "raw":
            if source.startswith('"""', i):
                i += 3
                state = "code"
            else:
                i += 1
        else:
            if source[i] == "\\":
                i += 2
            elif (state == "string" and source[i] == '"') or (state == "char" and source[i] == "'"):
                i += 1
                state = "code"
            else:
                i += 1
    return "".join(out)


def matching(source: str, start: int, opening: str, closing: str) -> int | None:
    """Find matching delimiter, skipping strings and comments."""
    depth = 0
    i = start
    state = "code"
    block_depth = 0
    while i < len(source):
        if state == "code":
            if source.startswith("//", i):
                state = "line"
                i += 2
            elif source.startswith("/*", i):
                state = "block"
                block_depth = 1
                i += 2
            elif source.startswith('"""', i):
                state = "raw"
                i += 3
            elif source[i] == '"':
                state = "string"
                i += 1
            elif source[i] == "'":
                state = "char"
                i += 1
            else:
                if source[i] == opening:
                    depth += 1
                elif source[i] == closing:
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
        elif state == "line":
            state = "code" if source[i] == "\n" else state
            i += 1
        elif state == "block":
            if source.startswith("/*", i):
                block_depth += 1
                i += 2
            elif source.startswith("*/", i):
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "code"
            else:
                i += 1
        elif state == "raw":
            if source.startswith('"""', i):
                state = "code"
                i += 3
            else:
                i += 1
        else:
            if source[i] == "\\":
                i += 2
            elif (state == "string" and source[i] == '"') or (state == "char" and source[i] == "'"):
                state = "code"
                i += 1
            else:
                i += 1
    return None


def word_count(value: str) -> int:
    # Dynamic IDs, usernames, filenames, and counts are data, not label wording.
    value = re.sub(r"\$\{[^}]*\}|\$[A-Za-z_][\w.]*", " ", value)
    # Count actual letters/digits; ignore punctuation and decorative emoji.
    value = re.sub(r"\b\d+(?:[./:-]\d+)+\b", "version", value)
    count = 0
    in_word = False
    for char in value:
        category = unicodedata.category(char)
        is_word = category.startswith(("L", "N")) or (category.startswith("M") and in_word) or char == "_"
        if is_word and not in_word:
            count += 1
        in_word = is_word
    return count


def unescape(value: str) -> str:
    return value.replace("\\n", " ").replace("\\\"", '"').strip()


def first_kotlin_string(source: str, start: int) -> tuple[str, int] | None:
    """Read a normal Kotlin string, omitting interpolated expressions from its copy."""
    while start < len(source) and source[start].isspace():
        start += 1
    if start >= len(source) or source[start] != '"':
        return None
    value: list[str] = []
    i = start + 1
    while i < len(source):
        if source[i] == "\\":
            if i + 1 < len(source):
                value.append(source[i + 1])
            i += 2
        elif source.startswith("${", i):
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source[i] in "\"'":
                    quote = source[i]
                    i += 1
                    while i < len(source):
                        if source[i] == "\\":
                            i += 2
                        elif source[i] == quote:
                            i += 1
                            break
                        else:
                            i += 1
                elif source[i] == "{":
                    depth += 1
                    i += 1
                elif source[i] == "}":
                    depth -= 1
                    i += 1
                else:
                    i += 1
            value.append(" ")
        elif source[i] == "$":
            value.append(" ")
            i += 1
            while i < len(source) and (source[i].isalnum() or source[i] in "_."):
                i += 1
        elif source[i] == '"':
            return "".join(value), i + 1
        else:
            value.append(source[i])
            i += 1
    return None


def kotlin_strings(source: str):
    """Yield normal Kotlin literals and their offsets, skipping interpolation code."""
    i = 0
    while i < len(source):
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] == '"':
            literal = first_kotlin_string(source, i)
            if literal:
                value, end = literal
                yield i, value
                i = end
            else:
                i += 1
        else:
            i += 1


def gather_checks(path: Path, source: str) -> list[tuple[int, str, str]]:
    code = mask_comments(source)
    checks: list[tuple[int, str, str]] = []

    def add(offset: int, text: str, kind: str) -> None:
        text = unescape(text)
        if text:
            checks.append((source.count("\n", 0, offset) + 1, text, kind))

    # Form labels, screen/dialog titles, action descriptions, and accessibility names.
    field_rx = re.compile(
        r"\b(?:title|label|placeholder|contentDescription|fieldLabel|confirmLabel)"
        r"\s*=\s*(?:\{\s*Text\s*\(\s*)?\"((?:\\.|[^\"\\])*)\""
    )
    for match in field_rx.finditer(code):
        add(match.start(), match.group(1), "UI label")

    # Text inside standard clickable controls and menu items.
    control_rx = re.compile(
        r"\b(?:Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|"
        r"DropdownMenuItem|FilterChip|AssistChip)\s*\("
    )
    text_rx = re.compile(r"\bText\s*\(\s*\"((?:\\.|[^\"\\])*)\"")
    for call in control_rx.finditer(code):
        open_paren = code.find("(", call.start())
        close_paren = matching(source, open_paren, "(", ")")
        if close_paren is None:
            continue
        end = close_paren + 1
        while end < len(source) and source[end].isspace():
            end += 1
        if end < len(source) and source[end] == "{":
            close_brace = matching(source, end, "{", "}")
            if close_brace is not None:
                end = close_brace + 1
        segment = source[call.start():end]
        for text_match in text_rx.finditer(segment):
            add(call.start() + text_match.start(), text_match.group(1), "control label")

    # Project-specific action rows expose their text through named or positional arguments.
    action_rx = re.compile(r"\b(?:CloudMenuActionRow|DestinationModeRow)\s*\(")
    string_rx = re.compile(r"\"((?:\\.|[^\"\\])*)\"")
    for call in action_rx.finditer(code):
        open_paren = code.find("(", call.start())
        close_paren = matching(source, open_paren, "(", ")")
        if close_paren is None:
            continue
        segment = source[call.start():close_paren + 1]
        # Only check text= values for menu rows; DestinationModeRow uses its first string argument.
        if "CloudMenuActionRow" in call.group(0):
            match = re.search(r"\btext\s*=\s*\"((?:\\.|[^\"\\])*)\"", segment)
            if match:
                add(call.start() + match.start(), match.group(1), "action label")
        else:
            match = string_rx.search(segment)
            if match:
                add(call.start() + match.start(), match.group(1), "action label")

    # Bottom-navigation items render their first argument as both label and a11y name.
    for call in re.finditer(r"\bBottomNavItem\s*\(", code):
        open_paren = code.find("(", call.start())
        literal = first_kotlin_string(source, open_paren + 1)
        if literal:
            add(open_paren + 1, literal[0], "navigation label")

    # Standalone headings and error-colored text are labels even outside a Button.
    for call in re.finditer(r"\bText\s*\(", code):
        open_paren = code.find("(", call.start())
        close_paren = matching(source, open_paren, "(", ")")
        if close_paren is None:
            continue
        literal = first_kotlin_string(source, open_paren + 1)
        if not literal:
            continue
        args = source[open_paren + 1:close_paren]
        is_heading = re.search(r"\bstyle\s*=\s*MaterialTheme\.typography\.(?:title|label)", args)
        is_error = re.search(r"\bcolor\s*=\s*MaterialTheme\.colorScheme\.error", args)
        if is_heading or is_error:
            add(open_paren + 1, literal[0], "heading/error label")

    # Direct error notifications are intentionally concise and stable across screens.
    notify_rx = re.compile(r"\bnotifyError\s*\(")
    for call in notify_rx.finditer(code):
        open_paren = code.find("(", call.start())
        close_paren = matching(source, open_paren, "(", ")")
        if close_paren is None:
            continue
        segment = source[open_paren + 1:close_paren]
        for match in string_rx.finditer(segment):
            add(open_paren + 1 + match.start(), match.group(1), "error message")

    # Error lexicons and inline auth/file-open errors are user-facing by contract.
    if path.name in {
        "AuthInteractionRules.kt", "FileOpener.kt", "CloudFailureMessages.kt",
        "CloudBrowseModels.kt", "CloudFileRules.kt", "CloudScanController.kt",
        "CloudStorageModels.kt", "InputValidator.kt",
    }:
        for offset, value in kotlin_strings(code):
            if re.search(r"[\u0600-\u06ff]", value):
                add(offset, value, "error copy")
    if path.name == "UserInputRules.kt":
        error_section = re.search(
            r"\bfun\s+lectureFormError\b.*?(?=\n\s*(?:/\*\*|fun\s)|\Z)",
            code,
            re.S,
        )
        if error_section:
            for offset, value in kotlin_strings(error_section.group(0)):
                if re.search(r"[\u0600-\u06ff]", value):
                    add(error_section.start() + offset, value, "validation error")
    return checks


def main() -> int:
    violations = []
    total = 0
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        for line, value, kind in gather_checks(path, source):
            total += 1
            count = word_count(value)
            if count > 3:
                violations.append(f"{path.relative_to(ROOT)}:{line}: {kind} ({count} words): {value}")
    strings_xml = ROOT / "app/src/main/res/values/strings.xml"
    if strings_xml.exists():
        import xml.etree.ElementTree as ET

        resource_root = ET.parse(strings_xml).getroot()
        for resource in resource_root.findall("string"):
            value = "".join(resource.itertext()).strip()
            if value:
                total += 1
                count = word_count(value)
                if count > 3:
                    violations.append(
                        f"{strings_xml.relative_to(ROOT)}:{resource.get('name')}: "
                        f"string resource ({count} words): {value}"
                    )
    if violations:
        print("Overlong UI labels/messages (maximum 3 words):")
        print("\n".join(violations))
        return 1
    print(f"PASS: checked {total} UI labels and user-facing errors; all are at most 3 words.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
