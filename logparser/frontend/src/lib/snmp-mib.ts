import type { Attributes, Mapping } from "./types";

export interface MibObject {
  module: string;
  name: string;
  oid: string;
  syntax: string;
  access: string;
  description: string | null;
  units: string | null;
  scalar: boolean;
  table: string | null;
  indexes: string[];
}
export interface MibPreview {
  modules: string[];
  objects: MibObject[];
  warnings: string[];
}
export const mibKey = (object: MibObject) => `${object.module}::${object.name}`;
export const mibTableKey = (object: MibObject) =>
  `${object.module}::${object.table}`;
export function instanceOid(object: MibObject, suffix: string) {
  const instance = object.scalar ? "0" : suffix.trim();
  if (!/^\d+(?:\.\d+)*$/.test(instance)) return null;
  const parts = `${object.oid}.${instance}`.split(".").map(Number);
  if (
    parts.length > 128 ||
    parts.some(
      (part) => !Number.isInteger(part) || part < 0 || part > 4294967295,
    )
  )
    return null;
  return parts.join(".");
}

export function mergeMibOids(
  existing: (string | Attributes)[],
  selected: { object: MibObject; oid: string }[],
) {
  const rows = [...existing];
  const metrics: { name: string; oid: string }[] = [];
  const canonical = (oid: string) =>
    oid.trim().replace(/^\./, "").split(".").map(Number).join(".");
  const names = new Set(
    rows.map((row) =>
      typeof row === "string" ? row : String(row.name || row.oid),
    ),
  );
  for (const { object, oid } of selected) {
    const duplicate = rows.find(
      (row) =>
        canonical(String(typeof row === "string" ? row : (row.oid ?? ""))) ===
        oid,
    );
    if (duplicate) {
      metrics.push({
        name:
          typeof duplicate === "string"
            ? duplicate
            : String(duplicate.name || duplicate.oid),
        oid,
      });
      continue;
    }
    const suffix = object.scalar
      ? ""
      : `_${oid.slice(object.oid.length + 1).replaceAll(".", "_")}`;
    const base = `${object.name}${suffix}`;
    let name = base;
    for (let n = 2; names.has(name); n++) name = `${base}_${n}`;
    names.add(name);
    const entry = { name, oid };
    rows.push(entry);
    metrics.push(entry);
  }
  return {
    rows,
    metrics: metrics.filter(
      (metric, i) =>
        metrics.findIndex((entry) => entry.name === metric.name) === i,
    ),
  };
}

export function snmpMibMapping(
  messageType: string,
  metrics: { name: string }[],
): Mapping {
  const field = (
    sourceField: string,
    targetField = sourceField,
    defaultValue: string | null = null,
  ) => ({ sourceField, targetField, defaultValue });
  const pointer = (key: string) =>
    key.replaceAll("~", "~0").replaceAll("/", "~1");
  return {
    messageType,
    commonMappings: [
      field("observed_at", "event_time"),
      field("target_name", "src_host"),
      field("target_host", "src_ip"),
      field("protocol"),
      field("", "event_category", "snmp"),
      field("", "event_type", "snmp.poll"),
      field("poll_status", "event_result"),
    ],
    subTableRules: [
      {
        targetSubTable: "event_snmp",
        conditionExpression: "protocol == 'snmp' && operation == 'poll'",
        mappings: [
          ...[
            "protocol",
            "operation",
            "observed_at",
            "target_host",
            "target_name",
            "target_port",
            "version",
            "poll_status",
            "error_message",
            "metrics",
          ].map((key) => field(key)),
          ...metrics.map(({ name }) =>
            field(`/metrics/${pointer(name)}/value`, `metric_${name}`),
          ),
        ],
      },
    ],
  };
}
