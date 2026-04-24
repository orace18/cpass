package com.cpass.platform.kyc.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class KycImageProcessingServiceTest {

    private final KycImageProcessingService service = new KycImageProcessingService();

    @Test
    void shouldRotatePortraitImageUsingExifOrientation() throws Exception {
        byte[] jpeg = withExifOrientation(jpeg(120, 80, new Color(180, 180, 180)), 6);

        KycImageProcessingService.ImageProcessingResult result =
                service.prepareOcrReady(jpeg, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isTrue();
        assertThat(result.originalWidth()).isEqualTo(120);
        assertThat(result.originalHeight()).isEqualTo(80);
        assertThat(result.processedWidth()).isEqualTo(80);
        assertThat(result.processedHeight()).isEqualTo(120);
        assertThat(result.transformationsApplied()).contains("auto_rotate_exif_90");
    }

    @Test
    void shouldResizeLargeImageWithoutDroppingBelowOcrFriendlyResolution() throws Exception {
        byte[] jpeg = jpeg(3600, 2400, new Color(190, 190, 190));

        KycImageProcessingService.ImageProcessingResult result =
                service.prepareOcrReady(jpeg, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isTrue();
        assertThat(Math.max(result.processedWidth(), result.processedHeight())).isEqualTo(2400);
        assertThat(result.processedWidth()).isGreaterThanOrEqualTo(1600);
        assertThat(result.transformationsApplied()).contains("resize_max_dimension_2400");
    }

    @Test
    void shouldApplyLightContrastForDarkImage() throws Exception {
        byte[] jpeg = jpeg(800, 500, new Color(35, 35, 35));

        KycImageProcessingService.ImageProcessingResult result =
                service.prepareOcrReady(jpeg, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isTrue();
        assertThat(result.transformationsApplied()).contains("contrast_light", "sharpen_light");
        assertThat(result.processedWidth()).isEqualTo(800);
        assertThat(result.processedHeight()).isEqualTo(500);
    }

    @Test
    void shouldKeepCorrectImageDimensionsAndAvoidExcessiveSizeGrowth() throws Exception {
        byte[] jpeg = jpeg(1000, 650, new Color(210, 210, 210));

        KycImageProcessingService.ImageProcessingResult result =
                service.prepareOcrReady(jpeg, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isTrue();
        assertThat(result.processedWidth()).isEqualTo(1000);
        assertThat(result.processedHeight()).isEqualTo(650);
        assertThat(result.processedSizeBytes()).isLessThan(jpeg.length * 4L);
        assertThat(result.transformationsApplied()).doesNotContain("resize_max_dimension_2400");
    }

    @Test
    void shouldFallbackToOriginalWhenPayloadCannotBeDecoded() {
        byte[] bytes = "not-an-image".getBytes();

        KycImageProcessingService.ImageProcessingResult result =
                service.prepareOcrReady(bytes, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isFalse();
        assertThat(result.processedBytes()).isEqualTo(bytes);
        assertThat(result.transformationsApplied()).contains("fallback_original_decode_failed");
    }

    @Test
    void shouldPrepareDedicatedMrzReadyCropFromBottomOfDocument() throws Exception {
        byte[] jpeg = passportLikeJpeg(1000, 700);

        KycImageProcessingService.MrzImageProcessingResult result =
                service.prepareMrzReady(jpeg, MediaType.IMAGE_JPEG_VALUE);

        assertThat(result.processed()).isTrue();
        assertThat(result.cropCoordinates()).isEqualTo("0,406,1000,294");
        assertThat(result.processedMimeType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(result.processedBytes()).isNotEmpty();
        assertThat(result.transformationsApplied())
                .contains("mrz_crop_bottom_42_percent", "mrz_upscale_x3", "mrz_grayscale", "mrz_contrast_strong", "mrz_threshold_adaptive", "mrz_sharpen_ocr");
    }

    private byte[] jpeg(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.drawString("CPASS KYC TEST 123456789", 20, Math.max(30, height / 2));
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private byte[] passportLikeJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(235, 235, 230));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.drawString("PASSPORT", 40, 80);
        graphics.drawString("P<BENDOE<<GRACE<<<<<<<<<<<<<<<<<<<<<<<<<<<<<", 40, height - 80);
        graphics.drawString("P1234567<1BEN9005204F2801108<<<<<<<<<<<<<<<<", 40, height - 45);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = exifOrientationSegment(orientation);
        byte[] combined = new byte[jpeg.length + app1.length];
        combined[0] = jpeg[0];
        combined[1] = jpeg[1];
        System.arraycopy(app1, 0, combined, 2, app1.length);
        System.arraycopy(jpeg, 2, combined, 2 + app1.length, jpeg.length - 2);
        return combined;
    }

    private byte[] exifOrientationSegment(int orientation) {
        byte[] payload = {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12,
                0, 3,
                0, 0, 0, 1,
                0, (byte) orientation, 0, 0,
                0, 0, 0, 0
        };
        int length = payload.length + 2;
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xe1;
        segment[2] = (byte) ((length >> 8) & 0xff);
        segment[3] = (byte) (length & 0xff);
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }
}
