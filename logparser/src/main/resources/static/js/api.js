// API Base URL
const API_BASE = '/api/v1';

// API Helper Functions
const api = {
    // Generic request function
    async request(url, options = {}) {
        try {
            const response = await fetch(url, {
                ...options,
                headers: {
                    'Content-Type': 'application/json',
                    ...options.headers
                }
            });

            const responseText = await response.text();
            let responseBody = null;
            if (responseText) {
                try {
                    responseBody = JSON.parse(responseText);
                } catch (parseError) {
                    responseBody = { message: responseText };
                }
            }

            if (!response.ok) {
                const error = new Error((responseBody && responseBody.message) || `HTTP ${response.status}`);
                error.status = response.status;
                error.body = responseBody;
                throw error;
            }

            if (response.status === 204 || responseBody === null) {
                return null;
            }

            return responseBody;
        } catch (error) {
            console.error('API request failed:', error);
            throw error;
        }
    },

    // GET request
    get(endpoint) {
        return this.request(`${API_BASE}${endpoint}`);
    },

    // POST request
    post(endpoint, data) {
        return this.request(`${API_BASE}${endpoint}`, {
            method: 'POST',
            body: JSON.stringify(data)
        });
    },

    // PUT request
    put(endpoint, data) {
        return this.request(`${API_BASE}${endpoint}`, {
            method: 'PUT',
            body: JSON.stringify(data)
        });
    },

    // PATCH request
    patch(endpoint, data = {}) {
        return this.request(`${API_BASE}${endpoint}`, {
            method: 'PATCH',
            body: JSON.stringify(data)
        });
    },

    // DELETE request
    delete(endpoint) {
        return this.request(`${API_BASE}${endpoint}`, {
            method: 'DELETE'
        });
    }
};

// Input Adapter API
const inputAdapterAPI = {
    getAll(page = 0, size = 100) {
        return api.get(`/input-adapters?page=${page}&size=${size}`);
    },

    getById(id) {
        return api.get(`/input-adapters/${id}`);
    },

    create(data) {
        return api.post('/input-adapters', data);
    },

    update(id, data) {
        return api.put(`/input-adapters/${id}`, data);
    },

    delete(id) {
        return api.delete(`/input-adapters/${id}`);
    },

    enable(id) {
        return api.patch(`/input-adapters/${id}/enable`);
    },

    disable(id) {
        return api.patch(`/input-adapters/${id}/disable`);
    },

    getByType(type) {
        return api.get(`/input-adapters/type/${type}`);
    },

    getByMessageType(messageType) {
        return api.get(`/input-adapters/messagetype/${messageType}`);
    }
};

// Parser API
const parserAPI = {
    getAll(page = 0, size = 100) {
        return api.get(`/parsers?page=${page}&size=${size}`);
    },

    getById(id) {
        return api.get(`/parsers/${id}`);
    },

    create(data) {
        return api.post('/parsers', data);
    },

    update(id, data) {
        return api.put(`/parsers/${id}`, data);
    },

    delete(id) {
        return api.delete(`/parsers/${id}`);
    },

    enable(id) {
        return api.patch(`/parsers/${id}/enable`);
    },

    disable(id) {
        return api.patch(`/parsers/${id}/disable`);
    },

    test(data) {
        return api.post('/parsers/test', data);
    }
};

// Transform API
const transformAPI = {
    getAll(page = 0, size = 100) {
        return api.get(`/transforms?page=${page}&size=${size}`);
    },

    getById(id) {
        return api.get(`/transforms/${id}`);
    },

    create(data) {
        return api.post('/transforms', data);
    },

    update(id, data) {
        return api.put(`/transforms/${id}`, data);
    },

    delete(id) {
        return api.delete(`/transforms/${id}`);
    },

    enable(id) {
        return api.patch(`/transforms/${id}/enable`);
    },

    disable(id) {
        return api.patch(`/transforms/${id}/disable`);
    }
};

// Structured Transform API
const structureAPI = {
    getSchema() {
        return api.get('/structure/schema');
    },

    getMapping(messageType) {
        return api.get(`/structure/mapping/${encodeURIComponent(messageType)}`);
    },

    saveMapping(config) {
        return api.post('/structure/mapping', config);
    },

    simulate(payload) {
        return api.post('/structure/simulate', payload);
    }
};

// Output Adapter API
const outputAdapterAPI = {
    getAll(page = 0, size = 100) {
        return api.get(`/output-adapters?page=${page}&size=${size}`);
    },

    getById(id) {
        return api.get(`/output-adapters/${id}`);
    },

    create(data) {
        return api.post('/output-adapters', data);
    },

    update(id, data) {
        return api.put(`/output-adapters/${id}`, data);
    },

    delete(id) {
        return api.delete(`/output-adapters/${id}`);
    },

    enable(id) {
        return api.patch(`/output-adapters/${id}/enable`);
    },

    disable(id) {
        return api.patch(`/output-adapters/${id}/disable`);
    }
};

// Pipeline API
const pipelineAPI = {
    getStatus() {
        return api.get('/pipeline/status');
    },

    reload() {
        return api.post('/pipeline/reload');
    },

    validateAndReload() {
        return api.post('/pipeline/validate-and-reload');
    },

    restart() {
        return api.post('/pipeline/restart');
    },

    getReloadProgress() {
        return api.get('/pipeline/reload-progress');
    },

    cancelReload() {
        return api.post('/pipeline/cancel-reload');
    },

    getThreads() {
        return api.get('/pipeline/threads');
    },

    getOutputMetrics() {
        return api.get('/pipeline/output-metrics');
    },
    
    getTopology() {
        return api.get('/pipeline/topology');
    },
    
    getLiveTailStatus() {
        return api.get('/pipeline/livetail/status');
    },

    enableLiveTail() {
        return api.post('/pipeline/livetail/enable');
    },

    disableLiveTail() {
        return api.post('/pipeline/livetail/disable');
    }
};

// Settings API
const settingsAPI = {
    getAll() {
        return api.get('/settings');
    },

    get(key) {
        return api.get(`/settings/${key}`);
    },

    update(key, value, dataType = 'STRING') {
        return api.put(`/settings/${key}`, { value, dataType });
    },

    updateAll(settings) {
        return api.put('/settings', settings);
    }
};

// Metadata API
const metadataAPI = {
    getInputAdapterTypes() {
        return api.get('/metadata/input-adapter-types');
    },

    getParserTypes() {
        return api.get('/metadata/parser-types');
    },

    getTransformTypes() {
        return api.get('/metadata/transform-types');
    },

    getOutputAdapterTypes() {
        return api.get('/metadata/output-adapter-types');
    },

    getInputAdapterSchema(type) {
        return api.get(`/metadata/input-adapter-schema/${type}`);
    },

    getParserSchema(type) {
        return api.get(`/metadata/parser-schema/${type}`);
    },

    getTransformSchema(type) {
        return api.get(`/metadata/transform-schema/${type}`);
    },

    getOutputAdapterSchema(type) {
        return api.get(`/metadata/output-adapter-schema/${type}`);
    },

    getSupportedCodecs() {
        return api.get('/metadata/supported-codecs');
    },

    getSupportedHttpMethods() {
        return api.get('/metadata/supported-http-methods');
    }
};
