import { describe, expect, it, vi } from "vitest";
import {
  cacheAfterSave,
  orderedSteps,
  signature,
  simulate,
  testSource,
  transformPreview,
  type TestNode,
} from "./pipeline-preview";
import type { Adapter } from "./types";
const adapter = (type: string, id: number, priority = 0): Adapter => ({
  id,
  type,
  priority,
  messagetype: "sample",
  enabled: true,
});
describe("pipeline preview continuity", () => {
  it("keeps a tested draft when saving assigns an id and server timestamps", () => {
    const config = {
      ...adapter("Filter", 0),
      filterDrop: '{ "level": "DEBUG" }',
      configParams: {},
    };
    delete config.id;
    const nodes: TestNode[] = [
      { key: "transform:new", stage: "transform", label: "Filter", config },
    ];
    const cache = {
      "transform:new": {
        payload: { level: "INFO" },
        note: "Passed",
        signature: signature(nodes, 0, "{}"),
      },
    };
    const saved: TestNode = {
      ...nodes[0],
      key: "transform:42",
      config: {
        ...config,
        id: 42,
        version: 0,
        createdAt: "today",
        filterDrop: '{"level":"DEBUG"}',
      },
    };
    const updated = cacheAfterSave(cache, nodes, nodes[0].key, saved, "{}");
    expect(updated["transform:new"]).toBeUndefined();
    expect(updated["transform:42"].signature).toBe(signature([saved], 0, "{}"));
    expect(updated["transform:42"].payload).toEqual({ level: "INFO" });
  });
  it("interleaves parsers and transforms by priority with deterministic ties", () => {
    const result = orderedSteps(
      [adapter("JsonParser", 2, 30), adapter("RegexParser", 1, 10)],
      [adapter("Filter", 1, 20), adapter("RemoveProperty", 2, 30)],
    );
    expect(result.map((s) => `${s.stage}:${s.config.id}`)).toEqual([
      "parser:1",
      "transform:1",
      "parser:2",
      "transform:2",
    ]);
  });
  it("requires current predecessor results and invalidates only downstream signatures", () => {
    const nodes: TestNode[] = [
      {
        key: "input:1",
        stage: "input",
        config: adapter("TcpInputAdapter", 1),
        label: "TCP",
      },
      {
        key: "parser:1",
        stage: "parser",
        config: adapter("JsonParser", 1),
        label: "JSON",
      },
    ];
    const sample = '{"message":"hello"}',
      inputSignature = signature(nodes, 0, sample);
    expect(testSource(nodes, 1, sample, {}).error).toContain("Test TCP first");
    const cache = {
      "input:1": {
        payload: { message: "hello" },
        note: "",
        signature: inputSignature,
      },
    };
    expect(testSource(nodes, 1, sample, cache).error).toBe("");
    (nodes[1].config as Adapter).sourceField = "body";
    expect(signature(nodes, 0, sample)).toBe(inputSignature);
    (nodes[0].config as Adapter).port = 8514;
    expect(testSource(nodes, 1, sample, cache).error).toContain(
      "Test TCP first",
    );
    expect(testSource(nodes, 1, "changed", cache).error).toContain(
      "Test TCP first",
    );
  });
  it("blocks downstream execution after failure or filter drop", () => {
    const nodes: TestNode[] = [
      {
        key: "a",
        stage: "transform",
        config: adapter("Filter", 1),
        label: "Filter",
      },
      {
        key: "b",
        stage: "output",
        config: adapter("ConsoleOutputAdapter", 1),
        label: "Console",
      },
    ];
    const first = {
      payload: {},
      note: "",
      signature: signature(nodes, 0, "{}"),
    };
    expect(
      testSource(nodes, 1, "{}", { a: { ...first, dropped: true } }).error,
    ).toContain("dropped");
    expect(
      testSource(nodes, 1, "{}", { a: { ...first, error: true } }).error,
    ).toContain("failed");
  });
  it("parses source-field arrays on the server and replaces only that source field", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ key: "value" }), { status: 200 }),
      );
    vi.stubGlobal("fetch", fetchMock);
    const result = await simulate(
      {
        key: "p",
        stage: "parser",
        label: "Regex",
        config: {
          ...adapter("RegexParser", 1),
          sourceField: "lines",
          param: "(a)=(b)",
        },
      },
      { lines: ["a=b"], retained: 42 },
      "original",
      "sample",
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({
      sampleData: ["a=b"],
    });
    expect(result.payload).toEqual({ lines: { key: "value" }, retained: 42 });
  });
  it("uses original text for parsers without sourceField and respects continueOnFailure", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "parse failed" }), {
        status: 400,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const config = { ...adapter("JsonParser", 1), continueOnFailure: true };
    const result = await simulate(
      { key: "p", stage: "parser", label: "JSON", config },
      { preserved: true },
      "original text",
      "sample",
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).sampleData).toBe(
      "original text",
    );
    expect(result.payload).toEqual({ preserved: true });
    expect(result.note).toContain("continue on failure");
  });
  it("groups and removes fields without mutating the preceding result", () => {
    const input = { host: "web", user: "alice", level: "INFO" };
    const grouped = transformPreview(
      {
        ...adapter("AddProperty", 1),
        addProperties: '{"context":["host","user"]}',
      },
      input,
    );
    expect(grouped.payload).toEqual({
      context: { host: "web", user: "alice" },
      level: "INFO",
    });
    expect(input.host).toBe("web");
    expect(
      transformPreview(
        { ...adapter("RemoveProperty", 2), removeProperties: '["level"]' },
        grouped.payload,
      ).payload,
    ).toEqual({ context: { host: "web", user: "alice" } });
    expect(
      transformPreview(
        { ...adapter("Filter", 3), filterDrop: '{"level":"DEBUG,INFO"}' },
        input,
      ).dropped,
    ).toBe(true);
  });
  it("sends mapping drafts with sampleData and reports server simulation failures", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ success: false, errors: ["Bad condition"] }),
          { status: 200 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    const config = {
      messageType: "sample",
      commonMappings: [],
      subTableRules: [],
    };
    await expect(
      simulate(
        { key: "s", stage: "structured", label: "Mapping", config },
        { value: 1 },
        "{}",
        "sample",
      ),
    ).rejects.toThrow("Bad condition");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      messageType: "sample",
      sampleData: { value: 1 },
      temporaryConfig: config,
    });
  });
  it("does not open external connections for input and output previews", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const input = await simulate(
      {
        key: "i",
        stage: "input",
        label: "TCP",
        config: adapter("TcpInputAdapter", 1),
      },
      { message: "hello" },
      "hello",
      "sample",
    );
    const output = await simulate(
      {
        key: "o",
        stage: "output",
        label: "Console",
        config: adapter("ConsoleOutputAdapter", 1),
      },
      input.payload,
      "hello",
      "sample",
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(output.note).toContain("No external delivery");
  });
});
