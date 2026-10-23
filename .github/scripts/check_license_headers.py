#!/usr/bin/env python3
import argparse
import os
import sys

HEAD_LINES = 10


def main():
    root_default = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser = argparse.ArgumentParser(description="Check that every listed file still carries its license notice.")
    parser.add_argument("list", nargs="?", help="path|marker list (default: .github/license-headers.txt)")
    parser.add_argument("--root", default=root_default, help="directory the listed paths are relative to")
    args = parser.parse_args()
    list_path = args.list or os.path.join(args.root, ".github", "license-headers.txt")
    problems = []
    checked = 0
    files = set()
    with open(list_path, encoding="utf-8") as f:
        entries = [line.rstrip("\r\n") for line in f]
    for n, entry in enumerate(entries, 1):
        if not entry.strip():
            continue
        path, sep, marker = entry.partition("|")
        if not sep or not path.strip() or not marker.strip():
            problems.append("%s:%d: expected path|marker, got %r" % (list_path, n, entry))
            continue
        path = path.strip()
        marker = marker.strip()
        full = os.path.join(args.root, path)
        try:
            with open(full, encoding="utf-8", errors="replace") as src:
                head = "".join(next(src, "") for _ in range(HEAD_LINES))
        except OSError:
            problems.append("%s is missing" % path)
            continue
        checked += 1
        files.add(path)
        if marker.casefold() not in head.casefold():
            problems.append("%s: '%s' not found in the first %d lines" % (path, marker, HEAD_LINES))
    for p in problems:
        print("FAIL " + p)
    if problems:
        print("%d problem(s)" % len(problems))
        return 1
    print("OK: %d markers in %d files" % (checked, len(files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
