import { useEffect, useRef } from "react";
import { stateLabels } from "./data";

export function Icon({ name, className = "" }) {
  return (
    <span aria-hidden="true" className={`sap-icon--${name} ${className}`} />
  );
}
export function Button({
  children,
  icon,
  primary,
  danger,
  className = "",
  ...props
}) {
  return (
    <button
      className={`fd-button button ${primary ? "primary" : ""} ${danger ? "danger" : ""} ${className}`}
      {...props}
    >
      {icon && <Icon name={icon} />}
      <span>{children}</span>
    </button>
  );
}
export function Status({ state, label }) {
  const tone = ["ACTIVE", "APPROVED", "SUCCESS"].includes(state)
    ? "good"
    : ["EXPIRING", "PENDING", "PENDING_DELETION"].includes(state)
      ? "warn"
      : ["COMPROMISED", "REVOKED", "DENIED"].includes(state)
        ? "bad"
        : "neutral";
  return (
    <span className={`status ${tone}`}>
      <Icon
        name={
          tone === "good"
            ? "accept"
            : tone === "warn"
              ? "message-warning"
              : tone === "bad"
                ? "message-error"
                : "status-inactive"
        }
      />
      {label || stateLabels[state] || state}
    </span>
  );
}
export function Notice({ children, tone = "info" }) {
  return (
    <div className={`notice ${tone}`}>
      <Icon
        name={
          tone === "warn"
            ? "message-warning"
            : tone === "bad"
              ? "message-error"
              : "hint"
        }
      />
      <div>{children}</div>
    </div>
  );
}
export function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function Facts({ rows }) {
  return (
    <dl className="facts">
      {rows.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
export function Panel({ title, action, children, className = "" }) {
  return (
    <section className={`panel ${className}`}>
      {title && (
        <div className="panel-heading">
          <h2>{title}</h2>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Tabs({ items, value, onChange }) {
  return (
    <div className="tabs" role="group" aria-label="상세 보기">
      {items.map(([id, label]) => (
        <button
          key={id}
          aria-pressed={value === id}
          className={value === id ? "selected" : ""}
          onClick={() => onChange(id)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
export function Search({
  value,
  onChange,
  placeholder = "검색어를 입력하세요",
  label = "목록 검색",
}) {
  return (
    <label className="search">
      <Icon name="search" />
      <input
        aria-label={label}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
      {value && (
        <button aria-label="검색어 지우기" onClick={() => onChange("")}>
          <Icon name="decline" />
        </button>
      )}
    </label>
  );
}
export function Empty({
  title = "표시할 항목이 없습니다",
  body = "검색어나 필터를 변경해 보세요.",
  action,
}) {
  return (
    <div className="empty">
      <Icon name="document" />
      <h2>{title}</h2>
      <p>{body}</p>
      {action}
    </div>
  );
}
export function Table({ columns, rows, selected, onSelect, label = "목록" }) {
  if (!rows.length) return <Empty />;
  return (
    <div className="table-scroll">
      <table className="fd-table table" aria-label={label}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c[0]} scope="col">
                {c[1]}
              </th>
            ))}
            {onSelect && (
              <th>
                <span className="sr-only">상세</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className={selected === row.id ? "selected" : ""}>
              {columns.map(([key, , render], i) => (
                <td key={key}>
                  {onSelect && i === 0 ? (
                    <button
                      className="row-link"
                      aria-label={`${row[key]} 상세 보기`}
                      onClick={() => onSelect(row)}
                    >
                      {render ? render(row) : row[key]}
                    </button>
                  ) : render ? (
                    render(row)
                  ) : (
                    row[key]
                  )}
                </td>
              ))}
              {onSelect && (
                <td className="row-arrow">
                  <button
                    aria-label={`${row[columns[0][0]]} 선택`}
                    onClick={() => onSelect(row)}
                  >
                    <Icon name="slim-arrow-right" />
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function Steps({ labels, current = 0 }) {
  return (
    <ol className="steps">
      {labels.map((label, i) => (
        <li
          key={label}
          className={i < current ? "complete" : i === current ? "current" : ""}
          aria-current={i === current ? "step" : undefined}
        >
          <span>{i < current ? <Icon name="accept" /> : i + 1}</span>
          <small>{label}</small>
        </li>
      ))}
    </ol>
  );
}
export function Modal({ title, children, onClose, wide = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const dialog = ref.current;
    const trigger = document.activeElement;
    dialog.showModal();
    return () => {
      dialog.close();
      if (trigger instanceof HTMLElement && trigger.isConnected)
        trigger.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? "wide" : ""}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
      aria-labelledby="dialog-title"
    >
      <div className="dialog-heading">
        <h2 id="dialog-title">{title}</h2>
        <button
          className="icon-button"
          aria-label="대화상자 닫기"
          onClick={onClose}
        >
          <Icon name="decline" />
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function downloadText(name, text) {
  const url = URL.createObjectURL(
    new Blob([text], { type: "text/plain;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
