package com.huuhv.foodsndrinks.enums;

/**
 * Trạng thái một lần import Excel ({@code import_jobs.status}). Không đổi tên giá trị đã lưu (SQL của
 * {@code ImportJobRepository} và JSON của trang trạng thái dùng chuỗi này).
 */
public enum ImportJobStatus {
    /** Đã nhận file, message đang chờ trong queue {@code excel.import}. */
    PENDING("Đang chờ xử lý", "bg-secondary", false),
    /** Consumer đang đọc + validate + ghi. */
    PROCESSING("Đang xử lý", "bg-info text-dark", false),
    /** Mọi dòng đã được ghi. */
    COMPLETED("Hoàn thành", "bg-success", true),
    /** Không dòng nào được ghi: file có dòng lỗi, file bị từ chối, hoặc lỗi hệ thống. */
    FAILED("Thất bại", "bg-danger", true);

    private final String label;
    private final String badgeClass;
    private final boolean terminal;

    ImportJobStatus(String label, String badgeClass, boolean terminal) {
        this.label = label;
        this.badgeClass = badgeClass;
        this.terminal = terminal;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }

    /** Đã kết thúc: không còn thay đổi, trang trạng thái ngừng tự cập nhật. */
    public boolean isTerminal() {
        return terminal;
    }
}
