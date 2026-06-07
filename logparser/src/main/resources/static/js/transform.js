// Global State
let schemaMetadata = null;
let sourceFields = []; // List of source field names
let commonMappings = {}; // target -> source
let subRules = {}; // subTable -> { condition: "", mappings: { target -> source } }

// API Endpoints
const API_BASE = '/api/v1/structure';

async function init() {
    try {
        // Check for message type in URL
        const urlParams = new URLSearchParams(window.location.search);
        const urlType = urlParams.get('type');
        if (urlType) {
            document.getElementById('message-type').value = urlType;
        }

        const res = await fetch(`${API_BASE}/schema`);
        schemaMetadata = await res.json();
        
        populateSubTableSelect();
        renderCommonSchema();
        renderSubSchema(); // Initial render
        
        // Add some default source fields for demo
        addSourceField('src_ip');
        addSourceField('dst_ip');
        addSourceField('timestamp');

        // If type was provided in URL, load its configuration immediately
        if (urlType) {
            await loadMapping();
        }
    } catch (e) {
        console.error("Failed to init", e);
        alert("Failed to load schema metadata");
    }
}

function populateSubTableSelect() {
    const select = document.getElementById('sub-schema-select');
    select.innerHTML = '';
    if (schemaMetadata && schemaMetadata.subSchemas) {
        Object.keys(schemaMetadata.subSchemas).forEach(key => {
            const opt = document.createElement('option');
            opt.value = key;
            opt.text = key;
            select.appendChild(opt);
        });
    }
}

// --- Source Fields ---

function addSourceField(name) {
    if (!name || sourceFields.includes(name)) return;
    sourceFields.push(name);
    renderSourceList();
    refreshDropdowns();
}

function addCustomField() {
    const input = document.getElementById('new-field-name');
    const name = input.value.trim();
    if (name) {
        addSourceField(name);
        input.value = '';
    }
}

function renderSourceList() {
    const container = document.getElementById('source-list');
    container.innerHTML = '';
    sourceFields.forEach(field => {
        const div = document.createElement('div');
        div.className = 'field-item';
        div.draggable = true;
        div.ondragstart = (e) => e.dataTransfer.setData('text', field);
        div.innerText = field;
        container.appendChild(div);
    });
}

function refreshDropdowns() {
    // Re-populate all dropdowns with current sourceFields
    document.querySelectorAll('.combo-select').forEach(select => {
        const currentVal = select.value;
        select.innerHTML = '<option value="">(Select...)</option>';
        sourceFields.forEach(src => {
            const opt = document.createElement('option');
            opt.value = src;
            opt.text = src;
            select.appendChild(opt);
        });
        select.value = currentVal;
    });
}

// --- Target Schema Rendering ---

function renderCommonSchema() {
    if (!schemaMetadata) return;
    renderSchemaRows(schemaMetadata.commonSchema, 'common-schema-rows', commonMappings, (target, source) => {
        if (source) commonMappings[target] = source;
        else delete commonMappings[target];
    });
}

function renderSubSchema() {
    if (!schemaMetadata) return;
    const subTable = document.getElementById('sub-schema-select').value;
    if (!subTable) return;
    
    document.getElementById('sub-schema-header').innerText = `Sub Table: ${subTable}`;
    
    // Get or init rule state
    if (!subRules[subTable]) {
        subRules[subTable] = { condition: "", mappings: {} };
    }
    const ruleState = subRules[subTable];
    
    // Update condition input
    document.getElementById('condition-expr').value = ruleState.condition || "";
    
    // Render rows
    const columns = schemaMetadata.subSchemas[subTable] || [];
    renderSchemaRows(columns, 'sub-schema-rows', ruleState.mappings, (target, source) => {
        if (source) ruleState.mappings[target] = source;
        else delete ruleState.mappings[target];
    });
}

// Generic row renderer
function renderSchemaRows(columns, containerId, mappingState, onUpdate) {
    const container = document.getElementById(containerId);
    container.innerHTML = '';
    
    columns.forEach(col => {
        const row = document.createElement('div');
        row.className = 'schema-row';
        
        const currentMap = mappingState[col.name] || "";
        
        let options = '<option value="">(Select...)</option>';
        sourceFields.forEach(src => {
            const selected = src === currentMap ? 'selected' : '';
            options += `<option value="${src}" ${selected}>${src}</option>`;
        });
        
        row.innerHTML = `
            <div class="col-name">${col.name}</div>
            <div class="col-type">${col.type}${col.deprecated ? ' (dep)' : ''}</div>
            <div class="col-mapping">
                <div class="mapping-input-wrapper" ondrop="dropHandler(event, '${col.name}')" ondragover="allowDrop(event)">
                    <select class="combo-select" id="select-${col.name}">
                        ${options}
                    </select>
                </div>
            </div>
        `;
        container.appendChild(row);
        
        // Event Listener for change
        const select = row.querySelector(`#select-${col.name}`);
        select.onchange = (e) => onUpdate(col.name, e.target.value);
        
        // Helper to bind the closure
        select.dataset.targetCol = col.name;
    });
}

// --- Drag & Drop ---
function allowDrop(ev) { ev.preventDefault(); }
function dropHandler(ev, targetCol) {
    ev.preventDefault();
    const srcField = ev.dataTransfer.getData('text');
    
    // Find select element in this row
    // ev.target might be the wrapper or select
    let wrapper = ev.target.closest('.mapping-input-wrapper');
    let select = wrapper.querySelector('select');
    
    if (select) {
        select.value = srcField;
        select.onchange({ target: select }); // Trigger update
    }
}

