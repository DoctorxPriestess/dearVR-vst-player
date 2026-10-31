#!/usr/bin/env python3
"""Repository style gate.

Checks the rules that are cheap to verify and always unambiguous. It is not a
formatter: it never rewrites source, it only reports (or with --fix, repairs the
purely mechanical items: trailing whitespace, CRLF, missing final newline).

    tools/check_style.py            # check, exit 1 on violations
    tools/check_style.py --fix      # repair mechanical issues in place
"""
from __future__ import annotations

import argparse
import os
import re
import sys

MAX_LINE = 120
# Line length is enforced for the compiled sources only. Scripts and docs have
# legitimately long pipelines, and res/drawable/*.xml is vector path data where a
# single long line is the readable form.
MAX_LINE_SUFFIXES = {".java", ".c", ".h", ".cpp", ".hpp", ".kts"}
# Generated, vendored or binary-heavy trees are out of scope.
SKIP_DIRS = {
    ".git", "_attic", "_notes", "build", "third_party", "test", "dist",
    "assets", "assets_orig", "gradle", "build_payload", "__pycache__",
    # archived trees keep their original formatting on purpose
    "legacy",
}
SUFFIXES = {".java", ".c", ".h", ".cpp", ".hpp", ".kts", ".gradle", ".sh", ".py", ".md", ".xml", ".cmake", ".txt"}
# Paths whose long lines are legitimate (generated tables, embedded data).
LONG_LINE_EXEMPT = {
    os.path.join("android", "app", "src", "main", "java", "com", "vstplayer", "ui", "Icon.java"),
}
WILDCARD_IMPORT = re.compile(r"^\s*import\s+(static\s+)?[A-Za-z0-9_.]+\.\*\s*;")
TAB = re.compile(r"\t")
TRAILING = re.compile(r"[ \t]+$")


def iter_files(root: str):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        for name in files:
            if os.path.splitext(name)[1] in SUFFIXES:
                yield os.path.join(base, name)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true", help="repair mechanical issues in place")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    problems: list[str] = []
    fixed = 0
    for path in sorted(iter_files(root)):
        rel = os.path.relpath(path, root)
        with open(path, "rb") as fh:
            raw = fh.read()
        if b"\0" in raw:
            continue  # binary
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            problems.append(f"{rel}: not valid UTF-8")
            continue

        changed = False
        if "\r\n" in text:
            problems.append(f"{rel}: CRLF line endings (expect LF)")
            if args.fix:
                text = text.replace("\r\n", "\n")
                changed = True
        if text and not text.endswith("\n"):
            problems.append(f"{rel}: missing final newline")
            if args.fix:
                text += "\n"
                changed = True

        long_exempt = rel.replace("/", os.sep) in LONG_LINE_EXEMPT
        for lineno, line in enumerate(text.splitlines(), 1):
            body = line.rstrip("\n")
            if TRAILING.search(body):
                problems.append(f"{rel}:{lineno}: trailing whitespace")
                if args.fix:
                    body = TRAILING.sub("", body)
                    changed = True
            if TAB.search(body):
                problems.append(f"{rel}:{lineno}: tab character (use 4 spaces)")
            if (os.path.splitext(rel)[1] in MAX_LINE_SUFFIXES
                    and len(body) > MAX_LINE and not long_exempt):
                problems.append(f"{rel}:{lineno}: line is {len(body)} chars (max {MAX_LINE})")
            if rel.endswith(".java") and WILDCARD_IMPORT.match(body):
                problems.append(f"{rel}:{lineno}: wildcard import")

        if changed:
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
            fixed += 1

    for item in problems:
        print(item)
    if args.fix:
        print(f"\n{len(problems)} problem(s), {fixed} file(s) rewritten")
    else:
        print(f"\n{len(problems)} problem(s)")
    return 1 if problems and not args.fix else 0


if __name__ == "__main__":
    sys.exit(main())
