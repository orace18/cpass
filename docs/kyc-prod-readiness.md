# KYC MVP Production Readiness

This note prepares the KYC MVP for a real pilot with operational monitoring, product friction tracking, and data-driven V2 planning.

## Operational Endpoint

Admin endpoint:

```http
GET /admin/kyc/stats
Authorization: Bearer <admin-token>
```

The endpoint returns a point-in-time operational snapshot computed from persisted KYC cases and documents, plus runtime auth failure counters.

Primary fields:

| Field | Meaning |
| --- | --- |
| `totalCases` | Total persisted KYC cases. |
| `verified.count` / `verified.rate` | Internal `APPROVED`, exposed to integrators as `VERIFIED`. |
| `resubmission.count` / `resubmission.rate` | Cases requiring a user retry or correction. |
| `review.count` / `review.rate` | Cases waiting for manual/operator review. |
| `rejected.count` / `rejected.rate` | Cases rejected by MVP policy. |
| `topReasonCodes` | Most frequent actionable `reasonCode` values. |
| `avgProcessingTimeMillis` | Average time from case creation to last processing. |
| `authFailures` | Runtime auth failures by code and mode. |
| `friction` | Product friction metrics for unreadable, selfie, mismatch, and document type performance. |
| `dataDrivenV2Priorities` | Suggested V2 backlog ordered by observed pilot signals. |

## Micrometer Metrics

The service also emits MVP metrics through Micrometer / Actuator.

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `kyc.cases.total` | Gauge | none | Total KYC cases in storage. |
| `kyc.cases.status` | Gauge | `caseStatus`, `productStatus` | Case count by internal and product status. |
| `kyc.case.decisions` | Counter | `caseStatus`, `productStatus`, `reasonCode`, `documentType` | Workflow/manual decision count. |
| `kyc.case.processing` | Timer | `caseStatus`, `productStatus` | Processing duration from case creation to final processing. |
| `cpass.auth.failures` | Counter | `mode`, `code`, `path` | API, bearer, or Spring Security auth failures. |

In production, expose these through the existing actuator configuration:

```properties
management.endpoints.web.exposure.include=health,info,prometheus
```

## Key Lifecycle Events

Logs should be searchable by:

- `requestId`
- `clientId`
- `caseId`
- `documentId`
- `caseStatus`
- `productStatus`
- `reasonCode`
- `nextAction`

Lifecycle events currently logged:

| Event | Key fields |
| --- | --- |
| Case initialized | `requestId`, `clientId`, `caseId`, `userId`, `level`, `reuseStatus`, `status` |
| Upload request received | `requestId`, `caseId`, `documentType`, file metadata, metadata summary |
| Upload service started | `requestId`, `clientId`, `caseId`, `documentType`, storage metadata, metadata summary |
| S3 storage success/failure | bucket, object key, content type, byte size, error type on failure |
| Document/selfie analysis | type, validity, confidence, biometric/document signals without raw identity values |
| Document persisted | `documentId`, `caseId`, `documentType`, status, validity |
| Workflow scheduled/published | `requestId`, `caseId`, mode, exchange, routing key |
| Workflow decision | `requestId`, `clientId`, `caseId`, `caseStatus`, `productStatus`, `reasonCode`, score, document status, processing time |
| Verify completed | `requestId`, `clientId`, `caseId`, `caseStatus`, `productStatus`, `reasonCode`, `nextAction` |
| Admin review decision | `requestId`, `clientId`, `caseId`, `caseStatus`, `productStatus`, `reasonCode`, `nextAction` |

## Product Friction Metrics

Use `/admin/kyc/stats` daily during the pilot to identify:

| Metric | Product question | Possible action |
| --- | --- | --- |
| `friction.topUnreadableCauses` | Why are users being asked to retry? | Improve capture UX, OCR, supported type detection. |
| `friction.selfieFailureCauses.NO_FACE_DETECTED` | Are users missing face framing? | Improve camera framing and instructions. |
| `friction.selfieFailureCauses.LOW_QUALITY` | Are captures too blurry or dark? | Add pre-submit quality checks. |
| `friction.selfieFailureCauses.INCONCLUSIVE_FACE_MATCH` | Is biometric confidence too weak? | Improve face matching or thresholds. |
| `friction.declaredInfoMismatchCount` | Are users entering data inconsistent with documents? | Improve declared info form and correction flow. |
| `friction.leastPerformingDocumentTypes` | Which document types fail most often? | Prioritize OCR templates and document-specific capture guidance. |

## Security Hardening Review

Current MVP controls:

- Client Bearer token auth for public KYC endpoints.
- Signed API key mode with `X-API-KEY`, `X-TIMESTAMP`, `X-NONCE`, and `X-SIGNATURE`.
- Redis-backed nonce replay protection.
- Client rate limit per minute.
- Admin endpoints protected by admin role.
- Request correlation through `X-Request-Id` and `X-Correlation-Id`.
- Auth failure metrics by failure code and mode.

Pilot checklist:

- Set a strong `JWT_SECRET`.
- Set `SECURITY_MASTER_KEY` through secret management only.
- Rotate API keys before external pilot start.
- Keep `S3_ACCESS_KEY`, `S3_SECRET_KEY`, Redis, RabbitMQ, and DB credentials out of logs and files.
- Confirm Redis availability; otherwise rate limiting is explicitly degraded.
- Confirm `rateLimitPerMinute` per pilot client.
- Confirm admin endpoint access is not exposed publicly without admin auth.
- Confirm no raw KYC metadata, full names, document numbers, or phone numbers are logged in upload paths.

## Log Redaction Policy

For KYC upload logs:

- Log metadata presence, size, and key names only.
- Do not log raw `metadataJson`.
- Do not log raw declared identity values.
- Do not expose biometric scores to end users; they are operational/back-office signals.
- Keep `requestId`, `clientId`, `caseId`, and `documentId` visible for support.

## Pilot Operating Rhythm

Recommended cadence for the first pilot:

| Cadence | Action |
| --- | --- |
| Daily | Review `/admin/kyc/stats`, top reason codes, auth failures, and document type performance. |
| Twice weekly | Review sample failed cases with support and product. |
| Weekly | Decide one capture/UX or engine improvement from observed data. |
| End of pilot | Compare verified rate, resubmission rate, review rate, rejected rate, and average processing time against pilot goals. |

## Data-Driven V2 Backlog

Prioritize V2 using real pilot signals:

| If metric dominates | Prioritize |
| --- | --- |
| `DOCUMENT_UNREADABLE` | Production OCR, image quality scoring, document capture guidance, resize/orientation hardening. |
| `SELFIE_UNREADABLE` | Camera framing, selfie quality pre-checks, biometric provider integration. |
| `DECLARED_INFO_MISMATCH` | Better declared info UX, field normalization, edit-before-submit flow. |
| High `REVIEW` rate | Threshold tuning, operator console, manual review SLA, webhook notifications. |
| High `UNSUPPORTED_DOCUMENT_TYPE` | Country/document catalog, type detection, front-end allowed document selector. |
| High auth failures | Integrator onboarding, credential validation tooling, signature SDK/sample clients. |
| One document type has high failure rate | Document-specific OCR templates and capture tips. |

## Pilot Readiness Exit Criteria

- `/admin/kyc/stats` is accessible to admins only.
- Prometheus can scrape actuator metrics in the target environment.
- Support can trace any case by `requestId`, `caseId`, and `clientId`.
- KYC logs do not contain raw document metadata or declared identity fields.
- Product can see top failure causes without querying the database manually.
- A V2 backlog can be reordered from real reason code and document performance data.
