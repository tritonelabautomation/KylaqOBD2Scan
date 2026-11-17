# Owner's Manual integration — official Škoda digital manual portal

**Date:** 2026-09-15 · **Status:** shipped, unit-tested, awaiting owner on-device verification
**Screen:** Drawer → *Owner's Manual* (`owner_manual`) · **Code:** `com.example.manual.*`, `ManualViewModel`, `OwnerManualScreen`

---

## 1. Provenance — the repo the owner pointed at, checked thoroughly

| Source | State (verified 2026-09-15) |
|---|---|
| [jypma/skoda-manual](https://github.com/jypma/skoda-manual) (Nov 2023, Shell, no license) | Original idea: crawl `digital-manual.skoda-auto.com` section-by-section into one offline HTML + images. **Weakness:** requires hand-copying browser cookies; its `/w/…/show/…` UI path now answers *"Forbidden — Resources not available or access restricted"*. |
| [King4s/skoda-manual](https://github.com/King4s/skoda-manual) fork (Feb 2026, pushed Sep 2026) | **The actually-working 2026 flow.** Removes all cookie hunting: the portal mints an anonymous session per VIN/part number through the same entrypoint API the official web apps use ("remove all authentication — manuals are publicly accessible", commit `680766d`). Adds resume/caching, PDF export, Windows port. |
| Official portals | `skoda-auto.co.in/apps/manuals/` (India, Kylaq_PC editions 09-2025 + 02-2026), `skoda.dk/apps/manuals/Models` (Denmark), `manual.skoda-auto.com/663/en-IN/…` → redirects into the new platform. Model imagery lives on public Azure blob `ownersmanuals.blob.core.windows.net` (anonymous listing disabled). |

**Verdict on the owner's proposal:** right idea, and the fork proves it still works in 2026 — but the *original* script is obsolete (cookie-based). We adopted the fork's **protocol**, reimplemented natively in Kotlin, and dropped its desktop-HTML-file output in favour of an in-app browse/search/offline-pack experience.

## 2. API contract (all endpoints `https://digital-manual.skoda-auto.com`)

| # | Call | Purpose |
|---|---|---|
| 1 | `POST /api/entrypoint/V1/direct/` form `vin=<17ch>` **or** `partNumber=<pn>` + `uiLanguage` + `importerId`; headers `Origin`/`Referer` of the market web app | Mints the session cookie. VIN pattern: 17 chars, no I/O/Q (ISO 3779) — anything else is sent as part number. |
| 2 | `GET /api/users/V1/getuser` | Session live ⟺ body contains `"anonymous":false`. |
| 3 | `GET /api/web/V6/search?query=&facetfilters=topic-type_%7C_welcome&lang=<l>&page=0&pageSize=200` | `.results[0].topicId` = manual root key for this VIN. |
| 4 | `GET /api/web/V6/topic?key=<manualId>&displaytype=topic&language=<l>&query=undefined` | `.trees` = full TOC (`label` may contain HTML; `linkTarget` may be JSON-string `"null"`; recursive `children`). |
| 5 | `GET /api/vw-topic/V1/topic?key=<linkTarget>&displaytype=desktop&language=<l>` | `.bodyHtml` = one section (full HTML document — we strip doctype/html/head/body). Images are lazy `data-src="https://digital-manual.skoda-auto.com/default/public/media?lang=<l>&amp;key=<k>"`. |
| 6 | `GET /public/media?lang=<l>&key=<k>` | Image bytes (GUID keys, no extension → magic-byte MIME sniffing). |

**Session expiry:** any payload containing `An Authentication object was not found in the SecurityContext` → re-POST step 1 with the stored identifier/config and retry **once** (never loop).

**Importer matrix** (tried in order, first non-empty tree wins; `PortalConfig.orderFor` puts the UI language first):

| Config | importerId | uiLanguage | Origin | Basis |
|---|---|---|---|---|
| India EN-IN | **663** | `en_IN` | `skoda-auto.co.in` | bid 663 visible in `manual.skoda-auto.com/663/en-IN/…` + `clg.skoda-auto.com/api/link?bid=663&instanceName=IND` |
| India EN-GB | 663 | `en_GB` | `skoda-auto.co.in` | language fallback |
| Denmark EN-GB | 004 | `en_GB` | `skoda.dk` | **proven end-to-end by the King4s fork** — global safety net |

## 3. In-app architecture

```
OwnerManualScreen (Compose)            ManualViewModel (state machine)
 ├─ Connect card: VIN/part-no + language chips + portal link     phases: DISCONNECTED → CONNECTING →
 ├─ TOC LazyColumn: flattened tree, chevron-expand, depth indent          READY / OFFLINE_READY / ERROR
 ├─ Search: TOC labels + cached-page full text (local only)     download: Downloading(done/total/label)
 └─ Section viewer: JS-disabled WebView ──┐
        shouldInterceptRequest ───────────┘ serves /public/media from cache or portal
                    │
            ManualRepository ── ManualPortalClient (protocol, re-auth-once)
                    │                 └── ManualHttp interface ← OkHttpManualHttp (cookie jar, browser UA)
                    ├── ManualCache (filesDir/manual_portal: tree_<lang>.json, sections/<sha256>, media/<sha256>, last_connect.json)
                    └── ManualJson (pure parsing: trees/flatten/bodyHtml/mediaRefs/mime/snippet — no Android types)
```

- **VIN prefill:** first garage `VehicleEntity` whose stored VIN matches the ISO pattern (OBD `0902` discovery already fills this), else last-used identifier.
- **Offline restart:** `cachedBook()` rebuilds the whole TOC from cache → `OFFLINE CACHE` badge; "Download for offline" (cloud icon) packs every section + image with 40/20 ms polite throttling, resumable (already-cached sections skip), stoppable.
- **WebView honesty:** JavaScript disabled, file access disabled, dark-theme CSS injected, `data-src`→`src` rewrite so images render without the portal's lazy-load JS.

## 4. Legal posture (why nothing is bundled)

The jypma README itself warns: *"do not share any extracted manuals further than your own use — there's probably copyright on them."* Therefore:

1. **Zero manual content in the APK or this repository.** All text/images are fetched **at runtime, to the owner's device, with the owner's own VIN** — technically and legally the same act as visiting the official portal in a browser.
2. Cache lives in app-private storage (`filesDir`), for **personal offline use** of the owner's own car manual; *Clear offline cache* wipes it completely.
3. On-screen attribution: *"Content © Škoda Auto a.s. — streamed from the official digital-manual portal with your own VIN and stored privately on this device… The app bundles no manual content."*
4. An *Official portal* button deep-links `skoda-auto.co.in/apps/manuals/Models` for anything we can't serve.

## 5. Test coverage (`OwnerManualPortalTest`, 24 cases)

Protocol: VIN-vs-partNumber form switching · importerId/uiLanguage wiring · `anonymous:true` rejection · POST-failure short-circuit · topicId resolution · tree URL construction · **expiry → re-auth once → retry** · double-expiry gives up · media URL construction · no-session = no network. Parsing: label tag-stripping · `"null"` linkTarget normalisation · DFS flatten path-ids/depths · visible-under-expanded-ancestors rule · bodyHtml document unwrapping · 2026-style absolute `data-src` media extraction with `&amp;` + dedupe · `data-src`→`src` activation · MIME magic bytes · snippet windowing. Cache: section roundtrip **preserving linkTarget through hashed filenames** · media/tree/lastConnect roundtrips · collision safety for keys with `?&=/` · clear().

## 6. Owner on-device verification checklist

1. Drawer → Owner's Manual → VIN should prefill from the garage (or type it: windscreen/RC/insurance).
2. **Connect to portal** → expect `LIVE SESSION` + book title (e.g. *Kylaq Owner's Manual*) + "Live session — N topic rows". If India (663) is refused, watch it fall through to the Denmark fallback; the status line lists what was tried.
3. Browse a section → text + images render (dark theme).
4. Cloud icon → offline pack downloads (progress `x/y`); then enable airplane mode, reopen the app → `OFFLINE CACHE` badge, sections + images still render, search covers all pages.
5. Escape hatch: *Official portal* button opens the Škoda India manuals web app.

**Troubleshooting:** "Session refused" → VIN typo / non-Škoda VIN / try the part number printed on the official portal's model page instead. "No manual for this VIN" → language fallback already tried; report the status line back and we'll extend the matrix.

## 7. Follow-ups (not shipped)

- PDF documents (service schedule etc.) from the `ownersmanuals.blob.core.windows.net` store — needs the apps/manuals document-list API (JS-gated; not reverse-engineered yet).
- DTC → manual deep links (open the relevant warning-lamp page from DTC Scanner).
- Android Auto surface (AA is driving-restricted; low priority for a reading feature).
