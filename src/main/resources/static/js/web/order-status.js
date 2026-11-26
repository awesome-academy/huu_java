// Trạng thái đơn realtime cho trang lịch sử (/orders) và chi tiết đơn (/orders/{id}) của khách.
// Cấu hình đọc từ #orderRealtime (fragment web/fragments/order-status-realtime.html); cần stompjs + /js/realtime.js nạp
// trước, showToast() của /js/web/main.js.
//
// - SUBSCRIBE /user/queue/orders: server chỉ gửi trạng thái đơn của chính người đăng nhập (convertAndSendToUser theo
//   username của chủ đơn) → đổi chữ + class của mọi badge [data-order-status-badge="<id>"] và hiện toast.
// - Kết nối lại (lỡ sự kiện khi mất mạng/đổi instance): GET /orders/statuses?ids=... (≤ 20 mã đang hiện trên trang,
//   server lọc theo chủ đơn) rồi cập nhật badge, không toast.
// - Dữ liệu đưa vào DOM bằng textContent/classList; class badge lấy từ server nhưng vẫn chỉ nhận token [A-Za-z0-9_-].
(function () {
    "use strict";

    const MAX_RESYNC_IDS = 20;
    const CLASS_TOKEN = /^[A-Za-z0-9_-]+$/;

    const root = document.getElementById("orderRealtime");
    if (!window.Realtime || !root || !root.dataset.wsUrl || !root.dataset.destination) {
        return;
    }

    function classTokens(value) {
        return String(value || "").split(/\s+/).filter(function (token) {
            return CLASS_TOKEN.test(token);
        });
    }

    function isStatusEvent(event) {
        return event !== null && typeof event === "object" && Number.isInteger(event.orderId)
                && typeof event.status === "string" && typeof event.statusLabel === "string"
                && typeof event.badgeClass === "string";
    }

    /** @returns {boolean} true nếu trang có badge của đơn này và trạng thái thực sự đổi */
    function applyStatus(event) {
        let changed = false;
        // orderId đã kiểm là số nguyên → an toàn khi ghép vào selector
        document.querySelectorAll('[data-order-status-badge="' + event.orderId + '"]').forEach(function (badge) {
            if (badge.dataset.status === event.status) {
                return;
            }
            const oldClasses = classTokens(badge.dataset.badgeClass);
            const newClasses = classTokens(event.badgeClass);
            if (oldClasses.length > 0) {
                badge.classList.remove.apply(badge.classList, oldClasses);
            }
            if (newClasses.length > 0) {
                badge.classList.add.apply(badge.classList, newClasses);
            }
            badge.dataset.badgeClass = newClasses.join(" ");
            badge.dataset.status = event.status;
            badge.textContent = event.statusLabel;
            changed = true;
        });
        return changed;
    }

    function onStatusEvent(event) {
        if (!isStatusEvent(event)) {
            return;
        }
        applyStatus(event);
        if (typeof showToast === "function") {
            showToast("Đơn #" + event.orderId + ": " + event.statusLabel, false);
        }
    }

    function orderIdsOnPage() {
        const ids = [];
        document.querySelectorAll("[data-order-status-badge]").forEach(function (badge) {
            const id = Number(badge.dataset.orderStatusBadge);
            if (Number.isInteger(id) && id > 0 && ids.indexOf(id) < 0 && ids.length < MAX_RESYNC_IDS) {
                ids.push(id);
            }
        });
        return ids;
    }

    function resync() {
        const ids = orderIdsOnPage();
        if (ids.length === 0 || !root.dataset.statusesUrl) {
            return;
        }
        const url = new URL(root.dataset.statusesUrl, window.location.href);
        ids.forEach(function (id) {
            url.searchParams.append("ids", String(id));
        });
        fetch(url.href, {
            headers: {Accept: "application/json"},
            credentials: "same-origin",
            cache: "no-store",
            redirect: "error"
        }).then(function (response) {
            if (!response.ok) {
                throw new Error("HTTP " + response.status);
            }
            return response.json();
        }).then(function (statuses) {
            if (Array.isArray(statuses)) {
                statuses.filter(isStatusEvent).forEach(applyStatus);
            }
        }).catch(function (error) {
            // giữ trạng thái đang hiện; lần kết nối lại sau sẽ thử tiếp
            console.warn("Order statuses unavailable:", error.message);
        });
    }

    window.Realtime.connect({
        url: root.dataset.wsUrl,
        csrfTokenUrl: root.dataset.csrfTokenUrl,
        subscriptions: [{destination: root.dataset.destination, onMessage: onStatusEvent}],
        onResync: function (initial) {
            // Lần đầu: badge vừa được server render. Kết nối lại: có thể đã lỡ sự kiện → hỏi lại server
            if (!initial) {
                resync();
            }
        }
    });
})();
