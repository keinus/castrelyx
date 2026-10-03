import { useEffect, useState } from "react";
import { api, setSession } from "./api";
import { adminNav, userNav } from "./data";
import { Runtime, ActionDialog } from "./runtime";
import { Icon, Button, Field, Notice, Empty, Modal } from "./ui";
import { Admin } from "./Admin";
import { Portal } from "./Portal";
const route = () => {
  const [page, query = ""] = location.hash.slice(1).split("?");
  return { page, tab: new URLSearchParams(query).get("tab") || "" };
};
const headings = {
  keys: ["키 관리", "현재 버전과 다음 교체를 한곳에서 확인하세요."],
  rotation: [
    "키 교체 준비",
    "새 버전을 준비하고 신뢰 자료 배포 후 활성화를 요청합니다.",
  ],
  approvals: [
    "승인 요청",
    "변경 내용과 영향을 확인하고 다른 담당자가 승인합니다.",
  ],
  certificates: ["인증서", "발급 대상과 인증서 상태를 확인하세요."],
  principals: ["서비스·에이전트", "등록 대상과 접근 상태를 관리하세요."],
  tls: ["서버 TLS", "현재 인증서와 발급 이력을 확인하세요."],
  releases: ["업데이트 서명", "릴리스의 서명과 배포 상태를 관리하세요."],
  trust: ["신뢰 자료", "검증에 사용하는 공개 자료를 확인하세요."],
  audit: ["감사 기록", "관리 작업과 서명 이력을 요청 단위로 확인하세요."],
  health: ["운영 상태", "키 사용 가능 여부와 저장소 무결성을 확인하세요."],
  settings: ["설정·구성", "현재 적용된 정책과 운영 절차를 확인하세요."],
  reviews: [
    "인증서 요청 검토",
    "서비스 담당자가 제출한 CSR 발급 요청을 검토하세요.",
  ],
  services: ["내 서비스", "서비스 연결과 인증서 상태를 확인하세요."],
  "my-certificates": [
    "내 인증서",
    "내 서비스에 발급된 공개 인증서를 확인하세요.",
  ],
  enroll: ["인증서 요청", "서비스 환경에서 만든 CSR을 제출하세요."],
  "my-requests": ["내 요청", "인증서 요청의 진행 상태를 확인하세요."],
  guide: ["연결 가이드", "CSR부터 인증서와 신뢰 자료 적용까지 안내합니다."],
  "my-activity": ["사용 이력", "내 서비스의 요청과 인증서 수령 이력입니다."],
  account: ["내 계정", "내 역할과 접근 범위를 확인하세요."],
};
export function App() {
  const [session, saveSession] = useState(undefined),
    [current, setCurrent] = useState(route),
    [revision, setRevision] = useState(0),
    [action, setAction] = useState(null),
    [mobile, setMobile] = useState(false),
    [notice, setNotice] = useState("");
  const accept = (value) => {
    setSession(value);
    saveSession(value);
  };
  useEffect(() => {
    let cancelled = false;
    api("/api/console/session")
      .then((s) => {
        if (!cancelled) accept(s);
      })
      .catch(() => {
        if (!cancelled) accept(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  useEffect(() => {
    const change = () => {
      setCurrent(route());
      setMobile(false);
    };
    const expired = () => {
      accept(null);
      setAction(null);
    };
    window.addEventListener("hashchange", change);
    window.addEventListener("session-expired", expired);
    return () => {
      window.removeEventListener("hashchange", change);
      window.removeEventListener("session-expired", expired);
    };
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 6000);
    return () => clearTimeout(timer);
  }, [notice]);
  const navigate = (destination) => {
    location.hash = destination;
    setMobile(false);
  };
  const refresh = () => setRevision((r) => r + 1);
  const logout = async () => {
    try {
      await api("/api/console/logout", { method: "POST" });
      accept(null);
      setAction(null);
    } catch (e) {
      setNotice(e.message);
    }
  };
  if (session === undefined)
    return (
      <main className="login">
        <h1>CastrelSign KMS</h1>
        <p role="status">세션을 확인하고 있습니다.</p>
      </main>
    );
  if (!session)
    return (
      <Login
        onLogin={(s) => {
          accept(s);
          navigate(
            s.realm === "user"
              ? "services"
              : s.roles.includes("AUDITOR")
                ? "keys"
                : "principals",
          );
        }}
      />
    );
  const user = session.realm === "user",
    roles = session.roles,
    readKms = roles.includes("AUDITOR");
  const page =
      current.page || (user ? "services" : readKms ? "keys" : "principals"),
    tab = current.tab;
  const nav = user
    ? userNav
    : adminNav
        .filter(([id]) =>
          [
            "keys",
            "approvals",
            "tls",
            "trust",
            "audit",
            "health",
            "settings",
          ].includes(id)
            ? readKms
            : roles.includes("AUDITOR") || roles.includes("OPERATOR"),
        )
        .concat(
          roles.includes("OPERATOR") || readKms
            ? [["reviews", "인증서 요청 검토", "approvals"]]
            : [],
        );
  const title = headings[page] || ["화면을 찾을 수 없습니다", ""];
  const runtime = {
    session,
    revision,
    refresh,
    navigate,
    open: setAction,
    notify: setNotice,
  };
  return (
    <Runtime.Provider value={runtime}>
      <div className={`app-shell ${user ? "user-shell" : ""}`}>
        <header className="shellbar">
          <button
            className="mobile-toggle icon-button"
            aria-label="메뉴 열기"
            onClick={() => setMobile(!mobile)}
          >
            <Icon name="menu2" />
          </button>
          <button
            className="wordmark"
            onClick={() =>
              navigate(user ? "services" : readKms ? "keys" : "principals")
            }
          >
            CastrelSign <span>KMS</span>
          </button>
          <span className="environment">
            {session.environment} · {session.tenant}
          </span>
          {user && (
            <nav className="topnav" aria-label="사용자 메뉴">
              {nav.slice(0, 4).map(([id, label, icon]) => (
                <button
                  key={id}
                  className={page === id ? "active" : ""}
                  onClick={() => navigate(id)}
                >
                  <Icon name={icon} />
                  {label}
                </button>
              ))}
            </nav>
          )}
          <button className="identity" onClick={() => navigate("account")}>
            <Icon name="person-placeholder" />
            <span>{session.id}</span>
            <small>{user ? "서비스 담당자" : roles.join(" · ")}</small>
          </button>
        </header>
        {(!user || mobile) && (
          <nav
            className={`sidebar ${mobile ? "open" : ""}`}
            aria-label="주 메뉴"
          >
            {nav.map(([id, label, icon]) => (
              <button
                key={id}
                className={page === id ? "active" : ""}
                aria-current={page === id ? "page" : undefined}
                onClick={() => navigate(id)}
              >
                <Icon name={icon} />
                <span>{label}</span>
              </button>
            ))}
            <div className="nav-foot">
              <small>CastrelSign KMS</small>
              <button onClick={logout}>
                <Icon name="log" />
                로그아웃
              </button>
            </div>
          </nav>
        )}
        <main id="main" className="content">
          <div className="page-heading">
            <div>
              <div className="breadcrumb">
                {user ? "서비스 워크스페이스" : "관리 콘솔"}{" "}
                <Icon name="slim-arrow-right" />
                {title[0]}
              </div>
              <h1>{title[0]}</h1>
              <p>{title[1]}</p>
            </div>
            <div className="actions">
              <Button icon="refresh" onClick={refresh}>
                새로고침
              </Button>
              {user && (
                <button
                  className="text-link"
                  onClick={() => navigate("my-activity")}
                >
                  사용 이력
                </button>
              )}
            </div>
          </div>
          {page === "account" ? (
            <section className="panel inspector narrow">
              <h2>{session.id}</h2>
              <p>역할: {roles.join(", ")}</p>
              <p>
                접근 범위:{" "}
                {(session.services || session.keys || []).join(", ") ||
                  "할당 없음"}
              </p>
              <Notice>
                계정과 접근 범위는 운영자가 관리합니다. 권한 변경은 다음
                요청부터 적용됩니다.
              </Notice>
              <Button onClick={logout}>로그아웃</Button>
            </section>
          ) : user ? (
            <Portal page={page} />
          ) : (
            <Admin page={page} tab={tab} />
          )}
          <footer className="page-footer">
            <span>
              {session.environment} · {session.tenant}
            </span>
            <span>개인키와 인증 비밀은 화면에 표시하지 않습니다.</span>
          </footer>
        </main>
      </div>
      {action && (
        <ActionDialog
          key={action.title}
          action={action}
          session={session}
          onClose={() => setAction(null)}
          onDone={() => {
            refresh();
            setNotice("요청을 처리했습니다.");
          }}
        />
      )}
      {notice && (
        <div role="status" className="toast">
          {notice}
          <button aria-label="알림 닫기" onClick={() => setNotice("")}>
            <Icon name="decline" />
          </button>
        </div>
      )}
    </Runtime.Provider>
  );
}
function Login({ onLogin }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <main className="login console-login">
      <div className="wordmark">
        CastrelSign <span>KMS</span>
      </div>
      <h1>키와 신뢰를 관리하는 작업 공간</h1>
      <p>운영자가 발급한 개인별 인증 토큰으로 로그인하세요.</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          const form = e.currentTarget,
            values = Object.fromEntries(new FormData(form));
          try {
            const session = await api("/api/console/login", {
              method: "POST",
              body: values,
            });
            form.reset();
            onLogin(session);
          } catch (e) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="접속 구분">
          <select name="realm">
            <option value="admin">관리자</option>
            <option value="user">일반 사용자</option>
          </select>
        </Field>
        <Field
          label="인증 토큰"
          hint="토큰은 브라우저 저장소에 보관하지 않습니다."
        >
          <input
            type="password"
            name="token"
            autoComplete="off"
            required
            maxLength={4096}
          />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <div className="dialog-actions">
          <Button primary type="submit" disabled={busy}>
            {busy ? "로그인 중…" : "로그인"}
          </Button>
        </div>
      </form>
      <Notice>관리 작업은 계정에 지정된 권한과 MFA 정책을 따릅니다.</Notice>
    </main>
  );
}
