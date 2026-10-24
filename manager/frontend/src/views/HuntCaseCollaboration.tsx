import { useEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import {
  huntApi,
  type HuntCaseAnalyst,
  type HuntCaseReview,
  type HuntCaseWorkflow,
  type HuntResource,
} from "../lib/huntApi";

const labels: Record<string, string> = {
  REQUESTED: "검토 요청",
  APPROVED: "승인·종료",
  CHANGES_REQUESTED: "보완 요청",
  WITHDRAWN: "요청 철회",
};
const person = (value: HuntCaseAnalyst | null) =>
  value
    ? `${value.displayName || value.username} (${value.username})${!value.enabled || value.role === "VIEWER" ? " · 현재 분석가 권한 없음" : ""}`
    : "미지정";

export function HuntCaseCollaboration({
  item,
  dirty,
  disabled,
  onWorkflow,
  onChange,
  onReload,
}: {
  item: HuntResource;
  dirty: boolean;
  disabled: boolean;
  onWorkflow: (id: string, workflow: HuntCaseWorkflow | null) => void;
  onChange: (item: HuntResource, preserveDraft: boolean) => void;
  onReload: () => Promise<void>;
}) {
  const [workflow, setWorkflow] = useState<HuntCaseWorkflow | null>(null);
  const [analysts, setAnalysts] = useState<HuntCaseAnalyst[]>([]);
  const [hasMoreAnalysts, setHasMoreAnalysts] = useState(false);
  const [search, setSearch] = useState("");
  const [assignee, setAssignee] = useState("");
  const [reviewer, setReviewer] = useState("");
  const [reason, setReason] = useState("");
  const [history, setHistory] = useState<HuntCaseReview[]>([]);
  const [before, setBefore] = useState<number | null>(null);
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const generation = useRef(0);
  const analystGeneration = useRef(0);
  const callbacks = useRef({ onWorkflow, onChange, onReload });
  callbacks.current = { onWorkflow, onChange, onReload };
  useEffect(() => {
    const epoch = ++generation.current;
    setWorkflow(null);
    setLoaded(false);
    setWorking(false);
    setError("");
    setDetail(null);
    setHistory([]);
    setBefore(null);
    setAnalysts([]);
    setHasMoreAnalysts(false);
    setReason("");
    callbacks.current.onWorkflow(item.id, null);
    void (async () => {
      try {
        const value = await huntApi.caseWorkflow(item.id);
        if (generation.current !== epoch) return;
        setWorkflow(value);
        setHistory(value.history.items);
        setBefore(value.history.nextBefore);
        setAssignee(value.assignee ? String(value.assignee.id) : "");
        setReviewer(value.reviewer ? String(value.reviewer.id) : "");
        callbacks.current.onWorkflow(item.id, value);
        setLoaded(true);
        if (value.permissions.manage) {
          const choiceEpoch = ++analystGeneration.current;
          const choices = await huntApi.caseAnalysts("");
          if (
            generation.current !== epoch ||
            analystGeneration.current !== choiceEpoch
          )
            return;
          setAnalysts(choices.items);
          setHasMoreAnalysts(choices.hasMore);
        }
      } catch (cause) {
        if (generation.current === epoch) {
          setLoaded(true);
          setError(cause instanceof Error ? cause.message : String(cause));
        }
      }
    })();
    return () => {
      generation.current++;
    };
  }, [item.id, item.version]);

  const stale = !!workflow && workflow.version !== item.version;
  const busy = disabled || working || !loaded;
  const choices = [
    ...new Map(
      [
        ...(workflow?.assignee ? [workflow.assignee] : []),
        ...(workflow?.reviewer ? [workflow.reviewer] : []),
        ...analysts,
      ].map((value) => [value.id, value]),
    ).values(),
  ];
  async function action(
    kind: "assign" | "submit" | "approve" | "changes" | "reopen" | "reload",
  ) {
    const epoch = generation.current;
    setWorking(true);
    setError("");
    try {
      if (kind === "reload") {
        await callbacks.current.onReload();
        return;
      }
      if (stale)
        throw new Error("다른 사용자가 변경했습니다. 최신 버전을 불러오세요.");
      const changed =
        kind === "assign"
          ? await huntApi.assignCase(
              item,
              assignee ? Number(assignee) : null,
              reviewer ? Number(reviewer) : null,
            )
          : kind === "submit"
            ? await huntApi.submitCaseReview(item, reason)
            : kind === "reopen"
              ? await huntApi.reopenCase(item, reason)
              : await huntApi.decideCaseReview(
                  item,
                  workflow!.pendingReview!.id,
                  kind === "approve" ? "APPROVED" : "CHANGES_REQUESTED",
                  reason,
                );
      if (generation.current === epoch) {
        callbacks.current.onChange(changed, kind === "assign");
        setReason("");
      }
    } catch (cause) {
      if (generation.current === epoch)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (generation.current === epoch) setWorking(false);
    }
  }
  async function searchAnalysts() {
    const epoch = generation.current;
    const choiceEpoch = ++analystGeneration.current;
    setWorking(true);
    setError("");
    try {
      const value = await huntApi.caseAnalysts(search);
      if (
        generation.current === epoch &&
        analystGeneration.current === choiceEpoch
      ) {
        setAnalysts(value.items);
        setHasMoreAnalysts(value.hasMore);
      }
    } catch (cause) {
      if (generation.current === epoch)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (generation.current === epoch) setWorking(false);
    }
  }
  async function more() {
    if (before === null) return;
    const epoch = generation.current;
    setWorking(true);
    setError("");
    try {
      const value = await huntApi.caseReviews(item.id, before);
      if (generation.current === epoch) {
        setHistory((previous) => [...previous, ...value.items]);
        setBefore(value.nextBefore);
      }
    } catch (cause) {
      if (generation.current === epoch)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (generation.current === epoch) setWorking(false);
    }
  }
  async function inspect(reviewId: number) {
    const epoch = generation.current;
    setWorking(true);
    setError("");
    try {
      const value = await huntApi.caseReviewDetail(item.id, reviewId);
      if (generation.current === epoch) setDetail(value);
    } catch (cause) {
      if (generation.current === epoch)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (generation.current === epoch) setWorking(false);
    }
  }

  return (
    <section className="hw-case-collaboration" aria-label="조사 담당과 검토">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4>담당·검토</h4>
        <Badge variant="outline">조사 버전 {item.version}</Badge>
      </div>
      {!loaded && (
        <p role="status">조사 권한과 검토 기록을 확인하고 있습니다.</p>
      )}
      {error && (
        <Alert variant="destructive">
          <AlertTitle>협업 요청을 완료하지 못했습니다</AlertTitle>
          <AlertDescription>
            {error} 미저장 분석 내용은 유지됩니다.
          </AlertDescription>
        </Alert>
      )}
      {stale && (
        <Alert>
          <AlertTitle>서버에 더 최신 버전이 있습니다</AlertTitle>
          <AlertDescription>
            현재 초안의 기준은 버전 {item.version}, 서버는 {workflow.version}
            입니다. 최신 버전 불러오기는 미저장 내용을 교체합니다.
          </AlertDescription>
        </Alert>
      )}
      {workflow && (
        <>
          <dl className="hw-case-people">
            <div>
              <dt>담당자</dt>
              <dd>{person(workflow.assignee)}</dd>
            </div>
            <div>
              <dt>검토자</dt>
              <dd>{person(workflow.reviewer)}</dd>
            </div>
          </dl>
          {!workflow.permissions.edit && (
            <p>
              이 버전은 읽기 전용입니다. 담당 권한과 조사 상태에 따라
              분석·증거를 변경할 수 있습니다.
            </p>
          )}
          {workflow.permissions.manage && (
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor={`case-${item.id}-analyst-search`}>
                  분석가 검색
                </FieldLabel>
                <div className="flex gap-2">
                  <Input
                    id={`case-${item.id}-analyst-search`}
                    value={search}
                    maxLength={120}
                    onChange={(event) => setSearch(event.target.value)}
                    disabled={busy}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => void searchAnalysts()}
                    disabled={busy}
                  >
                    분석가 찾기
                  </Button>
                </div>
                {hasMoreAnalysts && (
                  <FieldDescription>
                    상위 100명입니다. 이름이나 계정으로 범위를 좁히세요.
                  </FieldDescription>
                )}
              </Field>
              <Field>
                <FieldLabel htmlFor={`case-${item.id}-assignee`}>
                  조사 담당자
                </FieldLabel>
                <NativeSelect
                  id={`case-${item.id}-assignee`}
                  value={assignee}
                  onChange={(event) => setAssignee(event.target.value)}
                  disabled={busy || stale}
                >
                  <option value="">미지정</option>
                  {choices.map((value) => (
                    <option
                      key={value.id}
                      value={value.id}
                      disabled={!value.enabled || value.role === "VIEWER"}
                    >
                      {person(value)}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
              <Field>
                <FieldLabel htmlFor={`case-${item.id}-reviewer`}>
                  독립 검토자
                </FieldLabel>
                <NativeSelect
                  id={`case-${item.id}-reviewer`}
                  value={reviewer}
                  onChange={(event) => setReviewer(event.target.value)}
                  disabled={busy || stale}
                >
                  <option value="">미지정</option>
                  {choices.map((value) => (
                    <option
                      key={value.id}
                      value={value.id}
                      disabled={
                        !value.enabled ||
                        value.role === "VIEWER" ||
                        String(value.id) === assignee
                      }
                    >
                      {person(value)}
                    </option>
                  ))}
                </NativeSelect>
                <FieldDescription>
                  담당자·제출자·마지막 작성자와 다른 분석가가 검토합니다. 지정된
                  분석가는 개인 조사도 열람합니다.
                </FieldDescription>
              </Field>
              <Button
                type="button"
                variant="outline"
                onClick={() => void action("assign")}
                disabled={
                  busy || stale || (assignee !== "" && assignee === reviewer)
                }
              >
                담당·검토자 저장
              </Button>
            </FieldGroup>
          )}
          {dirty && (
            <p role="status">변경한 분석을 먼저 저장한 뒤 검토를 요청하세요.</p>
          )}
          {workflow.permissions.edit && workflow.reviewReadiness.length > 0 && (
            <ul aria-label="검토 요청 준비">
              {workflow.reviewReadiness.map((value) => (
                <li key={value}>{value}</li>
              ))}
            </ul>
          )}
          {workflow.pendingReview && (
            <p>
              검토 #{workflow.pendingReview.id} · 제출 버전{" "}
              {workflow.pendingReview.caseVersion}. 검토 중에는 내용과 근거를
              변경할 수 없습니다.
            </p>
          )}
          {workflow.evidenceChanged && (
            <Alert>
              <AlertTitle>제출한 근거가 달라졌습니다</AlertTitle>
              <AlertDescription>
                만료된 근거가 포함돼 승인할 수 없습니다. 보완을 요청하거나
                분석을 재개하세요.
              </AlertDescription>
            </Alert>
          )}
          {(workflow.permissions.edit ||
            workflow.permissions.decide ||
            workflow.permissions.reopen) && (
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor={`case-${item.id}-reason`}>
                  요청·판정·재개 이유
                </FieldLabel>
                <Textarea
                  id={`case-${item.id}-reason`}
                  value={reason}
                  maxLength={4000}
                  rows={3}
                  onChange={(event) => setReason(event.target.value)}
                  disabled={busy}
                />
                <FieldDescription>
                  검토할 쟁점이나 판정 근거를 기록하세요. 변경 이력에 남습니다.
                </FieldDescription>
              </Field>
              <div className="flex flex-wrap gap-2">
                {workflow.permissions.edit && (
                  <Button
                    type="button"
                    onClick={() => void action("submit")}
                    disabled={
                      busy ||
                      stale ||
                      dirty ||
                      !reason.trim() ||
                      !workflow.permissions.requestReview
                    }
                  >
                    독립 검토 요청
                  </Button>
                )}
                {workflow.permissions.decide && (
                  <>
                    <Button
                      type="button"
                      onClick={() => void action("approve")}
                      disabled={
                        busy ||
                        stale ||
                        !reason.trim() ||
                        !workflow.permissions.approve
                      }
                    >
                      승인하고 종료
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => void action("changes")}
                      disabled={busy || stale || !reason.trim()}
                    >
                      보완 요청
                    </Button>
                  </>
                )}
                {workflow.permissions.reopen && (
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => void action("reopen")}
                    disabled={busy || stale || !reason.trim()}
                  >
                    이유를 기록하고 분석 재개
                  </Button>
                )}
              </div>
            </FieldGroup>
          )}
        </>
      )}
      <Button
        type="button"
        variant="outline"
        onClick={() => void action("reload")}
        disabled={busy}
      >
        최신 버전 불러오기 · 미저장 내용 교체
      </Button>
      <h4>버전별 검토 이력 · {history.length}건 표시</h4>
      {history.length === 0 && <p>아직 검토 요청이 없습니다.</p>}
      <ol aria-label="버전별 검토 이력">
        {history.map((value) => (
          <li key={value.id}>
            <div className="flex flex-wrap items-center gap-2">
              <strong>
                검토 #{value.id} · 버전 {value.caseVersion}
              </strong>
              <Badge variant="secondary">
                {labels[value.state] ?? value.state}
              </Badge>
            </div>
            <p>
              제출 사용자 {value.submittedBy} · 검토 사용자 {value.reviewerId} ·{" "}
              {new Date(value.createdAt).toLocaleString("ko-KR")}
            </p>
            <p>요청: {value.requestNote}</p>
            {value.decisionNote && <p>결과: {value.decisionNote}</p>}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void inspect(value.id)}
              disabled={busy}
            >
              검토 #{value.id} 제출 버전 보기
            </Button>
          </li>
        ))}
      </ol>
      {before !== null && (
        <Button
          type="button"
          variant="outline"
          onClick={() => void more()}
          disabled={busy}
        >
          이전 검토 20건 더 보기
        </Button>
      )}
      {detail && (
        <details open>
          <summary>고정된 검토 내용·근거 목록·SHA-256</summary>
          <pre>{JSON.stringify(detail, null, 2)}</pre>
        </details>
      )}
    </section>
  );
}
