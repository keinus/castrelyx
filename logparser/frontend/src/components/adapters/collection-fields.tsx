import { useEffect, useId, useRef, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Field,
  FieldLabel,
  FieldError,
  FieldSet,
  FieldLegend,
} from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import { SelectInput } from "@/components/shared";
import type { Attributes, FieldDefinition } from "@/lib/types";

function parse(value: any, fallback: any) {
  if (value == null || value === "") return fallback;
  return typeof value === "string" ? JSON.parse(value) : value;
}
export function EntriesField({
  field,
  value,
  onChange,
  onValidity,
}: {
  field: FieldDefinition;
  value: any;
  onChange: (value: string) => void;
  onValidity: (error: string) => void;
}) {
  const list = field.type === "jsonList",
    id = useId();
  const decode = () => {
    try {
      const parsed = parse(value, list ? [] : {});
      return {
        rows: list
          ? parsed.map((v: any) => ["", String(v)])
          : Object.entries(parsed).map(([key, v]) => [
              key,
              Array.isArray(v) ? v.join(", ") : String(v),
            ]),
        error: "",
      };
    } catch {
      return {
        rows: [],
        error:
          "Invalid stored entries. Add valid entries to replace this value.",
      };
    }
  };
  const initial = decode(),
    [rows, setRows] = useState<string[][]>(initial.rows),
    [error, setError] = useState(initial.error),
    emitted = useRef(value);
  useEffect(() => {
    if (value !== emitted.current) {
      const next = decode();
      setRows(next.rows);
      setError(next.error);
      emitted.current = value;
      onValidity(next.error);
    }
  }, [value]);
  function update(next: string[][]) {
    setRows(next);
    const keys = next.map((row) => (list ? row[1] : row[0]).trim());
    const issue = keys.some((key) => !key)
      ? "Complete each entry or remove empty rows."
      : new Set(keys).size !== keys.length
        ? "Each field name must be unique."
        : keys.some((key) =>
              ["__proto__", "constructor", "prototype"].includes(key),
            )
          ? "This field name is reserved."
          : "";
    setError(issue);
    onValidity(issue);
    const output = list
      ? next.map((row) => row[1].trim())
      : Object.fromEntries(
          next.map(([key, v]) => [
            key.trim(),
            field.type === "mapList"
              ? v
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean)
              : v,
          ]),
        );
    emitted.current = JSON.stringify(output);
    onChange(emitted.current);
  }
  return (
    <div className="flex flex-col gap-3">
      {rows.map(([key, val], i) => (
        <div key={i} className="flex items-end gap-2">
          {!list && (
            <Field>
              <FieldLabel htmlFor={`${id}-key-${i}`}>
                {field.type === "mapList" ? "Target object" : "Field name"}{" "}
                {i + 1}
              </FieldLabel>
              <Input
                id={`${id}-key-${i}`}
                value={key}
                onChange={(e) =>
                  update(
                    rows.map((row, index) =>
                      index === i ? [e.target.value, row[1]] : row,
                    ),
                  )
                }
              />
            </Field>
          )}
          <Field>
            <FieldLabel htmlFor={`${id}-value-${i}`}>
              {list
                ? "Field"
                : field.valueLabel || "Source fields (comma separated)"}{" "}
              {i + 1}
            </FieldLabel>
            <Input
              id={`${id}-value-${i}`}
              type={
                field.path === "headers" &&
                /authorization|token|secret/i.test(key)
                  ? "password"
                  : "text"
              }
              value={val}
              onChange={(e) =>
                update(
                  rows.map((row, index) =>
                    index === i ? [row[0], e.target.value] : row,
                  ),
                )
              }
            />
          </Field>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label={`Remove ${field.label} entry ${i + 1}`}
            onClick={() => update(rows.filter((_, index) => index !== i))}
          >
            <Trash2 />
          </Button>
        </div>
      ))}
      {error && <FieldError>{error}</FieldError>}
      <Button
        type="button"
        variant="outline"
        className="self-start"
        onClick={() => update([...rows, ["", ""]])}
      >
        <Plus data-icon="inline-start" />
        Add entry
      </Button>
    </div>
  );
}

