// Biểu đồ dashboard admin: doanh thu 6 tháng, trạng thái đơn, top 5 sản phẩm bán chạy. Dữ liệu lấy từ
// GET /admin/dashboard/charts (URL trong data-charts-url) thay vì inline script — CSP không có 'unsafe-inline'.
//
// Realtime: static/js/admin/realtime.js phát "admin:orders-changed" (mỗi sự kiện đơn hàng qua STOMP) và
// "admin:realtime-resync" (sau khi kết nối lại) → tải lại dữ liệu từ server, không cộng dồn từ payload (mọi dashboard ở
// mọi instance hội tụ cùng số liệu DB).
// - Gộp: sự kiện đầu tiên hẹn 1 lần tải sau 1s + jitter 0–2s; sự kiện tới trong lúc chờ không hẹn thêm; đang tải thì
//   ghi nhận để tải thêm đúng 1 lần sau. N dashboard (nhiều admin, nhiều instance) không cùng bắn query vào DB một lúc.
// - Tab ẩn: không tải, đánh dấu cũ; hiện lại thì tải 1 lần.
// - Mỗi canvas chỉ tạo Chart 1 lần, các lần sau gán data rồi update(). Lỗi tải giữ nguyên biểu đồ đang có và hiện thông
//   báo; lần tải sau thành công thì ẩn đi.
(function () {
    "use strict";

    const REFRESH_DELAY_MS = 1000;
    const REFRESH_MAX_JITTER_MS = 2000;
    const MAX_LABEL_LENGTH = 24;

    const container = document.getElementById("dashboardCharts");
    const revenueCanvas = document.getElementById("revenueChart");
    const statusCanvas = document.getElementById("statusChart");
    const topCanvas = document.getElementById("topProductsChart");
    const topWrapper = document.getElementById("topProductsChartWrapper");
    const topEmpty = document.getElementById("topProductsEmpty");
    const statusList = document.getElementById("statusCountList");
    const errorBox = document.getElementById("dashboardChartsError");
    if (!container || !container.dataset.chartsUrl || !revenueCanvas || !statusCanvas || !topCanvas
            || typeof Chart === "undefined") {
        return;
    }

    const charts = {revenue: null, status: null, top: null};
    let loading = false;
    let reloadAfterLoading = false;
    let refreshTimer = null;
    let staleWhileHidden = false;

    function revenueConfig(labels, values) {
        return {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: "Doanh thu (đ)",
                    data: values,
                    backgroundColor: "rgba(13,110,253,0.75)",
                    borderColor: "rgba(13,110,253,1)",
                    borderWidth: 1,
                    borderRadius: 6
                }]
            },
            options: {
                responsive: true,
                plugins: {legend: {display: false}},
                scales: {
                    y: {
                        beginAtZero: true,
                        ticks: {
                            callback: function (value) {
                                return Number(value).toLocaleString("vi-VN") + " đ";
                            }
                        }
                    }
                }
            }
        };
    }

    function statusConfig(labels, values) {
        return {
            type: "doughnut",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: ["#ffc107", "#0dcaf0", "#198754", "#dc3545"],
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                plugins: {legend: {position: "bottom", labels: {boxWidth: 12, padding: 8}}},
                cutout: "65%"
            }
        };
    }

    function topProductsConfig(labels, values) {
        return {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: "Đã bán",
                    data: values,
                    backgroundColor: "rgba(25,135,84,0.75)",
                    borderColor: "rgba(25,135,84,1)",
                    borderWidth: 1,
                    borderRadius: 6
                }]
            },
            options: {
                indexAxis: "y", // cột ngang: tên sản phẩm dài vẫn đọc được
                responsive: true,
                plugins: {legend: {display: false}},
                scales: {
                    x: {beginAtZero: true, ticks: {precision: 0}},
                    y: {
                        ticks: {
                            // Nhãn trục rút gọn; tooltip vẫn hiện tên đầy đủ. Vẽ trên canvas nên không có rủi ro XSS
                            callback: function (value) {
                                const label = String(this.getLabelForValue(value));
                                return label.length > MAX_LABEL_LENGTH
                                        ? label.slice(0, MAX_LABEL_LENGTH - 1) + "…" : label;
                            }
                        }
                    }
                }
            }
        };
    }

    /** Tạo biểu đồ lần đầu, các lần sau chỉ thay dữ liệu (không tạo Chart mới trên cùng canvas). */
    function upsert(key, canvas, configFactory, labels, values) {
        const chart = charts[key];
        if (chart === null) {
            charts[key] = new Chart(canvas, configFactory(labels, values));
            return;
        }
        chart.data.labels = labels;
        chart.data.datasets[0].data = values;
        chart.update();
    }

    function renderStatusList(labels, counts) {
        if (!statusList) {
            return;
        }
        const rows = labels.map(function (label, index) {
            const row = document.createElement("div");
            row.className = "d-flex justify-content-between small text-muted mb-1";
            const name = document.createElement("span");
            name.textContent = label;
            const count = document.createElement("strong");
            count.textContent = String(counts[index]);
            row.append(name, count);
            return row;
        });
        statusList.replaceChildren.apply(statusList, rows);
    }

    function renderTopProducts(labels, quantities) {
        const empty = labels.length === 0;
        if (topEmpty) {
            topEmpty.hidden = !empty;
        }
        if (topWrapper) {
            topWrapper.hidden = empty;
        }
        if (!empty) {
            upsert("top", topCanvas, topProductsConfig, labels, quantities);
        }
    }

    function isPairOfArrays(labels, values) {
        return Array.isArray(labels) && Array.isArray(values) && labels.length === values.length;
    }

    function render(data) {
        if (!data || !isPairOfArrays(data.monthLabels, data.monthRevenues)
                || !isPairOfArrays(data.statusLabels, data.statusCounts)
                || !isPairOfArrays(data.topProductLabels, data.topProductQuantities)) {
            throw new Error("Unexpected chart payload");
        }
        upsert("revenue", revenueCanvas, revenueConfig, data.monthLabels, data.monthRevenues);
        upsert("status", statusCanvas, statusConfig, data.statusLabels, data.statusCounts);
        renderStatusList(data.statusLabels, data.statusCounts);
        renderTopProducts(data.topProductLabels, data.topProductQuantities);
    }

    function load() {
        if (loading) {
            reloadAfterLoading = true;
            return;
        }
        loading = true;
        fetch(container.dataset.chartsUrl, {
            headers: {Accept: "application/json"},
            credentials: "same-origin",
            cache: "no-store",
            redirect: "error" // session hết hạn → bị chuyển tới /login: coi là lỗi, không parse trang HTML
        }).then(function (response) {
            if (!response.ok) {
                throw new Error("HTTP " + response.status);
            }
            return response.json();
        }).then(function (data) {
            render(data);
            if (errorBox) {
                errorBox.hidden = true;
            }
        }).catch(function (error) {
            console.warn("Dashboard charts unavailable:", error.message);
            if (errorBox) {
                errorBox.hidden = false;
            }
        }).finally(function () {
            loading = false;
            if (reloadAfterLoading) {
                reloadAfterLoading = false;
                scheduleRefresh();
            }
        });
    }

    function scheduleRefresh() {
        if (document.hidden) {
            staleWhileHidden = true;
            return;
        }
        if (refreshTimer !== null) {
            return; // đã hẹn: sự kiện này được gộp vào lần tải đó
        }
        const delay = REFRESH_DELAY_MS + Math.floor(Math.random() * REFRESH_MAX_JITTER_MS);
        refreshTimer = window.setTimeout(function () {
            refreshTimer = null;
            load();
        }, delay);
    }

    document.addEventListener("admin:orders-changed", function () {
        scheduleRefresh();
    });
    document.addEventListener("admin:realtime-resync", function (event) {
        // Lần kết nối đầu của trang: dữ liệu vừa tải ở dưới — chỉ tải lại khi nối lại (có thể đã lỡ sự kiện)
        if (!event.detail || !event.detail.initial) {
            scheduleRefresh();
        }
    });
    document.addEventListener("visibilitychange", function () {
        if (!document.hidden && staleWhileHidden) {
            staleWhileHidden = false;
            scheduleRefresh();
        }
    });

    load();
})();
