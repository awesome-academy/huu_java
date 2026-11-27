-- Lần import sản phẩm từ Excel chạy nền qua queue JMS excel.import (service/excel/ImportJobService, ImportJobProcessor).
-- Upload tạo một dòng PENDING (cùng transaction với bản ghi outbox của message), consumer chuyển PROCESSING rồi
-- COMPLETED (kèm số sản phẩm thêm/cập nhật/không đổi) hoặc FAILED (kèm bảng lỗi theo dòng, hoặc lý do từ chối cả file).
-- Trạng thái chỉ đổi bằng UPDATE có điều kiện theo trạng thái hiện tại (ImportJobRepository) — message giao lại không
-- import lần hai. Mọi mốc thời gian là đồng hồ của DB.
-- File .xlsx chờ xử lý nằm trên đĩa (app.excel.import.dir, ngoài thư mục public), stored_file chỉ là TÊN file do server
-- sinh (UUID.xlsx) — không phải đường dẫn, không phải tên client gửi; file bị xoá khi lần import kết thúc.
CREATE TABLE import_jobs (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    file_name       VARCHAR(255) NOT NULL,                              -- tên file admin tải lên, chỉ để hiển thị
    stored_file     VARCHAR(64)  NOT NULL,                              -- tên file trong app.excel.import.dir
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',            -- ImportJobStatus
    total_rows      INT          NOT NULL DEFAULT 0,                    -- số dòng dữ liệu của file
    created_count   INT          NOT NULL DEFAULT 0,
    updated_count   INT          NOT NULL DEFAULT 0,
    unchanged_count INT          NOT NULL DEFAULT 0,
    error_count     INT          NOT NULL DEFAULT 0,                    -- tổng số lỗi dòng (errors có thể giữ ít hơn)
    errors          MEDIUMTEXT   NULL,                                  -- JSON: [{"row":3,"column":"Giá","message":"..."}]
    failure_message VARCHAR(255) NULL,                                  -- FAILED vì lý do của cả file (hiển thị cho admin)
    created_by      VARCHAR(50)  NOT NULL,                              -- username của admin tải file lên
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at      TIMESTAMP(3) NULL,                                  -- lần gần nhất consumer nhận việc
    finished_at     TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_import_jobs_errors_json CHECK (errors IS NULL OR JSON_VALID(errors)),
    -- đếm lần import chưa xong của một admin (giới hạn số file chờ xử lý)
    INDEX idx_import_jobs_created_by_status (created_by, status),
    -- job dọn dẹp tìm lần import kẹt
    INDEX idx_import_jobs_status_created (status, created_at)
);
