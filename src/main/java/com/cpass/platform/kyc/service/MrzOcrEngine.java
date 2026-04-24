package com.cpass.platform.kyc.service;

public interface MrzOcrEngine {

    String engineName();

    /**
     * Extracts raw text from a pre-processed MRZ-zone image.
     * Returns null when the engine is unavailable or produces no output.
     */
    String extractText(byte[] mrzImageBytes);

    default String extractText(byte[] mrzImageBytes, int pageSegmentationMode) {
        return extractText(mrzImageBytes);
    }
}
