# dataModelRobot MCP Server

四层架构的第三层：**极薄、只读**的 MCP 接口层。读 IDEA 插件产出的 JSON 事实源，把它暴露成 4 个工具，零业务逻辑、不连数据库、不改写任何文件。

```
IDEA 插件（解析层）→ JSON 事实源 → 【本 MCP Server】→ Skill（编排 + LLM 兜底）
```

## 跑起来

```bash
cd mcp-server
npm install                 # 一次性，装 @modelcontextprotocol/sdk

# 手动启动（一般不用，由 MCP 客户端拉起）：
node src/index.js --fact-source ../build/datamodel/<项目名>-datamodel.json
```

事实源路径优先级：`--fact-source <path>`（或 `--fact-source=<path>`）→ 环境变量 `DATAMODEL_FACT_SOURCE` → 自动发现 `../build/datamodel/` 下的 `*-datamodel.json`（恰好一份就直接用；多份取字典序第一个并在 stderr 告警）。

事实源由插件生成：在 IDE 里点 `Tools → Scan Data Model`，产出落 `<项目根>/build/datamodel/`。

`package.json` 里也声明了 `bin.datamodelrobot-mcp`，所以 `npm link` 之后可以直接敲 `datamodelrobot-mcp`（参数同上）；`npm start` 等价于 `node src/index.js`。

## 注册到 MCP 客户端

stdio 传输。按所用客户端的方式注册 MCP Server；下面是支持 `mcpServers` 配置的示例，**路径换成你机器上的绝对路径**：

```json
{
  "mcpServers": {
    "datamodelrobot": {
      "command": "node",
      "args": [
        "<项目根>/mcp-server/src/index.js"
      ]
    }
  }
}
```

不带 `--fact-source` 时自动发现 `<项目根>/build/datamodel/` 下的事实源；要钉死某一份就把它作为 `--fact-source` 的值追加到 `args` 里。

启动日志走 **stderr**（`[datamodelrobot-mcp] 已加载 …`），stdout 严格留给 JSON-RPC——事实源加载失败时进程非零退出、错误只进 stderr，绝不污染 stdout。

## 4 个工具

| 工具 | 回答什么 | 主要参数 |
|---|---|---|
| `get_tables` | 库里有哪些表 / 某张表的列·索引·外键 / 不带参数则回整个库的概览（统计 + 打分口径 + 各模块表数） | `table`（精确名看详情）、`module`、`name`（子串）、`detail`、`limit/offset` |
| `get_relations` | 表与表之间的推断关系（带置信度），按置信度降序，胜出的落点排第一 | `table`（from 或 to 端）、`end`（`表.列`）、`band`、`tier`、`source`、`minConfidence`、`solid`、`needsReview`、`schemaAligned` |
| `explain_relation` | 一条关系的**完整证据链**：每处代码证据（文件:行号）、置信度算式、消歧结论、schema 信号、关联冲突，并附上打分公式 | `relation`（完整 text）或 `end`（`表.列`，取最高分落点） |
| `list_conflicts` | 数据模型里的冲突/不自洽处 | `type`、`severity`、`needsReview`、`subject`、`subjectContains` |

工具描述里内插了运行时枚举值（模块、冲突类型、档位、tier、证据源），LLM 选参数时不用猜。

### 典型问答

> **「trade_order.user_id 指向哪张表、证据是什么、有哪些冲突？」**
> 1. `get_relations {end: "trade_order.user_id"}` → 落点按置信度排序，第一个是 `member_user.id`
> 2. `explain_relation {relation: "trade_order.user_id->member_user.id"}` → 证据链 + `0.46 = base 0.600 ×0.80 ×0.95` 算式 + 消歧结论 + 关联冲突
> 3.（可选）`list_conflicts {subjectContains: "trade_order.user_id"}` → 这一列上的全部冲突

## 边界

- **只读**：不写事实源、不连库、不执行任何 DDL/DML。
- **不重新打分**：置信度、档位、消歧结论全部来自事实源里插件算好的值；`scoring` 段把公式与系数一并透出，LLM 不能另立一套分数（Phase 3 的兜底推断封顶 0.6 也是据此）。
- **未对齐关系照样返回**：`schemaAligned=false` 的关系（代码引用了库里没有的表/列）不藏，带 `needsReview` 标记返回，由调用方判断。
- 事实源格式必须是 `datamodelrobot/2`（`/1` 的旧产物没有 `adjacency` 段），否则启动即报错 —— 版本不匹配时在 IDE 里重跑 `Tools → Scan Data Model`。
