// static/js/realtime.js: kết nối STOMP dùng chung — CSRF ở CONNECT, nối lại có backoff, làm mới token trước khi nối lại,
// dừng hẳn khi hết phiên đăng nhập hoặc lỗi liên tiếp, resync sau mỗi lần SUBSCRIBE (kể cả lần đầu).
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
async function dropAndWait(client, delayMs) {
    client.onWebSocketClose();
    mock.timers.tick(delayMs);
    await flush();
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
    test(`session gone (${name}) while reconnecting: stops for good`, async () => {
        const {client} = setup({fetchImpl: () => Promise.resolve(response)});
        await connect(client);
        await dropAndWait(client, 1000);

        await client.beforeConnect();
        assert.strictEqual(client.active, false);

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

test("backoff doubles up to 60s and stops after 10 consecutive failures", async () => {
    const {client} = setup();
    let activations = 1;
    for (let failure = 1; failure <= 10; failure++) {
        const delay = Math.min(60000, 1000 * 2 ** (failure - 1));
        client.onWebSocketClose();
        mock.timers.tick(delay - 1);
        await flush();
        assert.strictEqual(client.activations, activations, `failure ${failure}: not before ${delay}ms`);
        mock.timers.tick(1);
        await flush();
        assert.strictEqual(client.activations, ++activations, `failure ${failure}: reconnect after ${delay}ms`);
    }

    client.onWebSocketClose();
    mock.timers.tick(120000);
    await flush();
    assert.strictEqual(client.activations, 11);
    assert.strictEqual(client.active, false);
});

test("only a connection kept for 10s resets the failure counter", async () => {
    const {client} = setup();
    await dropAndWait(client, 1000);            // lỗi 1
    // SUBSCRIBE bị từ chối: CONNECTED rồi đóng ngay → vẫn là lỗi (lỗi 2: chờ 2s)
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

test("pagehide stops; pageshow from bfcache reconnects and resyncs as non-initial", async () => {
    const {client, context, resyncs} = setup();
    await connect(client);

    fireWindowEvent(context, "pagehide");
    assert.strictEqual(client.active, false);
    client.onWebSocketClose();
    mock.timers.tick(60000);
    await flush();
    assert.strictEqual(client.activations, 1);

    fireWindowEvent(context, "pageshow", {persisted: true});
    await flush();
    assert.strictEqual(client.activations, 2);
    await connect(client);
    assert.deepEqual(resyncs, [true, false]);
});
