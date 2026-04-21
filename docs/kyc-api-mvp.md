# KYC API MVP

This document describes the commercial MVP contract for the internal CPaaS KYC API.

Related documents:

- `docs/kyc-integration-quickstart.md`: step-by-step integrator quickstart and UX messages.
- `docs/kyc-poc-readiness.md`: pilot onboarding, demo plan, MVP positioning and V2 roadmap.

## Integrator Contract Summary

Integrators should consume `productStatus` as the primary user-facing status.

Priority order for client applications:

1. Use `productStatus` to decide the high-level user journey.
2. Use `nextAction` to decide the next screen or user task.
3. Use `reasonCode` for deterministic branching and analytics.
4. Use `statusReason` and `recommendation` for operator/support context.
5. Use `extractedFields` and `biometricSignals` for audit, display, or back-office review only.

`caseStatus` remains the internal workflow status. `APPROVED` is mapped to `productStatus=VERIFIED` for product integrations.

## Authentication

Public KYC endpoints require client authentication.

Supported MVP modes:

- Bearer token: call `POST /auth/token` with client credentials, then send `Authorization: Bearer <token>`.
- Signed API key request: send `X-API-KEY`, `X-TIMESTAMP`, `X-NONCE`, `X-SIGNATURE`.

Every response includes `X-Request-Id`. Integrators may send `X-Request-Id`; otherwise the API generates one.

## Endpoints

### POST /kyc/verify

Creates or reuses a KYC case, uploads the identity document and selfie, runs the MVP decision workflow, and returns the current case status.

Content type: `multipart/form-data`

Parts:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| identity | JSON | yes | Declared identity fields |
| documentType | query/form value | yes | `PASSPORT`, `NATIONAL_ID`, `VOTER_CARD`, `DRIVER_LICENSE` |
| document | file | yes | Identity document image |
| selfie | file | yes | Selfie image |
| documentMetadata | JSON | no | MVP OCR/extraction signals |
| selfieMetadata | JSON | no | MVP biometric signals |

Example:

```http
POST /kyc/verify HTTP/1.1
Authorization: Bearer <token>
X-Request-Id: req_kyc_001
Content-Type: multipart/form-data
```

`identity`:

```json
{
  "userId": "user_123",
  "fullName": "Grace Doe",
  "birthDate": "1990-05-20",
  "phoneNumber": "+22962000000",
  "documentNumber": "P1234567",
  "contactVerified": true
}
```

`documentMetadata`:

```json
{
  "documentType": "PASSPORT",
  "fullName": "DOE GRACE",
  "birthDate": "1990-05-20",
  "expiryDate": "2028-01-10",
  "documentNumber": "P1234567"
}
```

`selfieMetadata`:

```json
{
  "faceDetectedOnSelfie": true,
  "selfieQuality": 90,
  "faceDetectedOnDocument": true,
  "faceMatchScore": 92
}
```

Success response:

```json
{
  "requestId": "req_kyc_001",
  "clientId": "0f0586f7-0c7d-4ca6-b61d-876d2d1df21d",
  "caseId": "8b819d2a-6327-4753-94c7-c0033ac69842",
  "status": "APPROVED",
  "caseStatus": "APPROVED",
  "productStatus": "VERIFIED",
  "reasonCode": null,
  "nextAction": null,
  "recommendation": null,
  "confidenceScore": 95,
  "extractedFields": {
    "detectedDocumentType": "PASSPORT",
    "supportedDocumentType": true,
    "fullName": "DOE GRACE",
    "birthDate": "1990-05-20",
    "documentNumber": "P1234567",
    "expiryDate": "2028-01-10",
    "biometricSignals": {
      "faceDetectedOnSelfie": true,
      "selfieReadability": true,
      "selfieQuality": 90,
      "faceDetectedOnDocument": true,
      "faceMatchScore": 92,
      "faceDecision": "MATCH",
      "biometricConfidenceScore": 91
    }
  }
}
```

`APPROVED` is the internal status. Integrator-facing product status is `VERIFIED`.

### GET /kyc/status?caseId=...

Returns the current state and actionability for a KYC case.

```http
GET /kyc/status?caseId=8b819d2a-6327-4753-94c7-c0033ac69842
Authorization: Bearer <token>
X-Request-Id: req_status_001
```

## Statuses

| Internal caseStatus | Product meaning |
| --- | --- |
| `APPROVED` | Expose as `productStatus=VERIFIED` |
| `RESUBMISSION_REQUIRED` | User must correct or resubmit input |
| `REVIEW` | Operator/manual review is required |
| `REJECTED` | Case cannot be approved with current evidence |
| `INITIATED`, `CONTACT_VERIFIED`, `IDENTITY_PRECHECKED` | Processing or waiting for missing step |

## reasonCode / nextAction Reference

