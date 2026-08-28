// The existing StarUML model converter, shared with the unified documentation view.
const CLASS_NODE_TYPES = new Set([
  "UMLClass",
  "UMLInterface",
  "UMLEnumeration",
  "UMLDataType",
  "UMLSignal",
]);
const GENERIC_NODE_TYPES = new Set([
  "UMLClass",
  "UMLInterface",
  "UMLEnumeration",
  "UMLDataType",
  "UMLSignal",
  "UMLActor",
  "UMLUseCase",
  "UMLComponent",
  "UMLPackage",
  "UMLArtifact",
  "UMLNode",
  "UMLSubsystem",
]);
const RELATION_TYPES = new Set([
  "UMLAssociation",
  "UMLAssociationClassLink",
  "UMLDependency",
  "UMLGeneralization",
  "UMLInterfaceRealization",
  "UMLInclude",
  "UMLExtend",
  "UMLRealization",
]);

export function buildStarUmlRenderables(root) {
  const allObjects = collectObjects(root);
  const index = new Map(
    allObjects
      .filter(
        (item) =>
          item && typeof item === "object" && typeof item._id === "string",
      )
      .map((item) => [item._id, item]),
  );

  const allNodes = allObjects.filter((item) => isModelNode(item));
  const allRelations = allObjects.filter((item) => isRelation(item));
  const diagrams = allObjects.filter((item) => isDiagram(item));

  if (diagrams.length === 0) {
    const synthetic = buildRenderableForScope({
      title: root.name || "Model Overview",
      type: root._type || "StarUML Model",
      nodes: allNodes,
      relations: allRelations,
      index,
    });
    return synthetic ? [synthetic] : [];
  }

  return diagrams
    .map((diagram) => {
      const scopedIds = collectDiagramReferences(diagram);
      let nodes = allNodes.filter((node) => scopedIds.has(node._id));
      if (nodes.length === 0) {
        nodes = allNodes;
      }

      const nodeIds = new Set(nodes.map((node) => node._id));
      let relations = allRelations.filter((relation) => {
        const endpoints = resolveRelationEndpoints(relation, index);
        if (!endpoints) {
          return false;
        }

        return nodeIds.has(endpoints.from._id) && nodeIds.has(endpoints.to._id);
      });

      if (relations.length === 0) {
        relations = allRelations.filter((relation) => {
          const endpoints = resolveRelationEndpoints(relation, index);
          return (
            endpoints &&
            nodeIds.has(endpoints.from._id) &&
            nodeIds.has(endpoints.to._id)
          );
        });
      }

      return buildRenderableForScope({
        title: diagram.name || diagram._type,
        type: diagram._type,
        nodes,
        relations,
        index,
      });
    })
    .filter(Boolean);
}

function buildRenderableForScope({ title, type, nodes, relations, index }) {
  if (!nodes || nodes.length === 0) {
    return null;
  }

  const classNodes = nodes.filter((node) => CLASS_NODE_TYPES.has(node._type));
  if (classNodes.length > 0) {
    return buildClassDiagramRenderable(
      title,
      type,
      classNodes,
      relations,
      index,
    );
  }

  return buildFlowchartRenderable(
    title,
    type,
    nodes.filter((node) => GENERIC_NODE_TYPES.has(node._type)),
    relations,
    index,
  );
}

function buildClassDiagramRenderable(title, type, nodes, relations, index) {
  const aliases = createAliasMap(nodes);
  const lines = ["classDiagram"];

  nodes.forEach((node) => {
    const alias = aliases.get(node._id);
    const members = buildClassMembers(node, index);
    if (members.length === 0) {
      lines.push(`class ${alias}`);
    } else {
      lines.push(`class ${alias} {`);
      members.forEach((member) => lines.push(`  ${member}`));
      lines.push("}");
    }

    if (node._type === "UMLInterface") {
      lines.push(`<<interface>> ${alias}`);
    } else if (node._type === "UMLEnumeration") {
      lines.push(`<<enumeration>> ${alias}`);
    }
  });

  const relationLines = new Set();
  relations.forEach((relation) => {
    const endpoints = resolveRelationEndpoints(relation, index);
    if (
      !endpoints ||
      !aliases.has(endpoints.from._id) ||
      !aliases.has(endpoints.to._id)
    ) {
      return;
    }

    relationLines.add(buildClassRelationLine(relation, endpoints, aliases));
  });

  relationLines.forEach((line) => {
    if (line) {
      lines.push(line);
    }
  });

  return {
    title,
    type,
    note: `${nodes.length} classes`,
    mermaid: lines.join("\n"),
    meta: relations.length > 0 ? [`${relations.length} relations`] : [],
  };
}

