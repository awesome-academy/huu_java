package com.huuhv.foodsndrinks.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Upload ảnh: đuôi file phải thuộc whitelist VÀ nội dung (magic bytes) phải khớp đuôi; Content-Type không được tin. */
class FileStorageServiceTest {

    private static final byte[] PNG = HexFormat.of().parseHex("89504E470D0A1A0A0000000D49484452");
    private static final byte[] JPEG = HexFormat.of().parseHex("FFD8FFE000104A4649460001");
    private static final byte[] GIF = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] WEBP = "RIFF$\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir Path uploadDir;
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        service = new FileStorageService(uploadDir.toString());
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("newImages", name, contentType, content);
    }

    private static byte[] content(String format) {
        return switch (format) {
            case "PNG" -> PNG;
            case "JPEG" -> JPEG;
            case "GIF" -> GIF;
            case "WEBP" -> WEBP;
            default -> throw new IllegalArgumentException(format);
        };
    }

    @ParameterizedTest
    @CsvSource({"photo.png, PNG, .png", "photo.JPG, JPEG, .jpg", "photo.jpeg, JPEG, .jpg", "anim.gif, GIF, .gif",
            "photo.webp, WEBP, .webp"})
    void validImage_isStoredUnderUuidName_withNormalizedExtension(String name, String format, String storedExt)
            throws Exception {
        String url = service.store(file(name, "image/whatever", content(format)), "products");

        assertThat(url).matches("/uploads/products/[0-9a-f-]{36}\\" + storedExt);
        assertThat(Files.readAllBytes(uploadDir.resolve(url.substring("/uploads/".length())))).isEqualTo(content(format));
    }

    @Test
    void textFileRenamedToPng_isRejected_evenWithImageContentType() {
        MockMultipartFile fake = file("evil.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(fake, "products"))
                .withMessageContaining("PNG");
    }

    @Test
    void realPngWithJpgExtension_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo.jpg", "image/jpeg", PNG), "products"));
    }

    @Test
    void svgOrUnknownExtension_isRejected() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("logo.svg", "image/svg+xml", svg), "products"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo", "image/png", PNG), "products"));
    }

    @Test
    void fileShorterThanSignature_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> service.store(file("tiny.webp", "image/webp", "RIFF".getBytes(StandardCharsets.ISO_8859_1)), "products"));
    }

    @Test
    void pathTraversalInSubFolder_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo.png", "image/png", PNG), "../outside"));
    }
}
