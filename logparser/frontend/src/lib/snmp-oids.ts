export type SnmpOidPreset = {
  name: string;
  label: string;
  oid: string;
  index?: "interface" | "processor" | "storage";
  help?: string;
};

// SNMPv2-MIB (RFC 3418), IF-MIB (RFC 2863), HOST-RESOURCES-MIB (RFC 2790).
// Scalars include .0; table columns need the device's instance index.
export const SNMP_OID_PRESETS: SnmpOidPreset[] = [
  { name: "sysName", label: "Device name", oid: "1.3.6.1.2.1.1.5.0" },
  { name: "sysDescr", label: "Device description", oid: "1.3.6.1.2.1.1.1.0" },
  { name: "sysObjectID", label: "Device type", oid: "1.3.6.1.2.1.1.2.0" },
  { name: "sysUpTime", label: "SNMP uptime", oid: "1.3.6.1.2.1.1.3.0" },
  { name: "sysLocation", label: "Device location", oid: "1.3.6.1.2.1.1.6.0" },
  {
    name: "ifDescr",
    label: "Interface description",
    oid: "1.3.6.1.2.1.2.2.1.2",
    index: "interface",
  },
  {
    name: "ifAdminStatus",
    label: "Admin status",
    oid: "1.3.6.1.2.1.2.2.1.7",
    index: "interface",
  },
  {
    name: "ifOperStatus",
    label: "Link status",
    oid: "1.3.6.1.2.1.2.2.1.8",
    index: "interface",
  },
  {
    name: "ifHCInOctets",
    label: "Received bytes (64-bit)",
    oid: "1.3.6.1.2.1.31.1.1.1.6",
    index: "interface",
    help: "Cumulative counter. Requires SNMP v2c or v3.",
  },
  {
    name: "ifHCOutOctets",
    label: "Sent bytes (64-bit)",
    oid: "1.3.6.1.2.1.31.1.1.1.10",
    index: "interface",
    help: "Cumulative counter. Requires SNMP v2c or v3.",
  },
  {
    name: "ifInOctets",
    label: "Received bytes (32-bit)",
    oid: "1.3.6.1.2.1.2.2.1.10",
    index: "interface",
    help: "Cumulative counter; wraps at 2^32 bytes.",
  },
  {
    name: "ifOutOctets",
    label: "Sent bytes (32-bit)",
    oid: "1.3.6.1.2.1.2.2.1.16",
    index: "interface",
    help: "Cumulative counter; wraps at 2^32 bytes.",
  },
  {
    name: "ifInErrors",
    label: "Receive errors",
    oid: "1.3.6.1.2.1.2.2.1.14",
    index: "interface",
  },
  {
    name: "ifOutErrors",
    label: "Send errors",
    oid: "1.3.6.1.2.1.2.2.1.20",
    index: "interface",
  },
  {
    name: "hrProcessorLoad",
    label: "CPU load",
    oid: "1.3.6.1.2.1.25.3.3.1.2",
    index: "processor",
    help: "Average CPU use over the last minute, in percent.",
  },
  {
    name: "hrStorageAllocationUnits",
    label: "Bytes per allocation unit",
    oid: "1.3.6.1.2.1.25.2.3.1.4",
    index: "storage",
  },
  {
    name: "hrStorageSize",
    label: "Total storage units",
    oid: "1.3.6.1.2.1.25.2.3.1.5",
    index: "storage",
    help: "Multiply by hrStorageAllocationUnits at the same index for bytes.",
  },
  {
    name: "hrStorageUsed",
    label: "Used storage units",
    oid: "1.3.6.1.2.1.25.2.3.1.6",
    index: "storage",
    help: "Multiply by hrStorageAllocationUnits at the same index for bytes.",
  },
];
