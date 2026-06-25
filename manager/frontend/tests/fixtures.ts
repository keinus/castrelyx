import type { Page, Route } from '@playwright/test';

type MockAsset = { id?: number; assetUid: string; name: string; assetType: string; managementIp?: string; location?: string; description?: string; status: string };
type MockMetricAsset = ReturnType<typeof metricOverview>['assets'][number];

export async function mockApi(page: Page, role: 'ADMIN' | 'OPERATOR' | 'VIEWER' = 'ADMIN') {
  const assets = [
    { id: 1, assetUid: 'edge-router', name: 'edge-router', assetType: 'ROUTER', managementIp: '10.0.0.1', location: 'Seoul HQ', description: 'WAN router', status: 'active' }
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
    snmpPollHealth: { success: 4, failure: 1 },
    trafficTopInterfaces: [{
      assetUid: 'edge-router',
      interfaceName: 'wan0',
      inBps: 2400000,
      outBps: 1800000,
      utilizationPct: 42.5,
      errors: 2,
      discards: 1,
      status: 'up'
    }]
  }));
  await page.route('/api/dashboards/agent', routeJson({
    heartbeat: { healthy: 1, stale: 0, lastSeenAt: '2026-06-11T13:34:00Z' },
    securityPosture: { exposedPorts: 1, failedServices: 1, firewallDisabled: 1 },
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
  await page.route('/api/agent/logs?range=1h&severity=ALL&limit=8', routeJson([{
    assetUid: 'nas',
    eventType: 'log',
    severity: 'WARNING',
    message: 'SSH login failed for alice',
    observedAt: '2026-06-11T13:33:00Z'
  }]));
  await page.route('/api/dashboards/snmp', routeJson({
    polls: { success: 4, failure: 1 },
    targets: ['edge-router'],
    interfaces: [{
      assetUid: 'edge-router',
      interfaceName: 'wan0',
      inBps: 2400000,
      outBps: 1800000,
      utilizationPct: 42.5,
      errors: 2,
      discards: 1,
      status: 'up'
    }]
  }));
  await page.route('/api/traffic/interfaces?range=1h', routeJson([
    {
      assetUid: 'nas',
      interfaceName: 'enp2s0',
      inBps: 4621.02,
      outBps: 11663.97,
      utilizationPct: 0,
      errors: 0,
      discards: 0,
      status: 'up'
    },
    {
      assetUid: 'edge-router',
      interfaceName: 'wan0',
      inBps: 1200000,
      outBps: 900000,
      utilizationPct: 12.4,
      errors: 1,
      discards: 0,
      status: 'up'
    }
  ]));
  await page.route('/api/metrics/assets?range=1h', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(metricOverview(assets)) });
  });
  await page.route(/\/api\/metrics\/assets\/[^/?]+\?range=1h&bucket=auto$/, async (route) => {
    const url = new URL(route.request().url());
    const assetUid = decodeURIComponent(url.pathname.split('/').pop() ?? '');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(metricDetail(assetUid, assets)) });
  });
  await page.route('/api/assets', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON();
      const created = {
        id: assets.length + 1,
        assetUid: `manual-${assets.length + 1}`,
        name: payload.name,
        assetType: payload.assetType,
        managementIp: payload.managementIp,
        location: payload.location,
        description: payload.description,
        status: 'active'
      };
      assets.unshift(created);
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(created) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(assets) });
  });
  await page.route(/\/api\/assets\/\d+$/, async (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop());
    const index = assets.findIndex((asset) => asset.id === id);
    if (route.request().method() === 'PUT') {
      const payload = route.request().postDataJSON();
      assets[index] = {
        ...assets[index],
        name: payload.name,
        location: payload.location,
        description: payload.description
      };
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(assets[index]) });
      return;
    }
    if (route.request().method() === 'DELETE') {
      if (index >= 0) {
        assets.splice(index, 1);
      }
      await route.fulfill({ status: 204, contentType: 'application/json', body: '' });
      return;
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
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
  await page.route('/api/integrations/castrelsign/audit-events', routeJson([]));
  await page.route('/api/integrations/castrelsign/agent-releases', routeJson([]));
  await page.route('/api/integrations/castrelsign/agent-update-policies', routeJson([
    { policyKey: 'global', enabled: true, channel: 'stable' }
  ]));
  await page.route('/api/integrations/castrelsign/agent-update-attempts', routeJson([]));
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
  await page.route('/api/integrations/castrelsign/enrollment-packages', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/zip',
      body: 'zip'
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

function metricOverview(assets: MockAsset[]) {
  const metricAssets = [
    ...assets.map((asset) => ({
      assetUid: asset.assetUid,
      id: asset.id,
      name: asset.name,
      assetType: asset.assetType,
      managementIp: asset.managementIp,
      location: asset.location,
      description: asset.description,
      status: asset.status,
      lastSeenAt: '2026-06-11T13:34:00Z',
      stale: false,
      health: asset.assetUid === 'edge-router' ? 'warning' : 'unknown',
      sources: { registered: true, observed: asset.assetUid === 'edge-router', traffic: asset.assetUid === 'edge-router', diskIo: asset.assetUid === 'edge-router' },
      metrics: asset.assetUid === 'edge-router'
        ? { cpuUsagePct: 42.1, memoryUsagePct: 55.4, diskUsagePct: 68.2, normalizedLoadPct: 25, networkInBps: 1200000, networkOutBps: 900000, interfaceErrorCount: 1, diskReadBps: 262144, diskWriteBps: 131072, diskReadIops: 16, diskWriteIops: 8, diskIoUtilizationPct: 42.5 }
        : {}
    })),
    {
      assetUid: 'nas',
      name: 'nas',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.21',
      status: 'active',
      lastSeenAt: '2026-06-11T13:34:00Z',
      stale: false,
      health: 'critical',
      sources: { registered: false, agent: true, traffic: true, diskIo: true, security: true, observed: true },
      metrics: { cpuUsagePct: 91.2, memoryUsagePct: 67.8, diskUsagePct: 72.4, normalizedLoadPct: 45, networkInBps: 4621.02, networkOutBps: 11663.97, interfaceErrorCount: 0, diskReadBps: 1048576, diskWriteBps: 2097152, diskReadIops: 128, diskWriteIops: 64, diskIoUtilizationPct: 87.5 },
      security: { openPorts: 1, failedServices: 1, firewallDisabled: 1 }
    }
  ] as Array<{
    assetUid: string;
    name: string;
    assetType: string;
    managementIp?: string;
    status: string;
    lastSeenAt?: string;
    stale: boolean;
    health: 'healthy' | 'warning' | 'critical' | 'unknown';
    sources: { registered?: boolean; agent?: boolean; traffic?: boolean; diskIo?: boolean; security?: boolean; observed?: boolean };
    metrics: Record<string, number | null | undefined>;
    security?: { openPorts: number; failedServices: number; firewallDisabled: number };
  }>;
  return {
    range: '1h',
    summary: {
      totalAssets: metricAssets.length,
      observedAssets: metricAssets.filter((asset) => asset.sources.observed).length,
      staleAssets: 0,
      criticalAssets: metricAssets.filter((asset) => asset.health === 'critical').length,
      warningAssets: metricAssets.filter((asset) => asset.health === 'warning').length,
      avgCpuUsagePct: 66.7,
      avgMemoryUsagePct: 61.6,
      maxDiskUsagePct: 72.4,
      maxDiskIoUtilizationPct: 87.5,
      totalNetworkInBps: 1204621.02,
      totalNetworkOutBps: 911663.97
    },
    assets: metricAssets
  };
}

function metricDetail(assetUid: string, assets: MockAsset[]) {
  const overview = metricOverview(assets);
  const asset = (overview.assets.find((row) => row.assetUid === assetUid) ?? overview.assets[0]) as MockMetricAsset;
  return {
    range: '1h',
    bucket: 'auto',
    asset,
    series: {
      cpu: [{ timestamp: '2026-06-11T13:30:00Z', value: 82 }, { timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.cpuUsagePct ?? null }],
      memory: [{ timestamp: '2026-06-11T13:30:00Z', value: 60 }, { timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.memoryUsagePct ?? null }],
      disk: [{ timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.diskUsagePct ?? null }],
      diskIo: [{ timestamp: '2026-06-11T13:35:00Z', readBps: asset.metrics.diskReadBps ?? 0, writeBps: asset.metrics.diskWriteBps ?? 0, readIops: asset.metrics.diskReadIops ?? 0, writeIops: asset.metrics.diskWriteIops ?? 0, utilizationPct: asset.metrics.diskIoUtilizationPct ?? 0 }],
      network: [{ timestamp: '2026-06-11T13:35:00Z', inBps: asset.metrics.networkInBps ?? 0, outBps: asset.metrics.networkOutBps ?? 0 }]
    },
    disks: assetUid === 'nas'
      ? [{ mountPoint: '/data', filesystem: '/dev/sdb1', device: 'sdb', usedPct: 72.4, readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }]
      : [{ mountPoint: '/', filesystem: '/dev/sda1', device: 'sda', usedPct: asset.metrics.diskUsagePct ?? 0, readBps: asset.metrics.diskReadBps ?? 0, writeBps: asset.metrics.diskWriteBps ?? 0, readIops: asset.metrics.diskReadIops ?? 0, writeIops: asset.metrics.diskWriteIops ?? 0, ioUtilizationPct: asset.metrics.diskIoUtilizationPct ?? 0 }],
    diskIo: assetUid === 'nas'
      ? [{ assetUid: 'nas', device: 'sdb', readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }]
      : [{ assetUid, device: 'sda', readBps: asset.metrics.diskReadBps ?? 0, writeBps: asset.metrics.diskWriteBps ?? 0, readIops: asset.metrics.diskReadIops ?? 0, writeIops: asset.metrics.diskWriteIops ?? 0, ioUtilizationPct: asset.metrics.diskIoUtilizationPct ?? 0 }],
    interfaces: assetUid === 'nas'
      ? [{ assetUid: 'nas', interfaceName: 'enp2s0', inBps: 4621.02, outBps: 11663.97, utilizationPct: 0, errors: 0, discards: 0, status: 'up' }]
      : [{ assetUid: 'edge-router', interfaceName: 'wan0', inBps: 1200000, outBps: 900000, utilizationPct: 12.4, errors: 1, discards: 0, status: 'up' }],
    processes: assetUid === 'nas' ? [{ assetUid: 'nas', pid: 22, name: 'sshd', user: 'root', memoryBytes: 4096, listeningSocketCount: 1 }] : [],
    security: asset.security ?? { openPorts: 0, failedServices: 0, firewallDisabled: 0 },
    collectors: [{ name: 'metric', sampleCount: 10, lastSeenAt: '2026-06-11T13:34:00Z' }]
  };
}

export function routeJson(body: unknown, status = 200) {
  return (route: Route) => route.fulfill({
    status,
    contentType: 'application/json',
    body: status === 204 ? '' : JSON.stringify(body)
  });
}
