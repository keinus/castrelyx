import { useEffect, useId, useRef, useState } from "react";
import { BookOpen, ExternalLink } from "lucide-react";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { api, errorMessage } from "@/lib/api";
import { buildStarUmlRenderables } from "@/lib/staruml";
import { PageHeading, ErrorNotice } from "@/components/shared";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

function resolvePath(current: string, target: string) {
  const base = new URL(current, "https://docs.local/");
  return new URL(target, base).pathname.slice(1);
}
export function Documentation() {
  const initial =
    new URLSearchParams(location.hash.split("?")[1]).get("doc") ||
    "readme/logparser-user-manual.md";
  const [path, setPath] = useState(initial),
    [input, setInput] = useState(initial),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    container = useRef<HTMLDivElement>(null),
    renderId = useRef(0),
    id = useId();
  function navigate(next: string) {
    setPath(next);
    setInput(next);
    const hash = new URLSearchParams(location.hash.split("?")[1]);
    hash.set("doc", next);
    history.replaceState(null, "", `#docs?${hash}`);
  }
  useEffect(() => {
    const controller = new AbortController(),
      generation = ++renderId.current;
    setLoading(true);
    setError("");
    async function render() {
      try {
        const payload = await api<{
          path: string;
          content: string;
          mediaType: string;
        }>(
          `/docs/content?path=${encodeURIComponent(path)}`,
          "GET",
          undefined,
          controller.signal,
        );
        if (controller.signal.aborted || !container.current) return;
        const root = document.createElement("div"),
          diagrams: { element: HTMLElement; source: string }[] = [];
        function staruml(source: string, parent: HTMLElement) {
          const items = buildStarUmlRenderables(
            JSON.parse(source.replace(/^\uFEFF/, "")),
          );
          if (!items.length) {
            const pre = document.createElement("pre");
            pre.textContent = source;
            parent.append(pre);
            return;
          }
          for (const item of items) {
            if (!item) continue;
            const section = document.createElement("section"),
              heading = document.createElement("h3"),
              graph = document.createElement("div");
            heading.textContent = item.title;
            section.append(heading, graph);
            parent.append(section);
            if (item.mermaid)
              diagrams.push({ element: graph, source: item.mermaid });
          }
        }
        if (/\.mdj$/i.test(path)) staruml(payload.content, root);
        else if (/\.mmd$/i.test(path)) {
          const graph = document.createElement("div");
          root.append(graph);
          diagrams.push({ element: graph, source: payload.content });
        } else if (
          /\.md(own|arkdown)?$/i.test(path) ||
          payload.mediaType === "text/markdown"
        ) {
          root.innerHTML = DOMPurify.sanitize(
            await marked.parse(payload.content),
          );
          for (const code of root.querySelectorAll<HTMLElement>("pre > code")) {
            if (/language-(mermaid|mmd)\b/.test(code.className)) {
              const graph = document.createElement("div");
              code.parentElement!.replaceWith(graph);
              diagrams.push({ element: graph, source: code.textContent || "" });
            }
            if (/language-(staruml|staruml-json|mdj)\b/.test(code.className)) {
              const section = document.createElement("section");
              try {
                staruml(code.textContent || "", section);
                code.parentElement!.replaceWith(section);
              } catch {
                /* Preserve readable source when the model cannot be converted. */
              }
            }
          }
        } else {
          const pre = document.createElement("pre");
          pre.textContent = payload.content;
          root.append(pre);
        }
        for (const heading of root.querySelectorAll("h1,h2,h3,h4"))
          heading.id = (heading.textContent || "")
            .trim()
            .toLowerCase()
            .replace(/[^\p{L}\p{N}\s-]/gu, "")
            .replace(/\s+/g, "-");
        for (const img of root.querySelectorAll("img")) {
          const src = img.getAttribute("src");
          if (src && !/^(https?:)?\/\//i.test(src) && !src.startsWith("data:"))
            img.src = `/api/v1/docs/raw?path=${encodeURIComponent(resolvePath(payload.path, src))}`;
        }
        for (const link of root.querySelectorAll("a")) {
          const href = link.getAttribute("href") || "";
          if (/^(https?:)?\/\//i.test(href)) {
            link.target = "_blank";
            link.rel = "noopener noreferrer";
          } else if (href.startsWith("#"))
            link.onclick = (e) => {
              e.preventDefault();
              root.querySelectorAll("[id]").forEach((el) => {
                if (el.id === decodeURIComponent(href.slice(1)))
                  el.scrollIntoView({ block: "start" });
              });
            };
          else if (!/^[a-z]+:/i.test(href)) {
            const target = resolvePath(payload.path, href);
            if (/\.(md|markdown|mdj|mmd)(#|\?|$)/i.test(href)) {
              link.href = `#docs?doc=${encodeURIComponent(target)}`;
              link.onclick = (e) => {
                e.preventDefault();
                navigate(target);
              };
            } else {
              link.href = `/api/v1/docs/raw?path=${encodeURIComponent(target)}`;
              link.target = "_blank";
              link.rel = "noopener";
            }
          }
        }
        container.current.replaceChildren(root);
        setLoading(false);
        if (diagrams.length) {
          const { default: mermaid } = await import("mermaid");
          mermaid.initialize({
            startOnLoad: false,
            securityLevel: "strict",
            theme: "dark",
            flowchart: { htmlLabels: false },
          });
          for (let i = 0; i < diagrams.length; i++) {
            if (controller.signal.aborted || generation !== renderId.current)
              return;
            const diagram = diagrams[i];
            try {
              const result = await mermaid.render(
                `doc-diagram-${generation}-${i}`,
                diagram.source,
              );
              if (!controller.signal.aborted)
                diagram.element.innerHTML = DOMPurify.sanitize(result.svg, {
                  USE_PROFILES: { svg: true, svgFilters: true },
                });
            } catch {
              const pre = document.createElement("pre");
              pre.textContent = `Diagram could not be rendered. Source:\n${diagram.source}`;
              diagram.element.replaceChildren(pre);
            }
          }
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          setError(errorMessage(e));
          setLoading(false);
          container.current?.replaceChildren();
        }
      }
    }
    void render();
    return () => controller.abort();
  }, [path, revision]);
  return (
    <>
      <PageHeading
        title="Documentation"
        description="The LogParser manual, configuration reference, and diagrams."
      >
        <Button asChild variant="outline">
          <a
            href={`/api/v1/docs/raw?path=${encodeURIComponent(path)}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            <ExternalLink data-icon="inline-start" />
            View source
          </a>
        </Button>
      </PageHeading>
      <div className="flex flex-wrap gap-2">
        {[
          ["User manual", "readme/logparser-user-manual.md"],
          ["Configuration reference", "readme/logparser_schema.md"],
          ["Diagrams", "readme/diagram_samples.md"],
          ["README", "README.md"],
        ].map(([label, target]) => (
          <Button
            variant={path === target ? "secondary" : "outline"}
            key={target}
            onClick={() => navigate(target)}
          >
            <BookOpen data-icon="inline-start" />
            {label}
          </Button>
        ))}
      </div>
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          navigate(input.trim());
        }}
      >
        <Field>
          <FieldLabel htmlFor={id}>Document path</FieldLabel>
          <Input
            id={id}
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
        </Field>
        <Button variant="outline" disabled={!input.trim()}>
          Open
        </Button>
      </form>
      {error && (
        <ErrorNotice error={error} retry={() => setRevision((v) => v + 1)} />
      )}
      {loading && <Skeleton className="h-80" />}
      <Card className={loading ? "hidden" : ""}>
        <CardContent>
          <article
            ref={container}
            className="documentation mx-auto max-w-5xl"
            aria-label="Document content"
          />
        </CardContent>
      </Card>
    </>
  );
}
