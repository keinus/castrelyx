// Structured schema mapper UI module
window.MapperUI = (function() {
    const DEFAULT_SOURCE_FIELDS = [
        'timestamp', 'event_time', 'host', 'message', 'raw_log',
        'src_ip', 'src_port', 'dst_ip', 'dst_port', 'protocol',
        'method', 'url', 'status', 'user_agent', 'bytes'
    ];

    let currentState = createEmptyState();
    let containerEl = null;

    function createEmptyState() {
        return {
            configId: null,
            messageType: null,
            sourceFields: [...DEFAULT_SOURCE_FIELDS],
            commonMappings: {},
            commonSchema: [],
            subSchemas: {},
            rules: [],
            currentRuleIndex: 0
        };
    }

    function getEl(id) {
        return document.getElementById(id);
    }

    function render(container) {
        containerEl = container;
        containerEl.innerHTML = `
            <div class="flex flex-col h-full gap-4 text-slate-300">
                <div class="grid grid-cols-1 xl:grid-cols-12 gap-3">
                    <div class="xl:col-span-3">
                        <label class="label py-1">
                            <span class="label-text text-slate-400 text-xs font-mono uppercase">Rule</span>
                        </label>
                        <div class="join w-full">
                            <select id="rule-select" class="select select-bordered select-sm join-item w-full bg-slate-800 border-slate-700 text-slate-300" onchange="MapperUI.selectRule(parseInt(this.value, 10))"></select>
                            <button type="button" id="rule-up-button" class="btn btn-sm btn-outline join-item border-slate-700 text-slate-300" onclick="MapperUI.moveRule(-1)">
                                <span class="material-icons-round text-sm">keyboard_arrow_up</span>
                            </button>
                            <button type="button" id="rule-down-button" class="btn btn-sm btn-outline join-item border-slate-700 text-slate-300" onclick="MapperUI.moveRule(1)">
                                <span class="material-icons-round text-sm">keyboard_arrow_down</span>
                            </button>
                        </div>
                    </div>
                    <div class="xl:col-span-3">
                        <label class="label py-1">
                            <span class="label-text text-slate-400 text-xs font-mono uppercase">Target Sub-Table</span>
                        </label>
                        <select id="sub-schema-select" class="select select-bordered select-sm w-full bg-slate-800 border-slate-700 text-slate-300" onchange="MapperUI.handleSubTableChange()"></select>
                    </div>
                    <div class="xl:col-span-4">
                        <label class="label py-1">
                            <span class="label-text text-slate-400 text-xs font-mono uppercase">Condition Rule (SpEL)</span>
                        </label>
                        <input type="text" id="condition-input" class="input input-bordered input-sm w-full bg-slate-800 border-slate-700 focus:border-blue-500 font-mono text-sm" placeholder="e.g. ['dst_port'] == 80" oninput="MapperUI.updateCurrentRuleCondition(this.value)">
                    </div>
                    <div class="xl:col-span-2 flex items-end gap-2">
                        <button type="button" class="btn btn-sm btn-outline border-slate-700 text-slate-300 flex-1" onclick="MapperUI.addRule()">
                            <span class="material-icons-round text-sm">add</span>
                            Rule
                        </button>
                        <button type="button" id="delete-rule-button" class="btn btn-sm btn-ghost btn-square text-slate-400 hover:text-rose-400" onclick="MapperUI.deleteCurrentRule()">
                            <span class="material-icons-round text-sm">delete</span>
                        </button>
                    </div>
                </div>

                <div class="flex flex-col md:flex-row gap-6 flex-1 min-h-0">
                    <div class="w-full md:w-1/3 flex flex-col bg-slate-900 rounded-lg border border-slate-800 shadow-sm overflow-hidden">
                        <div class="p-3 bg-slate-800/50 border-b border-slate-700 flex justify-between items-center">
                            <span class="font-semibold text-sm text-slate-200 flex items-center gap-2">
                                <span class="material-icons-round text-base text-blue-500">data_object</span>
                                Source Fields
                            </span>
                            <span class="text-xs text-slate-500">Drag to map</span>
                        </div>
                        <div class="p-2 border-b border-slate-800 bg-slate-900">
                            <div class="join w-full">
                                <input type="text" id="new-field-name" class="input input-xs input-bordered join-item w-full bg-slate-800 border-slate-700" placeholder="Add custom field...">
                                <button type="button" class="btn btn-xs btn-primary join-item" onclick="MapperUI.addCustomField()">
                                    <span class="material-icons-round text-xs">add</span>
                                </button>
                            </div>
                        </div>
                        <div id="source-list" class="flex-1 overflow-y-auto p-3 space-y-2 scroll-smooth"></div>
                    </div>

                    <div class="w-full md:w-2/3 flex flex-col bg-slate-900 rounded-lg border border-slate-800 shadow-sm overflow-hidden">
                        <div class="p-3 bg-slate-800/50 border-b border-slate-700 flex justify-between items-center">
                            <span class="font-semibold text-sm text-slate-200 flex items-center gap-2">
                                <span class="material-icons-round text-base text-emerald-500">table_chart</span>
                                Target Schema
                            </span>
                            <div class="flex gap-2">
                                <button type="button" class="btn btn-xs btn-ghost text-slate-400 hover:text-white" onclick="MapperUI.resetMapping()">
                                    Reset
                                </button>
                                <button type="button" class="btn btn-xs btn-outline btn-primary gap-1" onclick="MapperUI.autoMap()">
                                    <span class="material-icons-round text-xs">auto_fix_high</span>
                                    Auto Map
                                </button>
                            </div>
                        </div>

                        <div id="target-area" class="flex-1 overflow-y-auto p-4 space-y-6">
                            <div class="space-y-2">
                                <div class="flex items-center gap-2 text-xs font-bold text-slate-500 uppercase tracking-wider px-1">
                                    <span class="w-2 h-2 rounded-full bg-indigo-500"></span> Common Table (event)
                                </div>
                                <div id="common-schema-rows" class="border border-slate-800 rounded-md divide-y divide-slate-800 bg-slate-900/50"></div>
                            </div>

                            <div class="space-y-2">
                                <div class="flex items-center gap-2 text-xs font-bold text-slate-500 uppercase tracking-wider px-1">
                                    <span class="w-2 h-2 rounded-full bg-pink-500"></span> <span id="sub-schema-header">Sub Table</span>
                                </div>
                                <div id="sub-schema-rows" class="border border-slate-800 rounded-md divide-y divide-slate-800 bg-slate-900/50"></div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="collapse collapse-arrow bg-slate-900 border border-slate-800 rounded-lg">
                    <input type="checkbox" />
                    <div class="collapse-title text-sm font-medium flex items-center gap-2 text-slate-300">
                        <span class="material-icons-round text-blue-400">play_circle</span>
                        Simulation Preview
                    </div>
                    <div class="collapse-content">
                        <div class="flex flex-col gap-3 pt-2">
                            <div class="form-control">
                                <label class="label py-1"><span class="label-text-alt text-slate-500">Sample JSON Data</span></label>
                                <textarea id="sample-log-data" class="textarea textarea-bordered textarea-sm bg-slate-800 font-mono text-xs h-20 leading-relaxed text-slate-300 border-slate-700" placeholder='{"src_ip": "192.168.1.1", "method": "GET"}'></textarea>
                            </div>
                            <button type="button" class="btn btn-sm btn-primary w-full sm:w-auto self-start" onclick="MapperUI.runSimulation()">
                                Run Simulation
                            </button>
                            <div class="mockup-code bg-slate-950 text-xs p-0 border border-slate-800 min-h-[6rem]">
                                <pre id="sim-result" class="text-emerald-400 p-4"> // Result will appear here...</pre>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    async function loadData(messageType, existingConfig = null, schemaMetadata = null) {
        const nextState = createEmptyState();
        nextState.messageType = messageType;
        currentState = nextState;

        if (schemaMetadata) {
            applySchemaMetadata(schemaMetadata);
        } else {
            await loadSchemaMetadata();
        }

        if (existingConfig) {
            loadConfigToState(existingConfig);
        }

        ensureRule();
        renderUI();
    }

    async function loadSchemaMetadata() {
        if (typeof structureAPI === 'undefined') {
            throw new Error('structureAPI is not available');
        }
        applySchemaMetadata(await structureAPI.getSchema());
    }

    function applySchemaMetadata(data) {
        currentState.commonSchema = data && data.commonSchema ? data.commonSchema : [];
        currentState.subSchemas = data && data.subSchemas ? data.subSchemas : {};
    }

    function loadConfigToState(config) {
        currentState.configId = config.id || null;
        currentState.messageType = config.messageType || currentState.messageType;
        currentState.commonMappings = {};

        (config.commonMappings || []).forEach(mapping => {
            currentState.commonMappings[mapping.targetField] = mapping.sourceField;
            addSourceFieldRaw(mapping.sourceField);
        });

        currentState.rules = (config.subTableRules || []).map(rule => {
            const mappings = {};
            (rule.mappings || []).forEach(mapping => {
                mappings[mapping.targetField] = mapping.sourceField;
                addSourceFieldRaw(mapping.sourceField);
            });

            return {
                targetSubTable: rule.targetSubTable || getDefaultSubTable(),
                conditionExpression: rule.conditionExpression || '',
                mappings
            };
        });
        currentState.currentRuleIndex = 0;
    }

    function ensureRule() {
        if (currentState.rules.length === 0) {
            currentState.rules.push(createRule());
        }

        if (currentState.currentRuleIndex < 0 || currentState.currentRuleIndex >= currentState.rules.length) {
            currentState.currentRuleIndex = 0;
        }
    }

    function createRule() {
        return {
            targetSubTable: getDefaultSubTable(),
            conditionExpression: '',
            mappings: {}
        };
    }

    function getDefaultSubTable() {
        return Object.keys(currentState.subSchemas)[0] || '';
    }

    function getCurrentRule() {
        ensureRule();
        return currentState.rules[currentState.currentRuleIndex];
    }

    function addSourceFieldRaw(name) {
        if (name && !currentState.sourceFields.includes(name)) {
            currentState.sourceFields.push(name);
        }
    }

    function addCustomField() {
        const input = getEl('new-field-name');
        const name = input.value.trim();
        if (name) {
            addSourceFieldRaw(name);
            input.value = '';
            renderSourceList();
            renderTargetSchemas();
        }
    }

    function addRule() {
        currentState.rules.push(createRule());
        currentState.currentRuleIndex = currentState.rules.length - 1;
        renderUI();
    }

    function deleteCurrentRule() {
        if (currentState.rules.length <= 1) return;
        if (!confirm('Delete selected rule?')) return;

        currentState.rules.splice(currentState.currentRuleIndex, 1);
        currentState.currentRuleIndex = Math.max(0, currentState.currentRuleIndex - 1);
        renderUI();
    }

    function moveRule(delta) {
        const from = currentState.currentRuleIndex;
        const to = from + delta;
        if (to < 0 || to >= currentState.rules.length) return;

        const [rule] = currentState.rules.splice(from, 1);
        currentState.rules.splice(to, 0, rule);
        currentState.currentRuleIndex = to;
        renderUI();
    }

    function selectRule(index) {
        if (Number.isNaN(index) || index < 0 || index >= currentState.rules.length) return;
        currentState.currentRuleIndex = index;
        renderUI();
    }

    function handleSubTableChange() {
        const select = getEl('sub-schema-select');
        getCurrentRule().targetSubTable = select.value;
        renderRuleControls();
        renderTargetSchemas();
    }

    function updateCurrentRuleCondition(value) {
        getCurrentRule().conditionExpression = value;
    }

    function renderUI() {
        renderRuleControls();
        renderSourceList();
        renderTargetSchemas();
    }

    function renderRuleControls() {
        const rule = getCurrentRule();
        const ruleSelect = getEl('rule-select');
        const subSchemaSelect = getEl('sub-schema-select');
        const conditionInput = getEl('condition-input');
        const deleteButton = getEl('delete-rule-button');
        const upButton = getEl('rule-up-button');
        const downButton = getEl('rule-down-button');

        if (!ruleSelect || !subSchemaSelect || !conditionInput) return;

        ruleSelect.innerHTML = '';
        currentState.rules.forEach((item, index) => {
            const option = document.createElement('option');
            option.value = index;
            option.textContent = `Rule ${index + 1}: ${item.targetSubTable || 'Sub Table'}`;
            option.selected = index === currentState.currentRuleIndex;
            ruleSelect.appendChild(option);
        });

        subSchemaSelect.innerHTML = '';
        Object.keys(currentState.subSchemas).forEach(tableName => {
            const option = document.createElement('option');
            option.value = tableName;
            option.textContent = tableName;
            option.selected = tableName === rule.targetSubTable;
            subSchemaSelect.appendChild(option);
        });
        subSchemaSelect.disabled = Object.keys(currentState.subSchemas).length === 0;

        conditionInput.value = rule.conditionExpression || '';

        if (deleteButton) deleteButton.disabled = currentState.rules.length <= 1;
        if (upButton) upButton.disabled = currentState.currentRuleIndex === 0;
        if (downButton) downButton.disabled = currentState.currentRuleIndex === currentState.rules.length - 1;
    }

    function renderSourceList() {
        const container = getEl('source-list');
        if (!container) return;

        container.innerHTML = '';
        currentState.sourceFields.forEach(field => {
            const chip = document.createElement('div');
            chip.className = 'badge badge-neutral gap-2 cursor-grab hover:bg-slate-700 hover:text-white transition-colors py-3 w-full justify-start border-slate-700 text-slate-300';
            chip.draggable = true;
            chip.ondragstart = event => {
                event.dataTransfer.setData('text', field);
                event.currentTarget.classList.add('opacity-50');
            };
            chip.ondragend = event => {
                event.currentTarget.classList.remove('opacity-50');
            };

            const icon = document.createElement('span');
            icon.className = 'material-icons-round text-xs text-slate-500';
            icon.textContent = 'drag_indicator';

            const text = document.createElement('span');
            text.className = 'font-mono text-xs';
            text.textContent = field;

            chip.appendChild(icon);
            chip.appendChild(text);
            container.appendChild(chip);
        });
    }

    function renderTargetSchemas() {
        renderSchemaGroup(currentState.commonSchema, 'common-schema-rows', 'common');

        const rule = getCurrentRule();
        const header = getEl('sub-schema-header');
        if (header) header.innerText = `Sub Table: ${rule.targetSubTable || '-'}`;

        renderSchemaGroup(currentState.subSchemas[rule.targetSubTable] || [], 'sub-schema-rows', 'sub');
    }

    function renderSchemaGroup(columns, containerId, scope) {
        const container = getEl(containerId);
        if (!container) return;

        container.innerHTML = '';
        columns.forEach(col => {
            const row = document.createElement('div');
            row.className = 'flex items-center p-2 text-sm gap-4 hover:bg-slate-800 transition-colors group';

            const currentMap = getMapping(scope, col.name);
            const isActive = !!currentMap;
            const dropZoneClass = isActive
                ? 'bg-blue-500/10 border-blue-500/30'
                : 'bg-slate-950 border-slate-700 border-dashed hover:border-slate-500';

            row.innerHTML = `
                <div class="flex-1 min-w-0">
                    <div class="font-medium text-slate-300 truncate" title="${escapeHtml(col.name)}">${escapeHtml(col.name)}</div>
                    <div class="text-[10px] font-mono text-slate-500">${escapeHtml(col.type || '')}${col.deprecated ? ' (deprecated)' : ''}</div>
                </div>
                <div class="text-slate-600">
                    <span class="material-icons-round text-sm">arrow_right_alt</span>
                </div>
                <div class="flex-[1.5]">
                    <div class="relative flex items-center gap-2 p-1 rounded border ${dropZoneClass} transition-all"
                         ondrop="MapperUI.handleDrop(event, '${scope}', '${escapeJs(col.name)}')"
                         ondragover="MapperUI.allowDrop(event)"
                         ondragenter="this.classList.add('border-blue-500', 'bg-blue-500/5')"
                         ondragleave="this.classList.remove('border-blue-500', 'bg-blue-500/5')">
                        ${isActive ? renderMappedBadge(scope, col.name, currentMap) : renderMappingSelect(scope, col.name, currentMap)}
                    </div>
                </div>
            `;
            container.appendChild(row);
        });
    }

    function renderMappedBadge(scope, targetCol, sourceField) {
        return `
            <span class="badge badge-sm badge-info gap-1 pl-1 pr-2">
                <span class="material-icons-round text-[10px]">data_object</span>
                ${escapeHtml(sourceField)}
            </span>
            <button type="button" onclick="MapperUI.clearMap('${scope}', '${escapeJs(targetCol)}')" class="btn btn-ghost btn-xs btn-circle text-slate-500 hover:text-white absolute right-1">
                <span class="material-icons-round text-xs">close</span>
            </button>
        `;
    }

    function renderMappingSelect(scope, targetCol, currentMap) {
        const options = ['<option value="">Select...</option>']
            .concat(currentState.sourceFields.map(sourceField => {
                const selected = sourceField === currentMap ? 'selected' : '';
                return `<option value="${escapeAttr(sourceField)}" ${selected}>${escapeHtml(sourceField)}</option>`;
            }))
            .join('');

        return `
            <select class="select select-ghost select-xs w-full text-slate-400 font-normal focus:bg-transparent pl-1"
                    onchange="MapperUI.manualSelect('${scope}', '${escapeJs(targetCol)}', this.value)">
                ${options}
            </select>
        `;
    }

    function getMapping(scope, targetCol) {
        if (scope === 'common') {
            return currentState.commonMappings[targetCol];
        }
        return getCurrentRule().mappings[targetCol];
    }

    function setMapping(scope, targetCol, sourceField) {
        const target = scope === 'common' ? currentState.commonMappings : getCurrentRule().mappings;
        if (sourceField) {
            target[targetCol] = sourceField;
        } else {
            delete target[targetCol];
        }
    }

    function handleDrop(event, scope, targetCol) {
        event.preventDefault();
        if (event.currentTarget) {
            event.currentTarget.classList.remove('border-blue-500', 'bg-blue-500/5');
        }

        manualSelect(scope, targetCol, event.dataTransfer.getData('text'));
    }

    function allowDrop(event) {
        event.preventDefault();
    }

    function manualSelect(scope, targetCol, sourceField) {
        setMapping(scope, targetCol, sourceField);
        renderTargetSchemas();
    }

    function clearMap(scope, targetCol) {
        setMapping(scope, targetCol, '');
        renderTargetSchemas();
    }

    function resetMapping() {
        if (!confirm('Clear common mappings and selected rule mappings?')) return;

        currentState.commonMappings = {};
        getCurrentRule().mappings = {};
        renderTargetSchemas();
    }

    function autoMap() {
        autoMapColumns(currentState.commonSchema, currentState.commonMappings);
        autoMapColumns(currentState.subSchemas[getCurrentRule().targetSubTable] || [], getCurrentRule().mappings);
        renderTargetSchemas();
    }

    function autoMapColumns(columns, mappings) {
        columns.forEach(col => {
            if (mappings[col.name]) return;

            const match = currentState.sourceFields.find(sourceField =>
                sourceField === col.name ||
                sourceField.includes(col.name) ||
                col.name.includes(sourceField)
            );
            if (match) {
                mappings[col.name] = match;
            }
        });
    }

    function getData() {
        const config = {
            messageType: currentState.messageType,
            commonMappings: Object.keys(currentState.commonMappings).map(targetField => ({
                sourceField: currentState.commonMappings[targetField],
                targetField
            })),
            subTableRules: currentState.rules.map(rule => ({
                targetSubTable: rule.targetSubTable,
                conditionExpression: rule.conditionExpression || '',
                mappings: Object.keys(rule.mappings).map(targetField => ({
                    sourceField: rule.mappings[targetField],
                    targetField
                }))
            }))
        };

        if (currentState.configId) {
            config.id = currentState.configId;
        }

        return config;
    }

    async function runSimulation() {
        let sampleData = {};
        try {
            const value = getEl('sample-log-data').value;
            if (value) sampleData = JSON.parse(value);
        } catch (e) {
            alert('Invalid JSON in sample data');
            return;
        }

        const simResult = getEl('sim-result');
        simResult.innerText = 'Simulating...';

        try {
            const result = await structureAPI.simulate({
                messageType: currentState.messageType,
                sampleData,
                temporaryConfig: getData()
            });
            simResult.innerText = JSON.stringify(result, null, 2);
            simResult.className = 'text-emerald-400 p-4';
        } catch (e) {
            simResult.innerText = 'Simulation Failed: ' + e.message;
            simResult.className = 'text-rose-400 p-4';
        }
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function escapeAttr(value) {
        return escapeHtml(value);
    }

    function escapeJs(value) {
        return String(value == null ? '' : value)
            .replace(/\\/g, '\\\\')
            .replace(/'/g, "\\'");
    }

    return {
        render,
        loadData,
        getData,
        selectRule,
        addRule,
        deleteCurrentRule,
        moveRule,
        handleSubTableChange,
        updateCurrentRuleCondition,
        addCustomField,
        handleDrop,
        allowDrop,
        manualSelect,
        clearMap,
        autoMap,
        resetMapping,
        runSimulation
    };
})();
