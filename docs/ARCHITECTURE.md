# Code Architecture

This repository uses a feature-oriented layered structure. Public façades remain small and stable; detailed behavior is grouped by business capability rather than collected in a single service class.

## Backend packages

```text
com.masterdata.reconciliation
├── api
│   ├── *Controller.java          HTTP routing only
│   ├── ApiException*.java       RFC 9457 error translation
│   └── model/                   one request or response contract per file
├── config/                      framework wiring, security, batch, metrics
├── domain/model/                persistence-independent value types
└── service
    ├── ReconciliationPipelineService.java  stable batch/API facade
    ├── ReviewService.java                  stable review facade
    ├── GoldenRecordService.java            membership commands
    ├── pipeline/
    │   ├── RevisionPreparationService.java claim and normalize run input
    │   ├── CandidateGenerationService.java blocking and pair persistence
    │   ├── CandidateScoringService.java    parallel score orchestration
    │   ├── CandidateScoreWriter.java       isolated score transaction
    │   ├── ClusterJoinValidator.java       complete-link invariant
    │   ├── ClusterResolutionService.java   merge application and retry
    │   ├── ReviewItemService.java          review publication
    │   └── MatchRunLifecycleService.java   run state and metrics
    ├── golden/
    │   ├── GoldenProjectionService.java    survivorship and provenance
    │   ├── GoldenRecordQueryService.java   assembled read model
    │   └── ReconciliationEventService.java append-only audit history
    └── review/
        ├── ReviewQueryService.java          queue and evidence reads
        ├── ReviewDecisionService.java       decision command transaction
        └── ReviewCursorCodec.java           opaque cursor encoding
```

Dependency direction is `api -> service facades -> capability services -> domain model`. Configuration creates framework objects but contains no business decisions. Domain models do not depend on controllers, Spring, or persistence.

## Frontend modules

```text
ui/src
├── api/reviewApi.ts             HTTP protocol and error handling
├── components/                 one visual responsibility per component
├── hooks/useReviewWorkspace.ts review-screen state and workflows
├── types/review.ts             API-facing TypeScript contracts
├── App.tsx                     page composition only
└── main.tsx                    React bootstrap only
```

Components do not issue HTTP requests. The hook coordinates user workflows, while the API module owns transport details.

## Transaction ownership

- A Spring Batch step owns the outer transaction for preparation and publication.
- Run-stage updates and failure recording use explicit `REQUIRES_NEW` service boundaries.
- Each candidate score is persisted by `CandidateScoreWriter` in its own transaction.
- Each automatic cluster application uses a bounded `TransactionTemplate` transaction and retry loop.
- A human review decision, any resulting merge, its immutable decision row, and review version update share one transaction.
- Golden projection versions, provenance, membership changes, and audit events commit atomically.

These boundaries are deliberately placed on separate Spring beans. This avoids the self-invocation trap where calling an annotated method on `this` bypasses Spring's transaction proxy.

## Review conventions

- Keep controllers declarative and free of SQL or business rules.
- Keep API contracts out of domain packages.
- Add a dedicated capability service when a class begins owning a second transaction boundary or unrelated persistence aggregate.
- Prefer named records for query results instead of positional maps inside command logic.
- Add regression coverage at the narrowest level that proves the invariant; use PostgreSQL integration tests for locking, constraints, and transaction behavior.
- Schema changes are append-only Flyway migrations. Never edit an applied migration.
