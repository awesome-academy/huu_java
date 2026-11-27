package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.service.excel.ExcelFile;
import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter;
import com.huuhv.foodsndrinks.service.excel.ProductImportService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** {@link ProductExcelController} qua chain web thật: chỉ {@code ROLE_ADMIN}, upload bắt CSRF. */
@SecuredWebMvcTest(ProductExcelController.class)
class ProductExcelControllerTest {

    private static final String IMPORT_URL = "/admin/products/import";
    private static final String TEMPLATE_URL = "/admin/products/import/template";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ProductImportService productImportService;

    private final MockMultipartFile file = new MockMultipartFile("file", "san-pham.xlsx",
            ExcelSheetWriter.XLSX_CONTENT_TYPE, new byte[]{'P', 'K', 3, 4});

    @BeforeEach
    void limits() {
        when(productImportService.maxRows()).thenReturn(1000);
        when(productImportService.maxFileSizeMb()).thenReturn(5L);
    }

    // -------------------------------------------------------
    // Bảo mật
    // -------------------------------------------------------

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get(IMPORT_URL)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(TEMPLATE_URL)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf())).andExpect(redirectedUrl("/login"));
        verify(productImportService, never()).importProducts(any());
        verify(productImportService, never()).template();
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get(IMPORT_URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(TEMPLATE_URL)).andExpect(status().isForbidden());
        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf())).andExpect(status().isForbidden());
        verify(productImportService, never()).importProducts(any());
        verify(productImportService, never()).template();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_upload_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(multipart(IMPORT_URL).file(file)).andExpect(status().isForbidden());
        verify(productImportService, never()).importProducts(any());
    }

    // -------------------------------------------------------
    // Trang import + file mẫu
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void importPage_showsTheUploadForm_theTemplateLink_andTheConfiguredLimits() throws Exception {
        String html = mockMvc.perform(get(IMPORT_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/import"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("href=\"" + TEMPLATE_URL + "\"")
                .contains("action=\"" + IMPORT_URL + "\"")
                .contains("enctype=\"multipart/form-data\"")
                .contains("name=\"_csrf\"")
                .containsPattern("<input type=\"file\" name=\"file\"[^>]*data-max-file-size=\"\\d+\"")
                .containsPattern("tối đa <span>5</span> MB và\\s+<span>1000</span> dòng dữ liệu")
                .doesNotContain("data-import-errors");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void template_isDownloadedAsXlsxAttachment() throws Exception {
        ExcelSheetWriter sheet = new ExcelSheetWriter("Sản phẩm", List.of(new ExcelSheetWriter.Column("Tên", 20)));
        when(productImportService.template())
                .thenReturn(new ExcelFile("foodsndrinks-products-template.xlsx", 0, sheet));

        MockHttpServletResponse response = mockMvc.perform(get(TEMPLATE_URL))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ExcelSheetWriter.XLSX_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"foodsndrinks-products-template.xlsx\""))
                .andReturn().getResponse();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Tên");
        }
    }

    // -------------------------------------------------------
    // Upload
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void validFile_redirectsBackWithTheNumberOfProductsAddedAndUpdated() throws Exception {
        when(productImportService.importProducts(file)).thenReturn(new ProductImportResDto(6, 3, 2, 1, 0, List.of()));

        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf()))
                .andExpect(redirectedUrl(IMPORT_URL))
                .andExpect(flash().attribute("successMessage",
                        "Import xong 6 dòng: thêm 3 · cập nhật 2 · không đổi 1 sản phẩm."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void fileWithRowErrors_showsTheErrorTable_escaped_andSavesNothing() throws Exception {
        when(productImportService.importProducts(file)).thenReturn(new ProductImportResDto(40, 0, 0, 0, 3, List.of(
                new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"),
                new ImportRowErrorResDto(7, "Tên", "<script>alert(1)</script>"))));

        String html = mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/import"))
                .andExpect(model().attributeExists("importResult"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-import-errors")
                .containsPattern("File có <span>3</span> lỗi trên\\s+<span>40</span> dòng dữ liệu")
                .contains("<td class=\"px-3 fw-medium\">3</td>").contains("<td>Giá</td>")
                .contains("<td>Giá phải lớn hơn 0!</td>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>")
                // 3 lỗi, chỉ 2 được hiển thị
                .containsPattern("và <span>1</span> lỗi khác");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectedFile_showsTheFriendlyMessage_onTheImportPage() throws Exception {
        when(productImportService.importProducts(any()))
                .thenThrow(new ExcelFileException("Chỉ nhận file Excel có đuôi .xlsx!"));

        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/import"))
                .andExpect(model().attribute("errorMessage", "Chỉ nhận file Excel có đuôi .xlsx!"))
                .andExpect(model().attributeDoesNotExist("importResult"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void uploadWithoutAFilePart_isHandledByTheService_notA400Page() throws Exception {
        when(productImportService.importProducts(null))
                .thenThrow(new ExcelFileException("Vui lòng chọn file Excel (.xlsx)!"));

        mockMvc.perform(multipart(IMPORT_URL).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorMessage", "Vui lòng chọn file Excel (.xlsx)!"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void productList_linksToTheImportPage() throws Exception {
        // Trang danh sách thuộc ProductAdminController (không nằm trong slice này) — kiểm tra template trực tiếp
        assertThat(new String(getClass().getResourceAsStream("/templates/admin/products/list.html").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8))
                .contains("th:href=\"@{/admin/products/import}\"");
    }
}
