import { useEffect, useRef, useState } from "react";
import {
  huntApi,
  type HuntEpisodeDetail,
  type HuntEpisodeProof,
} from "../lib/huntApi";
import type { Role } from "../lib/types";

type Props = {
  id: string;
  role: Role;
  onSelect: (id: string) => void;
  onClose: () => void;
};
const stamp = (value: string) =>
  new Date(value).toLocaleString("ko-KR", { hour12: false });
export function HuntEpisodeEvidence(props: Props) {
  return (
    <EpisodeEvidenceContent key={`${props.id}:${props.role}`} {...props} />
  );
}
function EpisodeProof({ proof }: { proof: HuntEpisodeProof }) {
  return (
    <li className="hw-exception-proof">
      <p>
        {stamp(proof.createdAt)} · 실행 {proof.jobId}
      </p>
      {proof.origin === "LEGACY_LAST_RESULT" && (
        <p>
          이전 버전의 마지막 결과 사본입니다. 이전 실행 전체를 복구한 기록은
          아닙니다.
        </p>
      )}
      {proof.sourceId && (
        <p>
          병합 전 {proof.sourceId} → 대표 {proof.targetId}
        </p>
      )}
      <details>
        <summary>원문과 검증 해시</summary>
        <p>
          원본 SHA-256: <code>{proof.evidenceSha256}</code>
        </p>
        {proof.exportEvidenceSha256 && (
          <p>
            권한 사본 SHA-256: <code>{proof.exportEvidenceSha256}</code>
          </p>
        )}
        <pre>{proof.evidenceJson}</pre>
      </details>
    </li>
  );
}
function EpisodeEvidenceContent({ id, onSelect, onClose }: Props) {
  const [value, setValue] = useState<HuntEpisodeDetail | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [paging, setPaging] = useState<string | null>(null);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    setValue(null);
    setError("");
    setLoading(true);
    setPaging(null);
    void huntApi
      .findingEvidence(id)
      .then(
        (result) => {
          if (generation.current === current) setValue(result);
        },
        (cause) => {
          if (generation.current === current)
            setError(cause instanceof Error ? cause.message : String(cause));
        },
      )
      .finally(() => {
        if (generation.current === current) setLoading(false);
      });
    return () => {
      generation.current++;
    };
  }, [id, revision]);
  async function more(kind: "occurrences" | "merges") {
    const cursor = value?.[kind].nextCursor;
    if (cursor == null || paging) return;
    const current = generation.current;
    setPaging(kind);
    setError("");
    try {
      const result = await huntApi.findingProofs(id, kind, cursor);
      if (current === generation.current)
        setValue((previous) =>
          previous
            ? {
                ...previous,
                [kind]: {
                  ...result,
                  items: [
                    ...new Map(
                      [...previous[kind].items, ...result.items].map((item) => [
                        item.id,
                        item,
                      ]),
                    ).values(),
                  ],
                },
              }
            : previous,
        );
    } catch (cause) {
      if (current === generation.current)
        setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      if (current === generation.current) setPaging(null);
    }
  }
  return (
    <section
      className="data-panel hw-exception-detail"
      aria-label="Episode 실행·병합 근거"
    >
      <div className="panel-heading">
        <h3>Episode 실행·병합 근거</h3>
        <div className="hw-card-actions">
          <button
            type="button"
            disabled={loading || !!paging}
            onClick={() => setRevision((v) => v + 1)}
          >
            근거 최신 상태
          </button>
          <button type="button" onClick={onClose}>
            근거 닫기
          </button>
        </div>
      </div>
      {loading && <p role="status">실행·병합 근거를 불러오는 중입니다.</p>}
      {error && (
        <p className="hw-feedback hw-error" role="alert">
          {error}
        </p>
      )}
      {value && (
        <>
          <p>
            {value.scenario_id} · 규칙 버전 {value.rule_version} · 후보 버전{" "}
            {value.version}
          </p>
          <p>
            Episode 관측 구간: {stamp(value.firstSeen)} ~{" "}
            {stamp(value.lastSeen)}
          </p>
          <code>{value.id}</code>
          {value.merged_into && (
            <p>
              이 후보는 병합됐습니다.{" "}
              <button
                type="button"
                onClick={() => onSelect(value.merged_into!)}
              >
                대표 episode 열기
              </button>
            </p>
          )}
          <details className="hw-exception-proof">
            <summary>최근 실행 후보 요약</summary>
            <pre>{JSON.stringify(value.latestCandidate, null, 2)}</pre>
          </details>
          <h4>실행별 원본 후보</h4>
          <p>
            각 실행의 시점·조건과 원래 후보를 보존합니다. 같은 episode에 속해도
            근거를 덮어쓰지 않습니다.
          </p>
          {!value.occurrences.items.length && (
            <p>
              실행별 기록이 없습니다. 이전 버전의 최근 후보 요약을 확인하세요.
            </p>
          )}
          <ol>
            {value.occurrences.items.map((proof) => (
              <EpisodeProof key={proof.id} proof={proof} />
            ))}
          </ol>
          {value.occurrences.hasMore && (
            <button
              type="button"
              disabled={!!paging}
              onClick={() => {
                void more("occurrences");
              }}
            >
              실행 근거 20개 더 보기
            </button>
          )}
          <h4>Episode 병합 이력</h4>
          {!value.merges.items.length && <p>기록된 병합이 없습니다.</p>}
          <ol>
            {value.merges.items.map((proof) => (
              <EpisodeProof key={proof.id} proof={proof} />
            ))}
          </ol>
          {value.merges.hasMore && (
            <button
              type="button"
              disabled={!!paging}
              onClick={() => {
                void more("merges");
              }}
            >
              병합 이력 20개 더 보기
            </button>
          )}
        </>
      )}
    </section>
  );
}
