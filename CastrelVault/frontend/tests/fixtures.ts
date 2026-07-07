import type { Page, Route } from '@playwright/test';

export async function setupVaultRoutes(page: Page) {
  let authenticated = false;
  const secrets: Array<Record<string, unknown>> = [{
    id: 'secret-1',
    path: '/manager/integrations/castrelsign/secret',
    displayName: 'CastrelSign admin token',
    type: 'API_TOKEN',
    tags: ['manager'],
    description: '',
    enabled: true,
    createdAt: '2026-06-28T00:00:00Z',
    updatedAt: '2026-06-28T00:00:00Z',
    currentVersion: 1,
    payload: { configured: true, masked: '********' }
  }];

  await page.route('/api/admin/session', async (route) => {
    if (!authenticated) {
      await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ error: 'admin session is required' }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ username: 'admin', role: 'ADMIN', requiresPasswordChange: false }) });
  });

  await page.route('/api/admin/login', async (route) => {
    authenticated = true;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Set-Cookie': 'CASTRELVAULT_CSRF=csrf-token; Path=/; SameSite=Strict' },
      body: JSON.stringify({ username: 'admin', role: 'ADMIN', requiresPasswordChange: false, csrfToken: 'csrf-token' })
    });
  });

  await page.route('/api/admin/logout', routeJson({ ok: true }));
  await page.route('/api/admin/status', routeJson({
    service: 'CastrelVault',
    dataDir: '/var/lib/castrelvault',
    databasePath: '/var/lib/castrelvault/vault.db',
    activeMasterKeyId: 'key-2026',
    configuredMasterKeyIds: ['key-2026'],
    tls: { serverTlsConfigured: true, trustStoreConfigured: true, castrelSignCaConfigured: true },
    secrets: { total: 1, enabled: 1, disabled: 0, deleted: 0, versions: 1 },
    audit: { total: 2, denied: 0, reveals: 1, resolves: 1 },
    castrelSign: { configured: true, state: 'AVAILABLE', detail: 'ok' },
    managerMigration: { configured: true, status: 'ready' }
  }));

  await page.route('/api/secrets', async (route) => {
    if (route.request().method() === 'POST') {
      requireCsrf(route);
      const payload = route.request().postDataJSON();
      const created = {
        id: `secret-${secrets.length + 1}`,
        path: payload.path,
        displayName: payload.displayName,
        type: payload.type,
        tags: payload.tags ?? [],
        description: payload.description ?? '',
        enabled: true,
        createdAt: '2026-06-28T01:00:00Z',
        updatedAt: '2026-06-28T01:00:00Z',
        currentVersion: 1,
        payload: { configured: true, masked: '********' }
      };
      secrets.unshift(created);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(created) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(secrets) });
  });

  await page.route(/\/api\/secrets\/[^/]+\/versions$/, routeJson([
    { id: 2, version: 2, keyId: 'key-2026', payloadContentHash: 'abcdef012345', createdAt: '2026-06-28T01:10:00Z', creatorPrincipal: 'admin', current: true },
    { id: 1, version: 1, keyId: 'key-2026', payloadContentHash: '012345abcdef', createdAt: '2026-06-28T01:00:00Z', creatorPrincipal: 'admin', current: false }
  ]));

  await page.route(/\/api\/secrets\/[^/]+\/reveal$/, async (route) => {
    requireCsrf(route);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify({ id: 'secret-1', path: '/manager/integrations/castrelsign/secret', version: 1, payload: { value: 'known-api-token-value-987654' } })
    });
  });

  await page.route(/\/api\/secrets\/[^/]+\/(rotate|enable|disable)$/, async (route) => {
    requireCsrf(route);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(secrets[0]) });
  });

  await page.route(/\/api\/secrets\/[^/]+$/, async (route) => {
    requireCsrf(route);
    await route.fulfill({ status: 204, body: '' });
  });

  await page.route(/\/api\/audit-events\/search.*/, routeJson({
    events: [{
      id: 1,
      timestamp: '2026-06-28T01:00:00Z',
      actorType: 'ADMIN',
      actorId: 'admin',
      secretPath: '/manager/integrations/castrelsign/secret',
      secretVersion: 1,
      action: 'REVEAL_SECRET',
      result: 'ALLOWED',
      reason: 'maintenance',
      sourceMetadata: {}
    }],
    total: 1,
    limit: 100,
    offset: 0
  }));

  await page.route('/api/applications', async (route) => {
    if (route.request().method() === 'POST') {
      requireCsrf(route);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ principalId: 'manager-app', displayName: 'Manager', status: 'ACTIVE', permissions: [] }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ principalId: 'manager-app', displayName: 'Manager', status: 'ACTIVE', permissions: ['vault:resolve'] }]) });
  });
  await page.route('/api/applications/certificates', routeJson([{ principalId: 'manager-app', serialNumber: 'abc', status: 'ACTIVE', notAfter: '2026-07-28T00:00:00Z' }]));
  await page.route('/api/applications/tokens', async (route) => {
    if (route.request().method() === 'POST') {
      requireCsrf(route);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 9, principalId: 'manager-app', token: 'one-use-token' }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 8, principalId: 'manager-app', name: 'existing token', expiresAt: '2026-06-29T00:00:00Z' }]) });
  });
  await page.route(/\/api\/applications\/[^/]+\/permissions$/, routeJson({ principalId: 'manager-app', status: 'ACTIVE', permissions: ['vault:resolve'] }));
  await page.route(/\/api\/applications\/[^/]+\/(block|reactivate)$/, routeJson({}, 204));

  await page.route('/api/manager-migration/status', routeJson({ vaultEnabled: true, pendingIntegrationSecrets: 1, pendingSnmpCredentials: 1, migratedIntegrationSecrets: 0, migratedSnmpCredentials: 0, status: 'ready' }));
  await page.route('/api/manager-migration/dry-run', async (route) => {
    requireCsrf(route);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        vaultEnabled: true,
        pendingIntegrationSecrets: 1,
        pendingSnmpCredentials: 1,
        integrations: [{ source: 'integration', name: 'castrelsign', vaultPath: '/manager/integrations/castrelsign/secret', type: 'API_TOKEN' }],
        snmpCredentials: [{ source: 'snmp', name: 'edge-snmp', vaultPath: '/manager/snmp/credentials/1', type: 'SNMP_V2C' }],
        status: 'dry-run'
      })
    });
  });
  await page.route('/api/manager-migration/run', async (route) => {
    requireCsrf(route);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ migratedIntegrationSecrets: 1, migratedSnmpCredentials: 1, status: 'migrated' }) });
  });
}

export function routeJson(body: unknown, status = 200) {
  return (route: Route) => route.fulfill({
    status,
    contentType: 'application/json',
    body: status === 204 ? '' : JSON.stringify(body)
  });
}

function requireCsrf(route: Route) {
  if (route.request().headers()['x-csrf-token'] !== 'csrf-token') {
    throw new Error(`missing csrf token for ${route.request().method()} ${route.request().url()}`);
  }
}