function buildFlowchartRenderable(title, type, nodes, relations, index) {
  if (!nodes || nodes.length === 0) {
    return null;
  }

  const aliases = createAliasMap(nodes);
  const lines = ["flowchart LR"];

  nodes.forEach((node) => {
    const alias = aliases.get(node._id);
    lines.push(`  ${alias}${buildFlowchartShape(node)}`);
  });

  const relationLines = new Set();
  relations.forEach((relation) => {
    const endpoints = resolveRelationEndpoints(relation, index);
    if (
      !endpoints ||
      !aliases.has(endpoints.from._id) ||
      !aliases.has(endpoints.to._id)
    ) {
      return;
    }

    relationLines.add(buildFlowchartRelationLine(relation, endpoints, aliases));
  });

  relationLines.forEach((line) => {
    if (line) {
      lines.push(`  ${line}`);
    }
  });

  return {
    title,
    type,
    note: `${nodes.length} nodes`,
    mermaid: lines.join("\n"),
    meta: relations.length > 0 ? [`${relations.length} links`] : [],
  };
}

function buildClassMembers(node, index) {
  const members = [];

  (node.attributes || []).forEach((attribute) => {
    const name = attribute.name || "attribute";
    const typeName = resolveTypeName(
      attribute.type || attribute.reference,
      index,
    );
    const line = `${visibilityPrefix(attribute.visibility)}${sanitizeMember(name)}${typeName ? ` : ${sanitizeMember(typeName)}` : ""}`;
    members.push(line);
  });

  (node.operations || []).forEach((operation) => {
    const params = (operation.parameters || [])
      .filter((parameter) => parameter.direction !== "return")
      .map((parameter) => {
        const parameterType = resolveTypeName(parameter.type, index);
        return `${sanitizeMember(parameter.name || "arg")}${parameterType ? `: ${sanitizeMember(parameterType)}` : ""}`;
      })
      .join(", ");

    members.push(
      `${visibilityPrefix(operation.visibility)}${sanitizeMember(operation.name || "operation")}(${params})`,
    );
  });

  return members;
}

function buildClassRelationLine(relation, endpoints, aliases) {
  const from = aliases.get(endpoints.from._id);
  const to = aliases.get(endpoints.to._id);
  const label = sanitizeRelationLabel(relation.name || "");

  switch (relation._type) {
    case "UMLGeneralization":
      return `${from} <|-- ${to}${label ? ` : ${label}` : ""}`;
    case "UMLInterfaceRealization":
    case "UMLRealization":
      return `${from} <|.. ${to}${label ? ` : ${label}` : ""}`;
    case "UMLDependency":
      return `${from} <.. ${to}${label ? ` : ${label}` : ""}`;
    case "UMLAssociation":
    case "UMLAssociationClassLink":
      return `${from} --> ${to}${label ? ` : ${label}` : ""}`;
    default:
      return `${from} --> ${to}${label ? ` : ${label}` : ""}`;
  }
}

function buildFlowchartShape(node) {
  const label = escapeMermaidLabel(node.name || node._type);

  switch (node._type) {
    case "UMLActor":
      return `([\"${label}\"])`;
    case "UMLUseCase":
      return `([\"${label}\"])`;
    case "UMLComponent":
    case "UMLArtifact":
    case "UMLSubsystem":
      return `[[\"${label}\"]]`;
    case "UMLPackage":
      return `[\"${label}\"]`;
    default:
      return `[\"${label}\"]`;
  }
}

function buildFlowchartRelationLine(relation, endpoints, aliases) {
  const from = aliases.get(endpoints.from._id);
  const to = aliases.get(endpoints.to._id);
  const label = sanitizeRelationLabel(relation.name || "");

  switch (relation._type) {
    case "UMLGeneralization":
      return label ? `${from} -->|${label}| ${to}` : `${from} --> ${to}`;
    case "UMLInclude":
      return `${from} -. include .-> ${to}`;
    case "UMLExtend":
      return `${from} -. extend .-> ${to}`;
    case "UMLDependency":
    case "UMLInterfaceRealization":
    case "UMLRealization":
      return label ? `${from} -. ${label} .-> ${to}` : `${from} -.-> ${to}`;
    default:
      return label ? `${from} -->|${label}| ${to}` : `${from} --> ${to}`;
  }
}

