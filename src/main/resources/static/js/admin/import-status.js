// Trang trạng thái import Excel (/admin/products/import/{id}): gọi lại endpoint JSON cho tới khi lần import kết thúc
// rồi tải lại trang để server render kết quả cuối (số sản phẩm đã thêm/cập nhật hoặc bảng lỗi) — JS không tự dựng HTML
// từ dữ liệu, chỉ đổi nhãn trạng thái bằng textContent. Không script inline (CSP): cấu hình lấy từ data-*.
(function () {
    "use strict";

    const POLL_INTERVAL_MS = 2000;
    // Không polling mãi: mỗi request gia hạn session, một tab bỏ quên sẽ giữ phiên đăng nhập sống vô thời hạn
    const MAX_POLL_MS = 10 * 60 * 1000;
    const MAX_CONSECUTIVE_FAILURES = 5;
    const CLASS_TOKEN = /^[A-Za-z0-9_-]+$/;

    const root = document.querySelector("[data-import-status-url]");
    if (!root || root.dataset.importTerminal === "true") {
        return;
    }
    const badge = document.querySelector("[data-import-status-badge]");
    const notice = document.querySelector("[data-import-status-notice]");
    const progress = document.querySelector("[data-import-status-progress]");
    let waited = 0;
    let failures = 0;

    function stop(message) {
        if (progress) {
            progress.hidden = true;
        }
        if (notice) {
            notice.textContent = message;
            notice.hidden = false;
        }
    }

    function schedule() {
        if (waited >= MAX_POLL_MS) {
            stop("Trang đã ngừng tự cập nhật. Tải lại trang để xem trạng thái mới nhất.");
            return;
        }
        waited += POLL_INTERVAL_MS;
        setTimeout(poll, POLL_INTERVAL_MS);
    }

    function showStatus(job) {
        if (!badge) {
            return;
        }
        if (typeof job.statusLabel === "string") {
            badge.textContent = job.statusLabel;
        }
        if (typeof job.badgeClass === "string") {
            const tokens = job.badgeClass.split(/\s+/).filter(function (token) {
                return CLASS_TOKEN.test(token);
            });
            badge.className = ["badge"].concat(tokens).join(" ");
        }
    }

    function poll() {
        // redirect "error": hết phiên thì server chuyển hướng về /login — coi là lỗi, không parse trang login
        fetch(root.dataset.importStatusUrl, {headers: {Accept: "application/json"}, redirect: "error", cache: "no-store"})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            })
            .then(function (job) {
                if (!job || typeof job.status !== "string") {
                    throw new Error("Unexpected payload");
                }
                failures = 0;
                if (job.terminal === true) {
                    window.location.reload();
                    return;
                }
                showStatus(job);
                schedule();
            })
            .catch(function () {
                failures++;
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    stop("Không lấy được trạng thái (mất kết nối hoặc hết phiên đăng nhập). Tải lại trang để thử lại.");
                } else {
                    schedule();
                }
            });
    }

    schedule();
})();
