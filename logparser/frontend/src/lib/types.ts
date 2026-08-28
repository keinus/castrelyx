export type Stage = "input" | "parser" | "transform" | "output";
// Adapter metadata is extensible; unknown attributes must survive a form round trip.
export type Attributes = Record<string, any>;
export interface Adapter extends Attributes {
  id?: number;
  type: string;
  messagetype: string;
  enabled: boolean;
  priority?: number;
}
export type Inventory = Record<Stage, Adapter[]>;
export type FieldKind =
  | "text"
  | "number"
  | "bytes"
  | "password"
  | "url"
  | "select"
  | "boolean"
  | "textarea"
  | "json"
  | "jsonList"
  | "keyValue"
  | "mapList"
  | "scope";
export interface FieldDefinition {
  path: string;
  label: string;
  type: FieldKind;
  default?: any;
  required?: boolean;
  tab?: string | string[];
  wide?: boolean;
  min?: number;
  max?: number;
  pattern?: string;
  choices?: string[];
  help?: string;
  unit?: string;
  readonly?: boolean;
  placeholder?: string;
  list?: string;
  valueLabel?: string;
}
export interface AdapterDefinition {
  type: string;
  label: string;
  icon: string;
  description: string;
  tabs: string[];
  fields: FieldDefinition[];
  notice?: string;
  warning?: string;
}
export interface FieldMapping {
  sourceField: string;
  targetField: string;
  defaultValue: string | null;
}
export interface Mapping {
  id?: string;
  messageType: string;
  commonMappings: FieldMapping[];
  subTableRules: {
    targetSubTable: string;
    conditionExpression: string;
    mappings: FieldMapping[];
  }[];
}
export interface MappingTemplate {
  id: string;
  name: string;
  description: string;
  sourceMessageType: string;
  config: Mapping;
}
export const stages: Stage[] = ["input", "parser", "transform", "output"];
export const stageNames: Record<Stage, string> = {
  input: "Input",
  parser: "Parser",
  transform: "Transform",
  output: "Output",
};
export const emptyInventory: Inventory = {
  input: [],
  parser: [],
  transform: [],
  output: [],
};
