# KYC Integration Quickstart

This guide is for a client backend or mobile team integrating the CPaaS KYC MVP for a first POC.

Use `productStatus` as the main integrator status. `caseStatus` is exposed for traceability but should not drive the end-user UX unless a support or back-office flow needs it.

## 1. Prerequisites

Before calling KYC endpoints, the integrator needs:

- API base URL.
- Client credentials for `POST /auth/token`, or signed API key headers.
- Scope `kyc:write` for `POST /kyc/verify`.
- Scope `kyc:read` for `GET /kyc/status`.
- A stable `userId` from the integrator system.
- Declared identity fields: full name, date of birth when available, phone number, document number when available.
- One identity document image.
- One selfie image.

Send `X-Request-Id` on every call. Store it in your own logs with the user/session id.

## 2. Get a Bearer Token

```http
POST /auth/token
Content-Type: application/json
```

```json
{
  "clientId": "client_public_id",
  "clientSecret": "client_secret"
}
```

Use the returned token:

```http
Authorization: Bearer <token>
```

## 3. Run KYC Verification

```http
POST /kyc/verify?documentType=PASSPORT
Authorization: Bearer <token>
X-Request-Id: req_kyc_001
Content-Type: multipart/form-data
```

Multipart parts:

- `identity`: JSON declared identity.
- `document`: identity document image.
- `selfie`: selfie image.
- `documentMetadata`: optional MVP extraction signals.
- `selfieMetadata`: optional MVP biometric signals.

Example `identity`:

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

Example `documentMetadata`:

```json
{
  "documentType": "PASSPORT",
  "fullName": "DOE GRACE",
  "birthDate": "1990-05-20",
  "expiryDate": "2028-01-10",
  "documentNumber": "P1234567"
}
```

Example `selfieMetadata`:

```json
{
  "faceDetectedOnSelfie": true,
  "selfieQuality": 90,
  "faceDetectedOnDocument": true,
  "faceMatchScore": 92
}
```

## 4. Handle the Response

Happy path:

```json
{
  "requestId": "req_kyc_001",
  "caseId": "8b819d2a-6327-4753-94c7-c0033ac69842",
  "caseStatus": "APPROVED",
  "productStatus": "VERIFIED",
  "reasonCode": null,
  "nextAction": null,
  "confidenceScore": 95
}
```

Minimal client-side routing:

```text
if productStatus == "VERIFIED":
    unlock the verified user journey
elif productStatus == "RESUBMISSION_REQUIRED":
    route by nextAction
elif productStatus == "REVIEW":
    show pending manual review
elif productStatus == "REJECTED":
    show rejected state and support path
else:
    poll GET /kyc/status
```

## 5. Poll Status

```http
GET /kyc/status?caseId=8b819d2a-6327-4753-94c7-c0033ac69842
Authorization: Bearer <token>
X-Request-Id: req_status_001
```

Use polling when:

- `productStatus=REVIEW`
- `caseStatus` is an intermediate state such as `INITIATED`, `CONTACT_VERIFIED`, or `IDENTITY_PRECHECKED`
- the mobile app lost the original `/kyc/verify` response

## 6. Recovery Scenarios

### Document unreadable

Response:

```json
{
  "productStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "DOCUMENT_UNREADABLE",
  "nextAction": "RESUBMIT_DOCUMENT"
}
```

Client behavior: reopen document capture and ask for a clear, uncropped image.

### Selfie unreadable

Response:

```json
{
  "productStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "SELFIE_UNREADABLE",
  "nextAction": "RETRY_SELFIE_CAPTURE"
}
```

Client behavior: reopen selfie capture.

### Declared info mismatch

Response:

```json
{
  "productStatus": "RESUBMISSION_REQUIRED",
  "reasonCode": "DECLARED_INFO_MISMATCH",
  "nextAction": "UPDATE_DECLARED_INFO"
}
```

Client behavior: let the user correct their declared name, birth date, or document number.

### Manual review

Response:

```json
{
  "productStatus": "REVIEW",
  "reasonCode": "MANUAL_REVIEW_REQUIRED",
  "nextAction": "WAIT_FOR_REVIEW"
}
```

Client behavior: show pending review and avoid asking the user to retry immediately.

## 7. UX Message Guide

| reasonCode | nextAction | Recommended user message | Button / CTA |
| --- | --- | --- | --- |
| `DOCUMENT_UNREADABLE` | `RESUBMIT_DOCUMENT` | We could not read your document. Please upload a clearer photo. | Retake document photo |
| `SELFIE_UNREADABLE` | `RETRY_SELFIE_CAPTURE` | We could not detect your face clearly. Please retake your selfie in good light. | Retake selfie |
| `DOCUMENT_EXPIRED` | `RESUBMIT_DOCUMENT` | This document appears to be expired. Please use a valid document. | Upload another document |
| `DECLARED_INFO_MISMATCH` | `UPDATE_DECLARED_INFO` | Your details do not match the document. Please check your information. | Review my details |
| `UNSUPPORTED_DOCUMENT_TYPE` | `RESUBMIT_DOCUMENT` | This document type is not supported. Please use a passport, national ID, voter card, or driver license. | Choose another document |
| `MANUAL_REVIEW_REQUIRED` | `WAIT_FOR_REVIEW` | Your verification is under review. We will update you soon. | View status |
| `MISSING_REQUIRED_DOCUMENT` | `RESUBMIT_DOCUMENT` | A required document is missing. Please add it to continue. | Add document |
| `WAITING_FOR_CONTACT_VERIFICATION` | `WAIT_FOR_REVIEW` or null | Please complete contact verification to continue. | Verify contact |

Keep messages short. Do not expose internal details such as OCR, face match score, or model confidence to the end user.

## 8. Sandbox Test Cases

Use these metadata variants in a POC sandbox.

| Scenario | documentMetadata | selfieMetadata | Expected productStatus |
| --- | --- | --- | --- |
| Verified passport | full name, birth date, future expiry, matching document number | face detected, quality `90`, match score `92` | `VERIFIED` |
| Document unreadable | `{}` | face detected, quality `90`, match score `92` | `RESUBMISSION_REQUIRED` |
| Selfie unreadable | valid document metadata | `{"faceDetectedOnSelfie":false,"selfieQuality":85}` | `RESUBMISSION_REQUIRED` |
| Declared mismatch | document number differs from declared identity | face detected, quality `90`, match score `92` | `RESUBMISSION_REQUIRED` |
| Review | valid document metadata | face detected, quality `86`, match score `65` | `REVIEW` |

## 9. Integration Checklist

- Store `caseId` after every `/kyc/verify` response.
- Store `requestId` in client logs and support tickets.
- Treat `productStatus=VERIFIED` as the only verified state.
- Drive user retry flows from `nextAction`, not from raw `caseStatus`.
- Show friendly UX copy from `reasonCode`.
- Poll `/kyc/status` after network failures or review states.
- Keep document and selfie capture flows separate.
- Do not expose biometric scores to end users.
