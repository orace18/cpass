package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MrzOcrEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(MrzOcrEngineConfig.class);

    @Value("${app.kyc.ocr.engine:auto}")
    private String engineMode;

    @Bean
    public MrzOcrEngine mrzOcrEngine() {
        if ("disabled".equalsIgnoreCase(engineMode)) {
            log.info("kyc mrz ocr engine=disabled forced via config");
            return new DisabledMrzOcrEngine();
        }
        String binary = detectTesseractBinary();
        if (binary != null) {
            log.info("kyc mrz ocr engine=tesseract binary={}", binary);
            return new TesseractMrzOcrEngine(binary);
        }
        log.warn("kyc mrz ocr engine=disabled tesseract binary not found; mrz text extraction unavailable — install tesseract or set app.kyc.ocr.engine=disabled to suppress this warning");
        return new DisabledMrzOcrEngine();
    }

    private String detectTesseractBinary() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] candidates = os.contains("win")
                ? new String[]{"tesseract", "C:\\Program Files\\Tesseract-OCR\\tesseract.exe", "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"}
                : new String[]{"tesseract", "/usr/bin/tesseract", "/usr/local/bin/tesseract", "/opt/homebrew/bin/tesseract"};

        for (String candidate : candidates) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isExecutable(String binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
