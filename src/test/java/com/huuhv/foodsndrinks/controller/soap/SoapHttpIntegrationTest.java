package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.config.WebServiceConfig;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import com.huuhv.foodsndrinks.service.websocket.AbstractRealtimeIntegrationTest;
import com.huuhv.foodsndrinks.support.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOAP service qua HTTP thật (Tomcat cổng ngẫu nhiên, MySQL Testcontainers): filter chain {@code /ws/**} — HTTP Basic,
 * stateless, không CSRF — cùng phân quyền theo chủ đơn, WSDL, và các request không đọc được (XML hỏng, DOCTYPE, quá lớn).
 * Ánh xạ từng field và nội dung fault: {@code StoreEndpointTest}.
 */
class SoapHttpIntegrationTest extends AbstractRealtimeIntegrationTest {

    private static final String NS = "urn:foodsndrinks:store";

    @Autowired private ProductRepository productRepository;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void deleteProducts() {
        productIds.forEach(productRepository::deleteById);
    }

    // -------------------------------------------------------
    // Xác thực
    // -------------------------------------------------------

    @Test
    void withoutCredentials_is401_withBasicChallenge_noBody_noCookie() throws Exception {
        HttpResponse<String> response = post(null, null, envelope(getProducts("")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge ->
                assertThat(challenge).startsWith("Basic realm="));
        // Client là máy: không trang HTML, không redirect /login, không session
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void wrongPassword_is401_andAuditedAsSoapLogin() throws Exception {
        User user = createUser(Role.ROLE_USER);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            HttpResponse<String> response = post(user.getUsername(), "not-the-password", envelope(getProducts("")));

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(audit.lines()).anySatisfy(line -> assertThat(line).contains("event.action=LOGIN_FAILURE",
                    "event.reason=BAD_CREDENTIALS", "auth.method=SOAP", "username=" + user.getUsername()));
        }
    }

    @Test
    void lockedAccount_is401_evenWithTheRightPassword() throws Exception {
        User user = createUser(Role.ROLE_USER);
        assertThat(post(user.getUsername(), PASSWORD, envelope(getProducts(""))).statusCode()).isEqualTo(200);

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        // Stateless: không có session nào để "còn đăng nhập" — tài khoản được kiểm tra lại ở mỗi request
        assertThat(post(user.getUsername(), PASSWORD, envelope(getProducts(""))).statusCode()).isEqualTo(401);
    }

    @Test
    void browserSessionCookie_isNotAnIdentityForSoap() throws Exception {
        // CSRF tắt ở /ws/** vì chain này không nhận cookie: session của trình duyệt đang đăng nhập không gọi được SOAP
        User user = createUser(Role.ROLE_USER);
        BrowserSession browser = login(user);

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url("/ws")))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("Cookie", browser.sessionCookie())
                .POST(HttpRequest.BodyPublishers.ofString(envelope(getProducts("")))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(browser.sessionCookie()).isNotBlank();
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // -------------------------------------------------------
    // GetProducts / GetOrderStatus
    // -------------------------------------------------------

    @Test
    void getProducts_withValidAccount_returnsAvailableProducts_withoutCsrfToken_andWithoutCreatingASession()
            throws Exception {
        User user = createUser(Role.ROLE_USER);
        Product onSale = product("Trà đào SOAP", true);
        Product hidden = product("Trà ẩn SOAP", false);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            HttpResponse<String> response = post(user.getUsername(), PASSWORD, envelope(getProducts("<size>100</size>")));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/xml");
            assertThat(response.body()).contains("GetProductsResponse", onSale.getName(), onSale.getSlug())
                    .doesNotContain(hidden.getName());
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(audit.lines()).anySatisfy(line -> assertThat(line).contains("event.action=LOGIN_SUCCESS",
                    "auth.method=SOAP", "username=" + user.getUsername()));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, user.getUsername())).isZero();
    }

    @Test
    void getOrderStatus_ownerAndAdminSeeIt_anotherUserGetsExactlyTheFaultOfAMissingOrder() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        User other = createUser(Role.ROLE_USER);
        User admin = createUser(Role.ROLE_ADMIN);
        Order order = createOrder(owner, OrderStatus.PROCESSING);
        Order cart = createOrder(owner, OrderStatus.CART);
        long missingId = 9_000_000_000L + order.getId();

        HttpResponse<String> asOwner = post(owner.getUsername(), PASSWORD, envelope(getOrderStatus(order.getId())));
        HttpResponse<String> asAdmin = post(admin.getUsername(), PASSWORD, envelope(getOrderStatus(order.getId())));
        HttpResponse<String> asOther = post(other.getUsername(), PASSWORD, envelope(getOrderStatus(order.getId())));
        HttpResponse<String> missing = post(other.getUsername(), PASSWORD, envelope(getOrderStatus(missingId)));
        HttpResponse<String> ownCart = post(owner.getUsername(), PASSWORD, envelope(getOrderStatus(cart.getId())));

        assertThat(asOwner.statusCode()).isEqualTo(200);
        assertThat(asOwner.body()).contains("GetOrderStatusResponse", ">PROCESSING<", ">Đang xử lý<");
        assertThat(asAdmin.body()).contains(">PROCESSING<");
        // SOAP 1.1: fault đi kèm HTTP 500. Đơn của người khác = đơn không tồn tại: cùng mã, cùng nội dung fault
        assertThat(asOther.statusCode()).isEqualTo(500);
        assertThat(asOther.body()).contains("Fault", WebServiceConfig.FAULT_NOT_FOUND).doesNotContain("PROCESSING");
        assertThat(asOther.body()).isEqualTo(missing.body());
        // Giỏ hàng không phải đơn, kể cả với chủ của nó
        assertThat(ownCart.body()).isEqualTo(missing.body());
    }

    @Test
    void requestThatBreaksTheContract_isAClientFault_notAnHtmlErrorPage() throws Exception {
        User user = createUser(Role.ROLE_USER);

        HttpResponse<String> response = post(user.getUsername(), PASSWORD, envelope(getProducts("<size>101</size>")));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("Client", WebServiceConfig.FAULT_VALIDATION).doesNotContain("<html", "101");
    }

    // -------------------------------------------------------
    // WSDL
    // -------------------------------------------------------

    @Test
    void wsdl_isPublic_describesBothOperations_andPointsAtTheRequestedHost() throws Exception {
        HttpResponse<String> wsdl = get(null, null, "/ws/foodsndrinks.wsdl");

        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("GetProductsRequest", "GetOrderStatusRequest", "targetNamespace=\"" + NS + "\"")
                // transformWsdlLocations: địa chỉ service theo host/cổng của request, không phải localhost:8080 cứng
                .contains("location=\"" + url("/ws") + "\"");
        assertThat(wsdl.headers().allValues("Set-Cookie")).isEmpty();
        // Chỉ file WSDL là public: mọi thứ khác dưới /ws cần tài khoản
        assertThat(get(null, null, "/ws/anything").statusCode()).isEqualTo(401);
        assertThat(get(null, null, "/ws").statusCode()).isEqualTo(401);
    }

    @Test
    void getOnTheServicePath_isMethodNotAllowed_withoutAnHtmlPage() throws Exception {
        User user = createUser(Role.ROLE_USER);

        HttpResponse<String> response = get(user.getUsername(), PASSWORD, "/ws");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).doesNotContain("<html", "<!DOCTYPE");
    }

    // -------------------------------------------------------
    // Request không đọc được
    // -------------------------------------------------------

    @Test
    void doctype_isRejectedWith400_soExternalEntitiesAreNeverResolved() throws Exception {
        User user = createUser(Role.ROLE_USER);
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + "<GetOrderStatusRequest xmlns=\"" + NS + "\"><orderId>&xxe;</orderId></GetOrderStatusRequest>"
                + "</soapenv:Body></soapenv:Envelope>";

        HttpResponse<String> response = post(user.getUsername(), PASSWORD, xxe);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void malformedXml_orNotSoap_is400_withoutBody() throws Exception {
        User user = createUser(Role.ROLE_USER);

        for (String garbage : List.of("<soapenv:Envelope", "not xml at all", "<a><b></a>")) {
            HttpResponse<String> response = post(user.getUsername(), PASSWORD, garbage);

            assertThat(response.statusCode()).as(garbage).isEqualTo(400);
            assertThat(response.body()).as(garbage).isEmpty();
        }
    }

    @Test
    void oversizedBody_is413_withOrWithoutContentLength() throws Exception {
        User user = createUser(Role.ROLE_USER);
        byte[] huge = envelope(getProducts("<!-- " + "x".repeat(70_000) + " -->")).getBytes(StandardCharsets.UTF_8);

        // Có Content-Length: bị từ chối trước khi đọc body
        HttpResponse<String> declared = send(user.getUsername(), PASSWORD,
                HttpRequest.BodyPublishers.ofByteArray(huge));
        // Chunked (không Content-Length): bị cắt khi đọc quá giới hạn
        HttpResponse<String> chunked = send(user.getUsername(), PASSWORD,
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge)));

        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(declared.body()).isEmpty();
        assertThat(chunked.body()).isEmpty();
    }

    // -------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> post(String username, String password, String body) throws Exception {
        return send(username, password, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(String username, String password, HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/ws")))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(body);
        authenticate(request, username, password);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String username, String password, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).GET();
        authenticate(request, username, password);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void authenticate(HttpRequest.Builder request, String username, String password) {
        if (username != null) {
            String credentials = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
    }

    private static String envelope(String payload) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + payload + "</soapenv:Body></soapenv:Envelope>";
    }

    private static String getProducts(String parameters) {
        return "<GetProductsRequest xmlns=\"" + NS + "\">" + parameters + "</GetProductsRequest>";
    }

    private static String getOrderStatus(long orderId) {
        return "<GetOrderStatusRequest xmlns=\"" + NS + "\"><orderId>" + orderId + "</orderId></GetOrderStatusRequest>";
    }

    /** Sản phẩm đã commit (Tomcat chạy trên thread khác), tự xoá sau test. */
    private Product product(String name, boolean available) {
        String unique = name + " " + UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product();
        product.setName(unique);
        product.setSlug(unique.toLowerCase().replace(' ', '-'));
        product.setType(ProductType.DRINK);
        product.setPrice(new BigDecimal("35000"));
        product.setIsAvailable(available);
        Product saved = productRepository.saveAndFlush(product);
        productIds.add(saved.getId());
        return saved;
    }
}
