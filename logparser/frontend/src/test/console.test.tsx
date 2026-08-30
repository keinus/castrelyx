import { beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "@/app";
import { WorkspaceProvider } from "@/lib/workspace";
import { AdapterEditor } from "@/components/adapters/adapter-form";
import { LiveTail } from "@/features/live-tail";
import { MappingEditor } from "@/features/mapping-editor";
import { Overview } from "@/features/overview";
import { PipelineStudio } from "@/features/pipeline-studio";
import { CodePreview } from "@/components/code-panel";
import { newAdapter } from "@/lib/adapters";
import type { Adapter } from "@/lib/types";

const input: Adapter = {
  id: 1,
  type: "TcpInputAdapter",
  messagetype: "agent",
  port: 6514,
  enabled: true,
};
const output: Adapter = {
  id: 2,
  type: "ClickHouseOutputAdapter",
  messagetype: "agent",
  enabled: false,
  configParams:
    '{"endpointUrl":"http://localhost:8123","custom":{"flag":false,"count":0}}',
};
let fetchMock: Mock<(url: string, init?: RequestInit) => Promise<Response>>;
beforeEach(() => {
  history.replaceState({}, "", "/#studio?type=agent");
  fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const path = url.split("?")[0].replace("/api/v1", "");
    let data: any = {};
    if (path === "/input-adapters") data = { content: [input], last: true };
    if (path === "/parsers")
      data = {
        content: [
          {
            id: 1,
            type: "JsonParser",
            messagetype: "agent",
            enabled: true,
            priority: 10,
          },
        ],
        last: true,
      };
    if (path === "/transforms")
      data = {
        content: [
          {
            id: 3,
            type: "Filter",
            messagetype: "agent",
            enabled: true,
            priority: 20,
            filterDrop: '{"level":"DEBUG"}',
          },
        ],
        last: true,
      };
    if (path === "/output-adapters") data = { content: [output], last: true };
    if (path === "/pipeline/status")
      data = {
        status: "RUNNING",
        queueSize: 0,
        queueCapacity: 100,
        throughput: 0,
      };
    if (path === "/structure/mapping/agent")
      data = { messageType: "agent", commonMappings: [], subTableRules: [] };
    if (path === "/structure/schema")
      data = {
        commonSchema: [{ name: "src_ip", type: "String" }],
        subSchemas: { event_web: [{ name: "url", type: "String" }] },
      };
    if (
      path === "/structure/templates" ||
      path === "/pipeline/threads" ||
      path === "/pipeline/output-metrics"
    )
      data = [];
    if (path === "/parsers/test")
      data = JSON.parse(JSON.parse(init.body as string).sampleData);
    if (path === "/structure/simulate")
      data = { success: true, result: { common: { srcIp: "192.0.2.10" } } };
    if (path === "/pipeline/livetail/status") data = { enabled: false };
    if (path === "/settings")
      data = {
        parser_threads: 4,
        flush_interval: 5000,
        "castrelyx.seed.version": "1",
      };
    if (init.method === "PUT" && /adapters\//.test(path))
      data = { ...JSON.parse(init.body as string), id: 2 };
    return new Response(JSON.stringify(data), { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

describe("unified console", () => {
  it("edits and saves the connection fields of an adapter stored with an alias", async () => {
    const user = userEvent.setup();
    render(
      <WorkspaceProvider>
        <AdapterEditor
          stage="input"
          adapter={{ ...input, type: "tcp" }}
          onSaved={() => {}}
        />
      </WorkspaceProvider>,
    );
    const port = await screen.findByLabelText(/Listen port/);
    await user.clear(port);
    await user.type(port, "6515");
    await user.click(screen.getByRole("button", { name: "Save input" }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(
        ([url, init]) =>
          url === "/api/v1/input-adapters/1" && init?.method === "PUT",
      );
      expect(call).toBeDefined();
      expect(JSON.parse(call![1]!.body as string)).toMatchObject({
        type: "TcpInputAdapter",
        port: 6515,
      });
    });
  });

  it("discards unsaved mapping previews but retains the latest saved mapping when switching Studio stages", async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const fallback = fetchMock.getMockImplementation()!;
    let stored = {
      messageType: "agent",
      commonMappings: [],
      subTableRules: [],
    };
    fetchMock.mockImplementation(async (url, init = {}) => {
      const path = url.split("?")[0].replace("/api/v1", "");
      let data: any;
      if (path === "/parsers" || path === "/transforms")
        data = { content: [], last: true };
      else if (path === "/structure/schema")
        data = {
          commonSchema: [{ name: "src_host", type: "String" }],
          subSchemas: {},
        };
      else if (path === "/structure/mapping/agent") data = stored;
      else if (path === "/structure/mapping" && init.method === "POST") {
        stored = JSON.parse(init.body as string);
        data = stored;
      } else return fallback(url, init);
      return new Response(JSON.stringify(data), { status: 200 });
    });
    render(
      <WorkspaceProvider>
        <PipelineStudio />
      </WorkspaceProvider>,
    );
    await user.click(
      await screen.findByRole("button", { name: /Field mapping/ }),
    );
    await user.click(await screen.findByRole("button", { name: "Auto-map" }));
    await user.click(
      screen.getByRole("button", { name: "Test selected step" }),
    );
    await screen.findByText("Completed");

    await user.click(
      screen.getByRole("button", { name: "ClickHouse Disabled" }),
    );
    expect(confirm).toHaveBeenCalledWith("Discard unsaved changes?");
    expect(
      screen.getByRole("button", { name: /Field mapping/ }),
    ).toHaveTextContent("0 common attributes");
    expect(
      screen.getByRole("button", { name: "Test selected step" }),
    ).toBeDisabled();
    expect(stored.commonMappings).toEqual([]);

    await user.click(screen.getByRole("button", { name: /Field mapping/ }));
    await user.click(await screen.findByRole("button", { name: "Auto-map" }));
    await user.click(screen.getByRole("button", { name: "Save mapping" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save mapping" }),
      ).toBeDisabled(),
    );
    await user.click(
      screen.getByRole("button", { name: "ClickHouse Disabled" }),
    );
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("button", { name: /Field mapping/ }),
    ).toHaveTextContent("1 common attributes");

    await user.click(screen.getByRole("button", { name: /Field mapping/ }));
    const source = await screen.findByRole("combobox", {
      name: "Source field",
    });
    await user.clear(source);
    await user.type(source, "message");
    await user.click(
      screen.getByRole("button", { name: "ClickHouse Disabled" }),
    );
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(
      screen.getByRole("button", { name: /Field mapping/ }),
    ).toHaveTextContent("1 common attributes");
    await user.click(screen.getByRole("button", { name: /Field mapping/ }));
    expect(
      await screen.findByRole("combobox", { name: "Source field" }),
    ).toHaveValue("host");
  });

  it("adds individual attributes to a sub-table rule from its compact toolbar", async () => {
    const user = userEvent.setup();
    render(
      <WorkspaceProvider>
        <MappingEditor />
      </WorkspaceProvider>,
    );
    await user.click(await screen.findByRole("button", { name: "Add rule" }));
    await user.click(
      screen.getByRole("button", { name: "Add mapping to rule 1" }),
    );
    expect(screen.getByRole("combobox", { name: "Source field" })).toHaveValue(
      "",
    );
    await user.type(
      screen.getByRole("combobox", { name: "Source field" }),
      "request_url",
    );
    await user.click(screen.getByRole("button", { name: "Remove mapping 1" }));
    expect(
      screen.queryByRole("combobox", { name: "Source field" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("combobox", { name: "Rule 1 table" }),
    ).toBeInTheDocument();
  });
  it("shows the complete route in execution order and opens its Studio", async () => {
    const user = userEvent.setup();
    render(
      <WorkspaceProvider>
        <Overview />
      </WorkspaceProvider>,
    );
    const route = await screen.findByRole("list", { name: "agent route" });
    expect(
      within(route)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([
      "TCP",
      "JSON parser",
      "Filter events",
      "Schema mapping",
      "ClickHouse",
    ]);
    expect(within(route).getByLabelText("Disabled")).toBeInTheDocument();
    await user.click(route.closest("a")!);
    expect(location.hash).toContain("studio");
    expect(location.hash).toContain("agent");
  });

  it("renders hostile JSON as text with readable syntax and an empty state", () => {
    const view = render(
      <CodePreview
        title="Payload"
        value={{
          message: '<img src=x onerror="alert(1)">',
          count: 0,
          enabled: false,
          extra: null,
        }}
      />,
    );
    const region = screen.getByRole("region", { name: "Payload content" });
    expect(region.querySelector("img")).toBeNull();
    expect(region).toHaveTextContent("<img src=x");
    expect(region.querySelector(".code-number")).toHaveTextContent("0");
    expect(region.querySelector(".code-literal")).toHaveTextContent("false");
    view.rerender(<CodePreview title="Payload" empty="Run first" />);
    expect(screen.getByText("Run first")).toBeInTheDocument();
  });
  it("keeps one sidebar across Studio, inventory, mapping, settings and live tail", async () => {
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("heading", { name: "Pipeline Studio" });
    for (const name of [
      "Outputs",
      "Schema Mapping",
      "Settings",
      "Live Tail",
      "Pipeline Studio",
    ]) {
      await user.click(screen.getByRole("link", { name }));
      await screen.findByRole("heading", { name });
      expect(
        screen.getAllByRole("link", { name: "Pipeline Studio" }),
      ).toHaveLength(1);
      expect(screen.getAllByRole("main")).toHaveLength(1);
      expect(location.hash).toContain("type=agent");
    }
  });
  it("uses the same attribute editor from the output inventory and preserves nested configuration on save", async () => {
    const user = userEvent.setup();
    history.replaceState({}, "", "/#outputs?type=agent");
    render(<App />);
    await user.click(
      await screen.findByRole("button", {
        name: "Edit ClickHouseOutputAdapter 2",
      }),
    );
    const endpoint = await screen.findByLabelText(/Endpoint URL/);
    await user.clear(endpoint);
    await user.type(endpoint, "http://localhost:8124");
    expect(
      screen.queryByRole("textbox", { name: /config.?params/i }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Buffering" }));
    await user.clear(screen.getByLabelText("Batch size"));
    await user.type(screen.getByLabelText("Batch size"), "250");
    await user.click(screen.getByRole("button", { name: "Save output" }));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) =>
            url === "/api/v1/output-adapters/2" && init?.method === "PUT",
        ),
      ).toBe(true),
    );
    const call = fetchMock.mock.calls.find(
      ([url, init]) =>
        url === "/api/v1/output-adapters/2" && init?.method === "PUT",
    )!;
    const payload = JSON.parse(call[1]?.body as string);
    expect(typeof payload.configParams).toBe("string");
    expect(JSON.parse(payload.configParams)).toMatchObject({
      endpointUrl: "http://localhost:8124",
      batchSize: 250,
      custom: { flag: false, count: 0 },
    });
  });
  it("blocks navigation when unsaved changes are retained", async () => {
    const user = userEvent.setup(),
      confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<App />);
    const port = await screen.findByRole("spinbutton", { name: "Listen port" });
    await user.clear(port);
    await user.type(port, "7514");
    await user.click(screen.getByRole("link", { name: "Outputs" }));
    expect(confirm).toHaveBeenCalledWith("Discard unsaved changes?");
    expect(
      screen.getByRole("heading", { name: "Pipeline Studio" }),
    ).toBeInTheDocument();
    expect(port).toHaveValue(7514);
  });
  it("uses the sample for the first processing step and preserves its result for the next step", async () => {
    const user = userEvent.setup();
    render(<App />);
    const sample = await screen.findByLabelText("Original sample event");
    expect(sample).toHaveValue(
      '<34>1 2026-08-27T12:00:00.000Z mymachine.example.com su 1234 ID47 [exampleSDID@32473 iut="3" eventSource="Application"] \'su root\' failed for lonvick on /dev/pts/8',
    );
    fireEvent.change(sample, {
      target: {
        value:
          '{"host":"web-01","level":"INFO","message":"User login successful"}',
      },
    });
    await user.click(
      await screen.findByRole("button", { name: /Filter events.*Order/ }),
    );
    expect(
      screen.getByRole("button", { name: "Test selected step" }),
    ).toBeDisabled();
    expect(screen.getByText(/Test JSON parser first/)).toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: /JSON parser.*Order/ }),
    );
    expect(
      screen.getByRole("button", { name: "Test selected step" }),
    ).toBeEnabled();
    await user.click(
      screen.getByRole("button", { name: "Test selected step" }),
    );
    await screen.findByText("Parsed by the server using the current draft.");
    await user.click(
      screen.getByRole("button", { name: /Filter events.*Order/ }),
    );
    expect(
      screen.getByRole("button", { name: "Test selected step" }),
    ).toBeEnabled();
    await user.click(
      screen.getByRole("button", { name: "Test selected step" }),
    );
    await screen.findByText("Event passed all filter rules.");
    await user.clear(sample);
    expect(
      screen.getByRole("button", { name: "Test selected step" }),
    ).toBeDisabled();
  });
  it("shows local validation issues without claiming disabled routes are valid", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Validate" }));
    expect(
      screen.getByText("No enabled output is saved for this message type."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Local field checks passed"),
    ).not.toBeInTheDocument();
  });
});

describe("structured attribute forms", () => {
  it("renders SNMP target credentials and OIDs as individually labelled inputs", async () => {
    const user = userEvent.setup();
    render(
      <WorkspaceProvider>
        <AdapterEditor
          stage="input"
          adapter={newAdapter("input", "SnmpInputAdapter", "agent")}
          onSaved={vi.fn()}
        />
      </WorkspaceProvider>,
    );
    await user.click(screen.getByRole("tab", { name: "Targets" }));
    expect(screen.getByLabelText("Host")).toHaveValue("192.0.2.10");
    expect(screen.getByLabelText("OID identifier")).toHaveValue(
      "1.3.6.1.2.1.1.3.0",
    );
    await user.click(screen.getByText("SNMPv3 authentication & privacy"));
    expect(screen.getByLabelText("Authentication passphrase")).toHaveAttribute(
      "type",
      "password",
    );
    expect(screen.getByLabelText("Privacy passphrase env")).toBeInTheDocument();
    expect(
      screen.queryByRole("textbox", { name: "Targets" }),
    ).not.toBeInTheDocument();
  });
  it("keeps invalid entry drafts across tabs and refuses to save duplicate header names", async () => {
    const user = userEvent.setup(),
      onSaved = vi.fn();
    render(
      <WorkspaceProvider>
        <AdapterEditor
          stage="output"
          adapter={{
            ...newAdapter("output", "HttpOutputAdapter", "agent"),
            url: "http://localhost/events",
            headers: '{"X-Test":"one"}',
          }}
          onSaved={onSaved}
        />
      </WorkspaceProvider>,
    );
    await user.click(screen.getByRole("tab", { name: "Headers" }));
    await user.click(screen.getByRole("button", { name: "Add entry" }));
    await user.type(screen.getByLabelText("Field name 2"), "X-Test");
    await user.type(screen.getByLabelText("Value 2"), "two");
    await user.click(screen.getByRole("tab", { name: "Destination" }));
    await user.click(screen.getByRole("tab", { name: "Headers" }));
    expect(screen.getByLabelText("Field name 2")).toHaveValue("X-Test");
    await user.click(screen.getByRole("button", { name: "Save output" }));
    expect(onSaved).not.toHaveBeenCalled();
    expect(
      screen.getAllByText("Each field name must be unique.").length,
    ).toBeGreaterThan(0);
  });
  it("focuses invalid scalar fields and does not send invalid input to the server", async () => {
    const user = userEvent.setup();
    render(
      <WorkspaceProvider>
        <AdapterEditor
          stage="input"
          adapter={newAdapter("input", "TcpInputAdapter", "agent")}
          onSaved={vi.fn()}
        />
      </WorkspaceProvider>,
    );
    await user.type(
      screen.getByRole("spinbutton", { name: "Listen port" }),
      "70000",
    );
    await user.click(screen.getByRole("button", { name: "Save input" }));
    await waitFor(() =>
      expect(
        screen.getByRole("spinbutton", { name: "Listen port" }),
      ).toHaveFocus(),
    );
    expect(
      screen.getByRole("spinbutton", { name: "Listen port" }),
    ).toHaveAttribute("aria-invalid", "true");
    expect(
      fetchMock.mock.calls.some(([, init]) => init?.method === "POST"),
    ).toBe(false);
  });
});

describe("mapping persistence", () => {
  it("preserves ordered fallback mappings to the same target when saving a template", async () => {
    const user = userEvent.setup();
    const originalFetch = fetchMock.getMockImplementation()!;
    const mapping = {
      messageType: "agent",
      commonMappings: [
        {
          sourceField: "observed_at",
          targetField: "event_time",
          defaultValue: null,
        },
        {
          sourceField: "payload_observed_at",
          targetField: "event_time",
          defaultValue: null,
        },
      ],
      subTableRules: [],
    };
    fetchMock.mockImplementation(
      async (url: string, init: RequestInit = {}) => {
        if (url.endsWith("/structure/mapping/agent"))
          return new Response(JSON.stringify(mapping));
        if (url.endsWith("/structure/templates") && init.method === "POST")
          return new Response(
            JSON.stringify({
              ...JSON.parse(init.body as string),
              id: "saved-template",
            }),
          );
        return originalFetch(url, init);
      },
    );
    render(
      <WorkspaceProvider>
        <MappingEditor />
      </WorkspaceProvider>,
    );
    await user.click(
      await screen.findByRole("button", { name: "Save as template" }),
    );
    await user.type(
      screen.getByRole("textbox", { name: "Template name" }),
      "Agent fallback",
    );
    await user.click(screen.getByRole("button", { name: "Save template" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    const saved = fetchMock.mock.calls.find(
      ([url, init]) =>
        url.endsWith("/structure/templates") && init?.method === "POST",
    );
    expect(JSON.parse(saved![1]?.body as string).config.commonMappings).toEqual(
      mapping.commonMappings,
    );
  });
  it("does not offer a blank editable mapping when the saved mapping fails to load", async () => {
    const originalFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (url: string, init: RequestInit = {}) =>
      url.endsWith("/structure/mapping/agent")
        ? new Response(
            JSON.stringify({ message: "Mapping storage unavailable" }),
            { status: 503 },
          )
        : originalFetch(url, init),
    );
    render(
      <WorkspaceProvider>
        <MappingEditor />
      </WorkspaceProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Mapping storage unavailable",
    );
    expect(
      screen.queryByRole("button", { name: "Save mapping" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Try again" }),
    ).toBeInTheDocument();
  });
});

describe("Live Tail connection lifecycle", () => {
  it("ignores invalid frames and paused events, reconnects, and closes only this viewer on exit", async () => {
    const user = userEvent.setup();
    const originalFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (url: string, init: RequestInit = {}) =>
      url.includes("/livetail/status")
        ? new Response(JSON.stringify({ enabled: true }))
        : originalFetch(url, init),
    );
    const sockets: MockSocket[] = [];
    class MockSocket {
      onopen?: () => void;
      onmessage?: (e: { data: string }) => void;
      onclose?: () => void;
      onerror?: () => void;
      close = vi.fn();
      constructor(public url: string) {
        sockets.push(this);
      }
    }
    vi.stubGlobal("WebSocket", MockSocket);
    const view = render(
      <WorkspaceProvider>
        <LiveTail />
      </WorkspaceProvider>,
    );
    await waitFor(() => expect(sockets).toHaveLength(1));
    const first = sockets[0];
    const emit = (socket: MockSocket, message: string) =>
      socket.onmessage?.({
        data: JSON.stringify({
          timestamp: 1000,
          messageType: "agent",
          data: { message },
        }),
      });
    act(() => {
      first.onopen?.();
      first.onmessage?.({ data: "not-json" });
      first.onmessage?.({ data: JSON.stringify({ messageType: "agent" }) });
      emit(first, "first event");
    });
    expect(screen.getByText("Connected")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /first event/ }),
    ).toBeInTheDocument();
    const eventToggle = screen.getByRole("button", { name: /first event/ });
    await user.click(eventToggle);
    expect(eventToggle).toHaveAttribute("aria-expanded", "true");
    const details = screen.getByRole("region", {
      name: "Event details content",
    });
    expect(details.closest("td")).toHaveAttribute("colspan", "3");
    expect(details).toHaveTextContent("first event");
    await user.click(eventToggle);
    expect(
      screen.queryByRole("region", { name: "Event details content" }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Pause" }));
    act(() => emit(first, "ignored while paused"));
    expect(
      screen.queryByRole("button", { name: /ignored while paused/ }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Resume" }));
    await user.click(screen.getByRole("button", { name: "Reconnect" }));
    await waitFor(() => expect(sockets).toHaveLength(2));
    expect(first.close).toHaveBeenCalledOnce();
    act(() => {
      first.onclose?.();
      emit(first, "stale connection");
      sockets[1].onopen?.();
      emit(sockets[1], "new connection");
    });
    expect(
      screen.queryByRole("button", { name: /stale connection/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /new connection/ }),
    ).toBeInTheDocument();
    view.unmount();
    expect(sockets[1].close).toHaveBeenCalledOnce();
    expect(
      fetchMock.mock.calls.some(([url]) => url.includes("/livetail/disable")),
    ).toBe(false);
  });
});
