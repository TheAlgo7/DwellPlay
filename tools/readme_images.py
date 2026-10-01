"""Builds the README images from emulator screenshots.

    python tools/readme_images.py <folder of screenshots>

Expects the names tools/shot.sh was given while following docs/testing.md:
  car-home, car-player                     the 1920x720 car display (the Curvv's panel size)
  aaos-driving, aaos-resume                Android Automotive OS: Drive View, then back in DwellPlay
  phone-home, phone-player, phone-driving, phone-parked
Writes docs/readme/*.png. Needs Pillow.

The two android-auto-*.png images are not made here: they are Desktop Head Unit screenshots
(docs/android-auto.md), cropped to the car's visible band (y 180 to 900) and scaled to 1600 wide.
"""
import os
import sys

from PIL import Image

src = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "readme")
os.makedirs(out, exist_ok=True)
GAP = (11, 13, 16)  # the app's canvas colour, so gaps read as part of the screen


def load(name):
    return Image.open(os.path.join(src, name + ".png")).convert("RGB")


def car(name):
    """A car-display shot without the simulator's 30 px system strip, 1600 wide."""
    im = load(name)
    im = im.crop((0, 0, im.width, 690))
    return im.resize((1600, round(690 * 1600 / im.width)), Image.LANCZOS)


def row(images, height, gap=24):
    scaled = [im.resize((round(im.width * height / im.height), height), Image.LANCZOS) for im in images]
    width = sum(im.width for im in scaled) + gap * (len(scaled) - 1)
    sheet = Image.new("RGB", (width, height), GAP)
    x = 0
    for im in scaled:
        sheet.paste(im, (x, 0))
        x += im.width + gap
    return sheet


for name in ("car-home", "car-player"):
    car(name).save(os.path.join(out, name + ".png"), optimize=True)

row([load("aaos-driving"), load("aaos-resume")], 540).save(os.path.join(out, "automotive-drive-park.png"), optimize=True)
row([load(n) for n in ("phone-home", "phone-player", "phone-driving", "phone-parked")], 1000).save(
    os.path.join(out, "phone.png"), optimize=True
)
print("wrote", sorted(os.listdir(out)))
