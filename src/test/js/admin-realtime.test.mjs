// static/js/admin/realtime.js: badge "đơn chờ xác nhận" theo số đếm của server, toast đơn mới dựng bằng textContent,
// tải lại số đơn chờ sau mỗi lần SUBSCRIBE (kể cả lần đầu), phát sự kiện cho script của trang (dashboard.js).
import {mock, test} from "node:test";
import assert from "node:assert";
import {FakeDocument, FakeElement, flush, jsonResponse, load} from "./support/sandbox.mjs";

function setup({fetchImpl, withToasts = false} = {}) {
    const badge = new FakeElement({tag: "span", id: "pendingOrderBadge", text: "3"});
    const toastContainer = new FakeElement({id: "adminToastContainer"});
    const document = new FakeDocument([badge, toastContainer]);
    Object.assign(document.body.dataset, {
        wsUrl: "/ws-stomp", adminOrdersDestination: "/topic/admin/orders",
        pendingCountUrl: "/admin/orders/pending-count", ordersUrl: "/admin/orders", csrfTokenUrl: "/csrf-token"
    });
    let options = null;
    const shown = mock.fn();
    const fetch = mock.fn(fetchImpl || (() => Promise.resolve(jsonResponse({pendingCount: 5}))));
    load(["admin/realtime.js"], {
        document,
        Realtime: {connect: (opts) => { options = opts; }},
        fetch,
        ...(withToasts ? {bootstrap: {Toast: {getOrCreateInstance: () => ({show: shown})}}} : {})
    });
    return {options, document, badge, toastContainer, fetch, shown, push: (e) => options.subscriptions[0].onMessage(e)};
}

test("every event sets the badge from the server count and is re-dispatched for page scripts", () => {
    const {push, badge, document, options} = setup();
    assert.deepEqual(options.subscriptions.map((s) => s.destination), ["/topic/admin/orders"]);

    push({type: "ORDER_STATUS_CHANGED", orderId: 1, pendingCount: 0});

    assert.strictEqual(badge.textContent, "0");
    assert.strictEqual(badge.hidden, true);
    assert.deepEqual(document.dispatched.map((e) => e.type), ["admin:orders-changed"]);

    for (const invalid of [-1, "5", 1.5e400, null]) {
        push({type: "ORDER_STATUS_CHANGED", orderId: 1, pendingCount: invalid});
    }
    assert.strictEqual(badge.textContent, "0");
    push({orderId: 1, pendingCount: 9});      // thiếu type → bỏ
    assert.strictEqual(badge.textContent, "0");
});

test("first connect also reloads the pending count (orders placed while connecting)", async () => {
    const {options, fetch, badge, document} = setup();

    options.onResync(true);
    await flush();

    assert.strictEqual(fetch.mock.calls[0].arguments[0], "/admin/orders/pending-count");
    assert.strictEqual(badge.textContent, "5");
    assert.strictEqual(badge.hidden, false);
    const resync = document.dispatched.find((e) => e.type === "admin:realtime-resync");
    assert.strictEqual(resync.detail.initial, true);
});

test("failed pending-count request keeps the current badge", async () => {
    const {options, badge} = setup({fetchImpl: () => Promise.reject(new TypeError("offline"))});

    options.onResync(false);
    await flush();

    assert.strictEqual(badge.textContent, "3");
});

test("new order toast is built from text nodes and links to the order", () => {
    const {push, toastContainer, shown} = setup({withToasts: true});

    push({type: "ORDER_PLACED", orderId: 42, itemCount: 3, totalPrice: 120000, pendingCount: 4});

    assert.strictEqual(toastContainer.children.length, 1);
    const toast = toastContainer.children[0];
    const [header, body] = toast.children;
    assert.strictEqual(header.children[0].textContent, "Đơn mới #42");
    const [summary, link] = body.children;
    assert.match(summary.textContent, /^3 món · 120\.000/);
    assert.strictEqual(link.href, "/admin/orders/42");
    assert.strictEqual(shown.mock.callCount(), 1);
    assert.ok(!("innerHTML" in toast), "no HTML parsing");

    push({type: "ORDER_PLACED", orderId: "42<script>", pendingCount: 4});
    assert.strictEqual(toastContainer.children.length, 1, "orderId must be an integer");
});
