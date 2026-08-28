import { ArrowRight, Cable, GitBranch, Radio, RefreshCw } from "lucide-react";
import { useWorkspace } from "@/lib/workspace";
import { useResource } from "@/hooks/use-resource";
import { PageHeading, ErrorNotice, EmptyState } from "@/components/shared";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Table,
  TableHead,
  TableHeader,
  TableBody,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { PipelineFlow } from "@/components/pipeline-flow";

export function Overview() {
  const { data, status, messageTypes, navigate, refresh } = useWorkspace(),
    metrics = useResource<any[]>("/pipeline/output-metrics", 10000),
    threads = useResource<any[]>("/pipeline/threads", 10000);
  const cards = [
    {
      label: "Pipelines",
      value: messageTypes.length,
      note: "Message type routes",
      Icon: GitBranch,
    },
    {
      label: "Inputs enabled",
      value: data.input.filter((a) => a.enabled).length,
      note: `${data.input.length} configured`,
      Icon: Radio,
    },
    {
      label: "Outputs enabled",
      value: data.output.filter((a) => a.enabled).length,
      note: `${data.output.length} configured`,
      Icon: Cable,
    },
  ];
  return (
    <>
      <PageHeading
        title="Overview"
        description="Configuration and runtime activity, in one place."
      >
        <Button
          variant="outline"
          onClick={() => {
            void refresh();
            metrics.refresh();
            threads.refresh();
          }}
        >
          <RefreshCw data-icon="inline-start" />
          Refresh
        </Button>
      </PageHeading>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map(({ label, value, note, Icon }) => (
          <Card key={label}>
            <CardHeader>
              <CardDescription className="flex justify-between">
                {label}
                <Icon className="size-4" />
              </CardDescription>
              <CardTitle className="text-3xl tabular-nums">{value}</CardTitle>
            </CardHeader>
            <CardContent className="text-xs text-muted-foreground">
              {note}
            </CardContent>
          </Card>
        ))}
        <Card>
          <CardHeader>
            <CardDescription>Event queue</CardDescription>
            <CardTitle className="text-3xl tabular-nums">
              {status ? status.queueSize.toLocaleString() : "—"}
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                / {status?.queueCapacity?.toLocaleString() || "—"}
              </span>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <Progress
              aria-label="Event queue usage"
              value={
                status?.queueCapacity
                  ? (status.queueSize / status.queueCapacity) * 100
                  : 0
              }
            />
            <p className="mt-2 text-xs text-muted-foreground">
              {status
                ? `${Number(status.throughput).toLocaleString()} events/s · runtime snapshot`
                : "Runtime unavailable"}
            </p>
          </CardContent>
        </Card>
      </div>
      <Tabs defaultValue="pipelines">
        <TabsList>
          <TabsTrigger value="pipelines">Pipelines</TabsTrigger>
          <TabsTrigger value="delivery">Output delivery</TabsTrigger>
          <TabsTrigger value="threads">Worker threads</TabsTrigger>
        </TabsList>
        <TabsContent value="pipelines" className="pt-4">
          <Card>
            <CardHeader>
              <CardTitle>Pipeline routes</CardTitle>
              <CardDescription>
                Inputs → ordered processing steps → schema mapping → outputs
              </CardDescription>
            </CardHeader>
            <CardContent className="divide-y border-t px-0">
              {messageTypes.length ? (
                messageTypes.map((type) => (
                  <a
                    key={type}
                    href={`#studio?type=${encodeURIComponent(type)}`}
                    className="group flex w-full flex-wrap items-center gap-4 px-5 py-5 text-left transition-colors hover:bg-accent"
                    onClick={(event) => {
                      event.preventDefault();
                      navigate("studio", type);
                    }}
                  >
                    <div className="w-full shrink-0 xl:w-56">
                      <span className="font-medium">{type}</span>
                      <span className="mt-1 block text-xs text-muted-foreground">
                        {data.parser.filter((a) => a.messagetype === type)
                          .length +
                          data.transform.filter((a) => a.messagetype === type)
                            .length}{" "}
                        processing steps
                      </span>
                    </div>
                    <div className="min-w-0 flex-1">
                      <PipelineFlow data={data} messageType={type} />
                    </div>
                    <ArrowRight className="size-4 text-muted-foreground group-hover:text-primary" />
                  </a>
                ))
              ) : (
                <EmptyState
                  title="Create your first pipeline"
                  description="Start with an input and connect processing steps and a destination."
                >
                  <Button onClick={() => navigate("studio")}>
                    Open Pipeline Studio
                  </Button>
                </EmptyState>
              )}
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="delivery" className="pt-4">
          {metrics.error ? (
            <ErrorNotice error={metrics.error} retry={metrics.refresh} />
          ) : metrics.loading ? (
            <Skeleton className="h-48" />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle>Output delivery</CardTitle>
                <CardDescription>
                  Actual counters from currently running output adapters.
                  Updates every 10 seconds.
                </CardDescription>
              </CardHeader>
              <CardContent>
                {metrics.data?.length ? (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        {[
                          "Output",
                          "Message type",
                          "Sent",
                          "Failed",
                          "Avg. latency",
                          "Last error",
                        ].map((label) => (
                          <TableHead key={label}>{label}</TableHead>
                        ))}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {metrics.data.map((row) => (
                        <TableRow key={row.adapterId}>
                          <TableCell>{row.adapterName}</TableCell>
                          <TableCell>{row.messageType}</TableCell>
                          <TableCell>{row.sentCount}</TableCell>
                          <TableCell>{row.failedCount}</TableCell>
                          <TableCell>
                            {row.averageLatencyMs == null
                              ? "—"
                              : `${row.averageLatencyMs.toFixed(1)} ms`}
                          </TableCell>
                          <TableCell className="max-w-64 whitespace-normal break-words text-destructive">
                            {row.lastError || "—"}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                ) : (
                  <EmptyState
                    title="No running outputs"
                    description="Enable an output to see its delivery counters here."
                  />
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>
        <TabsContent value="threads" className="pt-4">
          {threads.error ? (
            <ErrorNotice error={threads.error} retry={threads.refresh} />
          ) : threads.loading ? (
            <Skeleton className="h-48" />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle>Worker threads</CardTitle>
                <CardDescription>
                  Observed thread state. Updates every 10 seconds.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      {["Name", "Component", "State", "Alive"].map((label) => (
                        <TableHead key={label}>{label}</TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {threads.data?.map((row) => (
                      <TableRow key={row.threadId}>
                        <TableCell className="font-mono text-xs">
                          {row.name}
                        </TableCell>
                        <TableCell>
                          {row.componentName || row.componentType}
                        </TableCell>
                        <TableCell>
                          <Badge variant="outline">{row.state}</Badge>
                        </TableCell>
                        <TableCell>{row.alive ? "Yes" : "No"}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </>
  );
}
