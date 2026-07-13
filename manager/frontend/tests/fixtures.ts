import type { Page, Route } from '@playwright/test';

type MockAsset = { id?: number; assetUid: string; name: string; assetType: string; managementIp?: string; location?: string; description?: string; status: string };
type MockMetricAsset = ReturnType<typeof metricOverview>['assets'][number];

export async function mockApi(page: Page, role: 'ADMIN' | 'OPERATOR' | 'VIEWER' = 'ADMIN') {
  const assets = [
    { id: 1, assetUid: 'edge-router', name: 'edge-router', assetType: 'ROUTER', managementIp: '10.0.0.1', location: 'Seoul HQ', description: 'WAN router', status: 'active' },
    { id: 2, assetUid: 'inventory-only', name: 'inventory-only', assetType: 'LINUX_SERVER', managementIp: '10.0.0.99', location: 'Lab', description: 'Registered without telemetry', status: 'active' }
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
  await page.route('/api/agent/logs?range=7d&severity=CRITICAL&limit=8', routeJson([{
    assetUid: 'nas',
    eventType: 'filesystem',
    severity: 'CRITICAL',
    message: 'Root filesystem full',
    observedAt: '2026-06-11T13:33:00Z'
  }]));
  await page.route('/api/agent/logs?range=15m&severity=ALL&limit=200', routeJson([{
    assetUid: 'nas',
    eventType: 'auth.login.failure',
    eventCategory: 'auth',
    severity: 'WARNING',
    message: 'SSH login failed for alice',
    observedAt: '2026-06-11T13:33:00Z'
  }]));
  await page.route('/api/agent/logs?range=1h&severity=ALL&limit=120&assetUid=nas', routeJson([{
    assetUid: 'nas',
    sourceId: 'nas-agent',
    eventType: 'auth.login.failure',
    eventCategory: 'auth',
    severity: 'WARNING',
    sourceName: 'sshd',
    program: 'sshd',
    actor: 'alice',
    action: 'login',
    outcome: 'failure',
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
  await page.route('/api/traffic/interfaces?range=15m', routeJson([
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
  await page.route('/api/metrics/assets?range=15m', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(metricOverview(assets)) });
  });
  await page.route(/\/api\/metrics\/assets\/[^/?]+\?range=1h&bucket=auto$/, async (route) => {
    const url = new URL(route.request().url());
    const assetUid = decodeURIComponent(url.pathname.split('/').pop() ?? '');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(metricDetail(assetUid, assets)) });
  });
  await page.route(/\/api\/metrics\/assets\/[^/?]+\?range=15m&bucket=auto$/, async (route) => {
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
    ...assets.map((asset) => {
      const observed = asset.assetUid === 'edge-router';
      return {
        assetUid: asset.assetUid,
        id: asset.id,
        name: asset.name,
        assetType: asset.assetType,
        managementIp: asset.managementIp,
        location: asset.location,
        description: asset.description,
        status: asset.status,
        lastSeenAt: observed ? '2026-06-11T13:34:00Z' : undefined,
        stale: false,
        health: observed ? 'warning' : 'unknown',
        sources: { registered: true, observed, traffic: observed, diskIo: observed },
        metrics: observed
          ? { cpuUsagePct: 42.1, memoryUsagePct: 55.4, diskUsagePct: 68.2, normalizedLoadPct: 25, networkInBps: 1200000, networkOutBps: 900000, interfaceErrorCount: 1, diskReadBps: 262144, diskWriteBps: 131072, diskReadIops: 16, diskWriteIops: 8, diskIoUtilizationPct: 42.5 }
          : {}
      };
    }),
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
    },
    {
      assetUid: 'x86host',
      name: 'x86host',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.22',
      status: 'active',
      lastSeenAt: '2026-06-11T13:16:00Z',
      stale: true,
      health: 'warning',
      sources: { registered: false, agent: true, traffic: true, diskIo: true, observed: true },
      metrics: { cpuUsagePct: 14.2, memoryUsagePct: 33.1, diskUsagePct: 52, normalizedLoadPct: 12, networkInBps: 10000, networkOutBps: 5000, interfaceErrorCount: 1, diskReadBps: 262144, diskWriteBps: 131072, diskReadIops: 16, diskWriteIops: 8, diskIoUtilizationPct: 35.5 }
    },
    {
      assetUid: 'idle-node',
      name: 'idle-node',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.30',
      status: 'active',
      lastSeenAt: '2026-06-11T13:34:30Z',
      stale: false,
      health: 'healthy',
      sources: { registered: false, agent: true, traffic: true, diskIo: true, observed: true },
      metrics: { cpuUsagePct: 0, memoryUsagePct: 0, diskUsagePct: 20, normalizedLoadPct: 0, networkInBps: 0, networkOutBps: 0, interfaceErrorCount: 0, diskReadBps: 0, diskWriteBps: 0, diskReadIops: 0, diskWriteIops: 0, diskIoUtilizationPct: 0 }
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
      staleAssets: metricAssets.filter((asset) => asset.stale).length,
      criticalAssets: metricAssets.filter((asset) => asset.health === 'critical').length,
      warningAssets: metricAssets.filter((asset) => asset.health === 'warning').length,
      avgCpuUsagePct: 36.9,
      avgMemoryUsagePct: 39.1,
      maxDiskUsagePct: 72.4,
      maxDiskIoUtilizationPct: 87.5,
      totalNetworkInBps: 1214621.02,
      totalNetworkOutBps: 916663.97
    },
    assets: metricAssets
  };
}

function metricDetail(assetUid: string, assets: MockAsset[]) {
  const overview = metricOverview(assets);
  const asset = (overview.assets.find((row) => row.assetUid === assetUid) ?? overview.assets[0]) as MockMetricAsset;
  const hasDiskIo = asset.sources.diskIo === true;
  const hasTraffic = asset.sources.traffic === true;
  return {
    range: '1h',
    bucket: 'auto',
    asset,
    series: {
      cpu: [{ timestamp: '2026-06-11T13:30:00Z', value: 82 }, { timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.cpuUsagePct ?? null }],
      memory: [{ timestamp: '2026-06-11T13:30:00Z', value: 60 }, { timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.memoryUsagePct ?? null }],
      disk: [{ timestamp: '2026-06-11T13:35:00Z', value: asset.metrics.diskUsagePct ?? null }],
      diskIo: hasDiskIo ? [{ timestamp: '2026-06-11T13:35:00Z', readBps: asset.metrics.diskReadBps ?? null, writeBps: asset.metrics.diskWriteBps ?? null, readIops: asset.metrics.diskReadIops ?? null, writeIops: asset.metrics.diskWriteIops ?? null, utilizationPct: asset.metrics.diskIoUtilizationPct ?? null }] : [],
      network: hasTraffic ? [{ timestamp: '2026-06-11T13:35:00Z', inBps: asset.metrics.networkInBps ?? null, outBps: asset.metrics.networkOutBps ?? null }] : []
    },
    disks: assetUid === 'nas'
      ? [{ mountPoint: '/data', filesystem: '/dev/sdb1', device: 'sdb', usedPct: 72.4, readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }]
      : hasDiskIo ? [{ mountPoint: '/', filesystem: '/dev/sda1', device: 'sda', usedPct: asset.metrics.diskUsagePct ?? undefined, readBps: asset.metrics.diskReadBps ?? undefined, writeBps: asset.metrics.diskWriteBps ?? undefined, readIops: asset.metrics.diskReadIops ?? undefined, writeIops: asset.metrics.diskWriteIops ?? undefined, ioUtilizationPct: asset.metrics.diskIoUtilizationPct ?? undefined }] : [],
    diskIo: assetUid === 'nas'
      ? [{ assetUid: 'nas', device: 'sdb', readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }]
      : hasDiskIo ? [{ assetUid, device: 'sda', readBps: asset.metrics.diskReadBps ?? undefined, writeBps: asset.metrics.diskWriteBps ?? undefined, readIops: asset.metrics.diskReadIops ?? undefined, writeIops: asset.metrics.diskWriteIops ?? undefined, ioUtilizationPct: asset.metrics.diskIoUtilizationPct ?? undefined }] : [],
    interfaces: hasTraffic ? [{
      assetUid,
      interfaceName: assetUid === 'nas' ? 'enp2s0' : assetUid === 'edge-router' ? 'wan0' : assetUid === 'x86host' ? 'eno1' : 'eth0',
      inBps: asset.metrics.networkInBps ?? 0,
      outBps: asset.metrics.networkOutBps ?? 0,
      utilizationPct: assetUid === 'edge-router' ? 12.4 : 0,
      errors: asset.metrics.interfaceErrorCount ?? 0,
      discards: 0,
      status: 'up'
    }] : [],
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