function resolveRelationEndpoints(relation, index) {
  if (!relation) {
    return null;
  }

  let from = null;
  let to = null;

  switch (relation._type) {
    case "UMLGeneralization":
      from = dereference(
        relation.general || relation.target || relation.end1?.reference,
        index,
      );
      to = dereference(
        relation.specific || relation.source || relation.end2?.reference,
        index,
      );
      break;
    case "UMLInterfaceRealization":
    case "UMLRealization":
      from = dereference(
        relation.contract || relation.target || relation.end1?.reference,
        index,
      );
      to = dereference(
        relation.implementingClassifier ||
          relation.source ||
          relation.end2?.reference,
        index,
      );
      break;
    case "UMLDependency":
      from = dereference(
        relation.supplier || relation.target || relation.end2?.reference,
        index,
      );
      to = dereference(
        relation.client || relation.source || relation.end1?.reference,
        index,
      );
      break;
    case "UMLInclude":
    case "UMLExtend":
      from = dereference(
        relation.target || relation.addition || relation.end2?.reference,
        index,
      );
      to = dereference(
        relation.source || relation.extension || relation.end1?.reference,
        index,
      );
      break;
    default:
      from = dereference(
        relation.end1?.reference ||
          relation.source ||
          relation.tail ||
          relation.client,
        index,
      );
      to = dereference(
        relation.end2?.reference ||
          relation.target ||
          relation.head ||
          relation.supplier,
        index,
      );
  }

  if (!isModelNode(from) || !isModelNode(to)) {
    return null;
  }

  return { from, to };
}

function collectDiagramReferences(diagram) {
  const refs = new Set();
  const stack = [diagram];

  while (stack.length > 0) {
    const current = stack.pop();
    if (!current || typeof current !== "object") {
      continue;
    }

    if (typeof current.$ref === "string") {
      refs.add(current.$ref);
    }

    Object.values(current).forEach((value) => {
      if (value && typeof value === "object") {
        stack.push(value);
      }
    });
  }

  return refs;
}

function collectObjects(root) {
  const seen = new WeakSet();
  const result = [];
  const stack = [root];

  while (stack.length > 0) {
    const current = stack.pop();
    if (!current || typeof current !== "object" || seen.has(current)) {
      continue;
    }

    seen.add(current);
    result.push(current);

    if (Array.isArray(current)) {
      current.forEach((item) => stack.push(item));
      continue;
    }

    Object.values(current).forEach((value) => {
      if (value && typeof value === "object") {
        stack.push(value);
      }
    });
  }

  return result;
}

function createAliasMap(nodes) {
  const aliases = new Map();
  const used = new Set();

  nodes.forEach((node, index) => {
    let alias = sanitizeIdentifier(
      node.name || node._id || `Node_${index + 1}`,
    );
    while (used.has(alias)) {
      alias = `${alias}_${index + 1}`;
    }
    used.add(alias);
    aliases.set(node._id, alias);
  });

  return aliases;
}

function sanitizeIdentifier(value) {
  let sanitized = String(value || "Node")
    .replace(/[^a-zA-Z0-9_]/g, "_")
    .replace(/^_+/, "");

  if (!sanitized) {
    sanitized = "Node";
  }

  if (/^[0-9]/.test(sanitized)) {
    sanitized = `N_${sanitized}`;
  }

  return sanitized;
}

function sanitizeMember(value) {
  return String(value || "")
    .replace(/[{}<>]/g, "")
    .trim();
}

function sanitizeRelationLabel(value) {
  return String(value || "")
    .replace(/[\n\r:]/g, " ")
    .trim();
}

function escapeMermaidLabel(value) {
  return String(value || "")
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\n/g, "<br/>");
}

function visibilityPrefix(visibility) {
  switch (visibility) {
    case "private":
      return "-";
    case "protected":
      return "#";
    case "package":
      return "~";
    default:
      return "+";
  }
}

function resolveTypeName(value, index) {
  const resolved = dereference(value, index);
  if (typeof resolved === "string") {
    return resolved;
  }
  if (resolved && typeof resolved === "object") {
    return resolved.name || resolved._type || "";
  }
  return "";
}

function dereference(value, index) {
  if (!value) {
    return null;
  }

  if (typeof value === "string") {
    return value;
  }

  if (value.$ref) {
    return index.get(value.$ref) || null;
  }

  return value;
}

function isDiagram(item) {
  return (
    item &&
    typeof item._type === "string" &&
    item._type.endsWith("Diagram") &&
    !item._type.endsWith("View")
  );
}

function isModelNode(item) {
  return (
    item &&
    typeof item === "object" &&
    typeof item._type === "string" &&
    !item._type.endsWith("View") &&
    !item._type.endsWith("Compartment") &&
    GENERIC_NODE_TYPES.has(item._type) &&
    typeof item._id === "string"
  );
}

function isRelation(item) {
  return (
    item &&
    typeof item === "object" &&
    typeof item._type === "string" &&
    RELATION_TYPES.has(item._type) &&
    typeof item._id === "string"
  );
}
