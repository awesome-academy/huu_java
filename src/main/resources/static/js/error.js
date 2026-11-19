// Trang lỗi (error/*.html, error/admin/*.html): nút "Quay lại" — CSP cấm onclick inline.
document.addEventListener("click", function (event) {
    if (event.target.closest("[data-action='back']")) {
        history.back();
    }
});
