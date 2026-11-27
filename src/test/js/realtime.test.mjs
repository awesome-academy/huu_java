// static/js/realtime.js: kết nối STOMP dùng chung — CSRF ở CONNECT, nối lại có backoff (mãi mãi với lỗi mạng), làm mới
// token trước khi nối lại, chỉ dừng hẳn khi hết phiên/quá số tab/bị từ chối liên tiếp (kèm thông báo), resync sau mỗi lần
// SUBSCRIBE (kể cả lần đầu) và thêm một lần khi SUBSCRIBE đã có hiệu lực.
import {afterEach, beforeEach, mock, test} from "node:test";
import assert from "node:assert";
import {FakeDocument, FakeElement, fakeStompJs, fireWindowEvent, flush, jsonResponse, load} from "./support/sandbox.mjs";

beforeEach(() => mock.timers.enable({apis: ["setTimeout"]}));
afterEach(() => mock.timers.reset());

function setup({fetchImpl} = {}) {
    const csrf = new FakeElement({tag: "meta", attributes: {name: "_csrf", content: "token-from-page"}});
    const csrfHeader = new FakeElement({tag: "meta", attributes: {name: "_csrf_header", content: "X-CSRF-TOKEN"}});
    const {StompJs, clients} = fakeStompJs();
    const fetch = mock.fn(fetchImpl
            || (() => Promise.resolve(jsonResponse({token: "fresh-token", headerName: "X-CSRF-TOKEN"}))));
    const context = load(["realtime.js"], {document: new FakeDocument([csrf, csrfHeader]), StompJs, fetch});
    const messages = [];
    const resyncs = [];
    context.Realtime.connect({
        url: "/ws-stomp",
        csrfTokenUrl: "/csrf-token",
        subscriptions: [{destination: "/user/queue/orders", onMessage: (payload) => messages.push(payload)}],
        onResync: (initial) => resyncs.push(initial)
    });
    return {client: clients[0], csrf, fetch, messages, resyncs, context};
}

async function connect(client) {
    await client.beforeConnect();
    client.onConnect();
}

/** Mất kết nối rồi chờ đúng thời gian backoff → client được kích hoạt lại. */
async function dropAndWait(client, delayMs, closeEvent) {
    client.onWebSocketClose(closeEvent);
    mock.timers.tick(delayMs);
    await flush();
}

/** Thông báo "Tải lại trang" đang hiện (hoặc null). */
function notice(context) {
    return context.document.body.children.find((child) => child.dataset.realtimeNotice !== undefined) || null;
}

function showTab(context) {
    context.document.hidden = false;
    context.document.dispatchEvent({type: "visibilitychange"});
}

test("first connect: same-origin wss URL, CSRF token from the page, subscribes and resyncs", async () => {
    const {client, fetch, messages, resyncs} = setup();

    assert.strictEqual(client.brokerURL, "wss://shop.example/ws-stomp");
    assert.strictEqual(client.reconnectDelay, 0);
    assert.strictEqual(client.activations, 1);
    await connect(client);

    assert.deepEqual(client.connectHeaders, {"X-CSRF-TOKEN": "token-from-page"});
    assert.strictEqual(fetch.mock.callCount(), 0, "the token rendered with the page is fresh");
    assert.deepEqual(client.subscriptions.map((s) => s.destination), ["/user/queue/orders"]);
    // Kể cả lần đầu: sự kiện giữa lúc render trang và lúc SUBSCRIBE xong không ai gửi lại
    assert.deepEqual(resyncs, [true]);

    client.subscriptions[0].callback({body: "{\"orderId\":7}"});
    client.subscriptions[0].callback({body: "not json"});
    assert.deepEqual(messages, [{orderId: 7}]);
});

test("resyncs again once SUBSCRIBE has settled on the server, but not after the connection dropped", async () => {
    const {client, resyncs} = setup();
    await connect(client);

    mock.timers.tick(1499);
    assert.deepEqual(resyncs, [true]);
    mock.timers.tick(1);
    // Sự kiện phát giữa lần resync đầu và lúc server đăng ký SUBSCRIBE được bắt bởi lần này
    assert.deepEqual(resyncs, [true, true]);

    await dropAndWait(client, 1000);
    await connect(client);
    client.onWebSocketClose();
    mock.timers.tick(5000);
    await flush();
    assert.deepEqual(resyncs, [true, true, false], "a dropped connection does not resync again");
});

