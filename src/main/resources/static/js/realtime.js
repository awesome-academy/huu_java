// Kết nối STOMP qua WebSocket dùng chung cho admin và storefront: window.Realtime.connect({...}).
// Cần /webjars/stomp__stompjs/bundles/stomp.umd.min.js (global StompJs) nạp trước. Không script inline (CSP).
//
// - Cùng origin: URL ws(s):// tính từ path do server render (data-ws-url, theo context path).
// - CSRF: frame CONNECT mang token đã mask; server so với token của session lúc handshake. Lần đầu đọc từ
//   <meta name="_csrf"> (vừa render). Mỗi lần nối lại hỏi csrfTokenUrl trước (redirect: "manual"), vì session có thể đã
//   đổi từ lúc render trang (hết hạn rồi remember-me đăng nhập lại, đăng nhập lại ở tab khác) → token trong meta cũ bị
//   từ chối mãi. Token mới được ghi lại vào meta cho các fetch() khác của trang.
// - Chỉ dừng hẳn khi BIẾT CHẮC nối lại vô ích, và luôn báo cho người dùng bằng một thông báo cố định "Tải lại trang":
//     * session hết hạn/bị thu hồi: csrfTokenUrl bị chuyển hướng tới /login (hoặc 401/403);
//     * server đóng 1008 "Too many connections" (quá số tab của user trên instance này);
//     * bị server từ chối (frame ERROR — SUBSCRIBE/CSRF sai — hoặc đóng 1008) quá MAX_REJECTIONS lần liên tiếp.
//   Mất kết nối vì mạng/instance tắt/rolling deploy/tab bị trình duyệt bóp timer: thử lại MÃI, thời gian chờ tăng gấp đôi
//   tới 60s + jitter 0–3s để client không dồn cùng lúc vào instance còn lại.
// - Tab hiện lại (visibilitychange) hoặc có mạng lại (online): đang chờ nối lại → nối ngay; đã dừng → thử lại một lần
//   (vd. đã đăng nhập lại ở tab khác, đã đóng bớt tab). Nối được thì ẩn thông báo.
// - Bộ đếm (backoff, số lần bị từ chối) chỉ về 0 khi kết nối giữ được STABLE_MS.
// - Resync: sau mỗi lần SUBSCRIBE — KỂ CẢ lần đầu — gọi onResync() → trang tải lại số liệu qua JSON, bù sự kiện lỡ trong
//   lúc mất kết nối, và ở lần đầu là sự kiện giữa lúc server render trang và lúc SUBSCRIBE. Frame SUBSCRIBE chỉ mới được
//   GỬI: server đăng ký nó bất đồng bộ (pool clientInboundChannel) và simple broker không trả RECEIPT, nên request resync
//   có thể đọc DB trước khi subscription có hiệu lực → gọi onResync() thêm một lần sau SETTLE_RESYNC_MS trên cùng kết
//   nối để bắt sự kiện rơi vào khoảng đó.
// - Trang vào bfcache (pagehide) thì ngắt; quay lại (pageshow persisted) thì nối lại + resync.
(function () {
    "use strict";

    const BASE_DELAY_MS = 1000;
    const MAX_DELAY_MS = 60000;
    const MAX_JITTER_MS = 3000;
    const HEARTBEAT_MS = 10000;
    const STABLE_MS = 10000;
    const SETTLE_RESYNC_MS = 1500;
    const MAX_REJECTIONS = 5;
    const POLICY_VIOLATION = 1008;
    // Lý do đóng của WebSocketSessionTracker.TOO_MANY_CONNECTIONS
    const TOO_MANY_CONNECTIONS_REASON = "Too many connections";

    // Lý do dừng có thông báo cho người dùng (dừng vì pagehide/stop() thì không)
    const NOTICES = {
        "session-expired": "Phiên đăng nhập đã hết hạn nên trang không còn tự cập nhật. Tải lại trang để đăng nhập lại.",
        "too-many-connections": "Bạn đang mở quá nhiều tab nên trang này không tự cập nhật. Đóng bớt tab rồi tải lại trang.",
        "rejected": "Trang không nhận được cập nhật tự động. Tải lại trang để thử lại."
    };

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
     * @param {function(boolean)} [options.onResync]  gọi sau mỗi lần SUBSCRIBE (ngay và sau SETTLE_RESYNC_MS; tham số:
     *                                           kết nối này có phải kết nối đầu tiên của trang không)
     * @param {string} [options.csrfTokenUrl]    URL JSON (cần đăng nhập) trả token CSRF của session hiện tại
     */
    function connect(options) {
        if (typeof StompJs === "undefined" || !options || !options.url) {
            return null;
        }
        let failures = 0;
        let rejections = 0;
        let rejectedThisConnection = false;
        let attempts = 0;
        let stopped = false;
        let stopReason = null;
        let connectedOnce = false;
        let reconnectTimer = null;
        let stableTimer = null;
        let settleTimer = null;
        let notice = null;

        function resync(initial) {
            if (typeof options.onResync === "function") {
                options.onResync(initial);
            }
        }

        const client = new StompJs.Client({
            brokerURL: brokerUrl(options.url),
            reconnectDelay: 0, // tự lên lịch nối lại (backoff + jitter) trong onWebSocketClose
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            beforeConnect: async function () {
                rejectedThisConnection = false;
                // Lần đầu: meta vừa được render cùng trang. Nối lại: session có thể đã đổi → lấy token mới
                if (attempts++ > 0 && options.csrfTokenUrl) {
                    const signedIn = await refreshCsrfToken(options.csrfTokenUrl);
                    if (!signedIn) {
                        stop("session-expired"); // client không còn active → stompjs bỏ qua lần kết nối này
                        return;
                    }
                }
                client.connectHeaders = csrfHeaders();
            },
            onConnect: function () {
                clearConnectionTimers();
                hideNotice();
                stableTimer = window.setTimeout(function () {
                    stableTimer = null;
                    failures = 0;
                    rejections = 0;
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
                resync(initial);
                // SUBSCRIBE chưa chắc đã có hiệu lực ở server lúc resync ở trên đọc DB
                settleTimer = window.setTimeout(function () {
                    settleTimer = null;
                    resync(initial);
                }, SETTLE_RESYNC_MS);
            },
            onStompError: function () {
                // Frame ERROR (bị từ chối, CSRF sai): server đóng kết nối ngay sau đó → onWebSocketClose xử lý tiếp
                rejectedThisConnection = true;
            },
            onWebSocketClose: function (event) {
                clearConnectionTimers();
                if (stopped) {
                    return;
                }
                const code = event ? event.code : undefined;
                if (code === POLICY_VIOLATION && event.reason === TOO_MANY_CONNECTIONS_REASON) {
                    stop("too-many-connections");
                    return;
                }
                if (rejectedThisConnection || code === POLICY_VIOLATION) {
                    // Bị server từ chối (khác lỗi mạng): có giới hạn. 1008 "Session revoked" vẫn thử lại — đăng nhập
                    // lại trong cùng trình duyệt đổi danh tính; hết phiên thì csrfTokenUrl phát hiện ở lần nối lại
                    rejections++;
                    if (rejections > MAX_REJECTIONS) {
                        stop("rejected");
                        return;
                    }
                }
                scheduleReconnect();
            }
        });

        function clearConnectionTimers() {
            if (stableTimer !== null) {
                window.clearTimeout(stableTimer);
                stableTimer = null;
            }
            if (settleTimer !== null) {
                window.clearTimeout(settleTimer);
                settleTimer = null;
            }
        }

        function scheduleReconnect() {
            if (stopped || reconnectTimer !== null) {
                return;
            }
            failures++;
            const backoff = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * Math.pow(2, Math.min(failures, 16) - 1));
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

        function cancelReconnect() {
            if (reconnectTimer !== null) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
        }

        /** @param {string} reason khoá trong NOTICES (có thông báo), "pagehide" hoặc "manual" */
        function stop(reason) {
            stopped = true;
            stopReason = reason;
            clearConnectionTimers();
            cancelReconnect();
            client.deactivate();
            if (NOTICES[reason]) {
                showNotice(NOTICES[reason]);
            }
        }

        /** Nối lại ngay với bộ đếm mới (tab hiện lại, có mạng lại, quay về từ bfcache). Thông báo chỉ ẩn khi nối được. */
        function resume() {
            stopped = false;
            stopReason = null;
            failures = 0;
            rejections = 0;
            cancelReconnect();
            restart();
        }

        function wake() {
            if (stopped ? stopReason === "pagehide" || stopReason === "manual" : reconnectTimer === null) {
                return; // đang kết nối/đã kết nối, hoặc chủ động dừng
            }
            resume();
        }

        function showNotice(text) {
            if (notice === null) {
                notice = document.createElement("div");
                notice.className = "alert alert-warning shadow position-fixed bottom-0 start-50 translate-middle-x "
                        + "mb-3 z-3 d-flex align-items-center gap-2";
                notice.setAttribute("role", "status");
                notice.dataset.realtimeNotice = "";
                const message = document.createElement("span");
                const reload = document.createElement("button");
                reload.type = "button";
                reload.className = "btn btn-sm btn-warning text-nowrap";
                reload.textContent = "Tải lại trang";
                reload.addEventListener("click", function () {
                    window.location.reload();
                });
                notice.append(message, reload);
                document.body.append(notice);
            }
            notice.children[0].textContent = text;
        }

        function hideNotice() {
            if (notice !== null) {
                notice.remove();
                notice = null;
            }
        }

        window.addEventListener("pagehide", function () {
            stop("pagehide");
        });
        window.addEventListener("pageshow", function (event) {
            if (event.persisted) {
                // Trang lấy từ bfcache: dữ liệu có thể đã cũ → lần SUBSCRIBE tới là resync (không phải lần đầu)
                resume();
            }
        });
        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                wake();
            }
        });
        window.addEventListener("online", wake);

        client.activate();
        return {
            stop: function () {
                stop("manual");
            }
        };
    }

    window.Realtime = {connect: connect};
})();
