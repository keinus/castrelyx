import { describe, expect, it } from "vitest";
import {
  decodeConfig,
  fieldTab,
  hydrate,
  newAdapter,
  serialize,
  setPath,
  validate,
} from "./adapters";
import { TYPE_DEFS } from "./adapter-definitions";
import type { Adapter } from "./types";

describe("shared adapter form model", () => {
  it("round-trips nested attributes, false, zero, secrets and unknown options without changing the API contract", () => {
    const saved: Adapter = {
      id: 2,
      version: 4,
      createdAt: "yesterday",
      type: "ClickHouseOutputAdapter",
      messagetype: "agent",
      enabled: false,
      configParams: JSON.stringify({
        endpointUrl: "http://localhost:8123",
        autoCreateSchema: false,
        custom: { retries: 0, enabled: false },
        passwordEnv: "QA_PASSWORD",
      }),
    };
    const draft = hydrate(saved, "output");
    const changed = setPath(draft, "configParams.batchSize", 250) as Adapter;
    const payload = serialize(changed);
    expect(payload.id).toBeUndefined();
    expect(payload.createdAt).toBeUndefined();
    expect(payload.version).toBe(4);
    expect(typeof payload.configParams).toBe("string");
    expect(JSON.parse(payload.configParams)).toMatchObject({
      batchSize: 250,
      autoCreateSchema: false,
      custom: { retries: 0, enabled: false },
      passwordEnv: "QA_PASSWORD",
    });
    expect(JSON.parse(saved.configParams).batchSize).toBeUndefined();
  });
  it("does not silently replace malformed or non-object saved configuration", () => {
    for (const value of ["{broken", "[1,2]", "null", "12"])
      expect(() => decodeConfig(value)).toThrow();
    expect(decodeConfig(null)).toEqual({});
  });
  it("rejects prototype paths", () => {
    expect(() => setPath({}, "__proto__.polluted", true)).toThrow();
    expect(({} as any).polluted).toBeUndefined();
  });
  it("validates required fields and integer ranges in every canonical adapter form", () => {
    for (const stage of ["input", "parser", "transform", "output"] as const)
      for (const definition of TYPE_DEFS[stage]) {
        const draft = newAdapter(stage, definition.type, "sample");
        for (const field of definition.fields.filter(
          (field) => field.required && field.default === undefined,
        ))
          expect(
            validate(draft, stage)[field.path],
            `${definition.type}.${field.path}`,
          ).toBeTruthy();
      }
    const tcp = newAdapter("input", "TcpInputAdapter", "sample");
    for (const port of [0, -1, 65536, 12.5])
      expect(validate({ ...tcp, port }, "input").port).toBeTruthy();
    expect(validate({ ...tcp, port: 6514 }, "input")).toEqual({});
  });
  it("accepts TLS password or env and requires a trust store for client authentication", () => {
    let tcp = newAdapter("input", "TlsTcpInputAdapter", "sample");
    tcp.port = 6514;
    tcp.configParams.keyStorePath = "server.p12";
    expect(
      validate(tcp, "input")["configParams.keyStorePasswordEnv"],
    ).toBeTruthy();
    tcp.configParams.keyStorePasswordEnv = "KEY_PASSWORD";
    expect(validate(tcp, "input")).toEqual({});
    tcp.configParams.clientAuth = "need";
    expect(validate(tcp, "input")["configParams.trustStorePath"]).toBeTruthy();
  });
  it("validates SNMP repeatable target and OID entries", () => {
    const snmp = newAdapter("input", "SnmpInputAdapter", "sample");
    snmp.configParams.targets = [{ host: "", port: 70000 }];
    snmp.configParams.oids = [{ name: "uptime", oid: "" }];
    expect(validate(snmp, "input")).toMatchObject({
      "configParams.targets": expect.any(String),
      "configParams.oids": expect.any(String),
    });
  });
  it("places mTLS credentials and capacity fields on dedicated tabs", () => {
    const mtls = TYPE_DEFS.input.find(
      (d) => d.type === "TcpMtlsGzipInputAdapter",
    )!;
    expect(
      fieldTab(
        mtls.fields.find((f) => f.path === "configParams.keyStorePath")!,
      ),
    ).toBe("mtls");
    expect(fieldTab(mtls.fields.find((f) => f.path === "workerThreads")!)).toBe(
      "capacity",
    );
    expect(fieldTab(mtls.fields.find((f) => f.path === "port")!)).toBe(
      "connection",
    );
  });
});
