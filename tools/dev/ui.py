#!/usr/bin/env python3
"""Ajuda a dirigir o app no emulador: ui.py dump | tap <texto> | field <n> | type <texto>."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout

def nodes():
    for _ in range(3):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = sh("cat", "/sdcard/ui.xml")
        if xml.strip().startswith("<?xml"):
            return list(ET.fromstring(xml).iter("node"))
        time.sleep(1)
    return []

def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2

cmd = sys.argv[1]
if cmd == "dump":
    for n in nodes():
        label = n.get("text") or n.get("content-desc")
        kind = n.get("class").split(".")[-1]
        if label or kind == "EditText":
            flags = "".join(f for f, k in (("C", "checked"), ("S", "selected")) if n.get(k) == "true")
            print(f"{kind:12} {flags:2} {label[:90]!r} {n.get('bounds')}")
elif cmd == "tap":
    want = sys.argv[2]
    for n in nodes():
        if want in (n.get("text") or "") or want in (n.get("content-desc") or ""):
            x, y = center(n); sh("input", "tap", str(x), str(y)); print("tap", want, x, y); break
    else:
        sys.exit(f"não achei: {want}")
elif cmd == "tapx":
    want = sys.argv[2]
    for n in nodes():
        if want == (n.get("text") or "") or want == (n.get("content-desc") or ""):
            x, y = center(n); sh("input", "tap", str(x), str(y)); print("tap", want, x, y); break
    else:
        sys.exit(f"não achei: {want}")
elif cmd == "field":
    fields = [n for n in nodes() if n.get("class").endswith("EditText")]
    x, y = center(fields[int(sys.argv[2])]); sh("input", "tap", str(x), str(y)); print("field", x, y)
elif cmd == "type":
    sh("input", "text", sys.argv[2].replace(" ", "%s"))
time.sleep(0.6)
