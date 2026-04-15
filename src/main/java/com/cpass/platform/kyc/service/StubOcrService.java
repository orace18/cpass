package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * MVP stub implementation of {@link OcrService}.
 *
 * <p>This stub simulates server-side OCR by performing basic heuristics
 * on the uploaded file (non-empty, valid MIME type). It does NOT perform
 * real optical character recognition.
 *
 * <p>REPLACE this class with a real OCR provider integration before
 * going to production (e.g., AWS Textract, Google Vision, Tesseract).
 *
 * <p>The stub never reads or echoes client-provided metadata — it only
 * operates on raw image bytes, which is the correct server-side contract.
 */
@Service
public class StubOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(StubOcrService.class);

    @Override
    public OcrResult extract(byte[] imageBytes, String contentType) {
        log.info("ocr stub extract contentType={} bytes={}", contentType, imageBytes == null ? 0 : imageBytes.length);

        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("ocr stub received empty image — marking as unreadable");
            return new OcrResult(false, 0, null, null, null, null, null, null);
        }

        if (!isSupportedContentType(contentType)) {
            log.warn("ocr stub unsupported contentType={} — marking as unreadable", contentType);
            return new OcrResult(false, 10, null, null, null, null, null, null);
        }

        // Stub: treat any non-empty supported file as readable with moderate confidence.
        // A real OCR service would extract fields from the image pixels.
        int confidence = 80;
        LocalDate stubExpiryDate = LocalDate.now().plusYears(3);

        log.info("ocr stub result readable=true confidence={} expiryDate={}", confidence, stubExpiryDate);

        return new OcrResult(
                true,
                confidence,
                null,          // real OCR would extract fullName
                null,          // real OCR would extract documentNumber
                null,          // real OCR would extract dateOfBirth
                stubExpiryDate,
                null,          // real OCR would extract nationality
                null           // real OCR would extract portrait face region
        );
    }

    private boolean isSupportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.startsWith("image/") || normalized.equals("application/pdf");
    }
}
