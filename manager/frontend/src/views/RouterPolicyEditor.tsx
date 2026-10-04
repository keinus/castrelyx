import { useId, type ReactNode } from 'react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldGroup, FieldLabel, FieldSet, FieldLegend } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';

type ObjectValue = Record<string, unknown>;
type Port = ObjectValue & { name: string; role: string; vlan?: number; vlans?: number[]; aclIngress?: string; aclEgress?: string; securityProfile?: string };
type Rule = ObjectValue & { id: string; priority: number; action: string; match: ObjectValue };
type ACL = ObjectValue & { id: string; defaultAction?: string; rules: Rule[] };
type Guard = ObjectValue & { id: string; allowedSourceMacs?: string[]; bindings?: ObjectValue[]; stormControl?: ObjectValue; dhcpSnooping?: ObjectValue };
type Routing = ObjectValue & { enabled: boolean; stateful?: boolean; aclProfile: string; interfaces: ObjectValue[]; routes?: ObjectValue[]; nat?: ObjectValue[] };
type Policy = ObjectValue & { ovs: ObjectValue & { ports: Port[] }; acl?: ObjectValue & { profiles?: ACL[] }; security?: ObjectValue & { profiles?: Guard[] }; routing?: Routing };
const string = (value: unknown) => value == null ? '' : String(value);
const uid = (prefix: string) => `${prefix}-${crypto.randomUUID().slice(0, 8)}`;

function EditField({ label, value, onChange, numeric, options }: { label: string; value: unknown; onChange: (value: string | number) => void; numeric?: boolean; options?: [string, string][] }) {
  const id = useId();
  return <Field><FieldLabel htmlFor={id}>{label}</FieldLabel>{options
    ? <NativeSelect id={id} value={string(value)} onChange={e => onChange(e.target.value)}>{options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}</NativeSelect>
    : <Input id={id} type={numeric ? 'number' : 'text'} value={string(value)} onChange={e => onChange(numeric ? Number(e.target.value) : e.target.value)} />}</Field>;
}
function Group({ title, children }: { title: string; children: ReactNode }) {
  return <FieldSet className="rounded-md border p-4"><FieldLegend>{title}</FieldLegend><FieldGroup className="grid grid-cols-1 gap-4 md:grid-cols-2">{children}</FieldGroup></FieldSet>;
}
const choices = [['false', '사용 안 함'], ['true', '사용']] as [string, string][];
const actions = [['drop', '차단'], ['permit', '허용']] as [string, string][];

