import { useEffect, useId, useState } from "react";
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Copy,
  Play,
  Plus,
  Save,
  Trash2,
  Wand2,
} from "lucide-react";
import { toast } from "sonner";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useDirty, useWorkspace } from "@/lib/workspace";
import { useResource } from "@/hooks/use-resource";
import type { FieldMapping, Mapping, MappingTemplate } from "@/lib/types";
import {
  ConfirmAction,
  EmptyState,
  ErrorNotice,
  PageHeading,
  SelectInput,
} from "@/components/shared";
import { MessageTypeControl } from "@/components/message-type-control";
import { CodeInput, CodePreview } from "@/components/code-panel";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
} from "@/components/ui/card";
import { Field, FieldLabel } from "@/components/ui/field";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";

export const emptyMapping = (messageType: string): Mapping => ({
  messageType,
  commonMappings: [],
  subTableRules: [],
});
function MappingRows({
  rows,
  onChange,
  columns,
  sourceFields = [],
  hideAdd = false,
}: {
  rows: FieldMapping[];
  onChange: (rows: FieldMapping[]) => void;
  columns: any[];
  sourceFields?: string[];
  hideAdd?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <datalist id={`${id}-sources`}>
        {sourceFields.map((field) => (
          <option key={field} value={field} />
        ))}
      </datalist>
      {rows.length > 0 && (
        <div
          aria-hidden="true"
          className="mapping-row hidden text-xs font-medium sm:grid"
        >
          <span>Source field</span>
          <span />
          <span>Target attribute</span>
          <span>Default value</span>
          <span />
        </div>
      )}
      {rows.map((row, i) => (
        <div className="mapping-row border-b pb-3 sm:border-0 sm:pb-0" key={i}>
          <Field>
            <FieldLabel className="sm:sr-only" htmlFor={`${id}-${i}-source`}>
              Source field
            </FieldLabel>
            <Input
              id={`${id}-${i}-source`}
              list={`${id}-sources`}
              value={row.sourceField}
              placeholder="e.g. src_ip"
              onChange={(e) =>
                onChange(
                  rows.map((r, j) =>
                    i === j ? { ...r, sourceField: e.target.value } : r,
                  ),
                )
              }
            />
          </Field>
          <ArrowRight
            aria-hidden="true"
            className="mb-2 hidden size-4 text-muted-foreground sm:block"
          />
          <Field>
            <FieldLabel className="sm:sr-only" htmlFor={`${id}-${i}-target`}>
              Target attribute
            </FieldLabel>
            <SelectInput
              id={`${id}-${i}-target`}
              label={`Target attribute ${i + 1}`}
              value={row.targetField}
              options={[
                ...new Set(
                  [...columns.map((c) => c.name), row.targetField].filter(
                    Boolean,
                  ),
                ),
              ]}
              onChange={(value) =>
                onChange(
                  rows.map((r, j) =>
                    i === j ? { ...r, targetField: value } : r,
                  ),
                )
              }
            />
          </Field>
          <Field>
            <FieldLabel className="sm:sr-only" htmlFor={`${id}-${i}-default`}>
              Default value
            </FieldLabel>
            <Input
              id={`${id}-${i}-default`}
              value={row.defaultValue ?? ""}
              placeholder="Optional"
              onChange={(e) =>
                onChange(
                  rows.map((r, j) =>
                    i === j
                      ? { ...r, defaultValue: e.target.value || null }
                      : r,
                  ),
                )
              }
            />
          </Field>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Remove mapping ${i + 1}`}
            onClick={() => onChange(rows.filter((_, j) => i !== j))}
          >
            <Trash2 />
          </Button>
        </div>
      ))}
      {!hideAdd && (
        <Button
          variant="outline"
          className="self-start"
          onClick={() =>
            onChange([
              ...rows,
              { sourceField: "", targetField: "", defaultValue: null },
            ])
          }
        >
          <Plus data-icon="inline-start" />
          Add mapping
        </Button>
      )}
    </div>
  );
}
export function MappingEditor({
  onDraft,
  onPersisted,
  sourceFields,
  compact = false,
}: {
  onDraft?: (draft: Mapping) => void;
  onPersisted?: (saved: Mapping) => void;
  sourceFields?: string[];
  compact?: boolean;
}) {
  const { messageType } = useWorkspace(),
    schema = useResource<any>("/structure/schema"),
    templates = useResource<MappingTemplate[]>("/structure/templates");
  const [mapping, setMapping] = useState<Mapping>(emptyMapping(messageType)),
    [original, setOriginal] = useState<Mapping>(emptyMapping(messageType)),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [loadError, setLoadError] = useState(""),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const [selectedTemplate, setSelectedTemplate] = useState(""),
    [templateDialog, setTemplateDialog] = useState<"new" | "edit" | null>(null),
    [templateName, setTemplateName] = useState(""),
    [templateDescription, setTemplateDescription] = useState("");
  const [sample, setSample] = useState(
      '{\n  "src_ip": "192.0.2.10",\n  "message": "Example event"\n}',
    ),
    [result, setResult] = useState<any>(null),
    id = useId();
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setLoadError("");
    setResult(null);
    if (!messageType) {
      setLoading(false);
      return;
    }
    api<Mapping>(
      `/structure/mapping/${encodeURIComponent(messageType)}`,
      "GET",
      undefined,
      controller.signal,
    )
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404)
          return emptyMapping(messageType);
        throw e;
      })
      .then((value) => {
        if (!controller.signal.aborted) {
          setMapping(value);
          setOriginal(value);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setLoadError(errorMessage(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [messageType, revision]);
  useEffect(() => {
    if (!loading && !loadError) onDraft?.(mapping);
  }, [mapping, loading, loadError, onDraft]);
  useEffect(() => {
    if (!loading && !loadError) onPersisted?.(original);
  }, [original, loading, loadError, onPersisted]);
  const dirty = JSON.stringify(mapping) !== JSON.stringify(original);
  useDirty(dirty);
  function change(next: Mapping) {
    setMapping(next);
    setResult(null);
  }
  function assertValid() {
    for (const rows of [
      mapping.commonMappings,
      ...mapping.subTableRules.map((rule) => rule.mappings),
    ]) {
      if (
        rows.some(
          (row) =>
            !row.targetField ||
            (!row.sourceField.trim() && row.defaultValue == null),
        )
      )
        throw new Error(
          "Each mapping requires a target and either a source field or a default value.",
        );
    }
    if (mapping.subTableRules.some((rule) => !rule.targetSubTable))
      throw new Error("Choose a target table for each rule.");
  }
  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  const currentTemplate = templates.data?.find(
    (t) => t.id === selectedTemplate,
  );
  if (!messageType)
    return (
      <EmptyState
        title="Choose a message type"
        description="Create or select a message type to edit its schema mapping."
      />
    );
  if (loading || schema.loading) return <Skeleton className="h-80" />;
  if (loadError)
    return (
      <ErrorNotice
        error={loadError}
        retry={() => setRevision((value) => value + 1)}
      />
    );
  return (
    <div className="flex flex-col gap-5">
      {error && <ErrorNotice error={error} />}
      {schema.error && (
        <ErrorNotice error={schema.error} retry={schema.refresh} />
      )}
      {templates.error && (
        <ErrorNotice error={templates.error} retry={templates.refresh} />
      )}
      <Card size="sm" className="gap-2">
        <CardHeader className="flex flex-wrap items-center justify-between gap-3 border-b">
          <div>
            <CardTitle className="flex items-center gap-2 text-base">
              Structured mapping
              {dirty && <Badge variant="secondary">Unsaved</Badge>}
            </CardTitle>
            <CardDescription className="sr-only">
              Map event fields to common attributes and conditional sub-tables.
            </CardDescription>
          </div>
          <fieldset
            disabled={busy || !!schema.error}
            className="flex flex-wrap gap-2"
          >
            <div className="w-56">
              <SelectInput
                label="Mapping template"
                value={selectedTemplate}
                options={
                  templates.data?.map((t) => ({
                    value: t.id,
                    label: t.name,
                  })) || []
                }
                onChange={setSelectedTemplate}
              />
            </div>
            <ConfirmAction
              title="Apply this template?"
              description={`This replaces the saved mapping for ${messageType}, discards unsaved changes, and immediately refreshes the runtime mapping.`}
              label="Apply template"
              trigger={
                <Button variant="outline" disabled={!currentTemplate}>
                  Apply
                </Button>
              }
              action={async () => {
                const next = await api<Mapping>(
                  `/structure/templates/${encodeURIComponent(selectedTemplate)}/apply?messageType=${encodeURIComponent(messageType)}`,
                  "POST",
                  {},
                );
                setMapping(next);
                setOriginal(next);
                setResult(null);
                toast.success("Template applied");
              }}
            />
            <Button
              variant="outline"
              onClick={() => {
                setTemplateName("");
                setTemplateDescription("");
                setTemplateDialog("new");
              }}
            >
              <Copy data-icon="inline-start" />
              Save as template
            </Button>
            {currentTemplate && (
              <>
                <Button
                  variant="ghost"
                  onClick={() => {
                    setTemplateName(currentTemplate.name);
                    setTemplateDescription(currentTemplate.description || "");
                    setTemplateDialog("edit");
                  }}
                >
                  Update template
                </Button>
                <ConfirmAction
                  title="Delete this template?"
                  description="Existing message type mappings will not be changed."
                  destructive
                  label="Delete"
                  trigger={
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Delete template"
                    >
                      <Trash2 />
                    </Button>
                  }
                  action={async () => {
                    await api(
                      `/structure/templates/${encodeURIComponent(selectedTemplate)}`,
                      "DELETE",
                    );
                    setSelectedTemplate("");
                    templates.refresh();
                    toast.success("Template deleted");
                  }}
                />
              </>
            )}
          </fieldset>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <fieldset
            disabled={busy || !!schema.error}
            className="flex flex-col gap-4"
          >
            <section className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-2">
                <h3 className="text-sm font-semibold">
                  Common event attributes
                </h3>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    const aliases: Record<string, string> = {
                      timestamp: "event_time",
                      level: "severity",
                      host: "src_host",
                      user: "user_name",
                    };
                    let sampleFields = sourceFields || [];
                    if (!sampleFields.length) {
                      try {
                        sampleFields = Object.keys(JSON.parse(sample));
                      } catch {
                        toast.error("Enter a valid JSON sample first");
                        return;
                      }
                    }
                    const used = new Set(
                      mapping.commonMappings.map((row) => row.targetField),
                    );
                    const rows = sampleFields.flatMap((source) => {
                      const target = aliases[source] || source;
                      if (
                        !used.has(target) &&
                        schema.data?.commonSchema.some(
                          (c: any) => c.name === target,
                        )
                      ) {
                        used.add(target);
                        return [
                          {
                            sourceField: source,
                            targetField: target,
                            defaultValue: null,
                          },
                        ];
                      }
                      return [];
                    });
                    change({
                      ...mapping,
                      commonMappings: [...mapping.commonMappings, ...rows],
                    });
                  }}
                >
                  <Wand2 data-icon="inline-start" />
                  Auto-map
                </Button>
              </div>
              <MappingRows
                rows={mapping.commonMappings}
                columns={schema.data?.commonSchema || []}
                sourceFields={sourceFields}
                onChange={(commonMappings) =>
                  change({ ...mapping, commonMappings })
                }
              />
            </section>
            <section className="flex flex-col gap-2 border-t pt-3">
              <div>
                <h3 className="text-sm font-semibold">Sub-table rules</h3>
                <p className="sr-only">
                  Rules run in this order. Each rule has its own condition and
                  field mappings.
                </p>
              </div>
              {mapping.subTableRules.map((rule, i) => (
                <div
                  key={i}
                  className="flex flex-col gap-2 rounded-lg border p-2 [&_[data-slot=field]]:gap-1"
                >
                  <div className="grid items-end gap-4 xl:grid-cols-[auto_minmax(0,1fr)]">
                    <div className="flex h-8 items-center gap-2">
                      <Badge variant="outline">Rule {i + 1}</Badge>
                      <span className="ml-auto" />
                      {[-1, 1].map((direction) => (
                        <Button
                          key={direction}
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Move rule ${i + 1} ${direction < 0 ? "up" : "down"}`}
                          disabled={
                            i + direction < 0 ||
                            i + direction >= mapping.subTableRules.length
                          }
                          onClick={() => {
                            const next = [...mapping.subTableRules];
                            [next[i], next[i + direction]] = [
                              next[i + direction],
                              next[i],
                            ];
                            change({ ...mapping, subTableRules: next });
                          }}
                        >
                          {direction < 0 ? <ArrowUp /> : <ArrowDown />}
                        </Button>
                      ))}
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Delete rule ${i + 1}`}
                        onClick={() =>
                          change({
                            ...mapping,
                            subTableRules: mapping.subTableRules.filter(
                              (_, j) => i !== j,
                            ),
                          })
                        }
                      >
                        <Trash2 />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Add mapping to rule ${i + 1}`}
                        onClick={() =>
                          change({
                            ...mapping,
                            subTableRules: mapping.subTableRules.map((r, j) =>
                              i === j
                                ? {
                                    ...r,
                                    mappings: [
                                      ...r.mappings,
                                      {
                                        sourceField: "",
                                        targetField: "",
                                        defaultValue: null,
                                      },
                                    ],
                                  }
                                : r,
                            ),
                          })
                        }
                      >
                        <Plus />
                      </Button>
                    </div>
                    <div className="grid gap-4 sm:grid-cols-2">
                      <Field>
                        <FieldLabel htmlFor={`${id}-table-${i}`}>
                          Target sub-table
                        </FieldLabel>
                        <SelectInput
                          id={`${id}-table-${i}`}
                          label={`Rule ${i + 1} table`}
                          value={rule.targetSubTable}
                          options={Object.keys(schema.data?.subSchemas || {})}
                          onChange={(targetSubTable) =>
                            change({
                              ...mapping,
                              subTableRules: mapping.subTableRules.map(
                                (r, j) =>
                                  j === i
                                    ? { ...r, targetSubTable, mappings: [] }
                                    : r,
                              ),
                            })
                          }
                        />
                      </Field>
                      <Field>
                        <FieldLabel htmlFor={`${id}-condition-${i}`}>
                          Condition (SpEL)
                        </FieldLabel>
                        <Input
                          id={`${id}-condition-${i}`}
                          placeholder="['dst_port'] == 443"
                          value={rule.conditionExpression || ""}
                          onChange={(e) =>
                            change({
                              ...mapping,
                              subTableRules: mapping.subTableRules.map(
                                (r, j) =>
                                  j === i
                                    ? {
                                        ...r,
                                        conditionExpression: e.target.value,
                                      }
                                    : r,
                              ),
                            })
                          }
                        />
                      </Field>
                    </div>
                  </div>
                  <MappingRows
                    hideAdd
                    rows={rule.mappings}
                    columns={
                      schema.data?.subSchemas?.[rule.targetSubTable] || []
                    }
                    sourceFields={sourceFields}
                    onChange={(mappings) =>
                      change({
                        ...mapping,
                        subTableRules: mapping.subTableRules.map((r, j) =>
                          j === i ? { ...r, mappings } : r,
                        ),
                      })
                    }
                  />
                </div>
              ))}
              <Button
                className="self-start"
                variant="outline"
                onClick={() =>
                  change({
                    ...mapping,
                    subTableRules: [
                      ...mapping.subTableRules,
                      {
                        targetSubTable:
                          Object.keys(schema.data?.subSchemas || {})[0] || "",
                        conditionExpression: "",
                        mappings: [],
                      },
                    ],
                  })
                }
              >
                <Plus data-icon="inline-start" />
                Add rule
              </Button>
            </section>
          </fieldset>
        </CardContent>
        <CardFooter className="flex-wrap justify-between gap-3 border-t py-3">
          <p className="text-xs text-muted-foreground">
            Changes apply when saved.
          </p>
          <div className="ml-auto flex gap-2">
            <Button
              variant="outline"
              disabled={!dirty || busy}
              onClick={() => change(structuredClone(original))}
            >
              Discard
            </Button>
            <Button
              disabled={!dirty || busy || !!schema.error}
              onClick={() =>
                void perform(async () => {
                  assertValid();
                  await api("/structure/mapping", "POST", mapping);
                  setOriginal(structuredClone(mapping));
                  toast.success("Mapping saved");
                })
              }
            >
              <Save data-icon="inline-start" />
              Save mapping
            </Button>
          </div>
        </CardFooter>
      </Card>
      {!compact && (
        <Card size="sm">
          <CardHeader className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <CardTitle>Test mapping</CardTitle>
              <CardDescription className="mt-1 text-xs">
                Simulate the current draft without saving it.
              </CardDescription>
            </div>
            <Button
              className="self-start"
              disabled={busy}
              onClick={() =>
                void perform(async () => {
                  assertValid();
                  const response = await api("/structure/simulate", "POST", {
                    messageType,
                    sampleData: JSON.parse(sample),
                    temporaryConfig: mapping,
                  });
                  if (!response.success)
                    throw new Error(response.errors.join(", "));
                  setResult(response.result);
                })
              }
            >
              <Play data-icon="inline-start" />
              Run simulation
            </Button>
          </CardHeader>
          <CardContent className="grid gap-3 lg:grid-cols-2">
            <CodeInput
              id={`${id}-sample`}
              label="Sample event fields (JSON)"
              value={sample}
              onChange={(value) => {
                setSample(value);
                setResult(null);
              }}
            />
            <CodePreview
              title="Mapped result (output)"
              value={result ?? undefined}
              empty="Run a simulation to inspect the mapped event."
            />
          </CardContent>
        </Card>
      )}
      <Dialog
        open={!!templateDialog}
        onOpenChange={(open) => {
          if (!busy && !open) setTemplateDialog(null);
        }}
      >
        <DialogContent>
          {error && <ErrorNotice error={error} />}
          <DialogHeader>
            <DialogTitle>
              {templateDialog === "edit"
                ? "Update template"
                : "Save as template"}
            </DialogTitle>
            <DialogDescription>
              The current mapping draft is stored in the template. Updating a
              template does not update existing pipelines.
            </DialogDescription>
          </DialogHeader>
          <Field>
            <FieldLabel htmlFor={`${id}-template-name`}>
              Template name
            </FieldLabel>
            <Input
              id={`${id}-template-name`}
              value={templateName}
              onChange={(e) => setTemplateName(e.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor={`${id}-template-description`}>
              Description
            </FieldLabel>
            <Textarea
              id={`${id}-template-description`}
              value={templateDescription}
              onChange={(e) => setTemplateDescription(e.target.value)}
            />
          </Field>
          <DialogFooter>
            <Button
              disabled={!templateName.trim() || busy}
              onClick={() =>
                void perform(async () => {
                  assertValid();
                  const template = await api<MappingTemplate>(
                    `/structure/templates${templateDialog === "edit" ? `/${encodeURIComponent(selectedTemplate)}` : ""}`,
                    templateDialog === "edit" ? "PUT" : "POST",
                    {
                      name: templateName.trim(),
                      description: templateDescription,
                      sourceMessageType: messageType,
                      config: mapping,
                    },
                  );
                  templates.refresh();
                  setSelectedTemplate(template.id);
                  setTemplateDialog(null);
                  toast.success("Template saved");
                })
              }
            >
              Save template
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
export function MappingPage() {
  const { messageType } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Schema Mapping"
        description="Turn parsed fields into a consistent event schema."
      />
      <MessageTypeControl />
      <MappingEditor key={messageType} />
    </>
  );
}
