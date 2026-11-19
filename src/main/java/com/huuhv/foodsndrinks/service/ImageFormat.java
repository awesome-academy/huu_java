package com.huuhv.foodsndrinks.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Định dạng ảnh được phép upload, nhận diện bằng magic bytes ở đầu file — không tin {@code Content-Type} hay đuôi file
 * do client gửi lên. SVG cố ý không được hỗ trợ (là XML, chứa được script).
 */
enum ImageFormat {

    PNG(".png", Set.of(".png")),
    JPEG(".jpg", Set.of(".jpg", ".jpeg")),
    GIF(".gif", Set.of(".gif")),
    WEBP(".webp", Set.of(".webp"));

    /** Số byte đầu file cần đọc để nhận diện mọi định dạng (WEBP: "RIFF" + 4 byte kích thước + "WEBP"). */
    static final int HEADER_LENGTH = 12;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87A = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89A = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_TAG = {'W', 'E', 'B', 'P'};

    /** Đuôi file dùng khi lưu (chuẩn hoá: .jpeg → .jpg). */
    private final String storedExtension;
    private final Set<String> extensions;

    ImageFormat(String storedExtension, Set<String> extensions) {
        this.storedExtension = storedExtension;
        this.extensions = extensions;
    }

    String storedExtension() {
        return storedExtension;
    }

    /** @param extension đuôi file kèm dấu chấm, vd. ".PNG" */
    static Optional<ImageFormat> fromExtension(String extension) {
        String normalized = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.extensions.contains(normalized)).findFirst();
    }

    /** @param header tối đa {@link #HEADER_LENGTH} byte đầu file (ít hơn nếu file ngắn) */
    boolean matches(byte[] header) {
        return switch (this) {
            case PNG -> startsWith(header, 0, PNG_SIGNATURE);
            case JPEG -> startsWith(header, 0, JPEG_SIGNATURE);
            case GIF -> startsWith(header, 0, GIF87A) || startsWith(header, 0, GIF89A);
            case WEBP -> startsWith(header, 0, RIFF) && startsWith(header, 8, WEBP_TAG);
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] signature) {
        return data.length >= offset + signature.length
                && Arrays.equals(data, offset, offset + signature.length, signature, 0, signature.length);
    }
}
