import { Fragment, useEffect, useRef, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Download,
  Pause,
  Play,
  RefreshCw,
  Search,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";
import { useResource } from "@/hooks/use-resource";
import { api } from "@/lib/api";
import { useWorkspace } from "@/lib/workspace";
import {
  PageHeading,
  ConfirmAction,
  EmptyState,
  ErrorNotice,
  SelectInput,
} from "@/components/shared";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { CodePreview } from "@/components/code-panel";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
type Event = { timestamp: number; messageType: string; data: any; key: number };
export function LiveTail() {
  const { messageTypes } = useWorkspace(),
    status = useResource<{ enabled: boolean }>(
      "/pipeline/livetail/status",
      5000,
    );
  const [events, setEvents] = useState<Event[]>([]),
    [paused, setPaused] = useState(false),
    pausedRef = useRef(false),
    [search, setSearch] = useState(""),
    [filter, setFilter] = useState("__all"),
    [connection, setConnection] = useState("Disconnected"),
    [revision, setRevision] = useState(0),
    [selected, setSelected] = useState<number | null>(null),
    sequence = useRef(0);
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);
  useEffect(() => {
    if (!status.data?.enabled) {
      setConnection("Disconnected");
      return;
    }
    let stopped = false,
      timer: ReturnType<typeof setTimeout>,
      socket: WebSocket,
      attempt = 0;
    const reconnect = () => {
      if (stopped) return;
      setConnection("Reconnecting");
      timer = setTimeout(connect, Math.min(1000 * 2 ** attempt++, 15000));
    };
    const connect = () => {
      if (stopped) return;
      setConnection("Connecting");
      try {
        socket = new WebSocket(
          `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/tail`,
        );
      } catch {
        reconnect();
        return;
      }
      socket.onopen = () => {
        if (!stopped) {
          attempt = 0;
          setConnection("Connected");
        }
      };
      socket.onmessage = (e) => {
        if (stopped || pausedRef.current) return;
        try {
          const event = JSON.parse(e.data);
          if (
            typeof event.messageType !== "string" ||
            !Number.isFinite(event.timestamp) ||
            event.data === null ||
            typeof event.data !== "object"
          )
            return;
          setEvents((previous) =>
            [{ ...event, key: ++sequence.current }, ...previous].slice(0, 500),
          );
        } catch {
          /* Invalid frames are ignored; never render server data as HTML. */
        }
      };
      socket.onerror = () => {
        if (!stopped) setConnection("Connection error");
      };
      socket.onclose = reconnect;
    };
    connect();
    return () => {
      stopped = true;
      clearTimeout(timer);
      socket?.close();
    };
  }, [status.data?.enabled, revision]);
  const visible = events.filter(
    (e) =>
      (filter === "__all" || e.messageType === filter) &&
      JSON.stringify(e.data).toLowerCase().includes(search.toLowerCase()),
  );
  function download() {
    const blob = new Blob(
      [
        visible
          .map(({ key: _key, ...event }) => JSON.stringify(event))
          .join("\n"),
      ],
      { type: "application/x-ndjson" },
    );
    const url = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = url;
    link.download = "logparser-live-tail.jsonl";
    link.click();
    URL.revokeObjectURL(url);
  }
  return (
    <>
      <PageHeading
        title="Live Tail"
        description="Inspect dispatcher events as they arrive. The latest 500 events stay in this browser."
      >
        <ConfirmAction
          title={`${status.data?.enabled ? "Disable" : "Enable"} Live Tail capture?`}
          description="This changes the server-wide broadcast setting for every connected viewer. It does not change event processing."
          label={status.data?.enabled ? "Disable capture" : "Enable capture"}
          trigger={
            <Button
              disabled={status.loading || !!status.error}
              variant={status.data?.enabled ? "outline" : "default"}
            >
              {status.data?.enabled ? "Disable capture" : "Enable capture"}
            </Button>
          }
          action={async () => {
            await api(
              `/pipeline/livetail/${status.data?.enabled ? "disable" : "enable"}`,
              "POST",
            );
            status.refresh();
            toast.success("Live Tail setting updated");
          }}
        />
      </PageHeading>
      {status.error && (
        <ErrorNotice error={status.error} retry={status.refresh} />
      )}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border bg-card p-3">
        <InputGroup className="max-w-sm">
          <InputGroupAddon>
            <Search />
          </InputGroupAddon>
          <InputGroupInput
            aria-label="Filter event content"
            placeholder="Filter event content…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </InputGroup>
        <div className="w-48">
          <SelectInput
            label="Event message type"
            options={[
              { value: "__all", label: "All message types" },
              ...new Set([
                ...messageTypes,
                ...events.map((e) => e.messageType),
              ]),
            ]}
            value={filter}
            onChange={setFilter}
          />
        </div>
        <Badge variant="outline" className="gap-2">
          <span
            aria-hidden="true"
            className={`size-1.5 rounded-full ${connection === "Connected" ? "bg-success" : "bg-muted-foreground"}`}
          />
          {connection}
          {paused ? " · display paused" : ""}
        </Badge>
        <div className="ml-auto flex gap-2">
          <Button variant="outline" onClick={() => setPaused((v) => !v)}>
            {paused ? (
              <Play data-icon="inline-start" />
            ) : (
              <Pause data-icon="inline-start" />
            )}
            {paused ? "Resume" : "Pause"}
          </Button>
          <Button
            size="icon"
            variant="outline"
            aria-label="Reconnect"
            disabled={!status.data?.enabled}
            onClick={() => setRevision((v) => v + 1)}
          >
            <RefreshCw />
          </Button>
          <Button
            size="icon"
            variant="outline"
            aria-label="Export visible events"
            disabled={!visible.length}
            onClick={download}
          >
            <Download />
          </Button>
          <Button
            size="icon"
            variant="outline"
            aria-label="Clear events"
            disabled={!events.length}
            onClick={() => {
              setEvents([]);
              setSelected(null);
            }}
          >
            <Trash2 />
          </Button>
        </div>
      </div>
      <Card className="min-h-[420px] overflow-hidden py-0">
        <CardContent className="p-0">
          {visible.length ? (
            <>
              <div className="border-b px-4 py-3 text-xs text-muted-foreground">
                {visible.length} matching events ·{" "}
                {paused
                  ? "Incoming events are ignored while paused."
                  : "Newest first"}
              </div>
              <Table className="table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-28 pl-4">Time</TableHead>
                    <TableHead className="w-36 sm:w-52">Message type</TableHead>
                    <TableHead>Event</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {visible.map((event) => (
                    <Fragment key={event.key}>
                      <TableRow
                        data-state={
                          selected === event.key ? "selected" : undefined
                        }
                      >
                        <TableCell className="pl-4 align-top font-mono text-xs">
                          {new Date(event.timestamp).toLocaleTimeString(
                            "en-GB",
                            { hour12: false },
                          )}
                        </TableCell>
                        <TableCell className="align-top">
                          <Badge variant="outline" className="max-w-full">
                            <span
                              className="truncate"
                              title={event.messageType}
                            >
                              {event.messageType}
                            </span>
                          </Badge>
                        </TableCell>
                        <TableCell>
                          <button
                            className="flex w-full items-center gap-2 text-left font-mono text-xs"
                            aria-expanded={selected === event.key}
                            aria-controls={`event-detail-${event.key}`}
                            onClick={() =>
                              setSelected(
                                selected === event.key ? null : event.key,
                              )
                            }
                          >
                            {selected === event.key ? (
                              <ChevronDown
                                aria-hidden="true"
                                className="size-3.5 shrink-0"
                              />
                            ) : (
                              <ChevronRight
                                aria-hidden="true"
                                className="size-3.5 shrink-0"
                              />
                            )}
                            <span className="truncate">
                              {JSON.stringify(event.data)}
                            </span>
                          </button>
                        </TableCell>
                      </TableRow>
                      {selected === event.key && (
                        <TableRow className="hover:bg-transparent">
                          <TableCell
                            colSpan={3}
                            className="p-3 whitespace-normal"
                          >
                            <div id={`event-detail-${event.key}`}>
                              <CodePreview
                                title="Event details"
                                className="event-detail"
                                value={event.data}
                              />
                            </div>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  ))}
                </TableBody>
              </Table>
            </>
          ) : (
            <EmptyState
              title={
                events.length
                  ? "No matching events"
                  : status.data?.enabled
                    ? "Waiting for events"
                    : "Capture is disabled"
              }
              description={
                events.length
                  ? "Adjust the search or message type filter."
                  : "Enable capture and send a real event through an active input. No simulated events are shown."
              }
            />
          )}
        </CardContent>
      </Card>
    </>
  );
}
