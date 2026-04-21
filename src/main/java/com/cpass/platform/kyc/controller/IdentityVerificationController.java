package com.cpass.platform.kyc.controller;

import com.cpass.platform.common.security.ClientContextHolder;
import com.cpass.platform.kyc.dto.InitVerificationRequest;
import com.cpass.platform.kyc.dto.InitVerificationResponse;
import com.cpass.platform.kyc.dto.UploadVerificationResponse;
import com.cpass.platform.kyc.dto.VerificationResponse;
import com.cpass.platform.kyc.service.IdentityVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * MVP Identity Verification API.
 *
 * <p>This is the primary KYC endpoint. It performs server-side OCR, face
 * comparison, document validity checks, and reliability scoring. The client
 * must upload only raw files — no OCR results, face hashes, or scores are
 * accepted from the client.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@code POST /kyc/identity-verification/init} — create a verification session
 *   <li>{@code POST /kyc/identity-verification/{id}/upload} — upload document and selfie
 *   <li>{@code GET  /kyc/identity-verification/{id}} — poll for result
 * </ol>
 *
 * <h2>Example curl — init</h2>
 * <pre>
 * curl -X POST https://api.example.com/kyc/identity-verification/init \
 *   -H "Authorization: Bearer &lt;token&gt;" \
 *   -H "Content-Type: application/json" \
 *   -d '{"documentType":"NATIONAL_ID","firstName":"Jean","lastName":"Dupont","phoneNumber":"+22890000000"}'
 * </pre>
 *
 * <h2>Example curl — upload</h2>
 * <pre>
 * curl -X POST https://api.example.com/kyc/identity-verification/{verificationId}/upload \
 *   -H "Authorization: Bearer &lt;token&gt;" \
 *   -F "documentFront=@/path/to/id-front.jpg" \
 *   -F "selfie=@/path/to/selfie.jpg" \
 *   -F "captureDevice=mobile" \
 *   -F "platform=android"
 * </pre>
 *
 * <h2>Error codes</h2>
 * <ul>
 *   <li>{@code INVALID_FILE_FORMAT} — unsupported file type or missing required file
 *   <li>{@code VERIFICATION_NOT_FOUND} — no verification found for this client and ID
 *   <li>{@code VERIFICATION_ALREADY_SUBMITTED} — files already uploaded
 *   <li>{@code PROCESSING_FAILED} — internal storage or processing error
 * </ul>
 */
@Tag(name = "Identity Verification", description = "Server-side identity verification: OCR, face match, document validity")
@RestController
@RequestMapping("/kyc/identity-verification")
public class IdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;

    public IdentityVerificationController(IdentityVerificationService identityVerificationService) {
        this.identityVerificationService = identityVerificationService;
    }

    @Operation(
            summary = "Initialize a verification session",
            description = "Creates a new identity verification session. Returns a verificationId to be used in subsequent calls.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Verification session created",
                            content = @Content(schema = @Schema(implementation = InitVerificationResponse.class),
                                    examples = @ExampleObject(value = """
                                            {"verificationId":"550e8400-e29b-41d4-a716-446655440000","status":"INITIATED"}
                                            """)))
            }
    )
    @PostMapping("/init")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public InitVerificationResponse init(@Valid @RequestBody InitVerificationRequest request) {
        UUID clientId = ClientContextHolder.requireClientId();
        return identityVerificationService.init(clientId, request);
    }

    @Operation(
            summary = "Upload document and selfie",
            description = """
                    Upload the identity document (front and optionally back) and a selfie.
                    All OCR and face matching is performed server-side.
                    Do NOT send pre-computed hashes, OCR results, or face scores.
                    Returns status PROCESSING immediately — poll GET /{verificationId} for the result.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Files accepted, processing started",
                            content = @Content(schema = @Schema(implementation = UploadVerificationResponse.class),
                                    examples = @ExampleObject(value = """
                                            {"verificationId":"550e8400-e29b-41d4-a716-446655440000","status":"PROCESSING"}
                                            """)))
            }
    )
    @PostMapping(value = "/{verificationId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public UploadVerificationResponse upload(
            @PathVariable UUID verificationId,
            @RequestPart("documentFront") MultipartFile documentFront,
            @RequestPart(value = "documentBack", required = false) MultipartFile documentBack,
            @RequestPart("selfie") MultipartFile selfie,
            @RequestParam(required = false) String captureDevice,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String platform) {
        UUID clientId = ClientContextHolder.requireClientId();
        return identityVerificationService.upload(clientId, verificationId,
                documentFront, documentBack, selfie, captureDevice, appVersion, platform);
    }

    @Operation(
            summary = "Get verification result",
            description = """
                    Poll this endpoint to retrieve the verification result.
                    Status transitions: INITIATED → PROCESSING → APPROVED | REVIEW | REJECTED
                    When status is APPROVED or REJECTED, the result is final.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Verification result",
                            content = @Content(schema = @Schema(implementation = VerificationResponse.class),
                                    examples = @ExampleObject(value = """
                                            {
                                              "verificationId": "550e8400-e29b-41d4-a716-446655440000",
                                              "status": "APPROVED",
                                              "currentStep": "COMPLETED",
                                              "documentAnalysis": {
                                                "documentReadable": true,
                                                "documentExpired": false,
                                                "documentType": "NATIONAL_ID",
                                                "documentNumber": "AB**7890",
                                                "fullName": "Jean Dupont",
                                                "dateOfBirth": "1990-05-20",
                                                "expiryDate": "2030-05-20"
                                              },
                                              "faceMatch": { "matched": true, "score": 87 },
                                              "reliabilityScore": 84,
                                              "scoreDetails": {
                                                "documentReadability": 25,
                                                "ocrConfidence": 16,
                                                "documentValidity": 20,
                                                "faceMatch": 30
                                              },
                                              "decisionReason": "FACE_MATCH_OK_AND_DOCUMENT_VALID"
                                            }
                                            """)))
            }
    )
    @GetMapping("/{verificationId}")
    @PreAuthorize("hasAuthority('SCOPE_kyc:read')")
    public VerificationResponse get(@PathVariable UUID verificationId) {
        UUID clientId = ClientContextHolder.requireClientId();
        return identityVerificationService.getVerification(clientId, verificationId);
    }
}
