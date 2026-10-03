# DwellPlay Dark Cinema

## Direction and scope

Approved in the conversation on 1 October 2026: refine Home, Player and navigation in the existing native Compose app; retain four primary destinations with Settings elsewhere, neutral transport controls, crimson progress/selection, restrained translucent chrome, artwork atmosphere, and an explicit parked Resume action.

This is a refinement of the existing information architecture. The supplied direction and current phone/DHU screenshots resolve the visual lane, so image-generation probes and alternate north-star concepts are skipped. Use actual emulator renders to judge geometry, controls and realistic states.

## Scene

A person chooses a film on a phone or settles into a parked Curvv, often in a dim cabin, then watches without bright interface chrome competing with the picture.

## Color strategy

Restrained: near-black graphite layers with crimson occupying a small share of the interface. Compose stores sRGB tokens; the supplied hex palette is authoritative. Use perceptual lightness when adjusting layers rather than increasing saturation.

| Role | Color |
| --- | --- |
| Canvas | #08090B |
| Raised canvas | #101115 |
| Surface | #17181C |
| High surface | #222329 |
| Top surface | #2D2E35 |
| Primary text | #F2F2F2 |
| Secondary text | #A9ABB2 |
| Crimson | #C9183C |
| Text on crimson | #F2F2F2 |
| Hairline | near-white at 9% |

Crimson marks progress, primary deliberate actions and selected state. Navigation labels and transport icons remain neutral. Translucent surfaces use a dark base around 90% opacity with a quiet border; no live backdrop blur is required. Artwork atmosphere uses a cached small thumbnail and a separate softened layer, not frame-by-frame video analysis.

## Typography and geometry

Use the Android system sans family with clear scale and weight changes. Retain car headings 32sp, titles 28sp, and readable labels at 24sp. Phone hierarchy may increase the player title without inflating all controls. Respect font scale.

Phone targets remain at least 48dp, car targets at least 76dp. The car main transport target is 88dp. Keep automotive spacing generous; reduce decoration rather than shrinking targets.

## Navigation and Home

Home, Library, Search and Sources form the primary navigation. Settings is a secondary action in a phone header and a car rail footer. Navigation follows the sibling apps' dock; see the 0.2.0 additions below. Shelves retain bare 16:9 artwork with thin crimson watch progress, title and subdued source metadata.

## Player

Phone portrait: a dominant but uncropped video stage, metadata and progress within a connected control surface, neutral transport controls, and an expandable queue. Panoramic windows: video at left, connected controls at right, approximately 70/30 when the content and minimum control widths allow it. Narrow and portrait car windows use stacked composition with scrolling rather than an unusably narrow rail.

Do not crop or draw DwellPlay controls over the live YouTube embed. Keep native fullscreen overlay behavior separate from YouTube's adjacent controls.

## Resume and other states

Preserve the wording "Parked. Ready when you are." Show a bounded dark translucent surface over dimmed artwork, saved position and a deliberate Resume action. Native audio may carry on under this state; automatic queue advancement must preserve the Resume requirement. Keep loading, unavailable source, empty queue, long titles and captions paths usable.

## Platform invariants

Preserve Android Auto's measured 1.31 density correction, host corner exclusions, activity visibility reports, personal/store manifest differences and YouTube lifecycle rules. The driving cover remains fully opaque. No heavyweight new design library or alpha Material dependency is needed.

## Motion

Short fades and restrained state transitions, roughly 150–250ms. No continuous animation, bounce or page-load choreography. Use Compose transitions that respect system animation settings.

## Verification

Build personal and store variants, run meaningful safety regressions, inspect phone portrait/landscape, Curvv-sized panoramic and narrower/portrait car layouts, and test navigation/queue/resume state transitions. DHU and actual Curvv checks need a connected phone; record any unavailable checks honestly.

## 0.2.0 additions (3 October 2026)

**Brand.** The mark from the chosen sketch (Desktop\dwellplay\Ideas.png), rebuilt as geometry in tools/icon/mark.py: italic slant 0.4, play angle 28.5 degrees, circular fillets, 99.4% overlap with the sketch. Launcher icon: white mark on crimson lit softly from the top left; debug builds invert it (crimson on near-black). Wordmark: the crimson mark beside "DwellPlay" in the heading style, bold, the mark at 0.86 of the font size.

**Navigation.** Taken from the sibling apps (WearWise Wardrobe, WearWise Go, Vandana, Dueline) rather than invented: a floating pill sized to its tabs, icons only, the current tab a crimson pill that opens to show its name (240ms, cubic-bezier 0.22, 1, 0.36, 1). Wide windows turn it into a vertical capsule beside the content with the current tab as a crimson disc and Settings below a hairline. Content scrolls underneath both.

**Liquid glass.** ui/LiquidGlass.kt. The browsing content is recorded into a graphics layer; each glass surface redraws it through an AGSL bevel shader (15dp bevel, up to 12dp of bend, hardest at the rim), a 5dp blur and a tone (saturation 1.25, brightness 0.82), then the ink tint at 58%, a 1dp rim light, a crimson inner glow and a soft drop shadow. No white glare. Android 12 and older, and Android Automotive OS, get the same surface without the bend at 92%. Measured on the Android Auto head unit: 1 to 3ms of GPU per frame.

**Full screen.** The picture stays clear. Title at the top (Android Auto takes touches in both top corners, so nothing tappable goes there), and one glass bar along the bottom: previous, back 10, play or pause, forward 10, next, the seek line with times either side, picture options, leave full screen. Touches on the bar keep it from fading.

**Seek bar.** The board's: a thin crimson line on a neutral track with a white thumb that grows under a finger.

**Phone as remote.** While Android Auto is connected the phone player shows artwork, "Playing on the car screen" and the controls, never the picture.
