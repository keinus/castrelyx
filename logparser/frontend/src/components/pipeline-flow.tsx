import type { ReactNode } from "react";
import {
  ArrowRight,
  Braces,
  Cable,
  GitBranch,
  ListFilter,
  Radio,
} from "lucide-react";
import { definition } from "@/lib/adapters";
import { orderedSteps } from "@/lib/pipeline-preview";
import type { Inventory } from "@/lib/types";
import { Badge } from "@/components/ui/badge";

export const stageIcons = {
  input: Radio,
  parser: Braces,
  transform: ListFilter,
  output: Cable,
  structured: GitBranch,
};

export function StageSection({
  number,
  title,
  actions,
  children,
}: {
  number: string;
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="stage-section">
      <span className="stage-number" aria-hidden="true">
        {number}
      </span>
      <div className="mb-0.5 flex min-h-6 items-center justify-between gap-2">
        <h2 className="text-xs font-semibold">{title}</h2>
        {actions}
      </div>
      <div className="flex flex-col gap-1">{children}</div>
    </section>
  );
}

export function PipelineFlow({
  data,
  messageType,
}: {
  data: Inventory;
  messageType: string;
}) {
  const inputs = data.input.filter((a) => a.messagetype === messageType);
  const steps = orderedSteps(
    data.parser.filter((a) => a.messagetype === messageType),
    data.transform.filter((a) => a.messagetype === messageType),
  );
  const outputs = data.output.filter(
    (a) =>
      a.messagetype === messageType ||
      !a.messagetype ||
      a.messagetype === "all",
  );
  const nodes = [
    {
      stage: "input" as const,
      label:
        inputs
          .map((a) => definition("input", a.type)?.label || a.type)
          .join(", ") || "No input",
      enabled: inputs.some((a) => a.enabled),
    },
    ...steps.map(({ stage, config }) => ({
      stage,
      label: definition(stage, config.type)?.label || config.type,
      enabled: config.enabled,
    })),
    {
      stage: "structured" as const,
      label: "Schema mapping",
      enabled: undefined,
    },
    {
      stage: "output" as const,
      label:
        outputs
          .map((a) => definition("output", a.type)?.label || a.type)
          .join(", ") || "No output",
      enabled: outputs.some((a) => a.enabled),
    },
  ];
  return (
    <ol
      aria-label={`${messageType} route`}
      className="flex flex-wrap items-center gap-x-2 gap-y-2"
    >
      {nodes.map((node, i) => {
        const Icon = stageIcons[node.stage];
        return (
          <li
            key={`${node.stage}-${i}`}
            className="flex min-w-0 items-center gap-2"
          >
            {i > 0 && (
              <ArrowRight
                aria-hidden="true"
                className="size-3 shrink-0 text-muted-foreground"
              />
            )}
            <Badge
              variant="outline"
              className="max-w-full gap-2 py-1 font-normal"
              title={node.label}
            >
              <Icon className="size-3.5 shrink-0" />
              <span className="truncate">{node.label}</span>
              {node.enabled !== undefined && (
                <span
                  aria-label={node.enabled ? "Enabled" : "Disabled"}
                  className={`size-1.5 shrink-0 rounded-full ${node.enabled ? "bg-success" : "bg-muted-foreground"}`}
                />
              )}
            </Badge>
          </li>
        );
      })}
    </ol>
  );
}
