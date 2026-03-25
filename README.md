# Internal CPaaS Platform (Spring Boot 3 / Java 21)

Centralized internal messaging and OTP platform with clean modular architecture:

- `client`: client onboarding, API key/secret generation, quota profiles
- `otp`: secure OTP generation + verification (hashed, TTL, retry, cooldown via Redis)
- `messaging`: SMS/WhatsApp request APIs + async dispatch through RabbitMQ
- `provider`: provider strategy abstraction + fallback routing
- `billing`: usage/cost tracking + monthly quota enforcement
- `kyc`: document upload to S3-compatible storage + status workflow
- `webhook`: delivery callback ingestion
- `common`: shared security (HMAC), exception model, infra config

## Tech Stack

- Java 21, Spring Boot 3.4.4, Maven
- PostgreSQL + JPA/Hibernate + Flyway
- Redis (nonce, OTP cache, rate limiting)
- RabbitMQ (async messaging jobs)
- OpenAPI / Swagger UI (`/swagger-ui.html`)
- Structured JSON logs (Logback + Logstash encoder)

## API Summary

- `POST /clients`
- `POST /clients/{clientId}/api-keys`
- `POST /otp/send`
- `POST /otp/verify`
- `POST /messages/sms`
- `POST /messages/whatsapp`
- `POST /kyc/documents`
- `PATCH /kyc/documents/{documentId}/status`
- `POST /webhooks/delivery`

## HMAC Authentication

Required headers for protected APIs:

- `X-API-KEY`
- `X-TIMESTAMP` (epoch seconds)
- `X-NONCE`
- `X-SIGNATURE`

Signature payload format (`\n` joined):

```text
HTTP_METHOD
REQUEST_PATH
TIMESTAMP
NONCE
SHA256_HEX(BODY_BYTES)
```

Then:

```text
X-SIGNATURE = HMAC_SHA256_HEX(clientSecret, payload)
```

Implementation: `com.cpass.platform.common.security.ApiAuthenticationFilter`.

## Run Locally

```bash
docker compose up -d
mvn spring-boot:run
```

## Test

```bash
mvn test
```
