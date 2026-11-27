package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.ImportJobResDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.excel.ExcelFile;
import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter;
import com.huuhv.foodsndrinks.service.excel.ImportJobService;
import com.huuhv.foodsndrinks.service.excel.ProductImportService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** {@link ProductExcelController} qua chain web thật: chỉ {@code ROLE_ADMIN}, upload bắt CSRF. */
@SecuredWebMvcTest(ProductExcelController.class)
class ProductExcelControllerTest {

    private static final String IMPORT_URL = "/admin/products/import";
    private static final String TEMPLATE_URL = "/admin/products/import/template";
    private static final String STATUS_PAGE = IMPORT_URL + "/42";
    private static final String STATUS_JSON = IMPORT_URL + "/42/status";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @MockitoBean private ProductImportService productImportService;
    @MockitoBean private ImportJobService importJobService;

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
        mockMvc.perform(get(STATUS_PAGE)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(STATUS_JSON)).andExpect(redirectedUrl("/login"));
        verifyNothingHappened();
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get(IMPORT_URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(TEMPLATE_URL)).andExpect(status().isForbidden());
        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(get(STATUS_PAGE)).andExpect(status().isForbidden());
        mockMvc.perform(get(STATUS_JSON)).andExpect(status().isForbidden());
        verifyNothingHappened();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_upload_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(multipart(IMPORT_URL).file(file)).andExpect(status().isForbidden());
        verifyNothingHappened();
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
                // Chưa có lần import nào → không có bảng "gần đây"
                .doesNotContain("data-recent-imports");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importPage_listsRecentImports_linkedToTheirStatusPages() throws Exception {
        when(importJobService.recent()).thenReturn(List.of(
                new ImportJobResDto(43L, "moi.xlsx", ImportJobStatus.COMPLETED, "Hoàn thành", "bg-success", true, 6, 3,
                        2, 1, 0, List.of(), null, "boss", LocalDateTime.of(2026, 11, 27, 11, 0), null),
                job(ImportJobStatus.FAILED, 3, List.of(), null)));

        String html = mockMvc.perform(get(IMPORT_URL))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-recent-imports")
                .contains("href=\"/admin/products/import/43\"").contains("href=\"/admin/products/import/42\"")
                .contains("thêm 3 · cập nhật 2 · không đổi 1").contains("3 lỗi").contains("moi.xlsx");
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
    // Upload: tạo lần import rồi chuyển NGAY sang trang trạng thái (không chờ xử lý file)
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void upload_createsTheImportJobForTheSignedInAdmin_andRedirectsToItsStatusPage() throws Exception {
        when(importJobService.submit(file, "boss")).thenReturn(42L);

        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf()))
                .andExpect(redirectedUrl(STATUS_PAGE));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectedFile_showsTheFriendlyMessage_onTheImportPage() throws Exception {
        when(importJobService.submit(any(), any()))
                .thenThrow(new ExcelFileException("Chỉ nhận file Excel có đuôi .xlsx!"));

        mockMvc.perform(multipart(IMPORT_URL).file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/import"))
                .andExpect(model().attribute("errorMessage", "Chỉ nhận file Excel có đuôi .xlsx!"))
                .andExpect(model().attributeExists("importMaxRows", "recentImports"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void uploadWithoutAFilePart_isHandledByTheService_notA400Page() throws Exception {
        when(importJobService.submit(isNull(), any()))
                .thenThrow(new ExcelFileException("Vui lòng chọn file Excel (.xlsx)!"));

        mockMvc.perform(multipart(IMPORT_URL).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorMessage", "Vui lòng chọn file Excel (.xlsx)!"));
    }

    // -------------------------------------------------------
    // Trang trạng thái + JSON polling
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusPage_ofAnUnfinishedImport_carriesThePollingSettings_andLoadsTheScript() throws Exception {
        when(importJobService.get(42L)).thenReturn(job(ImportJobStatus.PROCESSING, 0, List.of(), null));

        String html = mockMvc.perform(get(STATUS_PAGE))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/import-status"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-import-status-url=\"" + STATUS_JSON + "\"")
                .contains("data-import-terminal=\"false\"")
                .containsPattern("<span class=\"badge bg-info text-dark\" data-import-status-badge[^>]*>Đang xử lý</span>")
                .contains("data-import-status-progress")
                .containsPattern("data-import-status-notice[^>]*hidden")
                .contains("san-pham.xlsx")
                .doesNotContain("data-import-completed").doesNotContain("data-import-errors")
                // JS nằm trong file riêng, nạp sau các script của layout (CSP: không script inline)
                .containsSubsequence("/js/admin/main.js", "/js/admin/import-status.js");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusPage_ofACompletedImport_showsTheCounts_andStopsPolling() throws Exception {
        when(importJobService.get(42L)).thenReturn(new ImportJobResDto(42L, "san-pham.xlsx",
                ImportJobStatus.COMPLETED, "Hoàn thành", "bg-success", true, 6, 3, 2, 1, 0, List.of(), null, "boss",
                LocalDateTime.of(2026, 11, 27, 10, 0), LocalDateTime.of(2026, 11, 27, 10, 0, 4)));

        String html = mockMvc.perform(get(STATUS_PAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-import-terminal=\"true\"")
                .contains("data-import-completed")
                .containsPattern("Import xong <strong>6</strong> dòng:\\s+thêm <strong>3</strong> ·\\s+cập nhật "
                        + "<strong>2</strong> ·\\s+không đổi <strong>1</strong> sản phẩm")
                .contains("27/11/2026 10:00:04")
                .doesNotContain("data-import-status-progress").doesNotContain("data-import-errors");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusPage_ofAFailedImport_showsTheErrorTable_escaped() throws Exception {
        when(importJobService.get(42L)).thenReturn(job(ImportJobStatus.FAILED, 3, List.of(
                new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"),
                new ImportRowErrorResDto(7, "Tên", "<script>alert(1)</script>")), null));

        String html = mockMvc.perform(get(STATUS_PAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-import-errors")
                .containsPattern("File có <span>3</span> lỗi trên\\s+<span>40</span> dòng dữ liệu")
                .contains("<td class=\"px-3 fw-medium\">3</td>").contains("<td>Giá</td>")
                .contains("<td>Giá phải lớn hơn 0!</td>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>")
                // 3 lỗi, chỉ 2 được giữ lại để hiển thị
                .containsPattern("và <span>1</span> lỗi khác")
                .doesNotContain("data-import-failure");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusPage_ofAnImportThatFailedAsAWhole_showsTheReason_withoutAnErrorTable() throws Exception {
        when(importJobService.get(42L)).thenReturn(job(ImportJobStatus.FAILED, 0, List.of(),
                "Lỗi hệ thống khi xử lý file. Vui lòng import lại!"));

        String html = mockMvc.perform(get(STATUS_PAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-import-failure").contains("Lỗi hệ thống khi xử lý file. Vui lòng import lại!")
                .doesNotContain("data-import-errors");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusJson_returnsTheFieldsThePollingScriptReads() throws Exception {
        when(importJobService.get(42L)).thenReturn(job(ImportJobStatus.PROCESSING, 0, List.of(), null));

        mockMvc.perform(get(STATUS_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.statusLabel").value("Đang xử lý"))
                .andExpect(jsonPath("$.badgeClass").value("bg-info text-dark"))
                .andExpect(jsonPath("$.terminal").value(false))
                .andExpect(jsonPath("$.errors").isArray());
        // Endpoint JSON không render view → không tốn truy vấn đếm đơn chờ của layout
        verify(orderService, never()).countPendingOrders();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownImport_returns404_forBothThePageAndTheJson() throws Exception {
        when(importJobService.get(404L)).thenThrow(new ResourceNotFoundException("Không tìm thấy lần import #404"));

        mockMvc.perform(get(IMPORT_URL + "/404")).andExpect(status().isNotFound())
                .andExpect(view().name("error/admin/404"));
        mockMvc.perform(get(IMPORT_URL + "/404/status")).andExpect(status().isNotFound());
    }

    @Test
    void productList_linksToTheImportPage() throws Exception {
        // Trang danh sách thuộc ProductAdminController (không nằm trong slice này) — kiểm tra template trực tiếp
        assertThat(new String(getClass().getResourceAsStream("/templates/admin/products/list.html").readAllBytes(),
                StandardCharsets.UTF_8))
                .contains("th:href=\"@{/admin/products/import}\"");
    }

    private void verifyNothingHappened() throws Exception {
        verify(importJobService, never()).submit(any(), any());
        verify(importJobService, never()).get(anyLong());
        verify(importJobService, never()).recent();
        verify(productImportService, never()).template();
    }

    private static ImportJobResDto job(ImportJobStatus status, int errorCount, List<ImportRowErrorResDto> errors,
                                       String failureMessage) {
        return new ImportJobResDto(42L, "san-pham.xlsx", status, status.getLabel(), status.getBadgeClass(),
                status.isTerminal(), errorCount > 0 ? 40 : 0, 0, 0, 0, errorCount, errors, failureMessage, "boss",
                LocalDateTime.of(2026, 11, 27, 10, 0),
                status.isTerminal() ? LocalDateTime.of(2026, 11, 27, 10, 0, 4) : null);
    }
}
