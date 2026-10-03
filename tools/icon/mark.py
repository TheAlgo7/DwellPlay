"""
The DwellPlay mark, built from geometry rather than traced: an italic D whose stem stands apart
and whose counter is a play button. Every edge is a straight line at the italic slant (0.4, about
21.8 degrees) or at the play angle (28.5 degrees); every corner is a circular fillet.

The parameters were fitted to the chosen sketch (Desktop\\dwellplay\\Ideas.png, 3 Oct 2026) and
match it at 99.4% overlap. Units are that image's pixels: cap line y=124, baseline y=878.5.

    python tools/icon/mark.py              write the Android vectors and tools/icon/svg/*.svg
    python tools/icon/mark.py --fit IMG    refit the parameters to a new sketch (needs opencv, scipy)

The Compose copy of the path (Icons.Monogram) is printed; paste it if the geometry changes.
"""
import math
import os
import sys

import numpy as np

P = dict(
    slant=0.4, phi=28.5, yT=124.0, yB=878.5,
    xL=225.5, Ws=320.5, gap=41.0, yStem=376.0, yUnder=315.0,
    apexX=1158.5, apexY=483.5, xR=1335.5, lowStroke=213.0,
    rStemTL=16, rStemTR=8, rSmall=6, rBarTL=16, rBarBL=10,
    rTopRight=314.5, rLowRight=285.5, rSoft=252.5, rCounterLow=81.5, rTip=33.0,
)

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable")
RES_DEBUG = os.path.join(HERE, "..", "..", "app", "src", "debug", "res", "drawable")


def _italic_x(y, x0, y0, slant):
    return x0 + slant * (y0 - y)


def _meet(p1, d1, p2, d2):
    a = np.array([[d1[0], -d2[0]], [d1[1], -d2[1]]], float)
    t = np.linalg.solve(a, np.array(p2, float) - np.array(p1, float))
    return np.array(p1, float) + t[0] * np.array(d1, float)


def geometry(p=P):
    """Two filleted polygons: the stem, then the bowl (top bar, round side, chamfer, play counter)."""
    s, yB, xL, ws, g = p["slant"], p["yB"], p["xL"], p["Ws"], p["gap"]
    ax, ay, m = p["apexX"], p["apexY"], math.tan(math.radians(p["phi"]))
    stem = [(_italic_x(p["yStem"], xL, yB, s), p["yStem"]), (_italic_x(p["yStem"], xL + ws, yB, s), p["yStem"]),
            (xL + ws, yB), (xL, yB)]
    down = (-1.0, m)  # from the play tip toward the lower left
    counter_top = _meet((ax, ay), (-1, -m), (0, p["yUnder"]), (1, 0))
    counter_low = _meet((ax, ay), down, (xL + ws + g, yB), (s, -1))
    normal = np.array([m, 1.0]) / math.hypot(m, 1.0)
    chamfer = np.array([ax, ay]) + normal * p["lowStroke"]  # parallel to the counter's lower edge
    bowl = [
        (_italic_x(p["yT"], xL, yB, s), p["yT"]),         # top bar, flush with the stem's left edge
        (p["xR"], p["yT"]),                                 # round top right
        tuple(_meet(chamfer, down, (p["xR"], 0), (0, 1))),  # round lower right into the chamfer
        tuple(_meet(chamfer, down, (0, yB), (1, 0))),       # chamfer meets the baseline
        (xL + ws + g, yB),                                  # bowl foot, a gap away from the stem
        tuple(counter_low),
        (ax, ay),                                           # the play tip
        tuple(counter_top),
        (_italic_x(p["yUnder"], xL, yB, s), p["yUnder"]),
    ]
    return [
        (stem, [p["rStemTL"], p["rStemTR"], p["rSmall"], p["rSmall"]]),
        (bowl, [p["rBarTL"], p["rTopRight"], p["rLowRight"], p["rSoft"], p["rSmall"], p["rCounterLow"],
                p["rTip"], p["rSoft"], p["rBarBL"]]),
    ]


