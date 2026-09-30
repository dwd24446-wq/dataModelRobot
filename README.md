# dataModelRobot

IntelliJ IDEA 插件：把 Spring Boot（**MyBatis-Plus 技术栈**）项目的**数据模型**从代码与数据库里抽出来，
产出表信息、推断出的表关系，以及每条关系的**置信度 + 证据链 + 冲突清单**。

> 实际项目普遍不建外键，表关系只能从业务代码推断。而研发一旦误解数据关系就会写出错代码 ——
> 所以本工具不满足于「给一个答案」，而是给**「这个答案有多可信、依据是哪几行代码、依据之间是否自洽」**。

**支持的技术栈**：MyBatis-Plus + MyBatis-Plus-Join（MPJ）+ dynamic-datasource + Lombok。
不做通用 ORM —— JPA / jOOQ / QueryDSL / JdbcTemplate 的解析面是无底洞，架构留了 Parser SPI 扩展点。

---

## 解决什么问题

```
relation: system_users.id ← t_order.user_id
evidence:
  - {type: mpj_join,          weight: .95, ref: OrderMapper.java:88}
  - {type: lambda_eq,         weight: .85, ref: OrderServiceImpl.java:142}
  - {type: naming_convention, weight: .60}
conflicts:
  - {type: missing_tenant_scope, ref: OrderServiceImpl.java:142}
confidence: 0.71
```

置信度表达的不是「我多确信这条关系存在」，而是**「证据之间是否自洽」**。
若代码本身写错了，从代码推断出的关系就是错的 —— 所以每条关系都存证据列表与冲突列表，分数由两者算出，
**不让 LLM 直接吐分数**（不可复现、不可追溯）。

检测的冲突类型（按价值排序）：类型不匹配（`bigint` vs `varchar`，隐式转换 + 索引失效）、
租户/逻辑删除隔离缺失（这类脚手架的通病）、多义指向（`user_id` 同时匹配 `system_users` / `member_user`）、
双向不一致、孤儿字段。

## 四层架构

只有第一层是重活，其余三层都很薄：

```
IDEA 插件（解析层）→ JSON 事实源 → MCP Server（极薄接口层）→ Skill（编排 + LLM 兜底）
```

| 层 | 位置 | 职责 |
|---|---|---|
| **① 插件** | `src/main/kotlin/` | PSI 扫代码、DatabaseTools 读 schema、规则引擎算证据与冲突，输出 JSON + Mermaid |
| **② JSON 事实源** | `build/datamodel/<项目名>-datamodel.json` | **唯一事实源**，可 diff；分数只在这里算一次 |
| **③ MCP Server** | `mcp-server/` | Node 实现，只读 JSON，暴露 4 个只读工具 |
| **④ Skill** | `skills/explore-data-model/` | 编排 MCP；处理规则引擎搞不定的模糊关系 |

Mermaid 是默认视图（`||..o{` 虚线表达低置信度）；DBML 有意没做 —— 它的关系语法不支持样式，表达不了置信度。

**LLM 兜底的硬约束**：推断结果必须写回证据链、标为 `inferred`、**置信度封顶 0.6**，且写进**独立的 overlay 文件**，
避免幻觉污染事实源。这些不变量由脚本强制，不靠模型自觉。

## 环境要求

| 项 | 值 |
|---|---|
| 运行插件的 IDE | **IntelliJ IDEA 2026.2+**（build **262+**，统一版含免费层）——这是 `verifyPlugin` 实测与 Marketplace 版本页给出的唯一支持范围。<br>**DataGrip 不支持**：卡在 `<depends>com.intellij.java</depends>`（插件要扫 Java 源码，这条依赖去不掉，属内在限制）；**IDEA Community 不支持**：缺 `com.intellij.database`；PyCharm / WebStorm / GoLand / CLion 两条都缺 |
| 看关系图 | 另需 IDE 自带的 **Mermaid** 插件（没被声明成 `<depends>`，缺了只是不出图，插件照常工作） |
| 构建期 JDK | **JDK 25**（toolchain）。平台 262 的 jar 是 Java 25 字节码，toolchain 低于 25 会硬失败 |
| Gradle | wrapper **9.7.1**（`./gradlew`，无需本地安装） |
| MCP Server | Node **≥ 20** |
| 最终用户 | **不需要装任何 JDK** —— 插件跑在 IDE 自带的 JBR 里 |

