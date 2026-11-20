package com.huuhv.foodsndrinks.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** EXIF bị bỏ khi encode lại nên hướng xoay (Orientation) phải được áp vào pixel. */
class ImageSanitizerTest {

    /** Khối APP1: "Exif\0\0" + TIFF header + IFD0 có 1 entry Orientation. */
    private static byte[] exifWithOrientation(int orientation, ByteOrder order) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        tiff.putShort((short) 42).putInt(8);                                      // offset IFD0
        tiff.putShort((short) 1);                                                 // số entry
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1);              // tag, SHORT, count
        tiff.putShort((short) orientation).putShort((short) 0);                   // value
        tiff.putInt(0);                                                           // không có IFD tiếp theo
        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] app1 = new byte[header.length + tiff.capacity()];
        System.arraycopy(header, 0, app1, 0, header.length);
        System.arraycopy(tiff.array(), 0, app1, header.length, tiff.capacity());
        return app1;
    }

    @Test
    void exifOrientation_isReadInBothByteOrders() {
        assertThat(ImageSanitizer.exifOrientation(exifWithOrientation(6, ByteOrder.LITTLE_ENDIAN))).isEqualTo(6);
        assertThat(ImageSanitizer.exifOrientation(exifWithOrientation(8, ByteOrder.BIG_ENDIAN))).isEqualTo(8);
    }

    @Test
    void malformedExif_isIgnored() {
        byte[] valid = exifWithOrientation(6, ByteOrder.LITTLE_ENDIAN);
        byte[] badOffset = valid.clone();
        badOffset[10] = (byte) 0xFF; // offset IFD0 trỏ ra ngoài khối

        assertThat(ImageSanitizer.exifOrientation(badOffset)).isZero();
        assertThat(ImageSanitizer.exifOrientation(new byte[]{'E', 'x', 'i', 'f'})).isZero();
        assertThat(ImageSanitizer.exifOrientation(exifWithOrientation(9, ByteOrder.LITTLE_ENDIAN))).isZero();
        assertThat(ImageSanitizer.exifOrientation("XMP data".getBytes(StandardCharsets.US_ASCII))).isZero();
    }

    /** Ảnh 3×2, điểm đỏ ở góc trên-trái; sau khi áp Orientation, điểm đỏ phải ở vị trí mong đợi. */
    @ParameterizedTest
    @CsvSource({
            "1, 3, 2, 0, 0",
            "2, 3, 2, 2, 0",
            "3, 3, 2, 2, 1",
            "4, 3, 2, 0, 1",
            "5, 2, 3, 0, 0",
            "6, 2, 3, 1, 0",
            "7, 2, 3, 1, 2",
            "8, 2, 3, 0, 2"})
    void applyOrientation_movesTopLeftPixelToExpectedCorner(int orientation, int width, int height, int redX, int redY) {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xFF0000);

        BufferedImage result = ImageSanitizer.applyOrientation(source, orientation);

        assertThat(result.getWidth()).isEqualTo(width);
        assertThat(result.getHeight()).isEqualTo(height);
        assertThat(result.getRGB(redX, redY) & 0xFFFFFF).isEqualTo(0xFF0000);
    }
}
