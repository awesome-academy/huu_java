// Biểu đồ dashboard admin. Dữ liệu lấy từ GET /admin/dashboard/charts (URL trong data-charts-url) thay vì inline
// script — để bật được Content-Security-Policy không có 'unsafe-inline'.
(function () {
    "use strict";

    const container = document.getElementById("dashboardCharts");
    const revenueCanvas = document.getElementById("revenueChart");
    const statusCanvas = document.getElementById("statusChart");
    if (!container || !revenueCanvas || !statusCanvas || typeof Chart === "undefined") {
        return;
    }

    function renderRevenueChart(labels, revenues) {
        new Chart(revenueCanvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: "Doanh thu (đ)",
                    data: revenues,
                    backgroundColor: "rgba(13,110,253,0.75)",
                    borderColor: "rgba(13,110,253,1)",
                    borderWidth: 1,
                    borderRadius: 6
                }]
            },
            options: {
                responsive: true,
                plugins: { legend: { display: false } },
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
        });
    }

    function renderStatusChart(labels, counts) {
        new Chart(statusCanvas, {
            type: "doughnut",
            data: {
                labels: labels,
                datasets: [{
                    data: counts,
                    backgroundColor: ["#ffc107", "#0dcaf0", "#198754", "#dc3545"],
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                plugins: { legend: { position: "bottom", labels: { boxWidth: 12, padding: 8 } } },
                cutout: "65%"
            }
        });
    }

    function showError(canvas) {
        const message = document.createElement("p");
        message.className = "text-muted small mb-0";
        message.textContent = "Không tải được dữ liệu biểu đồ.";
        canvas.replaceWith(message);
    }

    fetch(container.dataset.chartsUrl, { headers: { Accept: "application/json" }, credentials: "same-origin" })
        .then(function (response) {
            if (!response.ok) {
                throw new Error("HTTP " + response.status);
            }
            return response.json();
        })
        .then(function (data) {
            renderRevenueChart(data.monthLabels, data.monthRevenues);
            renderStatusChart(data.statusLabels, data.statusCounts);
        })
        .catch(function (error) {
            console.warn("Dashboard charts unavailable:", error.message);
            showError(revenueCanvas);
            showError(statusCanvas);
        });
})();