技术栈：`org.jetbrains.intellij.platform` 2.19.0 · Kotlin 2.4.0（对齐平台内置 stdlib）· 字节码 major 69。
`verifyPlugin` 实测：IU-262（编译目标）与 IU-263 均 Compatible，且具备 **Dynamic Plugin Eligibility**（免重启启用/禁用）。

构建期还需要本机的 IDE 安装路径 —— `build.gradle.kts` 里用 `local(...)` 指向它，换成你自己的路径。

## 快速开始

```bash
./gradlew buildPlugin     # 产物 build/distributions/dataModelRobot-1.0.1.zip（~593KB）
./gradlew runIde          # 起沙箱 IDE 直接试
```

装好插件后，在**目标项目**里：

1. **配一次数据源**（一次性）：Database 工具窗口 → `+` → Data Source → MySQL。
   ⚠️ 在 **Schemas 标签页里只勾选目标那一个库** —— 放任 introspect 整个服务器，插件会把所有库的表都拉进来，
   catalog 也会退化成数据源名。等 introspection 转完。驱动由 IDE 提供，插件不 bundle 任何 JDBC 驱动。
2. **扫描**：底部 `Data Model` 工具窗口 → 工具栏 **扫描并加载**（▶），或菜单 `Tools → Scan Data Model`（同一条实现）。
   索引没建完时按钮是灰的。
3. **看结果**：扫完面板自动加载。树有三个根：**业务范围（用户自建）** / **表前缀模块（自动）** / **冲突**，
   前两个下面统一是 `表 → 关系 → 证据`。一张表挂着它**两端**的全部关系（指出去的和被指向的都算），
   所以从「只被别人引用」的表出发也能看到谁引用了它。**双击证据跳到写它的那行 Java**；
   下半屏给算式、惩罚系数与人话理由（表节点还给字段清单）。低于 0.60 的表与关系整行灰掉。
4. **搜表**：树上方的搜索框按**表名 / 库注释 / 实体 Javadoc** 模糊匹配（搜「订单」能找到 `trade_order` 这类表），
   命中后过滤整棵树并自动摊开、选中第一个；清空即恢复。
5. **圈业务范围**：工具栏 **新建业务范围**，再选中表点 **把选中表加入业务范围**（列表末尾可就地新建）。
   范围存 `.idea/dataModelRobot.xml`，**不进扫描产物**，所以重扫不会丢；范围里引用了、最新事实源里已没有的表
   标 `[已失效]` 而**不静默删掉**。右键范围可重命名/删除，右键范围里的表可移除。
6. **看图**：工具栏 **打开 Mermaid 关系图** → 选一个模块，右侧预览由 IDE 自带 Mermaid 插件渲染。
   表框里是「表名 + 中文注释」，注释优先取数据库表注释、缺失时用实体类 Javadoc 首行。
   **双击表或业务范围**可打开带字段的 ER 图，以及非模态选择窗口；勾选表、调整最低置信度或邻域方向后实时更新预览。
   业务范围最多 25 张表；单表邻域最多 25 个邻居，超出时按置信度选择并在图头注明。
7. **不知道怎么用**：工具栏 **使用方法**（?）—— 内置帮助弹窗，含 MCP 与 Skill 的装载步骤。

产物落在 `<项目根>/build/datamodel/`：

| 产物 | 说明 |
|---|---|
| `<项目名>-datamodel.json` | JSON 事实源：表、关系、证据、冲突、置信度、按表组织的邻接视图 `adjacency`（`out`/`in` 两桶，从 `relations` 派生、带 mermaid 基数记号），以及 `scoring` 段（把公式与系数写进产物，下游不许另立一套）。每张表两段注释：`comment`（库里的表注释）+ `entityDoc`（实体类 Javadoc） |
| `mermaid/<模块>.mmd` | 按模块分的 ER 图（这类脚手架的表前缀天然是模块边界）。图里含本模块全部表：没推出关系的以带中文注释的空框补齐，跨模块的落点也照样标中文名 |
| `adhoc/*.mmd` | 双击表或业务范围生成的带字段 ER 图，含字段类型、注释与 PK/UK/FK 标记；选择窗口只调整这张图，不改持久化业务范围 |

