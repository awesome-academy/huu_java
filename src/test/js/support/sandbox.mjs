// Môi trường trình duyệt tối thiểu để nạp script trong src/main/resources/static/js bằng node:vm — không jsdom, không npm.
// Chỉ có đúng những API các script đang dùng; script dùng API mới thì bổ sung ở đây.
import vm from "node:vm";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";

const STATIC_JS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../main/resources/static/js");

/** data-order-status-badge → orderStatusBadge (như HTMLElement.dataset). */
function datasetKey(attribute) {
    return attribute.replace(/^data-/, "").replace(/-([a-z])/g, (match, letter) => letter.toUpperCase());
}

export class FakeElement {
    constructor({tag = "div", id = null, dataset = {}, className = "", text = "", attributes = {}} = {}) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.dataset = {...dataset};
        this.textContent = text;
        this.attributes = {...attributes};
        this.hidden = false;
        this.children = [];
        this.listeners = {};
        const classes = new Set(className.split(/\s+/).filter(Boolean));
        this.classList = {
            add: (...tokens) => tokens.forEach((token) => classes.add(token)),
            remove: (...tokens) => tokens.forEach((token) => classes.delete(token)),
            contains: (token) => classes.has(token),
            toArray: () => [...classes]
        };
    }

    get className() {
        return this.classList.toArray().join(" ");
    }

    set className(value) {
        this.classList.remove(...this.classList.toArray());
        this.classList.add(...String(value).split(/\s+/).filter(Boolean));
    }

    get content() {
        return this.attributes.content;
    }

    set content(value) {
        this.attributes.content = value;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    append(...nodes) {
        nodes.forEach((node) => {
            node.parentNode = this;
        });
        this.children.push(...nodes);
    }

    remove() {
        if (this.parentNode) {
            this.parentNode.children = this.parentNode.children.filter((child) => child !== this);
            this.parentNode = null;
        }
    }

    /** Như HTMLElement.click(): gọi listener "click". */
    click() {
        (this.listeners.click || []).forEach((listener) => listener({type: "click", target: this}));
    }

    addEventListener(type, listener) {
        (this.listeners[type] ||= []).push(listener);
    }

    /** Hỗ trợ: [data-x], [data-x="v"], tag[name="v"]. */
    matches(selector) {
        const match = /^([a-z]*)\[([a-z-]+)(?:="([^"]*)")?\]$/.exec(selector);
        if (!match) {
            throw new Error("Selector not supported by the sandbox: " + selector);
        }
        const [, tag, attribute, value] = match;
        if (tag && this.tagName !== tag.toUpperCase()) {
            return false;
        }
        const actual = attribute.startsWith("data-") ? this.dataset[datasetKey(attribute)] : this.attributes[attribute];
        return value === undefined ? actual !== undefined : actual === value;
    }
}

export class FakeDocument {
    constructor(elements = []) {
        this.elements = elements;
        this.body = new FakeElement({tag: "body"});
        this.hidden = false;
        this.listeners = {};
        this.dispatched = [];
    }

    getElementById(id) {
        return this.elements.find((element) => element.id === id) || null;
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    querySelectorAll(selector) {
        return this.elements.filter((element) => element.matches(selector));
    }

    createElement(tag) {
        return new FakeElement({tag});
    }

    addEventListener(type, listener) {
        (this.listeners[type] ||= []).push(listener);
    }

    dispatchEvent(event) {
        this.dispatched.push(event);
        (this.listeners[event.type] || []).forEach((listener) => listener(event));
        return true;
    }
}

/** StompJs.Client giả: lưu cấu hình; test tự gọi beforeConnect/onConnect/onWebSocketClose như thư viện thật. */
export function fakeStompJs() {
    const clients = [];

    class Client {
        constructor(config) {
            Object.assign(this, config);
            this.active = false;
            this.activations = 0;
            this.subscriptions = [];
            clients.push(this);
        }

        activate() {
            this.active = true;
            this.activations++;
        }

        deactivate() {
            this.active = false;
            return Promise.resolve();
        }

        subscribe(destination, callback) {
            this.subscriptions.push({destination, callback});
        }
    }

    return {StompJs: {Client}, clients};
}

/** Response của fetch() giả. */
export function jsonResponse(body, {status = 200, type = "basic"} = {}) {
    return {
        ok: status >= 200 && status < 300,
        status,
        type,
        json: () => Promise.resolve(body)
    };
}

/** Chờ mọi promise đang dở (microtask + một vòng macrotask). */
export function flush() {
    return new Promise((resolve) => setImmediate(resolve));
}

/**
 * Nạp các script (đường dẫn tương đối static/js) vào cùng một context, như các thẻ script theo thứ tự trong trang.
 *
 * @param {string[]} scripts
 * @param {Object} globals biến toàn cục (document, fetch, StompJs...) — window là chính context
 */
export function load(scripts, globals) {
    const listeners = {};
    const context = vm.createContext({
        URL,
        console: globals.console || console,
        // Gọi qua hàm bọc để mock.timers của node:test (thay setTimeout của realm chính) có tác dụng
        setTimeout: (...args) => setTimeout(...args),
        clearTimeout: (...args) => clearTimeout(...args),
        CustomEvent: class CustomEvent {
            constructor(type, init = {}) {
                this.type = type;
                this.detail = init.detail;
            }
        },
        location: {href: "https://shop.example/orders"},
        addEventListener: (type, listener) => (listeners[type] ||= []).push(listener),
        ...globals
    });
    context.window = context;
    context.windowListeners = listeners;
    vm.runInContext("Math.random = () => 0;", context);   // jitter = 0 → thời gian chờ xác định
    for (const script of scripts) {
        const file = path.join(STATIC_JS, script);
        vm.runInContext(readFileSync(file, "utf8"), context, {filename: file});
    }
    return context;
}

/** Phát sự kiện tới listener của window (pagehide, pageshow...). */
export function fireWindowEvent(context, type, event = {}) {
    (context.windowListeners[type] || []).forEach((listener) => listener({type, ...event}));
}
