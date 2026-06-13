import type { Page, Route } from '@playwright/test';

export async function mockApi(page: Page, role: 'ADMIN' | 'OPERATOR' | 'VIEWER' = 'ADMIN') {
  const assets = [
    { id: 1, assetUid: 'edge-router', name: 'edge-router', assetType: 'ROUTER', managementIp: '10.0.0.1', status: 'active' }
  ];
  const alerts = [
    { id: 1, severity: 'CRITICAL', status: 'ACTIVE', title: 'CPU threshold exceeded' }
  ];

  await page.route('/api/setup/status', routeJson({ required: false }));
  await page.route('/api/auth/session', routeJson({
    authenticated: true,
    user: { username: role.toLowerCase(), role }
  }));
  await page.route('/api/auth/logout', routeJson({}, 204));
  await page.route('/api/dashboards/overview', routeJson({
    activeAssets: 3,
    criticalAlerts: 1,
    agentHealth: { healthy: 2, stale: 1 },
    snmpPollHealth: { success: 4, failure: 1 }
  }));
  await page.route('/api/dashboards/agent', routeJson({
    heartbeat: { healthy: 1, stale: 0, lastSeenAt: '2026-06-11T13:34:00Z' },
    securityPosture: { exposedPorts: 1, failedServices: 1, firewallDisabled: 1, securityEvents: 1 },
    agents: [{ assetUid: 'nas', sourceId: 'nas', lastSeenAt: '2026-06-11T13:34:00Z' }],
    collectors: [{ name: 'package', sampleCount: 15477, lastSeenAt: '2026-06-11T13:34:00Z' }],
    states: {
      sockets: [{
        assetUid: 'nas',
        protocol: 'tcp',
        localAddress: '0.0.0.0',
        localPort: 22,
        direction: 'listening',
        processName: 'sshd',
        observedAt: '2026-06-11T13:34:00Z'
      }],
      services: [{ assetUid: 'nas', name: 'ssh.service', status: 'failed', observedAt: '2026-06-11T13:34:00Z' }],
      firewalls: [{ assetUid: 'nas', backend: 'ufw', enabled: false, observedAt: '2026-06-11T13:34:00Z' }]
    },
    resources: {
      metrics: [{ assetUid: 'nas', metricName: 'disk.usage', value: 72.4, unit: 'percent', observedAt: '2026-06-11T13:34:00Z' }]
    },
    events: [{
      assetUid: 'nas',
      eventType: 'log',
      severity: 'WARNING',
      message: 'SSH login failed for alice',
      observedAt: '2026-06-11T13:33:00Z'
    }]
  }));
  await page.route('/api/assets', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON();
      const created = {
        id: assets.length + 1,
        assetUid: `manual-${assets.length + 1}`,
        name: payload.name,
        assetType: payload.assetType,
        managementIp: payload.managementIp,
        status: 'active'
      };
      assets.unshift(created);
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(created) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(assets) });
  });
  await page.route('/api/alerts', routeJson(alerts));
  await page.route('/api/alerts/1/acknowledge', async (route) => {
    alerts[0] = { ...alerts[0], status: 'ACKNOWLEDGED' };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(alerts[0]) });
  });
  await page.route('/api/alerts/1/resolve', async (route) => {
    alerts[0] = { ...alerts[0], status: 'RESOLVED' };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(alerts[0]) });
  });
  await page.route('/api/integrations/castrelsign', routeJson({
    serviceName: 'castrelsign',
    baseUrl: 'https://castrelsign:8443',
    secret: { configured: true, masked: '********' },
    enabled: true
  }));
  await page.route('/api/integrations/castrelsign/agents', routeJson([
    { agentId: 'edge-agent', hostname: 'edge-router', status: 'ACTIVE' }
  ]));
  await page.route('/api/integrations/castrelsign/certificates', routeJson([
    { serialNumber: '01', subject: 'CN=edge-agent', status: 'ISSUED' }
  ]));
  await page.route('/api/integrations/castrelsign/tokens', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ id: 3, token: 'enroll-token-123', description: 'Manager issued token' })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 2, description: 'Existing token', revoked: false }])
    });
  });
  await page.route('/api/integrations/logparser', routeJson({
    serviceName: 'logparser',
    baseUrl: 'http://logparser:8765',
    secret: { configured: false, masked: '' },
    enabled: true
  }));
  await page.route('/api/integrations/logparser/status', routeJson({
    status: 'RUNNING',
    inputAdapterCount: 1,
    outputAdapterCount: 1
  }));
  await page.route('/api/integrations/logparser/deep-links', routeJson([
    { label: 'Pipeline', url: 'http://logparser:8765/' },
    { label: 'Input adapters', url: 'http://logparser:8765/#input-adapters' }
  ]));
}

export function routeJson(body: unknown, status = 200) {
  return (route: Route) => route.fulfill({
    status,
    contentType: 'application/json',
    body: status === 204 ? '' : JSON.stringify(body)
  });
}
