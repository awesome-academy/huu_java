// static/js/admin/import-status.js: trang trạng thái import Excel polling JSON cho tới khi lần import kết thúc rồi tải lại
// trang; lần import đã kết thúc thì không polling; lỗi liên tiếp hoặc quá lâu thì dừng và hiện thông báo.
import {afterEach, beforeEach, mock, test} from "node:test";
import assert from "node:assert";
import {FakeDocument, FakeElement, flush, jsonResponse, load} from "./support/sandbox.mjs";

const POLL_MS = 2000;

beforeEach(() => mock.timers.enable({apis: ["setTimeout"]}));
afterEach(() => mock.timers.reset());

function job(status, overrides = {}) {
    const labels = {PENDING: "Đang chờ xử lý", PROCESSING: "Đang xử lý", COMPLETED: "Hoàn thành", FAILED: "Thất bại"};
    const classes = {PENDING: "bg-secondary", PROCESSING: "bg-info text-dark", COMPLETED: "bg-success",
        FAILED: "bg-danger"};
    return {
        id: 42, status, statusLabel: labels[status], badgeClass: classes[status],
        terminal: status === "COMPLETED" || status === "FAILED", errors: [], ...overrides
    };
}

function setup({terminal = "false", fetchImpl} = {}) {
    const root = new FakeElement({dataset: {importStatusUrl: "/admin/products/import/42/status", importTerminal: terminal}});
    const badge = new FakeElement({tag: "span", className: "badge bg-secondary", text: "Đang chờ xử lý",
        dataset: {importStatusBadge: ""}});
    const notice = new FakeElement({dataset: {importStatusNotice: ""}});
    notice.hidden = true;
    const progress = new FakeElement({tag: "span", dataset: {importStatusProgress: ""}});
    const reload = mock.fn();
    const fetch = mock.fn(fetchImpl);
    load(["admin/import-status.js"], {
        document: new FakeDocument([root, badge, notice, progress]),
        fetch,
        location: {reload}
    });
    return {badge, notice, progress, reload, fetch};
}

/** Chạy một chu kỳ polling: hết thời gian chờ rồi chờ fetch + các then() xong. */
async function nextPoll() {
    mock.timers.tick(POLL_MS);
    await flush();
}

test("finished import: the page is final, nothing is polled", async () => {
    const {fetch, reload} = setup({terminal: "true", fetchImpl: () => Promise.resolve(jsonResponse(job("COMPLETED")))});

    mock.timers.tick(10 * POLL_MS);
    await flush();

    assert.strictEqual(fetch.mock.callCount(), 0);
    assert.strictEqual(reload.mock.callCount(), 0);
});

test("unfinished import: polls the status URL every 2s and shows the new status label", async () => {
    const responses = [job("PENDING"), job("PROCESSING"), job("PROCESSING")];
    const {fetch, badge, reload, notice} = setup({fetchImpl: () => Promise.resolve(jsonResponse(responses.shift()))});

    mock.timers.tick(POLL_MS - 1);
    await flush();
    assert.strictEqual(fetch.mock.callCount(), 0, "first request only after the interval");

    await nextPoll();
    assert.strictEqual(fetch.mock.callCount(), 1);
    assert.strictEqual(fetch.mock.calls[0].arguments[0], "/admin/products/import/42/status");
    // Hết phiên → server chuyển hướng về /login: phải thành lỗi, không parse trang login như JSON
    assert.strictEqual(fetch.mock.calls[0].arguments[1].redirect, "error");
    assert.strictEqual(badge.textContent, "Đang chờ xử lý");

    await nextPoll();
    assert.strictEqual(badge.textContent, "Đang xử lý");
    assert.strictEqual(badge.className, "badge bg-info text-dark");
    assert.strictEqual(reload.mock.callCount(), 0);
    assert.strictEqual(notice.hidden, true);
});

for (const status of ["COMPLETED", "FAILED"]) {
    test(`import ${status}: reloads the page once so the server renders the result, then stops polling`, async () => {
        const responses = [job("PROCESSING"), job(status)];
        const {fetch, reload} = setup({fetchImpl: () => Promise.resolve(jsonResponse(responses.shift()))});

        await nextPoll();
        await nextPoll();
        assert.strictEqual(reload.mock.callCount(), 1);

        mock.timers.tick(10 * POLL_MS);
        await flush();
        assert.strictEqual(fetch.mock.callCount(), 2, "no more requests after the final status");
        assert.strictEqual(reload.mock.callCount(), 1);
    });
}

test("badge class from the server is restricted to plain class tokens", async () => {
    const {badge} = setup({
        fetchImpl: () => Promise.resolve(jsonResponse(job("PROCESSING", {badgeClass: "bg-info \"><script> text-dark"})))
    });

    await nextPoll();

    assert.strictEqual(badge.className, "badge bg-info text-dark");
});

test("a few failed requests are retried; the next good answer resets the failure count", async () => {
    const answers = [
        () => Promise.reject(new TypeError("network")),
        () => Promise.resolve(jsonResponse({}, {status: 503})),
        () => Promise.resolve(jsonResponse({unexpected: true})),
        () => Promise.resolve(jsonResponse(job("PROCESSING"))),
        () => Promise.reject(new TypeError("network")),
        () => Promise.reject(new TypeError("network")),
        () => Promise.reject(new TypeError("network")),
        () => Promise.reject(new TypeError("network")),
        () => Promise.resolve(jsonResponse(job("PROCESSING")))
    ];
    const {fetch, notice, badge} = setup({fetchImpl: () => answers.shift()()});

    for (let i = 0; i < 9; i++) {
        await nextPoll();
    }

    assert.strictEqual(fetch.mock.callCount(), 9);
    assert.strictEqual(notice.hidden, true, "never 5 failures in a row");
    assert.strictEqual(badge.textContent, "Đang xử lý");
});

test("5 failures in a row (session expired, server down): stops and asks to reload", async () => {
    const {fetch, notice, progress, reload} = setup({fetchImpl: () => Promise.reject(new TypeError("redirected"))});

    for (let i = 0; i < 8; i++) {
        await nextPoll();
    }

    assert.strictEqual(fetch.mock.callCount(), 5);
    assert.strictEqual(notice.hidden, false);
    assert.match(notice.textContent, /Tải lại trang/);
    assert.strictEqual(progress.hidden, true);
    assert.strictEqual(reload.mock.callCount(), 0);
});

test("import still unfinished after 10 minutes: stops polling so an idle tab does not keep the session alive", async () => {
    const {fetch, notice} = setup({fetchImpl: () => Promise.resolve(jsonResponse(job("PENDING")))});

    for (let i = 0; i < 320; i++) {
        await nextPoll();
    }

    assert.strictEqual(fetch.mock.callCount(), 300, "10 minutes / 2s");
    assert.strictEqual(notice.hidden, false);
    assert.match(notice.textContent, /ngừng tự cập nhật/);
});
