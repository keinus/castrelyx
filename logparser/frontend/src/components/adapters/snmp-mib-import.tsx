import { useEffect, useId, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { ErrorNotice } from "@/components/shared";
import { api, errorMessage } from "@/lib/api";
import type { Attributes, MappingTemplate } from "@/lib/types";
import {
  instanceOid,
  mergeMibOids,
  mibKey,
  mibTableKey,
  snmpMibMapping,
  type MibPreview,
} from "@/lib/snmp-mib";

function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error(`Unable to read ${file.name}`));
    reader.readAsText(file);
  });
}

export function SnmpMibImport({
  rows,
  messageType,
  onChange,
  onBusyChange,
}: {
  rows: (string | Attributes)[];
  messageType: string;
  onChange: (rows: (string | Attributes)[]) => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const id = useId();
  const [preview, setPreview] = useState<MibPreview | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [suffixes, setSuffixes] = useState<Record<string, string>>({});
  const [query, setQuery] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function perform(action: () => Promise<void>) {
    setBusy(true);
    onBusyChange(true);
    setError("");
    try {
      await action();
    } catch (e) {
      if (active.current) setError(errorMessage(e));
    } finally {
      if (active.current) setBusy(false);
      onBusyChange(false);
    }
  }
  async function upload(files: File[]) {
    setPreview(null);
    setSuccess("");
    await perform(async () => {
      if (
        !files.length ||
        files.length > 16 ||
        files.some((file) => file.size > 1048576) ||
        files.reduce((total, file) => total + file.size, 0) > 4194304
      ) {
        throw new Error(
          "Choose 1–16 MIB files, up to 1 MiB each and 4 MiB total.",
        );
      }
      const contents = await Promise.all(
        files.map(async (file) => ({
          name: file.name,
          content: await readFile(file),
        })),
      );
      const result = await api<MibPreview>("/snmp/mib/preview", "POST", {
        files: contents,
      });
      if (!active.current) return;
      setPreview(result);
      setSelected(
        new Set(result.objects.filter((object) => object.scalar).map(mibKey)),
      );
      setSuffixes({});
      setQuery("");
      setName(`${result.modules.join(" + ")} SNMP`);
    });
  }
  const chosen = (preview?.objects || []).filter((object) =>
    selected.has(mibKey(object)),
  );
  const instances = chosen.map((object) => ({
    object,
    oid: instanceOid(object, suffixes[mibTableKey(object)] || ""),
  }));
  const invalid = instances.some(({ oid }) => !oid);
  const merged = mergeMibOids(
    rows,
    instances.filter(
      (item): item is { object: typeof item.object; oid: string } =>
        item.oid !== null,
    ),
  );
  const mapping = snmpMibMapping(messageType, merged.metrics);
  const filtered = (preview?.objects || []).filter((object) =>
    `${object.module} ${object.name} ${object.oid} ${object.description}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  return (
    <details className="min-w-0 rounded-lg border p-4">
      <summary className="cursor-pointer text-sm font-medium">
        Import MIB files
      </summary>
      <div className="mt-4 flex min-w-0 flex-col gap-4">
        <Field>
          <FieldLabel htmlFor={`${id}-files`}>MIB files</FieldLabel>
          <Input
            id={`${id}-files`}
            type="file"
            multiple
            accept=".mib,.txt,.my,text/plain"
            disabled={busy}
            onChange={(event) => {
              const files = Array.from(event.target.files || []);
              event.target.value = "";
              if (files.length) void upload(files);
            }}
          />
          <FieldDescription>
            Upload the MIB and its imported vendor modules together. Readable
            scalar and table objects become polling candidates; Trap definitions
            are excluded.
          </FieldDescription>
        </Field>
        {busy && (
          <p role="status" className="text-sm text-muted-foreground">
            Processing MIB import…
          </p>
        )}
        {error && <ErrorNotice error={error} />}
        {success && (
          <p role="status" className="text-sm text-muted-foreground">
            {success}
          </p>
        )}
        {preview && (
          <>
            <p className="text-sm">
              {preview.objects.length} readable OIDs · {selected.size} selected
            </p>
            {preview.warnings.length > 0 && (
              <details className="text-sm">
                <summary>Parser notes ({preview.warnings.length})</summary>
                <ul className="list-disc pl-5">
                  {preview.warnings.map((warning, i) => (
                    <li key={i}>{warning}</li>
                  ))}
                </ul>
              </details>
            )}
            <Field>
              <FieldLabel htmlFor={`${id}-search`}>Filter MIB OIDs</FieldLabel>
              <Input
                id={`${id}-search`}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Name, OID, or description"
              />
            </Field>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => setSelected(new Set(filtered.map(mibKey)))}
              >
                Select filtered
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => setSelected(new Set())}
              >
                Clear selection
              </Button>
            </div>
            <div className="flex max-h-96 min-w-0 flex-col gap-3 overflow-y-auto">
              {filtered.slice(0, 100).map((object) => {
                const key = mibKey(object),
                  checked = selected.has(key),
                  table = mibTableKey(object);
                const oid = instanceOid(object, suffixes[table] || "");
                return (
                  <div key={key} className="min-w-0 rounded-md border p-3">
                    <label className="flex items-start gap-2 text-sm font-medium">
                      <input
                        type="checkbox"
                        className="mt-0.5 size-4 shrink-0 accent-primary"
                        checked={checked}
                        onChange={(event) =>
                          setSelected((previous) => {
                            const next = new Set(previous);
                            if (event.target.checked) next.add(key);
                            else next.delete(key);
                            return next;
                          })
                        }
                        aria-label={`Select ${key}`}
                      />
                      <span className="min-w-0 break-all">{key}</span>
                    </label>
                    <p className="mt-2 break-all font-mono text-xs">
                      {oid || `${object.oid}.<instance>`}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {object.syntax} · {object.access}
                      {object.units ? ` · ${object.units}` : ""}
                    </p>
                    {object.description && (
                      <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                        {object.description}
                      </p>
                    )}
                    {!object.scalar && (
                      <Field className="mt-3">
                        <FieldLabel htmlFor={`${id}-${key}`}>
                          Instance suffix · {object.table}
                        </FieldLabel>
                        <Input
                          id={`${id}-${key}`}
                          aria-label={`Instance suffix for ${key}`}
                          value={suffixes[table] || ""}
                          placeholder="e.g. 3 or 3.7"
                          aria-invalid={checked && !oid}
                          onChange={(event) =>
                            setSuffixes((previous) => ({
                              ...previous,
                              [table]: event.target.value,
                            }))
                          }
                        />
                        <FieldDescription>
                          Index:{" "}
                          {object.indexes.join(", ") || "device-specific"}.
                          Enter the complete numeric instance suffix from your
                          device; shared by this table.
                        </FieldDescription>
                      </Field>
                    )}
                  </div>
                );
              })}
            </div>
            {filtered.length > 100 && (
              <p className="text-sm text-muted-foreground">
                Showing the first 100 of {filtered.length} matches. Filter to
                inspect more OIDs.
              </p>
            )}
            {preview.objects.length === 0 && (
              <p className="text-sm">
                No readable polling objects found. Upload a MIB containing
                readable OBJECT-TYPE definitions.
              </p>
            )}
            {invalid && (
              <p className="text-sm text-destructive">
                Complete the instance suffix for every selected table.
              </p>
            )}
            <Field>
              <FieldLabel htmlFor={`${id}-name`}>MIB template name</FieldLabel>
              <Input
                id={`${id}-name`}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
              <FieldDescription>
                Creates a structured transform template for message type{" "}
                {messageType || "(required)"}. Selected values map to individual
                metric attributes; original OID bindings are retained.
              </FieldDescription>
            </Field>
            <details className="min-w-0 text-sm">
              <summary>Preview structured transform template</summary>
              <pre className="mt-2 max-h-56 overflow-auto whitespace-pre-wrap break-all text-xs">
                {JSON.stringify(mapping, null, 2)}
              </pre>
            </details>
            <Button
              type="button"
              className="self-start"
              disabled={
                busy ||
                !chosen.length ||
                invalid ||
                !name.trim() ||
                !messageType.trim()
              }
              onClick={() =>
                void perform(async () => {
                  const template = await api<MappingTemplate>(
                    "/structure/templates",
                    "POST",
                    {
                      name: name.trim(),
                      description: `SNMP polling from ${preview.modules.join(", ")}`,
                      sourceMessageType: messageType,
                      config: mapping,
                    },
                  );
                  window.dispatchEvent(new Event("mapping-templates-changed"));
                  if (!active.current) return;
                  onChange(merged.rows);
                  setPreview(null);
                  setSuccess(
                    `Template “${template.name}” created. Save input to persist the OIDs, then apply the template in Schema Mapping.`,
                  );
                  toast.success(
                    "MIB template created and OIDs added to the input draft",
                  );
                })
              }
            >
              <Upload data-icon="inline-start" />
              Add OIDs and template
            </Button>
          </>
        )}
      </div>
    </details>
  );
}
