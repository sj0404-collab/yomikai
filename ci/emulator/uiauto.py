#!/usr/bin/env python3
"""uiautomator-хелпер: дамп дерева UI, поиск по тексту/content-desc, тапы, скриншоты."""
import re, sys, time, subprocess
import xml.etree.ElementTree as ET
from typing import Optional, Tuple

def sh(cmd, timeout=90):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)

def _parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    x0, y0, x1, y1 = map(int, m.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2

def dump() -> str:
    sh("adb shell uiautomator dump /sdcard/uidump.xml", timeout=60)
    r = sh("adb exec-out cat /sdcard/uidump.xml", timeout=60)
    return r.stdout

def tap(x, y):
    sh(f"adb shell input tap {x} {y}")

def swipe(x0, y0, x1, y1, ms=400):
    sh(f"adb shell input swipe {x0} {y0} {x1} {y1} {ms}")

def nodes_matching(xml_str, pattern, fields=("text", "content-desc", "resource-id")):
    try:
        root = ET.fromstring(xml_str)
    except ET.ParseError:
        return []
    rx = re.compile(pattern, re.IGNORECASE)
    out = []
    for node in root.iter("node"):
        attrs = node.attrib
        for f in fields:
            v = attrs.get(f, "")
            if v and rx.search(v):
                c = _parse_bounds(attrs.get("bounds"))
                if c:
                    out.append((c, f, v))
                break
    return out

def wait_node(pattern, timeout_s=25, fields=("text", "content-desc", "resource-id")):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        x = dump()
        m = nodes_matching(x, pattern, fields)
        if m:
            return m
        time.sleep(1.5)
    return []

def tap_node(pattern, timeout_s=25, index=0, fields=("text", "content-desc", "resource-id")) -> bool:
    m = wait_node(pattern, timeout_s, fields)
    if not m:
        return False
    c = m[min(index, len(m) - 1)][0]
    tap(*c)
    return True

def screencap(path):
    with open(path, "wb") as f:
        r = sh(f"adb exec-out screencap -p", timeout=60)
        f.write(r.stdout.encode("latin-1", "ignore") if isinstance(r.stdout, str) else r.stdout)
    # proper: скрин бинарный — через shell с файлом надёжнее
    sh(f"adb shell screencap -p /sdcard/_cap.png")
    sh(f"adb pull /sdcard/_cap.png '{path}' >/dev/null 2>&1")

def activity_check(pkg):
    r = sh(f"adb shell dumpsys activity activities | grep -m1 \"topResumedActivity\"")
    return r.stdout.strip()

def log(*a):
    print("[uiauto]", *a, flush=True)

if __name__ == "__main__":
    # CLI: dump | tapnode <pattern> | waitnode <pattern> [timeout] | cap <file> | tapxy x y
    cmd = sys.argv[1]
    if cmd == "dump":
        print(dump()[:4000])
    elif cmd == "tapnode":
        ok = tap_node(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 25)
        print("tapnode", sys.argv[2], "->", ok); sys.exit(0 if ok else 1)
    elif cmd == "waitnode":
        ok = bool(wait_node(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 25))
        print("waitnode", sys.argv[2], "->", ok); sys.exit(0 if ok else 1)
    elif cmd == "cap":
        screencap(sys.argv[2]); print("cap", sys.argv[2])
    elif cmd == "tapxy":
        tap(int(sys.argv[2]), int(sys.argv[3])); print("tap", sys.argv[2], sys.argv[3])
