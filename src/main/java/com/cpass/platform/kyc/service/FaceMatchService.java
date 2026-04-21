package com.cpass.platform.kyc.service;

/**
 * Server-side face comparison abstraction.
 *
 * <p>The MVP uses {@link StubFaceMatchService} which returns configurable
 * simulated scores. In production, replace with a real implementation backed
 * by a service such as AWS Rekognition, Azure Face API, or an on-premise
 * deep-learning model.
 *
 * <p>IMPORTANT: implementations must operate only on raw image bytes
 * passed by the server. They must never trust any client-provided scores,
 * hashes, or feature vectors.
 */
public interface FaceMatchService {

    /**
     * Compares two face images and returns a similarity score.
     *
     * @param face1Bytes raw bytes of the first face image (e.g., portrait extracted from document)
     * @param face2Bytes raw bytes of the second face image (e.g., selfie)
     * @return comparison result — never null
     */
    FaceMatchResult compare(byte[] face1Bytes, byte[] face2Bytes);

    record FaceMatchResult(
            boolean faceDetectedInFirst,
            boolean faceDetectedInSecond,
            boolean matched,
            int score
    ) {
    }
}
