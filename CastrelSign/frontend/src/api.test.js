import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { api, setSession, csvValue } from "./api.js";
const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
  delete globalThis.window;
  setSession(null);
});
test("JSON mutations attach CSRF and MFA without retaining bearer credentials", async () => {
  setSession({ csrf: "csrf-test" });
  globalThis.fetch = async (path, options) => {
    assert.equal(path, "/api/admin/example");
    assert.equal(options.headers["X-CSRF-Token"], "csrf-test");
    assert.equal(options.headers["X-KMS-TOTP"], "123456");
    assert.equal(options.headers.Authorization, undefined);
    assert.equal(options.credentials, "same-origin");
    assert.deepEqual(JSON.parse(options.body), { reason: "test" });
    return new Response(null, { status: 204 });
  };
  assert.equal(
    await api("/api/admin/example", {
      method: "POST",
      body: { reason: "test" },
      totp: "123456",
    }),
    null,
  );
});
test("multipart uploads retain browser-generated boundary", async () => {
  const body = new FormData();
  body.set("artifact", new Blob(["test"]), "artifact.bin");
  globalThis.fetch = async (_, options) => {
    assert.equal(options.headers["Content-Type"], undefined);
    assert.equal(options.body, body);
    return Response.json({ id: "release" });
  };
  assert.deepEqual(await api("/upload", { method: "POST", body }), {
    id: "release",
  });
});
test("expired read session signs out, invalid mutation MFA keeps retry possible", async () => {
  let expired = 0;
  globalThis.window = { dispatchEvent: () => expired++ };
  globalThis.fetch = async () => new Response("", { status: 401 });
  await assert.rejects(api("/session"), { status: 401 });
  assert.equal(expired, 1);
  await assert.rejects(api("/mutation", { method: "POST" }), { status: 401 });
  assert.equal(expired, 1);
});
test("CSV export quotes embedded text and neutralizes formula prefixes", () => {
  assert.equal(csvValue('=HYPERLINK("x")'), '"\'=HYPERLINK(""x"")"');
  assert.equal(csvValue("line\nbreak"), '"line\nbreak"');
  assert.equal(csvValue(null), '""');
});
