#!/usr/bin/env python3
"""Export GraphicSessionInstallPlan.kt package plans as a simple TSV catalog.

The rootfs compatibility workflow consumes this catalog instead of maintaining a
second hand-written copy of package lists. The parser is intentionally strict:
if the Kotlin plan shape changes and a plan can no longer be understood, CI
fails rather than silently dropping coverage.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_SOURCE = Path(
    "app/src/main/java/com/saas/x11manager/util/GraphicSessionInstallPlan.kt"
)
CALL_RE = re.compile(r"\b(apt|apk)\s*\(")
SESSION_RE = re.compile(r"GraphicSession\.([A-Z0-9_]+)")
LIST_RE = re.compile(r"\blistOf\s*\(")
STRING_RE = re.compile(r'"((?:\\.|[^"\\])*)"')


def matching_paren(text: str, open_index: int) -> int:
    if open_index >= len(text) or text[open_index] != "(":
        raise ValueError("matching_paren() requires an opening parenthesis")

    depth = 0
    quote: str | None = None
    escaped = False
    line_comment = False
    block_comment = False
    i = open_index

    while i < len(text):
        char = text[i]
        next_char = text[i + 1] if i + 1 < len(text) else ""

        if line_comment:
            if char == "\n":
                line_comment = False
            i += 1
            continue

        if block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                i += 2
            else:
                i += 1
            continue

        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            i += 1
            continue

        if char == "/" and next_char == "/":
            line_comment = True
            i += 2
            continue
        if char == "/" and next_char == "*":
            block_comment = True
            i += 2
            continue
        if char in ('"', "'"):
            quote = char
            i += 1
            continue
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1

    raise ValueError(f"unterminated parenthesized expression at offset {open_index}")


def decode_kotlin_string(raw: str) -> str:
    # Package names currently use ordinary quoted Kotlin strings. JSON decoding
    # gives us strict handling for the escape sequences used by those strings.
    return json.loads(f'"{raw}"')


def parse_plans(source: str) -> list[tuple[str, str, list[str]]]:
    marker = "private val plans = listOf("
    marker_index = source.find(marker)
    if marker_index < 0:
        raise ValueError("could not locate GraphicSessionInstallPlans.plans")

    region = source[marker_index + len(marker) :]
    end_marker = "\n    fun forSelection("
    end_index = region.find(end_marker)
    if end_index < 0:
        raise ValueError("could not locate end of GraphicSessionInstallPlans.plans")
    region = region[:end_index]

    matches = list(CALL_RE.finditer(region))
    if not matches:
        raise ValueError("no apt()/apk() install plans found")

    plans: list[tuple[str, str, list[str]]] = []
    seen: set[tuple[str, str]] = set()

    for match in matches:
        platform = match.group(1)
        open_index = match.end() - 1
        close_index = matching_paren(region, open_index)
        call = region[match.start() : close_index + 1]

        session_match = SESSION_RE.search(call)
        if session_match is None:
            raise ValueError(f"{platform} plan is missing a GraphicSession selector: {call[:120]!r}")
        session = session_match.group(1)

        list_match = LIST_RE.search(call)
        if list_match is None:
            raise ValueError(f"{platform}/{session} plan is missing listOf(packages)")
        list_open = list_match.end() - 1
        list_close = matching_paren(call, list_open)
        package_expression = call[list_open + 1 : list_close]
        packages = [decode_kotlin_string(raw) for raw in STRING_RE.findall(package_expression)]
        if not packages:
            raise ValueError(f"{platform}/{session} has no package names")
        if any(any(char.isspace() for char in package) or "\t" in package for package in packages):
            raise ValueError(f"{platform}/{session} contains a package name unsafe for TSV export")

        key = (platform, session)
        if key in seen:
            raise ValueError(f"duplicate install plan for {platform}/{session}")
        seen.add(key)
        plans.append((platform, session, packages))

    if len(plans) != len(matches):
        raise ValueError("not every apt()/apk() plan was exported")
    if not {platform for platform, _, _ in plans}.issuperset({"apt", "apk"}):
        raise ValueError("catalog must contain both apt and apk plans")

    return plans


def render_tsv(plans: list[tuple[str, str, list[str]]]) -> str:
    return "".join(
        f"{platform}\t{session}\t{' '.join(packages)}\n"
        for platform, session, packages in plans
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    try:
        plans = parse_plans(args.source.read_text(encoding="utf-8"))
        rendered = render_tsv(plans)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.output is None:
        sys.stdout.write(rendered)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")

    apt_count = sum(1 for platform, _, _ in plans if platform == "apt")
    apk_count = sum(1 for platform, _, _ in plans if platform == "apk")
    print(
        f"exported {len(plans)} plans ({apt_count} apt, {apk_count} apk)",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
