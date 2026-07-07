# Castrelyx UI Reference Review

Date: 2026-06-29
Target: https://192.168.50.25/ui/
Scope: Security Switch UI reference for possible Castrelyx Manager redesign.

## Captured Steps

1. Overview - healthy. The screen uses a fixed dark sidebar, compact header actions, KPI cards, chart area, operational tables, and service-health panels.
   Screenshot: 01-overview.png

2. Ports/VLAN - healthy. Dense operator form layout works well for selected object editing, with the main list on the left and selected detail on the right.
   Screenshot: 02-ports-vlan.png

3. ACL - mixed. The high-density rule editor is useful for expert operators, but narrow right-side grids can become cramped with long policy/rule values.
   Screenshot: 03-acl.png

4. Security - healthy with density risk. The sections are grouped logically, but many checkbox/input controls create a form-heavy page that needs strong grouping when adapted to Manager.
   Screenshot: 04-security.png

5. Monitoring - healthy. Timeline plus event list plus audit/metrics tables is a strong pattern for operational evidence and can map well to Castrelyx Manager telemetry.
   Screenshot: 05-monitoring.png

6. Admin - healthy but sparse. The credential/access model is clear, though the page feels empty compared with richer operational screens.
   Screenshot: 06-admin.png

## Strengths To Borrow

- A persistent dark sidebar gives the product a clear control-plane identity and keeps navigation predictable.
- The top-right action cluster separates refresh/validate/apply operations from the content body.
- Status chips, small count badges, and table row highlights make the screen scannable without large decorative surfaces.
- The visual density is appropriate for NMS/security operations: compact cards, low-radius panels, readable tables, and restrained color.
- The Overview and Monitoring pages combine summary, current state, and evidence in one place, which fits Castrelyx Manager's asset, traffic, agent, and CastrelSign workflows.

## Risks If Applied Directly

- Teal is doing almost all of the visual work. Castrelyx Manager should keep teal as the primary operational color but add clear warning, critical, neutral, and informational tones.
- Forms can become visually flat if every screen is a white card with inputs. Manager pages need more hierarchy between dashboard, table, detail drawer, and mutating form states.
- Some ACL fields are cramped in narrow columns. Manager should preserve horizontal scroll or use detail drawers for complex editable rows.
- This reference is very device-local. Manager is fleet-level, so the same style must support multi-tenant or multi-asset context without making everything look like one appliance.

## Recommended Manager Direction

- Use this as the base visual language for Castrelyx Manager: dark sidebar, light operational canvas, compact top action bar, 8px panels, teal primary actions, and status-first navigation.
- Rework Manager around a shared `ConsoleShell` and `ViewFrame`: title/subtitle/status/actions should be consistent on every view.
- Convert Overview, Assets, Traffic, Agent, Agent Logs, SNMP, Alerts, CastrelSign, LogParser, and Settings into the same three-level pattern: summary strip, main work area, evidence/detail panel.
- Keep existing shadcn-like primitives and lucide icons; do not introduce a separate UI library.
- Start with Overview, Assets, Traffic, and CastrelSign because they already contain the most real operational data and will set the design system for the simpler pages.

