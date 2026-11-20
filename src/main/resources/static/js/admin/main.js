// Main Javascript for Admin — gắn sự kiện bằng addEventListener (CSP cấm handler on* và script inline).
(function () {
    "use strict";

    // -------------------------------------------------------
    // Hộp xác nhận: <form data-confirm="..."> (submit) hoặc <button data-confirm="..."> (click).
    // Message lấy từ thuộc tính (Thymeleaf đã escape), hiển thị bằng confirm() nên không chèn HTML.
    // -------------------------------------------------------
    document.addEventListener("submit", function (event) {
        const form = event.target.closest("form[data-confirm]");
        if (form && !window.confirm(form.dataset.confirm)) {
            event.preventDefault();
        }
    });

    document.addEventListener("click", function (event) {
        const element = event.target.closest("[data-confirm]:not(form)");
        if (element && !window.confirm(element.dataset.confirm)) {
            event.preventDefault();
        }
    });

    // -------------------------------------------------------
    // <input type="file" data-max-file-size="bytes">: báo lỗi ngay khi chọn file quá lớn. Server vẫn chặn
    // (spring.servlet.multipart.*), nhưng request quá lớn bị trả 403 (Tomcat không đọc được CSRF token) — khó hiểu.
    // -------------------------------------------------------
    document.addEventListener("change", function (event) {
        const input = event.target;
        if (!(input instanceof HTMLInputElement) || input.type !== "file" || !input.dataset.maxFileSize) {
            return;
        }
        const maxBytes = Number(input.dataset.maxFileSize);
        const tooLarge = Array.from(input.files || []).filter(function (file) {
            return file.size > maxBytes;
        });
        if (tooLarge.length > 0) {
            const maxMb = (maxBytes / (1024 * 1024)).toFixed(0);
            window.alert("Ảnh vượt quá " + maxMb + "MB: " + tooLarge.map(function (f) { return f.name; }).join(", "));
            input.value = "";
        }
    });
})();
