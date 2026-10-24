import { useCallback, useEffect, useState } from "react";
import { huntApi, type HuntStorageStatus } from "../lib/huntApi";

/** Administrator rollout controls; submission and actual mutation completion stay distinct. */
export function HuntStoragePanel() {
  const [status, setStatus] = useState<HuntStorageStatus | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [partition, setPartition] = useState("");
  const update = useCallback((value: HuntStorageStatus) => {
    setStatus(value);
    setPartition((previous) =>
      value.partitions.some((item) => item.partition === previous)
        ? previous
        : (value.partitions[0]?.partition ?? ""),
    );
  }, []);
  const refresh = useCallback(async () => {
    try {
      update(await huntApi.storage());
      setError("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [update]);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  async function action(
    operation: () => Promise<HuntStorageStatus>,
    message: string,
  ) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      update(await operation());
      setNotice(message);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }
  const pending = (status?.pendingMutations ?? 0) > 0;
  // Poll only while storage reports real pending work; do not infer completion from elapsed time.
  useEffect(() => {
    if (!pending) return;
    const timer = window.setInterval(() => {
      void refresh();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [pending, refresh]);
  return (
    <section
      className="data-panel hw-config"
      aria-labelledby="hunt-storage-title"
    >
      <div className="panel-heading">
        <h3 id="hunt-storage-title">헌팅 저장소 · 조회 최적화</h3>
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            void refresh();
          }}
        >
          저장소 상태 새로고침
        </button>
      </div>
      {error && (
        <p className="hw-feedback hw-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="hw-feedback" role="status">
          {notice}
        </p>
      )}
      {status && (
        <>
          <p className="hw-help">
            {status.schema.ready
              ? "정규화 열 사용 가능"
              : "정규화 열 준비 필요"}{" "}
            ·{" "}
            {status.queryMode === "PHYSICAL"
              ? "물리 열 조회 사용"
              : "기존 JSON 조회 사용"}{" "}
            · 스키마 확인{" "}
            {new Date(status.schema.checkedAt).toLocaleString("ko-KR")}
          </p>
          <p className="hw-help">
            정규화 열을 설치하면 새 이벤트부터 값을 저장합니다. 과거 데이터는
            일별 파티션을 선택해 백필하세요. 백필 중에도 조회할 수 있으며
            저장소의 추가 공간과 I/O를 사용합니다.
          </p>
          {status.schema.error && <p role="alert">{status.schema.error}</p>}
          {!!status.schema.incompatible.length && (
            <p role="alert">
              정의가 다른 열 {status.schema.incompatible.length}개가 있습니다.
              마이그레이션 검토가 필요합니다.
            </p>
          )}
          {!status.schema.ready && (
            <button
              type="button"
              disabled={
                busy ||
                !!status.schema.error ||
                !!status.schema.incompatible.length
              }
              onClick={() => {
                void action(
                  huntApi.installReadModel,
                  "정규화 열을 설치했습니다. 과거 데이터의 백필 상태도 확인하세요.",
                );
              }}
            >
              정규화 열 설치
            </button>
          )}
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void action(
                () => huntApi.backfillReadModel(partition),
                "백필을 요청했습니다. 아래 저장소 변경 상태에서 실제 완료 여부를 확인하세요.",
              );
            }}
          >
            <label>
              백필 대상 일자
              <select
                aria-label="백필 대상 일자"
                value={partition}
                onChange={(event) => setPartition(event.target.value)}
                disabled={busy || pending}
              >
                {!status.partitions.length && (
                  <option value="">저장된 파티션 없음</option>
                )}
                {status.partitions.map((item) => (
                  <option key={item.partition} value={item.partition}>
                    {item.partition} ·{" "}
                    {Number(item.rows).toLocaleString("ko-KR")}건 ·{" "}
                    {(Number(item.bytes) / 1024 / 1024).toFixed(1)} MiB
                  </option>
                ))}
              </select>
            </label>
            <button
              type="submit"
              disabled={busy || pending || !status.schema.ready || !partition}
            >
              선택 일자 백필
            </button>
          </form>
          {pending && (
            <p role="status">
              저장소 변경 {status.pendingMutations}개 진행 중 · 5초마다 완료
              상태를 확인합니다.
            </p>
          )}
          <div className="hw-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>변경 ID</th>
                  <th>상태</th>
                  <th>남은 파트</th>
                  <th>오류 / 상세</th>
                </tr>
              </thead>
              <tbody>
                {status.mutations.map((item) => (
                  <tr key={item.id}>
                    <td>
                      {item.id}
                      <small>{item.create_time}</small>
                    </td>
                    <td>
                      {item.is_done
                        ? "완료"
                        : item.latest_fail_reason
                          ? "재시도 중 · 오류 확인"
                          : "진행 중"}
                    </td>
                    <td>{item.parts_to_do}</td>
                    <td>
                      {item.latest_fail_reason && (
                        <span role="alert">{item.latest_fail_reason}</span>
                      )}
                      <details>
                        <summary>변경 내용</summary>
                        <pre>{item.command}</pre>
                      </details>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!status.mutations.length && (
              <p className="hw-help">기록된 정규화 열 백필이 없습니다.</p>
            )}
          </div>
        </>
      )}
    </section>
  );
}
