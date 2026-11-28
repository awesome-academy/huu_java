package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipException;

/**
 * Đọc file import sản phẩm (.xlsx theo file mẫu) thành các {@link ProductImportRow} thô — chỉ đọc, không validate dữ liệu.
 * <p>
 * Đọc kiểu streaming (SAX qua {@link XSSFReader}): không dựng cả workbook trong bộ nhớ như {@code XSSFWorkbook} — file
 * upload là dữ liệu không tin cậy — và dừng ngay khi vượt số dòng cho phép. Chỉ đọc sheet đầu tiên. Ô công thức: dùng giá
 * trị đã lưu sẵn trong file, không bao giờ tính công thức. Ô số trả về dạng số thô (không theo định dạng hiển thị của ô).
 * XML được parse bằng {@link XMLHelper#newXMLReader()} của POI (đã tắt DTD/external entity — chống XXE).
 * <p>
 * Không giữ trạng thái giữa các lần gọi → dùng chung cho nhiều thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductImportReader {

    private static final String UNREADABLE = "Không đọc được file Excel. Hãy tải file mẫu, nhập dữ liệu rồi thử lại!";
    private static final int COLUMN_COUNT = ProductExcelColumns.HEADERS.size();

    private final ExcelProperties properties;

    /**
     * @param file file .xlsx trên đĩa (đã qua {@link ExcelUploadValidator}, kể cả bước kiểm tra nội dung zip)
     * @return các dòng có dữ liệu, theo thứ tự trong file (dòng trống bị bỏ qua)
     * @throws ExcelFileException   nội dung file không dùng được: không phải workbook, sai mẫu (dòng tiêu đề), không có
     *                              dòng dữ liệu, hoặc nhiều hơn {@code app.excel.import.max-rows} dòng — kết quả cuối
     * @throws UncheckedIOException lỗi I/O khi đọc file (ổ đĩa/volume mạng chập chờn, file biến mất...) — lỗi hệ thống,
     *                              người gọi để nó thoát ra để broker giao lại message; không bao giờ đổ lỗi cho file
     */
    public List<ProductImportRow> read(Path file) {
        RowCollector collector = new RowCollector(properties.imports().maxRows());
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            Iterator<InputStream> sheets = reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new ExcelFileException(UNREADABLE);
            }
            try (InputStream sheet = sheets.next()) {
                XMLReader parser = XMLHelper.newXMLReader();
                // false: không nối phần phiên âm (<rPh>, furigana do IME tiếng Nhật lưu kèm) vào giá trị ô —
                // "寿司" phải đọc ra "寿司", không phải "寿司 スシ"
                parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null,
                        new ReadOnlySharedStringsTable(pkg, false), collector, new RawNumberFormatter(), false));
                parser.parse(new InputSource(sheet));
            }
        } catch (OpenXML4JException | SAXException | ParserConfigurationException | IOException
                 | RuntimeException e) {
            throw classify(e);
        }
        return collector.rows();
    }

    /**
     * Phân loại lỗi của POI/SAX. Lỗi I/O của hệ thống file (một {@link IOException} ở bất kỳ tầng cause nào — POI hay
     * bọc lại, vd. {@code InvalidOperationException}) là lỗi hệ thống: file đã qua
     * {@link ExcelUploadValidator#validateContent} lúc upload — đọc trọn mọi entry bằng cùng lớp zip với POI, kể cả giới
     * hạn tỉ lệ nén — nên lỗi zip/zip bomb thật sự không tới được đây. Ngoại lệ là các {@code IOException} mô tả NỘI DUNG
     * hỏng ({@link #isContentError}); chúng và mọi lỗi còn lại (XML sai, thiếu part của workbook, lỗi runtime của POI) là
     * lỗi của file.
     */
    private static RuntimeException classify(Exception e) {
        IOException io = null;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // Lỗi do chính bộ đọc ném trong lúc SAX đang parse (sai mẫu, quá số dòng) — có thể đã bị parser bọc lại
            if (cause instanceof ExcelFileException rejected) {
                return rejected;
            }
            if (io == null && cause instanceof IOException found && !isContentError(found)) {
                io = found;
            }
        }
        if (io != null) {
            log.warn("I/O error while reading import file, will be retried: {}", io.getClass().getName());
            return new UncheckedIOException("Could not read import file", io);
        }
        // Chỉ ghi loại lỗi ở WARN: thông điệp của thư viện có thể chứa nội dung file
        log.warn("Rejected unreadable import file: {}", e.getClass().getName());
        log.debug("Unreadable import file", e);
        return new ExcelFileException(UNREADABLE, e);
    }

    /**
     * {@code IOException} do dữ liệu hỏng chứ không do hệ thống file: zip sai cấu trúc ({@link ZipException} — kể cả các
     * lớp con của commons-compress; POI cũng gặp nó khi file không phải zip) và byte không đúng bảng mã của XML
     * ({@link CharConversionException} — Xerces báo UTF-8 sai bằng lớp con của nó). Giao lại cũng hỏng y như vậy.
     */
    private static boolean isContentError(IOException e) {
        return e instanceof ZipException || e instanceof CharConversionException;
    }

    /** Ô số luôn trả về dạng số thô như Excel lưu ({@code 35000}, {@code 0.5}), bỏ qua định dạng hiển thị ("35,000 ₫"). */
    private static final class RawNumberFormatter extends DataFormatter {

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return NumberToTextConverter.toText(value);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                                            boolean use1904Windowing) {
            return NumberToTextConverter.toText(value);
        }
    }

    /** Nhận từng ô từ SAX, kiểm tra dòng tiêu đề và gom các dòng dữ liệu. Một instance cho một lần đọc. */
    private static final class RowCollector implements SheetContentsHandler {

        private final int maxRows;
        private final List<ProductImportRow> rows = new ArrayList<>();
        private final String[] cells = new String[COLUMN_COUNT];

        private int nextColumn;
        private boolean extraHeaderColumn;
        private boolean headerSeen;

        RowCollector(int maxRows) {
            this.maxRows = maxRows;
        }

        List<ProductImportRow> rows() {
            if (!headerSeen) {
                throw wrongTemplate();
            }
            if (rows.isEmpty()) {
                throw new ExcelFileException("File không có dòng dữ liệu nào!");
            }
            return rows;
        }

        @Override
        public void startRow(int rowNum) {
            Arrays.fill(cells, null);
            nextColumn = 0;
            extraHeaderColumn = false;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            // Thuộc tính r của ô là tuỳ chọn trong OOXML: thiếu thì ô nằm ngay sau ô trước đó
            int column = cellReference != null ? new CellReference(cellReference).getCol() : nextColumn;
            nextColumn = column + 1;
            String value = formattedValue == null || formattedValue.isBlank() ? null : formattedValue;
            if (column >= 0 && column < COLUMN_COUNT) {
                cells[column] = value;
            } else if (value != null) {
                extraHeaderColumn = true;
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) {
                requireTemplateHeader();
                return;
            }
            if (!headerSeen) {
                throw wrongTemplate();
            }
            if (isBlankRow()) {
                return;
            }
            if (rows.size() >= maxRows) {
                // Ném ra khỏi SAX parser: dừng đọc ngay, không đọc tiếp phần còn lại của file
                throw new ExcelFileException("File có nhiều hơn " + maxRows + " dòng dữ liệu. Hãy tách thành nhiều file!");
            }
            // Ô ngoài 6 cột của mẫu ở các dòng dữ liệu bị bỏ qua (ghi chú của người nhập)
            rows.add(new ProductImportRow(rowNum + 1, cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]));
        }

        private void requireTemplateHeader() {
            for (int i = 0; i < COLUMN_COUNT; i++) {
                if (cells[i] == null || !ProductExcelColumns.HEADERS.get(i).equalsIgnoreCase(cells[i].strip())) {
                    throw wrongTemplate();
                }
            }
            if (extraHeaderColumn) {
                throw wrongTemplate();
            }
            headerSeen = true;
        }

        private boolean isBlankRow() {
            for (String cell : cells) {
                if (cell != null) {
                    return false;
                }
            }
            return true;
        }

        private static ExcelFileException wrongTemplate() {
            return new ExcelFileException("File không đúng mẫu: dòng đầu tiên phải là các cột "
                    + String.join(", ", ProductExcelColumns.HEADERS) + ". Hãy tải file mẫu!");
        }
    }
}
