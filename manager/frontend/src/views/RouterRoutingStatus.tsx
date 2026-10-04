import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import type { RoutingSnapshot } from '@/lib/routerControl';

export function RouterRoutingStatus({ routing }: { routing?: RoutingSnapshot }) {
  return <Card><CardHeader><CardTitle>실제 라우팅 상태</CardTitle><CardDescription>장비에서 읽은 인터페이스와 경로입니다. 상태 새로고침으로 다시 조회합니다.</CardDescription></CardHeader><CardContent className="flex flex-col gap-4">
    {!routing ? <p>현재 장비 버전은 라우팅 상세 관측을 제공하지 않습니다.</p> : !routing.enabled ? <p>라우팅이 비활성화되어 있습니다.</p> : <>
      <div className="flex flex-wrap items-center gap-2"><Badge variant={routing.verified ? 'default' : 'destructive'}>{routing.verified ? '설정·장비 일치' : '장비 검증 실패'}</Badge><span className="text-sm">{routing.ipv6 ? 'IPv4 / IPv6' : 'IPv4'} · ACL {routing.aclProfile} · NAT {routing.natRules ?? 0}개 · 응답 자동 허용 {routing.stateful ? '사용' : '사용 안 함'}</span></div>
      {routing.error ? <Alert variant="destructive"><AlertDescription>{routing.error}</AlertDescription></Alert> : null}
      <Table><TableHeader><TableRow><TableHead>인터페이스</TableHead><TableHead>링크</TableHead><TableHead>실제 주소</TableHead></TableRow></TableHeader><TableBody>{(routing.interfaces ?? []).filter(item => item.ifname !== 'lo').map(item => <TableRow key={item.ifname}><TableCell>{item.ifname}</TableCell><TableCell>{item.operstate ?? '미확인'}</TableCell><TableCell>{(item.addr_info ?? []).map(address => <div key={address.local}>{address.local}/{address.prefixlen}{address.tentative ? ' · 주소 검사 중' : ''}{address.dadfailed ? ' · 중복 주소' : ''}</div>)}</TableCell></TableRow>)}</TableBody></Table>
      <Table><TableHeader><TableRow><TableHead>IP 버전</TableHead><TableHead>목적지</TableHead><TableHead>다음 홉 / 출력</TableHead><TableHead>메트릭 / 출처</TableHead></TableRow></TableHeader><TableBody>{(routing.routes ?? []).map((route, i) => <TableRow key={`${route.family}-${route.dst}-${route.dev}-${i}`}><TableCell>IPv{route.family}</TableCell><TableCell>{route.dst ?? 'default'}</TableCell><TableCell>{route.gateway ?? '직접 연결'} / {route.dev}</TableCell><TableCell>{route.metric ?? 0} / {route.protocol}</TableCell></TableRow>)}</TableBody></Table>
      <details><summary>인접 장비 ARP / NDP · {routing.neighbors?.length ?? 0}개</summary><Table><TableHeader><TableRow><TableHead>주소</TableHead><TableHead>인터페이스 / MAC</TableHead><TableHead>상태</TableHead></TableRow></TableHeader><TableBody>{(routing.neighbors ?? []).map((neighbor, i) => <TableRow key={`${neighbor.dev}-${neighbor.dst}-${i}`}><TableCell>{neighbor.dst}</TableCell><TableCell>{neighbor.dev} / {neighbor.lladdr ?? '미확인'}</TableCell><TableCell>{neighbor.state?.join(', ')}</TableCell></TableRow>)}</TableBody></Table></details>
    </>}
  </CardContent></Card>;
}
