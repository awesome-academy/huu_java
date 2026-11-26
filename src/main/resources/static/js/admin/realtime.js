// Realtime cho mọi trang admin: nhận sự kiện đơn hàng qua STOMP (/topic/admin/orders), cập nhật badge "đơn chờ xác nhận"
// ở sidebar và hiện toast khi có đơn mới. Phát CustomEvent cho script của từng trang (dashboard.js):
//   - "admin:orders-changed"  (detail = payload) mỗi sự kiện;
//   - "admin:realtime-resync" (detail = {initial}) sau mỗi lần SUBSCRIBE xong — kết nối lại thì tải lại số liệu.
// Mọi dữ liệu đưa vào DOM bằng textContent/thuộc tính, không innerHTML.
(function () {
    "use strict";

    const body = document.body;
    if (!window.Realtime || !body.dataset.wsUrl || !body.dataset.adminOrdersDestination) {
        return;
    }
    const badge = document.getElementById("pendingOrderBadge");
    const toastContainer = document.getElementById("adminToastContainer");
    const money = new Intl.NumberFormat("vi-VN", {style: "currency", currency: "VND"});

    function updateBadge(count) {
        if (!badge || typeof count !== "number" || !Number.isFinite(count) || count < 0) {
            return;
        }
        badge.textContent = String(count);
        badge.hidden = count === 0;
    }

    function showNewOrderToast(event) {
        if (!toastContainer || typeof bootstrap === "undefined" || !Number.isInteger(event.orderId)) {
            return;
        }
        const toast = document.createElement("div");
        toast.className = "toast text-bg-light border-success";
        toast.setAttribute("role", "status");

        const header = document.createElement("div");
        header.className = "toast-header";
        const title = document.createElement("strong");
        title.className = "me-auto text-success";
        title.textContent = "Đơn mới #" + event.orderId;
        const close = document.createElement("button");
        close.type = "button";
        close.className = "btn-close";
        close.setAttribute("data-bs-dismiss", "toast");
        close.setAttribute("aria-label", "Đóng");
        header.append(title, close);

        const content = document.createElement("div");
        content.className = "toast-body";
        const parts = [];
        if (Number.isInteger(event.itemCount)) {
            parts.push(event.itemCount + " món");
        }
        if (typeof event.totalPrice === "number") {
            parts.push(money.format(event.totalPrice));
        }
        const summary = document.createElement("div");
        summary.textContent = parts.join(" · ");
        const link = document.createElement("a");
        link.href = body.dataset.ordersUrl + "/" + event.orderId;
        link.textContent = "Xem đơn";
        content.append(summary, link);

        toast.append(header, content);
        toastContainer.append(toast);
        toast.addEventListener("hidden.bs.toast", function () {
            toast.remove();
        });
        bootstrap.Toast.getOrCreateInstance(toast, {delay: 8000}).show();
    }

    function onOrderEvent(event) {
        if (!event || typeof event.type !== "string") {
            return;
        }
        updateBadge(event.pendingCount);
        if (event.type === "ORDER_PLACED") {
            showNewOrderToast(event);
        }
        document.dispatchEvent(new CustomEvent("admin:orders-changed", {detail: event}));
    }

    function refreshPendingCount() {
        if (!body.dataset.pendingCountUrl) {
            return;
        }
        fetch(body.dataset.pendingCountUrl, {
            credentials: "same-origin",
            headers: {"Accept": "application/json"}
        }).then(function (response) {
            return response.ok ? response.json() : null;
        }).then(function (data) {
            if (data) {
                updateBadge(data.pendingCount);
            }
        }).catch(function () {
            // giữ giá trị cũ; lần kết nối lại sau sẽ thử tiếp
        });
    }

    window.Realtime.connect({
        url: body.dataset.wsUrl,
        csrfTokenUrl: body.dataset.csrfTokenUrl,
        subscriptions: [{destination: body.dataset.adminOrdersDestination, onMessage: onOrderEvent}],
        onResync: function (initial) {
            // Lần đầu: badge vừa được server render. Từ lần kết nối lại: có thể đã lỡ sự kiện → tải lại
            if (!initial) {
                refreshPendingCount();
            }
            document.dispatchEvent(new CustomEvent("admin:realtime-resync", {detail: {initial: initial}}));
        }
    });
})();
