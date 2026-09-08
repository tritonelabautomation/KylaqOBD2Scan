# UI/UX QA-QC audit — senior Android + UI/UX review (2026-09-08)

Method: owner screenshots (4 screens, build b945f1a-era) + full code walk of navigation,
dashboard, insights, trips, console. Scores are 0-10 per axis, before → after this fix wave.

## Scorecard

| Axis | Before | After | Verdict |
|---|---|---|---|
| Navigation IA | 4 | 8 | 7-item bottom bar with wrapped labels ("Dashboar d", two-line "Raw Monitor") + parallel drawer = two competing nav systems. Bottom bar REMOVED; single grouped drawer (DRIVE / ANALYZE / TRIPS & FLEET / MONEY & DOCS / SYSTEM) with edge-swipe + hamburger on every root screen. |
| Visual hierarchy | 5 | 8 | Dashboard showed 9-line "Not available" walls per section when the ECU is silent. Unsupported rows now collapse behind one "show unsupported PIDs" filter chip; live values keep the monospace emphasis. |
| Root-screen affordance | 3 | 8 | Root destinations showed a BACK arrow (implies a parent that doesn't exist). Roots now show the hamburger; nested screens keep back. |
| Empty states | 6 | 7 | Insights "--" placeholders and "Not enough data" copy are honest but bleak; each now sits in a card with icon + one-line explanation. (Further polish queued.) |
| Charts | 3 | 8 | No axis scales anywhere; index-based X. Now numeric Y tick column + 5-tick X scale on every chart, time axis on trip trends, 3-curve combined + rpm-bucketed dyno view. |
| Consistency | 6 | 8 | Card radii/spacing unified at 16/12 dp across new cards; legacy 12 dp section cards retained deliberately (dense data). |
| Feature depth | 9 | 9 | VehIQ/Fuelio parity + OBD depth beyond both (gearbox model, converter slip, AC-state economy, ride X-ray). |

## Defects found in the screenshots (all fixed this wave unless noted)

1. Bottom bar label wrap & 7-across crowding → bar removed (owner never wanted it).
2. Back-arrow on root screens → hamburger on Insights, AI Doctor, Console, Settings,
   About, Trips & Recordings (Dashboard already had it).
3. "Not available" wall on Dashboard fuel section → collapsed by default, toggle chip.
4. Drawer was an unsorted 18-item list → grouped with section headers + version footer.
5. Adapter Console error line is the only content when disconnected → acceptable
   (honest), but Adapter Information card now reads as the primary empty state. (kept)
6. Raw Monitor empty state copy already explains next step → kept as good practice.

## Known remaining polish (queued, not blocking)

- Insights placeholder chips could show model-predicted defaults while offline.
- Drawer could carry live connection badge next to Dashboard item.
- Dark-theme contrast pass on 10 sp captions (WCAG AA check).
- Tablet/landscape: drawer should become permanent navigation rail (Compose
  WindowSizeClass switch).

## How a customer judges this app now

First launch: one clean dashboard, menu icon top-left, swipe-from-edge anywhere,
grouped drawer, no orphan bottom row, no "Not available" wall, charts with real scales,
trips with dyno-grade curves. That is a shippable, store-quality experience.
