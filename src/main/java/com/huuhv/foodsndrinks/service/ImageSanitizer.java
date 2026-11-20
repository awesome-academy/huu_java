package com.huuhv.foodsndrinks.service;

import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Decode ảnh upload rồi encode lại thành file mới, thay vì lưu nguyên byte người dùng gửi lên:
 * <ul>
 *   <li>bỏ mọi dữ liệu ngoài pixel — metadata EXIF/GPS (lộ vị trí người chụp qua {@code /uploads/**} public), comment,
 *       payload nối thêm phía sau ảnh (file polyglot ảnh + HTML/JS vẫn qua được kiểm tra magic bytes);</li>
 *   <li>chặn decompression bomb: đọc kích thước từ header TRƯỚC khi decode, từ chối ảnh quá {@link #MAX_PIXELS} pixel;</li>
 *   <li>ảnh lớn được decode thẳng ở độ phân giải thấp (source subsampling) để cạnh dài ≤ {@link #MAX_OUTPUT_SIDE} — tốn
 *       ít bộ nhớ khi decode và file lưu nhỏ hơn.</li>
 * </ul>
 * EXIF bị bỏ nên hướng xoay (tag Orientation, ảnh chụp dọc từ điện thoại) được áp vào pixel trước khi encode.
 */
final class ImageSanitizer {

    /** Ảnh 8000×5000; lớn hơn coi là bất thường (decompression bomb), không decode. */
    static final long MAX_PIXELS = 40_000_000L;
    /** Cạnh dài tối đa của ảnh lưu — đủ cho ảnh sản phẩm/avatar. */
    static final int MAX_OUTPUT_SIDE = 2048;
    private static final float JPEG_QUALITY = 0.9f;

    private static final String JPEG_NATIVE_METADATA = "javax_imageio_jpeg_image_1.0";
    private static final String APP1_MARKER_TAG = "225";
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int MAX_IFD_ENTRIES = 512;

    private ImageSanitizer() {
    }

    /**
     * @return ảnh đã encode lại theo {@code format}
     * @throws IllegalArgumentException nếu không decode được (hỏng, giả mạo, định dạng màu không hỗ trợ) hoặc quá lớn
     * @throws IOException              nếu không đọc được luồng upload
     */
    static byte[] sanitize(InputStream input, ImageFormat format) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.ioFormatName());
            if (iis == null || !readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                // Chỉ JPEG cần metadata (EXIF Orientation)
                reader.setInput(iis, true, format != ImageFormat.JPEG);
                BufferedImage image = decode(reader, format);
                return encode(image, format);
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            // IIOException: file hỏng/giả mạo; decoder đôi khi ném RuntimeException với file cố ý làm hỏng
            throw invalidImage();
        }
    }

    private static BufferedImage decode(ImageReader reader, ImageFormat format) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Ảnh có kích thước quá lớn!");
        }
        int orientation = format == ImageFormat.JPEG ? exifOrientation(reader) : 1;

        ImageReadParam param = reader.getDefaultReadParam();
        int step = (Math.max(width, height) + MAX_OUTPUT_SIDE - 1) / MAX_OUTPUT_SIDE;
        if (step > 1) {
            param.setSourceSubsampling(step, step, 0, 0);
        }
        return applyOrientation(reader.read(0, param), orientation);
    }

    private static byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (format == ImageFormat.PNG) {
            if (!ImageIO.write(image, "png", out)) {
                throw invalidImage();
            }
            return out.toByteArray();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** JPEG không có kênh alpha và writer của JDK chỉ nhận ảnh RGB/xám: vẽ lại trên nền trắng. */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_3BYTE_BGR
                || image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(image, 0, 0, Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    // -------------------------------------------------------
    // EXIF Orientation
    // -------------------------------------------------------

    /** @return giá trị tag Orientation (1–8) của EXIF trong APP1; 1 nếu không có hoặc không đọc được */
    private static int exifOrientation(ImageReader reader) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null || !Arrays.asList(metadata.getMetadataFormatNames()).contains(JPEG_NATIVE_METADATA)) {
                return 1;
            }
            Node markers = findChild(metadata.getAsTree(JPEG_NATIVE_METADATA), "markerSequence");
            for (Node node = markers == null ? null : markers.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof IIOMetadataNode marker && "unknown".equals(marker.getNodeName())
                        && APP1_MARKER_TAG.equals(marker.getAttribute("MarkerTag"))
                        && marker.getUserObject() instanceof byte[] data) {
                    int orientation = exifOrientation(data);
                    if (orientation != 0) {
                        return orientation;
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Metadata hỏng không làm hỏng ảnh: giữ nguyên hướng
        }
        return 1;
    }

    /**
     * Đọc tag Orientation trong IFD0 của khối APP1 ({@code "Exif\0\0"} + TIFF). Mọi offset đều được kiểm tra biên.
     *
     * @return 1–8; 0 nếu khối không phải EXIF hoặc không có tag hợp lệ
     */
    static int exifOrientation(byte[] app1) {
        if (app1.length < EXIF_HEADER.length + 8
                || !Arrays.equals(app1, 0, EXIF_HEADER.length, EXIF_HEADER, 0, EXIF_HEADER.length)) {
            return 0;
        }
        int tiff = EXIF_HEADER.length;
        boolean littleEndian;
        if (app1[tiff] == 'I' && app1[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (app1[tiff] == 'M' && app1[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return 0;
        }
        long ifd0 = tiff + readUnsignedInt(app1, tiff + 4, littleEndian);
        if (ifd0 < tiff || ifd0 + 2 > app1.length) {
            return 0;
        }
        int entries = Math.min(readUnsignedShort(app1, (int) ifd0, littleEndian), MAX_IFD_ENTRIES);
        for (int i = 0; i < entries; i++) {
            int entry = (int) ifd0 + 2 + i * 12;
            if (entry + 12 > app1.length) {
                break;
            }
            if (readUnsignedShort(app1, entry, littleEndian) == ORIENTATION_TAG) {
                int value = readUnsignedShort(app1, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
    }

    /** Xoay/lật pixel theo Orientation để ảnh hiển thị đúng khi không còn EXIF. */
    static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, type);
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                int rgb = switch (orientation) {
                    case 2 -> source.getRGB(w - 1 - x, y);          // lật ngang
                    case 3 -> source.getRGB(w - 1 - x, h - 1 - y);  // xoay 180°
                    case 4 -> source.getRGB(x, h - 1 - y);          // lật dọc
                    case 5 -> source.getRGB(y, x);                  // chuyển vị
                    case 6 -> source.getRGB(y, h - 1 - x);          // xoay 90° theo chiều kim đồng hồ
                    case 7 -> source.getRGB(w - 1 - y, h - 1 - x);  // chuyển vị ngược
                    default -> source.getRGB(w - 1 - y, x);         // 8: xoay 90° ngược chiều kim đồng hồ
                };
                target.setRGB(x, y, rgb);
            }
        }
        return target;
    }

    private static Node findChild(Node parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }

    private static int readUnsignedShort(byte[] data, int offset, boolean littleEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static long readUnsignedInt(byte[] data, int offset, boolean littleEndian) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int b = data[offset + (littleEndian ? 3 - i : i)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }

    private static IllegalArgumentException invalidImage() {
        return new IllegalArgumentException("Không đọc được nội dung ảnh, vui lòng chọn ảnh PNG/JPEG khác!");
    }
}
