import { lazy, Suspense } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Skeleton } from "@/components/ui/skeleton";
import { WorkspaceProvider, useWorkspace } from "@/lib/workspace";
import { ErrorNotice } from "@/components/shared";
import { PipelineStudio } from "@/features/pipeline-studio";
import { AdapterList } from "@/features/adapter-list";
const Overview = lazy(() =>
  import("@/features/overview").then((m) => ({ default: m.Overview })),
);
import { MappingPage } from "@/features/mapping-editor";
const LiveTail = lazy(() =>
  import("@/features/live-tail").then((m) => ({ default: m.LiveTail })),
);
const Settings = lazy(() =>
  import("@/features/settings").then((m) => ({ default: m.Settings })),
);
const Documentation = lazy(() =>
  import("@/features/documentation").then((m) => ({
    default: m.Documentation,
  })),
);
function Pages() {
  const { view, loading, error, refresh } = useWorkspace();
  return (
    <AppShell>
      {error && <ErrorNotice error={error} retry={() => void refresh()} />}
      {loading ? (
        <>
          <Skeleton className="h-16" />
          <Skeleton className="h-[500px]" />
        </>
      ) : (
        <Suspense fallback={<Skeleton className="h-96" />}>
          {view === "docs" ? (
            <Documentation />
          ) : (
            !error &&
            (view === "studio" ? (
              <PipelineStudio />
            ) : view === "overview" ? (
              <Overview />
            ) : view === "live" ? (
              <LiveTail />
            ) : view === "settings" ? (
              <Settings />
            ) : view === "mapping" ? (
              <MappingPage />
            ) : (
              <AdapterList
                key={view}
                stage={
                  view === "inputs"
                    ? "input"
                    : view === "parsers"
                      ? "parser"
                      : view === "transforms"
                        ? "transform"
                        : "output"
                }
              />
            ))
          )}
        </Suspense>
      )}
    </AppShell>
  );
}
export default function App() {
  return (
    <TooltipProvider>
      <WorkspaceProvider>
        <Pages />
        <Toaster theme="dark" richColors />
      </WorkspaceProvider>
    </TooltipProvider>
  );
}
