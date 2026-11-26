// Kết nối STOMP qua WebSocket dùng chung cho admin và storefront: window.Realtime.connect({...}).
// Cần /webjars/stomp__stompjs/bundles/stomp.umd.min.js (global StompJs) nạp trước. Không script inline (CSP).
//
// - Cùng origin: URL ws(s):// tính từ path do server render (data-ws-url, theo context path).
// - CSRF: frame CONNECT mang token đã mask đọc từ <meta name="_csrf"> (server so với token trong session lúc handshake).
// - Mất kết nối (instance tắt/rolling deploy, mạng): tự nối lại, thời gian chờ tăng gấp đôi tới 60s + jitter ngẫu nhiên
//   0–3s để client không dồn cùng lúc vào instance còn lại. Sau mỗi lần SUBSCRIBE xong gọi onResync() → trang tải lại số
//   liệu qua JSON, bù sự kiện lỡ trong lúc mất kết nối.
// - Session hết hạn/bị thu hồi: handshake bị chuyển hướng tới /login, không upgrade được (JS chỉ thấy socket đóng, không
//   biết mã lỗi). Sau 3 lần liên tiếp không CONNECT được, hỏi
//   sessionCheckUrl (redirect: "manual"): bị chuyển về trang đăng nhập/401/403 → dừng hẳn; còn đăng nhập → thử tiếp.
//   Dừng hẳn sau MAX_FAILURES lần liên tiếp (vd. bị từ chối SUBSCRIBE mãi).
// - Trang vào bfcache (pagehide) thì ngắt; quay lại (pageshow persisted) thì nối lại + resync.
(function () {
    "use strict";

    const BASE_DELAY_MS = 1000;
    const MAX_DELAY_MS = 60000;
    const MAX_JITTER_MS = 3000;
    const HEARTBEAT_MS = 10000;
    const SESSION_CHECK_AFTER = 3;
    const MAX_FAILURES = 10;

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !header || !token.content || !header.content) {
            return {};
        }
        const headers = {};
        headers[header.content] = token.content;
        return headers;
    }

    function brokerUrl(path) {
        const url = new URL(path, window.location.href);
        url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
        return url.href;
    }

    /** @returns {Promise<boolean>} true nếu session vẫn còn đăng nhập */
    function sessionAlive(sessionCheckUrl) {
        if (!sessionCheckUrl) {
            return Promise.resolve(true);
        }
        return fetch(sessionCheckUrl, {
            method: "GET",
            credentials: "same-origin",
            redirect: "manual",
            headers: {"Accept": "application/json"}
        }).then(function (response) {
            return response.type !== "opaqueredirect" && response.status !== 401 && response.status !== 403;
        }).catch(function () {
            return true; // lỗi mạng: không kết luận được, cứ thử kết nối lại
        });
    }

    /**
     * @param {Object} options
     * @param {string} options.url               path endpoint (vd. /ws-stomp)
     * @param {Array<{destination: string, onMessage: function(Object)}>} options.subscriptions
     * @param {function(boolean)} [options.onResync]  gọi sau mỗi lần SUBSCRIBE xong (tham số: lần đầu của trang hay không)
     * @param {string} [options.sessionCheckUrl] URL JSON cần đăng nhập, dùng để biết session còn sống
     */
    function connect(options) {
        if (typeof StompJs === "undefined" || !options || !options.url) {
            return null;
        }
        let failures = 0;
        let stopped = false;
        let connectedOnce = false;
        let reconnectTimer = null;

        const client = new StompJs.Client({
            brokerURL: brokerUrl(options.url),
            reconnectDelay: 0, // tự lên lịch nối lại (backoff + jitter) trong onWebSocketClose
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            beforeConnect: function () {
                client.connectHeaders = csrfHeaders();
            },
            onConnect: function () {
                failures = 0;
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
                scheduleReconnect();
            }
        });

        function scheduleReconnect() {
            if (stopped || reconnectTimer !== null) {
                return;
            }
            failures++;
            if (failures > MAX_FAILURES) {
                stop();
                return;
            }
            const check = failures >= SESSION_CHECK_AFTER
                    ? sessionAlive(options.sessionCheckUrl) : Promise.resolve(true);
            check.then(function (alive) {
                if (!alive) {
                    stop();
                    return;
                }
                const backoff = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * Math.pow(2, failures - 1));
                const delay = backoff + Math.floor(Math.random() * MAX_JITTER_MS);
                reconnectTimer = window.setTimeout(function () {
                    reconnectTimer = null;
                    restart();
                }, delay);
            });
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
