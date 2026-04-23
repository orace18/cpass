-- psql -v case_id='f37d9d68-92a6-46d2-b7d9-88efad6657aa' -f scripts/audit-kyc-document-linkage.sql
-- Audits the physical document linkage for one CPaaS KYC case.

\if :{?case_id}
\else
\set case_id 'f37d9d68-92a6-46d2-b7d9-88efad6657aa'
\endif

\echo '1) kyc_cases + identity'
SELECT
    kc.id,
    kc.reference_id,
    kc.client_id,
    kc.identity_id,
    i.document_number AS identity_document_number,
    i.phone_number AS identity_phone_number,
    i.full_name AS identity_full_name,
    kc.user_id,
    kc.level,
    kc.status,
    kc.score,
    kc.status_reason,
    kc.document_expiry_date,
    kc.valid_until,
    kc.created_at,
    kc.updated_at,
    kc.last_processed_at,
    kc.metadata_json
FROM kyc_cases kc
LEFT JOIN identities i ON i.id = kc.identity_id
WHERE kc.id = :'case_id'::uuid;

\echo '2) identity_kyc mapping'
SELECT
    ik.id,
    ik.identity_id,
    ik.kyc_case_id,
    ik.level,
    ik.status,
    ik.valid_until,
    ik.created_at,
    ik.updated_at
FROM identity_kyc ik
WHERE ik.kyc_case_id = :'case_id'::uuid;

\echo '3) documents linked by kyc_documents.kyc_case_id'
SELECT
    d.id,
    d.client_id,
    d.kyc_case_id,
    d.document_type,
    d.file_name,
    d.object_key,
    d.bucket_name,
    d.preview_path,
    d.content_type,
    d.size_bytes,
    d.status,
    d.validity_status,
    d.document_number,
    d.expiry_date,
    d.review_reason,
    d.created_at,
    d.updated_at,
    d.reviewed_at,
    d.metadata_json
FROM kyc_documents d
WHERE d.kyc_case_id = :'case_id'::uuid
ORDER BY d.created_at ASC;

\echo '4) documents for same client around case creation'
WITH c AS (
    SELECT client_id, reference_id, created_at
    FROM kyc_cases
    WHERE id = :'case_id'::uuid
)
SELECT
    d.id,
    d.kyc_case_id,
    d.document_type,
    d.status,
    d.validity_status,
    d.file_name,
    d.object_key,
    d.created_at,
    d.updated_at,
    (d.object_key LIKE ('%' || c.reference_id || '%')) AS object_key_mentions_reference
FROM kyc_documents d
CROSS JOIN c
WHERE d.client_id = c.client_id
  AND d.created_at BETWEEN c.created_at - INTERVAL '30 minutes' AND now() + INTERVAL '5 minutes'
ORDER BY d.created_at ASC;

\echo '5) orphan documents for same client around case creation'
WITH c AS (
    SELECT client_id, reference_id, created_at
    FROM kyc_cases
    WHERE id = :'case_id'::uuid
)
SELECT
    d.id,
    d.kyc_case_id,
    d.document_type,
    d.status,
    d.validity_status,
    d.file_name,
    d.object_key,
    d.created_at,
    d.updated_at,
    (d.object_key LIKE ('%' || c.reference_id || '%')) AS object_key_mentions_reference
FROM kyc_documents d
CROSS JOIN c
WHERE d.client_id = c.client_id
  AND d.kyc_case_id IS NULL
  AND d.created_at BETWEEN c.created_at - INTERVAL '30 minutes' AND now() + INTERVAL '5 minutes'
ORDER BY d.created_at ASC;

\echo '6) documents whose object_key contains this case reference_id'
WITH c AS (
    SELECT client_id, reference_id
    FROM kyc_cases
    WHERE id = :'case_id'::uuid
)
SELECT
    d.id,
    d.kyc_case_id,
    d.document_type,
    d.status,
    d.validity_status,
    d.file_name,
    d.object_key,
    d.created_at,
    d.updated_at
FROM kyc_documents d
CROSS JOIN c
WHERE d.client_id = c.client_id
  AND d.object_key LIKE ('%' || c.reference_id || '%')
ORDER BY d.created_at ASC;

\echo '7) linkage counts'
WITH c AS (
    SELECT id, client_id, reference_id, created_at
    FROM kyc_cases
    WHERE id = :'case_id'::uuid
)
SELECT
    (SELECT count(*) FROM kyc_documents d WHERE d.kyc_case_id = c.id) AS linked_to_case,
    (SELECT count(*) FROM kyc_documents d WHERE d.client_id = c.client_id AND d.kyc_case_id IS NULL) AS same_client_orphans_total,
    (SELECT count(*) FROM kyc_documents d WHERE d.client_id = c.client_id AND d.kyc_case_id IS NULL AND d.object_key LIKE ('%' || c.reference_id || '%')) AS recoverable_reference_orphans,
    (SELECT count(*) FROM kyc_documents d WHERE d.client_id = c.client_id AND d.object_key LIKE ('%' || c.reference_id || '%')) AS same_reference_object_keys
FROM c;
