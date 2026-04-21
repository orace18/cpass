# KYC MVP POC Readiness

This note packages the CPaaS KYC MVP for a first commercial POC or pilot customer.

The public integration contract is:

- `POST /kyc/verify`
- `GET /kyc/status?caseId=...`
- `productStatus` is the main status for the integrator.
- `APPROVED` is internal and is exposed to product integrations as `VERIFIED`.

## MVP Positioning

The KYC MVP verifies a declared identity against an identity document and a selfie, then returns an actionable decision.

It is designed for a first assisted production pilot where the integrator needs clear user recovery paths, support traceability, and a stable API contract.

## What The MVP Does Today

| Capability | Current MVP behavior |
| --- | --- |
| Declared identity intake | Accepts user id, full name, birth date, phone number, document number, and contact verification flag. |
| Supported document types | Supports `PASSPORT`, `NATIONAL_ID`, `VOTER_CARD`, and `DRIVER_LICENSE`. |
| Document extraction | Uses MVP document signals to extract full name, birth date, document number, expiry date, and detected document type. |
| Document validation | Checks supported type, readability, required usable signals, and expiry date. |
| Identity comparison | Compares declared name, birth date, and document number when available. |
| Selfie analysis | Evaluates selfie usability, face detection, quality, document face presence, and face match score when provided. |
| Biometric decision | Produces explicit biometric signals and routes inconclusive cases to review. |
| Business decision | Returns `VERIFIED`, `RESUBMISSION_REQUIRED`, `REVIEW`, or `REJECTED` via `productStatus`. |
| User recovery | Exposes `reasonCode`, `nextAction`, `statusReason`, and `recommendation`. |
| Traceability | Exposes `requestId`, `clientId`, and `caseId`; every status can be queried. |

## What The MVP Does Not Do Yet

| Area | Known limit |
| --- | --- |
| OCR | No production-grade OCR engine in this lot. Extraction relies on MVP signals and local heuristics. |
| Liveness | No active or passive liveness detection. |
| Anti-spoofing | No deep selfie spoof detection. |
| Document fraud | No advanced template validation, MRZ checksum validation, hologram checks, or tamper detection. |
| Face recognition | Face matching is MVP signal-based, not a certified biometric engine. |
| Country-specific rules | No exhaustive country-by-country document rules. |
| Operations | No dedicated dashboard, analytics, billing, or manual-review console in this package. |

## Standard Demo Scenario

Use this script for a commercial demonstration.

1. Present the integrator contract: `POST /kyc/verify`, then `GET /kyc/status`.
2. Show that the integrator consumes `productStatus` first.
3. Authenticate a sandbox client with `POST /auth/token`.
4. Run a verified case with matching document metadata and high selfie score.
5. Run a document unreadable case and show `RESUBMISSION_REQUIRED` plus `RESUBMIT_DOCUMENT`.
6. Run a selfie unreadable case and show `RETRY_SELFIE_CAPTURE`.
7. Run a declared identity mismatch and show `UPDATE_DECLARED_INFO`.
8. Run a review case and show `WAIT_FOR_REVIEW`.
9. Query every case with `GET /kyc/status?caseId=...`.
10. Show support traceability with `requestId`, `caseId`, and `clientId`.

## Sandbox Test Set

These are the minimum cases to prepare for a pilot sandbox.

| Case | Declared identity | Document signals | Selfie signals | Expected productStatus | Expected action |
| --- | --- | --- | --- | --- | --- |
| Verified passport | Name, birth date, and document number match | `PASSPORT`, future expiry, matching extracted fields | `faceDetectedOnSelfie=true`, quality `90`, match score `92` | `VERIFIED` | Unlock verified journey |
| Document unreadable | Any valid declared identity | Empty or non-exploitable document signals | Usable selfie | `RESUBMISSION_REQUIRED` | `RESUBMIT_DOCUMENT` |
| Selfie unreadable | Valid declared identity | Valid document signals | `faceDetectedOnSelfie=false` or quality below `60` | `RESUBMISSION_REQUIRED` | `RETRY_SELFIE_CAPTURE` |
| Declared mismatch | Declared value differs from document | Extracted name, birth date, or document number contradicts declared data | Usable selfie | `RESUBMISSION_REQUIRED` | `UPDATE_DECLARED_INFO` |
| Manual review | Valid declared identity | Valid document signals | Missing or inconclusive match score, for example `65` | `REVIEW` | `WAIT_FOR_REVIEW` |
| Expired document | Valid declared identity | Expiry date in the past | Usable selfie | `REJECTED` | `RESUBMIT_DOCUMENT` with a valid document |

