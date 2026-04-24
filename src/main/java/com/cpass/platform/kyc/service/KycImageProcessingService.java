package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Service
public class KycImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(KycImageProcessingService.class);

    private static final int MAX_DIMENSION = 2400;
    private static final float JPEG_QUALITY = 0.92f;

    public ImageProcessingResult prepareOcrReady(byte[] originalBytes, String mimeType) {
        String normalizedMimeType = normalizeMimeType(mimeType);
        ImageDimensions originalDimensions = readDimensions(originalBytes);
        List<String> transformations = new ArrayList<>();

        if (!isProcessableImage(normalizedMimeType) || originalBytes == null || originalBytes.length == 0) {
            transformations.add("fallback_original_unprocessable");
            return fallback(originalBytes, normalizedMimeType, originalDimensions, transformations);
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (image == null) {
                transformations.add("fallback_original_decode_failed");
                return fallback(originalBytes, normalizedMimeType, originalDimensions, transformations);
            }

            int exifOrientation = readExifOrientation(originalBytes);
            BufferedImage oriented = applyExifOrientation(image, exifOrientation, transformations);
            BufferedImage resized = resizeIfNeeded(oriented, transformations);
            BufferedImage enhanced = applyLightEnhancement(resized, transformations);
            byte[] processedBytes = encode(enhanced, normalizedMimeType);

            if (processedBytes.length == 0) {
                transformations.add("fallback_original_encode_failed");
                return fallback(originalBytes, normalizedMimeType, originalDimensions, transformations);
            }

            return new ImageProcessingResult(
                    originalDimensions.width(),
                    originalDimensions.height(),
                    originalBytes.length,
                    enhanced.getWidth(),
                    enhanced.getHeight(),
                    processedBytes.length,
                    normalizedMimeType,
                    compressionQuality(normalizedMimeType),
                    transformations,
                    processedBytes,
                    normalizedMimeType,
                    true
            );
        } catch (Exception exception) {
            log.warn("kyc image processing failed; falling back to original mimeType={} bytes={} error={}",
                    normalizedMimeType,
                    originalBytes == null ? 0 : originalBytes.length,
                    exception.getMessage());
            transformations.add("fallback_original_processing_error");
            return fallback(originalBytes, normalizedMimeType, originalDimensions, transformations);
        }
    }

    private ImageProcessingResult fallback(byte[] originalBytes,
                                           String mimeType,
                                           ImageDimensions originalDimensions,
                                           List<String> transformations) {
        byte[] bytes = originalBytes == null ? new byte[0] : originalBytes;
        return new ImageProcessingResult(
                originalDimensions.width(),
                originalDimensions.height(),
                bytes.length,
                originalDimensions.width(),
                originalDimensions.height(),
                bytes.length,
                mimeType,
                null,
                transformations,
                bytes,
                mimeType,
                false
        );
    }

    private boolean isProcessableImage(String mimeType) {
        return MediaType.IMAGE_JPEG_VALUE.equals(mimeType)
                || "image/jpg".equals(mimeType)
                || MediaType.IMAGE_PNG_VALUE.equals(mimeType);
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? MediaType.IMAGE_JPEG_VALUE : normalized;
    }

    private ImageDimensions readDimensions(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ImageDimensions(null, null);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null
                    ? new ImageDimensions(null, null)
                    : new ImageDimensions(image.getWidth(), image.getHeight());
        } catch (Exception ignored) {
            return new ImageDimensions(null, null);
        }
    }

    private BufferedImage applyExifOrientation(BufferedImage image, int orientation, List<String> transformations) {
        return switch (orientation) {
            case 3 -> {
                transformations.add("auto_rotate_exif_180");
                yield rotate(image, 180);
            }
            case 6 -> {
                transformations.add("auto_rotate_exif_90");
                yield rotate(image, 90);
            }
            case 8 -> {
                transformations.add("auto_rotate_exif_270");
                yield rotate(image, 270);
            }
            default -> image;
        };
    }

    private BufferedImage rotate(BufferedImage source, int degrees) {
        boolean rightAngle = degrees == 90 || degrees == 270;
        int width = rightAngle ? source.getHeight() : source.getWidth();
        int height = rightAngle ? source.getWidth() : source.getHeight();
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.translate(width / 2.0, height / 2.0);
        graphics.rotate(Math.toRadians(degrees));
        graphics.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return target;
    }

    private BufferedImage resizeIfNeeded(BufferedImage image, List<String> transformations) {
        int max = Math.max(image.getWidth(), image.getHeight());
        if (max <= MAX_DIMENSION) {
            return toRgb(image);
        }
        double scale = (double) MAX_DIMENSION / max;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        transformations.add("resize_max_dimension_" + MAX_DIMENSION);
        return resized;
    }

    private BufferedImage applyLightEnhancement(BufferedImage image, List<String> transformations) {
        BufferedImage rgb = toRgb(image);
        float brightness = averageBrightness(rgb);
        BufferedImage contrasted = rgb;
        if (brightness < 95f) {
            contrasted = new RescaleOp(1.08f, 8f, null).filter(rgb, null);
            transformations.add("contrast_light");
        } else {
            contrasted = new RescaleOp(1.03f, 0f, null).filter(rgb, null);
            transformations.add("contrast_micro");
        }
        float[] sharpenKernel = {
                0f, -0.08f, 0f,
                -0.08f, 1.32f, -0.08f,
                0f, -0.08f, 0f
        };
        transformations.add("sharpen_light");
        return new ConvolveOp(new Kernel(3, 3, sharpenKernel), ConvolveOp.EDGE_NO_OP, null).filter(contrasted, null);
    }

    private BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        ColorConvertOp op = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_sRGB), null);
        try {
            op.filter(image, rgb);
        } catch (Exception ignored) {
            Graphics2D graphics = rgb.createGraphics();
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
        }
        return rgb;
    }

    private float averageBrightness(BufferedImage image) {
        long sum = 0;
        int stepX = Math.max(1, image.getWidth() / 64);
        int stepY = Math.max(1, image.getHeight() / 64);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                sum += Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                count++;
            }
        }
        return count == 0 ? 255f : (float) sum / count;
    }

    private byte[] encode(BufferedImage image, String mimeType) throws Exception {
        if (MediaType.IMAGE_PNG_VALUE.equals(mimeType)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return new byte[0];
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private Integer compressionQuality(String mimeType) {
        return MediaType.IMAGE_JPEG_VALUE.equals(mimeType) ? Math.round(JPEG_QUALITY * 100) : null;
    }

    private int readExifOrientation(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < 4 || (jpegBytes[0] & 0xff) != 0xff || (jpegBytes[1] & 0xff) != 0xd8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 < jpegBytes.length) {
            if ((jpegBytes[offset] & 0xff) != 0xff) {
                return 1;
            }
            int marker = jpegBytes[offset + 1] & 0xff;
            int length = ((jpegBytes[offset + 2] & 0xff) << 8) + (jpegBytes[offset + 3] & 0xff);
            if (marker == 0xe1 && offset + 4 + length <= jpegBytes.length && hasExifHeader(jpegBytes, offset + 4)) {
                return parseTiffOrientation(jpegBytes, offset + 10, length - 8);
            }
            if (length < 2) {
                return 1;
            }
            offset += 2 + length;
        }
        return 1;
    }

    private boolean hasExifHeader(byte[] bytes, int offset) {
        byte[] exif = {'E', 'x', 'i', 'f', 0, 0};
        if (offset + exif.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < exif.length; i++) {
            if (bytes[offset + i] != exif[i]) {
                return false;
            }
        }
        return true;
    }

    private int parseTiffOrientation(byte[] bytes, int tiffOffset, int tiffLength) {
        if (tiffOffset + 8 > bytes.length || tiffLength < 8) {
            return 1;
        }
        boolean littleEndian = bytes[tiffOffset] == 'I' && bytes[tiffOffset + 1] == 'I';
        boolean bigEndian = bytes[tiffOffset] == 'M' && bytes[tiffOffset + 1] == 'M';
        if (!littleEndian && !bigEndian) {
            return 1;
        }
        int ifdOffset = readInt(bytes, tiffOffset + 4, littleEndian);
        int directory = tiffOffset + ifdOffset;
        if (directory < tiffOffset || directory + 2 > bytes.length) {
            return 1;
        }
        int entries = readShort(bytes, directory, littleEndian);
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > bytes.length) {
                return 1;
            }
            int tag = readShort(bytes, entry, littleEndian);
            if (tag == 0x0112) {
                return readShort(bytes, entry + 8, littleEndian);
            }
        }
        return 1;
    }

    private int readShort(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        }
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private int readInt(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xff)
                    | ((bytes[offset + 1] & 0xff) << 8)
                    | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
        }
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private record ImageDimensions(Integer width, Integer height) {
    }

    public record ImageProcessingResult(
            Integer originalWidth,
            Integer originalHeight,
            long originalSizeBytes,
            Integer processedWidth,
            Integer processedHeight,
            long processedSizeBytes,
            String mimeType,
            Integer compressionQuality,
            List<String> transformationsApplied,
            byte[] processedBytes,
            String processedMimeType,
            boolean processed
    ) {
    }
}
