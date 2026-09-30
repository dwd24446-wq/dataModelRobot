import { readFileSync } from 'node:fs';

/** 事实源加载/查询层的错误。工具处理器把它转成 isError 结果，不让进程崩。 */
export class FactSourceError extends Error {}

const REQUIRED_FORMAT = 'datamodelrobot/2';

function push(map, key, value) {
  const list = map.get(key);
  if (list) list.push(value);
  else map.set(key, [value]);
}

/** 把 "table.column" 归一成小写，用于大小写不敏感的端点匹配。 */
function endKey(table, column) {
  return `${table}.${column}`.toLowerCase();
}

/**
 * 读 + 校验事实源 JSON。一次性载入内存（大型项目约 2MB 量级），之后全是内存查询。
 * 校验只验「MCP 依赖的结构」：format 标记 + 三个数组在。字段级缺失按「可能没有」处理，
 * 与事实源「null 字段不输出」的约定一致（见 FactSource.kt）。
 */
export function loadFactSource(path) {
  let raw;
  try {
    raw = readFileSync(path, 'utf8');
  } catch (e) {
    throw new FactSourceError(`读不到事实源文件 ${path}：${e.message}`);
  }
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch (e) {
    throw new FactSourceError(`事实源不是合法 JSON（${path}）：${e.message}`);
  }
  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
    throw new FactSourceError(`事实源顶层必须是对象（${path}）`);
  }
  if (doc.format !== REQUIRED_FORMAT) {
    throw new FactSourceError(
      `事实源 format 不是 ${REQUIRED_FORMAT}（实际 ${JSON.stringify(doc.format)}）—— 版本不匹配，重跑插件管线再试`,
    );
  }
  for (const key of ['tables', 'relations', 'conflicts']) {
    if (!Array.isArray(doc[key])) {
      throw new FactSourceError(`事实源缺少 ${key} 数组（${path}）`);
    }
  }
  return new FactSource(doc, path);
}

/**
 * 事实源的只读视图 + 查找索引。所有查询方法返回事实源里的**原对象**（不复制、不改写），
 * 外加 total/returned 分页元信息。表名、列名、端点匹配一律大小写不敏感。
 */
export class FactSource {
  constructor(doc, path) {
    this.doc = doc;
    this.path = path;
    this.format = doc.format;
    this.project = doc.project;
    this.generatedAt = doc.generatedAt;
    this.source = doc.source ?? null;
    this.stats = doc.stats ?? {};
    this.scoring = doc.scoring ?? null;
    /**
     * @type {Record<string, string>} 证据源枚举名 → 中文展示名（`scoring.sourceLabels`）。
     * 旧产物没有这段时是空对象，展示口径退化成枚举名本身。
     */
    this.sourceLabels = (this.scoring && this.scoring.sourceLabels) || {};
    this.modules = Array.isArray(doc.modules) ? doc.modules : [];

    /** @type {Map<string, object>} 小写表名 → table */
    this.tablesByName = new Map();
    for (const t of doc.tables) {
      if (t && typeof t.name === 'string') this.tablesByName.set(t.name.toLowerCase(), t);
    }

    /** @type {Map<string, object>} relation.text → relation */
    this.relationsByText = new Map();
    /** @type {Map<string, object[]>} 小写 fromTable → relations */
    this.relationsByFromTable = new Map();
    /** @type {Map<string, object[]>} 小写 toTable → relations */
    this.relationsByToTable = new Map();
    /** @type {Map<string, object[]>} 小写 "fromTable.fromColumn" → relations */
    this.relationsByFromEnd = new Map();
    for (const r of doc.relations) {
      if (!r || !r.key) continue;
      if (typeof r.text === 'string') this.relationsByText.set(r.text, r);
      const { fromTable, fromColumn, toTable } = r.key;
      if (fromTable) push(this.relationsByFromTable, fromTable.toLowerCase(), r);
      if (toTable) push(this.relationsByToTable, toTable.toLowerCase(), r);
      if (fromTable && fromColumn) push(this.relationsByFromEnd, endKey(fromTable, fromColumn), r);
    }

    /** @type {Map<string, object[]>} conflict.subject（原样）→ conflicts */
    this.conflictsBySubject = new Map();
    for (const c of doc.conflicts) {
      if (c && typeof c.subject === 'string') push(this.conflictsBySubject, c.subject, c);
    }
  }

