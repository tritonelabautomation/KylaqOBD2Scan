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

## Rework after owner field feedback (same day, build 2)

Owner: *"I don't see option of car pool ... give me an separate option for car pool logging.
Based on date & time input in car pool logging trip can fetch at exact time if any car pool
exist it can link to trip. Because we have seen some trips not logged properly abruptly stop in
background. But my car pool already a passenger in so."*

- **Standalone Car Pool screen** in the nav drawer (between Fuel & Costs and Save Fuel): this-
  month header (rides, km, earned, effective/surplus), previous months, "+ Log car pool", and
  every ride as a card with its IST date-time, riders, earned, and link state. Edit and delete
  per ride.
- **Date and time are first-class inputs** in the dialog (IST calendar math, same rule as the
  fuel log): the ride's instant is the linking key, stored as the entry's IST stamp.
- **Auto-link by time window** (`CarpoolCodec.tripLinkFor`, pure and tested): a ride joins the
  saved trip whose [start, end] covers its instant - latest-starting window wins, open end
  (still recording) covers onward. An explicit tripId from a trip's own card always wins.
- **Recovered trips relink orphans**: every session finalize (normal stop, orphan save, journal
  recovery, raw-log recovery) runs `relinkCarpoolEntries()` - rides logged during a session that
  died abruptly join it the moment the recovery recreates it. The passengers were aboard
  whether or not the recorder survived; the ledger now remembers that.
- The trip-detail card now shares its views with the screen (`CarpoolViews.kt`) and prefills the
  dialog's date-time from the trip's own start.

## Field bug 1, same night (owner screenshot 23:41 IST): rider fields swallowed keystrokes

Owner: *"unable to type rider 1 & ₹ place it's not taking any input from keyboard."*

Cause: the rider name/amount lists were `mutableStateOf(MutableList)` updated by mutating the
list and re-assigning the SAME instance (`riderNames = riderNames.also { it[i] = v }`). Compose
compares old and new state value, sees the identical reference, treats the write as a no-op and
never recomposes - so every keystroke vanished, and "+ Add rider" added nothing visible. The
date/time/distance fields worked because Strings are immutable: each keystroke IS a new value.

Fix: `mutableStateListOf` (SnapshotStateList) whose element writes are tracked individually -
`riderNames[i] = v`. Date/time/distance untouched. Swept the whole app for the same
mutate-and-reassign pattern: no other occurrence.