## Onboard A Test API Client

For a pilot client, prepare:

- Client public id.
- Client secret or signed API key material.
- Allowed scopes: `kyc:write` and `kyc:read`.
- Sandbox base URL.
- Rate limit policy for the pilot.
- Contact email or support channel for integration issues.
- Sample request payloads for all sandbox test cases.
- A request id convention, for example `clientname_env_flow_uuid`.
- A policy statement that `productStatus` is the primary client-facing status.

Recommended onboarding sequence:

1. Create the API client record.
2. Share sandbox base URL and credentials through the approved secret channel.
3. Confirm the client can call `POST /auth/token`.
4. Confirm `POST /kyc/verify` accepts multipart upload from the client backend.
5. Confirm the client stores `caseId` and `requestId`.
6. Confirm the client can call `GET /kyc/status?caseId=...`.
7. Walk through verified, resubmission, review, and rejected scenarios.
8. Review the client's user messages against the UX guide.
9. Confirm support can search logs by `requestId`, `caseId`, and `clientId`.

## Pilot Integration Checklist

| Check | Expected result |
| --- | --- |
| Authentication | Valid credentials succeed; invalid credentials return a clear `401`. |
| Correlation | Client sends `X-Request-Id`; API returns the same id. |
| Upload | Client can submit declared identity, document image, selfie image, and optional MVP metadata. |
| Status storage | Client stores `caseId` after every verification attempt. |
| Status polling | Client can retrieve the same decision through `GET /kyc/status`. |
| Routing | Client routes by `productStatus`, then `nextAction`, then `reasonCode`. |
| UX recovery | Client has screens for document retry, selfie retry, info correction, review wait, and rejection. |
| Error handling | Validation, auth, and unsupported document type errors are displayed clearly. |
| Support | Support tickets include `requestId`, `caseId`, and client user id. |
| Limits accepted | Pilot stakeholders understand OCR, liveness, anti-spoofing, and fraud limits. |

## Commercial Readiness Checklist

- Public contract documented in `docs/kyc-api-mvp.md`.
- Quickstart documented in `docs/kyc-integration-quickstart.md`.
- Reason codes and next actions documented with expected client behavior.
- UX messages proposed for all actionable outcomes.
- Demo script prepared.
- Sandbox test cases prepared.
- Test client onboarding path documented.
- MVP limits documented before pilot launch.
- Manual review or support escalation path agreed with the pilot client.

## Recommended V2 Roadmap

| Theme | Suggested V2 scope |
| --- | --- |
| OCR | Add a production OCR engine, MRZ parsing, checksum validation, and country-specific extraction. |
| Face recognition | Replace signal-based MVP matching with a robust face recognition provider or internal model. |
| Liveness | Add active/passive liveness checks for selfie capture. |
| Anti-spoofing | Add replay, print, screen, mask, and deepfake resistance signals. |
| Document fraud | Add template validation, tamper detection, document authenticity scoring, and fraud rules. |
| Manual review | Add a review console with operator decisions and audit trail. |
| Webhooks | Add callbacks for asynchronous case status changes. |
| Analytics | Add conversion, reason code, retry, and review funnel dashboards. |
| Billing | Add client-level usage metering and billable event tracking. |
| Policies | Add configurable thresholds by client, country, document type, and risk tier. |

## Pilot Exit Criteria

A pilot can be considered commercially successful when:

- The client can integrate without backend engineering support after receiving credentials and docs.
- All five main scenarios are demonstrable in sandbox.
- The client app can recover users from document unreadable, selfie unreadable, and mismatch outcomes.
- Support can trace any case by `requestId`, `caseId`, and `clientId`.
- No KYC case remains silent or stuck without a `productStatus`, `reasonCode`, or `nextAction` when action is required.
