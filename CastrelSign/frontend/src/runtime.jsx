import { createContext, useContext, useEffect, useState } from "react";
import { api } from "./api";
import { Panel, Empty, Button, Notice, Field, Modal } from "./ui";
export const Runtime = createContext(null);
export const useRuntime = () => useContext(Runtime);
export function useLoad(paths) {
  const { revision } = useRuntime(),
    key = JSON.stringify(paths),
    [state, setState] = useState({ loading: true });
  useEffect(() => {
    const abort = new AbortController();
    setState({ loading: true, key });
    const list = JSON.parse(key);
    Promise.all(
      (Array.isArray(list) ? list : [list]).map((p) =>
        api(p, { signal: abort.signal }),
      ),
    )
      .then((values) => {
        if (!abort.signal.aborted)
          setState({ key, data: Array.isArray(list) ? values : values[0] });
      })
      .catch((error) => {
        if (!abort.signal.aborted) setState({ key, error });
      });
    return () => abort.abort();
  }, [key, revision]);
  return state.key === key ? state : { loading: true };
}
export function Loaded({ state, children }) {
  const { refresh } = useRuntime();
  if (state.loading)
    return (
      <Panel>
        <div role="status" className="loading">
          <span className="loading-line" />
          <h2>정보를 불러오는 중입니다</h2>
        </div>
      </Panel>
    );
  if (state.error)
    return (
      <Panel>
        <Empty
          title={
            state.error.status === 403
              ? "이 화면에 접근할 권한이 없습니다"
              : "정보를 불러오지 못했습니다"
          }
          body={state.error.message}
          action={<Button onClick={refresh}>다시 시도</Button>}
        />
        {state.error.requestId && (
          <p className="count-note">요청 ID: {state.error.requestId}</p>
        )}
      </Panel>
    );
  return children(state.data);
}
export function ActionDialog({ action, onClose, onDone, session }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [secret, setSecret] = useState(null);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    const values = Object.fromEntries(new FormData(e.currentTarget));
    try {
      const result = await action.run(values, values.mfa);
      onDone();
      if (result?.token) setSecret(result.token);
      else onClose();
    } catch (e) {
      setError(e.message + (e.requestId ? ` (요청 ID: ${e.requestId})` : ""));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={secret ? "등록 토큰 발급 완료" : action.title}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <form onSubmit={submit}>
        {secret ? (
          <>
            <Notice tone="warn">
              토큰 원문은 지금 한 번만 표시됩니다. 대상 서비스에 안전하게
              전달하세요.
            </Notice>
            <textarea
              aria-label="발급된 등록 토큰"
              className="pem-view"
              readOnly
              value={secret}
            />
            <div className="dialog-actions">
              <Button type="button" onClick={onClose}>
                확인 후 닫기
              </Button>
            </div>
          </>
        ) : (
          <>
            <Notice tone={action.danger ? "warn" : "info"}>
              {action.description || "변경할 대상과 내용을 확인하세요."}
            </Notice>
            {action.fields?.map((f) => (
              <Field key={f.name} label={f.label} hint={f.hint}>
                {f.type === "select" ? (
                  <select
                    name={f.name}
                    defaultValue={f.value}
                    required={f.required !== false}
                  >
                    {f.options.map((o) => (
                      <option
                        key={typeof o === "string" ? o : o.value}
                        value={typeof o === "string" ? o : o.value}
                      >
                        {typeof o === "string" ? o : o.label}
                      </option>
                    ))}
                  </select>
                ) : f.type === "textarea" ? (
                  <textarea
                    name={f.name}
                    rows={3}
                    required={f.required !== false}
                    maxLength={f.maxLength || 1000}
                    defaultValue={f.value}
                  />
                ) : (
                  <input
                    name={f.name}
                    type={f.type || "text"}
                    defaultValue={f.value}
                    required={f.required !== false}
                    min={f.min}
                    max={f.max}
                    maxLength={f.maxLength || 500}
                    accept={f.accept}
                  />
                )}
              </Field>
            ))}
            {session.mfaRequired && (
              <Field
                label="MFA 인증 코드"
                hint="현재 6자리 코드를 입력하세요. 같은 코드는 재사용할 수 없습니다."
              >
                <input
                  name="mfa"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  required
                />
              </Field>
            )}
            <label className="checkbox">
              <input type="checkbox" required />
              작업의 대상과 영향을 확인했습니다.
            </label>
            {error && <Notice tone="bad">{error}</Notice>}
            <div className="dialog-actions">
              <Button type="button" disabled={busy} onClick={onClose}>
                취소
              </Button>
              <Button
                primary={!action.danger}
                danger={action.danger}
                disabled={busy}
                type="submit"
              >
                {busy ? "처리 중…" : action.submit || "확인 후 실행"}
              </Button>
            </div>
          </>
        )}
      </form>
    </Modal>
  );
}
export const reasonField = {
  name: "reason",
  label: "작업 사유",
  type: "textarea",
};
