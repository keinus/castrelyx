import { useId, useState } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Field,
  FieldLabel,
  FieldDescription,
  FieldError,
} from "@/components/ui/field";
import { SelectInput } from "@/components/shared";
import { SNMP_OID_PRESETS } from "@/lib/snmp-oids";
import type { Attributes } from "@/lib/types";

const indexLabels = {
  interface: "Interface index (ifIndex)",
  processor: "Processor index (hrDeviceIndex)",
  storage: "Storage index (hrStorageIndex)",
};

function canonicalOid(oid: string) {
  return oid.trim().replace(/^\./, "").split(".").map(Number).join(".");
}

export function SnmpOidPicker({
  rows,
  onAdd,
}: {
  rows: (string | Attributes)[];
  onAdd: (entry: { name: string; oid: string }) => void;
}) {
  const id = useId();
  const [selected, setSelected] = useState("sysName");
  const [index, setIndex] = useState("");
  const preset = SNMP_OID_PRESETS.find((item) => item.name === selected)!;
  const validIndex =
    /^\d+$/.test(index) && Number(index) >= 1 && Number(index) <= 2147483647;
  const ready = !preset.index || validIndex;
  const oid = `${preset.oid}${preset.index ? `.${validIndex ? Number(index) : "<index>"}` : ""}`;
  const duplicate =
    ready &&
    rows.some(
      (row) =>
        canonicalOid(
          String(typeof row === "string" ? row : (row.oid ?? "")),
        ) === oid,
    );
  const baseName = `${preset.name}${preset.index && validIndex ? `_${Number(index)}` : ""}`;
  const names = new Set(
    rows.map((row) => (typeof row === "string" ? row : row.name || row.oid)),
  );
  let name = baseName;
  for (let suffix = 2; names.has(name); suffix++)
    name = `${baseName}_${suffix}`;

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/30 p-4">
      <Field>
        <FieldLabel htmlFor={`${id}-preset`}>Common OID</FieldLabel>
        <SelectInput
          id={`${id}-preset`}
          label="Common OID"
          value={selected}
          options={SNMP_OID_PRESETS.map((item) => item.name)}
          onChange={(value) => {
            if (
              SNMP_OID_PRESETS.find((item) => item.name === value)?.index !==
              preset.index
            )
              setIndex("");
            setSelected(value);
          }}
        />
        <FieldDescription>
          {preset.label}
          {preset.help ? `. ${preset.help}` : ""}
        </FieldDescription>
      </Field>
      {preset.index && (
        <Field>
          <FieldLabel htmlFor={`${id}-index`}>
            {indexLabels[preset.index]}
          </FieldLabel>
          <Input
            id={`${id}-index`}
            inputMode="numeric"
            placeholder="e.g. 3"
            value={index}
            aria-invalid={index !== "" && !validIndex}
            aria-describedby={`${id}-index-help`}
            onChange={(event) => setIndex(event.target.value)}
          />
          <FieldDescription id={`${id}-index-help`}>
            Use the index reported by your device. It is not discovered
            automatically.
          </FieldDescription>
          {index !== "" && !validIndex && (
            <FieldError>Enter a whole number from 1 to 2147483647.</FieldError>
          )}
        </Field>
      )}
      <div className="min-w-0 text-sm" aria-live="polite">
        <div className="break-all font-mono text-xs" aria-label="Resolved OID">
          {oid}
        </div>
        {duplicate ? (
          <p className="mt-1 text-muted-foreground">
            This OID is already in the list.
          </p>
        ) : (
          ready && (
            <p className="mt-1 break-all text-muted-foreground">
              Metric name: {name}
            </p>
          )
        )}
      </div>
      <Button
        type="button"
        variant="outline"
        className="self-start"
        disabled={!ready || duplicate}
        onClick={() => onAdd({ name, oid })}
      >
        <Plus data-icon="inline-start" />
        Add selected OID
      </Button>
    </div>
  );
}
