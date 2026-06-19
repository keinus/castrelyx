// Main Application Logic
const App = (function() {
    
    // State
    const state = {
        currentView: 'overview',
        currentAdapterType: 'input', // input, parser, transform, output
        currentListFilter: 'all',    // all, enabled, disabled
        refreshInterval: null,
        chartInstance: null,
        chartDataBuffer: [],
        maxChartPoints: 60,
        adapterCache: {
            input: [], parser: [], transform: [], output: []
        },
        outputMetricsById: {},
        editingId: null,
        schemaMapRendered: false,
        schemaTemplates: []
    };

    // --- Initialization ---
    async function init() {
        console.log('Initializing LogParser UI...');
        
        // Initial Data Load
        await Promise.all([
            loadDashboardStats(),
            loadMetadata(),
            initChart()
        ]);
        
        // Start Polling
        startPolling();
        
        // Setup Search Debounce if needed (currently using onkeyup directly)
        
        // Initialize Live Tail
        connectLiveTail();
    }
    
    function startPolling() {
        // Poll status every 2 seconds
        setInterval(async () => {
            if (state.currentView === 'overview') {
                await loadDashboardStats();
            }
        }, 2000);
    }

    // --- Navigation ---
    function switchView(viewName) {
        state.currentView = viewName;
        
        // Update Sidebar Active State
        document.querySelectorAll('aside nav a').forEach(el => {
            el.classList.remove('sidebar-active', 'text-blue-500', 'bg-blue-500/10');
            el.classList.add('text-slate-400');
        });

        // Determine which nav ID to highlight
        let navId = 'nav-' + viewName;
        if (viewName === 'inputs') { navId = 'nav-input'; state.currentAdapterType = 'input'; }
        else if (viewName === 'outputs') { navId = 'nav-output'; state.currentAdapterType = 'output'; }
        else if (viewName === 'parser') { navId = 'nav-parser'; state.currentAdapterType = 'parser'; }
        else if (viewName === 'transform') { navId = 'nav-transform'; state.currentAdapterType = 'transform'; }
        else if (viewName === 'schema-map') { navId = 'nav-schema-map'; }
        
        const activeNav = document.getElementById(navId);
        if (activeNav) {
            activeNav.classList.add('sidebar-active');
            activeNav.classList.remove('text-slate-400');
        }

        // Update Header Title
        const titles = {
            'overview': 'Overview',
            'live-tail': 'Live Data Tail',
            'pipeline-visual': 'Pipeline Visualization',
            'inputs': 'Data Sources',
            'parser': 'Parsers',
            'transform': 'Event Rules',
            'schema-map': 'Structured Schema Mapping',
            'outputs': 'Destinations',
            'settings': 'System Settings'
        };
        document.getElementById('page-title').textContent = titles[viewName] || 'Dashboard';

        // Hide all views
        document.querySelectorAll('[id^="view-"]').forEach(el => el.classList.add('hidden'));

        // Show target view
        if (['inputs', 'outputs', 'parser', 'transform'].includes(viewName)) {
            document.getElementById('view-list-generic').classList.remove('hidden');
            // Reset filter on view switch
            state.currentListFilter = 'all';
            updateFilterUI();
            loadAdapterList(state.currentAdapterType);
        } else {
            const target = document.getElementById('view-' + viewName);
            if (target) target.classList.remove('hidden');
            
            if (viewName === 'overview') {
                initChart(); // Re-render chart if canvas was destroyed/hidden
            } else if (viewName === 'pipeline-visual') {
                 renderTopology();
            } else if (viewName === 'schema-map') {
                 initSchemaMapView();
            } else if (viewName === 'settings') {
                 loadSettings();
            }
        }
    }

    // --- Dashboard & Monitoring ---
    async function loadDashboardStats() {
        try {
            const status = await pipelineAPI.getStatus();
            const threads = await pipelineAPI.getThreads();
            
            // Text Metrics
            document.getElementById('stat-components').textContent = 
                (status.inputAdapterCount || 0) + (status.parserCount || 0) + 
                (status.transformCount || 0) + (status.outputAdapterCount || 0);
            
            const throughput = status.throughput !== undefined ? parseFloat(status.throughput).toFixed(1) : "0.0";
            document.getElementById('stat-throughput').textContent = `${throughput}/s`;
            
            document.getElementById('stat-queue').textContent = status.queueSize || 0;
            document.getElementById('queue-progress').value = status.queueSize || 0;
            document.getElementById('queue-progress').max = status.queueCapacity || 10000;
            
            document.getElementById('stat-threads').textContent = threads.length || 0;
            
            // Update Sidebar Badges (Global Health Check)
            document.getElementById('badge-input').textContent = status.inputAdapterCount || 0;
            document.getElementById('badge-parser').textContent = status.parserCount || 0;
            document.getElementById('badge-transform').textContent = status.transformCount || 0;
            document.getElementById('badge-output').textContent = status.outputAdapterCount || 0;
            
            // Status Pill
            const pill = document.getElementById('status-pill');
            const pillText = document.getElementById('pipeline-status-text');
            pillText.textContent = status.status;
            
            pill.className = `badge badge-outline gap-2 ml-2 ${status.status === 'RUNNING' ? 'badge-success' : 'badge-error'}`;
            
            // Footer Stats
            document.getElementById('threads-count').textContent = `Threads: ${threads.length}`;
            // Mem usage is not in API currently, placeholder
            
            // Update Chart Buffer
            updateChart(throughput);

            // Update Breakdown List
            updateBreakdownList(status);
            
            // Update Thread List
            renderThreadList(threads);

        } catch (e) {
            console.error("Failed to load dashboard stats", e);
        }
    }
    
    function renderThreadList(threads) {
        const tbody = document.getElementById('thread-list-body');
        if (!tbody) return;
        
        if (!threads || threads.length === 0) {
            tbody.innerHTML = `<tr><td colspan="5" class="text-center py-4 text-slate-500">No active managed threads found.</td></tr>`;
            return;
        }

        tbody.innerHTML = threads.map(t => {
            const stateClass = t.state === 'RUNNABLE' ? 'text-emerald-400' : 
                             (t.state === 'WAITING' || t.state === 'TIMED_WAITING') ? 'text-amber-400' : 'text-slate-400';
            
            const typeColors = {
                'INPUT': 'badge-info',
                'OUTPUT': 'badge-success', // emerald
                'PARSER': 'badge-secondary', // purple-ish usually
                'TRANSFORM': 'badge-warning',
                'BATCH': 'badge-ghost',
                'MONITOR': 'badge-neutral'
            };
            const typeBadge = typeColors[t.componentType] || 'badge-ghost';
            
            return `
            <tr class="hover:bg-slate-800/50 border-b border-slate-800/50">
                <td><span class="badge badge-xs ${typeBadge} badge-outline font-bold">${t.componentType || 'SYSTEM'}</span></td>
                <td>
                    <div class="font-bold text-slate-200">${t.componentName || t.name}</div>
                    ${t.componentName ? `<div class="text-[10px] text-slate-500">${t.name}</div>` : ''}
                </td>
                <td class="${stateClass} font-bold text-xs">${t.state}</td>
                <td class="text-slate-500 text-xs">#${t.threadId}</td>
                <td class="text-right">
                    <span class="inline-block w-2 h-2 rounded-full ${t.alive ? 'bg-emerald-500' : 'bg-rose-500'}"></span>
                </td>
            </tr>
            `;
        }).join('');
    }
    
    function updateBreakdownList(status) {
        const list = document.getElementById('pipeline-breakdown-list');
        if (!list) return;
        
        const items = [
            { label: 'Inputs', count: status.inputAdapterCount, color: 'emerald' },
            { label: 'Parsers', count: status.parserCount, color: 'emerald' },
            { label: 'Transforms', count: status.transformCount, color: 'emerald' },
            { label: 'Outputs', count: status.outputAdapterCount, color: 'amber' }
        ];
        
        list.innerHTML = items.map(item => `
            <div class="flex items-center justify-between p-3 rounded-lg bg-slate-800/50 border border-slate-700/50">
                <div class="flex items-center">
                    <div class="w-2 h-2 rounded-full bg-${item.color}-500 mr-3"></div>
                    <span class="text-sm font-medium text-slate-300">${item.label}</span>
                </div>
                <span class="text-xs text-slate-400 font-mono">${item.count} Active</span>
            </div>
        `).join('');
    }

    function initChart() {
        const ctx = document.getElementById('trafficChart');
        if (!ctx) return;
        
        if (state.chartInstance) {
            // Already initialized
            return;
        }

        const gradient = ctx.getContext('2d').createLinearGradient(0, 0, 0, 400);
        gradient.addColorStop(0, 'rgba(59, 130, 246, 0.5)'); 
        gradient.addColorStop(1, 'rgba(59, 130, 246, 0.0)');

        state.chartInstance = new Chart(ctx, {
            type: 'line',
            data: {
                labels: Array(60).fill(''),
                datasets: [{
                    label: 'Events Per Second',
                    data: Array(60).fill(0),
                    borderColor: '#3b82f6',
                    backgroundColor: gradient,
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4,
                    pointRadius: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false, // Performance
                plugins: { legend: { display: false } },
                scales: {
                    x: { display: false },
                    y: { 
                        grid: { color: '#1e293b' },
                        ticks: { color: '#64748b' },
                        beginAtZero: true
                    }
                }
            }
        });
    }

    function updateChart(value) {
        if (!state.chartInstance) return;
        
        const data = state.chartInstance.data.datasets[0].data;
        data.push(value);
        data.shift();
        state.chartInstance.update();
    }
    
    // --- Topology Visual ---
    async function renderTopology() {
        const container = document.getElementById('view-pipeline-visual');
        container.innerHTML = '<div class="flex h-full items-center justify-center"><span class="loading loading-spinner loading-lg text-primary"></span></div>';
        container.classList.remove('hidden');

        try {
            const topologyData = await pipelineAPI.getTopology();
            
            if (!topologyData || topologyData.length === 0) {
                container.innerHTML = `
                    <div class="flex flex-col items-center justify-center h-full text-slate-500">
                        <span class="material-icons-round text-4xl mb-2">schema</span>
                        <p>No pipeline configuration found.</p>
                        <button class="btn btn-sm btn-primary mt-4" onclick="App.openCreateModal()">Create Adapter</button>
                    </div>`;
                return;
            }

            let html = `
                <div class="flex justify-between items-center mb-6">
                     <div>
                        <h3 class="text-lg font-bold text-white">Pipeline Topology</h3>
                        <p class="text-xs text-slate-500 font-mono mt-1">Grouped by Message Type</p>
                     </div>
                     <div class="flex gap-2 text-xs font-mono text-slate-500">
                        <span class="flex items-center gap-1"><div class="w-2 h-2 bg-blue-500 rounded-full"></div> Input</span>
                        <span class="flex items-center gap-1"><div class="w-2 h-2 bg-purple-500 rounded-full"></div> Process</span>
                        <span class="flex items-center gap-1"><div class="w-2 h-2 bg-emerald-500 rounded-full"></div> Output</span>
                     </div>
                </div>
                <div class="space-y-8 pb-10">
            `;

            topologyData.forEach(pipe => {
                html += `
                <!-- Pipeline Row: ${pipe.messageType} -->
                <div class="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm hover:border-slate-700 transition-colors">
                    
                    <!-- Header -->
                    <div class="bg-slate-800/50 px-6 py-3 border-b border-slate-800 flex justify-between items-center">
                        <div class="flex items-center gap-3">
                            <span class="material-icons-round text-slate-400">hub</span>
                            <div>
                                <h4 class="font-bold text-slate-200 text-sm tracking-wide">${pipe.messageType}</h4>
                                <p class="text-[10px] text-slate-500 font-mono">${pipe.description || ''}</p>
                            </div>
                        </div>
                        <span class="badge badge-ghost badge-sm font-mono">Active</span>
                    </div>

                    <!-- Swimlane Body -->
                    <div class="p-6 relative">
                        <!-- Grid Layout: Sources -> Arrow -> Process -> Arrow -> Destinations -->
                        <div class="grid grid-cols-1 lg:grid-cols-[1fr_auto_1.5fr_auto_1fr] gap-6 items-start">
                            
                            <!-- 1. Sources Column -->
                            <div class="flex flex-col gap-3 relative group">
                                <span class="absolute -top-3 left-0 text-[10px] text-slate-500 font-mono uppercase tracking-wider">Sources</span>
                                ${pipe.inputs && pipe.inputs.length > 0 ? pipe.inputs.map(i => `
                                    <div class="bg-slate-800 border-l-2 ${i.enabled ? 'border-blue-500' : 'border-slate-600'} p-3 rounded shadow-sm flex items-center justify-between hover:bg-slate-750 cursor-pointer" onclick="App.editAdapter('input', ${i.id})">
                                        <div>
                                            <div class="text-xs font-bold ${i.enabled ? 'text-slate-300' : 'text-slate-500 line-through'}">${i.name}</div>
                                            <div class="text-[10px] text-slate-500 font-mono">${i.detail || ''}</div>
                                        </div>
                                        <span class="material-icons-round text-slate-600 text-sm">input</span>
                                    </div>
                                `).join('') : '<div class="text-xs text-slate-600 italic py-2">No inputs configured</div>'}
                            </div>

                            <!-- Connector Arrow -->
                            <div class="hidden lg:flex h-full items-center justify-center text-slate-700">
                                 <span class="material-icons-round text-2xl ${pipe.inputs.some(i => i.enabled) ? 'text-blue-500/50' : ''}">arrow_right_alt</span>
                            </div>

                            <!-- 2. Processing Chain Column -->
                            <div class="flex flex-col gap-2 relative">
                                <span class="absolute -top-3 left-0 text-[10px] text-slate-500 font-mono uppercase tracking-wider">Processing Chain</span>
                                
                                <div class="bg-slate-800/30 rounded-lg p-3 border border-slate-700/50 flex flex-wrap gap-2 items-center min-h-[60px]">
                                    ${pipe.processing && pipe.processing.length > 0 ? pipe.processing.map((p, idx) => `
                                        <div class="flex items-center bg-slate-800 border ${p.enabled ? 'border-slate-600' : 'border-slate-700 opacity-50'} rounded px-2 py-1 shadow-sm cursor-pointer hover:border-slate-500" onclick="${getProcessingStageClickHandler(p, pipe.messageType)}">
                                            <span class="text-[10px] font-bold ${getProcessingBadgeClass(p)} mr-2">${p.badge}</span>
                                            <span class="text-xs text-slate-300">${p.type}</span>
                                        </div>
                                        ${idx < pipe.processing.length - 1 ? `<span class="material-icons-round text-slate-600 text-sm">chevron_right</span>` : ''}
                                    `).join('') : '<span class="text-xs text-slate-600 italic">Pass-through (No processing)</span>'}
                                </div>
                            </div>

                            <!-- Connector Arrow -->
                            <div class="hidden lg:flex h-full items-center justify-center text-slate-700">
                                 <span class="material-icons-round text-2xl ${pipe.outputs.some(o => o.enabled) ? 'text-emerald-500/50' : ''}">arrow_right_alt</span>
                            </div>

                            <!-- 3. Destinations Column -->
                            <div class="flex flex-col gap-3 relative">
                                <span class="absolute -top-3 left-0 text-[10px] text-slate-500 font-mono uppercase tracking-wider">Destinations</span>
                                ${pipe.outputs && pipe.outputs.length > 0 ? pipe.outputs.map(o => `
                                    <div class="bg-slate-800 border-r-2 ${o.enabled ? 'border-emerald-500' : 'border-slate-600'} p-3 rounded shadow-sm flex items-center justify-between hover:bg-slate-750 cursor-pointer text-right" onclick="App.editAdapter('output', ${o.id})">
                                        <span class="material-icons-round text-slate-600 text-sm">output</span>
                                        <div>
                                            <div class="text-xs font-bold ${o.enabled ? 'text-slate-300' : 'text-slate-500 line-through'}">${o.name}</div>
                                            <div class="text-[10px] text-slate-500 font-mono">${o.detail || ''}</div>
                                        </div>
                                    </div>
                                `).join('') : '<div class="text-xs text-slate-600 italic py-2 text-right">No outputs configured</div>'}
                            </div>

                        </div>
                    </div>
                </div>
                `;
            });

            html += `</div>`;
            container.innerHTML = html;

        } catch (e) {
            console.error("Failed to load topology:", e);
            container.innerHTML = `
                <div class="alert alert-error max-w-lg mx-auto mt-10">
                    <span class="material-icons-round">error</span>
                    <span>Failed to load topology: ${e.message}</span>
                    <button class="btn btn-sm" onclick="App.renderTopology()">Retry</button>
                </div>
            `;
        }
    }

    function getProcessingStageClickHandler(stage, messageType) {
        if (stage.badge === 'SCHEMA') {
            return `App.openSchemaMapForMessageType('${escapeJsString(messageType)}')`;
        }

        const adapterType = isParserStage(stage) ? 'parser' : 'transform';
        return `App.editAdapter('${adapterType}', ${stage.id})`;
    }

    function isParserStage(stage) {
        return ['PARSER', 'GROK', 'JSON', 'REGEX'].includes(stage.badge);
    }

    function getProcessingBadgeClass(stage) {
        if (stage.badge === 'SCHEMA') return 'text-blue-400';
        return isParserStage(stage) ? 'text-purple-400' : 'text-amber-400';
    }

    function escapeJsString(value) {
        return String(value == null ? '' : value)
            .replace(/\\/g, '\\\\')
            .replace(/'/g, "\\'");
    }
    
    // --- Topology Visual ---
    async function updateTopologyCounts() {
        try {
            const status = await pipelineAPI.getStatus();
            document.getElementById('topo-input-count').textContent = (status.inputAdapterCount || 0) + ' Adapters';
            document.getElementById('topo-parser-count').textContent = (status.parserCount || 0) + ' Parsers';
            document.getElementById('topo-transform-count').textContent = (status.transformCount || 0) + ' Transforms';
            document.getElementById('topo-output-count').textContent = (status.outputAdapterCount || 0) + ' Outputs';
        } catch (e) {}
    }

    // --- Generic List View (CRUD) ---
    function setFilter(filter) {
        state.currentListFilter = filter;
        updateFilterUI();
        // Re-render using cache
        const type = state.currentAdapterType;
        renderList(state.adapterCache[type] || [], type);
    }

    function updateFilterUI() {
        const filters = ['all', 'enabled', 'disabled'];
        filters.forEach(f => {
            const el = document.getElementById('tab-' + f);
            if (el) {
                if (f === state.currentListFilter) {
                    el.classList.add('tab-active', 'text-white');
                    el.classList.remove('text-slate-400');
                } else {
                    el.classList.remove('tab-active', 'text-white');
                    el.classList.add('text-slate-400');
                }
            }
        });
    }

    async function loadAdapterList(type) {
        try {
            const apiMap = {
                'input': inputAdapterAPI,
                'parser': parserAPI,
                'transform': transformAPI,
                'output': outputAdapterAPI
            };
            
            const [response, outputMetrics] = await Promise.all([
                apiMap[type].getAll(),
                type === 'output'
                    ? pipelineAPI.getOutputMetrics().catch(error => {
                        console.warn('Failed to load output metrics', error);
                        return [];
                    })
                    : Promise.resolve([])
            ]);
            const metricsById = {};
            (outputMetrics || []).forEach(metric => {
                metricsById[metric.adapterId] = metric;
            });
            state.outputMetricsById = metricsById;

            const list = (response.content || []).map(item => ({
                ...item,
                deliveryMetrics: type === 'output' ? metricsById[item.id] || null : null
            }));
            state.adapterCache[type] = list; // Cache for search
            
            // Update Badge
            document.getElementById(`badge-${type}`).textContent = list.length;

            const configHeader = document.getElementById('generic-config-header');
            if (configHeader) {
                configHeader.textContent = type === 'output' ? 'Configuration / Metrics' : 'Configuration';
            }
            
            renderList(list, type);
        } catch (e) {
            showToast("Failed to load list: " + e.message, "error");
        }
    }

    function renderList(items, type) {
        const tbody = document.getElementById('generic-list-body');
        tbody.innerHTML = '';
        
        // Filter Logic
        let filteredItems = items;
        if (state.currentListFilter === 'enabled') {
            filteredItems = items.filter(i => i.enabled !== false);
        } else if (state.currentListFilter === 'disabled') {
            filteredItems = items.filter(i => i.enabled === false);
        }
        
        if (filteredItems.length === 0) {
            tbody.innerHTML = `<tr><td colspan="5" class="text-center py-8 text-slate-500">No matching items found.</td></tr>`;
            return;
        }

        filteredItems.forEach(item => {
            const enabled = (item.enabled !== false); // Default true usually, depends on schema
            // Parsers/Transforms usually don't have 'enabled' field in DTO unless added recently, assume active
            // Actually API supports enable/disable for all.
            
            const statusColor = enabled ? 'text-emerald-500' : 'text-slate-500';
            const statusDot = enabled ? 'bg-emerald-500' : 'bg-slate-500';
            const statusText = enabled ? 'Active' : 'Disabled';

            // Extract key config info for display
            let configSummary = getConfigSummary(item);
            let metricsSummary = '';
            if (type === 'output' && item.deliveryMetrics) {
                const sent = item.deliveryMetrics.sentCount || 0;
                const failed = item.deliveryMetrics.failedCount || 0;
                metricsSummary = `Sent: ${sent} · Failed: ${failed}`;
                if (item.deliveryMetrics.lastLatencyMs != null) {
                    metricsSummary += ` · Last Latency: ${item.deliveryMetrics.lastLatencyMs}ms`;
                }
                if (item.deliveryMetrics.averageLatencyMs != null) {
                    metricsSummary += ` · Avg Latency: ${item.deliveryMetrics.averageLatencyMs.toFixed(1)}ms`;
                }
                if (item.deliveryMetrics.lastError) {
                    metricsSummary += ` · Last Error: ${item.deliveryMetrics.lastError}`;
                }
            }

            const tr = document.createElement('tr');
            tr.className = 'hover:bg-slate-800/50 transition-colors border-b border-slate-800';
            tr.innerHTML = `
                <td>
                    <div class="flex items-center gap-3">
                        ${(type === 'input' || type === 'output') ? `
                        <label class="cursor-pointer flex items-center gap-2 group">
                            <input type="checkbox" class="toggle toggle-success toggle-sm" 
                                ${enabled ? 'checked' : ''} 
                                onchange="App.toggleAdapter('${type}', ${item.id}, this.checked)" />
                            <span class="text-xs font-mono font-bold ${enabled ? 'text-emerald-400' : 'text-slate-500 group-hover:text-slate-300'} transition-colors">
                                ${enabled ? 'ON' : 'OFF'}
                            </span>
                        </label>
                        ` : `
                        <div class="flex items-center gap-2">
                            <span class="w-2 h-2 rounded-full ${statusDot}"></span>
                            <span class="capitalize text-slate-300 text-xs">${statusText}</span>
                        </div>
                        `}
                    </div>
                </td>
                <td>
                    <div class="font-bold text-white">${item.messagetype}</div>
                    <div class="text-xs text-slate-500 font-mono">ID: ${item.id}</div>
                </td>
                <td><span class="badge badge-ghost badge-sm font-mono">${item.type}</span></td>
                <td class="text-slate-400 font-mono text-xs max-w-xs" title="${[configSummary, metricsSummary].filter(Boolean).join(' | ')}">
                    <div class="truncate">${configSummary}</div>
                    ${metricsSummary ? `<div class="truncate text-[10px] ${item.deliveryMetrics.failedCount > 0 ? 'text-amber-400' : 'text-slate-500'}">${metricsSummary}</div>` : ''}
                </td>
                <td class="text-right">
                    <div class="flex items-center justify-end gap-1">
                        <div class="tooltip tooltip-left" data-tip="Edit Configuration">
                            <button class="btn btn-ghost btn-sm btn-square text-slate-400 hover:text-blue-400 hover:bg-blue-400/10 transition-all" onclick="App.editAdapter('${type}', ${item.id})">
                                <span class="material-icons-round text-lg">edit</span>
                            </button>
                        </div>
                        <div class="tooltip tooltip-left" data-tip="Delete Adapter">
                            <button class="btn btn-ghost btn-sm btn-square text-slate-400 hover:text-rose-400 hover:bg-rose-400/10 transition-all" onclick="App.deleteAdapter('${type}', ${item.id})">
                                <span class="material-icons-round text-lg">delete</span>
                            </button>
                        </div>
                    </div>
                </td>
            `;
            tbody.appendChild(tr);
        });
    }

    function getConfigSummary(item) {
        if (item.host) return `Host: ${item.host}:${item.port}`;
        if (item.url) return `URL: ${item.url}`;
        if (item.topicid) return `Topic: ${item.topicid}`;
        if (item.param) {
            if (typeof item.param === 'string') return item.param;
            return JSON.stringify(item.param);
        }
        return '-';
    }

    // --- Search ---
    function handleSearch(query) {
        const type = state.currentAdapterType;
        const list = state.adapterCache[type] || [];
        const lower = query.toLowerCase();
        
        const filtered = list.filter(item => 
            (item.messagetype && item.messagetype.toLowerCase().includes(lower)) ||
            (item.type && item.type.toLowerCase().includes(lower)) ||
            (item.id && item.id.toString().includes(lower))
        );
        
        renderList(filtered, type);
    }

    // --- Modals & Forms ---
    async function loadMetadata() {
        // Pre-load types if needed, or load on demand
    }

    async function openCreateModal(isEdit = false) {
        if (!isEdit) {
            state.editingId = null;
            document.getElementById('modal-title').textContent = `Add ${capitalize(state.currentAdapterType)}`;
            document.getElementById('config-form').reset();
            document.getElementById('dynamic-fields').innerHTML = '';
        }
        
        document.getElementById('config_modal').showModal();
        
        // Populate Types
        const typeSelect = document.getElementById('config-type');
        // Save current selection if editing
        const currentSelection = typeSelect.value;
        
        typeSelect.innerHTML = '<option value="">Loading...</option>';
        
        try {
            const typeMap = {
                'input': metadataAPI.getInputAdapterTypes,
                'parser': metadataAPI.getParserTypes,
                'transform': metadataAPI.getTransformTypes,
                'output': metadataAPI.getOutputAdapterTypes
            };
            
            const types = await typeMap[state.currentAdapterType]();
            typeSelect.innerHTML = '<option value="">Select Type</option>' + 
                types.map(t => `<option value="${t.className || t.type}">${t.displayName || t.type}</option>`).join('');
                
            // Restore selection if it exists in new options
            if (currentSelection) {
                typeSelect.value = currentSelection;
            }
        } catch (e) {
            console.error("Failed to load adapter types", e);
            typeSelect.innerHTML = '<option value="">Error loading types</option>';
        }

        // Toggle Enabled Switch Visibility (Parsers/Transforms usually always enabled)
        document.getElementById('enabled-group').style.display = 
            (state.currentAdapterType === 'input' || state.currentAdapterType === 'output') ? 'block' : 'none';
    }

    async function editAdapter(type, id) {
        state.currentAdapterType = type; // Safety sync
        
        try {
            const apiMap = {
                'input': inputAdapterAPI,
                'parser': parserAPI,
                'transform': transformAPI,
                'output': outputAdapterAPI
            };
            
            const data = await apiMap[type].getById(id);
            console.log("Loaded adapter data:", data);
            
            // Open Modal in Edit Mode
            await openCreateModal(true);
            
            state.editingId = id; // Set after modal opens (and potentially resets)
            document.getElementById('modal-title').textContent = `Edit ${capitalize(type)}`;
            
            // Fill Basic Fields
            if (document.getElementById('config-messagetype')) document.getElementById('config-messagetype').value = data.messagetype || '';
            if (document.getElementById('config-type')) document.getElementById('config-type').value = data.type || '';
            
            if (data.enabled !== undefined && document.getElementById('config-enabled')) {
                 document.getElementById('config-enabled').checked = data.enabled;
            }

            // Load Schema & Dynamic Fields
            await loadSchema(data.type);
            
            // Fill Dynamic Fields
            // Wait for DOM update
            setTimeout(() => {
                // General Population
                Object.entries(data).forEach(([key, value]) => {
                     // If it's a param object (Transform generic), flatten or handle?
                     // Usually Transforms have `param` as Map. Parsers have `param` as String.
                     if (key === 'param' && typeof value === 'object' && value !== null) {
                         Object.entries(value).forEach(([k, v]) => {
                             const field = document.querySelector(`[name="${k}"]`);
                             if (field) {
                                 if (field.type === 'checkbox') field.checked = v === true || v === 'true';
                                 else field.value = v;
                             }
                         });
                     } else {
                         const field = document.querySelector(`[name="${key}"]`);
                         if (field) {
                             if (field.type === 'checkbox') field.checked = value === true || value === 'true';
                             else field.value = value;
                         }
                     }
                });
            }, 100);
            
        } catch (e) {
            console.error("Edit adapter failed:", e);
            showToast("Failed to load adapter details: " + e.message, "error");
        }
    }

    async function loadSchema(adapterType) {
        if (!adapterType) return;
        
        const container = document.getElementById('dynamic-fields');
        container.innerHTML = '<div class="text-center text-slate-500"><span class="loading loading-dots"></span></div>';
        
        try {
            let schema;
            const type = state.currentAdapterType;
            if (type === 'input') schema = await metadataAPI.getInputAdapterSchema(adapterType);
            else if (type === 'parser') schema = await metadataAPI.getParserSchema(adapterType);
            else if (type === 'transform') schema = await metadataAPI.getTransformSchema(adapterType);
            else if (type === 'output') schema = await metadataAPI.getOutputAdapterSchema(adapterType);
            
            // Generic Render
            if (!schema || !schema.fields || schema.fields.length === 0) {
                container.innerHTML = '<p class="text-slate-500 text-sm">No additional configuration required.</p>';
                return;
            }

            container.innerHTML = schema.fields.map(field => {
                 let inputHtml = '';
                 const required = field.required ? 'required' : '';
                 
                 // Map Types to HTML
                 const fieldType = field.dataType || field.type;
                 if (fieldType === 'Boolean') {
                     inputHtml = `
                        <select name="${field.name}" class="select select-bordered bg-slate-800 text-white w-full" ${required}>
                            <option value="true">True</option>
                            <option value="false">False</option>
                        </select>`;
                 } else if (field.choices && field.choices.length > 0) {
                     inputHtml = `
                        <select name="${field.name}" class="select select-bordered bg-slate-800 text-white w-full" ${required}>
                            ${field.choices.map(c => `<option value="${c}">${c}</option>`).join('')}
                        </select>`;
                 } else {
                     inputHtml = `<input type="text" name="${field.name}" class="input input-bordered bg-slate-800 text-white w-full" ${required} />`;
                 }

                 return `
                    <div class="form-control">
                        <label class="label">
                            <span class="label-text text-slate-300 capitalize">${formatLabel(field.name)}</span>
                        </label>
                        ${inputHtml}
                        ${field.description ? `<label class="label"><span class="label-text-alt text-slate-500">${field.description}</span></label>` : ''}
                    </div>
                 `;
            }).join('');
            
            // Add Test Pattern UI for Grok/Regex Parsers
            if (state.currentAdapterType === 'parser' && 
               (adapterType.includes('Grok') || adapterType.includes('Regex'))) {
                const testUiHtml = `
                    <div class="divider border-slate-700"></div>
                    <div class="bg-slate-800/50 p-4 rounded-lg border border-slate-700">
                        <h4 class="font-bold text-slate-300 mb-3 flex items-center gap-2">
                            <span class="material-icons-round text-blue-400">science</span>
                            Test Pattern
                        </h4>
                        <div class="form-control mb-3">
                            <label class="label">
                                <span class="label-text text-slate-400">Sample Log Data</span>
                            </label>
                            <textarea id="test-sample-data" class="textarea textarea-bordered bg-slate-900 font-mono text-xs h-24 text-slate-300 leading-relaxed" placeholder="Paste a log line here to test against the pattern above..."></textarea>
                        </div>
                        <button type="button" class="btn btn-sm btn-secondary mb-3" onclick="App.testPattern()">
                            Run Test
                        </button>
                        <div id="test-result-container" class="hidden">
                             <label class="label">
                                <span class="label-text text-slate-400">Result</span>
                            </label>
                            <div class="mockup-code bg-slate-950 text-xs p-0 border border-slate-800">
                                <pre id="test-result-content" class="text-emerald-400 p-4 block overflow-x-auto"></pre>
                            </div>
                        </div>
                    </div>
                `;
                container.insertAdjacentHTML('beforeend', testUiHtml);
            }
            
        } catch (e) {
            console.error("Load schema failed:", e);
            container.innerHTML = '<p class="text-rose-400">Failed to load configuration schema: ' + e.message + '</p>';
        }
    }
    
    async function testPattern() {
        const type = document.getElementById('config-type').value;
        const paramInput = document.querySelector('[name="param"]'); // pattern usually in 'param'
        const sampleData = document.getElementById('test-sample-data').value;
        const resultContainer = document.getElementById('test-result-container');
        const resultContent = document.getElementById('test-result-content');
        
        if (!paramInput || !paramInput.value) {
            showToast("Please enter a pattern first", "warning");
            return;
        }
        if (!sampleData) {
            showToast("Please enter sample data", "warning");
            return;
        }
        
        resultContainer.classList.remove('hidden');
        resultContent.textContent = "Testing...";
        resultContent.className = "text-slate-400 p-4 block overflow-x-auto";
        
        try {
            const result = await parserAPI.test({
                type: type,
                param: paramInput.value,
                sampleData: sampleData
            });
            
            resultContent.textContent = JSON.stringify(result, null, 2);
            resultContent.className = "text-emerald-400 p-4 block overflow-x-auto";
        } catch (e) {
            resultContent.textContent = "Error: " + e.message;
            resultContent.className = "text-rose-400 p-4 block overflow-x-auto";
        }
    }

    async function handleConfigSubmit(e) {
        e.preventDefault();
        
        const formData = new FormData(e.target);
        const data = {
            type: document.getElementById('config-type').value,
            messagetype: document.getElementById('config-messagetype').value,
        };
        
        if (state.currentAdapterType === 'input' || state.currentAdapterType === 'output') {
            data.enabled = document.getElementById('config-enabled').checked;
        }

        // Collect dynamic fields
        const dynamicFields = document.querySelectorAll('#dynamic-fields [name]');

        dynamicFields.forEach(field => {
            let val = field.value;
            // Basic type coercion if feasible, or let backend handle strings
            if (val === 'true') val = true;
            if (val === 'false') val = false;

            if (state.currentAdapterType === 'transform') {
                const transformFieldMap = {
                    pass: 'filterPass',
                    drop: 'filterDrop',
                    add: 'addProperties',
                    remove: 'removeProperties'
                };
                const mappedField = transformFieldMap[field.name];
                if (mappedField && val !== '') {
                    data[mappedField] = formatTransformFieldValue(field.name, val);
                }
            } else if (state.currentAdapterType === 'parser' && field.name === 'param') {
                data.param = val; // Grok/Regex pattern usually top level string in simple implementation, but DTO might expect it.
            } else {
                data[field.name] = val;
            }
        });

        try {
            const apiMap = {
                'input': inputAdapterAPI,
                'parser': parserAPI,
                'transform': transformAPI,
                'output': outputAdapterAPI
            };
            
            if (state.editingId) {
                await apiMap[state.currentAdapterType].update(state.editingId, data);
                showToast("Updated successfully", "success");
            } else {
                await apiMap[state.currentAdapterType].create(data);
                showToast("Created successfully", "success");
            }
            
            document.getElementById('config_modal').close();
            loadAdapterList(state.currentAdapterType);
            
        } catch (e) {
            showToast("Operation failed: " + e.message, "error");
        }
    }

    async function deleteAdapter(type, id) {
        if (!confirm("Are you sure you want to delete this configuration?")) return;
        try {
            const apiMap = {
                'input': inputAdapterAPI,
                'parser': parserAPI,
                'transform': transformAPI,
                'output': outputAdapterAPI
            };
            await apiMap[type].delete(id);
            showToast("Deleted successfully", "success");
            loadAdapterList(type);
        } catch (e) {
            showToast("Delete failed", "error");
        }
    }

    function formatTransformFieldValue(fieldName, value) {
        const trimmed = value.trim();
        if (fieldName === 'remove' && trimmed && !trimmed.startsWith('[')) {
            return JSON.stringify(trimmed.split(',').map(item => item.trim()).filter(Boolean));
        }
        return trimmed;
    }
    
    async function toggleAdapter(type, id, checked) {
        try {
            const apiMap = {
                'input': inputAdapterAPI,
                'output': outputAdapterAPI
            };
            if (!apiMap[type]) return; // Parsers/Transforms might not have generic toggle API in this codebase yet
            
            if (checked) await apiMap[type].enable(id);
            else await apiMap[type].disable(id);
            
            showToast(`Adapter ${checked ? 'enabled' : 'disabled'}`, "success");
        } catch (e) {
            showToast("Toggle failed", "error");
            // Revert UI?
            loadAdapterList(type);
        }
    }

    // --- Schema Map ---
    async function initSchemaMapView() {
        const container = document.getElementById('schema-map-container');
        if (!container) return;

        if (!state.schemaMapRendered) {
            MapperUI.render(container);
            state.schemaMapRendered = true;
        }

        await loadSchemaTemplates({ silent: true });

        if (!container.dataset.loaded) {
            await loadSchemaMapping();
        }
    }

    async function loadSchemaMapping() {
        const container = document.getElementById('schema-map-container');
        if (!container) return;

        if (!state.schemaMapRendered) {
            MapperUI.render(container);
            state.schemaMapRendered = true;
        }

        const input = document.getElementById('schema-map-message-type');
        const messageType = (input && input.value.trim()) || '';
        if (!messageType) {
            showToast("Message type is required", "error");
            return;
        }

        try {
            showToast("Loading schema map...", "info");
            const schemaMetadata = await structureAPI.getSchema();

            let existingConfig = null;
            try {
                existingConfig = await structureAPI.getMapping(messageType);
            } catch (e) {
                if (e.status !== 404) {
                    throw e;
                }
            }

            await MapperUI.loadData(messageType, existingConfig, schemaMetadata);
            container.dataset.loaded = 'true';
            showToast(existingConfig ? "Mapping loaded" : "Schema loaded", "success");
        } catch (e) {
            showToast("Failed to load schema map: " + e.message, "error");
        }
    }

    async function saveSchemaMapping() {
        if (!state.schemaMapRendered) {
            await initSchemaMapView();
        }

        const input = document.getElementById('schema-map-message-type');
        const messageType = (input && input.value.trim()) || '';
        const config = MapperUI.getData();
        config.messageType = messageType || config.messageType;

        if (!config.messageType) {
            showToast("Message type is required", "error");
            return;
        }

        try {
            await structureAPI.saveMapping(config);
            showToast("Mapping saved", "success");
        } catch (e) {
            showToast("Failed to save mapping: " + e.message, "error");
        }
    }

    async function loadSchemaTemplates(options = {}) {
        const select = document.getElementById('schema-template-select');
        if (!select) return;

        const selectedId = options.selectedId || select.value || '';
        try {
            const templates = await structureAPI.getTemplates();
            state.schemaTemplates = Array.isArray(templates) ? templates : [];
            renderSchemaTemplateSelect(selectedId);
            if (!options.silent) {
                showToast("Templates loaded", "success");
            }
        } catch (e) {
            state.schemaTemplates = [];
            renderSchemaTemplateSelect('');
            if (!options.silent) {
                showToast("Failed to load templates: " + e.message, "error");
            }
        }
    }

    function renderSchemaTemplateSelect(selectedId) {
        const select = document.getElementById('schema-template-select');
        if (!select) return;

        select.innerHTML = '';
        if (state.schemaTemplates.length === 0) {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = 'No templates';
            select.appendChild(option);
            select.disabled = true;
            return;
        }

        select.disabled = false;
        state.schemaTemplates.forEach(template => {
            const option = document.createElement('option');
            option.value = template.id;
            option.textContent = template.sourceMessageType
                ? `${template.name} (${template.sourceMessageType})`
                : template.name;
            option.selected = template.id === selectedId;
            select.appendChild(option);
        });
    }

    async function refreshSchemaTemplates() {
        await loadSchemaTemplates({ silent: false });
    }

    function getSelectedSchemaTemplate() {
        const select = document.getElementById('schema-template-select');
        const id = select ? select.value : '';
        return state.schemaTemplates.find(template => template.id === id) || null;
    }

    function currentSchemaMessageType() {
        const input = document.getElementById('schema-map-message-type');
        return (input && input.value.trim()) || '';
    }

    function currentSchemaMappingConfig() {
        if (!state.schemaMapRendered) {
            showToast("Schema map is not loaded", "error");
            return null;
        }

        const messageType = currentSchemaMessageType();
        if (!messageType) {
            showToast("Message type is required", "error");
            return null;
        }

        const config = MapperUI.getData();
        config.messageType = messageType;
        return config;
    }

    async function saveCurrentMappingAsTemplate() {
        const config = currentSchemaMappingConfig();
        if (!config) return;

        const suggestedName = `${config.messageType} template`;
        const name = prompt('Template name', suggestedName);
        if (name === null) return;

        const description = prompt('Template description', '') || '';
        try {
            const template = await structureAPI.createTemplate({
                name,
                description,
                sourceMessageType: config.messageType,
                config
            });
            await loadSchemaTemplates({ selectedId: template.id, silent: true });
            showToast("Template saved", "success");
        } catch (e) {
            showToast("Failed to save template: " + e.message, "error");
        }
    }

    async function updateSelectedSchemaTemplate() {
        const selected = getSelectedSchemaTemplate();
        if (!selected) {
            showToast("Select a template first", "error");
            return;
        }

        const config = currentSchemaMappingConfig();
        if (!config) return;

        const name = prompt('Template name', selected.name || '');
        if (name === null) return;

        const description = prompt('Template description', selected.description || '') || '';
        try {
            const updated = await structureAPI.updateTemplate(selected.id, {
                ...selected,
                name,
                description,
                sourceMessageType: config.messageType,
                config
            });
            await loadSchemaTemplates({ selectedId: updated.id, silent: true });
            showToast("Template updated", "success");
        } catch (e) {
            showToast("Failed to update template: " + e.message, "error");
        }
    }

    async function deleteSelectedSchemaTemplate() {
        const selected = getSelectedSchemaTemplate();
        if (!selected) {
            showToast("Select a template first", "error");
            return;
        }

        if (!confirm(`Delete template "${selected.name}"?`)) return;

        try {
            await structureAPI.deleteTemplate(selected.id);
            await loadSchemaTemplates({ silent: true });
            showToast("Template deleted", "success");
        } catch (e) {
            showToast("Failed to delete template: " + e.message, "error");
        }
    }

    async function applySchemaTemplate() {
        const selected = getSelectedSchemaTemplate();
        if (!selected) {
            showToast("Select a template first", "error");
            return;
        }

        const messageType = currentSchemaMessageType();
        if (!messageType) {
            showToast("Message type is required", "error");
            return;
        }

        if (!confirm(`Apply "${selected.name}" to "${messageType}" and overwrite the current mapping?`)) return;

        try {
            const config = await structureAPI.applyTemplate(selected.id, messageType);
            const schemaMetadata = await structureAPI.getSchema();
            await MapperUI.loadData(messageType, config, schemaMetadata);

            const container = document.getElementById('schema-map-container');
            if (container) {
                container.dataset.loaded = 'true';
            }

            showToast("Template applied", "success");
        } catch (e) {
            showToast("Failed to apply template: " + e.message, "error");
        }
    }

    function openSchemaMapForMessageType(messageType) {
        const input = document.getElementById('schema-map-message-type');
        if (input) {
            input.value = messageType || '';
        }

        const container = document.getElementById('schema-map-container');
        if (container) {
            delete container.dataset.loaded;
        }

        switchView('schema-map');
    }

    // --- Control Panel ---
    function openControlModal() {
        document.getElementById('control_modal').showModal();
    }
    
    async function reloadPipeline() {
        showToast("Reloading pipeline...", "info");
        try { await pipelineAPI.reload(); showToast("Reload signal sent", "success"); }
        catch(e) { showToast("Reload failed", "error"); }
    }
    
    async function validateAndReload() {
        showToast("Validating...", "info");
        try { await pipelineAPI.validateAndReload(); showToast("Validation passed & Reloaded", "success"); }
        catch(e) { showToast("Validation failed", "error"); }
    }
    
    async function restartPipeline() {
        if(!confirm("Full restart will drop current connections. Continue?")) return;
        showToast("Restarting...", "warning");
        try { await pipelineAPI.restart(); showToast("Restart signal sent", "success"); }
        catch(e) { showToast("Restart failed", "error"); }
    }

    // --- Settings ---
    async function loadSettings() {
        try {
            const val = await settingsAPI.get('parser_threads');
            if (val) document.getElementById('setting-threads').value = val;
        } catch (e) {}
    }
    
    async function saveSettings() {
        const val = document.getElementById('setting-threads').value;
        try {
            await settingsAPI.update('parser_threads', val, 'INTEGER');
            showToast("Settings saved", "success");
        } catch (e) { showToast("Save failed", "error"); }
    }

    // --- Live Tail ---
    let ws = null;
    let wsReconnectInterval = null;

    async function connectLiveTail() {
        // Init Toggle State
        try {
            const status = await pipelineAPI.getLiveTailStatus();
            const toggle = document.getElementById('livetail-service-toggle');
            if(toggle) toggle.checked = status.enabled;
        } catch (e) {
            console.error("Failed to fetch live tail status", e);
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = `${protocol}//${window.location.host}/ws/tail`;
        
        console.log(`Connecting to Live Tail WebSocket: ${url}`);
        ws = new WebSocket(url);

        ws.onopen = () => {
            console.log('Live Tail connected');
            const term = document.getElementById('terminal-window');
            term.innerHTML += `<div class="text-emerald-500 font-bold border-b border-emerald-900/50 pb-1"># Connected to log stream</div>`;
            
            if (wsReconnectInterval) {
                clearInterval(wsReconnectInterval);
                wsReconnectInterval = null;
            }
        };

        ws.onmessage = (event) => {
            const view = document.getElementById('view-live-tail');
            if (view.classList.contains('hidden')) return;
            
            const term = document.getElementById('terminal-window');
            if (term.getAttribute('data-paused') === 'true') return;
            
            try {
                const payload = JSON.parse(event.data);
                const now = new Date(payload.timestamp || Date.now()).toISOString();
                const type = payload.messageType || 'UNKNOWN';
                const dataStr = JSON.stringify(payload.data || payload);
                
                const log = `[${now}] ${type} ${dataStr}`;
                
                const line = document.createElement('div');
                line.className = 'text-slate-300 hover:bg-slate-800/50 px-1 py-0.5 border-b border-slate-800/30 break-all';
                line.textContent = log;
                term.appendChild(line);
                
                // Buffer limit
                while (term.children.length > 500) {
                    term.removeChild(term.children[0]);
                }
                
                // Auto-scroll if near bottom
                if (term.scrollHeight - term.scrollTop - term.clientHeight < 100) {
                    term.scrollTop = term.scrollHeight;
                }
                
            } catch (e) {
                console.warn('Failed to parse log event', e);
            }
        };

        ws.onclose = () => {
            console.log('Live Tail disconnected');
            const term = document.getElementById('terminal-window');
            if (term) term.innerHTML += `<div class="text-amber-500 font-bold border-b border-amber-900/50 pb-1"># Disconnected. Reconnecting...</div>`;
            
            if (!wsReconnectInterval) {
                wsReconnectInterval = setInterval(connectLiveTail, 3000);
            }
        };

        ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            ws.close();
        };
    }

    async function toggleLiveTailService(enabled) {
        try {
            if (enabled) {
                await pipelineAPI.enableLiveTail();
                showToast("Live Tail Service Enabled", "success");
            } else {
                await pipelineAPI.disableLiveTail();
                showToast("Live Tail Service Disabled", "warning");
            }
        } catch (e) {
            showToast("Failed to toggle service: " + e.message, "error");
            // Revert toggle UI
            document.getElementById('livetail-service-toggle').checked = !enabled;
        }
    }
    
    function togglePauseTail(btn) {
        const term = document.getElementById('terminal-window');
        const isPaused = term.getAttribute('data-paused') === 'true';
        if (isPaused) {
            term.setAttribute('data-paused', 'false');
            btn.textContent = 'Pause';
            btn.classList.remove('btn-warning');
            btn.classList.add('btn-secondary');
        } else {
             term.setAttribute('data-paused', 'true');
             btn.textContent = 'Resume';
             btn.classList.remove('btn-secondary');
             btn.classList.add('btn-warning');
        }
    }

    // --- Helpers ---
    function showToast(msg, type = 'info') {
        const container = document.getElementById('toast-container');
        const alertClass = type === 'success' ? 'alert-success' : (type === 'error' ? 'alert-error' : 'alert-info');
        
        const toast = document.createElement('div');
        toast.className = `alert ${alertClass} text-white shadow-lg mb-2`;
        toast.innerHTML = `<span>${msg}</span>`;
        
        container.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 3000);
    }
    
    function capitalize(s) {
        return s.charAt(0).toUpperCase() + s.slice(1);
    }
    
    function formatLabel(s) {
        return s.replace(/([A-Z])/g, ' $1').trim();
    }

    // Public API
    return {
        init,
        switchView,
        handleSearch,
        openCreateModal,
        openControlModal,
        editAdapter,
        deleteAdapter,
        toggleAdapter,
        handleConfigSubmit,
        loadSchema,
        reloadPipeline,
        validateAndReload,
        restartPipeline,
        loadSchemaMapping,
        saveSchemaMapping,
        refreshSchemaTemplates,
        saveCurrentMappingAsTemplate,
        updateSelectedSchemaTemplate,
        deleteSelectedSchemaTemplate,
        applySchemaTemplate,
        openSchemaMapForMessageType,
        saveSettings,
        togglePauseTail,
        toggleLiveTailService,
        testPattern,
        setFilter
    };

})();

// Initialize on Load
document.addEventListener('DOMContentLoaded', App.init);
