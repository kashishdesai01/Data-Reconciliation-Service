# Senior Engineering Review

## Verdict

The implementation is suitable as a production-shaped, locally deployable portfolio service. The HLD's correctness-critical paths are implemented and exercised against PostgreSQL. It is not an enterprise production service without the identity, compliance, retention, and operational controls explicitly excluded by the HLD.

No known correctness blocker remains after the review and clean-install verification.

## Findings fixed during review

| Finding | Risk | Resolution |
| --- | --- | --- |
| Java `UUID.compareTo` order differs from PostgreSQL UUID order | Canonical candidate inserts could violate the database check constraint | Canonicalization now uses UUID string/byte order, with regression coverage |
| JDBC could not infer `Instant` parameter types | Valid ingestion failed against PostgreSQL | Bind timestamps explicitly and convert result timestamps centrally |
| Bounded scoring executor rejected candidate bursts | A 1,000-record run failed under realistic load | Added caller-runs backpressure while keeping the executor bounded |
| Email-and-phone contradictions below the numeric review threshold became `NO_MATCH` | Violated the HLD's mandatory human-review rule | Contradiction now routes to `REVIEW` before threshold evaluation |
| Changed linked records did not immediately recompute their golden projection | Golden values could remain stale between ingestion and matching | Batch ingestion now recomputes each affected golden once in the same transaction |
| Returning to a historical payload conflicted with payload-hash uniqueness | A legitimate third revision could not be appended | Flyway V2 replaces global hash uniqueness with a lookup index; only the current hash is idempotent |
| Candidate application ran as one large transaction and lacked retry | Increased lock duration and did not meet deadlock recovery requirements | Each candidate applies in `REQUIRES_NEW`; transient failures reload and retry up to three times with jitter |
| Current-revision validation happened before membership locks | Ingestion could race a stale merge plan | Resolution locks source identities before validating that candidate revisions remain current |
| Review reads were public | Exposed review evidence and raw customer fields | All review endpoints now require the reviewer role; 401/403 responses use problem details |
| Nullable review filters produced untyped PostgreSQL parameters | Authenticated queue requests returned 500 | Optional filters now use explicit PostgreSQL casts and have an integration regression assertion |
| A successful restart retained its old failure summary | Completed run status was operationally misleading | Finalization clears the active failure summary; Spring Batch retains execution history |
| Manual `NO_MATCH` had no supported supersession path | A mistaken cannot-link could never be corrected | Corrections append a decision linked through `supersedesDecisionId`; selected-pair authorization does not bypass other cluster conflicts |
| Synthetic phone exchanges were invalid under NANP rules | Evaluation under-exercised phone comparison | Generator now emits valid, deterministic US phone numbers |
| Merge audit history was only visible from the surviving golden | Absorbed and newly split goldens appeared to lack their origin event | History lookup now includes `absorbedGoldenId` and `newGoldenId` event references |
| Pipeline, golden-record, review, and UI responsibilities were concentrated in large files | Reviews were harder and Spring transaction annotations on same-class calls could be bypassed | Split code into capability services, domain/API models, query/command paths, a typed UI client, state hook, and focused components; transactional stages now cross Spring bean boundaries |

## Verification evidence

- Backend: 14 tests pass, including PostgreSQL/Testcontainers integration tests.
- Concurrency: overlapping claims have no duplicate revision ownership; simultaneous review decisions commit once and replay idempotently.
- Atomicity: an injected failure at the final audit-event write rolls back membership, golden projection, provenance, and status changes.
- Determinism: reordered input produces equivalent scores at thread-pool sizes 1, 4, and 16.
- Restart: a real failed 1,000-record scoring run resumed with the same run ID and completed without replaying completed batch steps.
- Frontend: production Vite build succeeds; npm reports zero known vulnerabilities.
- Structure: production source files are capability-scoped; the public pipeline facade is 88 lines and the React page composition is 42 lines. Package ownership and transaction boundaries are documented in `docs/ARCHITECTURE.md`.
- Deployment: clean Compose start is healthy for PostgreSQL, API, and UI; Flyway upgrade from V1 to V2 and fresh V1+V2 install both succeed.
- Synthetic release gate: 400/400 labeled duplicate pairs reached scoring (100% recall); 388/388 automatic matches were true matches (100% precision); 699 comparisons replaced 499,500 all-pairs comparisons (99.86% reduction).

## Deliberate boundaries

The local reviewer account is demo authentication, not production identity management. PostgreSQL is host-exposed for inspectability. TLS termination, managed secrets, backups, PII policy, retention, multi-tenancy, external connectors, and regulatory certification remain outside the approved first-release scope. The README names the concrete hardening steps required before deployment with real customer data.
