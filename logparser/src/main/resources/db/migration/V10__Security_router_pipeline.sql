-- Opt-in: populate certificate bindings and enable output before input.
INSERT INTO input_adapters(type,messagetype,host,port,enabled,config_params)
SELECT 'TlsTcpInputAdapter','security-router','0.0.0.0',6514,0,
'{"framing":"octet-counted","maxFrameBytes":2097152,"clientAuth":"need","keyStorePath":"/etc/logparser/router/server.p12","keyStorePasswordEnv":"ROUTER_KEYSTORE_PASSWORD","trustStorePath":"/etc/logparser/router/truststore.p12","trustStorePasswordEnv":"ROUTER_TRUSTSTORE_PASSWORD","inboxDirectory":"/root/logparser/data/router-inbox","clientSourceBindings":{}}'
WHERE NOT EXISTS (SELECT 1 FROM input_adapters WHERE messagetype='security-router');

INSERT INTO parsers(type,messagetype,priority,enabled)
SELECT 'RFC5424SyslogParser','security-router',10,1
WHERE NOT EXISTS (SELECT 1 FROM parsers WHERE messagetype='security-router' AND type='RFC5424SyslogParser');
INSERT INTO parsers(type,messagetype,source_field,priority,enabled)
SELECT 'JsonParser','security-router','syslog_MESSAGE',20,1
WHERE NOT EXISTS (SELECT 1 FROM parsers WHERE messagetype='security-router' AND type='JsonParser');

INSERT INTO output_adapters(type,messagetype,enabled,config_params)
SELECT 'ClickHouseOutputAdapter','security-router',0,
'{"endpointUrl":"http://clickhouse:8123","database":"castrelyx","usernameEnv":"CLICKHOUSE_USER","passwordEnv":"CLICKHOUSE_PASSWORD","tableName":"castrelyx_router_events","autoCreateSchema":true,"writeTelemetryTables":true}'
WHERE NOT EXISTS (SELECT 1 FROM output_adapters WHERE messagetype='security-router');
