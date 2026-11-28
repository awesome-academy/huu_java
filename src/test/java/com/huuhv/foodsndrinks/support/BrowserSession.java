package com.huuhv.foodsndrinks.support;

import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Một "trình duyệt" cho integration test chạy trên Tomcat thật ({@code RANDOM_PORT}): đăng nhập form bằng HTTP thật
 * (cookie {@code JSESSIONID}, token CSRF đọc từ trang như JS của app), rồi mở kết nối STOMP qua WebSocket với cookie đó —
 * đúng đường đi của {@code static/js/realtime.js}.
 */
public final class BrowserSession {

    private static final Pattern FORM_CSRF = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern META_CSRF = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
    private static final Pattern META_CSRF_HEADER = Pattern.compile("<meta name=\"_csrf_header\" content=\"([^\"]+)\"");
    private static final Pattern JSON_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    public static final Duration WAIT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final CookieManager cookies;
    private final HttpClient http;
    private String csrfToken;
    private String csrfHeader;

    private BrowserSession(int port) {
        this(port, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private BrowserSession(int port, CookieManager cookies) {
        this.baseUrl = "http://localhost:" + port;
        this.cookies = cookies;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * CÙNG trình duyệt này (chung cookie — cookie không phân biệt cổng — và token CSRF của trang đang mở) nhưng request đi
     * tới instance khác: như load balancer không sticky chuyển request kế tiếp sang instance {@code port}.
     */
    public BrowserSession onInstance(int port) {
        BrowserSession sameBrowser = new BrowserSession(port, cookies);
        sameBrowser.csrfToken = csrfToken;
        sameBrowser.csrfHeader = csrfHeader;
        return sameBrowser;
    }

    /** Đăng nhập form ({@code POST /login}) rồi đọc token CSRF (đã mask) từ meta của trang chủ. */
    public static BrowserSession login(int port, String username, String password) throws Exception {
        BrowserSession browser = new BrowserSession(port);
        browser.signIn(username, password);
        return browser;
    }

    /**
     * Đăng nhập (lại) bằng form với cookie hiện có — như đăng nhập ở một tab khác của cùng trình duyệt: id session đổi,
     * token CSRF cũ hết hiệu lực. Không cập nhật {@link #csrfToken()} (trang đã mở vẫn giữ token cũ trong meta).
     */
    public void signInAgain(String username, String password) throws Exception {
        String pageToken = csrfToken;
        signIn(username, password);
        csrfToken = pageToken;
    }

    private void signIn(String username, String password) throws Exception {
        String formToken = extract(FORM_CSRF, get("/login").body());
        HttpResponse<String> login = post("/login", Map.of("username", username, "password", password,
                "_csrf", formToken));
        if (login.statusCode() != 302 || login.headers().firstValue("Location").orElse("").contains("error")) {
            throw new IllegalStateException("Login failed for " + username + ": " + login.statusCode());
        }
        refreshCsrfToken();
    }

    /** {@code GET /csrf-token} như {@code realtime.js} trước khi nối lại; trả token của session hiện tại. */
    public String fetchCsrfToken() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/csrf-token"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET /csrf-token: " + response.statusCode());
        }
        return extract(JSON_TOKEN, response.body());
    }

    /** Khách chưa đăng nhập (vẫn có session + token CSRF của trang /login). */
    public static BrowserSession anonymous(int port) throws Exception {
        BrowserSession browser = new BrowserSession(port);
        browser.refreshCsrfToken();
        return browser;
    }

    public void refreshCsrfToken() throws Exception {
        String page = get("/").body();
        csrfToken = extract(META_CSRF, page);
        csrfHeader = extract(META_CSRF_HEADER, page);
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> body.append(body.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** {@code POST /logout} như form đăng xuất của trang. */
    public void logout() throws IOException, InterruptedException {
        post("/logout", Map.of("_csrf", csrfToken));
    }

    public String csrfToken() {
        return csrfToken;
    }

    public String sessionCookie() {
        return cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .map(HttpCookie::toString)
                .findFirst().orElse("");
    }

    /** Kết nối STOMP với cookie session và token CSRF hiện tại. */
    public Stomp connect() {
        return connect(csrfToken, null);
    }

    /**
     * @param csrf   token đặt vào frame CONNECT ({@code null} = không gửi)
     * @param origin header {@code Origin} của handshake ({@code null} = không gửi, như client không phải trình duyệt)
     */
    public Stomp connect(String csrf, String origin) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add("Cookie", sessionCookie());
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (csrf != null) {
            connectHeaders.add(csrfHeader == null ? "X-CSRF-TOKEN" : csrfHeader, csrf);
        }
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        Stomp stomp = new Stomp(client);
        stomp.future = client.connectAsync(URI.create(baseUrl.replace("http", "ws") + "/ws-stomp"), handshake,
                connectHeaders, stomp.handler);
        return stomp;
    }

    private static String extract(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Pattern not found: " + pattern);
        }
        return matcher.group(1).replace("&#45;", "-");
    }

    /** Một kết nối STOMP của test: message nhận được và frame ERROR được đưa vào hàng đợi để chờ có timeout. */
    public static final class Stomp {

        private final WebSocketStompClient client;
        private final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private CompletableFuture<StompSession> future;

        private final StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(headers); // chỉ frame ERROR tới session handler
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                closed.complete(null);
            }
        };

        private Stomp(WebSocketStompClient client) {
            this.client = client;
        }

        /** @return session đã CONNECTED; ném lỗi nếu handshake/CONNECT bị từ chối */
        public StompSession session() throws Exception {
            return future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        /** Handshake hoặc CONNECT thất bại (401/403, ERROR frame). */
        public Throwable failure() {
            try {
                future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                return null;
            } catch (Exception ex) {
                return ex;
            }
        }

        public BlockingQueue<Map<String, Object>> subscribe(String destination) throws Exception {
            BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
            session().subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                @SuppressWarnings("unchecked")
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((Map<String, Object>) payload);
                }
            });
            return received;
        }

        /** Frame ERROR kế tiếp (null nếu không có trong thời gian chờ). */
        public StompHeaders nextError() throws InterruptedException {
            return errors.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        /** Chờ server đóng kết nối. */
        public boolean awaitClosed() {
            try {
                closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        public boolean isConnected() {
            return future.isDone() && !future.isCompletedExceptionally() && future.join().isConnected();
        }

        public void close() {
            if (future.isDone() && !future.isCompletedExceptionally() && future.join().isConnected()) {
                try {
                    future.join().disconnect();
                } catch (MessageDeliveryException alreadyClosed) {
                    // Server vừa đóng socket (SUBSCRIBE bị từ chối, session bị thu hồi...) giữa lúc kiểm tra isConnected()
                    // và lúc gửi DISCONNECT — dọn dẹp sau test không được làm test fail vì cuộc đua này
                }
            }
            client.stop();
        }
    }
}
