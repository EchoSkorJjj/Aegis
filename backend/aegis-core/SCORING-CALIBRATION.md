# Impact score calibration (CS203-73)

How the impact model's shipped weights were chosen, what they were checked against, and how to
change them without silently re-ranking every organization's queue. The model itself — `score =
severity x exposure x criticality x confidence x 100` — is described in the [README](README.md#impact-scoring-epic-7-stories-1-2);
this document is about the numbers.

**Enforced by** [`ScoringCalibrationTest`](src/test/java/com/aegis/core/scoring/ScoringCalibrationTest.java),
which scores the real demo files (`src/main/resources/demo/`) with the weights bound from the real
`application.yml`. Every anchor below is a named test; the demo table at the end is asserted pair by
pair. Run it on its own to print the table:

```bash
./mvnw test -Dtest=ScoringCalibrationTest
```

## The demo dataset

Calibration is measured against the estate `docker compose up` seeds. Its services span the model's
inputs:

| Service | Criticality | Exposure | Carries |
|---|---|---|---|
| Payments API | CRITICAL | INTERNET_FACING | Spring MVC 5.3.13, log4j-core 2.14.1 (3 hops deep), jackson-databind 2.13.0, pgjdbc 42.3.1; Stripe, AWS |
| Customer Portal | HIGH | INTERNET_FACING | axios 0.21.1, follow-redirects 1.14.7, lodash 4.17.20; Cloudflare, Auth0 |
| Order Service | HIGH | INTERNAL | log4j-core 2.14.1 (direct), jackson-databind 2.13.0, kafka-clients; AWS |
| Marketing Site | MEDIUM | INTERNET_FACING | (no SBOM); Cloudflare |
| Internal Reporting | LOW | ISOLATED | requests 2.25.1, urllib3 1.26.4; Snowflake |

The signals are the **real CVEs those pinned versions are exposed to**, with their NVD CVSS v3.1 base
scores — Log4Shell (CVE-2021-44228, 10.0), Spring4Shell (CVE-2022-22965, 9.8), pgjdbc (CVE-2022-21724,
9.8), the follow-up Log4j CVEs (CVE-2021-45046 9.0, CVE-2021-44832 6.6, CVE-2021-45105 5.9),
jackson-databind (CVE-2020-36518, 7.5), axios (CVE-2021-3749, 7.5), urllib3 (CVE-2021-33503, 7.5),
lodash (CVE-2021-23337 7.2, CVE-2020-28500 5.3), requests (CVE-2023-32681, 6.1) and follow-redirects
(CVE-2022-0536, 5.9) — plus the outages the demo's vendors can raise, by the impact word their status
feed uses (Stripe *major*, Auth0 *critical*, Snowflake *major*, Cloudflare *minor* and *none*, and AWS,
whose RSS feed carries no impact word). The test reads which service carries which CVE off the SBOM
files, not off this table.

## The anchors

Weights were not tuned until the table "looked right". Instead the model must satisfy two kinds of
anchor, and the numbers follow from them.

### Principles — must hold for every service profile, at every CVSS band edge

| | Principle | Why |
|---|---|---|
| **P1** | On a worst-case service (CRITICAL, INTERNET_FACING) the impact tier **is** the published CVSS v3.1 rating: 9.0+ CRITICAL, 7.0+ HIGH, 4.0+ MEDIUM, the rest LOW. **P1b:** the same for a feed's severity word — *critical* → CRITICAL, *high/major* → HIGH, *medium/minor* → MEDIUM, *low* → LOW, *none/maintenance* → NEGLIGIBLE. | When nothing about the service mitigates, the standard's rating is the right answer. The model's job is to discount for context, never to invent urgency — a queue where every 7.5 reads CRITICAL teaches people to ignore CRITICAL. |
| **P2** | Each step below the worst case on either axis costs **at most one band**. | A service one notch less critical, or one notch less exposed, is a mitigation — not a reason for a critical CVE to vanish into MEDIUM. |
| **P3** | A Critical-rated CVE (9.0+) is **never immaterial**, on any service. | "Isolated" and "low tier" are business declarations that can be wrong. A 9.8 must at least reach the queue. |
| **P4** | A Medium-rated CVE on the lowest-value service (LOW, ISOLATED) **is** immaterial. | The noise control has to control noise, or the queue fills with it. |
| **P5** | Exposure does **not** discount an outage. | Exposure is inbound attack reach. An outage is not an attack: when AWS is down, an internal service is down exactly as surely as an internet-facing one. |

### Reference judgments — concrete demo cases a reviewer signed off on

| | Judgment | Why |
|---|---|---|
| **R1** | Log4Shell on Order Service (HIGH, INTERNAL) is **HIGH**, and Log4Shell on Payments API is the top of the whole table. | The demo's headline case. A maximum-severity, network-exploitable RCE on a HIGH-tier service must not drop to MEDIUM for being internal — Log4Shell payloads arrive through whatever the service logs. |
| **R2** | Stripe *major* on Payments API is **HIGH**, not CRITICAL. | *Major* is degraded payments; *critical* is the word Statuspage uses for a total loss. |
| **R3** | Snowflake *major* on Internal Reporting is **material**. | Low-value, but the tool is genuinely down — that belongs in someone's queue, at LOW. |
| **R4** | A Cloudflare *none* notice is **immaterial** everywhere. | A notice reporting no impact is not work. |

## What the original weighting got wrong

The pre-calibration defaults (criticality 1.0/0.75/0.5/0.25, exposure 1.0/0.6/0.4, tiers 75/50/25/10,
*none* = 0.1, exposure applied to outages) failed eight of ten anchors:

- **P1/P1b — worst-case inflation.** The quartile tier ladder put every worst-case score a band above
  its rating: jackson-databind's 7.5 on Payments read CRITICAL, a 6.9 read HIGH, a 3.9 MEDIUM; Stripe
  *major* read CRITICAL (so R2 failed too).
- **P3 — critical CVEs buried.** 0.4 x 0.25 meant a 9.0 on Internal Reporting scored 9.0: immaterial.
- **P5 — outages discounted for exposure.** The same AWS outage scored 50.0 on Payments but 22.5 on
  Order Service, purely for being internal; Snowflake *major* on Internal Reporting (7.5) was dropped
  as noise (R3).
- **R1 — the headline case.** Log4Shell on Order Service scored 45.0: MEDIUM. Exposure was the
  culprit: INTERNAL at 0.6 discounted internal services four times harder than CVSS itself does.
- **R4 — no-impact notices queued.** *none* at 0.1 scored exactly the materiality threshold (10.0) on a
  worst-case service.

## How the numbers were chosen

1. **Tiers** are fixed by P1: the CVSS bands scaled to 0-100 — **90 / 70 / 40**, with LOW starting at
   the materiality threshold (**10**). (CVSS v3.1 cannot produce a non-zero base score below 1.6, so
   every published Low rating clears 10 on a worst-case service.)
2. **Severity words** are fixed by P1b. The shipped table already mapped every word to its tier except
   the no-impact ones, which move from 0.1 to **0.05** so R4 holds on any service.
3. **Criticality and exposure weights** were grid-searched (steps of 0.05) for every weighting that
   passes all ten anchors, then ranked by **smallest total change** from the original defaults. MEDIUM
   (0.5), LOW (0.25) and CRITICAL/INTERNET_FACING (1.0) never needed to move. The minimum-change
   weightings differ only in how they split HIGH against INTERNAL, so two tie-break rules decide:
   - **INTERNAL = 0.9**, because exposure is CVSS's Attack Vector by another name: re-scoring a critical
     network vector (AV:N/AC:L/PR:N/UI:N, 9.8) as Adjacent gives 8.8 — 0.9 of it. The external standard
     wins a tie over intuition. That leaves the smallest move for HIGH: **0.75 → 0.8**.
   - **ISOLATED = 0.5**, not the minimum-change 0.45. P3 and P4 box LOW x ISOLATED into
     [0.111, 0.145); at 0.45 a 9.0 on Internal Reporting clears materiality by 0.1 of a point, at 0.5 by
     1.3 — the middle of the window, so rounding or a small retune cannot flip it.

| Weight | Before | After | Anchor that moved it |
|---|---|---|---|
| `aegis.scoring.tiers.critical / high / medium` | 75 / 50 / 25 | **90 / 70 / 40** | P1 |
| `aegis.scoring.criticality.high` | 0.75 | **0.8** | P2, R1 |
| `aegis.scoring.exposure.internal` | 0.6 | **0.9** | R1 (value from CVSS Adjacent) |
| `aegis.scoring.exposure.isolated` | 0.4 | **0.5** | P3 (centred in the P3/P4 window) |
| `aegis.scoring.severity-label.none / maintenance / informational` | 0.1 | **0.05** | R4, P1b |
| exposure on vendor outages | applied | **not applied** | P5, R3 |
| everything else | — | unchanged | |

ISOLATED is deliberately harsher than CVSS's Local vector (which would be ~0.86): with CVSS's value an
isolated service would be barely discounted at all, and the P3/P4 noise window is what an
"isolated" declaration is for.

## The reviewed result

Every (signal, service) pair in the demo dataset under the shipped weights — asserted by
`ScoringCalibrationTest.theDemoDatasetScoresAsReviewed`:

| Score | Tier | Service | Signal |
|---:|---|---|---|
| 100.0 | CRITICAL | Payments API | CVE-2021-44228 Log4Shell (10.0) |
| 98.0 | CRITICAL | Payments API | CVE-2022-22965 Spring4Shell (9.8) |
| 98.0 | CRITICAL | Payments API | CVE-2022-21724 pgjdbc (9.8) |
| 90.0 | CRITICAL | Payments API | CVE-2021-45046 Log4j (9.0) |
| 76.0 | HIGH | Customer Portal | Auth0 outage, *critical* |
| 75.0 | HIGH | Payments API | CVE-2020-36518 jackson-databind (7.5) |
| 75.0 | HIGH | Payments API | Stripe outage, *major* |
| 72.0 | HIGH | Order Service | CVE-2021-44228 Log4Shell (10.0) |
| 66.0 | MEDIUM | Payments API | CVE-2021-44832 Log4j (6.6) |
| 64.8 | MEDIUM | Order Service | CVE-2021-45046 Log4j (9.0) |
| 60.0 | MEDIUM | Customer Portal | CVE-2021-3749 axios (7.5) |
| 59.0 | MEDIUM | Payments API | CVE-2021-45105 Log4j (5.9) |
| 57.6 | MEDIUM | Customer Portal | CVE-2021-23337 lodash (7.2) |
| 54.0 | MEDIUM | Order Service | CVE-2020-36518 jackson-databind (7.5) |
| 50.0 | MEDIUM | Payments API | AWS outage, no impact word |
| 47.5 | MEDIUM | Order Service | CVE-2021-44832 Log4j (6.6) |
| 47.2 | MEDIUM | Customer Portal | CVE-2022-0536 follow-redirects (5.9) |
| 42.5 | MEDIUM | Order Service | CVE-2021-45105 Log4j (5.9) |
| 42.4 | MEDIUM | Customer Portal | CVE-2020-28500 lodash (5.3) |
| 40.0 | MEDIUM | Order Service | AWS outage, no impact word |
| 40.0 | MEDIUM | Customer Portal | Cloudflare outage, *minor* |
| 25.0 | LOW | Marketing Site | Cloudflare outage, *minor* |
| 18.8 | LOW | Internal Reporting | Snowflake outage, *major* |
| 9.4 | NEGLIGIBLE | Internal Reporting | CVE-2021-33503 urllib3 (7.5) — immaterial |
| 7.6 | NEGLIGIBLE | Internal Reporting | CVE-2023-32681 requests (6.1) — immaterial |
| 4.0 | NEGLIGIBLE | Customer Portal | Cloudflare notice, *none* — immaterial |
| 2.5 | NEGLIGIBLE | Marketing Site | Cloudflare notice, *none* — immaterial |

Read against the epic's goal — *rank what matters to this organization first, and say why*: the
payments path's critical RCEs lead; context demotes without burying (Log4Shell is still HIGH on the
internal Order Service, and a critical CVE could not go immaterial even on Internal Reporting); outages
rank by the business they break, not by attack surface; and the only things marked noise are a
no-impact notice and non-critical CVEs on the isolated, lowest-value tool.

## Known limits — what calibration cannot fix

- **No exploitability signal.** The model has no "exploited in the wild" input (CISA KEV, EPSS), so
  Log4Shell ranks level with any other 10.0. That is a missing factor, not a mis-weighted one; the
  README reserves reachability for the AI *assess* step.
- **MEDIUM is broad.** 13 of 27 demo pairs land in MEDIUM. Order within a tier comes from the score,
  which is always shown — the tier is a routing band, not the ranking.
- **Exposure is coarse by design.** Three levels that can be declared confidently; CVSS's
  Adjacent/Local ratios vary by vector (0.81-0.90 for Adjacent), and 0.9 takes the critical-vector end.
- **The demo is small.** Five services and a few dozen pairs anchor the model; the principles (P1-P5)
  are what keep it honest beyond them, which is why they are tested on the full grid of service
  profiles rather than only on the demo.

## Changing the weights

The weights are configuration (`aegis.scoring.*`, each with an env-var override), and a deployment is
free to override them — `ScoringCalibrationTest` checks the **shipped** defaults, not a deployment's.
To change a shipped default:

1. Change it in **both** `ScoringProperties` and `application.yml` — the test fails if they drift.
2. Run `./mvnw test -Dtest=ScoringCalibrationTest`. A broken principle or reference judgment fails by
   name; if you intend to overturn one, change the anchor here first and say why.
3. Update the reviewed table (in the test and above) with the printed result, and have it reviewed —
   the diff of that table *is* the change to everyone's queue.
