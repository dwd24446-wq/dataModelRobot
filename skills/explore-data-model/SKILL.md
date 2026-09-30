---
name: explore-data-model
description: Explore and explain a dataModelRobot-scanned Spring Boot (MyBatis-Plus stack) project's data model — tables, inferred table relations with confidence scores, evidence chains, and conflicts. Use when the user asks how two tables relate, what a foreign-key column points to, why a relation scored some confidence, which relations need review, or to resolve ambiguous/low-confidence relations the rule engine couldn't decide. Orchestrates the datamodelrobot MCP tools (get_tables / get_relations / explain_relation / list_conflicts), and for relations the engine punted on, reads the real source code and records LLM-inferred relations to a separate rollback-able overlay (type=inferred, confidence capped at 0.6) without ever touching the fact source.
---

# Explore Data Model

## Overview

Answer data-model questions about a project scanned by **dataModelRobot** (the IntelliJ plugin that turns Spring Boot code + DB schema into a JSON fact source of tables, relations, evidence, conflicts, and confidence). Two jobs:

- **A — Explore/explain**: query the fact source to answer "how do these tables relate / what does this column point to / why this confidence / what's wrong here".
- **B — LLM fallback**: for relations the rule engine marked `UNRESOLVED` or `needsReview`, read the actual source code, decide the target, and record an **inferred** relation to a separate overlay — capped at 0.6, evidence required, never merged into the fact source.

The fact source is the only source of truth for scores. **Do not invent or recompute confidence** — read what the engine produced, and when you infer, mark it `inferred` and cap it.

## Prerequisites

