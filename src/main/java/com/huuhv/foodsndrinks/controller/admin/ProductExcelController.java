package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.ImportJobResDto;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.service.excel.ExcelFile;
import com.huuhv.foodsndrinks.service.excel.ImportJobService;
import com.huuhv.foodsndrinks.service.excel.ProductExportService;
import com.huuhv.foodsndrinks.service.excel.ProductImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;

/**
 * Import/export sản phẩm bằng Excel. Tách khỏi {@link ProductAdminController} (class đó nạp danh sách danh mục cho mọi
 * handler — không cần ở đây).
 * <p>
 * Import chạy nền: upload chỉ tạo một "lần import" rồi chuyển ngay sang trang trạng thái; file được xử lý bởi consumer
 * của queue JMS {@code excel.import}. Mọi admin xem được mọi lần import (dữ liệu quản trị dùng chung, như đơn hàng).
 */
@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class ProductExcelController {

    static final String IMPORT_VIEW = "admin/products/import";
    static final String STATUS_VIEW = "admin/products/import-status";

    private final ProductImportService productImportService;
    private final ImportJobService importJobService;
    private final ProductExportService productExportService;

    /**
     * Tải file .xlsx gồm đúng các sản phẩm mà trang {@code /admin/products} liệt kê với cùng bộ lọc (mọi trang), cùng định
     * dạng cột với file mẫu import. Vượt giới hạn số dòng → không tạo file, quay lại danh sách kèm thông báo.
     */
    @GetMapping("/export")
    public String exportProducts(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) Long categoryId,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String isAvailable,
                                 HttpServletResponse response,
                                 RedirectAttributes ra) throws IOException {
        ExcelFile file;
        try {
            file = productExportService.export(name, categoryId, type, isAvailable);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            ra.addAttribute("name", name);
            ra.addAttribute("categoryId", categoryId);
            ra.addAttribute("type", type);
            ra.addAttribute("isAvailable", isAvailable);
            return "redirect:/admin/products";
        }
        // File đã dựng xong trước khi đụng tới response; trả null = response đã được ghi trực tiếp
        ExcelDownload.write(file, response);
        return null;
    }

    @GetMapping("/import")
    public String importPage(Model model) {
        return importView(model);
    }

    /** File mẫu .xlsx (dòng tiêu đề + hướng dẫn + danh mục hiện có). */
    @GetMapping("/import/template")
    public void importTemplate(HttpServletResponse response) throws IOException {
        ExcelDownload.write(productImportService.template(), response);
    }

    /**
     * Nhận file và trả về NGAY trang trạng thái của lần import vừa tạo — không chờ đọc/ghi file. File bị từ chối ngay từ
     * lúc tải lên (không phải .xlsx, quá lớn, đang có quá nhiều file chờ) → thông báo lỗi trên trang import.
     */
    @PostMapping("/import")
    public String importProducts(@RequestParam(value = "file", required = false) MultipartFile file,
                                 Principal principal, Model model) {
        long id;
        try {
            id = importJobService.submit(file, principal.getName());
        } catch (ExcelFileException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return importView(model);
        }
        return "redirect:/admin/products/import/" + id;
    }

    /** Trang trạng thái: tự cập nhật bằng cách gọi lại {@link #importStatusJson} cho tới khi lần import kết thúc. */
    @GetMapping("/import/{id}")
    public String importStatus(@PathVariable long id, Model model) {
        model.addAttribute("job", importJobService.get(id));
        return STATUS_VIEW;
    }

    /** Trạng thái hiện tại của lần import (JSON) — {@code static/js/admin/import-status.js} polling. */
    @GetMapping(value = "/import/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ImportJobResDto importStatusJson(@PathVariable long id) {
        return importJobService.get(id);
    }

    /** Trang import cần giới hạn đang cấu hình (controller không đọc cấu hình trực tiếp) và các lần import gần đây. */
    private String importView(Model model) {
        model.addAttribute("importMaxRows", productImportService.maxRows());
        model.addAttribute("importMaxFileSizeMb", productImportService.maxFileSizeMb());
        model.addAttribute("recentImports", importJobService.recent());
        return IMPORT_VIEW;
    }
}
