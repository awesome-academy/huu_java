package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.service.excel.ProductImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/**
 * Import/export sản phẩm bằng Excel. Tách khỏi {@link ProductAdminController} (class đó nạp danh sách danh mục cho mọi
 * handler — không cần ở đây).
 */
@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class ProductExcelController {

    static final String IMPORT_VIEW = "admin/products/import";

    private final ProductImportService productImportService;

    /** Giới hạn đang cấu hình, để trang import hiển thị đúng con số (controller không đọc cấu hình trực tiếp). */
    @ModelAttribute("importMaxRows")
    public int importMaxRows() {
        return productImportService.maxRows();
    }

    @ModelAttribute("importMaxFileSizeMb")
    public long importMaxFileSizeMb() {
        return productImportService.maxFileSizeMb();
    }

    @GetMapping("/import")
    public String importPage() {
        return IMPORT_VIEW;
    }

    /** File mẫu .xlsx (dòng tiêu đề + hướng dẫn + danh mục hiện có). */
    @GetMapping("/import/template")
    public void importTemplate(HttpServletResponse response) throws IOException {
        ExcelDownload.write(productImportService.template(), response);
    }

    /**
     * Import file: mọi dòng hợp lệ → lưu tất cả rồi quay lại trang import kèm thông báo; có dòng lỗi → không lưu gì, hiển
     * thị bảng lỗi; file bị từ chối cả file → thông báo lỗi.
     */
    @PostMapping("/import")
    public String importProducts(@RequestParam(value = "file", required = false) MultipartFile file,
                                 Model model, RedirectAttributes ra) {
        ProductImportResDto result;
        try {
            result = productImportService.importProducts(file);
        } catch (ExcelFileException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return IMPORT_VIEW;
        }
        if (!result.isSuccess()) {
            model.addAttribute("importResult", result);
            return IMPORT_VIEW;
        }
        ra.addFlashAttribute("successMessage", "Import xong " + result.totalRows() + " dòng: thêm " + result.created()
                + " · cập nhật " + result.updated() + " · không đổi " + result.unchanged() + " sản phẩm.");
        return "redirect:/admin/products/import";
    }
}