  // ── 概览 ──────────────────────────────────────────────────────────────

  /** 顶层元信息 + 统计 + 打分口径 + 模块清单。get_tables 不带过滤时回这个，省一个工具。 */
  overview() {
    return {
      format: this.format,
      project: this.project,
      generatedAt: this.generatedAt,
      source: this.source,
      stats: this.stats,
      scoring: this.scoring,
      modules: this.modules.map((m) => ({ name: m.name, tables: (m.tables ?? []).length })),
      counts: {
        tables: this.doc.tables.length,
        relations: this.doc.relations.length,
        conflicts: this.doc.conflicts.length,
      },
    };
  }

  // ── 展示口径 ──────────────────────────────────────────────────────────

  /** 证据源的中文展示名。legend 缺失或类型不认识时原样返回枚举名（不编造翻译）。 */
  sourceLabel(type) {
    return this.sourceLabels[type] ?? type;
  }

  /** `命名约定（NAMING_CONVENTION）` 形式的串，给工具描述与结果里的可读列表用。 */
  describeSources(types) {
    return (types ?? []).map((t) => `${this.sourceLabel(t)}（${t}）`).join('、');
  }

  // ── 表 ────────────────────────────────────────────────────────────────

  getTable(name) {
    if (typeof name !== 'string') return undefined;
    return this.tablesByName.get(name.toLowerCase());
  }

