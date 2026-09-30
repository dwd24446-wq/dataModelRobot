# Fact source & overlay reference

Field cheat-sheet for the dataModelRobot JSON fact source (`format: datamodelrobot/2`) and the inferred overlay (`datamodelrobot-inferred/1`). Read this when you need exact field names/semantics; the SKILL.md workflow covers the common path.

Paths under `scripts/` are relative to the `explore-data-model` skill directory.

## Fact source top level

`format, project, generatedAt, source{provider,dbms,dbmsVersion,catalog,server}, stats{...}, scoring{...}, modules[{name,tables[]}], excludedTables[], tables[], relations[], adjacency{}, conflicts[]`

`stats` carries the authoritative counts (tables/columns/indexes/relations/relationsByBand/relationsNeedingReview/relationsUnaligned/evidences/conflicts/conflictsBySeverity). Trust `stats` over recomputing.

## tables[]

`{name, module, comment?, engine?, columns[{name,position,type,fullType,nullable,key?,defaultValue?,extra?,comment?}], indexes[{name,unique,columns[]}], foreignKeys[{name,columns[],refTable,refColumns[],onDelete?,onUpdate?}]}`

- `column.key` mirrors information_schema `COLUMN_KEY`: `PRI` / `UNI` / `MUL` / null.
- `engine`/`collation`/index `type` are present only from the JSON-dump path; the DatabaseTools (IDE) path leaves them null. Absent/null fields are omitted from the JSON — treat every optional field as "may be missing".
- Business tables in this stack typically have **zero foreign keys**; relations come from code/naming/key-tuple inference, not `foreignKeys[]`.

## relations[]

`{text, key{fromTable,fromColumn,toTable,toColumn}, confidence, band, solid, baseScore, tier, schemaAligned, needsReview, reviewReasons[], explain, penalties[{trigger,factor,reason}], ambiguity?{targets[],outcome,reason?}, signals{...}, sources[], locations[], orientedBy[], evidences[]}`

