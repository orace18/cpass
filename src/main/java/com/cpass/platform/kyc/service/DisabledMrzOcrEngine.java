package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DisabledMrzOcrEngine implements MrzOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(DisabledMrzOcrEngine.class);

    @Override
    public String engineName() {
        return "disabled";
    }

    @Override
    public String extractText(byte[] mrzImageBytes) {
        log.debug("kyc mrz ocr engine=disabled skipping extraction");
        return null;
    }
}
