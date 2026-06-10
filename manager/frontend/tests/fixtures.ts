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
}

export function routeJson(body: unknown, status = 200) {
  return (route: Route) => route.fulfill({
    status,
    contentType: 'application/json',
    body: status === 204 ? '' : JSON.stringify(body)
  });
}
