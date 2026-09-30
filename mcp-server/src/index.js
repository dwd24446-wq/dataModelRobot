#!/usr/bin/env node
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { loadFactSource, FactSourceError } from './factSource.js';
import { createServer } from './server.js';

/** 插件的 Scan Data Model 动作把事实源写到 `<项目根>/build/datamodel/`。 */
const DEFAULT_FACT_SOURCE_DIR = fileURLToPath(new URL('../../build/datamodel/', import.meta.url));

/**
 * 事实源文件名带项目名（`<项目名>-datamodel.json`），无法写死，所以按后缀自动发现。
 * 恰好一个就用它；多个取字典序第一个并告警；一个都没有（还没跑过扫描）就返回目录本身，
 * 让 loadFactSource 报错、由调用方提示用 --fact-source 指定。
 */
function discoverDefaultFactSource() {
  let hits;
  try {
    hits = readdirSync(DEFAULT_FACT_SOURCE_DIR).filter((f) => f.endsWith('-datamodel.json')).sort();
  } catch {
    return DEFAULT_FACT_SOURCE_DIR;
  }
  if (hits.length === 0) return DEFAULT_FACT_SOURCE_DIR;
  if (hits.length > 1) {
    console.error(`[datamodelrobot-mcp] ${DEFAULT_FACT_SOURCE_DIR} 下有 ${hits.length} 份事实源，默认取 ${hits[0]}；要指定别的用 --fact-source`);
  }
  return join(DEFAULT_FACT_SOURCE_DIR, hits[0]);
}

/** 事实源路径优先级：--fact-source <path> / --fact-source=<path> → DATAMODEL_FACT_SOURCE 环境变量 → build/datamodel/ 下自动发现。 */
function resolveFactSourcePath(argv) {
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--fact-source' && argv[i + 1]) return argv[i + 1];
    if (a.startsWith('--fact-source=')) return a.slice('--fact-source='.length);
  }
  return process.env.DATAMODEL_FACT_SOURCE || discoverDefaultFactSource();
}

async function main() {
  const path = resolveFactSourcePath(process.argv.slice(2));
  let fs;
  try {
    fs = loadFactSource(path);
  } catch (err) {
    // 只能写 stderr：stdout 是 JSON-RPC 通道，写进去会污染协议流
    const msg = err instanceof FactSourceError ? err.message : `加载事实源失败：${err?.message ?? err}`;
    console.error(`[datamodelrobot-mcp] ${msg}`);
    console.error(`[datamodelrobot-mcp] 找不到可用事实源（试过 ${path}）：先在 IDE 里跑 Tools → Scan Data Model，或用 --fact-source <path> / DATAMODEL_FACT_SOURCE 指定`);
    process.exit(1);
  }

  const server = createServer(fs);

  console.error(
    `[datamodelrobot-mcp] 已加载 ${path}：项目 ${fs.project}，表 ${fs.doc.tables.length} / 关系 ${fs.doc.relations.length} / 冲突 ${fs.doc.conflicts.length}`,
  );
  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  console.error(`[datamodelrobot-mcp] 致命错误：${err?.stack ?? err}`);
  process.exit(1);
});
