package com.huuhv.foodsndrinks.service.soap;

import com.huuhv.foodsndrinks.config.WebServiceClientConfig;
import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.exception.ExchangeRateUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withClientOrSenderFault;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;
import static org.springframework.ws.test.client.ResponseCreators.withServerOrReceiverFault;

/**
 * {@link ExchangeRateSoapClient}: request đúng hợp đồng, response của dịch vụ ngoài không được tin (sai XSD, sai cặp tiền,
 * mốc thời gian vô lý), và mọi lỗi thành {@link ExchangeRateUnavailableException} chỉ mang mã lý do. Phần SOAP dùng
 * {@link MockWebServiceServer}; phần HTTP (timeout, HTTP Basic, redirect, 401) dùng một HTTP server thật của JDK với client
 * dựng bằng đúng factory của production.
 */
class ExchangeRateSoapClientTest {

    private static final Instant NOW = Instant.parse("2026-11-28T03:00:00Z");
    private static final String NS = "urn:foodsndrinks:exchange-rate";

    private SaajSoapMessageFactory messageFactory;
    private MockWebServiceServer soapServer;
    private ExchangeRateSoapClient client;
    private HttpServer httpServer;

    @BeforeEach
    void setUp() {
        messageFactory = new SaajSoapMessageFactory();
        messageFactory.afterPropertiesSet();
        Jaxb2Marshaller marshaller = WebServiceClientConfig.exchangeRateMarshaller();
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri("http://rates.invalid/ws");
        soapServer = MockWebServiceServer.createServer(template);
        client = new ExchangeRateSoapClient(template, () -> NOW);
    }

    @AfterEach
    void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // -------------------------------------------------------
    // Hợp đồng + response không tin cậy
    // -------------------------------------------------------

    @Test
    void sendsTheContractRequest_andReturnsRateAndTimestamp() {
        soapServer.expect(payload(new StringSource("<GetExchangeRateRequest xmlns=\"" + NS + "\">"
                        + "<baseCurrency>USD</baseCurrency><quoteCurrency>VND</quoteCurrency></GetExchangeRateRequest>")))
                .andRespond(withPayload(new StringSource(response("USD", "VND", "25412.350000", "2026-11-28T02:00:00Z"))));

        ExchangeRateSoapClient.Quote quote = client.getRate("USD", "VND");

        assertThat(quote.rate()).isEqualByComparingTo("25412.35");
        assertThat(quote.asOf()).isEqualTo(Instant.parse("2026-11-28T02:00:00Z"));
        soapServer.verify();
    }

    @Test
    void timestampWithOffset_isConvertedToTheSameInstant() {
        respondWith(response("USD", "VND", "25400", "2026-11-28T09:00:00+07:00"));

        assertThat(client.getRate("USD", "VND").asOf()).isEqualTo(Instant.parse("2026-11-28T02:00:00Z"));
    }

    @Test
    void soapFault_isUnavailable_withoutTheServersFaultText() {
        soapServer.expect(payload(request())).andRespond(
                withServerOrReceiverFault("ORA-00942 table RATES does not exist at db-internal-01", Locale.ENGLISH));
        assertUnavailable("SOAP_FAULT");

        soapServer.reset();
        soapServer.expect(payload(request())).andRespond(withClientOrSenderFault("<script>alert(1)</script>", Locale.ENGLISH));
        assertUnavailable("SOAP_FAULT");
    }

    @Test
    void responseThatBreaksTheContract_isRejected() {
        List<String> invalid = List.of(
                // Tỷ giá 0 / âm / không phải số
                response("USD", "VND", "0", "2026-11-28T02:00:00Z"),
                response("USD", "VND", "-25400", "2026-11-28T02:00:00Z"),
                response("USD", "VND", "abc", "2026-11-28T02:00:00Z"),
                // Mã tiền sai định dạng
                response("usd", "VND", "25400", "2026-11-28T02:00:00Z"),
                // Thiếu phần tử bắt buộc
                "<GetExchangeRateResponse xmlns=\"" + NS + "\"><baseCurrency>USD</baseCurrency></GetExchangeRateResponse>",
                // Không phải response của hợp đồng này
                "<Other xmlns=\"" + NS + "\"/>");
        for (String payload : invalid) {
            soapServer.reset();
            respondWith(payload);
            assertUnavailable("INVALID_RESPONSE");
        }
    }

    @Test
    void validXmlButWrongAnswer_isRejected() {
        // Trả lời cho cặp tiền khác với cặp đã hỏi
        respondWith(response("EUR", "VND", "27600", "2026-11-28T02:00:00Z"));
        assertUnavailable("WRONG_CURRENCY_PAIR");

        // Mốc thời gian không có múi giờ
        soapServer.reset();
        respondWith(response("USD", "VND", "25400", "2026-11-28T02:00:00"));
        assertUnavailable("INVALID_AS_OF");

        // Mốc ở tương lai quá độ lệch đồng hồ cho phép
        soapServer.reset();
        respondWith(response("USD", "VND", "25400", "2026-11-28T03:05:01Z"));
        assertUnavailable("AS_OF_IN_FUTURE");

        // Lệch vài phút thì chấp nhận
        soapServer.reset();
        respondWith(response("USD", "VND", "25400", "2026-11-28T03:04:59Z"));
        assertThat(client.getRate("USD", "VND").rate()).isEqualByComparingTo("25400");
    }