test("reconnect: waits the backoff, fetches a fresh CSRF token first, resyncs as non-initial", async () => {
    const {client, csrf, fetch, resyncs} = setup();
    await connect(client);

    client.onWebSocketClose();
    mock.timers.tick(999);
    await flush();
    assert.strictEqual(client.activations, 1);
    mock.timers.tick(1);
    await flush();
    assert.strictEqual(client.activations, 2);

    await connect(client);
    assert.strictEqual(fetch.mock.callCount(), 1);
    const [url, options] = fetch.mock.calls[0].arguments;
    assert.strictEqual(url, "/csrf-token");
    assert.strictEqual(options.redirect, "manual");
    assert.strictEqual(csrf.content, "fresh-token");
    assert.deepEqual(client.connectHeaders, {"X-CSRF-TOKEN": "fresh-token"});
    assert.deepEqual(resyncs, [true, false]);
});

for (const [name, response] of [
    ["redirected to /login", jsonResponse(null, {status: 0, type: "opaqueredirect"})],
    ["401", jsonResponse(null, {status: 401})],
    ["403", jsonResponse(null, {status: 403})]]) {
    test(`session gone (${name}) while reconnecting: stops for good and tells the user to reload`, async () => {
        const {client, context} = setup({fetchImpl: () => Promise.resolve(response)});
        await connect(client);
        await dropAndWait(client, 1000);

        await client.beforeConnect();
        assert.strictEqual(client.active, false);
        assert.match(notice(context).children[0].textContent, /hết hạn/);

        client.onWebSocketClose();
        mock.timers.tick(120000);
        await flush();
        assert.strictEqual(client.activations, 2);
    });
}

test("network error on the token endpoint does not stop reconnecting", async () => {
    const {client} = setup({fetchImpl: () => Promise.reject(new TypeError("Failed to fetch"))});
    await connect(client);
    await dropAndWait(client, 1000);

    await client.beforeConnect();

    assert.strictEqual(client.active, true);
    assert.deepEqual(client.connectHeaders, {"X-CSRF-TOKEN": "token-from-page"});
});

test("network failures: backoff doubles up to 60s and never gives up", async () => {
    // Tab bị trình duyệt bóp timer, mạng/deploy chập chờn lâu: thử lại mãi ở mức 60s, không dừng im lặng
    const {client, context} = setup();
    let activations = 1;
    for (let failure = 1; failure <= 15; failure++) {
        const delay = Math.min(60000, 1000 * 2 ** (failure - 1));
        client.onWebSocketClose({code: 1006});
        mock.timers.tick(delay - 1);
        await flush();
        assert.strictEqual(client.activations, activations, `failure ${failure}: not before ${delay}ms`);
        mock.timers.tick(1);
        await flush();
        assert.strictEqual(client.activations, ++activations, `failure ${failure}: reconnect after ${delay}ms`);
    }
    assert.strictEqual(client.active, true);
    assert.strictEqual(notice(context), null);
});

test("only a connection kept for 10s resets the failure counter", async () => {
    const {client} = setup();
    await dropAndWait(client, 1000);            // lỗi 1
    // CONNECTED rồi đóng ngay → vẫn là lỗi (lỗi 2: chờ 2s)
    await connect(client);
    await dropAndWait(client, 2000);
    assert.strictEqual(client.activations, 3);

    await connect(client);
    mock.timers.tick(10000);                    // giữ được 10s → về 0
    client.onWebSocketClose();
    mock.timers.tick(1000);
    await flush();
    assert.strictEqual(client.activations, 4);
});