def _fillet(prev, v, nxt, r):
    v = np.array(v, float)
    u1, u2 = np.array(prev, float) - v, np.array(nxt, float) - v
    u1, u2 = u1 / np.linalg.norm(u1), u2 / np.linalg.norm(u2)
    theta = math.acos(float(np.clip(np.dot(u1, u2), -1, 1)))
    t = r / math.tan(theta / 2)
    bis = (u1 + u2) / np.linalg.norm(u1 + u2)
    centre = v + bis * (r / math.sin(theta / 2))
    turn = (v[0] - prev[0]) * (nxt[1] - v[1]) - (v[1] - prev[1]) * (nxt[0] - v[0])
    return v + u1 * t, v + u2 * t, centre, turn


def outline(poly, radii, steps=48):
    """Dense points along a filleted polygon (for fitting and measuring)."""
    pts = []
    for i, v in enumerate(poly):
        a, b, c, _ = _fillet(poly[i - 1], v, poly[(i + 1) % len(poly)], radii[i])
        a1, a2 = math.atan2(a[1] - c[1], a[0] - c[0]), math.atan2(b[1] - c[1], b[0] - c[0])
        da = (a2 - a1 + math.pi) % (2 * math.pi) - math.pi
        pts += [c + radii[i] * np.array([math.cos(a1 + da * k / steps), math.sin(a1 + da * k / steps)]) for k in range(steps + 1)]
    return np.array(pts)


def _num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def path_data(width, centre=None, origin=None, p=P):
    """SVG/Android path data for the mark `width` units wide, centred optically on `centre` or with
    its bounding box at `origin`."""
    shapes = geometry(p)
    pts = np.vstack([outline(poly, r) for poly, r in shapes])
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    k = width / (x1 - x0)
    if centre is not None:
        # Halfway between the bounding-box centre and the area centroid, which sits right of it.
        cx, cy = _centroid(shapes)
        ox, oy = ((x0 + x1) / 2 + cx) / 2, ((y0 + y1) / 2 + cy) / 2
        tf = lambda q: ((q[0] - ox) * k + centre[0], (q[1] - oy) * k + centre[1])
    else:
        tf = lambda q: ((q[0] - x0) * k + origin[0], (q[1] - y0) * k + origin[1])
    out = []
    for poly, radii in shapes:
        for i, v in enumerate(poly):
            a, b, _, turn = _fillet(poly[i - 1], v, poly[(i + 1) % len(poly)], radii[i])
            (ax, ay), (bx, by) = tf(a), tf(b)
            r = _num(radii[i] * k)
            out.append(("M" if i == 0 else "L") + f"{_num(ax)},{_num(ay)}")
            out.append(f"A{r},{r} 0 0,{1 if turn > 0 else 0} {_num(bx)},{_num(by)}")
        out.append("Z")
    return "".join(out), (x1 - x0) / (y1 - y0)


def _centroid(shapes):
    area = cx = cy = 0.0
    for poly, radii in shapes:
        q = outline(poly, radii)
        x, y = q[:, 0], q[:, 1]
        cross = x * np.roll(y, -1) - np.roll(x, -1) * y
        a = cross.sum() / 2
        area += a
        cx += ((x + np.roll(x, -1)) * cross).sum() / 6
        cy += ((y + np.roll(y, -1)) * cross).sum() / 6
    return cx / area, cy / area


def _vector(name, viewport, body, comment, aapt=False, res=RES):
    ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
           f'    android:width="{viewport}dp"\n    android:height="{viewport}dp"\n'
           f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n{body}\n</vector>\n')
    os.makedirs(res, exist_ok=True)
    with open(os.path.join(res, name), "w", newline="\n", encoding="utf-8") as f:
        f.write(xml)


def _fill(d, color):
    return f'    <path\n        android:fillColor="{color}"\n        android:pathData="{d}" />'


