# Data Reconciliation Service

This project takes customer records from different source systems and works out which records belong to the same person. It keeps every source revision, explains why two records were considered a match, builds a versioned canonical customer record, and sends uncertain cases to a small review application instead of guessing.

The service is designed to be easy to run locally and easy to discuss in a code review. The implementation favors deterministic results, explicit transaction boundaries, and an audit trail over hidden matching behavior.

## What the project includes

- A Spring Boot API for ingestion, reconciliation runs, canonical customer record, and human review.
- PostgreSQL storage with Flyway migrations and database-backed Spring Batch state.
- Deterministic normalization, blocking, weighted scoring, and complete-link cluster validation.
- Versioned canonical customer records with field-level provenance.
- Append-only audit history for merges, splits, and review decisions.
- A React review UI for inspecting evidence and confirming or rejecting a match.
- A repeatable 1,000-record dataset with automated precision and recall checks.
- Docker Compose for running the API, UI, and database together.

## Start the application

You only need Docker and Docker Compose.

```bash
cp .env.example .env
```

Update both passwords in `.env`, then start the stack:

```bash
docker compose up --build
```

If your Docker installation provides the standalone Compose command, use `docker-compose up --build` instead.

Once the containers are healthy, open:

- Review application: <http://localhost:3000>
- API: <http://localhost:8080>
- Readiness check: <http://localhost:8080/actuator/health/readiness>
- PostgreSQL: `localhost:5432`

For isolated local development, the fallback reviewer credentials are `reviewer` / `change-me`. Do not use those defaults outside your own machine.

## Run a complete example

The repository contains a deterministic dataset with 1,000 records split across CRM and billing sources. The following script loads both files, checks that replaying the same input is idempotent, starts reconciliation, waits for it to finish, and evaluates the result:

```bash
./scripts/seed-and-run.sh
```

The script fails if candidate recall falls below 95% or if any automatic merge is incorrect. With the committed dataset, all 400 known duplicate pairs reach scoring, all 388 automatic matches are correct, and blocking reduces 499,500 possible comparisons to 699.

You can also ingest a record directly:

```bash
curl -u reviewer:change-me \
  -H 'Content-Type: application/json' \
  --data '{
    "records": [{
      "sourceRecordId": "crm-42",
      "fullName": "Ana Rivera",
      "email": "ana@example.com",
      "phone": "+14155550111",
      "address": "10 Pine Street",
      "postalCode": "94105",
      "updatedAt": "2026-09-01T12:00:00Z"
    }]
  }' \
  http://localhost:8080/api/ingest/CRM
```

## How reconciliation works

Each run follows the same seven stages:

1. Claim current source revisions that have not been processed by the selected ruleset.
2. Create immutable normalized records for names, emails, phone numbers, and addresses.
3. Generate candidate pairs using exact and phonetic blocking keys.
4. Score candidates in parallel using the ruleset pinned to the run.
5. Apply automatic matches only when every pair across the two clusters passes complete-link validation.
6. Build or update canonical customer record and record the source revision chosen for every field.
7. Publish ambiguous candidates and cluster conflicts to the review queue.

The service never treats a high pair score as permission to join two unsafe clusters. A human `NO_MATCH` decision becomes a cannot-link rule, contradictory identifiers require review, and changed records that would move between existing goldens are not silently relinked.

## Project structure

```text
src/main/java/com/masterdata/reconciliation
├── api/                 controllers, validation contracts, and problem responses
├── config/              security, batch, executor, and metrics configuration
├── domain/model/        persistence-independent value objects
└── service/
    ├── pipeline/        individual reconciliation stages and cluster validation
    ├── golden/          golden projection, queries, provenance, and audit events
    └── review/          review queries, cursor handling, and decisions

ui/src
├── api/                 browser API client
├── components/          focused visual components
├── hooks/               review-screen state and workflows
└── types/               shared TypeScript contracts
```

The public pipeline and review services are intentionally small façades. Detailed package ownership and transaction boundaries are documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## API endpoints

```text
POST /api/ingest/{sourceSystem}
POST /api/match-runs
POST /api/match-runs/{id}/restart
GET  /api/match-runs/{id}
GET  /api/review-queue
GET  /api/review-queue/{id}
POST /api/review-queue/{id}/decisions
GET  /api/golden-records/{id}
POST /api/golden-records/{id}/split
```

Errors use RFC 9457 problem details. Review decisions require an idempotency key and expected version. To correct a completed decision, send the replacement with the latest decision ID in `supersedesDecisionId`; the original decision remains in history.

## Tests and verification

Run the backend tests with:

```bash
mvn test
```

The integration tests use Testcontainers and a real PostgreSQL instance. When using Colima on macOS:

```bash
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn test
```

Verify the frontend with:

```bash
cd ui
npm ci
npm audit --audit-level=high
npm run build
```

The tests cover ingestion replay, historical payloads, normalization, scoring, complete-link conflicts, linked-record recomputation, overlapping run claims, concurrent reviewer decisions, idempotency, decision correction, atomic rollback, provenance, split history, and deterministic output across different thread-pool sizes.

## Operational notes

Actuator exposes liveness, readiness, Spring Batch timers, and application metrics for active runs, review backlog, retries, failed candidate applications, and observed block size. Logs are structured JSON and deliberately omit customer payloads.

This is a production-shaped reference implementation, not a complete enterprise deployment. Before processing real customer data, replace demo authentication, use managed secrets, terminate TLS at a trusted ingress, remove the database host port, configure backups and retention, restrict Actuator access, and complete the relevant privacy and compliance review.