export const snmpSecurityFields = [
  ["securityName", "Security name"],
  [
    "securityLevel",
    "Security level",
    ["noAuthNoPriv", "authNoPriv", "authPriv"],
  ],
  [
    "authProtocol",
    "Authentication protocol",
    ["MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512"],
  ],
  ["authPassphrase", "Authentication passphrase"],
  ["authPassphraseEnv", "Authentication passphrase env"],
  ["privProtocol", "Privacy protocol", ["DES", "AES128", "AES192", "AES256"]],
  ["privPassphrase", "Privacy passphrase"],
  ["privPassphraseEnv", "Privacy passphrase env"],
] as const;
export function SnmpCollection({
  field,
  value,
  onChange,
}: {
  field: FieldDefinition;
  value: any;
  onChange: (value: any) => void;
}) {
  const id = useId(),
    targets = field.path.endsWith("targets"),
    rows: any[] = Array.isArray(value) ? value : [];
  const update = (i: number, key: string, v: any) =>
    onChange(
      rows.map((row, index) =>
        index === i
          ? {
              ...(typeof row === "string" ? { oid: row } : row),
              [key]: v === "" ? undefined : v,
            }
          : row,
      ),
    );
  return (
    <div className="flex flex-col gap-3">
      {rows.map((item, i) => {
        const row: Attributes = typeof item === "string" ? { oid: item } : item;
        return (
          <FieldSet key={i} className="rounded-lg border bg-background p-4">
            <div className="flex items-center justify-between">
              <FieldLegend>
                {targets ? "Target" : "OID"} {i + 1}
              </FieldLegend>
              <Button
                size="icon-sm"
                type="button"
                variant="ghost"
                aria-label={`Remove ${targets ? "target" : "OID"} ${i + 1}`}
                onClick={() => onChange(rows.filter((_, index) => index !== i))}
              >
                <Trash2 />
              </Button>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              {(targets
                ? ["name", "host", "port", "version", "community"]
                : ["name", "oid"]
              ).map((key) => (
                <Field key={key}>
                  <FieldLabel htmlFor={`${id}-${i}-${key}`}>
                    {
                      {
                        name: "Name",
                        host: "Host",
                        port: "Port",
                        version: "Version",
                        community: "Community",
                        oid: "OID identifier",
                      }[key]
                    }
                  </FieldLabel>
                  {key === "version" ? (
                    <SelectInput
                      id={`${id}-${i}-${key}`}
                      label={`Target ${i + 1} version`}
                      value={row[key] || "__inherit"}
                      options={[
                        { value: "__inherit", label: "Inherit default" },
                        "1",
                        "2c",
                        "3",
                      ]}
                      onChange={(v) =>
                        update(i, key, v === "__inherit" ? "" : v)
                      }
                    />
                  ) : (
                    <Input
                      id={`${id}-${i}-${key}`}
                      type={
                        key === "port"
                          ? "number"
                          : key === "community"
                            ? "password"
                            : "text"
                      }
                      value={row[key] ?? ""}
                      placeholder={key === "port" ? "161" : undefined}
                      min={key === "port" ? 1 : undefined}
                      max={key === "port" ? 65535 : undefined}
                      onChange={(e) =>
                        update(
                          i,
                          key,
                          key === "port" && e.target.value !== ""
                            ? Number(e.target.value)
                            : e.target.value,
                        )
                      }
                    />
                  )}
                </Field>
              ))}
            </div>
            {targets && (
              <details className="mt-1">
                <summary className="cursor-pointer text-sm text-muted-foreground">
                  SNMPv3 authentication & privacy
                </summary>
                <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  {snmpSecurityFields.map(([key, label, choices]) => (
                    <Field key={key}>
                      <FieldLabel htmlFor={`${id}-${i}-${key}`}>
                        {label}
                      </FieldLabel>
                      {choices ? (
                        <SelectInput
                          label={label}
                          id={`${id}-${i}-${key}`}
                          value={row[key] || "__inherit"}
                          options={[
                            { value: "__inherit", label: "Inherit default" },
                            ...choices,
                          ]}
                          onChange={(v) =>
                            update(i, key, v === "__inherit" ? "" : v)
                          }
                        />
                      ) : (
                        <Input
                          id={`${id}-${i}-${key}`}
                          type={
                            key.endsWith("Passphrase") ? "password" : "text"
                          }
                          value={row[key] || ""}
                          onChange={(e) => update(i, key, e.target.value)}
                        />
                      )}
                    </Field>
                  ))}
                </div>
              </details>
            )}
          </FieldSet>
        );
      })}
      <Button
        type="button"
        variant="outline"
        className="self-start"
        onClick={() =>
          onChange([
            ...rows,
            targets ? { name: "", host: "", port: 161 } : { name: "", oid: "" },
          ])
        }
      >
        <Plus data-icon="inline-start" />
        Add {targets ? "target" : "OID"}
      </Button>
    </div>
  );
}