def write_all():
    white, crimson = "#FFFFFFFF", "#FFC9183C"
    note = "Generated by tools/icon/mark.py from the DwellPlay mark geometry; edit it there."
    # The board's app icon: the white mark across about 72% of the visible tile.
    _vector("ic_launcher_foreground.xml", 108, _fill(path_data(52, centre=(54, 54))[0], white), note)
    _vector("ic_launcher_monochrome.xml", 108, _fill(path_data(52, centre=(54, 54))[0], white), note + " Themed icon layer.")
    _vector("media3_notification_small_icon.xml", 24, _fill(path_data(22, centre=(12, 12))[0], white),
            note + " Replaces Media3's default play circle in the status bar.")
    _vector("splash_mark.xml", 288, _fill(path_data(144, centre=(144, 144))[0], crimson),
            note + " Android 12+ splash, inside the 192dp circle the system shows.")
    _vector("ic_launcher_background.xml", 108, '''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="34"
                android:centerY="26"
                android:gradientRadius="104"
                android:startColor="#FFD62549"
                android:endColor="#FFAE1131" />
        </aapt:attr>
    </path>''', "Algothrim crimson, about #C9183C overall, lit softly from the top left like the brand board.", aapt=True)

    # Debug builds wear the inverse, a crimson mark on black, so the two apps are told apart at a glance.
    _vector("ic_launcher_foreground.xml", 108, _fill(path_data(52, centre=(54, 54))[0], crimson), note + " Debug builds: crimson on black.", res=RES_DEBUG)
    _vector("ic_launcher_background.xml", 108, '''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="34"
                android:centerY="26"
                android:gradientRadius="104"
                android:startColor="#FF1A1B20"
                android:endColor="#FF08090B" />
        </aapt:attr>
    </path>''', "Debug builds: the app's near-black canvas, lit softly from the top left.", aapt=True, res=RES_DEBUG)

    os.makedirs(os.path.join(HERE, "svg"), exist_ok=True)
    tight, aspect = path_data(100 * 1.4674, origin=(0, 0))
    w = 100 * aspect
    for name, color in (("dwellplay-mark", "#0A0A0A"), ("dwellplay-mark-white", "#FFFFFF"), ("dwellplay-mark-crimson", "#C9183C")):
        with open(os.path.join(HERE, "svg", name + ".svg"), "w", newline="\n") as f:
            f.write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {_num(w)} 100"><path fill="{color}" d="{tight}"/></svg>\n')
    icon = path_data(52 / 72 * 512, centre=(256, 256))[0]
    with open(os.path.join(HERE, "svg", "dwellplay-icon.svg"), "w", newline="\n") as f:
        f.write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><defs><radialGradient id="g" '
                'cx="0.315" cy="0.24" r="0.963"><stop offset="0" stop-color="#D62549"/><stop offset="1" stop-color="#AE1131"/>'
                f'</radialGradient></defs><rect width="512" height="512" fill="url(#g)"/><path fill="#FFFFFF" d="{icon}"/></svg>\n')
    print(f"Icons.Monogram viewport {_num(w)} x 100, aspect {aspect:.4f}:\n{tight}")


def fit(image):
    """Refit the free parameters to a black-on-white sketch by maximising overlap."""
    import cv2
    from scipy.optimize import minimize

    gray = cv2.imread(image, cv2.IMREAD_GRAYSCALE)
    target = gray < 128

    def overlap(p):
        img = np.zeros(target.shape, np.uint8)
        for poly, radii in geometry(p):
            cv2.fillPoly(img, [np.round(outline(poly, radii, 24) * 8).astype(np.int32)], 255, shift=3)
        drawn = img > 0
        return (drawn & target).sum() / (drawn | target).sum()

    keys = ["xL", "Ws", "gap", "yStem", "yUnder", "apexX", "apexY", "xR", "lowStroke", "rTopRight", "rLowRight",
            "rSoft", "rCounterLow", "rTip", "yT", "yB"]
    res = minimize(lambda x: -overlap({**P, **dict(zip(keys, x))}), [P[k] for k in keys], method="Nelder-Mead",
                   options=dict(maxiter=3000, xatol=0.05, fatol=1e-6, adaptive=True))
    print(f"overlap {-res.fun:.4f}")
    print({k: round(float(v) * 2) / 2 for k, v in zip(keys, res.x)})


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--fit":
        fit(sys.argv[2])
    else:
        write_all()
