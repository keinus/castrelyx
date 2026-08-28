import { TYPE_DEFS } from "./adapter-definitions";
import type { Adapter, Attributes, FieldDefinition, Stage } from "./types";

export function getPath(object: Attributes, path: string): any {
  return path.split(".").reduce((value, key) => value?.[key], object);
}
export function setPath(
  object: Attributes,
  path: string,
  value: any,
): Attributes {
  const next = structuredClone(object);
  const keys = path.split(".");
  if (
    keys.some((key) => ["__proto__", "constructor", "prototype"].includes(key))
  )
    throw new Error("Invalid attribute name");
  let cursor = next;
  for (const key of keys.slice(0, -1)) cursor = cursor[key] ??= {};
  if (value === undefined) delete cursor[keys.at(-1)!];
  else cursor[keys.at(-1)!] = value;
  return next;
}
export const definition = (stage: Stage, type: string) =>
  TYPE_DEFS[stage].find((item) => item.type === type);
export function decodeConfig(value: unknown): Attributes {
  if (value == null || value === "") return {};
  const parsed =
    typeof value === "string" ? JSON.parse(value) : structuredClone(value);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object")
    throw new Error(
      "Stored configuration must be an object. Repair the saved configuration through the API before editing.",
    );
  return parsed;
}
export function hydrate(adapter: Adapter, stage: Stage): Adapter {
  let value: Adapter = {
    ...structuredClone(adapter),
    configParams: decodeConfig(adapter.configParams),
  };
  if (stage === "output" && !value.messagetype) value.messagetype = "all";
  for (const field of definition(stage, adapter.type)?.fields || []) {
    if (getPath(value, field.path) == null && field.default !== undefined)
      value = setPath(
        value,
        field.path,
        structuredClone(field.default),
      ) as Adapter;
  }
  return value;
}
export function serialize(adapter: Adapter): Adapter {
  const {
    id: _id,
    createdAt: _created,
    updatedAt: _updated,
    deliveryMetrics: _metrics,
    ...rest
  } = structuredClone(adapter);
  return {
    ...rest,
    configParams: JSON.stringify(decodeConfig(adapter.configParams)),
  } as Adapter;
}
export function newAdapter(
  stage: Stage,
  type: string,
  messagetype: string,
): Adapter {
  return hydrate({ type, messagetype, enabled: false }, stage);
}
export function duplicateAdapter(adapter: Adapter): Adapter {
  const {
    id: _id,
    version: _version,
    createdAt: _created,
    updatedAt: _updated,
    deliveryMetrics: _metrics,
    ...attributes
  } = structuredClone(adapter);
  return { ...attributes, enabled: false } as Adapter;
}
export function fieldTab(field: FieldDefinition) {
  if (!Array.isArray(field.tab)) return field.tab || "general";
  if (
    field.path === "port" ||
    field.path === "timeoutMs" ||
    field.path === "queueSize"
  )
    return "connection";
  return field.tab.includes("mtls") ? "mtls" : field.tab.at(-1)!;
}
export function validate(
  adapter: Adapter,
  stage: Stage,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!adapter.messagetype.trim())
    errors.messagetype = "Message type is required.";
  for (const field of definition(stage, adapter.type)?.fields || []) {
    const value = getPath(adapter, field.path);
    let parsed = value;
    if (
      ["keyValue", "mapList", "jsonList"].includes(field.type) &&
      typeof value === "string" &&
      value
    ) {
      try {
        parsed = JSON.parse(value);
      } catch {
        errors[field.path] =
          "The stored value is invalid. Correct its entries before saving.";
        continue;
      }
    }
    if (
      field.required &&
      (value == null ||
        value === "" ||
        (typeof parsed === "object" && Object.keys(parsed).length === 0))
    )
      errors[field.path] = `${field.label} is required.`;
    if (value !== "" && value != null) {
      if (
        ["number", "bytes"].includes(field.type) &&
        (!Number.isInteger(Number(value)) ||
          (field.min !== undefined && value < field.min) ||
          (field.max !== undefined && value > field.max))
      )
        errors[field.path] =
          `Enter a whole number${field.min !== undefined ? ` ≥ ${field.min}` : ""}${field.max !== undefined ? ` and ≤ ${field.max}` : ""}.`;
      if (field.pattern && !new RegExp(field.pattern).test(value))
        errors[field.path] = "Use letters, numbers, or underscores only.";
      if (field.type === "url") {
        try {
          const url = new URL(value);
          if (!["http:", "https:"].includes(url.protocol)) throw new Error();
        } catch {
          errors[field.path] = "Enter a complete HTTP or HTTPS URL.";
        }
      }
    }
  }
  const config = decodeConfig(adapter.configParams);
  if (["TlsTcpInputAdapter", "HttpsInputAdapter"].includes(adapter.type)) {
    if (!config.keyStorePassword && !config.keyStorePasswordEnv)
      errors["configParams.keyStorePasswordEnv"] =
        "A password or environment variable is required.";
    if (["want", "need"].includes(config.clientAuth)) {
      if (!config.trustStorePath)
        errors["configParams.trustStorePath"] =
          "Trust store is required for client authentication.";
      if (!config.trustStorePassword && !config.trustStorePasswordEnv)
        errors["configParams.trustStorePasswordEnv"] =
          "A password or environment variable is required.";
    }
  }
  if (adapter.type === "SnmpInputAdapter") {
    if (
      (config.targets || []).some(
        (target: Attributes) =>
          !target.host ||
          (target.port != null &&
            (!Number.isInteger(Number(target.port)) ||
              target.port < 1 ||
              target.port > 65535)),
      )
    )
      errors["configParams.targets"] =
        "Each target needs a host and a valid port (1–65535).";
    if (
      (config.oids || []).some(
        (oid: any) =>
          !String(typeof oid === "string" ? oid : oid.oid || "").trim(),
      )
    )
      errors["configParams.oids"] = "Each OID needs an identifier.";
  }
  return errors;
}
export function summary(adapter: Adapter): string {
  let config: Attributes = {};
  try {
    config = decodeConfig(adapter.configParams);
  } catch {
    return "Invalid stored configuration";
  }
  return (
    config.endpointUrl ||
    config.jdbcUrl ||
    adapter.url ||
    adapter.path ||
    (adapter.host
      ? `${adapter.host}${adapter.port ? `:${adapter.port}` : ""}`
      : "") ||
    (adapter.port ? `Listen on :${adapter.port}` : "") ||
    adapter.topicid ||
    (adapter.sourceField ? `Parse ${adapter.sourceField}` : "") ||
    adapter.type
  );
}
