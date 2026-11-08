# Aegis test strategy

What each kind of test is for, the coverage each service must keep, and which test proves each story. A new feature
is done when its acceptance criteria appear in the table at the end, each pointing at a test.

## The layers

| Layer | Tool | Runs against | What it is for |
|---|---|---|---|
| Unit | JUnit 5 + AssertJ, pytest | Nothing outside the process; a local HTTP stand-in where a client is the subject | Rules with many cases: version ordering, scoring, parsers, redaction, egress guards, failure mapping of HTTP clients |
| Integration | Spring Boot + Testcontainers Postgres | A real PostgreSQL, as a plain role so row-level security applies as on RDS | Business flows through the REST API: RBAC, tenancy, state machines, migrations, the decision trail |
| Architecture | ArchUnit | The compiled classes | aegis-core's module boundaries (`PackageStructureArchTest`) |
| Access | The access tests | Each service's security rules | Every endpoint against [`security/access-control.md`](../security/access-control.md); a new endpoint fails the build until it is listed |
| AI evaluation | `python -m ai_service.evaluation` | Golden cases through the guarded pipeline: offline on every change, and against the real model (Groq), three runs each, when the AI changes | Draft quality against a rubric: the replay gates the pipeline; the real-model run gates prompt and model changes against a committed baseline |
| Browser contract | Playwright against a fake BFF (`frontend/e2e`) | The real UI, faked responses | What each page shows and sends for each role and state, and accessibility |
| Account journeys | Playwright (`frontend/e2e`) | The real identity-service and gateway | Sign-up, sign-in with its emailed code, organizations, admin |
| Full-stack journeys | Playwright (`frontend/e2e-full-stack`) | Every real service, with only NVD and Atlassian faked at the network | The product working as one: from an advisory arriving to a decision on record |

Put a test at the lowest layer that can prove the behaviour. Rules and edge cases go in unit and integration tests;
a journey proves the parts connect, not every case. The rules for keeping shared-database tests independent are in
`AGENTS.md` section 8.

**Only the AI evaluation reaches an outside system from CI.** `ai-evaluation` sends the synthetic golden cases to Groq
when a prompt, a capability, the LLM layer or the evaluation changes, or on demand. Everywhere else the AI runs on its
offline stub, and `AbstractIntegrationTest` pins the integrations off. The full-stack journeys point the real NVD connector and Jira adapter at
`frontend/e2e-full-stack/fake-upstreams.mjs`.

## Coverage targets

`testing/coverage-targets.json` holds the targets; this table explains them. CI reports each service's coverage in
its job summary and **fails the job below target** (`testing/check_coverage.py`), and keeps the full report as a
build artifact.

| Service | Tool | Line target | Branch target | Measured 2026-11-05 (line / branch) |
|---|---|---|---:|---|
| aegis-core | JaCoCo | 90% | 75% | 93.5% / 77.3% |
| identity-service | JaCoCo | 90% | 72% | 92.2% / 74.2% |
| gateway | JaCoCo | 90% | 80% | 93.9% / 83.3% |
| ai-service | pytest-cov | 95% | 88% | 96.9% / 90.1% |
| frontend | Route check in `frontend/e2e/accessibility.spec.ts` | Every route opened by a test | n/a | 32 of 32 routes |

**Why these numbers.** Each target sits a little below what was measured, so a change that drops real coverage fails
while an ordinary refactor doesn't. Raise a target when coverage rises for good; lower one only with the reason in
the pull request.

**What they don't count.**

- JaCoCo and pytest-cov count the code the service's own tests execute. The full-stack journeys run against packaged
  services and add nothing to these numbers, so for example the success paths of `JiraIntegrationController` are
  proved by a journey while JaCoCo reports them uncovered.
- The frontend is measured by routes, not lines: it has no unit tests, and line coverage from a dev server would
  measure bundler output as much as our code. Its behaviour is held by the browser specs.
- Coverage shows which code ran, not that the test checked anything. It finds gaps; it doesn't prove quality.

## Running the tests

| What | Command, from the service's folder | Needs |
|---|---|---|
| Java service, with coverage | `mvn verify`, then open `target/site/jacoco/index.html` | Docker |
| Check against the target, from the repo root | `python3 testing/check_coverage.py aegis-core backend/aegis-core/target/site/jacoco/jacoco.xml` | A `verify` first |
| ai-service, with coverage | `pytest --cov --cov-report=term --cov-report=xml:coverage.xml` | `pip install -e '.[dev]'` |
| Browser specs and accessibility | `pnpm test:e2e` | The account specs need identity-service, the gateway and Mailhog |
| Full-stack journeys | `e2e-full-stack/stack.sh up`, then `pnpm exec playwright test --config playwright.full-stack.config.ts`, then `e2e-full-stack/stack.sh down` | Docker, JDK 21+, Maven, Python 3.12, Node 22 |

The stack script uses its own Compose project and ports, so it leaves a running `docker compose up` database alone. It
does use ports 8080 to 8083, like the main stack, so stop that first.

