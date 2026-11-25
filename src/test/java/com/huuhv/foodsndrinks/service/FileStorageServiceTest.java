package com.huuhv.foodsndrinks.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Upload ảnh: đuôi file phải thuộc whitelist (PNG/JPEG) VÀ nội dung (magic bytes) phải khớp đuôi; Content-Type không được
 * tin; ảnh được decode/encode lại nên file lưu không chứa byte gốc (EXIF, payload nối thêm).
 */
class FileStorageServiceTest {

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

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    private BufferedImage readStored(String url) throws IOException {
        return ImageIO.read(uploadDir.resolve(url.substring("/uploads/".length())).toFile());
    }

    @ParameterizedTest
    @CsvSource({"photo.png, png, .png", "photo.JPG, jpeg, .jpg", "photo.jpeg, jpeg, .jpg"})
    void validImage_isReencodedUnderUuidName_withNormalizedExtension(String name, String format, String storedExt)
            throws Exception {
        String url = service.store(file(name, "image/whatever", image(format, 40, 30)), "products");

        assertThat(url).matches("/uploads/products/[0-9a-f-]{36}\\" + storedExt);
        BufferedImage stored = readStored(url);
        assertThat(stored.getWidth()).isEqualTo(40);
        assertThat(stored.getHeight()).isEqualTo(30);
    }

    @Test
    void polyglotPayloadAppendedToImage_isNotStored() throws Exception {
        byte[] payload = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] png = image("png", 10, 10);
        byte[] polyglot = new byte[png.length + payload.length];
        System.arraycopy(png, 0, polyglot, 0, png.length);
        System.arraycopy(payload, 0, polyglot, png.length, payload.length);

        String url = service.store(file("evil.png", "image/png", polyglot), "products");

        String stored = new String(Files.readAllBytes(uploadDir.resolve(url.substring("/uploads/".length()))),
                StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain("<script>");
    }

    @Test
    void largeImage_isDownscaledToMaxSide() throws Exception {
        String url = service.store(file("big.png", "image/png", image("png", 5000, 1000)), "products");

        BufferedImage stored = readStored(url);
        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isLessThanOrEqualTo(ImageSanitizer.MAX_OUTPUT_SIDE);
    }

    @Test
    void decompressionBomb_isRejectedFromHeaderWithoutDecoding() {
        // Header PNG (CRC hợp lệ) khai báo 20.000 × 20.000 pixel (400M) nhưng không có dữ liệu ảnh
        byte[] ihdr = HexFormat.of().parseHex("49484452" + "00004E20" + "00004E20" + "0802000000");
        CRC32 crc = new CRC32();
        crc.update(ihdr);
        byte[] bomb = HexFormat.of().parseHex("89504E470D0A1A0A" + "0000000D"
                + HexFormat.of().formatHex(ihdr) + String.format("%08x", crc.getValue()));

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("bomb.png", "image/png", bomb), "products"))
                .withMessageContaining("quá lớn");
    }

    @Test
    void truncatedImage_isRejected() throws Exception {
        byte[] png = image("png", 10, 10);
        byte[] truncated = Arrays.copyOf(png, 20);

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("broken.png", "image/png", truncated), "products"));
        try (var files = Files.walk(uploadDir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void textFileRenamedToPng_isRejected_evenWithImageContentType() {
        MockMultipartFile fake = file("evil.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(fake, "products"))
                .withMessageContaining("PNG");
    }

    @Test
    void realPngWithJpgExtension_isRejected() throws Exception {
        byte[] png = image("png", 10, 10);
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo.jpg", "image/jpeg", png), "products"));
    }

    @Test
    void gifWebpSvgOrUnknownExtension_isRejected() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("anim.gif", "image/gif", GIF), "products"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo.webp", "image/webp", WEBP), "products"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("logo.svg", "image/svg+xml", svg), "products"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo", "image/png", image("png", 2, 2)), "products"));
    }

    @Test
    void pathTraversalInSubFolder_isRejected() throws Exception {
        byte[] png = image("png", 2, 2);
        assertThatIllegalArgumentException().isThrownBy(() -> service.store(file("photo.png", "image/png", png), "../outside"));
    }

    @Test
    void sanitize_readsOnlyFromTheGivenStream() throws Exception {
        byte[] out = ImageSanitizer.sanitize(new ByteArrayInputStream(image("jpeg", 8, 8)), ImageFormat.JPEG);

        assertThat(ImageIO.read(new ByteArrayInputStream(out)).getWidth()).isEqualTo(8);
    }
}
