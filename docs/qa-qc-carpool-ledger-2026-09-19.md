# QA/QC — Car-pool ledger: per-trip riders, monthly earnings, effective cost (2026-09-19)

Owner: *"recently I'm started car pooling to office ... for each trip give an option to add car
pooling details like distance and amount. So, I can easily calculate for that trip what is the
effective cost for me like petrol cost - car pool earned money. Sometimes 3 people also joined
my ride so max 4 people can or may join my ride give + option to add additional rider ... also
show monthly earnings or car pool & effective cost for me."*

## What ships

- **Trip detail → Car pool card.** Empty state explains and offers "Add car-pool details";
  filled state lists the shared distance, each rider with what they paid, **Earned**, the
  trip's **fuel cost** (app-integrated litres × latest logged price per litre) and the
  **effective cost = fuel − earned**. Below zero it prints as **surplus +₹X** (the ride paid
  for itself), never as a negative cost. Edit reopens the dialog; delete removes the entry.
- **Dialog:** shared distance prefilled from the trip's own integrated distance; rider rows
  (name optional, amount required > 0) with **+ Add rider** capped at **four** ("the car seats
  five including you"); rows removable; blank names render as Rider 1..4.
- **Reports → Car pool (monthly):** per IST calendar month, newest first: trips, shared km,
  **earned ₹**, fuel ₹ and **effective/surplus** — the monthly view he asked for. Months with
  an unintegratable trip's fuel show earned with "fuel --" and no effective line, rather than a
  fabricated cost (no-fake-values rule).
- **Storage:** `CarpoolRepository`, prefs-backed line codec exactly like the fuel journal
  (`c1|id|IST-stamp|distance|tripId|name~amount;...`), changeTick flow so cards recompute; one
  entry per trip, upsert by id; escaping round-trips hostile names (`| ; ~ \ newline`).
- **Pure core, tested:** `CarpoolCodec.monthly(entries, fuelCost)` groups by IST month key via
  `RecordTime.format("yyyy-MM")` — a ride at 00:20 IST on the 1st is the new month even though
  UTC calls it the previous day (owner mandate: IST for all records).

## Verification

- `CarpoolCodecTest` (3 tests): hostile-name round trip with four riders and the earned sum;
  standalone entries + garbage lines decode to null; IST month boundary + effective arithmetic
  (350 fuel − 200 earned = 150 effective; unknown fuel ⇒ null effective, never a guess).
- CI census at commit: see PR digest.
- Field check: open any office-commute trip → Car pool → add distance 24, riders ₹150+₹150 →
  card shows earned 300 against that trip's fuel cost and the effective number; Reports shows
  the month row. Add a fourth rider to see the cap message.

## What is NOT claimed

- Fuel cost uses the **latest logged price per litre** applied to the trip's integrated litres;
  without a fuel-log entry there is no price and the card says "--" instead of inventing one.
- Rider amounts are what he enters (cash/UPI reality), not derived from distance.
- No backup-snapshot inclusion yet: car-pool lines live in prefs like the fuel journal; if he
  wants them in the Drive backup set, that is a follow-up.
