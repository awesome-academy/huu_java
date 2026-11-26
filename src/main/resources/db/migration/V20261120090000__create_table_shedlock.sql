-- Khoá phân tán của ShedLock (config/SchedulerLockConfig): mỗi job @Scheduled có @SchedulerLock là một dòng, instance
-- nào cập nhật được lock_until thì chạy job, các instance khác bỏ qua lần kích hoạt đó.
-- Schema theo tài liệu ShedLock cho MySQL; thời gian lấy từ đồng hồ DB (usingDbTime), không phụ thuộc giờ từng instance.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
