#!/usr/bin/env node
// 把一条 LLM 兜底推断的关系写进**独立 overlay 文件**，绝不碰事实源。
// 硬性不变量（AGENTS.md / PLAN §5）：type 强制 inferred、confidence 封顶 0.6、必须带证据链。
// overlay 与事实源分文件 → 可单独过滤、删文件即回滚，规则引擎的事实源永远干净。
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';

const CAP = 0.6; // LLM 推断的置信度天花板，与 0.60（MEDIUM 下限 / Mermaid 实线 / 命名证据 base）同一条线
const OVERLAY_FORMAT = 'datamodelrobot-inferred/1';

function fail(msg, code = 2) {
  process.stderr.write(`[record-inferred] ${msg}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const flags = {};
  const evidence = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--evidence') { evidence.push(argv[++i]); continue; }
    if (a.startsWith('--evidence=')) { evidence.push(a.slice('--evidence='.length)); continue; }
    if (a.startsWith('--')) {
      const eq = a.indexOf('=');
      if (eq >= 0) flags[a.slice(2, eq)] = a.slice(eq + 1);
      else if (argv[i + 1] && !argv[i + 1].startsWith('--')) flags[a.slice(2)] = argv[++i];
      else flags[a.slice(2)] = true;
    }
  }
  return { flags, evidence };
}

/** "file:line: note" / "file:line" / "note" → 结构化证据。 */
function parseEvidence(s) {
  const m = /^(.*?):(\d+):\s*(.+)$/.exec(s);
  if (m) return { file: m[1], line: Number(m[2]), note: m[3] };
  const m2 = /^(.*?\.[A-Za-z]+):(\d+)$/.exec(s);
  if (m2) return { file: m2[1], line: Number(m2[2]) };
  return { note: s };
}

function parseEnd(s, label) {
  if (typeof s !== 'string' || !s.includes('.')) fail(`${label} 要写成 "表.列"，收到：${JSON.stringify(s)}`);
  const i = s.indexOf('.');
  const table = s.slice(0, i).trim();
  const column = s.slice(i + 1).trim();
  if (!table || !column) fail(`${label} 的表名/列名不能空：${JSON.stringify(s)}`);
  return { table, column };
}

function overlayPathFor(factSourcePath, explicitOut) {
  if (explicitOut) return resolve(String(explicitOut));
  const dir = dirname(resolve(factSourcePath));
  const base = basename(factSourcePath);
  const name = base.endsWith('-datamodel.json')
    ? base.slice(0, -'-datamodel.json'.length) + '-inferred.json'
    : base.replace(/\.json$/, '') + '.inferred.json';
  return resolve(dir, name);
}

function readOverlay(p, project, factSourcePath) {
  if (existsSync(p)) {
    try {
      const o = JSON.parse(readFileSync(p, 'utf8'));
      if (o && Array.isArray(o.relations)) return o;
    } catch { /* 损坏就重建，overlay 是可丢弃的旁路文件 */ }
  }
  return {
    format: OVERLAY_FORMAT,
    project,
    factSource: factSourcePath,
    capConfidence: CAP,
    note: `LLM 兜底推断的关系，独立于事实源。可单独过滤；删掉本文件即回滚。绝不并进 ${basename(factSourcePath)}。`,
    generatedAt: new Date().toISOString(),
    relations: [],
  };
}

function main() {
  const { flags, evidence } = parseArgs(process.argv.slice(2));
  const factSourcePath = resolve(String(flags['fact-source'] || process.env.DATAMODEL_FACT_SOURCE || ''));

  // --list：只读 overlay，不需要事实源
  if (flags.list) {
    const p = flags.out ? resolve(String(flags.out)) : (flags['fact-source'] ? overlayPathFor(factSourcePath) : null);
    if (!p) return fail('--list 需要 --fact-source 或 --out 来定位 overlay');
    if (!existsSync(p)) return process.stdout.write(JSON.stringify({ overlay: p, relations: [] }, null, 2) + '\n');
    return process.stdout.write(JSON.stringify({ overlay: p, ...JSON.parse(readFileSync(p, 'utf8')) }, null, 2) + '\n');
  }

  if (!flags['fact-source'] && !process.env.DATAMODEL_FACT_SOURCE) {
    return fail('需要 --fact-source <path>（用来定位 overlay、读 project 名，并保证不写到事实源上）');
  }
  if (!existsSync(factSourcePath)) return fail(`事实源不存在：${factSourcePath}`, 1);

  const outPath = overlayPathFor(factSourcePath, flags.out);
  // 安全闸：overlay 绝不能是事实源本身
  if (outPath === factSourcePath) return fail(`拒绝：overlay 路径与事实源相同（${outPath}），会覆盖事实源`, 1);

  let project = 'unknown';
  try { project = JSON.parse(readFileSync(factSourcePath, 'utf8')).project || 'unknown'; } catch { /* 用 unknown 兜底 */ }

  // ── 校验输入（硬不变量）──
  const from = parseEnd(flags.from, '--from');
  const to = parseEnd(flags.to, '--to');
  const confidence = Number(flags.confidence);
  if (!Number.isFinite(confidence) || confidence <= 0) return fail(`--confidence 要是 (0, ${CAP}] 的数，收到：${JSON.stringify(flags.confidence)}`);
  if (confidence > CAP) {
    return fail(
      `--confidence ${confidence} 超过封顶 ${CAP}。LLM 兜底推断不许高于规则引擎给「只有命名证据」的分（${CAP}）——` +
      `比这更确信就该是规则引擎能算出来的，不该靠 LLM。请下调或改用代码侧证据让规则引擎消歧。`,
      1,
    );
  }
  const rationale = String(flags.rationale || '').trim();
  if (!rationale) return fail('--rationale 不能空：要说清凭什么这么判（读了哪个文件、import 了什么、调了哪个 API）');
  const evList = evidence.map((e) => parseEvidence(String(e))).filter((e) => e.file || e.note);
  if (!evList.length) return fail('至少给一条 --evidence "文件:行号: 说明"——推断必须写回证据链，否则不可追溯');

  const text = `${from.table}.${from.column}->${to.table}.${to.column}`;
  const resolvesFromEnd = flags.resolves ? String(flags.resolves) : `${from.table}.${from.column}`;
  const record = {
    type: 'inferred', // 强制：永远标 inferred，消费方据此过滤/回滚
    text,
    key: { fromTable: from.table, fromColumn: from.column, toTable: to.table, toColumn: to.column },
    confidence,
    band: confidence >= CAP ? 'MEDIUM' : 'LOW',
    solid: false, // 推断关系一律不画实线
    needsReview: true, // 一律需人复核
    resolvesFromEnd,
    rationale,
    evidence: evList,
    inferredBy: { layer: 'phase3-skill', by: flags.by ? String(flags.by) : 'llm-fallback', at: new Date().toISOString() },
  };

  const overlay = readOverlay(outPath, project, factSourcePath);
  const i = overlay.relations.findIndex((r) => r.text === text);
  const isNew = i < 0;
  if (isNew) overlay.relations.push(record);
  else overlay.relations[i] = record;
  overlay.relations.sort((a, b) => a.text.localeCompare(b.text)); // 排序 → overlay 也可 diff
  overlay.generatedAt = new Date().toISOString();

  writeFileSync(outPath, JSON.stringify(overlay, null, 2) + '\n');

  process.stdout.write(
    `[record-inferred] ${isNew ? '记录' : '更新'} inferred 关系 ${text}（confidence ${confidence}，封顶 ${CAP}）\n` +
    `  overlay: ${outPath}（共 ${overlay.relations.length} 条 inferred）\n` +
    `  事实源未改动: ${factSourcePath}\n` +
    `  回滚: 删除 overlay 文件即可\n`,
  );
}

try {
  main();
} catch (e) {
  fail(e?.message ?? String(e), 1);
}