    @Test
    void connectionFailure_isUnavailable() {
        soapServer.expect(payload(request())).andRespond(
                org.springframework.ws.test.client.ResponseCreators.withException(new IOException("Connection refused")));

        assertUnavailable("TIMEOUT_OR_IO");
    }

    // -------------------------------------------------------
    // HTTP thật: timeout, tài khoản, redirect
    // -------------------------------------------------------

    @Test
    void sendsConfiguredBasicCredentials_andReadsTheRate() throws Exception {
        List<String> authorization = new CopyOnWriteArrayList<>();
        ExchangeRateSoapClient http = httpClient(exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, envelope(response("USD", "VND", "25400", "2026-11-28T02:00:00Z")));
        }, "svc-user", "s3cret pass", Duration.ofSeconds(3));

        assertThat(http.getRate("USD", "VND").rate()).isEqualByComparingTo("25400");
        // base64("svc-user:s3cret pass")
        assertThat(authorization).containsExactly("Basic c3ZjLXVzZXI6czNjcmV0IHBhc3M=");
    }

    @Test
    void withoutConfiguredUsername_noAuthorizationHeaderIsSent() throws Exception {
        List<String> authorization = new ArrayList<>();
        ExchangeRateSoapClient http = httpClient(exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, envelope(response("USD", "VND", "25400", "2026-11-28T02:00:00Z")));
        }, "", "", Duration.ofSeconds(3));

        http.getRate("USD", "VND");

        assertThat(authorization).containsExactly((String) null);
    }

    @Test
    void slowService_failsAfterTheReadTimeout_insteadOfHoldingTheThread() throws Exception {
        ExchangeRateSoapClient http = httpClient(exchange -> {
            sleep(Duration.ofSeconds(3));
            respond(exchange, 200, envelope(response("USD", "VND", "25400", "2026-11-28T02:00:00Z")));
        }, "svc", "pw", Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatThrownBy(() -> http.getRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class).hasMessage("TIMEOUT_OR_IO");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void wrongCredentials_orServerError_isUnavailable_withoutTheResponseBody() throws Exception {
        ExchangeRateSoapClient unauthorized = httpClient(exchange ->
                respond(exchange, 401, "<html>secret realm page</html>"), "svc", "wrong", Duration.ofSeconds(3));

        assertThatThrownBy(() -> unauthorized.getRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessage("HTTP_ERROR")
                .hasNoCause();
    }

    @Test
    void redirect_isNotFollowed_soCredentialsNeverGoToAnotherHost() throws Exception {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        ExchangeRateSoapClient http = httpClient(exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/ws")) {
                exchange.getResponseHeaders().add("Location", "/elsewhere");
                respond(exchange, 302, "");
            } else {
                respond(exchange, 200, envelope(response("USD", "VND", "25400", "2026-11-28T02:00:00Z")));
            }
        }, "svc", "pw", Duration.ofSeconds(3));

        assertThatThrownBy(() -> http.getRate("USD", "VND")).isInstanceOf(ExchangeRateUnavailableException.class);

        assertThat(requestedPaths).containsExactly("/ws");
    }

    @Test
    void responseWithDoctype_isRejected_soExternalEntitiesAreNeverResolved() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                + envelope("<GetExchangeRateResponse xmlns=\"" + NS + "\"><baseCurrency>USD</baseCurrency>"
                + "<quoteCurrency>VND</quoteCurrency><rate>25400</rate><asOf>&xxe;</asOf></GetExchangeRateResponse>");
        ExchangeRateSoapClient http = httpClient(exchange -> respond(exchange, 200, xxe), "svc", "pw",
                Duration.ofSeconds(3));

        assertThatThrownBy(() -> http.getRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasNoCause();
    }

    // -------------------------------------------------------

    private void respondWith(String responsePayload) {
        soapServer.expect(payload(request())).andRespond(withPayload(new StringSource(responsePayload)));
    }

    private void assertUnavailable(String reason) {
        assertThatThrownBy(() -> client.getRate("USD", "VND"))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessage(reason)
                // Không giữ cause: thông điệp gốc có thể chứa fault string/XML của máy chủ bên kia
                .hasNoCause();
    }

    private static StringSource request() {
        return new StringSource("<GetExchangeRateRequest xmlns=\"" + NS + "\"><baseCurrency>USD</baseCurrency>"
                + "<quoteCurrency>VND</quoteCurrency></GetExchangeRateRequest>");
    }

    private static String response(String base, String quote, String rate, String asOf) {
        return "<GetExchangeRateResponse xmlns=\"" + NS + "\"><baseCurrency>" + base + "</baseCurrency><quoteCurrency>"
                + quote + "</quoteCurrency><rate>" + rate + "</rate><asOf>" + asOf + "</asOf></GetExchangeRateResponse>";
    }

    private static String envelope(String payload) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + payload + "</soapenv:Body></soapenv:Envelope>";
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Client dựng bằng factory của production, trỏ vào một HTTP server của JDK trên cổng ngẫu nhiên. */
    private ExchangeRateSoapClient httpClient(Handler handler, String username, String password, Duration readTimeout)
            throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange);
            }
        });
        httpServer.start();
        SoapProperties.ExchangeRate properties = new SoapProperties.ExchangeRate(
                "http://localhost:" + httpServer.getAddress().getPort() + "/ws", username, password,
                Duration.ofSeconds(3), readTimeout, Duration.ofMinutes(10), Duration.ofMinutes(1),
                new SoapProperties.MockEndpoint(false));
        return WebServiceClientConfig.exchangeRateSoapClient(properties, messageFactory);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
