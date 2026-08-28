import { useEffect, useId, useRef, useState } from "react";
import { Copy, Info, Loader2, Save, ShieldAlert, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { TYPE_DEFS } from "@/lib/adapter-definitions";
import {
  definition,
  fieldTab,
  getPath,
  hydrate,
  newAdapter,
  serialize,
  setPath,
  validate,
} from "@/lib/adapters";
import { api, endpoints, errorMessage } from "@/lib/api";
import { useDirty, useWorkspace } from "@/lib/workspace";
import {
  stageNames,
  type Adapter,
  type FieldDefinition,
  type Stage,
} from "@/lib/types";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
  CardAction,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Field,
  FieldLabel,
  FieldDescription,
  FieldError,
  FieldGroup,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ConfirmAction, ErrorNotice, SelectInput } from "@/components/shared";
import {
  AttributeTree,
  EntriesField,
  SnmpCollection,
  snmpSecurityFields,
} from "./collection-fields";

const tabLabel = (tab: string) =>
  ({ mtls: "mTLS", tls: "TLS", dlq: "DLQ" })[tab] ||
  tab[0].toUpperCase() + tab.slice(1);
function FormField({
  field,
  value,
  onChange,
  error,
  onValidity,
  sourceFields,
}: {
  field: FieldDefinition;
  value: any;
  onChange: (v: any) => void;
  error?: string;
  onValidity: (error: string) => void;
  sourceFields?: string[];
}) {
  const id = useId(),
    describedBy =
      [field.help ? `${id}-help` : "", error ? `${id}-error` : ""]
        .filter(Boolean)
        .join(" ") || undefined;
  const props = {
    id,
    "aria-invalid": !!error,
    "aria-describedby": describedBy,
    disabled: field.readonly,
  };
  return field.type === "boolean" ? (
    <Field
      data-invalid={!!error}
      className={field.wide ? "sm:col-span-2" : undefined}
    >
      <div className="flex min-h-5 items-center gap-3">
        <FieldLabel htmlFor={id}>{field.label}</FieldLabel>
        <Switch {...props} checked={!!value} onCheckedChange={onChange} />
      </div>
      {field.help && (
        <FieldDescription id={`${id}-help`}>{field.help}</FieldDescription>
      )}
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  ) : (
    <Field
      data-invalid={!!error}
      className={field.wide ? "sm:col-span-2" : undefined}
      orientation="vertical"
    >
      <div>
        <FieldLabel htmlFor={id}>
          {field.label}
          {field.required && (
            <span aria-hidden="true" className="text-muted-foreground">
              *
            </span>
          )}
          {field.unit && (
            <span className="font-normal text-muted-foreground">
              ({field.unit})
            </span>
          )}
        </FieldLabel>
      </div>
      {field.type === "select" ? (
        <SelectInput
          {...props}
          label={field.label}
          invalid={!!error}
          value={value ?? ""}
          options={field.choices || []}
          onChange={onChange}
        />
      ) : ["keyValue", "mapList", "jsonList"].includes(field.type) ? (
        <EntriesField
          field={field}
          value={value}
          onChange={onChange}
          onValidity={onValidity}
        />
      ) : field.type === "json" &&
        ["configParams.targets", "configParams.oids"].includes(field.path) ? (
        <SnmpCollection field={field} value={value} onChange={onChange} />
      ) : field.type === "json" ? (
        <AttributeTree
          label={field.label}
          value={value || {}}
          onChange={onChange}
        />
      ) : field.type === "textarea" ? (
        <Textarea
          {...props}
          value={value ?? ""}
          className="min-h-32 font-mono"
          onChange={(e) => onChange(e.target.value)}
          placeholder={field.placeholder}
        />
      ) : (
        <>
          <Input
            {...props}
            type={
              ["number", "bytes"].includes(field.type)
                ? "number"
                : field.type === "password"
                  ? "password"
                  : field.type === "url"
                    ? "url"
                    : "text"
            }
            min={field.min}
            max={field.max}
            step={["number", "bytes"].includes(field.type) ? 1 : undefined}
            value={value ?? ""}
            autoComplete={field.type === "password" ? "new-password" : "off"}
            placeholder={field.placeholder}
            list={field.path === "sourceField" ? `${id}-sources` : undefined}
            onChange={(e) =>
              onChange(
                ["number", "bytes"].includes(field.type) &&
                  e.target.value !== ""
                  ? Number(e.target.value)
                  : e.target.value,
              )
            }
          />
          {field.path === "sourceField" && (
            <datalist id={`${id}-sources`}>
              {sourceFields?.map((key) => (
                <option key={key} value={key} />
              ))}
            </datalist>
          )}
        </>
      )}
      {field.help && (
        <FieldDescription id={`${id}-help`}>{field.help}</FieldDescription>
      )}
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  );
}
export function AdapterEditor({
  stage,
  adapter,
  onSaved,
  onDraft,
  onDuplicate,
  sourceFields,
  showContext = false,
}: {
  stage: Stage;
  adapter: Adapter;
  onSaved: (saved?: Adapter) => void;
  onDraft?: (draft: Adapter) => void;
  onDuplicate?: (draft: Adapter) => void;
  sourceFields?: string[];
  showContext?: boolean;
}) {
  const { refresh, messageTypes } = useWorkspace(),
    [value, setValue] = useState(() => {
      try {
        return hydrate(adapter, stage);
      } catch {
        return adapter;
      }
    });
  const [initial] = useState(value),
    [configError] = useState(() => {
      try {
        hydrate(adapter, stage);
        return "";
      } catch (e) {
        return errorMessage(e);
      }
    });
  const [errors, setErrors] = useState<Record<string, string>>({}),
    [collectionErrors, setCollectionErrors] = useState<Record<string, string>>(
      {},
    ),
    [busy, setBusy] = useState(false),
    [saveError, setSaveError] = useState("");
  const def = definition(stage, value.type),
    fields = [...(def?.fields || [])];
  if (value.type === "SnmpInputAdapter")
    fields.push(
      ...snmpSecurityFields.map(([key, label, choices]) => ({
        path: `configParams.${key}`,
        label: `Default ${label.toLowerCase()}`,
        type: choices
          ? ("select" as const)
          : key.endsWith("Passphrase")
            ? ("password" as const)
            : ("text" as const),
        choices: choices ? [...choices] : undefined,
        tab: "authentication",
      })),
    );
  const orderField = fields.find((f) => f.path === "priority");
  const tabs = [
    ...new Set([
      ...(def?.tabs || ["general"]),
      ...fields.map(fieldTab),
      "advanced",
    ]),
  ];
  const [tab, setTab] = useState(tabs[0]),
    form = useRef<HTMLFormElement>(null),
    dirty = JSON.stringify(value) !== JSON.stringify(initial),
    id = useId();
  useDirty(dirty);
  useEffect(() => {
    onDraft?.(value);
  }, [value, onDraft]);
  const change = (path: string, v: any) => {
    setValue((previous) => setPath(previous, path, v) as Adapter);
    setErrors((previous) => {
      const next = { ...previous };
      delete next[path];
      return next;
    });
    setSaveError("");
  };
  const knownConfigKeys = fields
    .filter((f) => f.path.startsWith("configParams."))
    .map((f) => f.path.split(".")[1]);
  const additionalConfig = Object.fromEntries(
    Object.entries(value.configParams || {}).filter(
      ([key]) => !knownConfigKeys.includes(key),
    ),
  );
  async function save() {
    const invalid = {
      ...validate(value, stage),
      ...Object.fromEntries(
        Object.entries(collectionErrors).filter(([, error]) => error),
      ),
    };
    setErrors(invalid);
    if (Object.keys(invalid).length) {
      const first = fields.find((f) => invalid[f.path]);
      if (first) setTab(fieldTab(first));
      setTimeout(
        () =>
          form.current
            ?.querySelector<HTMLElement>('[aria-invalid="true"]')
            ?.focus(),
        0,
      );
      return;
    }
    setBusy(true);
    setSaveError("");
    try {
      const saved = await api<Adapter>(
        `${endpoints[stage]}${adapter.id ? `/${adapter.id}` : ""}`,
        adapter.id ? "PUT" : "POST",
        serialize(value),
      );
      await refresh();
      toast.success(
        `${stageNames[stage]} saved. Runtime configuration updated.`,
      );
      onSaved(saved || value);
    } catch (e) {
      setSaveError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="flex h-full min-w-0 flex-col gap-4">
      <Card className="flex-1 overflow-hidden pb-0 [--card-spacing:--spacing(5)]">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg font-semibold">
            {def?.label || value.type}
            {dirty && <Badge variant="secondary">Unsaved</Badge>}
          </CardTitle>
          <CardDescription>
            {stageNames[stage]}{" "}
            {["input", "output"].includes(stage) ? "adapter" : "step"} ·{" "}
            {def?.description}
          </CardDescription>
          <CardAction>
            <Field orientation="horizontal">
              <FieldLabel htmlFor={`${id}-enabled`}>Enabled</FieldLabel>
              <Switch
                id={`${id}-enabled`}
                checked={value.enabled}
                disabled={busy || !!configError}
                onCheckedChange={(v) => change("enabled", v)}
              />
            </Field>
          </CardAction>
        </CardHeader>
        <form
          ref={form}
          className="flex flex-1 flex-col"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <fieldset
            disabled={busy || !!configError}
            className="flex flex-1 flex-col"
          >
            <CardContent className="flex flex-1 flex-col gap-4">
              {configError && <ErrorNotice error={configError} />}
              {!adapter.id && (
                <Field>
                  <FieldLabel htmlFor={`${id}-type`}>
                    {stageNames[stage]} type
                  </FieldLabel>
                  <SelectInput
                    id={`${id}-type`}
                    label="Adapter type"
                    value={value.type}
                    options={TYPE_DEFS[stage].map((d) => ({
                      value: d.type,
                      label: d.label,
                    }))}
                    onChange={(type) => {
                      const next = newAdapter(stage, type, value.messagetype);
                      if (stage === "parser" || stage === "transform")
                        next.priority = value.priority;
                      setValue(next);
                      setErrors({});
                      setCollectionErrors({});
                      setTab(definition(stage, type)?.tabs[0] || "general");
                    }}
                  />
                </Field>
              )}
              <FieldGroup className="grid gap-6 sm:grid-cols-2">
                <Field data-invalid={!!errors.messagetype}>
                  <FieldLabel htmlFor={`${id}-message`}>
                    Message type{" "}
                    <span className="text-muted-foreground">*</span>
                  </FieldLabel>
                  <Input
                    id={`${id}-message`}
                    value={value.messagetype}
                    list={`${id}-types`}
                    aria-invalid={!!errors.messagetype}
                    onChange={(e) => change("messagetype", e.target.value)}
                  />
                  <datalist id={`${id}-types`}>
                    {stage === "output" && <option value="all" />}
                    {messageTypes.map((type) => (
                      <option value={type} key={type} />
                    ))}
                  </datalist>
                  {stage === "output" && (
                    <FieldDescription>
                      Use all to receive events from every message type.
                    </FieldDescription>
                  )}
                  {errors.messagetype && (
                    <FieldError>{errors.messagetype}</FieldError>
                  )}
                </Field>
                {orderField && (
                  <FormField
                    field={orderField}
                    value={value.priority}
                    onChange={(v) => change("priority", v)}
                    error={errors.priority}
                    onValidity={() => {}}
                  />
                )}
              </FieldGroup>
              <Tabs value={tab} onValueChange={setTab}>
                <div className="overflow-x-auto overflow-y-hidden pb-1">
                  <TabsList>
                    {tabs.map((item) => (
                      <TabsTrigger value={item} key={item}>
                        {tabLabel(item)}
                        {fields.some(
                          (f) => fieldTab(f) === item && errors[f.path],
                        ) && (
                          <span className="size-1.5 rounded-full bg-destructive" />
                        )}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </div>
                {tabs.map((item) => (
                  <TabsContent
                    forceMount
                    hidden={tab !== item}
                    value={item}
                    key={item}
                    className="pt-3"
                  >
                    <FieldGroup>
                      <div className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
                        {fields
                          .filter(
                            (f) =>
                              fieldTab(f) === item && f.path !== "priority",
                          )
                          .map((field) => (
                            <FormField
                              key={field.path}
                              field={field}
                              value={getPath(value, field.path)}
                              onChange={(v) => change(field.path, v)}
                              error={errors[field.path]}
                              sourceFields={sourceFields}
                              onValidity={(error) =>
                                setCollectionErrors((previous) => ({
                                  ...previous,
                                  [field.path]: error,
                                }))
                              }
                            />
                          ))}
                      </div>
                      {item === "advanced" && (
                        <>
                          <FieldDescription>
                            Additional saved attributes are preserved. Add named
                            attributes only when supported by this adapter.
                          </FieldDescription>
                          <AttributeTree
                            value={additionalConfig}
                            onChange={(extra) =>
                              change("configParams", {
                                ...Object.fromEntries(
                                  Object.entries(
                                    value.configParams || {},
                                  ).filter(([key]) =>
                                    knownConfigKeys.includes(key),
                                  ),
                                ),
                                ...extra,
                              })
                            }
                          />
                        </>
                      )}
                      {!fields.some((f) => fieldTab(f) === item) &&
                        item !== "advanced" && (
                          <p className="text-sm text-muted-foreground">
                            No additional settings are required for this
                            adapter.
                          </p>
                        )}
                    </FieldGroup>
                    {value.type === "TcpMtlsGzipInputAdapter" &&
                      item === "mtls" && (
                        <section className="mt-6 border-t pt-4">
                          <h3 className="text-sm font-semibold">
                            Transport security
                          </h3>
                          <p className="mt-2 text-xs text-muted-foreground">
                            Each connecting agent must present a trusted client
                            certificate.
                          </p>
                          <dl className="mt-4 grid gap-5 sm:grid-cols-2">
                            <div>
                              <dt className="mb-2 text-xs font-medium">
                                Protocols
                              </dt>
                              <dd className="rounded-md border bg-muted/40 px-3 py-2 font-mono text-xs">
                                TLSv1.3 · TLSv1.2
                              </dd>
                            </div>
                            <div>
                              <dt className="mb-2 text-xs font-medium">
                                Client authentication
                              </dt>
                              <dd className="rounded-md border bg-muted/40 px-3 py-2 text-xs">
                                Required
                              </dd>
                            </div>
                          </dl>
                        </section>
                      )}
                    {value.type === "ClickHouseOutputAdapter" &&
                      item === "buffering" && (
                        <Alert className="mt-5">
                          <Info />
                          <AlertDescription>
                            Structured writes and incomplete-chunk handling use
                            independent limits.
                          </AlertDescription>
                        </Alert>
                      )}
                  </TabsContent>
                ))}
              </Tabs>
              {def?.notice &&
                stage !== "parser" &&
                stage !== "transform" &&
                !(
                  value.type === "ClickHouseOutputAdapter" &&
                  tab === "buffering"
                ) &&
                !(
                  value.type === "TcpMtlsGzipInputAdapter" && tab === "mtls"
                ) && (
                  <p className="text-xs leading-relaxed text-muted-foreground">
                    {def.notice.replace(
                      /inside configParams|in configParams|stored in configParams/g,
                      "in the saved configuration",
                    )}
                  </p>
                )}
              {def?.warning && (
                <Alert>
                  <ShieldAlert />
                  <AlertDescription>{def.warning}</AlertDescription>
                </Alert>
              )}
              {saveError && <ErrorNotice error={saveError} />}
            </CardContent>
            <CardFooter className="mt-5 flex flex-wrap justify-between gap-3 border-t bg-muted/20 py-3">
              <div className="flex items-center gap-2">
                {adapter.id && onDuplicate && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Duplicate ${stage}`}
                    onClick={() => onDuplicate(value)}
                  >
                    <Copy />
                  </Button>
                )}
                {adapter.id && (
                  <ConfirmAction
                    title="Delete this component?"
                    description="This removes the saved component and updates the running pipeline. This cannot be undone."
                    destructive
                    label="Delete"
                    trigger={
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        aria-label={`Delete ${stage}`}
                      >
                        <Trash2 />
                      </Button>
                    }
                    action={async () => {
                      await api(`${endpoints[stage]}/${adapter.id}`, "DELETE");
                      await refresh();
                      toast.success("Component deleted");
                      onSaved();
                    }}
                  />
                )}
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Info className="size-3.5 shrink-0" />
                  Changes apply when saved.
                </span>
              </div>
              <div className="ml-auto flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setValue(structuredClone(initial));
                    setErrors({});
                    setCollectionErrors({});
                    setSaveError("");
                    if (!adapter.id) onSaved();
                  }}
                >
                  Discard
                </Button>
                <Button
                  type="submit"
                  disabled={busy || (!!adapter.id && !dirty)}
                >
                  {busy ? (
                    <Loader2
                      className="animate-spin"
                      data-icon="inline-start"
                    />
                  ) : (
                    <Save data-icon="inline-start" />
                  )}
                  Save {stage}
                </Button>
              </div>
            </CardFooter>
          </fieldset>
        </form>
      </Card>
      {showContext && !configError && <AdapterContext adapter={value} />}
    </div>
  );
}

function AdapterContext({ adapter }: { adapter: Adapter }) {
  const entries = [
    ["Endpoint", adapter.configParams?.endpointUrl],
    ["Database", adapter.configParams?.database],
    ["Listen port", adapter.port],
    [
      "Timeout",
      adapter.timeoutMs == null
        ? undefined
        : `${Number(adapter.timeoutMs).toLocaleString()} ms`,
    ],
    [
      "Queue",
      adapter.queueSize == null
        ? undefined
        : Number(adapter.queueSize).toLocaleString(),
    ],
  ].filter(
    ([, value]) => value !== undefined && value !== null && value !== "",
  );
  if (!entries.length) return null;
  return (
    <dl
      aria-label="Adapter configuration summary"
      className="flex flex-wrap gap-x-6 gap-y-3 rounded-lg border bg-card px-4 py-3 text-xs"
    >
      {entries.map(([label, value]) => (
        <div key={label} className="flex min-w-0 items-center gap-2">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="break-all font-mono">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
