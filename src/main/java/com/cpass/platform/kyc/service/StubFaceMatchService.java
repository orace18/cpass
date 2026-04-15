package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MVP stub implementation of {@link FaceMatchService}.
 *
 * <p>Returns a configurable score (default 80) for any two non-empty images.
 * This stub allows the full pipeline to be exercised end-to-end without a
 * real face recognition model.
 *
 * <p>REPLACE this class with a real face comparison integration before
 * going to production (e.g., AWS Rekognition CompareFaces, Azure Face API,
 * DeepFace, InsightFace).
 *
 * <p>The score returned by this stub is driven by
 * {@code app.kyc.verification.stub.face-match-score} (default: 80).
 */
@Service
public class StubFaceMatchService implements FaceMatchService {

    private static final Logger log = LoggerFactory.getLogger(StubFaceMatchService.class);

    private final VerificationProperties properties;

    public StubFaceMatchService(VerificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public FaceMatchResult compare(byte[] face1Bytes, byte[] face2Bytes) {
        boolean face1Present = face1Bytes != null && face1Bytes.length > 0;
        boolean face2Present = face2Bytes != null && face2Bytes.length > 0;

        log.info("face match stub compare face1Present={} face2Present={}", face1Present, face2Present);

        if (!face1Present || !face2Present) {
            log.warn("face match stub one or both images missing — returning score=0");
            return new FaceMatchResult(face1Present, face2Present, false, 0);
        }

        int score = properties.stubFaceMatchScore();
        boolean matched = score >= properties.faceMatchThreshold();

        log.info("face match stub result score={} matched={} threshold={}", score, matched, properties.faceMatchThreshold());

        return new FaceMatchResult(true, true, matched, score);
    }
}
