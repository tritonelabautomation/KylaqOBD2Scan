# Fuel-saving evidence base — primary-source verification (2026-09-13)

Owner forwarded the study behind the "23 %" claim:
sciencedirect.com/science/article/pii/S1361920923004224 (fetched in full, open access CC BY-NC-ND).

## The paper
- **Title:** "Assessing driving behavior influence on fuel efficiency using machine-learning and
  drive-cycle simulations"
- **Authors:** Amin Mohammadnazar, Zulqarnain H. Khattak, Asad J. Khattak
- **Venue:** Transportation Research Part D: Transport and Environment, Vol 126, Jan 2024, 104025
- **DOI:** 10.1016/j.trd.2023.104025
- **Method:** second-by-second naturalistic driving data; driving-volatility surrogate; k-means /
  k-medoids / hierarchical clustering to classify styles; Autonomie® powertrain drive-cycle
  simulations for fuel/emissions outcomes.

## Exact findings (verbatim anchors)
| Context | Aggressive vs normal/calm | Source line |
|---|---|---|
| Headline (abstract/highlights) | "**23 % increase in fuel consumption** as opposed to normal driving" | abstract, highlight 5 |
| Curves | 23 % decrease in fuel economy, **+18.8 % emissions** | §6 |
| Work zones | **59.1 % decrease** in fuel economy, **+39.9 % emissions** | §6 |
| Freeways | calm driving **+50.4 %** fuel economy vs aggressive; +41.6 % emissions aggressive | §5.3.1, §6 |
| Arterials | calm driving **+28.4 %** fuel economy vs aggressive | §5.3.1 |
| Event share | aggressive = 12.2 % of work-zone events, 15.4 % of curve events | abstract |

## Consequence for our in-app copy (fixed same day)
- OBDeleven's article (and our first Save Fuel build) quoted "**up to 23 %**" — that phrasing
  *understates* the paper: 23 % is the **curve** figure; work zones reach **59.1 %** and freeways
  ~50 % fuel-economy gap.
- `FuelSavingsGuideScreen` step 2 now states: 23 % on curves, up to 59 % in stop-and-go work zones,
  with the venue named; plus the corroborating everyday range from ORNL/SAE (John Thomas et al.,
  SAE 2017, via DOE ScienceDaily 2017-10-01): **10-40 %** worse in stop-and-go, **15-30 %** at
  highway speeds ≈ $0.25-1/gallon.
- Rule recorded: every percentage shown in-app must trace to a primary source listed here;
  secondary articles (OBDeleven blog) are leads, never citations.

## Other numbers in the guide and their primary sources (re-verified)
| Claim | Source |
|---|---|
| Driver-feedback tools ≈ 3 % avg, up to 10 % | energy.gov "Driving More Efficiently" (via article) |
| Every 5 mph over 50 mph ≈ $0.27/gallon | U.S. DOE, same page |
| +100 lb ≈ −1 % economy | energy.gov |
| Roof box −10…−25 % at interstate speeds | energy.gov / article |
| A/C > −25 % in very hot conditions (short trips) | energy.gov / article |
| Tires +0.6 % avg, up to +3 % | afdc.energy.gov gas-saving_tips.pdf |
| Idling 0.25-0.5 gal/h | article §4 (engine-size dependent); our app personalises with PowertrainModel 0.8 L/h warm |

## Real-car lead (2026-09-16): measured warm-idle fuel rate 0.58 L/h — LEAD ONLY
- Owner's live PID-discovery run (F-6 oracle, `kylaq-pid-validation-2026-09-16.md` §4) captured
  PID **019D = 0.12 g/s** at warm idle, ≈31 °C ambient, AC ON → **0.58 L/h**
  (×3600 ÷ 745 g/L, the app's own J1979 mass-rate decode).
- `PowertrainModel.IDLE_FUEL_LH` stays **1.05 L/h** (trip-median anchored 2026-09-14; 0.8 L/h was
  the bottom of that measured band). This is a SINGLE instantaneous sample — AC clutch may have
  been between engagements, and rail-flow instants sit below trip-averaged burn.
- Status: **lead, not a citation.** No in-app number may quote 0.58 L/h yet. Decision rule: if
  repeated 019D idle samples across trips cluster near 0.6 L/h, revisit the anchor and this line.
