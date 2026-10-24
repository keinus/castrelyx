import { afterEach, describe, expect, it, vi } from "vitest";
import { huntApi } from "./huntApi";
afterEach(() => vi.unstubAllGlobals());
const ready = {
  url: "/api/security/hunt/v2/exports/11111111-1111-4111-8111-111111111111",
  sha256: "a".repeat(64),
  length: 17,
  caseVersion: 1,
};
describe("native investigation download preparation", () => {
  it("prepares the exact version with session-bound CSRF and returns a same-origin native URL", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response('{"csrfToken":"session-token"}'))
      .mockResolvedValueOnce(
        new Response(JSON.stringify(ready), { status: 201 }),
      );
    vi.stubGlobal("fetch", fetcher);
    const value = await huntApi.downloadExport("case/with space", 1);
    expect(fetcher).toHaveBeenLastCalledWith(
      "/api/security/hunt/v2/investigations/case%2Fwith%20space/exports",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-Hunt-CSRF": "session-token",
        },
        body: '{"version":1}',
      }),
    );
    expect(value.url).toBe(ready.url);
    expect(value.sha256).toBe(ready.sha256);
    expect(value.length).toBe(17);
  });
  it.each([
    "missing_hash",
    "foreign_url",
    "wrong_version",
    "wrong_length",
    "server_error",
  ])("rejects %s before starting a native download", async (kind) => {
    const body = {
      ...ready,
      ...(kind === "missing_hash"
        ? { sha256: "" }
        : kind === "foreign_url"
          ? { url: "https://untrusted.invalid/file" }
          : kind === "wrong_version"
            ? { caseVersion: 2 }
            : kind === "wrong_length"
              ? { length: 0 }
              : {}),
    };
    const response =
      kind === "server_error"
        ? new Response('{"error":"파일 한도 초과"}', { status: 409 })
        : new Response(JSON.stringify(body), { status: 201 });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(new Response('{"csrfToken":"session-token"}'))
        .mockResolvedValueOnce(response),
    );
    await expect(huntApi.downloadExport("case", 1)).rejects.toThrow(
      kind === "server_error" ? "파일 한도 초과" : "검증 정보",
    );
  });
});
