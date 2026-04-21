package com.cpass.platform.kyc.service;

import java.time.LocalDate;

/**
 * Server-side OCR abstraction for identity document analysis.
 *
 * <p>The MVP uses {@link StubOcrService} which simulates OCR results.
 * In production, replace with a real implementation backed by a service
 * such as AWS Textract, Google Vision API, or an on-premise Tesseract instance.
 *
 * <p>IMPORTANT: implementations must never trust client-provided values.
 * All data must be extracted from the raw image bytes.
 */
public interface OcrService {

    /**
     * Extracts identity fields and portrait from a document image.
     *
     * @param imageBytes  raw bytes of the document image (front side)
     * @param contentType MIME type of the image (e.g., image/jpeg)
     * @return extraction result — never null
     */
    OcrResult extract(byte[] imageBytes, String contentType);

    record OcrResult(
            boolean readable,
            int confidence,
            String fullName,
            String documentNumber,
            LocalDate dateOfBirth,
            LocalDate expiryDate,
            String nationality,
            byte[] portraitBytes
    ) {
    }
}
