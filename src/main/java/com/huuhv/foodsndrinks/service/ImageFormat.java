package com.huuhv.foodsndrinks.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Định dạng ảnh được phép upload, nhận diện bằng magic bytes ở đầu file — không tin {@code Content-Type} hay đuôi file
 * do client gửi lên. Chỉ PNG/JPEG: ảnh được decode rồi encode lại bằng ImageIO ({@code FileStorageService}), mà JDK không
 * có codec WebP còn GIF encode lại sẽ mất animation. SVG cố ý không được hỗ trợ (là XML, chứa được script).
 */
enum ImageFormat {

    PNG(".png", "png", Set.of(".png")),
    JPEG(".jpg", "jpeg", Set.of(".jpg", ".jpeg"));

    /** Số byte đầu file cần đọc để nhận diện mọi định dạng. */
    static final int HEADER_LENGTH = 8;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** Đuôi file dùng khi lưu (chuẩn hoá: .jpeg → .jpg). */
    private final String storedExtension;
    /** Tên định dạng của ImageIO ({@code ImageIO.getImageReadersByFormatName}). */
    private final String ioFormatName;
    private final Set<String> extensions;

    ImageFormat(String storedExtension, String ioFormatName, Set<String> extensions) {
        this.storedExtension = storedExtension;
        this.ioFormatName = ioFormatName;
        this.extensions = extensions;
    }

    String storedExtension() {
        return storedExtension;
    }

    String ioFormatName() {
        return ioFormatName;
    }

    /** @param extension đuôi file kèm dấu chấm, vd. ".PNG" */
    static Optional<ImageFormat> fromExtension(String extension) {
        String normalized = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.extensions.contains(normalized)).findFirst();
    }

    /** @param header tối đa {@link #HEADER_LENGTH} byte đầu file (ít hơn nếu file ngắn) */
    boolean matches(byte[] header) {
        return switch (this) {
            case PNG -> startsWith(header, PNG_SIGNATURE);
            case JPEG -> startsWith(header, JPEG_SIGNATURE);
        };
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        return data.length >= signature.length
                && Arrays.equals(data, 0, signature.length, signature, 0, signature.length);
    }
}
