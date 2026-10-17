"""Builds the README images, banner included, from screenshots.

    python tools/readme_images.py <folder of screenshots>

Expects these names, taken on a real phone with the Desktop Head Unit (tools/dhu, the Curvv
preset, cropped to its 1920x720 panel) or on an emulator following docs/testing.md:
  car-home, car-player, car-fullscreen, car-remote   the 1920x720 car display (the Curvv's panel)
  phone-home, phone-player, phone-driving, phone-parked, phone-remote
Phone shots lose their status bar, so no notification ever reaches the page.
Writes docs/readme/*.png. Needs Pillow, and Playwright with Microsoft Edge for the banner.
"""
import os
import sys
from pathlib import Path

from PIL import Image

src = Path(sys.argv[1])
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "readme"
OUT.mkdir(parents=True, exist_ok=True)
CANVAS = (8, 9, 11)  # the app's canvas, so gaps read as part of the screen
# The phone's status bar (1080-wide shots), cropped off every phone screenshot.
STATUS_BAR = 100


def load(name):
    return Image.open(src / f"{name}.png").convert("RGB")


def car(name, width=1600):
    """A car-display shot. Emulator overlay shots lose their 30 px system strip."""
    im = load(name)
    if im.height > 720:
        im = im.crop((0, 0, im.width, 690))
    return im.resize((width, round(690 * width / im.width)), Image.LANCZOS)


def row(images, height, gap=28):
    scaled = [im.resize((round(im.width * height / im.height), height), Image.LANCZOS) for im in images]
    sheet = Image.new("RGB", (sum(i.width for i in scaled) + gap * (len(scaled) - 1), height), CANVAS)
    x = 0
    for im in scaled:
        sheet.paste(im, (x, 0))
        x += im.width + gap
    return sheet


for name in ("car-home", "car-player", "car-fullscreen"):
    car(name).save(OUT / f"{name}.png", optimize=True)

def phone(name, bottom=None):
    im = load(name)
    return im.crop((0, round(STATUS_BAR * im.width / 1080), im.width, bottom or im.height))


row([phone(n, 2000) for n in ("phone-home", "phone-player", "phone-driving", "phone-parked")], 1000).save(OUT / "phone.png", optimize=True)

phone = phone("phone-remote", 1700)
row([car("car-remote"), phone], 820).save(OUT / "remote.png", optimize=True)

ICON = ROOT / "tools" / "icon" / "svg" / "dwellplay-icon.svg"
HTML = f"""<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;800&display=swap" rel="stylesheet">
<style>
* {{ margin: 0; box-sizing: border-box; }}
body {{ width: 1600px; height: 820px; overflow: hidden; color: #f2f2f2; font-family: Inter, sans-serif;
  background: radial-gradient(110% 95% at 80% 45%, #1d1013 0%, #0e0d10 50%, #08090b 100%); }}
.wrap {{ display: flex; align-items: center; height: 100%; padding: 0 44px 0 64px; gap: 48px; }}
.text {{ flex: 1; }}
.icon {{ width: 128px; height: 128px; border-radius: 30px; display: block; margin-bottom: 34px; box-shadow: 0 18px 40px rgba(0,0,0,.55); }}
h1 {{ font-weight: 800; font-size: 84px; line-height: .95; letter-spacing: -2px; }}
.lead {{ font-size: 30px; line-height: 1.3; color: #d6d6da; margin-top: 22px; max-width: 470px; }}
.meta {{ font-size: 20px; line-height: 1.6; color: #8d8f97; margin-top: 30px; }}
.meta b {{ font-weight: 600; color: #b9bbc2; }}
.cars {{ display: flex; flex-direction: column; gap: 26px; }}
.car {{ width: 960px; height: 345px; border-radius: 18px; overflow: hidden; display: block; object-fit: cover; object-position: top;
  border: 1px solid rgba(255,255,255,.09); box-shadow: 0 24px 50px rgba(0,0,0,.6); }}
</style></head><body><div class="wrap">
  <div class="text">
    <img class="icon" src="{ICON.as_uri()}">
    <h1>DwellPlay</h1>
    <p class="lead">Your videos on the car's big screen <span style="white-space: nowrap">while you're parked.</span></p>
    <p class="meta"><b>Android Auto</b> and <b>Android Automotive</b><br>Phone videos, YouTube, Jellyfin, streams</p>
  </div>
  <div class="cars">
    <img class="car" src="{(OUT / 'car-home.png').as_uri()}">
    <img class="car" src="{(OUT / 'car-player.png').as_uri()}">
  </div>
</div></body></html>"""

page = OUT / "_hero.html"
page.write_text(HTML, encoding="utf-8")
try:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge")
        tab = browser.new_page(viewport={"width": 1600, "height": 820})
        tab.goto(page.as_uri())
        tab.wait_for_load_state("networkidle")
        tab.evaluate("document.fonts.ready")
        tab.screenshot(path=str(OUT / "hero.png"))
        browser.close()
finally:
    page.unlink(missing_ok=True)
print("wrote", sorted(os.listdir(OUT)))