| reasonCode | Meaning | nextAction | Recommendation | Expected integrator behavior |
| --- | --- | --- | --- | --- |
| `DOCUMENT_UNREADABLE` | Identity document cannot be exploited | `RESUBMIT_DOCUMENT` | Ask the user to resubmit a clear, uncropped document image with readable text. | Return the user to document capture/upload. |
| `SELFIE_UNREADABLE` | Selfie cannot be exploited or no face was detected | `RETRY_SELFIE_CAPTURE` | Ask the user to retake a well-lit selfie and submit it again. | Return the user to selfie capture. |
| `DOCUMENT_EXPIRED` | Identity document is expired | `RESUBMIT_DOCUMENT` | Ask the user to submit a non-expired document. | Ask for a new valid identity document. |
| `DECLARED_INFO_MISMATCH` | Declared identity does not match extracted document fields | `UPDATE_DECLARED_INFO` | Ask the user to correct declared identity information or submit the matching document. | Let the user correct name/birth date/document number or restart with another document. |
| `UNSUPPORTED_DOCUMENT_TYPE` | Submitted type is not accepted by the commercial MVP | `RESUBMIT_DOCUMENT` | Ask the user to submit a passport, national ID, voter card or driver license. | Show supported document types and restart document capture. |
| `MANUAL_REVIEW_REQUIRED` | Signals are insufficient, inconclusive, or require operator review | `WAIT_FOR_REVIEW` | Wait for an operator review before asking the user to retry. | Show pending review state; do not loop the user through capture. |
| `MISSING_REQUIRED_DOCUMENT` | Required document is missing | `RESUBMIT_DOCUMENT` | Ask the user to provide the missing required document. | Ask for the missing file before retrying verification. |
| `WAITING_FOR_CONTACT_VERIFICATION` | Contact verification is still required | `WAIT_FOR_REVIEW` or null | Complete the contact verification step before final KYC decision. | Complete OTP/contact step or poll status. |

## nextAction Values

| nextAction | Integrator action |
| --- | --- |
| `RESUBMIT_DOCUMENT` | Ask user to capture/upload a clearer or supported identity document. |
| `RETRY_SELFIE_CAPTURE` | Ask user to retake a well-lit selfie. |
| `UPDATE_DECLARED_INFO` | Ask user to correct declared identity data or submit the matching document. |
| `WAIT_FOR_REVIEW` | Do not ask the user to retry; wait for operator decision. |

## Scenarios

### Verified

Document fields match declared identity, document is not expired, selfie is usable, and `faceMatchScore >= 80`.

Result:

```json
{
  "caseStatus": "APPROVED",
  "productStatus": "VERIFIED",
  "reasonCode": null,
  "nextAction": null
}
```

### Document unreadable

No usable document fields or OCR signal is available.

```json
{
  "caseStatus": "RESUBMISSION_REQUIRED",
  "productStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "DOCUMENT_UNREADABLE",
  "nextAction": "RESUBMIT_DOCUMENT",
  "recommendation": "Ask the user to resubmit a clear, uncropped document image with readable text."
}
```

### Selfie unreadable

Selfie quality is below 60, selfie readability is false, or no face is detected.

```json
{
  "caseStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "SELFIE_UNREADABLE",
  "nextAction": "RETRY_SELFIE_CAPTURE"
}
```

### Declared info mismatch

Declared name, birth date, or document number contradicts extracted document fields.

```json
{
  "caseStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "DECLARED_INFO_MISMATCH",
  "nextAction": "UPDATE_DECLARED_INFO"
}
```

### Review

Document is coherent but biometric match is missing or inconclusive.

```json
{
  "caseStatus": "REVIEW",
  "reasonCode": "MANUAL_REVIEW_REQUIRED",
  "nextAction": "WAIT_FOR_REVIEW"
}
```

## Error Responses

Validation error:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/kyc/verify",
  "requestId": "req_invalid_001"
}
```

Authentication error:

```json
{
  "status": 401,
  "code": "AUTH_HEADERS_REQUIRED",
  "message": "Missing authentication headers",
  "requestId": "req_auth_001"
}
```

Unsupported document type on `POST /kyc/verify`:

```json
{
  "status": 400,
  "code": "UNSUPPORTED_DOCUMENT_TYPE",
  "message": "documentType must be PASSPORT, NATIONAL_ID, VOTER_CARD or DRIVER_LICENSE",
  "path": "/kyc/verify",
  "requestId": "req_doc_type_001"
}
```

## MVP Limits

- No liveness detection.
- No anti-spoofing.
- No advanced OCR engine in this lot.
- Biometric matching is based on explicit MVP signals, not a production-grade face recognition engine.
- Manual review remains the safe outcome for inconclusive or strong mismatch biometric signals.