1. A fact source JSON exists at `<project root>/build/datamodel/<project>-datamodel.json`, produced by the plugin's `Tools -> Scan Data Model` action. Find its path; pass it to every script via `--fact-source` or `DATAMODEL_FACT_SOURCE`.
2. **Preferred**: the `datamodelrobot` MCP server is registered (see the repository's `mcp-server/README.md`). Call its `get_tables`, `get_relations`, `explain_relation`, and `list_conflicts` tools; tool-name prefixes vary by client.
3. **Fallback when MCP isn't registered**: run `node scripts/query.mjs` from this skill directory (same data layer, CLI). The top-level `skills/` and `mcp-server/` directories are siblings; this skill lives in `skills/explore-data-model/`. If a client copies the skill elsewhere, set `DATAMODELROBOT_ROOT` to the repository root so the script can load `mcp-server/src/factSource.js`.
4. For job B you also need **read access to the scanned project's source** (to search and read imports and call sites). If the scanned project isn't the current workspace, ask the user for its root before starting.

## A — Explore / explain

Map the question to a tool. With MCP registered use the tool; otherwise the equivalent `query.mjs` command.

| Question | MCP tool | query.mjs |
|---|---|---|
| What's in this DB? stats, modules, scoring formula | `get_tables` (no args) | `overview` |
| Columns/indexes/FKs of a table | `get_tables {table}` | `tables --table T` |
| Tables in a module / matching a name | `get_tables {module\|name}` | `tables --module M` / `--name N` |
| What does column X.Y point to? (all candidates, ranked) | `get_relations {end:"X.Y"}` | `relations --end X.Y` |
| Relations touching a table | `get_relations {table:"T"}` | `relations --table T` |
| High-confidence / solid relations only | `get_relations {solid:true}` or `{minConfidence:0.6}` | `relations --min 0.6` |
| **Why** this relation? full evidence + arithmetic + conflicts | `explain_relation {relation:"a.b->c.d"}` | `explain a.b->c.d` |
| What's inconsistent? conflicts | `list_conflicts {type\|severity\|subjectContains}` | `conflicts --type T` |
| What needs a human? | `get_relations {needsReview:true}` | `relations --needs-review true` |

`get_relations` returns candidates **ranked by confidence** — the top one is the engine's answer. Always read `ambiguity` (WINNER/LOSER/UNRESOLVED) and `schemaAligned` before trusting a target. Use `explain_relation` whenever the user asks "why" or before acting on a relation: it carries the evidence chain (`file:line`), the `explain` arithmetic, the disambiguation reason, and the joined conflicts.

**Table names in Chinese**: a table carries two labels — `comment` (the DB table comment, authoritative) and `entityDoc` (first paragraph of the DO class Javadoc). Name a table to the user with `comment`, falling back to `entityDoc`; never invert that order, and never present an `entityDoc` as if the DB said it. `stats.tablesMissingLabel` says how many tables have neither.

**Evidence type names**: `sources[]` / `evidences[].type` carry machine enum names (`MPJ_JOIN`, `NAMING_CONVENTION`, …). The fact source's `scoring.sourceLabels` maps each to the display name used by the IDE panel — `MPJ 显式 JOIN` / `键元组同名配对` / `Lambda 等值条件` / `Service 层组装` / `命名约定`. Present those Chinese labels to the user (`get_relations` already returns them as `sourcesZh`), keep the enum name for tool arguments and filters, and if an artifact has no `sourceLabels` (generated before the legend) just quote the enum name — never invent a translation.

Key semantics (full list in `references/fact-source-and-overlay.md`):
- `confidence` bands: HIGH ≥0.85, MEDIUM ≥0.60, LOW <0.60. **0.60 is also the solid/dashed line** — solid = engine trusts it; dashed = go verify.
- `schemaAligned:false` = code references a table/column not in this DB (drift). Kept, flagged, capped at 0.59 — not a bug in the relation, a bug in code-vs-DB sync.
- `needsReview:true` = the engine couldn't conclude (unresolved ambiguity, direction, drift). These are job-B candidates.

## B — LLM fallback for ambiguous relations

Trigger: the user asks to resolve/decide ambiguous or low-confidence relations, or to "do a round" of inference. Only handle relations the engine punted on — never override a relation the engine already scored solid.

### Workflow

1. **List targets**: `node scripts/query.mjs ambiguous --limit N` from this skill directory (or `get_relations {needsReview:true}` then filter `ambiguity.outcome == UNRESOLVED`). Returns each unresolved `fromEnd`, its candidate `targets`, and any `codeLocations`.
2. **Pick a tractable cluster**. Ambiguity concentrates on a few cross-module column names (`user_id` usually dominates). Resolve them family-by-family, not one-by-one — the same evidence settles every `<table>.user_id`.
3. **Gather evidence from the real source** (`codeLocations` is often empty for these — the engine had no method-reference record, which is *why* it's unresolved; so search yourself):
   - Find the entity: search for `@TableName("<table>")` in the project → the DO class.
   - Find usages of the column: search for the getter (`getUserId`) or the field within the owning module's `service`/`api`/`controller`.
   - **Read the imports** of those files. The imported API/entity family is the decisive signal (see the `user_id` table below).
   - If the column is written from another entity's id (`.setUserId(x.getId())`), note where `x` comes from.
4. **Decide** the single most-supported target, or conclude "genuinely polymorphic / undecidable" (then record nothing, or record with low confidence + a rationale saying so).
5. **Record** each inferred relation:
   ```bash
   node scripts/record-inferred.mjs --fact-source <fact-source.json> \
     --from pay_order.user_id --to member_user.id \
     --confidence 0.55 \
     --rationale "PayOrderServiceImpl imports MemberUserApi and calls memberUserApi.getUser(userId); pay orders are placed by members, not admins" \
     --evidence "PayOrderServiceImpl.java:88: import ...member.api.MemberUserApi" \
     --evidence "PayOrderServiceImpl.java:140: memberUserApi.getUser(order.getUserId())"
   ```
   The script enforces the invariants (rejects >0.6, requires rationale + ≥1 evidence, forces `type:inferred`, writes to `<name>-inferred.json` next to the fact source, refuses to overwrite the fact source). Re-running the same `--from/--to` updates in place.
6. **Report** what you inferred, the confidence, and the evidence. State plainly that these live in the overlay, are `needsReview`, and are rolled back by deleting it.

### Choosing confidence (≤ 0.6, always)

- **0.55–0.60**: decisive code evidence — the owning service imports exactly one candidate's API family and calls it with this column.
- **0.40–0.55**: strong convention + partial code support (e.g. module-prefix semantics + one indirect call).
- **0.20–0.40**: convention only, no decisive code; record so a human sees the lean, flag the doubt in `rationale`.
- **Record nothing** when evidence is symmetric/contradictory — an honest "undecidable" beats a coin-flip in the overlay.

The cap is hard: 0.6 is what the rule engine gives a naming-only relation. If you're *more* sure than that, the evidence belongs in the engine (code-side), not in an LLM guess — say so instead of inflating the number.

### `user_id` disambiguation (the common case)

When a `<table>.user_id` is torn between these, the importing API/entity family in the referencing code decides:

| Target | Family in imports / calls | Semantics |
|---|---|---|
| `member_user` | `MemberUserApi`, `MemberUser*`, `member.api.*` | 会员（C 端用户）。trade/pay/promotion 的 `user_id` 几乎都是这个 |
| `system_users` | `AdminUserApi`, `PermissionApi`, `SystemUser*`, `admin.api.*` | 后台管理员。`infra_*`/`system_*` 日志、操作人字段 |
| `system_social_user` | `SocialUserApi`, `social.*` | 社交登录绑定，少见 |
| `trade_brokerage_user` | （注意：此表**没有** `user_id` 列，只有 `id` + `bind_user_id`） | 分销账户；`user_id→trade_brokerage_user.id` 基本是命名退化误配，排除 |

`infra_api_access_log.user_id` / `infra_api_error_log.user_id` are genuinely **polymorphic** in this stack (they log both member and admin calls, distinguished by a `user_type` column) — if the code confirms that, record it as undecidable-to-one-target (low confidence + rationale naming `user_type`), not as a forced pick.

## Hard rules (do not violate)

- **Never write to the fact source** (`*-datamodel.json`). Inferred relations go only to the overlay (`*-inferred.json`) via `record-inferred.mjs`.
- **Every inferred relation**: `type:inferred`, `confidence ≤ 0.6`, `needsReview:true`, `solid:false`, a `rationale`, and ≥1 `evidence` with a real `file:line`. The script enforces this — don't bypass it by hand-editing.
- **Don't recompute or override engine scores.** Read them; explain them; only *add* inferred relations alongside.
- **Rollback = delete the overlay file.** Keep it that way: don't sprinkle inferred relations into other artifacts.
- Database DDL is out of scope — this skill only reads the fact source and writes the overlay.

## Resources

- `scripts/query.mjs` — CLI over the fact source (reuses `mcp-server/src/factSource.js`): `overview | tables | relations | explain | conflicts | ambiguous`. Fallback for when the MCP server isn't registered, and the entry point for job B (`ambiguous`).
- `scripts/record-inferred.mjs` — validated writer for the inferred overlay; enforces every hard rule above. `--list` to inspect, delete the file to roll back.
- `references/fact-source-and-overlay.md` — fact-source field cheat-sheet (relations/evidence/conflicts/scoring), confidence semantics, and the overlay schema.
