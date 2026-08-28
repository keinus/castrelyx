import { api } from "./api";
import { summary } from "./adapters";
import type { Adapter, Attributes, Mapping, Stage } from "./types";
export type TestNode = {
  key: string;
  stage: Stage | "structured";
  config: Adapter | Mapping;
  label: string;
};
export type Preview = {
  payload: Attributes;
  note: string;
  dropped?: boolean;
  error?: boolean;
};
export type CachedPreview = Preview & { signature: string };
export function orderedSteps(parsers: Adapter[], transforms: Adapter[]) {
  return [
    ...parsers.map((config) => ({ stage: "parser" as const, config })),
    ...transforms.map((config) => ({ stage: "transform" as const, config })),
  ].sort(
    (a, b) =>
      (a.config.priority || 0) - (b.config.priority || 0) ||
      (a.stage === b.stage
        ? (a.config.id || 0) - (b.config.id || 0)
        : a.stage === "parser"
          ? -1
          : 1),
  );
}
export function signature(
  nodes: TestNode[],
  index: number,
  sample: string,
): string {
  return JSON.stringify([
    sample,
    nodes.slice(0, index + 1).map((n) => [n.key, semanticConfig(n.config)]),
  ]);
}

function canonical(value: any): any {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonical(value[key])]),
    );
  return value;
}
export function semanticConfig(config: Attributes): Attributes {
  const result = { ...config };
  for (const key of [
    "id",
    "version",
    "createdAt",
    "updatedAt",
    "deliveryMetrics",
  ])
    delete result[key];
  for (const [key, fallback] of [
    ["configParams", {}],
    ["filterDrop", {}],
    ["filterPass", {}],
    ["headers", {}],
    ["addProperties", {}],
    ["removeProperties", []],
  ] as const) {
    if (key !== "configParams" && !(key in result)) continue;
    try {
      result[key] = result[key]
        ? typeof result[key] === "string"
          ? JSON.parse(result[key])
          : result[key]
        : fallback;
    } catch {
      /* Invalid drafts retain their exact value and fail validation. */
    }
  }
  for (const key of Object.keys(result))
    if (result[key] == null || result[key] === "") delete result[key];
  for (const key of [
    "filterDrop",
    "filterPass",
    "headers",
    "addProperties",
    "removeProperties",
  ])
    if (
      result[key] &&
      typeof result[key] === "object" &&
      !Object.keys(result[key]).length
    )
      delete result[key];
  return canonical(result);
}
export function cacheAfterSave(
  cache: Record<string, CachedPreview>,
  nodes: TestNode[],
  oldKey: string,
  saved: TestNode,
  sample: string,
) {
  const index = nodes.findIndex((node) => node.key === oldKey);
  if (index < 0) return cache;
  const changed =
    JSON.stringify(semanticConfig(nodes[index].config)) !==
    JSON.stringify(semanticConfig(saved.config));
  const nextNodes = nodes.map((node) => (node.key === oldKey ? saved : node)),
    next = { ...cache };
  delete next[oldKey];
  nodes.forEach((node, i) => {
    const result = cache[node.key];
    if (changed && i >= index) {
      delete next[node.key];
      return;
    }
    if (result?.signature === signature(nodes, i, sample))
      next[nextNodes[i].key] = {
        ...result,
        signature: signature(nextNodes, i, sample),
      };
  });
  return next;
}
export function testSource(
  nodes: TestNode[],
  index: number,
  sample: string,
  cache: Record<string, CachedPreview>,
) {
  if (index <= 0) {
    let parsed: any;
    try {
      parsed = JSON.parse(sample);
    } catch {
      parsed = { raw: sample };
    }
    return {
      payload:
        parsed && typeof parsed === "object" && !Array.isArray(parsed)
          ? parsed
          : { raw: sample },
      error: "",
    };
  }
  const previous = nodes[index - 1],
    result = cache[previous.key];
  if (!result || result.signature !== signature(nodes, index - 1, sample))
    return {
      payload: {},
      error: `Test ${previous.label} first. Its current result is needed by this step.`,
    };
  if (result.error)
    return {
      payload: {},
      error: "The previous test failed. Correct it before testing this step.",
    };
  if (result.dropped)
    return {
      payload: {},
      error:
        "The previous filter dropped the event. No event reaches this step.",
    };
  return { payload: result.payload, error: "" };
}
const parsed = (value: any, fallback: any) =>
  value == null || value === ""
    ? fallback
    : typeof value === "string"
      ? JSON.parse(value)
      : value;
