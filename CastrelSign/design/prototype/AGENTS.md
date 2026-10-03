# Prototype Instructions

## CastrelSign design decisions

- Selected source: `../reference/selected-dark.png`. Preserve its key table and right inspector, and the simpler user service workspace.
- User requested SAP Fundamental Styles, dark surfaces, mostly achromatic colors, no fluorescent/neon accents. Use the installed SAP icon font and component CSS with the neutral token overrides in `src/prototype.css`.
- This is an isolated, in-memory screen-design prototype. Never connect production APIs, accept real credentials, or imply that demo changes reach the KMS.
- General-user self-service, browser sessions, and aggregated configuration views require backend extensions. Keep this distinction in `../screen_spec.md`.
- Keep all key-destruction, compromised-key, dual-approval, offline-backup, and service-owned-private-key constraints in the screen flows.

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.
