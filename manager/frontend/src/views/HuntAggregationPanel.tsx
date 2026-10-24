import { Button } from "../components/ui/button";
import { Field, FieldGroup, FieldLabel } from "../components/ui/field";
import { NativeSelect } from "../components/ui/native-select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "../components/ui/table";
import type {
  HuntFieldV2,
  HuntFilter,
  HuntGroupV2,
  HuntQueryV2,
} from "../lib/huntApi";
import { groupFilter } from "../lib/huntFilters";

const numeric = (value: number | undefined) =>
  value === undefined ? "—" : Number(value).toLocaleString("ko-KR");

export function HuntAggregationPanel({
  fields,
  dimensions,
  metric,
  onDimensions,
  onMetric,
  onRun,
  busy,
  result,
  onFilter,
}: {
  fields: HuntFieldV2[];
  dimensions: string[];
  metric: string;
  onDimensions: (fields: string[]) => void;
  onMetric: (metric: string) => void;
  onRun: () => void;
  busy: boolean;
  result?: {
    query: HuntQueryV2;
    groups: HuntGroupV2[];
    groupHasMore?: boolean;
  };
  onFilter: (filter: HuntFilter) => void;
}) {
  const columns = result?.query.groupFields?.length
    ? result.query.groupFields
    : [result?.query.groupBy ?? "event_type"];
  const fieldLabel = (name: string) =>
    fields.find((f) => f.name === name)?.label ?? name;
  function change(index: number, value: string) {
    const next = [...dimensions];
    if (!value) next.splice(index);
    else next[index] = value;
    onDimensions([...new Set(next.filter(Boolean))]);
  }
  return (
    <section className="data-panel hw-aggregation" aria-label="복합 필드 분석">
      <div className="panel-heading">
        <h3>필드 분석</h3>
        <span>최대 3개 필드 · 전체 기간 집계</span>
      </div>
      <FieldGroup className="hw-aggregation-controls">
        {[0, 1, 2].map((index) => (
          <Field key={index}>
            <FieldLabel htmlFor={`hw-group-${index}`}>
              {index === 0 ? "집계 필드" : `집계 필드 ${index + 1}`}
            </FieldLabel>
            <NativeSelect
              id={`hw-group-${index}`}
              value={dimensions[index] ?? ""}
              disabled={busy || (index === 2 && dimensions.length < 2)}
              onChange={(e) => change(index, e.target.value)}
            >
              {index > 0 && <option value="">선택 안 함</option>}
              {fields.map((field) => (
                <option
                  key={field.name}
                  value={field.name}
                  disabled={dimensions.some(
                    (value, slot) => slot !== index && value === field.name,
                  )}
                >
                  {field.label}
                </option>
              ))}
            </NativeSelect>
          </Field>
        ))}
        <Field>
          <FieldLabel htmlFor="hw-group-metric">집계 측정값</FieldLabel>
          <NativeSelect
            id="hw-group-metric"
            value={metric}
            onChange={(e) => onMetric(e.target.value)}
            disabled={busy}
          >
            <option value="count">이벤트 수</option>
            <option value="distinct_hosts">고유 관측 자산</option>
            <option value="bytes_sent">송신 바이트</option>
            <option value="bytes_received">수신 바이트</option>
          </NativeSelect>
        </Field>
        <Button type="button" onClick={onRun} disabled={busy}>
          집계 실행
        </Button>
      </FieldGroup>
      {result ? (
        <>
          <div
            className="hw-aggregation-scroll"
            role="region"
            aria-label="복합 집계 표 가로 스크롤"
            tabIndex={0}
          >
            <Table aria-label="전체 기간 복합 집계">
              <TableHeader>
                <TableRow>
                  {columns.map((name) => (
                    <TableHead key={name}>{fieldLabel(name)}</TableHead>
                  ))}
                  <TableHead>이벤트 수</TableHead>
                  <TableHead>관측 자산</TableHead>
                  <TableHead>송신 바이트</TableHead>
                  <TableHead>수신 바이트</TableHead>
                  <TableHead>조건</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {result.groups.map((row, index) => {
                  const values = row.dimensions ?? { [columns[0]]: row.value };
                  return (
                    <TableRow key={JSON.stringify([values, index])}>
                      {columns.map((name) => (
                        <TableCell key={name}>
                          {values[name] === null || values[name] === ""
                            ? "(값 없음)"
                            : (values[name] ?? "—")}
                        </TableCell>
                      ))}
                      <TableCell>{numeric(row.count)}</TableCell>
                      <TableCell>{numeric(row.hostCount)}</TableCell>
                      <TableCell>{numeric(row.bytesSent)}</TableCell>
                      <TableCell>{numeric(row.bytesReceived)}</TableCell>
                      <TableCell>
                        <div className="flex gap-2">
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            aria-label={`집계 ${index + 1} 조합 포함`}
                            onClick={() => onFilter(groupFilter(values))}
                          >
                            포함
                          </Button>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            aria-label={`집계 ${index + 1} 조합 제외`}
                            onClick={() => onFilter(groupFilter(values, true))}
                          >
                            제외
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
          <p className="hw-help">
            마지막 실행 기준의 상위 {result.groups.length}개 조합입니다.
            {result.groupHasMore
              ? " 다른 조합이 더 있습니다. 조건이나 기간을 좁혀 확인하세요."
              : ""}
            {result.groups.length === 0
              ? " 일치하는 데이터가 없습니다."
              : " 조합 포함·제외는 모든 필드 값을 하나의 조건으로 적용합니다."}
          </p>
        </>
      ) : (
        <p className="hw-help">
          집계를 실행하면 이벤트 수·고유 관측 자산·송수신 바이트를 함께
          비교합니다.
        </p>
      )}
    </section>
  );
}