export function AttributeTree({
  value,
  onChange,
  label = "Additional attributes",
}: {
  value: any;
  onChange: (value: any) => void;
  label?: string;
}) {
  const id = useId(),
    [newKey, setNewKey] = useState(""),
    [kind, setKind] = useState("text");
  if (typeof value !== "object" || value === null)
    return (
      <Field>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        {typeof value === "boolean" ? (
          <Switch id={id} checked={value} onCheckedChange={onChange} />
        ) : (
          <Input
            id={id}
            type={
              typeof value === "number"
                ? "number"
                : /password|passphrase|secret|token/i.test(label) &&
                    !/env/i.test(label)
                  ? "password"
                  : "text"
            }
            value={value ?? ""}
            onChange={(e) =>
              onChange(
                typeof value === "number"
                  ? Number(e.target.value)
                  : e.target.value,
              )
            }
          />
        )}
      </Field>
    );
  const array = Array.isArray(value);
  return (
    <FieldSet className="rounded-lg border p-4">
      <FieldLegend>{label}</FieldLegend>
      {Object.entries(value).map(([key, val]) => (
        <div key={key} className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <AttributeTree
              label={array ? `Item ${Number(key) + 1}` : key}
              value={val}
              onChange={(v) => {
                const next = structuredClone(value);
                next[key] = v;
                onChange(next);
              }}
            />
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={`Remove ${label} ${key}`}
            onClick={() => {
              const next = structuredClone(value);
              if (array) next.splice(Number(key), 1);
              else delete next[key];
              onChange(next);
            }}
          >
            <Trash2 />
          </Button>
        </div>
      ))}
      <div className="flex flex-wrap items-end gap-2">
        {!array && (
          <Field className="min-w-24 flex-1">
            <FieldLabel htmlFor={`${id}-key`}>Attribute name</FieldLabel>
            <Input
              id={`${id}-key`}
              value={newKey}
              onChange={(e) => setNewKey(e.target.value)}
            />
          </Field>
        )}
        <div className="w-28">
          <SelectInput
            label="Attribute type"
            value={kind}
            options={["text", "number", "boolean", "object", "array"]}
            onChange={setKind}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          disabled={
            !array &&
            (!newKey.trim() ||
              newKey in value ||
              ["__proto__", "constructor", "prototype"].includes(newKey))
          }
          onClick={() => {
            const v = {
              text: "",
              number: 0,
              boolean: false,
              object: {},
              array: [],
            }[kind];
            onChange(array ? [...value, v] : { ...value, [newKey.trim()]: v });
            setNewKey("");
          }}
        >
          <Plus data-icon="inline-start" />
          Add
        </Button>
      </div>
    </FieldSet>
  );
}