export function RouterPolicyEditor({ value, onChange, disabled, routingAvailable, ipv6Available = false }: { value: string; onChange: (value: string) => void; disabled: boolean; routingAvailable: boolean; ipv6Available?: boolean }) {
  let c: Policy | null = null;
  try { const parsed = JSON.parse(value) as Policy; if (Array.isArray(parsed.ovs?.ports)) c = parsed; } catch { /* Advanced editor remains available for repair. */ }
  const config = c;
  const edit = (mutate: (next: Policy) => void) => { if (!config) return; const next = structuredClone(config); mutate(next); onChange(JSON.stringify(next, null, 2)); };
  const profiles = config?.acl?.profiles ?? [];
  const guards = config?.security?.profiles ?? [];
  const routing = config?.routing ?? { enabled: false, aclProfile: '', interfaces: [], routes: [], nat: [] };
  const setRoute = (key: string, v: unknown) => edit(next => { next.routing = { ...routing, [key]: v }; });
  const setList = (key: 'interfaces' | 'routes' | 'nat', index: number, patch: ObjectValue) => setRoute(key, (routing[key] ?? []).map((item, i) => i === index ? { ...item, ...patch } : item));
  const removeRoute = (key: 'interfaces' | 'routes' | 'nat', i: number) => setRoute(key, (routing[key] ?? []).filter((_, n) => n !== i));
  const setACL = (i: number, patch: Partial<ACL>) => edit(next => { next.acl = { ...next.acl, profiles: profiles.map((p, n) => n === i ? { ...p, ...patch } : p) }; });
  const setGuard = (i: number, patch: Partial<Guard>) => edit(next => { next.security = { ...next.security, profiles: guards.map((p, n) => n === i ? { ...p, ...patch } : p) }; });
  const references = (id: string) => config?.ovs.ports.some(p => p.aclIngress === id || p.aclEgress === id) || routing.aclProfile === id;
  const empty = [['', '선택 안 함']] as [string, string][];
  return <Tabs defaultValue="routing">
    <TabsList className="h-auto flex-wrap"><TabsTrigger value="routing">라우팅·NAT</TabsTrigger><TabsTrigger value="acl">ACL</TabsTrigger><TabsTrigger value="ports">포트·VLAN</TabsTrigger><TabsTrigger value="security">포트 보안</TabsTrigger><TabsTrigger value="advanced">고급 JSON</TabsTrigger></TabsList>
    {!config ? <Alert><AlertDescription>설정 형식을 읽을 수 없습니다. 고급 JSON에서 수정하세요.</AlertDescription></Alert> : null}
    <fieldset disabled={disabled || !config} className="min-w-0">
      <TabsContent value="routing"><FieldGroup>
        <Alert><AlertDescription>IPv4·IPv6 정적 경로와 VLAN 간 라우팅을 설정합니다. 같은 VLAN에 IPv4와 IPv6 주소를 각각 추가하면 두 버전을 함께 사용합니다. NAT는 IPv4만 지원합니다. 라우팅 ACL은 DNAT 이후 주소를 검사합니다. 변경 적용 중 데이터 통신이 잠시 중단되고 NAT 연결이 초기화될 수 있습니다.</AlertDescription></Alert>
        {!routingAvailable ? <Alert><AlertDescription>이 장비의 라우팅 실행부가 활성화되지 않았습니다. 설정을 편집할 수 있지만 적용은 장비에서 거부됩니다.</AlertDescription></Alert> : null}
        <Group title="라우팅 설정"><EditField label="라우팅 활성화" value={routing.enabled} options={choices} onChange={v => setRoute('enabled', v === 'true')} /><EditField label="라우팅 ACL 프로필" value={routing.aclProfile} options={[...empty, ...profiles.map(p => [p.id, p.id] as [string, string])]} onChange={v => setRoute('aclProfile', v)} /><EditField label="허용된 연결의 응답 자동 허용" value={Boolean(routing.stateful)} options={choices} onChange={v => setRoute('stateful', v === 'true')} /></Group>
        {!ipv6Available ? <p className="text-sm text-muted-foreground">현재 장비는 IPv6 라우팅을 지원하지 않습니다. IPv4 주소만 사용하세요.</p> : null}
        {routing.interfaces.map((item, i) => <Group key={i} title={`VLAN 인터페이스 ${i + 1}`}><EditField label="VLAN ID" numeric value={item.vlan} onChange={v => setList('interfaces', i, { vlan: v })} /><EditField label="IPv4 또는 IPv6 주소 / 프리픽스" value={item.address} onChange={v => setList('interfaces', i, { address: v })} /><Button variant="outline" onClick={() => removeRoute('interfaces', i)}>인터페이스 삭제</Button></Group>)}
        <Button variant="outline" onClick={() => setRoute('interfaces', [...routing.interfaces, { vlan: 10, address: '' }])}>VLAN 인터페이스 추가</Button>
        {(routing.routes ?? []).map((item, i) => <Group key={i} title={`정적 경로 ${i + 1}`}>{[['destination', '목적지 CIDR'], ['gateway', '다음 홉 IP 주소'], ['vlan', '출력 VLAN'], ['metric', '메트릭']].map(([key, label]) => <EditField key={key} label={label} numeric={key === 'vlan' || key === 'metric'} value={item[key]} onChange={v => setList('routes', i, { [key]: v })} />)}<Button variant="outline" onClick={() => removeRoute('routes', i)}>경로 삭제</Button></Group>)}
        <Button variant="outline" onClick={() => setRoute('routes', [...(routing.routes ?? []), { destination: '0.0.0.0/0', gateway: '', vlan: 10, metric: 100 }])}>정적 경로 추가</Button>
        {(routing.nat ?? []).map((item, i) => <Group key={i} title={`NAT ${string(item.id)}`}><EditField label="변환 방식" value={item.type} options={[["snat", "SNAT · 출발지 변환"], ["dnat", "DNAT · 목적지 변환"]]} onChange={v => setList('nat', i, { type: v })} /><EditField label="VLAN · SNAT 출력 / DNAT 입력" numeric value={item.vlan} onChange={v => setList('nat', i, { vlan: v })} />
          {['source', 'destination'].map((key, n) => <EditField key={key} label={n === 0 ? '출발지 CIDR · 비우면 모두' : '목적지 CIDR · DNAT는 입력 인터페이스 /32'} value={item[key]} onChange={v => setList('nat', i, { [key]: v })} />)}
          <EditField label="프로토콜" value={item.protocol} options={[["", "모두"], ["tcp", "TCP"], ["udp", "UDP"]]} onChange={v => setList('nat', i, { protocol: v })} /><EditField label="목적지 포트 · 0은 모두" numeric value={item.destinationPort ?? 0} onChange={v => setList('nat', i, { destinationPort: v })} />
          <EditField label="변환 IPv4 · SNAT는 출력 인터페이스 주소" value={item.translatedAddress} onChange={v => setList('nat', i, { translatedAddress: v })} /><EditField label="변환 포트 · DNAT만, 0은 유지" numeric value={item.translatedPort ?? 0} onChange={v => setList('nat', i, { translatedPort: v })} /><Button variant="outline" onClick={() => removeRoute('nat', i)}>NAT 삭제</Button></Group>)}
        <Button variant="outline" onClick={() => setRoute('nat', [...(routing.nat ?? []), { id: uid('nat'), type: 'snat', vlan: 10, translatedAddress: '' }])}>NAT 추가</Button>
      </FieldGroup></TabsContent>
      <TabsContent value="acl"><FieldGroup>
        <Alert><AlertDescription>우선순위 숫자가 큰 규칙부터 검사합니다. 기본 차단 프로필을 데이터 포트에 연결할 때 ARP 허용 규칙도 추가하세요. 설정된 고급 조건은 JSON에서 확인할 수 있으며 화면 편집 시 보존됩니다.</AlertDescription></Alert>
        {profiles.map((profile, i) => <FieldSet key={profile.id} className="rounded-md border p-4"><FieldLegend>{profile.id}</FieldLegend><FieldGroup>
          <EditField label="ACL 프로필 설명" value={profile.description} onChange={v => setACL(i, { description: v })} /><EditField label="일치하지 않는 패킷" value={profile.defaultAction ?? 'permit'} options={actions} onChange={v => setACL(i, { defaultAction: String(v) })} />
          {profile.rules.map((rule, j) => {
            const patch = (p: Partial<Rule>) => setACL(i, { rules: profile.rules.map((r, k) => k === j ? { ...r, ...p } : r) });
            const match = (key: string, v: unknown) => patch({ match: { ...rule.match, [key]: v } });
            return <Group key={rule.id} title={`규칙 ${j + 1} · ${rule.id}`}><EditField label="우선순위" numeric value={rule.priority} onChange={v => patch({ priority: Number(v) })} /><EditField label="동작" value={rule.action} options={[...actions, ['rate-limit', '속도 제한 · 입력 포트 전용']]} onChange={v => patch({ action: String(v), rateLimitPps: v === 'rate-limit' ? Number(rule.rateLimitPps) || 100 : 0 })} />
              {rule.action === 'rate-limit' ? <EditField label="초당 허용 패킷 수 (PPS)" numeric value={rule.rateLimitPps ?? 100} onChange={v => patch({ rateLimitPps: Number(v) })} /> : null}
              <EditField label="이더넷 유형" value={rule.match.etherType} options={[["", "모두"], ["ipv4", "IPv4"], ["arp", "ARP"], ["ipv6", "IPv6"]]} onChange={v => match('etherType', v)} /><EditField label="IP 프로토콜" value={rule.match.ipProtocol} options={[["", "모두"], ["tcp", "TCP"], ["udp", "UDP"], ["icmp", "ICMPv4"], ["icmp6", "ICMPv6"]]} onChange={v => match('ipProtocol', v)} />
              {[['ipv4Source', '출발지 IPv4 CIDR'], ['ipv4Dest', '목적지 IPv4 CIDR'], ['ipv6Source', '출발지 IPv6 CIDR'], ['ipv6Dest', '목적지 IPv6 CIDR'], ['sourceMac', '출발지 MAC · 데이터 포트 전용'], ['destMac', '목적지 MAC · 데이터 포트 전용'], ['l4SourcePort', '출발지 포트 · 0은 모두'], ['l4DestPort', '목적지 포트 · 0은 모두'], ['vlan', '입력 VLAN · 0은 모두']].map(([key, label]) => <EditField key={key} label={label} value={rule.match[key]} numeric={key === 'vlan' || key.startsWith('l4')} onChange={v => match(key, v)} />)}
              <Button variant="outline" onClick={() => setACL(i, { rules: profile.rules.filter((_, k) => k !== j) })}>규칙 삭제</Button></Group>;
          })}
          <Button variant="outline" onClick={() => setACL(i, { rules: [...profile.rules, { id: uid('rule'), priority: Math.max(0, ...profile.rules.map(r => r.priority)) + 10, action: 'permit', match: {} }] })}>규칙 추가</Button>
          <Button variant="outline" disabled={references(profile.id)} onClick={() => edit(next => { next.acl = { ...next.acl, profiles: profiles.filter((_, j) => j !== i) }; })}>{references(profile.id) ? '사용 중인 프로필' : 'ACL 프로필 삭제'}</Button>
        </FieldGroup></FieldSet>)}
        <Button variant="outline" onClick={() => edit(next => { next.acl = { ...next.acl, profiles: [...profiles, { id: uid('acl'), defaultAction: 'drop', rules: [] }] }; })}>ACL 프로필 추가</Button>
      </FieldGroup></TabsContent>
      <TabsContent value="ports"><FieldGroup>
        <Alert><AlertDescription>데이터 포트의 역할·VLAN·MTU와 입력·출력 ACL을 설정합니다. 역할이나 VLAN 변경 시 연결이 끊길 수 있으므로 임시 적용 후 통신을 확인하세요.</AlertDescription></Alert>
        {config?.ovs.ports.filter(p => p.role !== 'mirror-output').map(port => <Group key={port.name} title={`포트 ${port.name}`}>
          <EditField label="포트 설명" value={port.description} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)!.description = v; })} />
          <EditField label="포트 역할" value={port.role} options={[["access", "Access"], ["trunk", "Trunk"]]} onChange={v => edit(next => { const p = next.ovs.ports.find(p => p.name === port.name)!; if (v === 'access') { p.vlan = p.vlans?.[0] ?? 10; delete p.vlans; delete p.nativeVlan; delete p.nativeTagged; } else { p.vlans = [p.vlan ?? 10]; delete p.vlan; } p.role = String(v); })} />
          <EditField label="MTU · 0은 기본값 1500" numeric value={port.mtu ?? 0} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)!.mtu = v; })} />
          {port.role === 'trunk' ? <><EditField label="Native VLAN · 0은 사용 안 함" numeric value={port.nativeVlan ?? 0} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)!.nativeVlan = v; })} /><EditField label="Native VLAN 태그 유지" value={Boolean(port.nativeTagged)} options={choices} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)!.nativeTagged = v === 'true'; })} /></> : null}
          <EditField label="VLAN · Access는 1개, Trunk는 쉼표 구분" value={port.role === 'access' ? port.vlan : port.vlans?.join(',')} onChange={v => edit(next => { const p = next.ovs.ports.find(p => p.name === port.name)!; if (p.role === 'access') p.vlan = Number(v); else p.vlans = String(v).split(',').map(Number); })} />
          {(['aclIngress', 'aclEgress'] as const).map((key, n) => <EditField key={key} label={n === 0 ? '입력 ACL' : '출력 ACL'} value={port[key]} options={[...empty, ...profiles.map(p => [p.id, p.id] as [string, string])]} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)![key] = String(v); })} />)}
        </Group>)}
      </FieldGroup></TabsContent>
      <TabsContent value="security"><FieldGroup>
        <Alert><AlertDescription>정적 MAC·IP 바인딩, ARP 검사, IP Source Guard, BPDU Guard 및 Storm Control을 설정합니다. DHCP 학습·802.1X·MACsec 지원 여부는 상태 탭에서 확인하세요.</AlertDescription></Alert>
        {guards.map((guard, i) => <FieldSet key={guard.id} className="rounded-md border p-4"><FieldLegend>{guard.id}</FieldLegend><FieldGroup className="grid grid-cols-1 md:grid-cols-2">
          <EditField label="보안 프로필 설명" value={guard.description} onChange={v => setGuard(i, { description: v })} />
          {Number(guard.stormControl?.unknownUnicastPps) > 0 ? <Alert><AlertDescription>알 수 없는 유니캐스트 제한은 현재 실행부가 지원하지 않습니다. 기존 값을 고급 JSON에서 해제해야 적용할 수 있습니다.</AlertDescription></Alert> : null}
          {[["portSecurity", "MAC 접근 제한"], ['dynamicArpInspection', 'ARP 검사 (DAI)'], ['ipSourceGuard', 'IP Source Guard'], ['bpduGuard', 'BPDU Guard']].map(([key, label]) => <EditField key={key} label={label} value={Boolean(guard[key])} options={choices} onChange={v => setGuard(i, { [key]: v === 'true' })} />)}
          <EditField label="허용 MAC · 쉼표 구분" value={guard.allowedSourceMacs?.join(', ')} onChange={v => setGuard(i, { allowedSourceMacs: String(v).split(',').map(s => s.trim()).filter(Boolean) })} />
          <EditField label="DHCP 서버 응답 차단 · 비신뢰 포트" value={Boolean(guard.dhcpSnooping?.enabled && !guard.dhcpSnooping?.trusted)} options={choices} onChange={v => setGuard(i, { dhcpSnooping: { ...guard.dhcpSnooping, enabled: v === 'true', trusted: false } })} />
          {['broadcastPps', 'multicastPps'].map(key => <EditField key={key} label={`${key} · 0은 제한 없음`} numeric value={guard.stormControl?.[key] ?? 0} onChange={v => setGuard(i, { stormControl: { ...guard.stormControl, [key]: v } })} />)}
          {(guard.bindings ?? []).map((binding, j) => <Group key={j} title={`정적 바인딩 ${j + 1}`}>{[['mac', 'MAC 주소'], ['ipv4', 'IPv4 주소'], ['vlan', 'VLAN']].map(([key, label]) => <EditField key={key} label={label} value={binding[key]} numeric={key === 'vlan'} onChange={v => setGuard(i, { bindings: guard.bindings!.map((b, k) => k === j ? { ...b, [key]: v } : b) })} />)}<Button variant="outline" onClick={() => setGuard(i, { bindings: guard.bindings!.filter((_, k) => k !== j) })}>바인딩 삭제</Button></Group>)}
          <Button variant="outline" onClick={() => setGuard(i, { bindings: [...(guard.bindings ?? []), { mac: '', ipv4: '', vlan: 10 }] })}>정적 바인딩 추가</Button>
          <Button variant="outline" disabled={config?.ovs.ports.some(p => p.securityProfile === guard.id)} onClick={() => edit(next => { next.security = { ...next.security, profiles: guards.filter((_, j) => j !== i) }; })}>보안 프로필 삭제</Button>
        </FieldGroup></FieldSet>)}
        <Button variant="outline" onClick={() => edit(next => { next.security = { ...next.security, profiles: [...guards, { id: uid('security') }] }; })}>보안 프로필 추가</Button>
        {config?.ovs.ports.filter(p => p.role !== 'mirror-output').map(port => <EditField key={port.name} label={`${port.name} 보안 프로필`} value={port.securityProfile} options={[...empty, ...guards.map(p => [p.id, p.id] as [string, string])]} onChange={v => edit(next => { next.ovs.ports.find(p => p.name === port.name)!.securityProfile = String(v); })} />)}
      </FieldGroup></TabsContent>
    </fieldset>
    <TabsContent value="advanced"><Field><FieldLabel htmlFor="router-config">설정 JSON</FieldLabel><Textarea id="router-config" className="min-h-80 font-mono" value={value} readOnly={disabled} onChange={e => onChange(e.target.value)} /></Field></TabsContent>
  </Tabs>;
}