- `text` = `"fromTable.fromColumn->toTable.toColumn"` — the join key for `explain_relation` and for conflict subjects.
- `confidence` ∈ (0, 0.99]; `band` ∈ HIGH/MEDIUM/LOW; `solid` = `confidence ≥ 0.60`.
- `baseScore` = noisy-OR of evidence weights (before penalties); `explain` is the one-line reproducible arithmetic, e.g. `0.46 = base 0.600 ×0.80(type_mismatch) ×0.95(ambiguous_winner)`.
- `tier` (support, **not** confidence): `CODE_BACKED` (has code evidence) > `SCHEMA_STRONG` > `WEAK` (naming only).
- `schemaAligned:false` = code references a table/column absent from this DB (code↔DB drift). Such relations are capped at 0.59, flagged `needsReview`, excluded from Mermaid by default.
- `ambiguity.outcome`: `WINNER` (disambiguation picked this), `LOSER` (picked another target — kept but penalised), `UNRESOLVED` (engine couldn't decide — **job-B target**).
- `signals`: `fromType/toType/typeMatch` (SAME/BASE_SAME/DIFFERENT/UNKNOWN), `toIsPrimaryKey/toIsUniqueKey`, `fromIndexed`, `sameModule`, `sharedNameSegments` (name-prefix segments beyond the module prefix).
- `sources[]`: evidence types present — `MPJ_JOIN` / `LAMBDA_EQ` / `SELECT_ASSOCIATION` / `NAMING_CONVENTION` / `KEY_TUPLE`. Display names come from `scoring.sourceLabels`: MPJ 显式 JOIN / Lambda 等值条件 / Service 层组装 / 命名约定 / 键元组同名配对. Filter and quote the enum name; show the user the Chinese label.
- `locations[]`: `file:line` of the strongest code evidence (may be empty for naming-only relations).

### evidences[]

`{type, weight, schemaAligned, from{table,column,entityFqn?,file?,line?,refText?}, to{...}, joinType?, callName?, enclosingMethod?, scenario?, notes[]}`

Weights: `MPJ_JOIN`（MPJ 显式 JOIN）.95, `KEY_TUPLE`（键元组同名配对）.90 (`.70` when the target is only a unique key), `LAMBDA_EQ`（Lambda 等值条件）.85, `SELECT_ASSOCIATION`（Service 层组装）.75, `NAMING_CONVENTION`（命名约定）.60 (`.45` tail-only). `schemaAligned:false` evidence is halved (×0.5) by the aligner. `file` is basename only (absolute paths would break diffing).

## adjacency{}

`{ "<table>": { out: [item], in: [item] } }`, item = `{table, fromColumn, toColumn, confidence, band, solid, cardinality}`

A **derived view** of `relations[]`, recomputed on every render. Never authoritative — if the two disagree, trust `relations[]`. Use it to answer "what does this table touch" without scanning all relations.

- `out` = this table holds the foreign key and points at `item.table`; `in` = `item.table` holds the foreign key and points at this table. Every relation appears exactly twice (once in each end's bucket), so total items = 2 × `stats.relations`.
- `fromColumn`/`toColumn` keep the **relation's own** direction (from = the FK side) and do **not** flip with the bucket — that is what lets you rebuild `relations[].text` and pull the full evidence chain via `explain_relation`.
- `cardinality` is Mermaid ER notation, left = target side, right = FK side: `||--o{` one→many (FK nullable), `||--|{` one→many (FK non-nullable), `||--||` one→one (shared primary key), `}o--o{` the target column is **not** unique so this is not a one-to-many at all. Line style is not encoded here — `solid` carries it. Identical to what the `.mmd` artifacts draw.
- Only tables taking part in ≥1 relation have a key. A missing key means "no inferred relations"; the full table list lives in `tables[]`/`modules[]`.
- Keys are sorted by table name and each bucket by neighbour/column — **not** by score — so a small score change doesn't reshuffle the whole section.

## conflicts[]

`{type, severity, subject, detail, refs[], resolution?, needsReview}`

- `type`: `TYPE_MISMATCH` (①), `MISSING_TENANT_SCOPE`/`MISSING_LOGICAL_DELETE` (②), `AMBIGUOUS_TARGET` (③), `RECIPROCAL_INCONSISTENT` (④), `ORPHAN_COLUMN` (⑤).
- `severity`: HIGH/MEDIUM/LOW.
- `subject` convention (how to join a conflict to a relation):
  - ① TYPE_MISMATCH, ④ RECIPROCAL_INCONSISTENT → `subject` = the full relation `text`.
  - ③ AMBIGUOUS_TARGET, ⑤ ORPHAN_COLUMN → `subject` = `"table.column"` (the from-end).
  - ② isolation → `subject` = `"file:line method → table.column"` (query-chain scoped; **not** joined to a single relation — find via `list_conflicts {subjectContains}`).
- `resolution` is present when ③ was disambiguated (names the winning target + the reason).
- `needsReview` = the engine couldn't conclude (②, unresolved ③, ④, ⑤). ① is a definite finding, so it is **not** flagged needsReview.

## scoring (in the fact source — pass it through, never re-derive)

`{formula, base, cap, bands{HIGH,MEDIUM,LOW}, solidLineMin, sourceLabels{ENUM:中文名}, penalties[{trigger,severity?,factor,why}], notScored[]}`

- `formula`: `confidence = round2( min(noisyOr(evidence.weight), cap) × Π penalty.factor )`, `cap = 0.99`.
- Penalty factors: `type_mismatch` HIGH ×0.55 / MEDIUM ×0.80 / LOW ×0.95; `ambiguous_winner` ×0.95 / `ambiguous_loser` ×0.45 / `ambiguous_unresolved` ×0.65; `reciprocal` ×0.60 and `direction_unresolved` ×0.50 (**only the heavier of the two applies**); `target_not_key` ×0.70.
- `notScored`: ② isolation conflicts and schema-drift are **not** folded into confidence (② only sets `needsReview`; drift already halved the evidence weight). Don't penalise a relation for them.

### The 0.60 line (three meanings, one number)

`0.60` is simultaneously: the MEDIUM/LOW band boundary, the Mermaid solid/dashed threshold, the base score of a naming-only relation, **and the cap on LLM-inferred confidence**. An inferred relation at 0.60 would sit exactly on the solid line — that's why the cap is `≤ 0.6` and inferred relations are always `solid:false` + `needsReview:true` regardless.

## Inferred overlay (`datamodelrobot-inferred/1`)

Written only by `scripts/record-inferred.mjs`, to `<factSourceName with -datamodel→-inferred>.json` beside the fact source. **Separate file = separately filterable; delete it to roll back.** The fact source is never modified.

```
{ format, project, factSource, capConfidence:0.6, note, generatedAt,
  relations: [ {
    type:"inferred", text, key{fromTable,fromColumn,toTable,toColumn},
    confidence,            // always ≤ 0.6
    band,                  // LOW (<0.6) or MEDIUM (==0.6)
    solid:false, needsReview:true,
    resolvesFromEnd,       // the ambiguous "table.column" this decides
    rationale,             // why — name the file/import/call you relied on
    evidence:[{file?,line?,note}],   // ≥1, real file:line
    inferredBy:{layer:"phase3-skill", by, at}
  } ] }
```

`relations` is sorted by `text` (overlay is diffable). Re-recording the same `text` updates in place (idempotent). The writer rejects `confidence > 0.6`, empty `rationale`, zero `evidence`, and any attempt to target the fact-source file.
