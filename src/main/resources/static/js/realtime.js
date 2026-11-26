// Kết nối STOMP qua WebSocket dùng chung cho admin và storefront: window.Realtime.connect({...}).
// Cần /webjars/stomp__stompjs/bundles/stomp.umd.min.js (global StompJs) nạp trước. Không script inline (CSP).
//
// - Cùng origin: URL ws(s):// tính từ path do server render (data-ws-url, theo context path).
// - CSRF: frame CONNECT mang token đã mask; server so với token của session lúc handshake. Lần đầu đọc từ
//   <meta name="_csrf"> (vừa render). Mỗi lần nối lại hỏi csrfTokenUrl trước (redirect: "manual"), vì session có thể đã
//   đổi từ lúc render trang (hết hạn rồi remember-me đăng nhập lại, đăng nhập lại ở tab khác) → token trong meta cũ bị
//   từ chối mãi. Token mới được ghi lại vào meta cho các fetch() khác của trang.
// - Session hết hạn/bị thu hồi: csrfTokenUrl bị chuyển hướng tới /login (hoặc 401/403) → dừng hẳn, không thử nữa.
// - Mất kết nối (instance tắt/rolling deploy, mạng): tự nối lại, thời gian chờ tăng gấp đôi tới 60s + jitter ngẫu nhiên
//   0–3s để client không dồn cùng lúc vào instance còn lại. Sau mỗi lần SUBSCRIBE xong gọi onResync() → trang tải lại số
//   liệu qua JSON, bù sự kiện lỡ trong lúc mất kết nối.
// - Bộ đếm lỗi chỉ về 0 khi kết nối giữ được STABLE_MS: SUBSCRIBE bị từ chối (ERROR rồi đóng ngay sau CONNECTED) vẫn
//   tính là lỗi → dừng hẳn sau MAX_FAILURES lần liên tiếp, không lặp vô hạn.
// - Trang vào bfcache (pagehide) thì ngắt; quay lại (pageshow persisted) thì nối lại + resync.
(function () {
    "use strict";

    const BASE_DELAY_MS = 1000;
    const MAX_DELAY_MS = 60000;
    const MAX_JITTER_MS = 3000;
    const HEARTBEAT_MS = 10000;
    const STABLE_MS = 10000;
    const MAX_FAILURES = 10;

    function csrfMeta() {
        return {
            token: document.querySelector('meta[name="_csrf"]'),
            header: document.querySelector('meta[name="_csrf_header"]')
        };
    }

    function csrfHeaders() {
        const meta = csrfMeta();
        if (!meta.token || !meta.header || !meta.token.content || !meta.header.content) {
            return {};
        }
        const headers = {};
        headers[meta.header.content] = meta.token.content;
        return headers;
    }

    function brokerUrl(path) {
        const url = new URL(path, window.location.href);
        url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
        return url.href;
    }

    /**
     * Lấy token CSRF của session hiện tại và ghi vào meta.
     * @returns {Promise<boolean>} false nếu không còn đăng nhập (bị chuyển hướng/401/403); lỗi mạng/lỗi khác → true
     *          (không kết luận được, thử kết nối với token đang có)
     */
    function refreshCsrfToken(csrfTokenUrl) {
        return fetch(csrfTokenUrl, {
            method: "GET",
            credentials: "same-origin",
            redirect: "manual",
            cache: "no-store",
            headers: {"Accept": "application/json"}
        }).then(function (response) {
            if (response.type === "opaqueredirect" || response.status === 401 || response.status === 403) {
                return false;
            }
            if (!response.ok) {
                return true;
            }
            return response.json().then(function (data) {
                const meta = csrfMeta();
                if (data && typeof data.token === "string" && typeof data.headerName === "string"
                        && meta.token && meta.header) {
                    meta.token.content = data.token;
                    meta.header.content = data.headerName;
                }
                return true;
            });
        }).catch(function () {
            return true;
        });
    }

    /**
     * @param {Object} options
     * @param {string} options.url               path endpoint (vd. /ws-stomp)
     * @param {Array<{destination: string, onMessage: function(Object)}>} options.subscriptions
     * @param {function(boolean)} [options.onResync]  gọi sau mỗi lần SUBSCRIBE xong (tham số: lần đầu của trang hay không)
     * @param {string} [options.csrfTokenUrl]    URL JSON (cần đăng nhập) trả token CSRF của session hiện tại
     */
    function connect(options) {
        if (typeof StompJs === "undefined" || !options || !options.url) {
            return null;
        }
        let failures = 0;
        let attempts = 0;
        let stopped = false;
        let connectedOnce = false;
        let reconnectTimer = null;
        let stableTimer = null;

        const client = new StompJs.Client({
            brokerURL: brokerUrl(options.url),
            reconnectDelay: 0, // tự lên lịch nối lại (backoff + jitter) trong onWebSocketClose
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            beforeConnect: async function () {
                // Lần đầu: meta vừa được render cùng trang. Nối lại: session có thể đã đổi → lấy token mới
                if (attempts++ > 0 && options.csrfTokenUrl) {
                    const signedIn = await refreshCsrfToken(options.csrfTokenUrl);
                    if (!signedIn) {
                        stop(); // client không còn active → stompjs bỏ qua lần kết nối này
                        return;
                    }
                }
                client.connectHeaders = csrfHeaders();
            },
            onConnect: function () {
                clearStableTimer();
                stableTimer = window.setTimeout(function () {
                    stableTimer = null;
                    failures = 0;
                }, STABLE_MS);
                (options.subscriptions || []).forEach(function (subscription) {
                    client.subscribe(subscription.destination, function (frame) {
                        let payload;
                        try {
                            payload = JSON.parse(frame.body);
                        } catch (e) {
                            return;
                        }
                        subscription.onMessage(payload);
                    });
                });
                const initial = !connectedOnce;
                connectedOnce = true;
                if (typeof options.onResync === "function") {
                    options.onResync(initial);
                }
            },
            onStompError: function () {
                // Frame ERROR (bị từ chối, CSRF sai): server đóng kết nối ngay sau đó → onWebSocketClose xử lý tiếp
            },
            onWebSocketClose: function () {
                clearStableTimer();
                scheduleReconnect();
            }
        });

        function clearStableTimer() {
            if (stableTimer !== null) {
                window.clearTimeout(stableTimer);
                stableTimer = null;
            }
        }

        function scheduleReconnect() {
            if (stopped || reconnectTimer !== null) {
                return;
            }
            failures++;
            if (failures > MAX_FAILURES) {
                stop();
                return;
            }
            const backoff = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * Math.pow(2, failures - 1));
            const delay = backoff + Math.floor(Math.random() * MAX_JITTER_MS);
            reconnectTimer = window.setTimeout(function () {
                reconnectTimer = null;
                restart();
            }, delay);
        }

        function restart() {
            if (stopped) {
                return;
            }
            client.deactivate().then(function () {
                if (!stopped) {
                    client.activate();
                }
            });
        }

        function stop() {
            stopped = true;
            clearStableTimer();
            if (reconnectTimer !== null) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            client.deactivate();
        }

        window.addEventListener("pagehide", function () {
            stop();
        });
        window.addEventListener("pageshow", function (event) {
            if (event.persisted) {
                // Trang lấy từ bfcache: dữ liệu có thể đã cũ → lần SUBSCRIBE tới là resync (không phải lần đầu)
                stopped = false;
                failures = 0;
                restart();
            }
        });

        client.activate();
        return {stop: stop};
    }

    window.Realtime = {connect: connect};
})();