export function transformPreview(config: Adapter, input: Attributes): Preview {
  const payload = structuredClone(input);
  if (config.type === "Filter") {
    const drop = parsed(config.filterDrop, {}),
      pass = parsed(config.filterPass, {});
    const matches = ([key, values]: [string, any]) =>
      Object.hasOwn(payload, key) &&
      String(values)
        .split(",")
        .map((s) => s.trim())
        .includes(String(payload[key]));
    if (
      Object.entries(drop).some(matches) ||
      !Object.entries(pass).every(matches)
    )
      return {
        payload,
        dropped: true,
        note: "Event dropped by filter rules. No downstream event.",
      };
    return { payload, note: "Event passed all filter rules." };
  }
  if (config.type === "AddProperty") {
    for (const [target, sources] of Object.entries(
      parsed(config.addProperties, {}),
    )) {
      const grouped: Attributes = {};
      for (const source of Array.isArray(sources)
        ? sources
        : String(sources)
            .split(",")
            .map((s) => s.trim())) {
        grouped[source] = payload[source] ?? null;
        delete payload[source];
      }
      payload[target] = grouped;
    }
  }
  if (config.type === "RemoveProperty")
    for (const key of parsed(config.removeProperties, [])) delete payload[key];
  return { payload, note: "Transform preview completed locally." };
}
export async function simulate(
  node: TestNode,
  source: Attributes,
  sample: string,
  messageType: string,
): Promise<Preview> {
  if (!sample.trim()) throw new Error("Enter sample data before testing.");
  const config = node.config as Adapter;
  if (node.stage === "input")
    return {
      payload: structuredClone(source),
      note: "Input payload preview only. No listener, TLS handshake, decompression, or external connection was tested.",
    };
  if (node.stage === "parser") {
    const sourceField = String(config.sourceField || "").trim(),
      input = sourceField ? source[sourceField] : sample;
    if (input == null)
      throw new Error(`Source field "${sourceField}" is missing.`);
    try {
      const sampleData =
        typeof input === "string" ||
        (config.type === "RegexParser" && Array.isArray(input))
          ? input
          : JSON.stringify(input);
      const result = await api<Attributes>("/parsers/test", "POST", {
        type: config.type,
        param: config.param || null,
        sampleData,
      });
      return {
        payload: sourceField
          ? { ...source, [sourceField]: result }
          : { ...source, ...result },
        note: "Parsed by the server using the current draft.",
      };
    } catch (e) {
      if (!config.continueOnFailure) throw e;
      return {
        payload: structuredClone(source),
        note: `Parser failed; continue on failure kept the input: ${e instanceof Error ? e.message : String(e)}`,
      };
    }
  }
  if (node.stage === "transform") return transformPreview(config, source);
  if (node.stage === "structured") {
    const result = await api("/structure/simulate", "POST", {
      messageType,
      sampleData: source,
      temporaryConfig: node.config,
    });
    if (!result.success)
      throw new Error(result.errors?.join(", ") || "Mapping simulation failed");
    return {
      payload: result.result,
      note: "Mapped by the server using the current draft.",
    };
  }
  return {
    payload: {
      destination: summary(config),
      event: source,
      ...(config.addOriginText ? { originalText: sample } : {}),
    },
    note: "Destination and event preview only. No external delivery or acknowledgement was attempted.",
  };
}