// --- Actions ---

function renderTargetSchemas() {
    // Save current condition before switching?
    // Actually, onchange logic of inputs updates state immediately? 
    // No, condition input needs explicit saving or listener.
    // Let's add listener to condition input.
    renderSubSchema();
}

// Condition input listener
document.getElementById('condition-expr').addEventListener('input', (e) => {
    const subTable = document.getElementById('sub-schema-select').value;
    if (subTable && subRules[subTable]) {
        subRules[subTable].condition = e.target.value;
    }
});

function autoMap() {
    // Simple heuristic
    const subTable = document.getElementById('sub-schema-select').value;
    // Map common
    autoMapLogic(schemaMetadata.commonSchema, commonMappings, (t,s) => commonMappings[t]=s);
    renderCommonSchema();
    
    // Map sub
    if (subTable && subRules[subTable]) {
        autoMapLogic(schemaMetadata.subSchemas[subTable], subRules[subTable].mappings, (t,s) => subRules[subTable].mappings[t]=s);
        renderSubSchema();
    }
}

function autoMapLogic(columns, state, updateFn) {
    columns.forEach(col => {
        if (state[col.name]) return; // already mapped
        // Find match in sourceFields
        const match = sourceFields.find(src => src.includes(col.name) || col.name.includes(src));
        if (match) updateFn(col.name, match);
    });
}

function resetMapping() {
    if(confirm("Reset all mappings?")) {
        commonMappings = {};
        subRules = {};
        renderCommonSchema();
        renderSubSchema();
    }
}

// --- API Interactions ---

async function loadMapping() {
    const type = document.getElementById('message-type').value;
    try {
        const res = await fetch(`${API_BASE}/mapping/${type}`);
        if (res.status === 404) {
            alert("No mapping found for " + type);
            return;
        }
        const config = await res.json();
        
        // Parse config to UI state
        commonMappings = {};
        config.commonMappings.forEach(m => commonMappings[m.targetField] = m.sourceField);
        
        subRules = {};
        if (config.subTableRules) {
            config.subTableRules.forEach(r => {
                const mappings = {};
                r.mappings.forEach(m => mappings[m.targetField] = m.sourceField);
                subRules[r.targetSubTable] = {
                    condition: r.conditionExpression,
                    mappings: mappings
                };
            });
        }
        
        // Update Source fields from config?
        // Ideally we should merge known sources.
        const allSources = new Set(sourceFields);
        config.commonMappings.forEach(m => allSources.add(m.sourceField));
        config.subTableRules.forEach(r => r.mappings.forEach(m => allSources.add(m.sourceField)));
        sourceFields = Array.from(allSources);
        renderSourceList();
        
        renderCommonSchema();
        renderSubSchema();
        alert("Loaded!");
    } catch (e) {
        console.error(e);
        alert("Error loading");
    }
}

async function saveMapping() {
    const type = document.getElementById('message-type').value;
    
    const commonList = Object.keys(commonMappings).map(t => ({
        sourceField: commonMappings[t],
        targetField: t
    }));
    
    const rulesList = [];
    Object.keys(subRules).forEach(sub => {
        const rule = subRules[sub];
        if (!rule.mappings || Object.keys(rule.mappings).length === 0) return; // Skip empty rules?
        
        const mList = Object.keys(rule.mappings).map(t => ({
            sourceField: rule.mappings[t],
            targetField: t
        }));
        
        rulesList.push({
            targetSubTable: sub,
            conditionExpression: rule.condition,
            mappings: mList
        });
    });
    
    const config = {
        messageType: type,
        commonMappings: commonList,
        subTableRules: rulesList
    };
    
    try {
        await fetch(`${API_BASE}/mapping`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(config)
        });
        alert("Saved!");
    } catch (e) {
        console.error(e);
        alert("Error saving");
    }
}

async function runSimulation() {
    const type = document.getElementById('message-type').value;
    let sampleData = {};
    try {
        sampleData = JSON.parse(document.getElementById('sample-log-data').value || "{}");
    } catch (e) {
        alert("Invalid JSON in sample data");
        return;
    }

    // Construct temp config from UI state (same as save)
    const commonList = Object.keys(commonMappings).map(t => ({
        sourceField: commonMappings[t], targetField: t
    }));
    const rulesList = [];
    Object.keys(subRules).forEach(sub => {
        const rule = subRules[sub];
        const mList = Object.keys(rule.mappings).map(t => ({
            sourceField: rule.mappings[t], targetField: t
        }));
        rulesList.push({
            targetSubTable: sub,
            conditionExpression: rule.condition,
            mappings: mList
        });
    });
    const tempConfig = {
        messageType: type,
        commonMappings: commonList,
        subTableRules: rulesList
    };

    try {
        const res = await fetch(`${API_BASE}/simulate`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                messageType: type,
                sampleData: sampleData,
                temporaryConfig: tempConfig
            })
        });
        const result = await res.json();
        document.getElementById('sim-result').innerText = JSON.stringify(result, null, 2);
    } catch (e) {
        console.error(e);
        alert("Simulation failed");
    }
}

// Start
init();
