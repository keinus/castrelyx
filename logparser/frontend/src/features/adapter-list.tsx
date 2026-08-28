import { useState } from "react";
import { ArrowLeft, ChevronRight, Plus, Search, Workflow } from "lucide-react";
import { AdapterEditor } from "@/components/adapters/adapter-form";
import { EmptyState, PageHeading, SelectInput } from "@/components/shared";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import {
  InputGroup,
  InputGroupInput,
  InputGroupAddon,
} from "@/components/ui/input-group";
import {
  Table,
  TableHeader,
  TableHead,
  TableBody,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import {
  definition,
  duplicateAdapter,
  newAdapter,
  summary,
} from "@/lib/adapters";
import { TYPE_DEFS } from "@/lib/adapter-definitions";
import { useWorkspace } from "@/lib/workspace";
import { stageNames, type Adapter, type Stage } from "@/lib/types";

export function AdapterList({ stage }: { stage: Stage }) {
  const { data, messageType, messageTypes, navigate, guard } = useWorkspace();
  const [selected, setSelected] = useState<Adapter | null>(null),
    [search, setSearch] = useState(""),
    [filter, setFilter] = useState("__all"),
    [page, setPage] = useState(0);
  const items = data[stage].filter(
    (a) =>
      (filter === "__all" || a.messagetype === filter) &&
      `${a.type} ${a.messagetype} ${summary(a)}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const name = stageNames[stage],
    size = 15,
    visiblePage = Math.min(
      page,
      Math.max(0, Math.ceil(items.length / size) - 1),
    );
  function choose(adapter: Adapter | null) {
    if (guard()) setSelected(adapter);
  }
  return (
    <>
      <PageHeading
        title={`${name}s`}
        description={
          stage === "input"
            ? "Manage where events enter your pipelines."
            : stage === "output"
              ? "Manage destinations, delivery, and storage."
              : `Manage reusable ${stage} steps across your pipelines.`
        }
      >
        <Button
          onClick={() =>
            choose(newAdapter(stage, TYPE_DEFS[stage][0].type, messageType))
          }
        >
          <Plus data-icon="inline-start" />
          Add {stage}
        </Button>
      </PageHeading>
      {selected ? (
        <>
          <div className="flex flex-wrap justify-between gap-2 rounded-lg border bg-card p-2">
            <Button variant="ghost" onClick={() => choose(null)}>
              <ArrowLeft data-icon="inline-start" />
              All {name.toLowerCase()}s
            </Button>
            <Button
              variant="outline"
              onClick={() =>
                navigate(
                  "studio",
                  stage === "output" &&
                    (!selected.messagetype || selected.messagetype === "all")
                    ? messageType
                    : selected.messagetype,
                )
              }
            >
              <Workflow data-icon="inline-start" />
              Open pipeline
            </Button>
          </div>
          <div className="w-full min-w-0">
            <AdapterEditor
              key={`${stage}:${selected.id || selected.type}`}
              stage={stage}
              adapter={selected}
              showContext
              onSaved={() => setSelected(null)}
              onDuplicate={(draft) => choose(duplicateAdapter(draft))}
            />
          </div>
        </>
      ) : (
        <>
          <div className="flex flex-wrap gap-3">
            <InputGroup className="max-w-sm">
              <InputGroupAddon>
                <Search />
              </InputGroupAddon>
              <InputGroupInput
                aria-label={`Search ${name.toLowerCase()}s`}
                placeholder={`Search ${name.toLowerCase()}s…`}
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setPage(0);
                }}
              />
            </InputGroup>
            <div className="w-56">
              <SelectInput
                label="Filter message type"
                value={filter}
                onChange={(v) => {
                  setFilter(v);
                  setPage(0);
                }}
                options={[
                  { value: "__all", label: "All message types" },
                  ...messageTypes,
                ]}
              />
            </div>
            <span className="ml-auto self-center text-sm text-muted-foreground">
              {items.length} component{items.length === 1 ? "" : "s"} ·{" "}
              {items.filter((a) => a.enabled).length} enabled
            </span>
          </div>
          {items.length ? (
            <Card>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Component</TableHead>
                      <TableHead>Message type</TableHead>
                      <TableHead>
                        {["parser", "transform"].includes(stage)
                          ? "Order"
                          : "Connection"}
                      </TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>
                        <span className="sr-only">Edit</span>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {items
                      .slice(visiblePage * size, (visiblePage + 1) * size)
                      .map((adapter) => (
                        <TableRow key={adapter.id}>
                          <TableCell>
                            <button
                              className="text-left font-medium hover:text-primary hover:underline"
                              onClick={() => choose(adapter)}
                            >
                              {definition(stage, adapter.type)?.label ||
                                adapter.type}
                              <span className="mt-1 block font-mono text-xs font-normal text-muted-foreground">
                                #{adapter.id}
                              </span>
                            </button>
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline">
                              {adapter.messagetype}
                            </Badge>
                          </TableCell>
                          <TableCell className="max-w-64 truncate font-mono text-xs">
                            {["parser", "transform"].includes(stage)
                              ? (adapter.priority ?? 0)
                              : summary(adapter)}
                          </TableCell>
                          <TableCell>
                            <Badge
                              variant={
                                adapter.enabled ? "secondary" : "outline"
                              }
                            >
                              {adapter.enabled ? "Enabled" : "Disabled"}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <Button
                              size="icon"
                              variant="ghost"
                              aria-label={`Edit ${adapter.type} ${adapter.id}`}
                              onClick={() => choose(adapter)}
                            >
                              <ChevronRight />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                  </TableBody>
                </Table>
                <div className="mt-4 flex items-center justify-between border-t pt-4 text-xs text-muted-foreground">
                  <span>
                    {visiblePage * size + 1}–
                    {Math.min((visiblePage + 1) * size, items.length)} of{" "}
                    {items.length}
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={!visiblePage}
                      onClick={() => setPage(visiblePage - 1)}
                    >
                      Previous
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={(visiblePage + 1) * size >= items.length}
                      onClick={() => setPage(visiblePage + 1)}
                    >
                      Next
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>
          ) : (
            <EmptyState
              title={`No ${name.toLowerCase()}s found`}
              description="Add a component or adjust the current filters."
            >
              <Button
                onClick={() =>
                  choose(
                    newAdapter(stage, TYPE_DEFS[stage][0].type, messageType),
                  )
                }
              >
                <Plus data-icon="inline-start" />
                Add {stage}
              </Button>
            </EmptyState>
          )}
        </>
      )}
    </>
  );
}
