(function () {
    const DOC_CONTENT_ENDPOINT = "/api/v1/docs/content";
    const DOC_RAW_ENDPOINT = "/api/v1/docs/raw";
    const MARKDOWN_EXTENSIONS = new Set(["md", "markdown"]);
    const STARUML_EXTENSIONS = new Set(["mdj"]);
    const MERMAID_LANGUAGES = new Set(["mermaid", "mmd"]);
    const STARUML_LANGUAGES = new Set(["staruml", "staruml-json", "mdj"]);
    const CLASS_NODE_TYPES = new Set([
        "UMLClass", "UMLInterface", "UMLEnumeration", "UMLDataType", "UMLSignal"
    ]);
    const GENERIC_NODE_TYPES = new Set([
        "UMLClass", "UMLInterface", "UMLEnumeration", "UMLDataType", "UMLSignal",
        "UMLActor", "UMLUseCase", "UMLComponent", "UMLPackage", "UMLArtifact",
        "UMLNode", "UMLSubsystem"
    ]);
    const RELATION_TYPES = new Set([
        "UMLAssociation", "UMLAssociationClassLink", "UMLDependency", "UMLGeneralization",
        "UMLInterfaceRealization", "UMLInclude", "UMLExtend", "UMLRealization"
    ]);

    const state = {
        currentPath: "README.md",
    };

    const elements = {
        pathForm: document.getElementById("path-form"),
        pathInput: document.getElementById("path-input"),
        rawLink: document.getElementById("raw-link"),
        currentPath: document.getElementById("current-path"),
        statusText: document.getElementById("status-text"),
        docContainer: document.getElementById("doc-container"),
    };

    mermaid.initialize({
        startOnLoad: false,
        securityLevel: "loose",
        theme: "dark",
        themeVariables: {
            primaryColor: "#0d1728",
            primaryTextColor: "#dce8f6",
            primaryBorderColor: "#7dd3fc",
            lineColor: "#8ba2bf",
            secondaryColor: "#13243a",
            tertiaryColor: "#07101b",
        },
        flowchart: {
            useMaxWidth: true,
            htmlLabels: true,
        },
    });

    document.addEventListener("DOMContentLoaded", () => {
        bindEvents();
        const requestedPath = new URLSearchParams(window.location.search).get("path") || "README.md";
        loadDocument(requestedPath);
    });

    function bindEvents() {
        elements.pathForm.addEventListener("submit", (event) => {
            event.preventDefault();
            const requestedPath = elements.pathInput.value.trim() || "README.md";
            const nextUrl = new URL(window.location.href);
            nextUrl.searchParams.set("path", requestedPath);
            window.history.pushState({}, "", nextUrl);
            loadDocument(requestedPath);
        });

        window.addEventListener("popstate", () => {
            const requestedPath = new URLSearchParams(window.location.search).get("path") || "README.md";
            loadDocument(requestedPath);
        });
    }

    async function loadDocument(requestedPath) {
        const normalizedPath = requestedPath.trim() || "README.md";
        state.currentPath = normalizedPath;

        elements.pathInput.value = normalizedPath;
        updateHeader(normalizedPath);
        setStatus("Loading document…");
        renderLoadingState();

        try {
            const response = await fetch(`${DOC_CONTENT_ENDPOINT}?path=${encodeURIComponent(normalizedPath)}`);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            const payload = await response.json();
            state.currentPath = payload.path;
            updateHeader(payload.path);

            const extension = getExtension(payload.path);
            if (MARKDOWN_EXTENSIONS.has(extension) || payload.mediaType === "text/markdown") {
                await renderMarkdownDocument(payload.content);
                setStatus("Markdown rendered.");
                return;
            }

            if (STARUML_EXTENSIONS.has(extension)) {
                await renderStarUmlDocument(payload.content, payload.path);
                setStatus("StarUML diagram rendered.");
                return;
            }

            renderSourceBlock(payload.content, payload.mediaType);
            setStatus(`${payload.mediaType} rendered as source.`);
        } catch (error) {
            console.error(error);
            renderErrorState(normalizedPath, error);
            setStatus("Failed to load document.");
        }
    }

    function updateHeader(path) {
        elements.currentPath.textContent = path;
        elements.rawLink.href = `${DOC_RAW_ENDPOINT}?path=${encodeURIComponent(path)}`;
    }

    function setStatus(text) {
        elements.statusText.textContent = text;
    }

    function renderLoadingState() {
        elements.docContainer.innerHTML = `
            <div class="empty-state">
                <span class="loading loading-dots loading-lg text-info"></span>
            </div>
        `;
    }

    function renderErrorState(path, error) {
        const message = escapeHtml(error.message || "Unknown error");
        elements.docContainer.innerHTML = `
            <div class="diagram-card diagram-error">
                <div class="diagram-toolbar">
                    <div>
                        <div class="eyebrow">Load Error</div>
                        <div class="text-sm text-slate-100">${escapeHtml(path)}</div>
                    </div>
                </div>
                <div class="diagram-body">
                    <p class="text-amber-300">문서를 불러오지 못했습니다.</p>
                    <pre class="source-block">${message}</pre>
                </div>
            </div>
        `;
    }

    async function renderMarkdownDocument(markdown) {
        const rawHtml = marked.parse(markdown, { gfm: true, breaks: false });
        const cleanHtml = DOMPurify.sanitize(rawHtml, {
            USE_PROFILES: { html: true },
        });

        elements.docContainer.innerHTML = cleanHtml;
        rewriteDocumentLinks(elements.docContainer);
        transformCodeBlocks(elements.docContainer);
        await renderMermaidBlocks(elements.docContainer);
    }

    function renderSourceBlock(content, mediaType) {
        elements.docContainer.innerHTML = `
            <div class="diagram-card">
                <div class="diagram-toolbar">
                    <div>
                        <div class="eyebrow">Source View</div>
                        <div class="text-sm text-slate-100">${escapeHtml(mediaType)}</div>
                    </div>
                </div>
                <div class="diagram-body">
                    <pre class="source-block">${escapeHtml(content)}</pre>
                </div>
            </div>
        `;
    }

    function rewriteDocumentLinks(root) {
        root.querySelectorAll("a[href]").forEach((anchor) => {
            const href = anchor.getAttribute("href");
            if (!href || href.startsWith("#") || isExternalHref(href)) {
                if (isExternalHref(href)) {
                    anchor.target = "_blank";
                    anchor.rel = "noopener noreferrer";
                }
                return;
            }

            const resolvedPath = resolveRelativePath(state.currentPath, href);
            const extension = getExtension(resolvedPath);
            if (MARKDOWN_EXTENSIONS.has(extension) || STARUML_EXTENSIONS.has(extension)) {
                anchor.href = `/markdown-viewer.html?path=${encodeURIComponent(resolvedPath)}`;
                return;
            }

            anchor.href = `${DOC_RAW_ENDPOINT}?path=${encodeURIComponent(resolvedPath)}`;
            anchor.target = "_blank";
            anchor.rel = "noopener noreferrer";
        });

        root.querySelectorAll("img[src]").forEach((image) => {
            const src = image.getAttribute("src");
            if (!src || isExternalHref(src) || src.startsWith("data:")) {
                return;
            }

            const resolvedPath = resolveRelativePath(state.currentPath, src);
            image.src = `${DOC_RAW_ENDPOINT}?path=${encodeURIComponent(resolvedPath)}`;
        });
    }

    function transformCodeBlocks(root) {
        const blocks = Array.from(root.querySelectorAll("pre > code"));
        blocks.forEach((codeBlock, index) => {
            const language = getCodeLanguage(codeBlock);
            if (!language) {
                return;
            }

            const pre = codeBlock.parentElement;
            if (!pre) {
                return;
            }

            const source = codeBlock.textContent || "";
            if (MERMAID_LANGUAGES.has(language)) {
                pre.replaceWith(createMermaidCard(source, "Mermaid", `mermaid-${index + 1}`));
                return;
            }

            if (STARUML_LANGUAGES.has(language)) {
                pre.replaceWith(createStarUmlCard(source, `StarUML Block ${index + 1}`));
            }
        });
    }

    async function renderStarUmlDocument(source, sourcePath) {
        elements.docContainer.innerHTML = "";
        elements.docContainer.appendChild(createStarUmlCard(source, sourcePath));
        await renderMermaidBlocks(elements.docContainer);
    }

    function createMermaidCard(source, title, subtitle) {
        const wrapper = document.createElement("section");
        wrapper.className = "diagram-card";
        wrapper.innerHTML = `
            <div class="diagram-toolbar">
                <div>
                    <div class="eyebrow">${escapeHtml(title)}</div>
                    <div class="text-sm text-slate-100">${escapeHtml(subtitle || "diagram")}</div>
                </div>
            </div>
            <div class="diagram-body">
                <div class="diagram-source" data-diagram-kind="mermaid" hidden></div>
            </div>
        `;

        wrapper.querySelector(".diagram-source").textContent = source.trim();
        return wrapper;
    }

    function createStarUmlCard(source, label) {
        const documentCard = document.createElement("section");
        documentCard.className = "diagram-card";

        const body = document.createElement("div");
        body.className = "diagram-body";

        try {
            const parsed = JSON.parse(stripBom(source));
            const renderables = buildStarUmlRenderables(parsed);

            documentCard.innerHTML = `
                <div class="diagram-toolbar">
                    <div>
                        <div class="eyebrow">StarUML</div>
                        <div class="text-sm text-slate-100">${escapeHtml(label)}</div>
                    </div>
                    <div class="diagram-note">${renderables.length} diagram(s)</div>
                </div>
            `;

            if (renderables.length === 0) {
                body.appendChild(createNotice(
                    "지원 가능한 StarUML 다이어그램을 찾지 못했습니다. 원본 JSON을 표시합니다."
                ));
                body.appendChild(createSourcePre(source));
            } else {
                renderables.forEach((diagram) => {
                    body.appendChild(createNestedDiagramCard(diagram));
                });
            }
        } catch (error) {
            documentCard.classList.add("diagram-error");
            documentCard.innerHTML = `
                <div class="diagram-toolbar">
                    <div>
                        <div class="eyebrow">StarUML Parse Error</div>
                        <div class="text-sm text-slate-100">${escapeHtml(label)}</div>
                    </div>
                </div>
            `;

            body.appendChild(createNotice("StarUML JSON 파싱에 실패했습니다. 원본 소스를 표시합니다."));
            body.appendChild(createSourcePre(source));
            body.appendChild(createNotice(escapeHtml(error.message || "Unknown parse error")));
        }

        documentCard.appendChild(body);
        return documentCard;
    }

    function createNestedDiagramCard(diagram) {
        const section = document.createElement("section");
        section.className = "diagram-card";
        section.innerHTML = `
            <div class="diagram-toolbar">
                <div>
                    <div class="eyebrow">${escapeHtml(diagram.type)}</div>
                    <div class="text-sm text-slate-100">${escapeHtml(diagram.title)}</div>
                </div>
                <div class="diagram-note">${escapeHtml(diagram.note)}</div>
            </div>
            <div class="diagram-body"></div>
        `;

        const body = section.querySelector(".diagram-body");
        if (diagram.mermaid) {
            const source = document.createElement("div");
            source.className = "diagram-source";
            source.hidden = true;
            source.dataset.diagramKind = "mermaid";
            source.textContent = diagram.mermaid;
            body.appendChild(source);
        }

        if (diagram.meta && diagram.meta.length > 0) {
            const note = document.createElement("p");
            note.className = "diagram-note mt-3";
            note.textContent = diagram.meta.join(" · ");
            body.appendChild(note);
        }

        return section;
    }

    function createNotice(message) {
        const note = document.createElement("p");
        note.className = "diagram-note";
        note.textContent = message;
        return note;
    }

    function createSourcePre(content) {
        const pre = document.createElement("pre");
        pre.className = "source-block";
        pre.textContent = content;
        return pre;
    }

    async function renderMermaidBlocks(root) {
        const blocks = Array.from(root.querySelectorAll(".diagram-source[data-diagram-kind='mermaid']"));
        for (const block of blocks) {
            const source = (block.textContent || "").trim();
            const container = document.createElement("div");

            if (!source) {
                container.innerHTML = `<p class="diagram-note">Empty Mermaid source.</p>`;
                block.replaceWith(container);
                continue;
            }

            try {
                const renderId = `mermaid-${Date.now()}-${Math.random().toString(16).slice(2)}`;
                const { svg, bindFunctions } = await mermaid.render(renderId, source);
                container.innerHTML = svg;
                bindFunctions?.(container);
                block.replaceWith(container);
            } catch (error) {
                container.innerHTML = `
                    <div class="diagram-note mb-3">Mermaid 렌더링에 실패했습니다. 원본 소스를 표시합니다.</div>
                    <pre class="source-block">${escapeHtml(source)}</pre>
                `;
                block.replaceWith(container);
                console.error("Mermaid render failed", error);
            }
        }
    }

    function buildStarUmlRenderables(root) {
        const allObjects = collectObjects(root);
        const index = new Map(allObjects
            .filter((item) => item && typeof item === "object" && typeof item._id === "string")
            .map((item) => [item._id, item]));

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

        return diagrams.map((diagram) => {
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
                    return endpoints && nodeIds.has(endpoints.from._id) && nodeIds.has(endpoints.to._id);
                });
            }

            return buildRenderableForScope({
                title: diagram.name || diagram._type,
                type: diagram._type,
                nodes,
                relations,
                index,
            });
        }).filter(Boolean);
    }

    function buildRenderableForScope({ title, type, nodes, relations, index }) {
        if (!nodes || nodes.length === 0) {
            return null;
        }

        const classNodes = nodes.filter((node) => CLASS_NODE_TYPES.has(node._type));
        if (classNodes.length > 0) {
            return buildClassDiagramRenderable(title, type, classNodes, relations, index);
        }

        return buildFlowchartRenderable(title, type, nodes.filter((node) => GENERIC_NODE_TYPES.has(node._type)), relations, index);
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
            if (!endpoints || !aliases.has(endpoints.from._id) || !aliases.has(endpoints.to._id)) {
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
            if (!endpoints || !aliases.has(endpoints.from._id) || !aliases.has(endpoints.to._id)) {
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
            const typeName = resolveTypeName(attribute.type || attribute.reference, index);
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

            members.push(`${visibilityPrefix(operation.visibility)}${sanitizeMember(operation.name || "operation")}(${params})`);
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
                from = dereference(relation.general || relation.target || relation.end1?.reference, index);
                to = dereference(relation.specific || relation.source || relation.end2?.reference, index);
                break;
            case "UMLInterfaceRealization":
            case "UMLRealization":
                from = dereference(relation.contract || relation.target || relation.end1?.reference, index);
                to = dereference(relation.implementingClassifier || relation.source || relation.end2?.reference, index);
                break;
            case "UMLDependency":
                from = dereference(relation.supplier || relation.target || relation.end2?.reference, index);
                to = dereference(relation.client || relation.source || relation.end1?.reference, index);
                break;
            case "UMLInclude":
            case "UMLExtend":
                from = dereference(relation.target || relation.addition || relation.end2?.reference, index);
                to = dereference(relation.source || relation.extension || relation.end1?.reference, index);
                break;
            default:
                from = dereference(relation.end1?.reference || relation.source || relation.tail || relation.client, index);
                to = dereference(relation.end2?.reference || relation.target || relation.head || relation.supplier, index);
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
            let alias = sanitizeIdentifier(node.name || node._id || `Node_${index + 1}`);
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
        return String(value || "").replace(/[\n\r:]/g, " ").trim();
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
        return item && typeof item._type === "string"
            && item._type.endsWith("Diagram")
            && !item._type.endsWith("View");
    }

    function isModelNode(item) {
        return item
            && typeof item === "object"
            && typeof item._type === "string"
            && !item._type.endsWith("View")
            && !item._type.endsWith("Compartment")
            && GENERIC_NODE_TYPES.has(item._type)
            && typeof item._id === "string";
    }

    function isRelation(item) {
        return item
            && typeof item === "object"
            && typeof item._type === "string"
            && RELATION_TYPES.has(item._type)
            && typeof item._id === "string";
    }

    function getCodeLanguage(codeBlock) {
        const className = codeBlock.className || "";
        const match = className.match(/language-([a-z0-9_-]+)/i);
        return match ? match[1].toLowerCase() : "";
    }

    function getExtension(path) {
        const cleanPath = String(path || "").split("#")[0].split("?")[0];
        const dotIndex = cleanPath.lastIndexOf(".");
        return dotIndex >= 0 ? cleanPath.slice(dotIndex + 1).toLowerCase() : "";
    }

    function resolveRelativePath(currentPath, targetPath) {
        const normalizedTarget = String(targetPath || "").split("#")[0].split("?")[0];
        if (!normalizedTarget) {
            return currentPath;
        }

        const isAbsolute = normalizedTarget.startsWith("/");
        const baseParts = currentPath.split("/").filter(Boolean);
        if (baseParts.length > 0) {
            baseParts.pop();
        }

        const pathParts = isAbsolute ? [] : [...baseParts];
        normalizedTarget.split("/").forEach((part) => {
            if (!part || part === ".") {
                return;
            }
            if (part === "..") {
                pathParts.pop();
                return;
            }
            pathParts.push(part);
        });

        return pathParts.join("/") || "README.md";
    }

    function isExternalHref(href) {
        return /^(https?:)?\/\//i.test(href) || href.startsWith("mailto:");
    }

    function stripBom(text) {
        return String(text || "").replace(/^\uFEFF/, "");
    }

    function escapeHtml(value) {
        return String(value || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }
})();
