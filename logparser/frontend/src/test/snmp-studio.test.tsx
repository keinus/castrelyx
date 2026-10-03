import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PipelineStudio } from "@/features/pipeline-studio";
import { WorkspaceProvider } from "@/lib/workspace";
import type { Adapter, MappingTemplate } from "@/lib/types";

let saved: Adapter[];
let writes: { method: string; payload: Adapter }[];
let templates: MappingTemplate[];
let failTemplate: boolean;

const mibPreview = {
  modules: ["EXAMPLE-MIB"],
  warnings: [],
  objects: [
    {
      module: "EXAMPLE-MIB",
      name: "temperature",
      oid: "1.3.6.1.4.1.32473.2",
      scalar: true,
      syntax: "INTEGER_32",
      access: "read-only",
      units: "Celsius",
      description: "Temperature",
      table: null,
      indexes: [],
    },
    {
      module: "EXAMPLE-MIB",
      name: "portBytes",
      oid: "1.3.6.1.4.1.32473.3.1.2",
      scalar: false,
      syntax: "COUNTER_64",
      access: "read-only",
      units: "bytes",
      description: "Received bytes",
      table: "portEntry",
      indexes: ["portIndex"],
    },
  ],
};

beforeEach(() => {
  history.replaceState({}, "", "/#studio?type=snmp");
  saved = [];
  writes = [];
  templates = [];
  failTemplate = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const path = url.split("?")[0].replace("/api/v1", "");
      let data: unknown = {};
      if (path.startsWith("/input-adapters")) {
        if (init.method === "POST" || init.method === "PUT") {
          const payload = JSON.parse(init.body as string);
          writes.push({ method: init.method, payload });
          const id =
            init.method === "POST"
              ? saved.length + 1
              : Number(path.split("/").at(-1));
          data = { ...payload, id };
          saved = [...saved.filter((item) => item.id !== id), data as Adapter];
        } else data = { content: saved, last: true };
      } else if (path === "/parsers") {
        data = {
          content: [
            {
              id: 10,
              type: "JsonParser",
              messagetype: "snmp",
              enabled: true,
              priority: 10,
            },
          ],
          last: true,
        };
      } else if (path === "/transforms" || path === "/output-adapters") {
        data = { content: [], last: true };
      } else if (path === "/structure/mapping/snmp") {
        data = { messageType: "snmp", commonMappings: [], subTableRules: [] };
      } else if (path === "/parsers/test") {
        data = JSON.parse(JSON.parse(init.body as string).sampleData);
      } else if (path === "/snmp/mib/preview") {
        data = mibPreview;
      } else if (path === "/structure/templates") {
        if (init.method === "POST") {
          if (failTemplate)
            return new Response(
              JSON.stringify({ message: "Template name already exists" }),
              { status: 400 },
            );
          data = { ...JSON.parse(init.body as string), id: "mib-template" };
          templates.push(data as MappingTemplate);
        } else data = templates;
      } else if (path === "/structure/schema") {
        data = { commonSchema: [], subSchemas: { event_snmp: [] } };
      }
      return new Response(JSON.stringify(data), { status: 200 });
    }),
  );
});

function renderStudio() {
  render(
    <WorkspaceProvider>
      <PipelineStudio />
    </WorkspaceProvider>,
  );
}

function samplePayload() {
  return JSON.parse(
    (screen.getByLabelText("Original sample event") as HTMLTextAreaElement)
      .value,
  );
}

async function chooseType(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
) {
  await user.click(await screen.findByRole("button", { name: "Add input" }));
  await user.click(screen.getByRole("combobox", { name: "Adapter type" }));
  await user.click(screen.getByRole("option", { name: label }));
}

async function reopen(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(
    await screen.findByRole("button", { name: /JSON parser Order/ }),
  );
  await user.click(screen.getByRole("button", { name: `${label} Disabled` }));
}

