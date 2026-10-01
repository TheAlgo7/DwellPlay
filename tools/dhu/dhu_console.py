"""Runs the Android Auto Desktop Head Unit with the Curvv preset and a controllable console.

    python tools/dhu/dhu_console.py [config.ini]

Commands are appended as lines to C:/tmp/dhu-cmd.txt; this script forwards new lines to the DHU's
stdin, so the DHU can be driven without moving the mouse or stealing window focus. DHU output goes
to C:/tmp/dhu-out.txt. Useful lines for DwellPlay:

    tap 400 300                      touch the car screen
    screenshot C:/tmp/dwellplay.png  save what the car shows
    restrict all                     the car is driving: parked apps must disappear
    restrict none                    parked again
    gear 100 / gear 101              drive / park
    speed 10 / speed 0               metres per second
    parking_brake true

(Same helper as Overworld Maps, with the config as an argument.)
"""
import os
import subprocess
import sys
import threading
import time

DHU_DIR = os.path.join(os.environ["LOCALAPPDATA"], r"Android\Sdk\extras\google\auto")
CONFIG = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "tata_curvv.ini")
CMD = r"C:\tmp\dhu-cmd.txt"
OUT = r"C:\tmp\dhu-out.txt"

open(CMD, "w").close()
out = open(OUT, "w", buffering=1, encoding="utf-8", errors="replace")
proc = subprocess.Popen(
    [os.path.join(DHU_DIR, "desktop-head-unit.exe"), "-c", CONFIG],
    cwd=DHU_DIR,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
)


def pump():
    for line in proc.stdout:
        out.write(line)


threading.Thread(target=pump, daemon=True).start()

seen = 0
while proc.poll() is None:
    with open(CMD, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for line in lines[seen:]:
        out.write(f">>> {line}\n")
        proc.stdin.write(line + "\n")
        proc.stdin.flush()
    seen = len(lines)
    time.sleep(0.2)
out.write(f"DHU exited {proc.returncode}\n")
sys.exit(0)