**读图**：`被指向的表 ||--o{ 持有外键的表 : "外键列 置信度"`；**实线 = 置信度 ≥ 0.60（可采信），虚线 = 去核对一眼**。
表名那行是 mermaid 的实体别名 `表名["表名 中文注释"]`，注释来源优先级 = 数据库表注释 → 实体类 Javadoc 首行，
杂质会被洗掉（引号、方括号、`%` 这类会让整张图渲染失败的字符一律剔掉，超 30 字符截断）。
基数从 schema 推导，不是猜的。**模块图不画字段，单表邻域与业务范围图带字段**。
Mermaid 的 `maxTextSize`（50,000 字符）是 secure 配置项，文件内 directive 抬不动。
带字段图使用 49,000 字符预算：完整字段与注释 → 省略字段注释 → 仅保留主键及关系字段；
仍超预算则按置信度裁剪关系，所有降级与裁剪均在图头注明。

## MCP Server（让 AI 客户端直接查事实源）

```bash
cd mcp-server && npm install
```

4 个只读工具：`get_tables` / `get_relations` / `explain_relation` / `list_conflicts`。
**只读、不连库、不重新打分** —— 置信度与消歧结论全用插件算好的值。

事实源默认自动发现 `build/datamodel/*-datamodel.json`，也可用 `--fact-source` 或环境变量 `DATAMODEL_FACT_SOURCE` 指定。
注册方式与 JSON 片段见 `mcp-server/README.md`。

## Skill（编排 + LLM 兜底）

Skill 源码在与 `mcp-server/` 平级的 `skills/explore-data-model/`，随仓库分发。按所用 AI 客户端的
Skill 机制注册或链接该目录；命令触发和自然语言自动触发是否可用取决于客户端。
它优先调用 MCP 工具；未注册 MCP 时，可运行 Skill 内的 `node scripts/query.mjs` 查询同一份事实源。
若客户端把 Skill 复制到仓库外，运行查询脚本时设置 `DATAMODELROBOT_ROOT` 为本仓库根目录，以定位 `mcp-server` 数据层。

## 已知边界（有意取舍，不是待办）

- **首期只支持 MyBatis-Plus 技术栈**（MyBatis-Plus + MyBatis-Plus-Join + dynamic-datasource + Lombok）。
  JPA / jOOQ / QueryDSL / JdbcTemplate 的解析面是无底洞；架构留了 Parser SPI 扩展点。
- **模块图不画字段**；单表邻域与业务范围图带字段，受表数量和 Mermaid 字符预算约束。
- **不出全库总览图**：一张图装全库必然超 `maxTextSize`，而表框加上中文注释后更装不下。模块图合起来就是全库，
  跨模块要看全貌时看事实源 JSON 的 `tables` / `relations` 或工具窗口。
- **插件内不自渲染图**，交给 IDE 自带 Mermaid 插件 —— 省 5.1MB 的 `mermaid.js` 与 JCEF 依赖，
  `plugin.xml` 也不新增 `<depends>`（缺 Mermaid 插件只是不出图，不会整体加载失败）。
- **默认不画「代码引用了、库里没有的表」**（ER 图描述的是*这个库*）。它们仍在事实源里，
  标 `schemaAligned=false` 且分数封顶在虚线区 —— 代码与库漂移本身就是有价值的信号。
- **关系是从代码推断的，不是数据库声明的**。低分的结论要人看一眼，工具不替你做决定。
- **测试与验收夹具不随仓库分发**：本仓库只发布插件本体、MCP Server 与 Skill。

## 文档导航

| 文档 | 内容 |
|---|---|
| `README.md`（本文） | 是什么、怎么装、怎么跑 |
| `AGENTS.md` | **环境事实与硬约束**：JDK/Gradle/网络实测结论、四条硬约束、`plugin.xml` 描述符踩过的坑 |
| `mcp-server/README.md` | MCP Server 的工具签名与注册方式 |
| `skills/explore-data-model/SKILL.md` | Skill 的编排规则与 LLM 兜底不变量 |

## 许可

Apache License 2.0 —— 见 [LICENSE](LICENSE)。