describe("SNMP in the Pipeline Studio workbench", () => {
  it(
    "imports a MIB, handles template failure without changing OIDs, and lists the saved template",
    { timeout: 30000 },
    async () => {
      const user = userEvent.setup();
      renderStudio();
      await chooseType(user, "SNMP poller");
      await user.click(screen.getByRole("tab", { name: "Targets" }));
      await user.click(screen.getByText("Import MIB files"));
      await user.upload(
        screen.getByLabelText("MIB files"),
        new File(["EXAMPLE-MIB DEFINITIONS ::= BEGIN END"], "example.mib", {
          type: "text/plain",
        }),
      );
      expect(
        await screen.findByText("2 readable OIDs · 1 selected"),
      ).toBeVisible();
      await user.click(
        screen.getByRole("checkbox", { name: "Select EXAMPLE-MIB::portBytes" }),
      );
      const add = screen.getByRole("button", { name: "Add OIDs and template" });
      expect(add).toBeDisabled();
      fireEvent.change(
        screen.getByLabelText("Instance suffix for EXAMPLE-MIB::portBytes"),
        { target: { value: "3" } },
      );
      failTemplate = true;
      await user.click(add);
      expect(
        await screen.findByText("Template name already exists"),
      ).toBeVisible();
      expect(screen.getAllByLabelText("OID identifier")).toHaveLength(1);
      expect(templates).toHaveLength(0);
      failTemplate = false;
      fireEvent.change(screen.getByLabelText("MIB template name"), {
        target: { value: "Switch metrics" },
      });
      await user.click(add);
      expect(
        await screen.findByText(/Template “Switch metrics” created/),
      ).toBeVisible();
      expect(templates[0]).toMatchObject({
        name: "Switch metrics",
        sourceMessageType: "snmp",
      });
      expect(templates[0].config.subTableRules[0].mappings).toContainEqual({
        sourceField: "/metrics/portBytes_3/value",
        targetField: "metric_portBytes_3",
        defaultValue: null,
      });
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(1));
      expect(JSON.parse(writes[0].payload.configParams).oids).toEqual([
        { name: "sysUpTime", oid: "1.3.6.1.2.1.1.3.0" },
        { name: "temperature", oid: "1.3.6.1.4.1.32473.2.0" },
        { name: "portBytes_3", oid: "1.3.6.1.4.1.32473.3.1.2.3" },
      ]);
      await user.click(
        screen.getByRole("button", { name: /Field mapping.*common attributes/ }),
      );
      await user.click(
        await screen.findByRole("combobox", { name: "Mapping template" }),
      );
      expect(
        screen.getByRole("option", { name: "Switch metrics" }),
      ).toBeVisible();
    },
  );

  it(
    "adds named and indexed OIDs without losing custom entries when saved and reopened",
    { timeout: 30000 },
    async () => {
      const original = {
        version: "3",
        securityName: "poller",
        securityLevel: "authPriv",
        authPassphraseEnv: "SNMP_AUTH",
        privPassphraseEnv: "SNMP_PRIV",
        targets: [{ name: "switch", host: "192.0.2.10", port: 161 }],
        oids: [
          ".1.3.6.1.2.1.1.3.0",
          { name: "sysName", oid: "1.3.6.1.4.1.32473.1.0", unit: "watts" },
        ],
        customSetting: "keep",
      };
      saved = [
        {
          id: 1,
          type: "SnmpInputAdapter",
          messagetype: "snmp",
          enabled: false,
          configParams: JSON.stringify(original),
        },
      ];
      const user = userEvent.setup();
      renderStudio();
      await user.click(await screen.findByRole("tab", { name: "Targets" }));
      const select = async (name: string) => {
        await user.click(screen.getByRole("combobox", { name: "Common OID" }));
        await user.click(screen.getByRole("option", { name }));
      };
      const add = () =>
        screen.getByRole("button", { name: "Add selected OID" });
      expect(screen.getByText("Metric name: sysName_2")).toBeVisible();
      await user.click(add());
      expect(add()).toBeDisabled();
      await select("sysUpTime");
      expect(add()).toBeDisabled();
      expect(
        screen.getByText("This OID is already in the list."),
      ).toBeVisible();
      await select("ifHCInOctets");
      expect(add()).toBeDisabled();
      const indexInput = screen.getByLabelText("Interface index (ifIndex)");
      for (const value of ["0", "-1", "1.5", "2147483648", "1e3"]) {
        fireEvent.change(indexInput, { target: { value } });
        expect(add()).toBeDisabled();
        expect(indexInput).toHaveAttribute("aria-invalid", "true");
      }
      fireEvent.change(indexInput, { target: { value: "003" } });
      expect(screen.getByLabelText("Resolved OID")).toHaveTextContent(
        "1.3.6.1.2.1.31.1.1.1.6.3",
      );
      await user.click(add());
      expect(add()).toBeDisabled();
      await select("ifHCOutOctets");
      expect(indexInput).toHaveValue("003");
      await user.click(add());
      await select("hrProcessorLoad");
      expect(
        screen.getByLabelText("Processor index (hrDeviceIndex)"),
      ).toHaveValue("");
      expect(add()).toBeDisabled();
      fireEvent.change(
        screen.getByLabelText("Processor index (hrDeviceIndex)"),
        { target: { value: "7" } },
      );
      await user.click(add());
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(1));
      expect(writes[0].method).toBe("PUT");
      expect(typeof writes[0].payload.configParams).toBe("string");
      const config = JSON.parse(writes[0].payload.configParams);
      expect(config).toMatchObject({ ...original, oids: expect.any(Array) });
      expect(config.oids).toEqual([
        ...original.oids,
        { name: "sysName_2", oid: "1.3.6.1.2.1.1.5.0" },
        { name: "ifHCInOctets_3", oid: "1.3.6.1.2.1.31.1.1.1.6.3" },
        { name: "ifHCOutOctets_3", oid: "1.3.6.1.2.1.31.1.1.1.10.3" },
        { name: "hrProcessorLoad_7", oid: "1.3.6.1.2.1.25.3.3.1.2.7" },
      ]);
      await reopen(user, "SNMP poller");
      await user.click(screen.getByRole("tab", { name: "Targets" }));
      expect(
        screen
          .getAllByLabelText("OID identifier")
          .map((input) => (input as HTMLInputElement).value),
      ).toEqual(
        config.oids.map((row: string | { oid: string }) =>
          typeof row === "string" ? row : row.oid,
        ),
      );
      expect(screen.getByDisplayValue("ifHCInOctets_3")).toBeVisible();
      expect(screen.getByRole("button", { name: "Add OID" })).toBeEnabled();
    },
  );

  it(
    "creates and reopens a v3 poller with inherited target settings and editable credentials",
    { timeout: 30000 },
    async () => {
      const user = userEvent.setup();
      renderStudio();
      await chooseType(user, "SNMP poller");
      expect(samplePayload()).toMatchObject({
        protocol: "snmp",
        operation: "poll",
        metrics: {
          sysUpTime: {
            oid: "1.3.6.1.2.1.1.3.0",
            syntax: "TimeTicks",
            value: 8640000,
          },
          ifHCInOctets: { syntax: "Counter64", value: "1234567890123" },
        },
      });
      await user.click(
        screen.getByRole("combobox", { name: "Default version" }),
      );
      await user.click(screen.getByRole("option", { name: "3" }));
      await user.click(screen.getByRole("button", { name: "Save input" }));
      expect(
        await screen.findByText(
          /Use the Authentication defaults or target overrides/,
        ),
      ).toBeVisible();
      expect(writes).toHaveLength(0);
      expect(
        screen.getByRole("combobox", { name: "Target 1 version" }),
      ).toHaveTextContent("Inherit default");
      await user.clear(screen.getByLabelText("Host", { exact: true }));
      await user.type(
        screen.getByLabelText("Host", { exact: true }),
        "192.0.2.55",
      );
      await user.click(screen.getByRole("tab", { name: "Authentication" }));
      expect(
        screen.getByRole("combobox", { name: "Default security level" }),
      ).toHaveTextContent("authPriv");
      expect(
        screen.getByRole("combobox", {
          name: "Default authentication protocol",
        }),
      ).toHaveTextContent("SHA256");
      expect(
        screen.getByRole("combobox", { name: "Default privacy protocol" }),
      ).toHaveTextContent("AES128");
      await user.type(screen.getByLabelText("Default security name"), "poller");
      await user.type(
        screen.getByLabelText("Default authentication passphrase env"),
        "SNMP_AUTH",
      );
      await user.type(
        screen.getByLabelText("Default privacy passphrase env"),
        "SNMP_PRIV",
      );
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(1));
      expect(writes[0].payload).toMatchObject({
        type: "SnmpInputAdapter",
        messagetype: "snmp",
        enabled: false,
      });
      expect(typeof writes[0].payload.configParams).toBe("string");
      const config = JSON.parse(writes[0].payload.configParams);
      expect(config).toMatchObject({
        version: "3",
        securityName: "poller",
        securityLevel: "authPriv",
        authPassphraseEnv: "SNMP_AUTH",
        privPassphraseEnv: "SNMP_PRIV",
      });
      expect(config.targets[0]).toEqual({
        name: "router-01",
        host: "192.0.2.55",
        port: 161,
      });
      expect(config.oids).toEqual([
        { name: "sysUpTime", oid: "1.3.6.1.2.1.1.3.0" },
      ]);
      await reopen(user, "SNMP poller");
      await user.click(screen.getByRole("tab", { name: "Authentication" }));
      expect(screen.getByLabelText("Default security name")).toHaveValue(
        "poller",
      );
      await user.clear(screen.getByLabelText("Default privacy passphrase env"));
      await user.type(
        screen.getByLabelText("Default privacy passphrase env"),
        "SNMP_PRIV_NEXT",
      );
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(2));
      expect(writes[1].method).toBe("PUT");
      expect(JSON.parse(writes[1].payload.configParams)).toMatchObject({
        ...config,
        privPassphraseEnv: "SNMP_PRIV_NEXT",
      });
    },
  );

  it(
    "creates and reopens a receiver with separate v3 Inform and Trap users",
    { timeout: 30000 },
    async () => {
      const user = userEvent.setup();
      renderStudio();
      await chooseType(user, "SNMP notifications");
      expect(samplePayload()).toMatchObject({
        protocol: "snmp",
        operation: "trap",
        trap_oid: "1.3.6.1.6.3.1.1.5.3",
      });
      expect(samplePayload().engine_id).toBeUndefined();
      await user.clear(screen.getByLabelText("Listen port"));
      await user.type(screen.getByLabelText("Listen port"), "1162");
      await user.click(screen.getByRole("button", { name: "Save input" }));
      expect(
        await screen.findByText("Add an SNMPv3 user or an allowed community."),
      ).toBeVisible();
      expect(writes).toHaveLength(0);
      for (let i = 0; i < 2; i++) {
        await user.click(screen.getByRole("button", { name: "Add user" }));
        await user.type(
          screen.getAllByLabelText("Security name")[i],
          "receiver",
        );
        await user.type(
          screen.getAllByLabelText("Authentication passphrase env")[i],
          "SNMP_AUTH",
        );
        await user.type(
          screen.getAllByLabelText("Privacy passphrase env")[i],
          "SNMP_PRIV",
        );
      }
      await user.type(
        screen.getAllByLabelText("Sender engine ID (Trap only)")[1],
        "8000000001020304",
      );
      await user.click(screen.getByRole("tab", { name: "Advanced" }));
      await user.type(
        screen.getByLabelText("Engine state file"),
        "/data/snmp/receiver.json",
      );
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(1));
      expect(writes[0].payload).toMatchObject({
        type: "SnmpNotificationInputAdapter",
        messagetype: "snmp",
        port: 1162,
        enabled: false,
      });
      expect(typeof writes[0].payload.configParams).toBe("string");
      const config = JSON.parse(writes[0].payload.configParams);
      expect(config.users).toHaveLength(2);
      expect(config.users[0].engineId).toBeUndefined();
      expect(config.users[1].engineId).toBe("8000000001020304");
      expect(config.users[1]).toMatchObject({
        securityName: "receiver",
        securityLevel: "authPriv",
        authProtocol: "SHA256",
        privProtocol: "AES128",
        authPassphraseEnv: "SNMP_AUTH",
        privPassphraseEnv: "SNMP_PRIV",
      });
      expect(config.engineStatePath).toBe("/data/snmp/receiver.json");
      await reopen(user, "SNMP notifications");
      await user.click(screen.getByRole("tab", { name: "Security" }));
      expect(
        screen.getAllByLabelText("Sender engine ID (Trap only)")[0],
      ).toHaveValue("");
      expect(
        screen.getAllByLabelText("Sender engine ID (Trap only)")[1],
      ).toHaveValue("8000000001020304");
      await user.click(screen.getByRole("tab", { name: "Listener" }));
      await user.clear(screen.getByLabelText("Listen port"));
      await user.type(screen.getByLabelText("Listen port"), "2162");
      await user.click(screen.getByRole("button", { name: "Save input" }));
      await waitFor(() => expect(writes).toHaveLength(2));
      expect(writes[1].method).toBe("PUT");
      expect(writes[1].payload.port).toBe(2162);
      expect(JSON.parse(writes[1].payload.configParams)).toEqual(config);
    },
  );

  it("previews SNMP Inform data through the parser and preserves a hand-edited sample", async () => {
    saved = [
      {
        id: 1,
        type: "snmp_notification",
        messagetype: "snmp",
        enabled: false,
        configParams:
          '{"users":[{"securityName":"sample","securityLevel":"noAuthNoPriv"}]}',
      },
    ];
    const user = userEvent.setup();
    renderStudio();
    await waitFor(() => expect(samplePayload().operation).toBe("trap"));
    await user.click(screen.getByRole("combobox", { name: "Sample data" }));
    await user.click(screen.getByRole("option", { name: "SNMP Inform (v3)" }));
    const inform = samplePayload();
    expect(inform.operation).toBe("inform");
    expect(inform.engine_id).toBe("80:00:00:00:05:06:07:08");
    expect(inform.varbinds).toHaveLength(5);
    await user.click(
      screen.getByRole("button", { name: "Test selected step" }),
    );
    expect(await screen.findByText("Completed")).toBeVisible();
    expect(
      screen.getByRole("region", { name: "SNMP notifications result content" }),
    ).toHaveTextContent('"operation": "inform"');
    await user.click(screen.getByRole("button", { name: /JSON parser Order/ }));
    expect(samplePayload()).toEqual(inform);
    await user.click(
      screen.getByRole("button", { name: "Test selected step" }),
    );
    expect(await screen.findByText("Completed")).toBeVisible();
    expect(
      screen.getByRole("region", { name: "JSON parser result content" }),
    ).toHaveTextContent('"operation": "inform"');
    await user.click(
      screen.getByRole("button", { name: "SNMP notifications Disabled" }),
    );
    expect(samplePayload()).toEqual(inform);
    const custom = JSON.stringify({ ...inform, request_id: 99 });
    fireEvent.change(screen.getByLabelText("Original sample event"), {
      target: { value: custom },
    });
    await chooseType(user, "SNMP poller");
    expect(screen.getByLabelText("Original sample event")).toHaveValue(custom);
    expect(
      screen.getByRole("combobox", { name: "Sample data" }),
    ).toHaveTextContent("Custom sample");
    await user.click(screen.getByRole("combobox", { name: "Sample data" }));
    await user.click(screen.getByRole("option", { name: "SNMP polling (v3)" }));
    expect(samplePayload().operation).toBe("poll");
  });
});
