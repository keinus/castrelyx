import { memo, useMemo, useRef, type ReactNode } from "react";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

function highlight(line: string) {
  // Render tokens as React text, never HTML: event payloads are untrusted.
  return line
    .split(
      /("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)/g,
    )
    .map((token, i) => {
      const kind = /^".*:\s*$/.test(token)
        ? "key"
        : token.startsWith('"')
          ? "string"
          : /^(true|false|null)$/.test(token)
            ? "literal"
            : /^-?\d/.test(token)
              ? "number"
              : "plain";
      return (
        <span key={i} className={`code-${kind}`}>
          {token}
        </span>
      );
    });
}

export const CodePreview = memo(function CodePreview({
  value,
  empty = "No result yet.",
  title,
  status,
  className,
}: {
  value?: unknown;
  empty?: string;
  title: string;
  status?: ReactNode;
  className?: string;
}) {
  const lines = useMemo(
    () =>
      value === undefined
        ? null
        : (JSON.stringify(value, null, 2) ?? "null").split("\n"),
    [value],
  );
  return (
    <section aria-label={title} className={cn("code-panel", className)}>
      <div className="code-panel-header">
        <h3>{title}</h3>
        <span className="text-xs text-muted-foreground">
          {status || "JSON"}
        </span>
      </div>
      <div
        className="code-panel-body"
        tabIndex={0}
        role="region"
        aria-label={`${title} content`}
      >
        {lines ? (
          <pre className="code-lines">
            <code>
              {lines.map((line, i) => (
                <span className="code-line" key={i}>
                  <span className="code-line-number" aria-hidden="true">
                    {i + 1}
                  </span>
                  <span>
                    {highlight(line)}
                    {"\n"}
                  </span>
                </span>
              ))}
            </code>
          </pre>
        ) : (
          <p className="p-4 text-xs text-muted-foreground">{empty}</p>
        )}
      </div>
    </section>
  );
});

export function CodeInput({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const gutter = useRef<HTMLDivElement>(null);
  return (
    <Field className="code-panel gap-0">
      <div className="code-panel-header">
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <span className="text-xs text-muted-foreground">Sample</span>
      </div>
      <div className="code-input-body">
        <div ref={gutter} className="code-input-gutter" aria-hidden="true">
          {value.split("\n").map((_, i) => (
            <div key={i}>{i + 1}</div>
          ))}
        </div>
        <Textarea
          id={id}
          value={value}
          wrap="off"
          spellCheck={false}
          autoCapitalize="off"
          autoComplete="off"
          className="code-input"
          onChange={(e) => onChange(e.target.value)}
          onScroll={(e) => {
            if (gutter.current)
              gutter.current.scrollTop = e.currentTarget.scrollTop;
          }}
        />
      </div>
    </Field>
  );
}
