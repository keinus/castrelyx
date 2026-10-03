import { describe, expect, it } from "vitest";
import {
  instanceOid,
  mergeMibOids,
  snmpMibMapping,
  type MibObject,
} from "./snmp-mib";

const scalar: MibObject = {
  module: "EXAMPLE-MIB",
  name: "temperature",
  oid: "1.3.6.1.4.1.32473.2",
  syntax: "INTEGER_32",
  access: "read-only",
  description: "Sensor",
  units: "Celsius",
  scalar: true,
  table: null,
  indexes: [],
};
const column: MibObject = {
  ...scalar,
  name: "portBytes",
  oid: "1.3.6.1.4.1.32473.3.1.2",
  scalar: false,
  table: "portEntry",
  indexes: ["portIndex"],
};
describe("MIB import configuration", () => {
  it("appends scalar instances and validates encoded composite table suffixes", () => {
    expect(instanceOid(scalar, "")).toBe(`${scalar.oid}.0`);
    expect(instanceOid(column, "003.7")).toBe(`${column.oid}.3.7`);
    for (const suffix of ["", "-1", "3..7", "4294967296", "1e3", ".3"])
      expect(instanceOid(column, suffix)).toBeNull();
  });
  it("preserves custom attributes and reuses existing aliases without duplicate OIDs", () => {
    const existing = [
      { name: "custom.name/~", oid: `.${scalar.oid}.0`, unit: "C" },
      { name: "portBytes_3", oid: "1.3.6.1.4.1.32473.9.0" },
    ];
    const result = mergeMibOids(existing, [
      { object: scalar, oid: `${scalar.oid}.0` },
      { object: column, oid: `${column.oid}.3` },
    ]);
    expect(result.rows.slice(0, 2)).toEqual(existing);
    expect(result.rows[2]).toEqual({
      name: "portBytes_3_2",
      oid: `${column.oid}.3`,
    });
    const template = snmpMibMapping("snmp", result.metrics);
    expect(template.subTableRules[0].mappings).toContainEqual({
      sourceField: "/metrics/custom.name~1~0/value",
      targetField: "metric_custom.name/~",
      defaultValue: null,
    });
    expect(template.subTableRules[0].mappings).toContainEqual({
      sourceField: "metrics",
      targetField: "metrics",
      defaultValue: null,
    });
    expect(template.commonMappings).toContainEqual({
      sourceField: "",
      targetField: "event_category",
      defaultValue: "snmp",
    });
  });
});
