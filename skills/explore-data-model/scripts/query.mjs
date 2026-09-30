#!/usr/bin/env node
// 事实源 CLI 查询。复用 mcp-server 的数据层（同一份 FactSource，不另立逻辑）。
// 用途：datamodelrobot MCP server 未注册时的回退路径，也是 Skill 验收的查询入口。
// 输出 JSON 到 stdout（LLM 直接读），错误到 stderr。
import { readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** 插件把事实源写到 `<项目根>/build/datamodel/`，文件名带项目名。 */
const repoRoot = process.env.DATAMODELROBOT_ROOT
  ? resolve(process.env.DATAMODELROBOT_ROOT)
  : fileURLToPath(new URL('../../../', import.meta.url));
const DEFAULT_FACT_SOURCE_DIR = join(repoRoot, 'build/datamodel');
const { loadFactSource, FactSourceError } = await import(
  pathToFileURL(join(repoRoot, 'mcp-server/src/factSource.js')).href
);

/**
 * 自动发现事实源：恰好一个就用它；多个取字典序第一个**并在 stderr 告警** ——
 * 与 `mcp-server/src/index.js` 同一行为，静默选一份会让人以为看的是自己刚扫的那个项目。
 * 一个都没有（还没跑过扫描）就返回目录本身，让 loadFactSource 报错、由调用方提示用 --fact-source 指定。
 */
function discoverDefaultFactSource() {
  let hits;
  try {
    hits = readdirSync(DEFAULT_FACT_SOURCE_DIR).filter((f) => f.endsWith('-datamodel.json')).sort();
  } catch {
    // 目录不存在（还没跑过扫描）：交给 loadFactSource 报错
    return DEFAULT_FACT_SOURCE_DIR;
  }
  if (hits.length === 0) return DEFAULT_FACT_SOURCE_DIR;
  if (hits.length > 1) {
    process.stderr.write(`[query] ${DEFAULT_FACT_SOURCE_DIR} 下有 ${hits.length} 份事实源，默认取 ${hits[0]}；要指定别的用 --fact-source\n`);
  }
  return join(DEFAULT_FACT_SOURCE_DIR, hits[0]);
}

function fail(msg, code = 2) {
  process.stderr.write(`[query] ${msg}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const positional = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const eq = a.indexOf('=');
      if (eq >= 0) flags[a.slice(2, eq)] = a.slice(eq + 1);
      else if (argv[i + 1] && !argv[i + 1].startsWith('--')) flags[a.slice(2)] = argv[++i];
      else flags[a.slice(2)] = true;
    } else positional.push(a);
  }
  return { cmd: positional[0], rest: positional.slice(1), flags };
}

const num = (v) => (v === undefined || v === true ? undefined : Number(v));

function relationSummary(r, sourceLabel = (t) => t) {
  return {
    text: r.text,
    from: `${r.key.fromTable}.${r.key.fromColumn}`,
    to: `${r.key.toTable}.${r.key.toColumn}`,
    confidence: r.confidence,
    band: r.band,
    tier: r.tier,
    solid: r.solid,
    schemaAligned: r.schemaAligned,
    needsReview: r.needsReview,
    sources: r.sources,
    /** 与 sources 同序的中文展示名（`scoring.sourceLabels`）；旧产物没 legend 时原样回显枚举名 */
    sourcesZh: (r.sources ?? []).map(sourceLabel),
    ambiguity: r.ambiguity ? `${r.ambiguity.outcome}（${(r.ambiguity.targets ?? []).join(' / ')}）` : null,
    locations: r.locations ?? [],
  };
}

function out(data) {
  process.stdout.write(JSON.stringify(data, null, 2) + '\n');
}

function main() {
  const { cmd, rest, flags } = parseArgs(process.argv.slice(2));
  const fsPath = flags['fact-source'] || process.env.DATAMODEL_FACT_SOURCE || discoverDefaultFactSource();
  let fs;
  try {
    fs = loadFactSource(fsPath);
  } catch (e) {
    fail(e instanceof FactSourceError ? e.message : `加载失败：${e.message}`, 1);
  }

  switch (cmd) {
    case 'overview':
      return out({ factSource: fsPath, ...fs.overview() });

    case 'tables': {
      if (flags.table) {
        const t = fs.getTable(String(flags.table));
        return t ? out({ table: t }) : fail(`没有这张表：${flags.table}`, 3);
      }
      const tables = fs.listTables({ module: flags.module, name: flags.name, detail: !!flags.detail, limit: num(flags.limit) ?? 50, offset: num(flags.offset) ?? 0 });
      return out({ totalTables: fs.doc.tables.length, matched: tables.length, tables });
    }

    case 'relations': {
      const relations = fs.listRelations({
        table: flags.table, end: flags.end, band: flags.band, tier: flags.tier,
        source: flags.source, minConfidence: num(flags.min), solid: flags.solid === undefined ? undefined : flags.solid === 'true',
        needsReview: flags['needs-review'] === undefined ? undefined : flags['needs-review'] === 'true',
        schemaAligned: flags.aligned === undefined ? undefined : flags.aligned === 'true',
        limit: num(flags.limit) ?? 50, offset: num(flags.offset) ?? 0,
      });
      return out({
        totalRelations: fs.doc.relations.length,
        matched: relations.length,
        relations: relations.map((r) => relationSummary(r, (t) => fs.sourceLabel(t))),
      });
    }

    case 'explain': {
      let relation;
      if (flags.end) {
        const [t, ...c] = String(flags.end).split('.');
        const cs = fs.relationsByEnd(t, c.join('.'));
        if (!cs.length) return fail(`没有以 ${flags.end} 为 from 端的关系`, 3);
        relation = cs[0];
      } else if (rest[0]) {
        relation = fs.getRelation(rest[0]);
        if (!relation) return fail(`没有这条关系：${rest[0]}`, 3);
      } else return fail('explain 要 <relation-text> 或 --end 表.列', 2);
      return out({ relation, conflicts: fs.conflictsForRelation(relation), scoring: fs.scoring });
    }

    case 'conflicts': {
      const conflicts = fs.listConflicts({
        type: flags.type, severity: flags.severity, subject: flags.subject,
        subjectContains: flags['subject-contains'],
        needsReview: flags['needs-review'] === undefined ? undefined : flags['needs-review'] === 'true',
        limit: num(flags.limit) ?? 100, offset: num(flags.offset) ?? 0,
      });
      return out({ totalConflicts: fs.doc.conflicts.length, matched: conflicts.length, conflicts });
    }

    // LLM 兜底的目标集：规则引擎判 UNRESOLVED 的多义列，按 from 端聚合，附引用代码位置（去读这些文件定案）
    case 'ambiguous': {
      const limit = num(flags.limit) ?? 50;
      const unres = fs.doc.relations.filter((r) => r.ambiguity?.outcome === 'UNRESOLVED');
      const byEnd = new Map();
      for (const r of unres) {
        const fe = `${r.key.fromTable}.${r.key.fromColumn}`;
        if (!byEnd.has(fe)) byEnd.set(fe, { fromEnd: fe, targets: r.ambiguity.targets, candidates: [], codeLocations: [] });
        const g = byEnd.get(fe);
        g.candidates.push({ to: `${r.key.toTable}.${r.key.toColumn}`, confidence: r.confidence });
        for (const loc of r.locations ?? []) if (!g.codeLocations.includes(loc)) g.codeLocations.push(loc);
      }
      const groups = [...byEnd.values()].slice(0, limit);
      return out({
        factSource: fsPath,
        note: '这些列 schema 侧四条依据 + 代码侧类型提示都选不出落点。读 codeLocations 指向的文件（看 import 的实体家族 / Service 调的是哪个 API）定案，再用 record-inferred.mjs 写 overlay。',
        unresolvedFromEnds: byEnd.size,
        needsReviewRelations: fs.doc.relations.filter((r) => r.needsReview).length,
        returned: groups.length,
        ambiguous: groups,
      });
    }

    default:
      return fail(
        `未知命令 ${cmd ?? '（空）'}。可用：overview | tables | relations | explain | conflicts | ambiguous\n` +
        `  query.mjs relations --end trade_order.user_id\n` +
        `  query.mjs explain trade_order.user_id->member_user.id\n` +
        `  query.mjs ambiguous --limit 20\n` +
        `  --fact-source <path> 覆盖事实源（默认自动发现 build/datamodel/*-datamodel.json）`,
        2,
      );
  }
}

try {
  main();
} catch (e) {
  fail(e?.message ?? String(e), 1);
}
