const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

// Exercise the existing closure without adding test-only exports to the browser API.
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pipeline-studio.js'), 'utf8');
const exportsMarker = 'return { mount, reload: loadStudioData };';
assert.ok(source.includes(exportsMarker));
const instrumented = source.replace(exportsMarker, `return {
    state, selectComponent, beginCreate, startCreateWithType, runTest, testContext,
    markDirty, handleTestInput, changeMessageType, reorderProcessingSteps,
    toggleFromRail, saveCurrent, renderTest, availableSourceFields, discardCurrent
};`);

function setup() {
    const calls = [];
    const container = { innerHTML: '' };
    const context = vm.createContext({
        console, performance,
        document: {
            activeElement: null,
            getElementById: id => id === 'studio-test' ? container : null,
            querySelector: () => null,
            querySelectorAll: () => [],
            createElement: () => ({ setAttribute() {}, remove() {} }),
            body: { appendChild() {} }
        },
        window: { confirm: () => true },
        setTimeout: () => 0,
        parserAPI: { test: async request => { calls.push(request); return JSON.parse(request.sampleData); } },
        structureAPI: {
            simulate: async request => ({ common: request.sampleData, subFields: {}, additionalAttributes: {} }),
            getMapping: async () => ({ commonMappings: [], subTableRules: [] })
        }
    });
    vm.runInContext(instrumented, context);
    const studio = context.window.PipelineStudio;
    const { state } = studio;
    state.messageType = 'test';
    state.mapping = { messageType: 'test', commonMappings: [], subTableRules: [] };
    state.sampleInput = '{"message":"{\\"level\\":\\"INFO\\"}","debug":true}';
    state.data.parser = [{ id: 1, type: 'JsonParser', messagetype: 'test', priority: 10, enabled: true }];
    state.data.transform = [{ id: 2, type: 'RemoveProperty', messagetype: 'test', priority: 20, enabled: true, removeProperties: '["debug"]' }];
    return { studio, state, calls, container, context };
}

const plain = value => JSON.parse(JSON.stringify(value));

test('lower-order tests inherit the exact result without rerunning the upper parser', async () => {
    const { studio, state, calls, container } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    const upper = plain(state.testResults.get('parser:1').payload);
    studio.selectComponent('transform', 2);
    assert.deepEqual(plain(studio.testContext().source.payload), upper);
    await studio.runTest();
    assert.equal(calls.length, 1);
    assert.equal(studio.testContext().result.payload.debug, undefined);
    assert.deepEqual(plain(state.testResults.get('parser:1').payload), upper, 'the inherited source is not mutated');
    assert.match(container.innerHTML, /studio-test-source[^>]*readonly/);
    assert.doesNotMatch(container.innerHTML, /studio-test-stage/);
});

test('a new parser tests its draft against the preceding transform result without saving', async () => {
    const { studio, state, calls } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    studio.beginCreate('parser');
    studio.startCreateWithType('JsonParser');
    assert.equal(state.draft.priority, 30);
    state.draft.sourceField = 'message';
    studio.markDirty();
    assert.ok(studio.availableSourceFields().includes('message'));
    assert.equal(studio.testContext().source.node.key, 'transform:2');
    await studio.runTest();
    assert.equal(calls.length, 2);
    assert.equal(calls[1].sampleData, '{"level":"INFO"}');
    assert.deepEqual(plain(studio.testContext().result.payload.message), { level: 'INFO' });
    assert.equal(studio.testContext().result.payload.level, undefined);
    assert.equal(studio.testContext().result.payload.debug, undefined);
    assert.equal(state.data.parser.length, 1);
    assert.equal(state.selected.id, null);
});

test('untouched form defaults and JSON formatting do not invalidate a tested adapter on selection', async () => {
    const { studio, context, state } = setup();
    studio.selectComponent('parser', 1);
    context.document.querySelectorAll = selector => selector === '#studio-settings [data-field]' ? [
        { dataset: { field: 'sourceField' }, value: '' },
        { dataset: { field: 'continueOnFailure', valueType: 'boolean' }, checked: false }
    ] : [];
    await studio.runTest();
    context.document.querySelectorAll = () => [];
    studio.selectComponent('transform', 2);
    assert.equal(studio.testContext().source.error, null);
    state.draft.removeProperties = '[ "debug" ]';
    await studio.runTest();
    studio.selectComponent('structured', 'mapping');
    assert.equal(studio.testContext().source.error, null);
});

