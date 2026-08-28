const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

// Exercise the production connection code without adding browser-only test exports.
const appPath = path.join(__dirname, '../../main/resources/static/js/app.js');
const source = fs.readFileSync(appPath, 'utf8');
const start = source.indexOf('    // --- Live Tail ---');
const end = source.indexOf('    async function toggleLiveTailService(', start);
assert.ok(start >= 0 && end > start);
const connectionSource = source.slice(start, end);
const flush = () => new Promise(resolve => setImmediate(resolve));

function setup({ constructorFailures = 0 } = {}) {
    const sockets = [];
    const statuses = [];
    const timers = new Map();
    const errors = [];
    let now = 0;
    let timerId = 0;
    const terminal = { innerHTML: '' };
    const toggle = { checked: false };
    const track = result => Promise.resolve(result).catch(error => errors.push(error));
    const addTimer = (callback, delay, repeat) => {
        const id = ++timerId;
        timers.set(id, { callback, delay, repeat, at: now + delay });
        return id;
    };
    class MockWebSocket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;
        constructor(url) {
            if (constructorFailures > 0) {
                constructorFailures--;
                throw new Error('WebSocket construction failed');
            }
            this.url = url;
            this.readyState = MockWebSocket.CONNECTING;
            this.closeCalls = 0;
            sockets.push(this);
        }
        open() {
            this.readyState = MockWebSocket.OPEN;
            this.onopen();
        }
        close() {
            this.closeCalls++;
            this.readyState = MockWebSocket.CLOSED;
            this.onclose();
        }
    }
    const context = vm.createContext({
        console: { log() {}, error() {}, warn() {} },
        pipelineAPI: {
            getLiveTailStatus() {
                return new Promise((resolve, reject) => statuses.push({ resolve, reject }));
            }
        },
        document: { getElementById: id => id === 'livetail-service-toggle' ? toggle : terminal },
        window: { location: { protocol: 'http:', host: 'localhost:8765' } },
        WebSocket: MockWebSocket,
        setTimeout: (callback, delay) => addTimer(callback, delay, false),
        clearTimeout: id => timers.delete(id),
        setInterval: (callback, delay) => addTimer(callback, delay, true),
        clearInterval: id => timers.delete(id)
    });
    vm.runInContext(connectionSource + '\nthis.connect = connectLiveTail;', context, { filename: appPath });
    return {
        sockets, statuses, timers, errors, terminal, toggle,
        connect: () => track(context.connect()),
        async advance(duration) {
            const target = now + duration;
            while (true) {
                const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
                if (!next || next[1].at > target) break;
                const [id, timer] = next;
                now = timer.at;
                if (timer.repeat) timer.at += timer.delay;
                else timers.delete(id);
                track(timer.callback());
                await flush();
            }
            now = target;
        }
    };
}

async function connectedPage() {
    const page = setup();
    page.connect();
    page.statuses[0].resolve({ enabled: false });
    await flush();
    page.sockets[0].open();
    return page;
}

test('status refresh cannot delay or duplicate a connecting or open socket', async () => {
    const page = setup();
    page.connect();
    page.connect();
    await flush();
    assert.equal(page.sockets.length, 1);
    assert.equal(page.statuses.length, 1);
    page.sockets[0].open();
    page.connect();
    page.statuses[0].resolve({ enabled: false });
    await flush();
    assert.equal(page.sockets.length, 1);
    assert.equal(page.timers.size, 0);
});

test('reconnect stays single while status and handshake take more than three seconds', async () => {
    const page = await connectedPage();
    page.sockets[0].close();
    await page.advance(2999);
    assert.equal(page.sockets.length, 1);
    await page.advance(1);
    assert.equal(page.sockets.length, 2);
    await page.advance(12000);
    assert.equal(page.sockets.length, 2);
    assert.equal(page.statuses.length, 2);
    assert.equal(page.sockets[1].readyState, 0);
    page.sockets[1].open();
    page.statuses[1].resolve({ enabled: true });
    await flush();
    assert.equal(page.toggle.checked, true);
    assert.equal(page.timers.size, 0);
    assert.equal(page.sockets.filter(socket => socket.readyState === 1).length, 1);
});

test('old socket error, close and open callbacks cannot affect its replacement', async () => {
    const page = await connectedPage();
    const oldSocket = page.sockets[0];
    oldSocket.close();
    await page.advance(3000);
    page.statuses[1].resolve({ enabled: false });
    await flush();
    const newSocket = page.sockets[1];
    newSocket.open();
    const before = page.terminal.innerHTML;
    oldSocket.onerror(new Error('late transport error'));
    oldSocket.onclose();
    oldSocket.onopen();
    assert.equal(newSocket.closeCalls, 0);
    assert.equal(newSocket.readyState, 1);
    assert.equal(page.timers.size, 0);
    assert.equal(page.terminal.innerHTML, before);
});

test('a closing socket is not replaced until it closes', async () => {
    const page = await connectedPage();
    page.sockets[0].readyState = 2;
    page.connect();
    await flush();
    assert.equal(page.sockets.length, 1);
    page.sockets[0].close();
    page.sockets[0].onclose();
    assert.equal(page.timers.size, 1);
    await page.advance(3000);
    assert.equal(page.sockets.length, 2);
});

test('a WebSocket constructor failure schedules one retry', async () => {
    const page = setup({ constructorFailures: 1 });
    page.connect();
    await flush();
    assert.equal(page.sockets.length, 0);
    assert.equal(page.timers.size, 1);
    await page.advance(3000);
    assert.equal(page.sockets.length, 1);
    page.statuses[0].resolve({ enabled: false });
    await flush();
    page.sockets[0].open();
    assert.equal(page.timers.size, 0);
    assert.equal(page.errors.length, 0);
});

test('a failed status refresh does not interrupt an open connection', async () => {
    const page = setup();
    page.connect();
    await flush();
    assert.equal(page.sockets.length, 1);
    page.sockets[0].open();
    page.statuses[0].reject(new Error('status unavailable'));
    await flush();
    assert.equal(page.sockets[0].readyState, 1);
    assert.equal(page.sockets[0].closeCalls, 0);
    assert.equal(page.timers.size, 0);
    assert.equal(page.errors.length, 0);
});

test('a status response from an old connection cannot overwrite current status', async () => {
    const page = setup();
    page.connect();
    await flush();
    assert.equal(page.sockets.length, 1);
    page.sockets[0].open();
    page.sockets[0].close();
    await page.advance(3000);
    page.sockets[1].open();
    page.statuses[1].resolve({ enabled: true });
    await flush();
    page.statuses[0].resolve({ enabled: false });
    await flush();
    assert.equal(page.toggle.checked, true);
});

test('repeated transport failures keep exactly one retry per closed socket', async () => {
    const page = await connectedPage();
    for (let attempt = 0; attempt < 3; attempt++) {
        const socket = page.sockets[attempt];
        socket.onerror(new Error('server unavailable'));
        socket.onclose();
        assert.equal(socket.closeCalls, 1);
        assert.equal(page.timers.size, 1);
        await page.advance(3000);
        assert.equal(page.sockets.length, attempt + 2);
        page.statuses[attempt + 1].resolve({ enabled: false });
        await flush();
    }
    page.sockets[3].open();
    assert.equal(page.timers.size, 0);
    assert.equal(page.errors.length, 0);
});