  /**
   * 列表/过滤表。name 是大小写不敏感的**子串**匹配，module 是精确匹配。
   * 不带 detail 时只回摘要（名/模块/注释/列数），带 detail 回整张表（列/索引/外键）。
   */
  listTables({ module, name, detail = false, limit = 50, offset = 0 } = {}) {
    let all = this.doc.tables;
    if (module) all = all.filter((t) => t.module === module);
    if (name) {
      const needle = name.toLowerCase();
      all = all.filter((t) => (t.name ?? '').toLowerCase().includes(needle));
    }
    all = [...all].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''));
    return page(all, limit, offset).map((t) => (detail ? t : tableSummary(t)));
  }

  // ── 关系 ──────────────────────────────────────────────────────────────

  getRelation(text) {
    if (typeof text !== 'string') return undefined;
    return this.relationsByText.get(text);
  }

  /** 按 from 端 "表.列" 找候选关系（消歧竞争的所有落点都在这）。 */
  relationsByEnd(table, column) {
    return this.relationsByFromEnd.get(endKey(table, column)) ?? [];
  }

  /**
   * 过滤关系。table 匹配 fromTable **或** toTable（大小写不敏感精确）；end 匹配 from 端 "表.列"；
   * minConfidence/band/tier/solid/needsReview/schemaAligned/source 各按字面过滤。
   * 默认按 confidence 降序、同分按 text，所以「指向哪张表」的答案里胜出的排第一。
   */
  listRelations(opts = {}) {
    const {
      table, end, minConfidence, maxConfidence, band, tier,
      solid, needsReview, schemaAligned, source,
      limit = 50, offset = 0,
    } = opts;
    let all;
    if (end) {
      const [t, ...rest] = String(end).split('.');
      all = rest.length ? this.relationsByEnd(t, rest.join('.')) : (this.relationsByFromTable.get(t.toLowerCase()) ?? []);
    } else if (table) {
      const key = String(table).toLowerCase();
      const seen = new Set();
      all = [];
      for (const r of [...(this.relationsByFromTable.get(key) ?? []), ...(this.relationsByToTable.get(key) ?? [])]) {
        if (!seen.has(r)) { seen.add(r); all.push(r); }
      }
    } else {
      all = this.doc.relations;
    }
    if (typeof minConfidence === 'number') all = all.filter((r) => r.confidence >= minConfidence);
    if (typeof maxConfidence === 'number') all = all.filter((r) => r.confidence <= maxConfidence);
    if (band) all = all.filter((r) => r.band === band);
    if (tier) all = all.filter((r) => r.tier === tier);
    if (typeof solid === 'boolean') all = all.filter((r) => r.solid === solid);
    if (typeof needsReview === 'boolean') all = all.filter((r) => r.needsReview === needsReview);
    if (typeof schemaAligned === 'boolean') all = all.filter((r) => r.schemaAligned === schemaAligned);
    if (source) all = all.filter((r) => Array.isArray(r.sources) && r.sources.includes(source));
    all = [...all].sort(byConfidenceThenText);
    return page(all, limit, offset);
  }

  // ── 冲突 ──────────────────────────────────────────────────────────────

  /**
   * 过滤冲突。type/severity 精确，needsReview 布尔，subject 精确，subjectContains 子串。
   * 默认按 type、severity、subject 排序（与事实源里的顺序一致）。
   */
  listConflicts({ type, severity, needsReview, subject, subjectContains, limit = 100, offset = 0 } = {}) {
    let all = this.doc.conflicts;
    if (type) all = all.filter((c) => c.type === type);
    if (severity) all = all.filter((c) => c.severity === severity);
    if (typeof needsReview === 'boolean') all = all.filter((c) => c.needsReview === needsReview);
    if (subject) all = all.filter((c) => c.subject === subject);
    if (subjectContains) {
      const needle = String(subjectContains).toLowerCase();
      all = all.filter((c) => (c.subject ?? '').toLowerCase().includes(needle));
    }
    return page(all, limit, offset);
  }

  /**
   * 一条关系关联的冲突。subject 约定（见 FactSource.kt）：①④用完整 key（= relation.text），
   * ③⑤用 "表.列"（= from 端）。②隔离缺失按「文件:行号 方法 → 表.列」编址、是查询链路的问题，
   * 不归到单条关系上（与「②不折算进置信度」同一立场），用 list_conflicts 单独看。
   */
  conflictsForRelation(relation) {
    if (!relation || !relation.key) return [];
    const out = [];
    const byText = this.conflictsBySubject.get(relation.text);
    if (byText) out.push(...byText);
    const fromEnd = `${relation.key.fromTable}.${relation.key.fromColumn}`;
    const byEnd = this.conflictsBySubject.get(fromEnd);
    if (byEnd) for (const c of byEnd) if (!out.includes(c)) out.push(c);
    return out;
  }

  /** 去重的枚举值，给工具 schema 的描述用（冲突类型、档位、tier、证据源）。 */
  enums() {
    const uniq = (arr) => [...new Set(arr.filter(Boolean))].sort();
    return {
      conflictTypes: uniq(this.doc.conflicts.map((c) => c.type)),
      severities: uniq(this.doc.conflicts.map((c) => c.severity)),
      bands: uniq(this.doc.relations.map((r) => r.band)),
      tiers: uniq(this.doc.relations.map((r) => r.tier)),
      sources: uniq(this.doc.relations.flatMap((r) => r.sources ?? [])),
      modules: uniq(this.modules.map((m) => m.name)),
    };
  }
}

// ── 内部小工具 ────────────────────────────────────────────────────────────

function page(arr, limit, offset) {
  const start = Math.max(0, offset | 0);
  const end = limit && limit > 0 ? start + (limit | 0) : arr.length;
  return arr.slice(start, end);
}

function byConfidenceThenText(a, b) {
  return (b.confidence ?? 0) - (a.confidence ?? 0) || (a.text ?? '').localeCompare(b.text ?? '');
}

function tableSummary(t) {
  return {
    name: t.name,
    module: t.module,
    comment: t.comment ?? null,
    /** 实体类 Javadoc 首行；comment 缺失时它是表名之外的中文名 */
    entityDoc: t.entityDoc ?? null,
    columns: (t.columns ?? []).length,
    indexes: (t.indexes ?? []).length,
    foreignKeys: (t.foreignKeys ?? []).length,
  };
}