test('a new transform executes its draft and keeps its tested result when saved', async () => {
    const { studio, state } = setup();
    state.demo = true;
    state.data.transform = [];
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.beginCreate('transform');
    studio.startCreateWithType('RemoveProperty');
    state.draft.removeProperties = '["debug"]';
    studio.markDirty();
    await studio.runTest();
    assert.equal(state.testResults.get('transform:draft').payload.debug, undefined);
    await studio.saveCurrent();
    assert.equal(state.mode, 'edit');
    assert.ok(studio.testContext().result);
    assert.equal(state.testResults.has('transform:draft'), false);
});

test('an unconfigured new pipeline can test its first draft directly with the sample', async () => {
    const { studio, state } = setup();
    state.data.parser = [];
    state.data.transform = [];
    studio.beginCreate('parser');
    assert.equal(studio.testContext().node, undefined);
    studio.startCreateWithType('JsonParser');
    assert.equal(studio.testContext().source.node, null);
    await studio.runTest();
    assert.equal(studio.testContext().result.payload.debug, true);
});

test('missing upper results block lower tests instead of falling back to the sample', async () => {
    const { studio, calls, container } = setup();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    assert.match(studio.testContext().source.error, /successfully first/);
    assert.equal(studio.testContext().source.text, '');
    assert.equal(studio.testContext().result, undefined);
    assert.equal(calls.length, 0);
    assert.match(container.innerHTML, /data-run-test disabled/);
});

test('editing a lower draft preserves the upper result but invalidates its own result', async () => {
    const { studio, state } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    state.draft.removeProperties = '["message"]';
    studio.markDirty();
    assert.ok(state.testResults.get('parser:1'));
    assert.equal(studio.testContext().result, undefined);
    await studio.runTest();
    assert.equal(studio.testContext().result.payload.message, undefined);
    assert.equal(studio.testContext().result.payload.debug, true);
});

test('upper edits, sample changes and order changes invalidate downstream results', async t => {
    for (const change of ['config', 'sample', 'order']) {
        await t.test(change, async () => {
            const { studio, state } = setup();
            state.demo = true;
            studio.selectComponent('parser', 1);
            await studio.runTest();
            studio.selectComponent('transform', 2);
            await studio.runTest();
            studio.selectComponent('parser', 1);
            if (change === 'config') {
                state.draft.sourceField = 'message';
                studio.markDirty();
            } else if (change === 'sample') {
                studio.handleTestInput({ target: { id: 'studio-sample-input', value: '{"different":true}' } });
            } else {
                await studio.reorderProcessingSteps('transform', 2, 'parser', 1);
            }
            studio.testContext();
            assert.equal(state.testResults.has('transform:2'), false);
            assert.equal(state.testResults.has('parser:1'), false);
        });
    }
});

test('rerunning an upper test invalidates the lower cache even if the payload stays identical', async () => {
    const { studio, state } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    assert.equal(state.testResults.has('transform:2'), false);
});

test('a failed rerun replaces the old success and cannot be inherited', async () => {
    const { studio, context } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    context.parserAPI.test = async () => { throw new Error('Invalid JSON'); };
    await studio.runTest();
    assert.equal(studio.testContext().result.error, true);
    studio.selectComponent('transform', 2);
    assert.match(studio.testContext().source.error, /successfully first/);
});

test('a dropped event blocks the next adapter', async () => {
    const { studio, state } = setup();
    state.data.transform[0] = { id: 2, type: 'Filter', messagetype: 'test', priority: 20, enabled: true, filterDrop: '{"debug":"true"}' };
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    assert.equal(studio.testContext().result.count, 0);
    studio.beginCreate('parser');
    studio.startCreateWithType('JsonParser');
    assert.match(studio.testContext().source.error, /dropped the event/);
});

test('empty filter form maps are equivalent to unset saved maps', async () => {
    const { studio, state } = setup();
    state.data.transform[0] = { id: 2, type: 'Filter', messagetype: 'test', priority: 20, enabled: true, filterDrop: '{"debug":"false"}' };
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    state.draft.filterPass = '{}';
    await studio.runTest();
    studio.selectComponent('structured', 'mapping');
    assert.equal(studio.testContext().source.error, null);
});

test('current draft priority and parser/transform tie order determine the predecessor', async () => {
    const { studio, state } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.beginCreate('parser');
    studio.startCreateWithType('JsonParser');
    state.draft.priority = 20;
    studio.markDirty();
    assert.equal(studio.testContext().source.node.key, 'parser:1');
    state.draft.priority = 5;
    studio.markDirty();
    assert.equal(studio.testContext().source.node, null);
});

test('disabled processing steps are skipped and enabling them invalidates dependent results', async () => {
    const { studio, state } = setup();
    state.demo = true;
    state.data.parser[0].enabled = false;
    studio.selectComponent('transform', 2);
    assert.equal(studio.testContext().source.node, null);
    await studio.runTest();
    await studio.toggleFromRail('parser', 1, true);
    assert.equal(studio.testContext().result, undefined);
    assert.match(studio.testContext().source.error, /successfully first/);
});

