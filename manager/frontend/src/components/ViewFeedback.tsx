export function ViewFeedback({ loading, error, hasData = false }: { loading: boolean; error: string; hasData?: boolean }) {
  return <>
    {error && <div className="notice error" role="alert">{error}{hasData && ' 마지막으로 조회한 데이터를 표시합니다.'}</div>}
    {loading && <div className="notice" role="status">데이터를 불러오는 중입니다.</div>}
  </>;
}
