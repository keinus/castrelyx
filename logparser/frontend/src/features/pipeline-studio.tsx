import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  ChevronRight,
  GitBranch,
  Play,
  Plus,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { toast } from "sonner";
import { useWorkspace } from "@/lib/workspace";
import { api, ApiError, errorMessage } from "@/lib/api";
import {
  definition,
  duplicateAdapter,
  hydrate,
  newAdapter,
  summary,
  validate,
} from "@/lib/adapters";
import { TYPE_DEFS } from "@/lib/adapter-definitions";
import {
  orderedSteps,
  cacheAfterSave,
  signature,
  simulate,
  testSource,
  type CachedPreview,
  type TestNode,
} from "@/lib/pipeline-preview";
import { type Adapter, type Mapping, type Stage } from "@/lib/types";
import { AdapterEditor } from "@/components/adapters/adapter-form";
import { MessageTypeControl } from "@/components/message-type-control";
import { CodeInput, CodePreview } from "@/components/code-panel";
import { StageSection, stageIcons } from "@/components/pipeline-flow";
import { ConfirmAction, EmptyState, PageHeading } from "@/components/shared";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { MappingEditor, emptyMapping } from "./mapping-editor";

const sampleEvent =
  '{\n  "host": "web-01",\n  "level": "INFO",\n  "message": "User login successful"\n}';
const keyFor = (stage: Stage, adapter: Adapter) =>
  `${stage}:${adapter.id ?? "new"}`;
