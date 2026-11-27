// static/js/web/order-status.js: badge trạng thái đơn của khách đổi theo sự kiện STOMP, toast đúng một lần mỗi trạng
// thái mới, class lạ bị loại, resync (kể cả lần đầu) qua GET /orders/statuses.
import {mock, test} from "node:test";
import assert from "node:assert";
import {FakeDocument, FakeElement, flush, jsonResponse, load} from "./support/sandbox.mjs";

function badge(orderId, status, badgeClass, label) {
    return new FakeElement({
        tag: "span",
        className: "badge " + badgeClass,
        text: label,
        dataset: {orderStatusBadge: String(orderId), status, badgeClass}
    });
}

function setup({badges, fetchImpl} = {}) {
    const root = new FakeElement({
        id: "orderRealtime",
        dataset: {wsUrl: "/ws-stomp", destination: "/user/queue/orders", statusesUrl: "/orders/statuses",
            csrfTokenUrl: "/csrf-token"}
    });
    const elements = badges || [
        badge(7, "PENDING", "bg-warning text-dark", "Chờ xác nhận"),
        badge(7, "PENDING", "bg-warning text-dark", "Chờ xác nhận"),   // cùng đơn hiện 2 chỗ
        badge(8, "COMPLETED", "bg-success", "Hoàn thành")];
    let options = null;
    const showToast = mock.fn();
    const fetch = mock.fn(fetchImpl || (() => Promise.resolve(jsonResponse([]))));
    const warn = mock.fn();
    load(["web/order-status.js"], {
        document: new FakeDocument([root, ...elements]),
        Realtime: {connect: (opts) => { options = opts; }},
        showToast,
        fetch,
        console: {warn}
    });
    return {
        options, showToast, fetch, warn, badges: elements,
        push: (event) => options.subscriptions[0].onMessage(event)
    };
}

const PROCESSING = {orderId: 7, status: "PROCESSING", statusLabel: "Đang xử lý", badgeClass: "bg-info text-dark"};

test("subscribes to the user's order queue through Realtime", () => {
    const {options} = setup();

    assert.strictEqual(options.url, "/ws-stomp");
    assert.strictEqual(options.csrfTokenUrl, "/csrf-token");
    assert.deepEqual(options.subscriptions.map((s) => s.destination), ["/user/queue/orders"]);
});

test("status event updates every badge of the order and toasts once per new status", () => {
    const {push, showToast, badges} = setup();

    push(PROCESSING);

    for (const element of badges.slice(0, 2)) {
        assert.strictEqual(element.textContent, "Đang xử lý");
        assert.strictEqual(element.dataset.status, "PROCESSING");
        assert.deepEqual(element.classList.toArray().sort(), ["badge", "bg-info", "text-dark"]);
    }
    assert.strictEqual(badges[2].textContent, "Hoàn thành", "other orders untouched");
    assert.strictEqual(showToast.mock.callCount(), 1);
    assert.deepEqual(showToast.mock.calls[0].arguments, ["Đơn #7: Đang xử lý", false]);

    // Server gửi trạng thái hiện tại lúc gửi → hai lần đổi liên tiếp có thể tới thành hai sự kiện cùng trạng thái
    push(PROCESSING);
    // Trạng thái đang hiện trên trang (render từ server) → không phải tin mới
    push({orderId: 8, status: "COMPLETED", statusLabel: "Hoàn thành", badgeClass: "bg-success"});
    assert.strictEqual(showToast.mock.callCount(), 1);
});

test("order not shown on the page: toast on a new status, not on a repeat", () => {
    const {push, showToast} = setup();
    const event = {orderId: 99, status: "CANCELLED", statusLabel: "Đã hủy", badgeClass: "bg-danger"};

    push(event);
    push(event);

    assert.strictEqual(showToast.mock.callCount(), 1);
});

test("untrusted class tokens are dropped and malformed events ignored", () => {
    const {push, showToast, badges} = setup();

    push({orderId: 7, status: "CANCELLED", statusLabel: "<img src=x onerror=alert(1)>",
        badgeClass: "bg-danger \"onmouseover=alert(1) <b>"});

    assert.deepEqual(badges[0].classList.toArray().sort(), ["badge", "bg-danger"]);
    assert.strictEqual(badges[0].textContent, "<img src=x onerror=alert(1)>", "label is text, never HTML");
    assert.strictEqual(badges[0].dataset.badgeClass, "bg-danger");

    for (const malformed of [null, "x", {orderId: "7", status: "PROCESSING", statusLabel: "x", badgeClass: "y"},
        {orderId: 7, status: 1, statusLabel: "x", badgeClass: "y"}, {orderId: 7.5, status: "A", statusLabel: "x",
            badgeClass: "y"}]) {
        push(malformed);
    }
    assert.strictEqual(badges[0].dataset.status, "CANCELLED");
    assert.strictEqual(showToast.mock.callCount(), 1);
});

test("resync (also on first connect) loads statuses of orders on the page, updates without toast", async () => {
    const {options, fetch, showToast, badges, push} = setup({
        fetchImpl: () => Promise.resolve(jsonResponse([
            {orderId: 7, status: "CANCELLED", statusLabel: "Đã hủy", badgeClass: "bg-danger"},
            {orderId: "8", status: "x"}]))
    });

    options.onResync(true);
    await flush();

    assert.strictEqual(fetch.mock.callCount(), 1);
    const [url, init] = fetch.mock.calls[0].arguments;
    assert.strictEqual(url, "https://shop.example/orders/statuses?ids=7&ids=8");
    assert.strictEqual(init.redirect, "error", "an expired session must not be parsed as the login page");
    assert.strictEqual(badges[0].textContent, "Đã hủy");
    assert.strictEqual(badges[2].textContent, "Hoàn thành");
    assert.strictEqual(showToast.mock.callCount(), 0);

    // Đã biết trạng thái mới từ resync → sự kiện tới muộn cùng trạng thái không toast
    push({orderId: 7, status: "CANCELLED", statusLabel: "Đã hủy", badgeClass: "bg-danger"});
    assert.strictEqual(showToast.mock.callCount(), 0);
});

test("resync asks for at most 20 distinct orders", async () => {
    const many = Array.from({length: 25}, (unused, i) => badge(i + 1, "PENDING", "bg-warning", "Chờ"));
    const {options, fetch} = setup({badges: many});

    options.onResync(false);
    await flush();

    const url = new URL(fetch.mock.calls[0].arguments[0]);
    assert.deepEqual(url.searchParams.getAll("ids"), Array.from({length: 20}, (unused, i) => String(i + 1)));
});

test("resync failure keeps the badges as they are", async () => {
    const {options, warn, badges} = setup({fetchImpl: () => Promise.resolve(jsonResponse(null, {status: 500}))});

    options.onResync(false);
    await flush();

    assert.strictEqual(badges[0].textContent, "Chờ xác nhận");
    assert.strictEqual(warn.mock.callCount(), 1);
});

test("page without orders shown: no status request", async () => {
    const {options, fetch} = setup({badges: []});

    options.onResync(true);
    await flush();

    assert.strictEqual(fetch.mock.callCount(), 0);
});
