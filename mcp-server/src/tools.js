import { z } from 'zod';
import { FactSourceError } from './factSource.js';

/** 统一的结果封装：数据以 JSON 文本回给客户端（LLM 直接读结构化数据）。 */
function json(data) {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}

function fail(message) {
  return { content: [{ type: 'text', text: message }], isError: true };
}

/** get_relations 列表里每条关系的紧凑投影（完整证据链交给 explain_relation）。 */
function relationSummary(r, fs) {
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
    /** 与 sources 同序的中文展示名，回答用户时直接用它，不用再翻枚举 */
    sourcesZh: (r.sources ?? []).map((s) => fs.sourceLabel(s)),
    ambiguity: r.ambiguity ? `${r.ambiguity.outcome}（${(r.ambiguity.targets ?? []).join(' / ')}）` : null,
  };
}

/**
 * 在一个 McpServer 上注册 4 个只读工具。全部是 [FactSource] 查询层之上的薄处理器：
 * 不含业务逻辑、不改写数据， FactSourceError 转成 isError 结果而不是让进程崩。
 * 工具描述里内插了运行时枚举值（冲突类型 / 档位 / tier / 证据源），LLM 选参数时不用猜。
 */
export function registerTools(server, fs) {
  const e = fs.enums();

  server.registerTool(
    'get_tables',
    {
      title: '列出 / 查看表',
      description:
        `列出数据库的表（可按模块或名字子串过滤），或给一个精确表名看它的列/索引/外键全貌。` +
        `不带任何参数时返回这个库的概览（项目、统计、打分口径、各模块表数）。` +
        `已知模块：${e.modules.join(', ') || '（无）'}。`,
      inputSchema: {
        table: z.string().optional().describe('精确表名（大小写不敏感）。给了就返回这张表的完整详情，忽略其它过滤'),
        module: z.string().optional().describe(`按模块精确过滤，如 trade / member / system。可选：${e.modules.join(', ')}`),
        name: z.string().optional().describe('按表名子串过滤（大小写不敏感），如 user / order'),
        detail: z.boolean().optional().describe('列表模式下是否返回整张表（列/索引/外键）。默认 false，只回摘要'),
        limit: z.number().int().min(1).max(500).optional().describe('最多返回多少张表，默认 50'),
        offset: z.number().int().min(0).optional().describe('跳过前多少张，默认 0'),
      },
    },
    (args) => {
      try {
        if (args.table) {
          const t = fs.getTable(args.table);
          if (!t) return fail(`没有这张表：${args.table}`);
          return json({ project: fs.project, table: t });
        }
        if (!args.module && !args.name && !args.detail) {
          // 无任何过滤 → 概览模式，省得 LLM 再找一个 stats 工具
          return json(fs.overview());
        }
        const tables = fs.listTables(args);
        return json({
          project: fs.project,
          totalTables: fs.doc.tables.length,
          matched: tables.length,
          tables,
        });
      } catch (err) {
        return fail(errorMessage(err));
      }
    },
  );

  server.registerTool(
    'get_relations',
    {
      title: '查表关系',
      description:
        `查表与表之间的推断关系（带置信度）。可按某张表（from 或 to 端）、按一个 "表.列" 外键端、` +
        `按档位/置信度/证据源过滤。结果按置信度降序，所以「这列指向哪张表」的答案里胜出的排第一。` +
        `拿完整证据链与冲突用 explain_relation。档位=${e.bands.join('/')}；tier=${e.tiers.join('/')}；证据源=${fs.describeSources(e.sources)}。`,
      inputSchema: {
        table: z.string().optional().describe('表名（大小写不敏感）：匹配 from 端或 to 端含有这张表的关系'),
        end: z.string().optional().describe('"表.列" 外键端，如 trade_order.user_id：返回这一列的所有竞争落点'),
        band: z.enum(bandEnum(e.bands)).optional().describe('按置信度档位过滤：HIGH / MEDIUM / LOW'),
        tier: z.enum(tierEnum(e.tiers)).optional().describe('按支撑档位过滤：CODE_BACKED / SCHEMA_STRONG / WEAK'),
        source: z
          .string()
          .optional()
          .describe(`按证据源过滤，取值必须是枚举名本身（中文标签只用于展示）。可选：${e.sources.join(', ')}`),
        minConfidence: z.number().min(0).max(1).optional().describe('只返回 confidence ≥ 此值的关系（0~1）'),
        solid: z.boolean().optional().describe('true 只要实线（confidence ≥ 0.60，可采信）；false 只要虚线（需核对）'),
        needsReview: z.boolean().optional().describe('true 只要标了需人工复核的关系'),
        schemaAligned: z.boolean().optional().describe('false 只要「代码引用了库里没有的表/列」的未对齐关系'),
        limit: z.number().int().min(1).max(500).optional().describe('最多返回多少条，默认 50'),
        offset: z.number().int().min(0).optional().describe('跳过前多少条，默认 0'),
      },
    },
    (args) => {
      try {
        const relations = fs.listRelations(args);
        return json({
          project: fs.project,
          totalRelations: fs.doc.relations.length,
          matched: relations.length,
          note: '按置信度降序。要某条的完整证据链/惩罚算式/冲突，用 explain_relation 传它的 text。',
          relations: relations.map((r) => relationSummary(r, fs)),
        });
      } catch (err) {
        return fail(errorMessage(err));
      }
    },
  );

  server.registerTool(
    'explain_relation',
    {
      title: '解释一条关系',
      description:
        `给一条关系的**完整证据链**：每处代码证据（文件:行号）、置信度算式（base × 各惩罚）、消歧结论、` +
        `schema 信号、以及关联的冲突（①类型不匹配 / ③多义指向 / ④双向 / ⑤孤儿）。` +
        `传 relation（完整 text，如 "trade_order.user_id->member_user.id"）精确取一条；` +
        `或传 end（"表.列"，如 trade_order.user_id）取该列置信度最高的那个落点。`,
      inputSchema: {
        relation: z.string().optional().describe('关系的完整 text，形如 "from表.from列->to表.to列"'),
        end: z.string().optional().describe('"表.列" 外键端；取该端置信度最高的一条来解释'),
      },
    },
    (args) => {
      try {
        let relation;
        if (args.relation) {
          relation = fs.getRelation(args.relation);
          if (!relation) {
            // 给点建设性的提示：是不是只传了 from 端？
            const hint = fs.doc.relations.filter((r) => r.text.startsWith(args.relation)).slice(0, 8).map((r) => r.text);
            return fail(`没有这条关系：${args.relation}${hint.length ? `\n你是不是要找：\n  ${hint.join('\n  ')}` : ''}`);
          }
        } else if (args.end) {
          const [t, ...rest] = String(args.end).split('.');
          if (!rest.length) return fail(`end 要写成 "表.列"，例如 trade_order.user_id（收到的没有列名）`);
          const candidates = fs.relationsByEnd(t, rest.join('.'));
          if (!candidates.length) return fail(`没有以 ${args.end} 为 from 端的关系`);
          relation = candidates[0]; // relationsByEnd 已按置信度降序
        } else {
          return fail('要么传 relation（完整 text），要么传 end（"表.列"）');
        }
        const conflicts = fs.conflictsForRelation(relation);
        return json({
          project: fs.project,
          relation,
          conflicts,
          conflictsNote:
            '②隔离缺失（tenant/deleted）按查询链路编址、不归到单条关系，用 list_conflicts 的 subjectContains 查。',
          scoring: fs.scoring,
        });
      } catch (err) {
        return fail(errorMessage(err));
      }
    },
  );

  server.registerTool(
    'list_conflicts',
    {
      title: '列冲突',
      description:
        `列数据模型里的冲突/不自洽处。可按类型、严重度、是否需复核、subject 过滤。` +
        `类型=${e.conflictTypes.join(', ')}；严重度=${e.severities.join(', ')}。` +
        `subject 约定：①类型不匹配/④双向用完整关系 text；③多义/⑤孤儿用 "表.列"；②隔离缺失用 "文件:行号 方法 → 表.列"。`,
      inputSchema: {
        type: z.string().optional().describe(`按冲突类型精确过滤。可选：${e.conflictTypes.join(', ')}`),
        severity: z.enum(severityEnum(e.severities)).optional().describe('按严重度过滤：HIGH / MEDIUM / LOW'),
        needsReview: z.boolean().optional().describe('true 只要标了需人工复核的冲突（工具下不了结论的那些）'),
        subject: z.string().optional().describe('按 subject 精确过滤'),
        subjectContains: z.string().optional().describe('按 subject 子串过滤（大小写不敏感），如某张表名或 "表.列"'),
        limit: z.number().int().min(1).max(1000).optional().describe('最多返回多少条，默认 100'),
        offset: z.number().int().min(0).optional().describe('跳过前多少条，默认 0'),
      },
    },
    (args) => {
      try {
        const conflicts = fs.listConflicts(args);
        return json({
          project: fs.project,
          totalConflicts: fs.doc.conflicts.length,
          matched: conflicts.length,
          byType: countBy(fs.doc.conflicts, (c) => c.type),
          conflicts,
        });
      } catch (err) {
        return fail(errorMessage(err));
      }
    },
  );
}

function countBy(arr, keyFn) {
  const out = {};
  for (const x of arr) {
    const k = keyFn(x);
    out[k] = (out[k] ?? 0) + 1;
  }
  return out;
}

function errorMessage(err) {
  if (err instanceof FactSourceError) return err.message;
  return err && err.message ? err.message : String(err);
}

// z.enum 要非空字面量数组；事实源里万一某维度为空，给个占位避免 z.enum([]) 抛错
function bandEnum(bands) {
  return bands.length ? bands : ['HIGH', 'MEDIUM', 'LOW'];
}
function tierEnum(tiers) {
  return tiers.length ? tiers : ['CODE_BACKED', 'SCHEMA_STRONG', 'WEAK'];
}
function severityEnum(sev) {
  return sev.length ? sev : ['HIGH', 'MEDIUM', 'LOW'];
}
