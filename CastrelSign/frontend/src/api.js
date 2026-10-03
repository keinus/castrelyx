let csrf = "";
export function setSession(value) {
  csrf = value?.csrf || "";
}
const messages = {
  400: "입력 내용을 확인하세요.",
  401: "인증이 만료되었거나 MFA 코드가 올바르지 않습니다.",
  403: "권한 또는 MFA를 확인하세요. 사용한 MFA 코드는 다시 사용할 수 없습니다.",
  404: "대상을 찾을 수 없습니다.",
  409: "대상 상태가 변경되었거나 작업 조건을 충족하지 않습니다.",
  413: "파일이 허용 크기를 초과합니다.",
  429: "요청 한도를 초과했습니다. 잠시 후 다시 시도하세요.",
  503: "KMS 또는 인증 정책을 사용할 수 없습니다. 운영 상태를 확인하세요.",
};
export async function api(
  path,
  { method = "GET", body, totp, signal, text = false } = {},
) {
  const headers = {
    Accept: text ? "application/x-pem-file" : "application/json",
  };
  if (method !== "GET") {
    headers["X-CSRF-Token"] = csrf;
    if (totp) headers["X-KMS-TOTP"] = totp;
  }
  if (body && !(body instanceof FormData))
    headers["Content-Type"] = "application/json";
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers,
    body:
      body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal,
    cache: "no-store",
  });
  if (!response.ok) {
    const error = new Error(
      messages[response.status] || "요청을 처리하지 못했습니다.",
    );
    error.status = response.status;
    error.requestId = response.headers.get("X-Request-ID");
    if (response.status === 401 && method === "GET")
      window.dispatchEvent(new Event("session-expired"));
    throw error;
  }
  if (response.status === 204) return null;
  return text ? response.text() : response.json();
}
export const segment = (value) => encodeURIComponent(String(value));
export const fmt = (value) =>
  value ? new Date(value).toLocaleString("ko-KR", { hour12: false }) : "—";
export const keyPath = (key) =>
  `/api/admin/kms/keys/${segment(key.keyId)}/versions/${key.version}`;
export function csvValue(value) {
  return (
    '"' +
    String(value ?? "")
      .replace(/^[=+@\-\t\r]/, "'$&")
      .replaceAll('"', '""') +
    '"'
  );
}