test('switching message types clears test results', async () => {
    const { studio, state } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    await studio.changeMessageType('other', true);
    assert.equal(state.testResults.size, 0);
    assert.equal(studio.testContext().source.node, null);
});

test('late parser responses are discarded when the draft or sample changes', async t => {
    for (const change of ['draft', 'sample']) {
        await t.test(change, async () => {
            const { studio, state, context } = setup();
            let resolve;
            context.parserAPI.test = () => new Promise(done => { resolve = done; });
            studio.selectComponent('parser', 1);
            const running = studio.runTest();
            if (change === 'draft') {
                state.draft.sourceField = 'message';
                studio.markDirty();
            } else {
                studio.handleTestInput({ target: { id: 'studio-sample-input', value: '{"new":true}' } });
            }
            resolve({ stale: true });
            await running;
            assert.equal(studio.testContext().result, undefined);
            assert.equal(state.testRunning, false);
        });
    }
});

test('parser source fields use inherited objects and missing fields produce a test error', async () => {
    const { studio, state, calls } = setup();
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.beginCreate('parser');
    studio.startCreateWithType('JsonParser');
    state.draft.priority = 15;
    state.draft.sourceField = 'missing';
    studio.markDirty();
    await studio.runTest();
    assert.match(studio.testContext().result.payload.error, /missing from the test source/);
    assert.equal(calls.length, 1);
});

test('regex parser sends structured-data arrays without JSON string wrapping', async () => {
    const { studio, state, context } = setup();
    const structuredData = ['[exampleSDID@32473 iut="3" eventSource="Application"]'];
    state.sampleInput = JSON.stringify({ syslog_MSGID: 'ID47', syslog_STRUCTURED_DATA: structuredData });
    state.data.transform = [];
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.beginCreate('parser');
    studio.startCreateWithType('RegexParser');
    state.draft.param = '^\\[(?<sdid>\\w+)@(?<id>\\d+)\\s+(?<attributes>.*)\\]$';
    state.draft.sourceField = 'syslog_STRUCTURED_DATA';
    studio.markDirty();
    let request;
    context.parserAPI.test = async input => {
        request = input;
        return { sdid: 'exampleSDID', id: '32473', attributes: 'iut="3" eventSource="Application"' };
    };

    await studio.runTest();

    assert.deepEqual(plain(request.sampleData), structuredData);
    assert.deepEqual(plain(studio.testContext().result.payload.syslog_STRUCTURED_DATA), {
        sdid: 'exampleSDID',
        id: '32473',
        attributes: 'iut="3" eventSource="Application"'
    });
    assert.equal(studio.testContext().result.payload.sdid, undefined);
    assert.equal(studio.testContext().result.payload.syslog_MSGID, 'ID47');
});

test('demo regex parsing applies named groups to each sample array item', async () => {
    const { studio, state } = setup();
    state.demo = true;
    state.data.parser = [];
    state.data.transform = [];
    state.sampleInput = JSON.stringify({ structured: ['[first@1 a=one]', 'unmatched', '[second@2 a=two]'] });
    studio.beginCreate('parser');
    studio.startCreateWithType('RegexParser');
    state.draft.param = '^\\[(?<sdid>\\w+)@(?<id>\\d+)\\s+(?<attributes>.*)\\]$';
    state.draft.sourceField = 'structured';
    studio.markDirty();

    await studio.runTest();

    assert.equal(studio.testContext().result.error, undefined);
    assert.deepEqual(plain(studio.testContext().result.payload.structured), {
        sdid: 'second',
        id: '2',
        attributes: 'a=two',
        a: 'two'
    });
    assert.equal(studio.testContext().result.payload.sdid, undefined);
});

test('structured mapping and output previews inherit results without rerunning previous steps', async () => {
    const { studio, state, calls } = setup();
    state.data.output = [{ id: 3, type: 'ConsoleOutputAdapter', messagetype: 'test', enabled: true }];
    studio.selectComponent('parser', 1);
    await studio.runTest();
    studio.selectComponent('transform', 2);
    await studio.runTest();
    studio.selectComponent('structured', 'mapping');
    await studio.runTest();
    const structured = plain(studio.testContext().result.payload);
    studio.selectComponent('output', 3);
    await studio.runTest();
    assert.deepEqual(plain(studio.testContext().result.payload.serializedPayload), structured);
    assert.equal(calls.length, 1);
    assert.match(studio.testContext().result.status, /no external delivery/);
});