## Stories and the tests that prove them

Open stories come first. When a story opens, add its acceptance criteria here with the test that covers each one.

### Open

| Story | Acceptance criterion | Test |
|---|---|---|
| CS203-231 AI evaluation against the real model | CI runs the golden cases against the real model when the AI changes, and on demand, and compares the result with a committed baseline | `.github/workflows/ai-evaluation.yml`, `backend/ai-service/evaluation-baseline.json` |
| | A case is judged over several runs: quality on two in three, its constraints on every run | `test_evaluation.py`: `test_a_case_passes_when_it_meets_the_pass_rate_on_two_of_three_runs`, `test_one_run_breaking_a_constraint_fails_the_case_however_the_others_score` |
| | A type's mean score falling more than 5 points, a case's more than 15, more constraint-breaking runs than the baseline plus 2, or a guardrail case not blocked fails the run; a case changing verdict is listed, not failed on | `test_a_type_whose_mean_score_falls_more_than_five_points_regresses`, `test_a_case_whose_mean_score_falls_more_than_fifteen_points_regresses`, `test_more_runs_breaking_a_constraint_than_the_baseline_plus_two_regress`, `test_a_case_the_guardrails_must_block_regresses_unless_blocked_on_every_run`, `test_a_borderline_case_missing_its_threshold_is_listed_for_review_not_failed`, `test_the_job_summary_names_each_regression_and_each_changed_verdict` |
| | Each run's scores per draft type appear in the job summary | `test_each_draft_type_reports_its_pass_rate_and_mean_score_in_the_job_summary` |

### Built

| Story | What is proved | Test |
|---|---|---|
| E8 s4 upgrade PR | Safe upgrades, approval, four eyes | `UpgradeProposalIntegrationTest`, `UpgradeRecommendationIntegrationTest` |
| E8 s5 risk ticket | Drafted, filed in Jira only on approval | `RiskTicketIntegrationTest`, `journey.spec.ts` |
| E8 s6 regulator memo | Only a second analyst makes it final | `RegulatorMemoIntegrationTest`, `core-journeys.spec.ts` |
| E8 s7 evidence | Evidence on every draft | `DraftEvidenceTest` |
| E9-1 queue | Ranked and filtered | `TriageApiIntegrationTest`, `findings.spec.ts`, `journey.spec.ts` |
| E9-2 routing | Reassigning moves the items; the owner finds them | `TriageApiIntegrationTest`, `core-journeys.spec.ts` |
| E9-3 one page to decide from | The change, services, factors and responses | `finding-detail.spec.ts` |
| E9-4 decisions | Edit, override, approve, dismiss with a reason | `RiskTicketDecisionIntegrationTest`, `risk-tickets.spec.ts`, `core-journeys.spec.ts` |
| E9-5 notifications | Each owner told once | `OwnerNotificationIntegrationTest`, `notifications.spec.ts`, `core-journeys.spec.ts` |
| E10-1 decision trail | Every step recorded, append-only | `DecisionTrailIntegrationTest`, `DecisionRecordImmutabilityIntegrationTest`, `journey.spec.ts` |
| E10-2 search | By service, person, decision and date | `DecisionTrailSearchIntegrationTest`, `decision-trail.spec.ts` |
| E10-3 export | PDF and CSV for a period | `EvidenceExportIntegrationTest`, `EvidenceExportCorrectnessIntegrationTest`, `evidence-export.spec.ts`, `core-journeys.spec.ts` |
| E11-1 dashboard | Open items by service and owner, with a trend | `RiskPostureIntegrationTest`, `dashboard.spec.ts` |
| E11-2 usable everywhere | Every page at phone width, by keyboard, with labelled fields; a new page is held to the same | `accessibility.spec.ts` |
| E11-3 code usage | Where the code imports each dependency | `ImportScannerTest`, `CodeUsageTest`, `reachability.spec.ts` |
| E11-4 usage in the score | Not imported ranks lower | `ReachabilityTest`, `ScoringCalibrationTest` |
| E11-5 AI suggestion | A suggestion beside the score, never in it | `ReachabilityAssessmentIntegrationTest`, `ai-suggestion.spec.ts` |
| E12-3 access review | Every endpoint's required role | `GatewayAccessTest`, `IdentityAccessIntegrationTest`, `AccessControlIntegrationTest` |
| E13 observability | Pipeline metrics, the decision queue gauge, the alerts and one trace across services | `DecisionQueueMetricsTest`, `PipelineMetricsIntegrationTest`, `ops/alerts.test.yml`, `PipelineTracingTest`, `GatewayTracingTest`, `test_observability.py` |
| E14-1 test strategy | Coverage targets per service, checked in CI; the core journeys through the real services | `check_coverage.py`, `journey.spec.ts`, `core-journeys.spec.ts` |
| Tenancy (ADR section 6) | One organization never sees another's data | `MatchingTenantIsolationIntegrationTest`, `DecisionTrailIsolationIntegrationTest`, `core-journeys.spec.ts` |