test("server rejections (STOMP ERROR) stop after 5 in a row, with a notice", async () => {
    const {client, context} = setup();
    await connect(client);
    for (let rejection = 1; rejection <= 5; rejection++) {
        client.onStompError({headers: {message: "Access denied"}});
        await dropAndWait(client, 1000 * 2 ** (rejection - 1), {code: 1002});
        assert.strictEqual(client.activations, rejection + 1, `rejection ${rejection} is retried`);
        await connect(client);
    }

    client.onStompError({headers: {message: "Access denied"}});
    client.onWebSocketClose({code: 1002});
    mock.timers.tick(120000);
    await flush();

    assert.strictEqual(client.activations, 6);
    assert.strictEqual(client.active, false);
    assert.match(notice(context).children[0].textContent, /Tải lại trang/);
});

test("1008 'Too many connections' stops at once with a notice; showing the tab again retries", async () => {
    const {client, context} = setup();
    await connect(client);

    client.onWebSocketClose({code: 1008, reason: "Too many connections"});
    mock.timers.tick(120000);
    await flush();
    assert.strictEqual(client.activations, 1, "no handshake loop against the per-user limit");
    assert.match(notice(context).children[0].textContent, /quá nhiều tab/);

    // Người dùng đã đóng bớt tab rồi quay lại tab này
    showTab(context);
    await flush();
    assert.strictEqual(client.activations, 2);
    await connect(client);
    assert.strictEqual(notice(context), null, "notice disappears once connected");
});

test("1008 'Session revoked' is retried: re-login in the same browser keeps a valid session", async () => {
    const {client} = setup();
    await connect(client);

    await dropAndWait(client, 1000, {code: 1008, reason: "Session revoked"});

    assert.strictEqual(client.activations, 2);
});

test("tab shown again while waiting to reconnect: reconnects now with a fresh backoff", async () => {
    const {client, context} = setup();
    await connect(client);
    await dropAndWait(client, 1000);
    await dropAndWait(client, 2000);
    client.onWebSocketClose();                  // đang chờ 4s
    assert.strictEqual(client.activations, 3);

    context.document.hidden = true;
    context.document.dispatchEvent({type: "visibilitychange"});
    await flush();
    assert.strictEqual(client.activations, 3, "hiding the tab does nothing");

    showTab(context);
    await flush();
    assert.strictEqual(client.activations, 4);

    await dropAndWait(client, 1000);
    assert.strictEqual(client.activations, 5, "backoff starts over");
    showTab(context);
    await flush();
    assert.strictEqual(client.activations, 5, "nothing to do while connecting");
});

test("back online after the session expired: retries once, logged in again → reconnects and hides the notice",
        async () => {
    let signedIn = false;
    const {client, context} = setup({
        fetchImpl: () => Promise.resolve(signedIn
                ? jsonResponse({token: "fresh-token", headerName: "X-CSRF-TOKEN"})
                : jsonResponse(null, {status: 0, type: "opaqueredirect"}))
    });
    await connect(client);
    await dropAndWait(client, 1000);
    await client.beforeConnect();
    assert.notStrictEqual(notice(context), null);

    signedIn = true;                            // đăng nhập lại ở tab khác
    fireWindowEvent(context, "online");
    await flush();
    assert.strictEqual(client.activations, 3);
    await connect(client);

    assert.strictEqual(client.active, true);
    assert.strictEqual(notice(context), null);
});

test("notice button reloads the page", async () => {
    const {client, context} = setup();
    context.location.reload = mock.fn();
    await connect(client);
    client.onWebSocketClose({code: 1008, reason: "Too many connections"});

    notice(context).children[1].click();

    assert.strictEqual(context.location.reload.mock.callCount(), 1);
});

test("pagehide stops without a notice; pageshow from bfcache reconnects and resyncs as non-initial", async () => {
    const {client, context, resyncs} = setup();
    await connect(client);

    fireWindowEvent(context, "pagehide");
    assert.strictEqual(client.active, false);
    assert.strictEqual(notice(context), null);
    client.onWebSocketClose();
    mock.timers.tick(60000);
    await flush();
    assert.strictEqual(client.activations, 1);
    showTab(context);
    await flush();
    assert.strictEqual(client.activations, 1, "a page in bfcache stays disconnected");

    fireWindowEvent(context, "pageshow", {persisted: true});
    await flush();
    assert.strictEqual(client.activations, 2);
    await connect(client);
    assert.deepEqual(resyncs, [true, false]);
});
