#!/usr/bin/env python3
"""Find a UI node in a uiautomator dump and print the tap coordinates of its centre.

Usage: ui-tap.py <dump.xml> <text-regex> [package-substring]
The regex is matched against the node's text and its content description (Compose merges the
texts of clickable rows). "@EditText" matches the first text input field instead.
Exit code 1 if no node matches.
"""
import re
import sys
import xml.etree.ElementTree as ET

dump, pattern = sys.argv[1], re.compile(sys.argv[2])
package = sys.argv[3] if len(sys.argv) > 3 else ""
try:
    root = ET.parse(dump).getroot()
except (ET.ParseError, FileNotFoundError):
    sys.exit(1)
for node in root.iter("node"):
    if package not in node.get("package", ""):
        continue
    if sys.argv[2] == "@EditText":
        hit = "EditText" in node.get("class", "")
    else:
        hit = any(pattern.fullmatch(node.get(attr, "")) for attr in ("text", "content-desc"))
    if hit:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
