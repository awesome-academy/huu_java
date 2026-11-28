// static/js/admin/dashboard.js: KPI "Đơn đang xử lý" cập nhật từ activeOrders của JSON biểu đồ, lúc tải trang và mỗi
// sự kiện đơn hàng realtime; payload sai → giữ số cũ, hiện thông báo lỗi. Chart.js được thay bằng lớp giả.
import {afterEach, beforeEach, mock, test} from "node:test";
import assert from "node:assert";
import {FakeDocument, FakeElement, flush, jsonResponse, load} from "./support/sandbox.mjs";

beforeEach(() => mock.timers.enable({apis: ["setTimeout"]}));
afterEach(() => mock.timers.reset());

function payload(activeOrders) {
    return {
        monthLabels: ["Th11/2026"], monthRevenues: [150000],
        statusLabels: ["Chờ xác nhận", "Đang xử lý"], statusCounts: [2, 3],
        topProductLabels: [], topProductQuantities: [],
        activeOrders
    };
}

function setup(responses) {
    const kpi = new FakeElement({dataset: {dashboardActiveOrders: ""}, text: "3"});
    const errorBox = new FakeElement({id: "dashboardChartsError"});
    errorBox.hidden = true;
    const document = new FakeDocument([
        new FakeElement({id: "dashboardCharts", dataset: {chartsUrl: "/admin/dashboard/charts"}}),
        new FakeElement({id: "revenueChart"}),
        new FakeElement({id: "statusChart"}),
        new FakeElement({id: "topProductsChart"}),
        kpi, errorBox]);
    const charts = [];

    class Chart {
        constructor(canvas, config) {
            this.canvas = canvas;
            this.data = config.data;
            this.updates = 0;
            charts.push(this);
        }

        update() {
            this.updates++;
        }
    }

    const fetch = mock.fn(() => Promise.resolve(jsonResponse(responses.shift())));
    load(["admin/dashboard.js"], {document, Chart, fetch, console: {warn: () => {}}});
    return {kpi, errorBox, document, fetch, charts};
}

test("page load: KPI shows activeOrders from the chart JSON", async () => {
    const {kpi, fetch, charts} = setup([payload(4)]);
    await flush();

    assert.strictEqual(fetch.mock.callCount(), 1);
    assert.strictEqual(fetch.mock.calls[0].arguments[0], "/admin/dashboard/charts");
    assert.strictEqual(kpi.textContent, "4");
    assert.strictEqual(charts.length, 2, "revenue + status charts; no top products yet");
});

test("order event: reloads after the debounce and updates the KPI with the server count", async () => {
    const {kpi, document, fetch} = setup([payload(4), payload(5)]);
    await flush();

    document.dispatchEvent({type: "admin:orders-changed", detail: {type: "ORDER_PLACED", pendingCount: 3}});
    mock.timers.tick(999);
    await flush();
    assert.strictEqual(fetch.mock.callCount(), 1);
    mock.timers.tick(1);
    await flush();

    assert.strictEqual(fetch.mock.callCount(), 2);
    assert.strictEqual(kpi.textContent, "5");
});

// Instance cũ (rolling deploy) chưa có activeOrders, hoặc giá trị lạ: biểu đồ vẫn vẽ, KPI giữ số đang hiện
for (const [name, value] of [["missing", undefined], ["a string", "7"], ["negative", -1], ["fractional", 1.5]]) {
    test(`activeOrders ${name}: charts are still updated, the KPI keeps its value, no error box`, async () => {
        const {kpi, errorBox, document, charts} = setup([payload(4), payload(value)]);
        await flush();

        document.dispatchEvent({type: "admin:realtime-resync", detail: {initial: false}});
        mock.timers.tick(1000);
        await flush();

        assert.strictEqual(kpi.textContent, "4");
        assert.strictEqual(errorBox.hidden, true);
        assert.deepStrictEqual(charts.map(chart => chart.updates), [1, 1]);
    });
}

test("first payload without activeOrders: charts are drawn and the server-rendered KPI stays", async () => {
    const {kpi, errorBox, charts} = setup([payload(undefined)]);
    await flush();

    assert.strictEqual(kpi.textContent, "3");
    assert.strictEqual(errorBox.hidden, true);
    assert.strictEqual(charts.length, 2);
});

test("payload without chart arrays: keeps the previous charts and shows the error box", async () => {
    const {kpi, errorBox, document, charts} = setup([payload(4), {activeOrders: 9}]);
    await flush();

    document.dispatchEvent({type: "admin:realtime-resync", detail: {initial: false}});
    mock.timers.tick(1000);
    await flush();

    assert.strictEqual(kpi.textContent, "4");
    assert.strictEqual(errorBox.hidden, false);
    assert.deepStrictEqual(charts.map(chart => chart.updates), [0, 0]);
});
