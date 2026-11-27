package com.huuhv.foodsndrinks.service.excel;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ghi một sheet .xlsx theo kiểu streaming ({@link SXSSFWorkbook}: chỉ giữ {@value #ROW_WINDOW} dòng trong bộ nhớ, phần
 * còn lại nằm ở file tạm) — dùng chung cho mọi file export.
 * <p>
 * <b>Chống formula injection:</b> ô chữ có giá trị bắt đầu bằng {@code = + - @}, tab hoặc ký tự xuống dòng được gắn style
 * {@code quotePrefixed} (tương đương gõ dấu {@code '} trước giá trị trong Excel): Excel luôn coi ô là chữ, kể cả khi người
 * dùng sửa ô rồi nhấn Enter. Giá trị của ô được giữ nguyên (không chèn {@code '} vào dữ liệu) nên file export import lại
 * được. Lớp này không bao giờ ghi công thức.
 * <p>
 * Luôn {@link #close()} (try-with-resources hoặc qua {@link ExcelFile}) để xoá file tạm, kể cả khi ghi lỗi giữa chừng.
 * Không thread-safe.
 */
@Slf4j
public final class ExcelSheetWriter implements AutoCloseable {

    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Độ dài tối đa của một ô chữ trong .xlsx (32.767 ký tự) — POI ném lỗi nếu vượt. */
    static final int MAX_CELL_LENGTH = SpreadsheetVersion.EXCEL2007.getMaxTextLength();

    private static final int ROW_WINDOW = 100;
    private static final String FORMULA_TRIGGERS = "=+-@\t\r\n";
    /** Đơn vị độ rộng cột của POI: 1/256 ký tự. */
    private static final int COLUMN_WIDTH_UNIT = 256;

    /** @param widthChars độ rộng cột tính bằng số ký tự (không dùng autoSizeColumn — cần font trên máy chủ) */
    public record Column(String header, int widthChars) {
    }

    private final SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
    private final CellStyle headerStyle;
    private final CellStyle guardedTextStyle;
    private final CellStyle dateTimeStyle;
    private final CellStyle integerStyle;
    private final CellStyle moneyStyle;
    private final CellStyle decimalStyle;

    private Sheet sheet;
    private Row currentRow;
    private int nextRowIndex;
    private int nextColumnIndex;

    public ExcelSheetWriter(String sheetName, List<Column> columns) {
        try {
            headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            guardedTextStyle = workbook.createCellStyle();
            guardedTextStyle.setQuotePrefixed(true);
            dateTimeStyle = numberStyle("dd/mm/yyyy hh:mm");
            integerStyle = numberStyle("0");
            moneyStyle = numberStyle("#,##0");
            decimalStyle = numberStyle("#,##0.##");
            newSheet(sheetName, columns);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * Thêm một sheet nữa (sau các sheet đã có) với dòng tiêu đề {@code columns}; các lệnh {@link #row()} tiếp theo ghi vào
     * sheet này. Sheet đầu tiên vẫn là sheet mở ra khi người dùng mở file.
     */
    public ExcelSheetWriter newSheet(String sheetName, List<Column> columns) {
        sheet = workbook.createSheet(sheetName);
        nextRowIndex = 0;
        writeHeader(columns);
        return this;
    }

    /**
     * Gắn danh sách chọn (data validation) cho một cột của sheet hiện tại, từ dòng dữ liệu đầu tiên tới dòng
     * {@code lastDataRow} — chỉ là gợi ý cho người nhập, server vẫn validate lại mọi giá trị.
     *
     * @param columnIndex cột tính từ 0
     * @param lastDataRow số dòng dữ liệu được gắn danh sách (không tính dòng tiêu đề)
     */
    public ExcelSheetWriter dropdown(int columnIndex, int lastDataRow, List<String> options) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        sheet.addValidationData(helper.createValidation(
                helper.createExplicitListConstraint(options.toArray(String[]::new)),
                new CellRangeAddressList(1, lastDataRow, columnIndex, columnIndex)));
        return this;
    }

    /** Bắt đầu một dòng dữ liệu mới; các lệnh ghi ô tiếp theo điền lần lượt từ cột đầu tiên. */
    public ExcelSheetWriter row() {
        currentRow = sheet.createRow(nextRowIndex++);
        nextColumnIndex = 0;
        return this;
    }

    /** Ô chữ (đã chống formula injection). {@code null}/rỗng → ô trống. */
    public ExcelSheetWriter text(String value) {
        Cell cell = nextCell();
        if (value == null || value.isEmpty()) {
            return this;
        }
        cell.setCellValue(value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH) : value);
        if (FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            cell.setCellStyle(guardedTextStyle);
        }
        return this;
    }

    /** Ô số nguyên. */
    public ExcelSheetWriter integer(long value) {
        Cell cell = nextCell();
        cell.setCellValue(value);
        cell.setCellStyle(integerStyle);
        return this;
    }

    /** Ô số tiền (ô số, hiển thị có dấu phân cách hàng nghìn). {@code null} → ô trống. */
    public ExcelSheetWriter money(BigDecimal value) {
        Cell cell = nextCell();
        if (value != null) {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(moneyStyle);
        }
        return this;
    }

    /**
     * Ô số thập phân (ô số; hiển thị có dấu phân cách hàng nghìn và tối đa 2 chữ số thập phân, giá trị lưu đầy đủ — bộ đọc
     * import nhận lại đúng số này). {@code null} → ô trống.
     */
    public ExcelSheetWriter decimal(BigDecimal value) {
        Cell cell = nextCell();
        if (value != null) {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(decimalStyle);
        }
        return this;
    }

    /** Ô ngày giờ (ô ngày của Excel, không kèm múi giờ — giờ theo múi giờ của ứng dụng). {@code null} → ô trống. */
    public ExcelSheetWriter dateTime(LocalDateTime value) {
        Cell cell = nextCell();
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(dateTimeStyle);
        }
        return this;
    }

    /** Số dòng dữ liệu đã ghi vào sheet hiện tại (không tính dòng tiêu đề). */
    public int dataRowCount() {
        return nextRowIndex - 1;
    }

    /** Ghi toàn bộ file .xlsx ra {@code out} (không đóng {@code out}). */
    public void writeTo(OutputStream out) throws IOException {
        workbook.write(out);
    }

    /** Xoá file tạm của SXSSF rồi đóng workbook. Gọi nhiều lần không sao; lỗi chỉ ghi log. */
    @Override
    public void close() {
        try {
            workbook.dispose();
        } catch (RuntimeException e) {
            log.warn("Could not delete temporary files of an Excel export", e);
        }
        try {
            workbook.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not close an Excel export workbook", e);
        }
    }

    private void writeHeader(List<Column> columns) {
        row();
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            Cell cell = nextCell();
            cell.setCellValue(column.header());
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, column.widthChars() * COLUMN_WIDTH_UNIT);
        }
        sheet.createFreezePane(0, 1);
    }

    private CellStyle numberStyle(String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private Cell nextCell() {
        return currentRow.createCell(nextColumnIndex++);
    }
}
