#!/usr/bin/env python3
"""Prints the release notes of a fork release: how to install it, then every commit since the previous
release (or since upstream for the first one), grouped by conventional-commit type.

Settings come from the environment, set by .github/workflows/release.yml:
REPO, VERSION, BASE, TAG, PREVIOUS (empty for the first release), UPSTREAM_BASE, CERT_SHA256,
APK_SHA256, EXTRA_NOTES, FORK_EMAIL (commits by other authors are listed as upstream changes).
"""
import os
import re
import subprocess

env = os.environ
repo, version, base, tag = env["REPO"], env["VERSION"], env["BASE"], env["TAG"]
previous, upstream_base = env.get("PREVIOUS", ""), env.get("UPSTREAM_BASE", "")
start = previous or upstream_base
log = subprocess.run(["git", "log", "--no-merges", "--reverse", "--format=%H%x09%ae%x09%s",
                      f"{start}..{env.get('HEAD_REF', 'HEAD')}"],
                     check=True, capture_output=True, text=True).stdout

GROUPS = {"feat": "New and improved", "fix": "Fixes", "perf": "Performance", "other": "Other changes",
          "upstream": "From upstream InstaEclipse"}
SUBJECT = re.compile(r"^(\w+)(?:\(([^)]*)\))?!?:\s*(.+)$")
entries = {key: [] for key in GROUPS}
for line in log.splitlines():
    sha, email, subject = line.split("\t", 2)
    link = f"([{sha[:7]}](https://github.com/{repo}/commit/{sha}))"
    if email != env.get("FORK_EMAIL", email):
        entries["upstream"].append(f"- {subject} {link}")
        continue
    m = SUBJECT.match(subject)
    kind, scope, text = (m.group(1), m.group(2), m.group(3)) if m else ("other", None, subject)
    entries[kind if kind in entries else "other"].append(f"- {f'**{scope}:** ' if scope else ''}{text} {link}")

since = f"[{previous}](https://github.com/{repo}/compare/{previous}...{tag})" if previous else \
    f"upstream InstaEclipse {base} ([compare](https://github.com/{repo}/compare/{upstream_base}...{tag}))"
out = [f"InstaEclipse **{version}**, the LumixCode fork of "
       f"[InstaEclipse](https://github.com/ReSo7200/InstaEclipse) {base}.", ""]
if env.get("EXTRA_NOTES", "").strip():
    out += [env["EXTRA_NOTES"].strip(), ""]
out += [
    "### Install",
    "",
    f"- Download `InstaEclipse-{version}.apk` below. It's built for the new Xposed API (102): "
    "JingMatrix LSPatch 1.0 or newer (no root), or Vector 2.2 or newer (root).",
    "- It's signed with this fork's own key, so it installs over earlier releases of this fork, but not over "
    "the official InstaEclipse or a build you signed yourself. Back up your settings in the companion app, "
    "uninstall the old app, install this one and restore the backup.",
    "- Module embedded in a patched Instagram (LSPatch)? Patch Instagram again with this APK.",
    "",
    "| | |",
    "|---|---|",
    f"| Signing certificate SHA-256 | `{env['CERT_SHA256']}` |",
    f"| APK SHA-256 | `{env['APK_SHA256']}` |",
    "",
    f"### Changes since {since}",
]
for key, title in GROUPS.items():
    if entries[key]:
        out += ["", f"#### {title}", "", *entries[key]]
if not any(entries.values()):
    out += ["", "No code changes."]
print("\n".join(out))