export function PipelineStudio() {
  const { data, messageType, guard, refresh } = useWorkspace();
  const [selected, setSelected] = useState(""),
    [creating, setCreating] = useState<{
      stage: Stage;
      adapter: Adapter;
    } | null>(null),
    [drafts, setDrafts] = useState<Record<string, Adapter>>({}),
    [editorRevision, setEditorRevision] = useState(0);
  const [mapping, setMapping] = useState<Mapping>(emptyMapping(messageType)),
    [mappingError, setMappingError] = useState(""),
    [outputKey, setOutputKey] = useState(""),
    [sample, setSample] = useState(sampleEvent),
    [cache, setCache] = useState<Record<string, CachedPreview>>({}),
    [testing, setTesting] = useState(false),
    [reordering, setReordering] = useState(false),
    [checks, setChecks] = useState<string[] | null>(null);
  const generation = useRef(0);
  const scoped = useMemo(
    () =>
      Object.fromEntries(
        Object.entries(data).map(([stage, items]) => [
          stage,
          items.filter(
            (a: Adapter) =>
              a.messagetype === messageType ||
              (stage === "output" &&
                (!a.messagetype || a.messagetype === "all")),
          ),
        ]),
      ) as typeof data,
    [data, messageType],
  );
  const steps = useMemo(
    () =>
      orderedSteps(
        scoped.parser.map((a) => drafts[keyFor("parser", a)] || a),
        scoped.transform.map((a) => drafts[keyFor("transform", a)] || a),
      ),
    [scoped, drafts],
  );
  useEffect(() => {
    const controller = new AbortController();
    setMapping(emptyMapping(messageType));
    setMappingError("");
    setCache({});
    setDrafts({});
    setCreating(null);
    setSelected("");
    setOutputKey("");
    setChecks(null);
    generation.current++;
    if (messageType)
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
          if (!controller.signal.aborted) setMapping(value);
        })
        .catch((e) => {
          if (!controller.signal.aborted) setMappingError(errorMessage(e));
        });
    return () => controller.abort();
  }, [messageType]);
  const allItems = [
    ...scoped.input.map((config) => ({ stage: "input" as const, config })),
    ...steps,
    ...scoped.output.map((config) => ({ stage: "output" as const, config })),
  ];
  const activeKey =
    selected ||
    (scoped.input[0]
      ? keyFor("input", scoped.input[0])
      : steps[0]
        ? keyFor(steps[0].stage, steps[0].config)
        : scoped.output[0]
          ? keyFor("output", scoped.output[0])
          : "");
  const selectedItem = allItems.find(
    ({ stage, config }) => keyFor(stage, config) === activeKey,
  );
  const current =
    creating ||
    (selectedItem
      ? { stage: selectedItem.stage, adapter: selectedItem.config }
      : undefined);
  const currentKey = creating
    ? keyFor(creating.stage, creating.adapter)
    : activeKey;
  const onDraft = useCallback(
    (draft: Adapter) =>
      setDrafts((previous) =>
        previous[currentKey] === draft
          ? previous
          : { ...previous, [currentKey]: draft },
      ),
    [currentKey],
  );
  const node = (stage: Stage, config: Adapter): TestNode => {
    const key = keyFor(stage, config);
    let normalized = config;
    try {
      normalized = hydrate(config, stage);
    } catch {
      /* The editor reports malformed stored configuration. */
    }
    return {
      key,
      stage,
      config: drafts[key] || normalized,
      label:
        definition(stage, (drafts[key] || normalized).type)?.label ||
        config.type,
    };
  };
  const chosenOutput =
    scoped.output.find(
      (a) =>
        keyFor("output", a) ===
        (activeKey.startsWith("output:") ? activeKey : outputKey),
    ) ||
    scoped.output.find((a) => a.enabled) ||
    scoped.output[0];
  const previewSteps =
    creating && ["parser", "transform"].includes(creating.stage)
      ? orderedSteps(
          [
            ...steps.filter((s) => s.stage === "parser").map((s) => s.config),
            ...(creating.stage === "parser"
              ? [drafts[currentKey] || creating.adapter]
              : []),
          ],
          [
            ...steps
              .filter((s) => s.stage === "transform")
              .map((s) => s.config),
            ...(creating.stage === "transform"
              ? [drafts[currentKey] || creating.adapter]
              : []),
          ],
        )
      : steps;
  let nodes: TestNode[] = [
    ...previewSteps
      .filter(
        (s) => s.config.enabled || keyFor(s.stage, s.config) === activeKey,
      )
      .map((s) => node(s.stage, s.config)),
    {
      key: "structured",
      stage: "structured",
      config: mapping,
      label: "Structured mapping",
    },
    ...(chosenOutput ? [node("output", chosenOutput)] : []),
  ];
  if (creating?.stage === "output")
    nodes = [
      ...nodes.filter((n) => n.stage !== "output"),
      node("output", creating.adapter),
    ];
  // Input is an independent local preview; the first processing step uses the sample.
  if (current?.stage === "input") nodes = [node("input", current.adapter)];
  const testIndex = nodes.findIndex((n) => n.key === currentKey),
    selectedNode = nodes[testIndex],
    currentSignature =
      testIndex >= 0 ? signature(nodes, testIndex, sample) : "",
    currentSignatureRef = useRef(currentSignature);
  currentSignatureRef.current = currentSignature;
  const source = testSource(nodes, testIndex, sample, cache),
    savedResult = cache[currentKey],
    result =
      savedResult?.signature === currentSignature ? savedResult : undefined;
  const sourceFields = Object.keys(source.payload);
  const configSignature = JSON.stringify([data, drafts, mapping]);
  useEffect(() => {
    setChecks(null);
  }, [configSignature]);
  function choose(key: string, stage?: Stage) {
    if (!guard()) return;
    setDrafts({});
    setCreating(null);
    setSelected(key);
    setEditorRevision((r) => r + 1);
    if (stage === "output") setOutputKey(key);
  }
  function add(stage: Stage) {
    if (!messageType || !guard()) return;
    const adapter = newAdapter(stage, TYPE_DEFS[stage][0].type, messageType);
    if (stage === "parser" || stage === "transform")
      adapter.priority =
        Math.max(0, ...steps.map((s) => s.config.priority || 0)) + 10;
    setDrafts({});
    setCreating({ stage, adapter });
    setSelected(keyFor(stage, adapter));
    setEditorRevision((r) => r + 1);
  }
  async function reorder(index: number, direction: number) {
    if (!guard()) return;
    setReordering(true);
    try {
      const order = [...steps];
      [order[index], order[index + direction]] = [
        order[index + direction],
        order[index],
      ];
      await api(
        `/pipeline/${encodeURIComponent(messageType)}/processing-steps/order`,
        "PUT",
        { steps: order.map((s) => ({ kind: s.stage, id: s.config.id })) },
      );
      setDrafts({});
      setCache({});
      setEditorRevision((r) => r + 1);
      await refresh();
      toast.success("Processing order updated");
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setReordering(false);
    }
  }
  async function runTest() {
    if (!selectedNode || source.error || testing) return;
    const startedGeneration = generation.current,
      runSignature = currentSignature;
    setTesting(true);
    // Retesting an upstream node invalidates downstream results, even if its config is unchanged.
    setCache((previous) =>
      Object.fromEntries(
        Object.entries(previous).filter(
          ([key]) => nodes.findIndex((n) => n.key === key) < testIndex,
        ),
      ),
    );
    try {
      const preview = await simulate(
        selectedNode,
        source.payload,
        testIndex > 0 && selectedNode.stage === "parser"
          ? JSON.stringify(source.payload)
          : sample,
        messageType,
      );
      if (
        generation.current === startedGeneration &&
        currentSignatureRef.current === runSignature
      )
        setCache((previous) => ({
          ...previous,
          [currentKey]: { ...preview, signature: runSignature },
        }));
    } catch (e) {
      if (
        generation.current === startedGeneration &&
        currentSignatureRef.current === runSignature
      )
        setCache((previous) => ({
          ...previous,
          [currentKey]: {
            payload: { error: errorMessage(e) },
            note: "Test failed",
            error: true,
            signature: runSignature,
          },
        }));
    } finally {
      setTesting(false);
    }
  }
  const renderItem = (stage: Stage, adapter: Adapter, index?: number) => {
    const Icon = stageIcons[stage],
      key = keyFor(stage, adapter),
      selected = key === currentKey,
      display = drafts[key] || adapter;
    return (
      <div
        key={key}
        className={`flex items-center gap-1 rounded-lg border ${selected ? "border-primary/50 bg-accent" : "border-transparent"}`}
      >
        <Button
          variant="ghost"
          className="h-auto min-w-0 flex-1 justify-start gap-2 px-2 py-1 text-left"
          aria-pressed={selected}
          aria-label={`${definition(stage, display.type)?.label || display.type} ${stage === "parser" || stage === "transform" ? `Order ${display.priority || 0} · ` : ""}${display.enabled ? "Enabled" : "Disabled"}`}
          onClick={() => choose(key, stage)}
        >
          <Icon className="shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm">
              {definition(stage, display.type)?.label || display.type}
            </span>
            <span className="mt-0.5 block truncate text-[11px] font-normal text-muted-foreground">
              {stage === "parser" || stage === "transform"
                ? `Order ${display.priority || 0} · ${display.enabled ? "Enabled" : "Disabled"}`
                : `${display.enabled ? "Enabled" : "Disabled"} · ${summary(display)}`}
            </span>
          </span>
          {selected && <ChevronRight className="shrink-0 text-primary" />}
        </Button>
        {index !== undefined && (
          <div className="flex flex-col pr-1">
            {[-1, 1].map((direction) => (
              <Button
                variant="ghost"
                size="icon-xs"
                key={direction}
                disabled={
                  reordering ||
                  index + direction < 0 ||
                  index + direction >= steps.length
                }
                aria-label={`Move ${definition(stage, adapter.type)?.label || adapter.type} ${direction < 0 ? "up" : "down"}`}
                onClick={() => void reorder(index, direction)}
              >
                {direction < 0 ? <ArrowUp /> : <ArrowDown />}
              </Button>
            ))}
          </div>
        )}
      </div>
    );
  };
  return (
    <>
      <PageHeading
        title="Pipeline Studio"
        description="Configure, inspect, and test your event pipeline."
      />
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-card p-3">
        <MessageTypeControl />
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            disabled={!messageType}
            onClick={() => {
              const issues: string[] = [];
              if (
                !scoped.input.some(
                  (a) => (drafts[keyFor("input", a)] || a).enabled,
                )
              )
                issues.push("No enabled input is saved for this message type.");
              if (
                !scoped.output.some(
                  (a) => (drafts[keyFor("output", a)] || a).enabled,
                )
              )
                issues.push(
                  "No enabled output is saved for this message type.",
                );
              for (const item of [
                ...allItems,
                ...(creating
                  ? [{ stage: creating.stage, config: creating.adapter }]
                  : []),
              ]) {
                try {
                  const v =
                    drafts[keyFor(item.stage, item.config)] ||
                    hydrate(item.config, item.stage);
                  Object.values(validate(v, item.stage)).forEach((error) =>
                    issues.push(
                      `${definition(item.stage, v.type)?.label}: ${error}`,
                    ),
                  );
                } catch (e) {
                  issues.push(errorMessage(e));
                }
              }
              setChecks(issues);
            }}
          >
            <ShieldCheck data-icon="inline-start" />
            Validate
          </Button>
          <ConfirmAction
            title="Reload saved configuration?"
            description="This uses saved configuration and can restart active adapters. Unsaved drafts are not included."
            label="Reload configuration"
            trigger={
              <Button variant="outline">
                <RefreshCw data-icon="inline-start" />
                Reload configuration
              </Button>
            }
            action={async () => {
              await api("/pipeline/validate-and-reload", "POST");
              await refresh();
              toast.success("Configuration validated and reloaded");
            }}
          />
        </div>
      </div>
      {checks && (
        <Alert variant={checks.length ? "destructive" : "default"}>
          <ShieldCheck />
          <AlertTitle>
            {checks.length
              ? `${checks.length} configuration issue${checks.length === 1 ? "" : "s"}`
              : "Local field checks passed"}
          </AlertTitle>
          <AlertDescription>
            {checks.length ? (
              <ul className="list-inside list-disc">
                {checks.map((issue, i) => (
                  <li key={i}>{issue}</li>
                ))}
              </ul>
            ) : (
              "Required fields and route presence checked. Connections, credentials, and runtime delivery have not been tested."
            )}
          </AlertDescription>
        </Alert>
      )}
      {!messageType ? (
        <EmptyState
          title="Build your first pipeline"
          description="Create a message type above, then add an input, processing steps, and an output."
        />
      ) : (
        <>
          <div className="grid items-stretch gap-3 xl:grid-cols-[320px_minmax(0,1fr)]">
            <Card className="gap-0 overflow-hidden py-0">
              <CardHeader className="border-b py-3">
                <CardTitle className="text-sm">Pipeline stages</CardTitle>
                <CardDescription>
                  Choose a component to configure
                </CardDescription>
              </CardHeader>
              <CardContent className="p-3">
                <StageSection
                  number="01"
                  title="Input"
                  actions={
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label="Add input"
                      onClick={() => add("input")}
                    >
                      <Plus />
                    </Button>
                  }
                >
                  {scoped.input.map((a) => renderItem("input", a))}
                  {!scoped.input.length && (
                    <p className="px-2 pb-3 text-xs text-muted-foreground">
                      No input configured
                    </p>
                  )}
                </StageSection>
                <StageSection
                  number="02"
                  title="Processing steps"
                  actions={<Badge variant="outline">{steps.length}</Badge>}
                >
                  {steps.map((s, i) => renderItem(s.stage, s.config, i))}
                  <div className="mt-2 flex flex-wrap gap-1">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => add("parser")}
                    >
                      <Plus data-icon="inline-start" />
                      Parser
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => add("transform")}
                    >
                      <Plus data-icon="inline-start" />
                      Transform
                    </Button>
                  </div>
                </StageSection>
                <StageSection number="03" title="Structured mapping">
                  <Button
                    variant={
                      currentKey === "structured" ? "secondary" : "ghost"
                    }
                    className="h-auto w-full justify-start gap-2 px-2 py-1"
                    onClick={() => choose("structured")}
                    aria-pressed={currentKey === "structured"}
                  >
                    <GitBranch />
                    <span className="flex-1 text-left">
                      Field mapping
                      <span className="mt-1 block text-xs font-normal text-muted-foreground">
                        {mapping.commonMappings.length} common attributes
                      </span>
                    </span>
                    <ChevronRight />
                  </Button>
                </StageSection>
                <StageSection
                  number="04"
                  title="Output"
                  actions={
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label="Add output"
                      onClick={() => add("output")}
                    >
                      <Plus />
                    </Button>
                  }
                >
                  {scoped.output.map((a) => renderItem("output", a))}
                  {!scoped.output.length && (
                    <p className="px-2 pb-3 text-xs text-muted-foreground">
                      No output configured
                    </p>
                  )}
                </StageSection>
              </CardContent>
            </Card>
            <div className="flex min-w-0 flex-col [&>div]:flex-1">
              {currentKey === "structured" ? (
                <MappingEditor
                  key={messageType}
                  onDraft={setMapping}
                  sourceFields={sourceFields}
                  compact
                />
              ) : current ? (
                <AdapterEditor
                  key={`${currentKey}:${editorRevision}`}
                  stage={current.stage}
                  adapter={current.adapter}
                  sourceFields={sourceFields}
                  onDraft={onDraft}
                  onDuplicate={(draft) => {
                    if (!guard()) return;
                    const copy = duplicateAdapter(draft);
                    setDrafts({});
                    setCreating({ stage: current.stage, adapter: copy });
                    setSelected(keyFor(current.stage, copy));
                    setEditorRevision((r) => r + 1);
                  }}
                  onSaved={(saved) => {
                    if (saved?.id)
                      setCache((previous) =>
                        cacheAfterSave(
                          previous,
                          nodes,
                          currentKey,
                          {
                            key: keyFor(current.stage, saved),
                            stage: current.stage,
                            config: hydrate(saved, current.stage),
                            label:
                              definition(current.stage, saved.type)?.label ||
                              saved.type,
                          },
                          sample,
                        ),
                      );
                    else setCache({});
                    setDrafts({});
                    setCreating(null);
                    setEditorRevision((r) => r + 1);
                    setSelected(saved?.id ? keyFor(current.stage, saved) : "");
                  }}
                />
              ) : (
                <EmptyState
                  title="Choose a pipeline component"
                  description="Select a stage to configure its attributes, or add a new component."
                />
              )}
            </div>
          </div>
          <Card size="sm" className="gap-3">
            <CardHeader>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <CardTitle>Test pipeline</CardTitle>
                  <CardDescription className="mt-1.5 text-xs">
                    Test one step at a time. Each step consumes the preceding
                    test result.
                  </CardDescription>
                </div>
                <Button
                  disabled={
                    testing ||
                    !selectedNode ||
                    !!source.error ||
                    (selectedNode.stage === "structured" && !!mappingError)
                  }
                  onClick={() => void runTest()}
                >
                  <Play data-icon="inline-start" />
                  {testing ? "Testing…" : "Test selected step"}
                </Button>
              </div>
            </CardHeader>
            <CardContent className="grid gap-3 lg:grid-cols-2">
              <div className="flex min-w-0 flex-col gap-2">
                <CodeInput
                  id="pipeline-sample"
                  label="Original sample event"
                  value={sample}
                  onChange={(value) => {
                    setSample(value);
                    setCache({});
                    generation.current++;
                  }}
                />
                <p className="text-xs text-muted-foreground">
                  {selectedNode
                    ? `${selectedNode.label} · ${selectedNode.stage === "input" ? "local payload preview" : "current draft"}`
                    : "Select a component to test."}
                </p>
                {testIndex > 0 && !source.error && (
                  <details>
                    <summary className="cursor-pointer text-xs text-muted-foreground">
                      Source · {nodes[testIndex - 1].label} result
                    </summary>
                    <CodePreview
                      className="mt-2"
                      title="Preceding step result"
                      value={source.payload}
                    />
                  </details>
                )}
                {selectedNode?.stage === "parser" &&
                  testIndex > 0 &&
                  !(selectedNode.config as Adapter).sourceField && (
                    <p className="text-xs text-muted-foreground">
                      This preview parses the preceding result. In the running
                      pipeline, a blank Input field parses originalText.
                    </p>
                  )}
              </div>
              <div className="min-w-0">
                <CodePreview
                  title={`${selectedNode?.label || "Step"} result`}
                  value={result?.payload}
                  empty={source.error || "Run this step to inspect its result."}
                  status={
                    result && (
                      <Badge
                        variant={result.error ? "destructive" : "secondary"}
                      >
                        {result.error
                          ? "Failed"
                          : result.dropped
                            ? "Dropped"
                            : "Completed"}
                      </Badge>
                    )
                  }
                />
                <p
                  role="status"
                  className="mt-2 text-xs leading-relaxed text-muted-foreground"
                >
                  {result?.note ||
                    (savedResult
                      ? "Previous result is stale because the sample or configuration changed."
                      : "Tests do not save configuration or deliver events to outputs.")}
                </p>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </>
  );
}
