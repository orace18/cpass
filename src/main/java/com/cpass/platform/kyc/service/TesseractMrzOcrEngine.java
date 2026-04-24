package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TesseractMrzOcrEngine implements MrzOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractMrzOcrEngine.class);
    private static final int TIMEOUT_SECONDS = 10;

    private final String tesseractBinary;

    public TesseractMrzOcrEngine(String tesseractBinary) {
        this.tesseractBinary = tesseractBinary;
    }

    @Override
    public String engineName() {
        return "tesseract";
    }

    @Override
    public String extractText(byte[] mrzImageBytes) {
        return extractText(mrzImageBytes, 6);
    }

    @Override
    public String extractText(byte[] mrzImageBytes, int pageSegmentationMode) {
        if (mrzImageBytes == null || mrzImageBytes.length == 0) {
            return null;
        }
        Path tempInput = null;
        try {
            tempInput = Files.createTempFile("cpass-mrz-", ".jpg");
            Files.write(tempInput, mrzImageBytes);

            ProcessBuilder pb = new ProcessBuilder(List.of(
                    tesseractBinary,
                    tempInput.toAbsolutePath().toString(),
                    "stdout",
                    "--psm", String.valueOf(pageSegmentationMode),
                    "-l", "eng",
                    "-c", "tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
            ));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("kyc mrz ocr engine=tesseract timeout after {}s bytes={} psm={}", TIMEOUT_SECONDS, mrzImageBytes.length, pageSegmentationMode);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("kyc mrz ocr engine=tesseract exit={} bytes={} psm={}", process.exitValue(), mrzImageBytes.length, pageSegmentationMode);
                return null;
            }

            String cleaned = output.trim();
            if (cleaned.isBlank()) {
                log.debug("kyc mrz ocr engine=tesseract output empty bytes={} psm={}", mrzImageBytes.length, pageSegmentationMode);
                return null;
            }
            return cleaned;

        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("kyc mrz ocr engine=tesseract error={} bytes={} psm={}", exception.getMessage(), mrzImageBytes.length, pageSegmentationMode);
            return null;
        } finally {
            if (tempInput != null) {
                try {
                    Files.deleteIfExists(tempInput);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
