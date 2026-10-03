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

Home, Library, Search and Sources form the primary navigation. Settings is a secondary action in a phone header and a car rail footer. Phone navigation is a rounded inset dock, and car navigation is an inset dark strip. Selected items use graphite, neutral content and a small crimson marker. Shelves retain bare 16:9 artwork with thin crimson watch progress, title and subdued source metadata.

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
