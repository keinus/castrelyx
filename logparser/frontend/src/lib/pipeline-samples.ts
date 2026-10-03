const observedAt = "2026-09-03T00:00:00Z";
const senderEngineId = "80:00:00:00:01:02:03:04";
const binding = (oid: string, syntax: string, value: string | number) => ({
  oid,
  syntax,
  status: "success",
  value,
});

function notification(operation: "trap" | "inform") {
  return JSON.stringify(
    {
      protocol: "snmp",
      operation,
      received_at: observedAt,
      observed_at: observedAt,
      target_host: "192.0.2.10",
      source_port: 49162,
      version: "3",
      request_id: 42,
      security_name: "snmp-notifier",
      security_level: "authPriv",
      ...(operation === "inform"
        ? { engine_id: "80:00:00:00:05:06:07:08" }
        : {}),
      context_name: "",
      context_engine_id: senderEngineId,
      trap_oid: "1.3.6.1.6.3.1.1.5.3",
      uptime_ticks: 8640000,
      varbinds: [
        binding("1.3.6.1.2.1.1.3.0", "TimeTicks", 8640000),
        binding(
          "1.3.6.1.6.3.1.1.4.1.0",
          "OBJECT IDENTIFIER",
          "1.3.6.1.6.3.1.1.5.3",
        ),
        binding("1.3.6.1.2.1.2.2.1.1.1", "Integer32", 1),
        binding("1.3.6.1.2.1.2.2.1.7.1", "Integer32", 1),
        binding("1.3.6.1.2.1.2.2.1.8.1", "Integer32", 2),
      ],
    },
    null,
    2,
  );
}

// Illustrative payloads follow the input adapters' JSON format, without credentials.
export const PIPELINE_SAMPLES = {
  syslog: {
    label: "Syslog",
    text: '<34>1 2026-08-27T12:00:00.000Z mymachine.example.com su 1234 ID47 [exampleSDID@32473 iut="3" eventSource="Application"] \'su root\' failed for lonvick on /dev/pts/8',
  },
  snmp_poll: {
    label: "SNMP polling (v3)",
    text: JSON.stringify(
      {
        protocol: "snmp",
        operation: "poll",
        observed_at: observedAt,
        poll_time: observedAt,
        target_name: "router-01",
        target_host: "192.0.2.10",
        target_port: 161,
        version: "3",
        security_level: "authPriv",
        security_name: "snmp-reader",
        poll_status: "success",
        metrics: {
          sysName: {
            ...binding("1.3.6.1.2.1.1.5.0", "OCTET STRING", "router-01"),
            hex: "72:6f:75:74:65:72:2d:30:31",
          },
          sysUpTime: binding("1.3.6.1.2.1.1.3.0", "TimeTicks", 8640000),
          ifOperStatus: binding("1.3.6.1.2.1.2.2.1.8.1", "Integer32", 1),
          ifHCInOctets: binding(
            "1.3.6.1.2.1.31.1.1.1.6.1",
            "Counter64",
            "1234567890123",
          ),
          ifHCOutOctets: binding(
            "1.3.6.1.2.1.31.1.1.1.10.1",
            "Counter64",
            "987654321012",
          ),
        },
      },
      null,
      2,
    ),
  },
  snmp_trap: { label: "SNMP Trap (v3)", text: notification("trap") },
  snmp_inform: { label: "SNMP Inform (v3)", text: notification("inform") },
};

export type PipelineSample = keyof typeof PIPELINE_SAMPLES;

export function inputSample(type: string): PipelineSample {
  if (type === "SnmpInputAdapter") return "snmp_poll";
  if (type === "SnmpNotificationInputAdapter") return "snmp_trap";
  return "syslog";
}

export function identifySample(text: string): PipelineSample | "custom" {
  return (
    (Object.keys(PIPELINE_SAMPLES) as PipelineSample[]).find(
      (key) => PIPELINE_SAMPLES[key].text === text,
    ) || "custom"
  );
}
