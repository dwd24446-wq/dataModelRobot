package org.dw.datamodelrobot.phase0

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Phase0Result(
    val projectName: String,
    val timestamp: String,
    val entityCount: Int,
    val columnCount: Int,
    val configSource: String,
    val totalReferences: Int,
    val records: List<RefRecord>,
    val sfunctionParamDeclarations: Int,
) {
    val inDenominator get() = records.filter { it.inDenominator }
    val resolvedToProperty get() = inDenominator.count { it.property != null }
    val mappedToColumn get() = inDenominator.count { it.success }
    val failures get() = records.filter { it.inDenominator && !it.success }
    val cTier get() = records.count { it.tier == RefTier.C_UNRESOLVED_GENERIC }
    val dTier get() = records.count { it.tier == RefTier.D_NON_ENTITY }
    val rTier get() = records.count { it.tier == RefTier.R_QUALIFIER_ONLY }
    val nonSFunction get() = records.count { it.tier == RefTier.NON_SFUNCTION }
    val noContext get() = records.count { it.tier == RefTier.NO_CONTEXT }
    val entitySourceCounts get() = records.groupingBy { it.entitySource ?: "-" }.eachCount()

    fun tierStats(): Map<RefTier, Pair<Int, Int>> =
        inDenominator.groupBy { it.tier }.mapValues { (_, rs) ->
            rs.count { it.success } to rs.size
        }

    fun scenarioStats(): Map<String, Triple<Int, Int, Int>> {
        // scenario -> (total, success, nonEntity)
        val map = LinkedHashMap<String, Triple<Int, Int, Int>>()
        for (r in records) {
            val key = r.scenario ?: continue
            val (t, s, d) = map[key] ?: Triple(0, 0, 0)
            map[key] = Triple(
                t + 1,
                s + if (r.inDenominator && r.success) 1 else 0,
                d + if (r.tier == RefTier.D_NON_ENTITY) 1 else 0,
            )
        }
        return map
    }
}

object Phase0Scan {

    /** 必须在 ReadAction 里调用（或由调用方包 ReadAction）。 */
    fun scan(project: Project): Phase0Result {
        val index = EntityIndex.build(project)
        val refs = SFunctionScan.collectReferences(project)
        val records = refs.map { SFunctionScan.classify(it, index) }
        val paramDecls = SFunctionScan.collectSFunctionParameterDeclarations(project)
        return Phase0Result(
            projectName = project.name,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            entityCount = index.entitiesByFqn.size,
            columnCount = index.entitiesByFqn.values.sumOf { it.columns.size },
            configSource = index.configSource,
            totalReferences = refs.size,
            records = records,
            sfunctionParamDeclarations = paramDecls,
        )
    }

    fun scanInReadAction(project: Project): Phase0Result =
        ReadAction.computeBlocking<Phase0Result, RuntimeException> { scan(project) }

    /**
     * 报告目录。传 [projectBasePath] 时把相对路径落到**项目根**，而不是 IDE 进程的工作目录 ——
     * 从 Finder/Dock 启动的 IDE 工作目录是 `/`，在那里建 `build/` 会失败或写到看不见的地方。
     */
    fun defaultReportDir(projectBasePath: String? = null): Path {
        val dir = Path.of(System.getProperty("phase0.reportDir") ?: "build/phase0-report")
        return if (dir.isAbsolute || projectBasePath == null) dir else Path.of(projectBasePath).resolve(dir)
    }

    fun writeReport(result: Phase0Result, reportDir: Path = defaultReportDir()): Path {
        Files.createDirectories(reportDir)
        val textFile = reportDir.resolve("phase0-report-${result.projectName}.txt")
        Files.writeString(textFile, renderText(result))
        val jsonFile = reportDir.resolve("phase0-records-${result.projectName}.json")
        Files.writeString(jsonFile, renderJson(result))
        return textFile
    }

    private fun pct(n: Int, d: Int): String =
        if (d == 0) "-" else "%.1f%%".format(100.0 * n / d)

    fun renderText(r: Phase0Result): String {
        val sb = StringBuilder()
        sb.appendLine("Phase 0 扫描报告 — 项目: ${r.projectName} — ${r.timestamp}")
        sb.appendLine("=".repeat(72))
        sb.appendLine("实体索引: ${r.entityCount} 个 @TableName 实体, ${r.columnCount} 个属性→列映射")
        sb.appendLine("MP 配置来源: ${r.configSource}")
        sb.appendLine()
        sb.appendLine("四组数字:")
        sb.appendLine("  1. 找到的方法引用总数:            ${r.totalReferences}")
        sb.appendLine("     （进分母的 SFunction 引用:     ${r.inDenominator.size}）")
        sb.appendLine("  2. 成功解析到 实体#属性:          ${r.resolvedToProperty}  ${pct(r.resolvedToProperty, r.inDenominator.size)}")
        sb.appendLine("  3. 成功映射到 表.列:              ${r.mappedToColumn}  ${pct(r.mappedToColumn, r.inDenominator.size)}")
        sb.appendLine("  4. 失败样本 (前 20):")
        val failures = r.failures
        if (failures.isEmpty()) {
            sb.appendLine("     （无失败）")
        } else {
            for (f in failures.take(20)) {
                sb.appendLine("     ${loc(f)} ${f.refText} [${f.scenario ?: "-"}] ${f.tier} — ${f.failureReason}")
            }
            if (failures.size > 20) sb.appendLine("     ... 另有 ${failures.size - 20} 条")
        }
        sb.appendLine()
        sb.appendLine("分档成功率 (A=类级泛型 B=方法级泛型 R=限定符兜底):")
        for ((tier, stat) in r.tierStats()) {
            sb.appendLine("  $tier: ${stat.first}/${stat.second}  ${pct(stat.first, stat.second)}")
        }
        sb.appendLine()
        sb.appendLine("实体来源分布: ${r.entitySourceCounts}")
        sb.appendLine()
        sb.appendLine("单列项 (不进分母):")
        sb.appendLine("  C 档 泛型未具体化引用:   ${r.cTier}")
        sb.appendLine("  C 档 SFunction 形参声明: ${r.sfunctionParamDeclarations} (定义处语境, 信息项)")
        sb.appendLine("  D 档 非实体(VO/BO)引用:  ${r.dTier}")
        sb.appendLine("  非 SFunction 引用:       ${r.nonSFunction}")
        sb.appendLine("  无调用语境引用:          ${r.noContext}")
        sb.appendLine()
        val scenarios = r.scenarioStats()
        if (scenarios.isNotEmpty()) {
            sb.appendLine("按场景 [S#] (总数/成功/非实体):")
            for ((s, t) in scenarios.toSortedMap(compareBy({ it.length }, { it }))) {
                sb.appendLine("  $s: ${t.first}/${t.second}/${t.third}")
            }
            sb.appendLine()
        }
        val getterFail = r.records.count { it.inDenominator && !it.getterResolved }
        sb.appendLine("命门 2 信号: 进分母引用中 getter 未 resolve 的数量 = $getterFail (Lombok light method 缺失的直接证据)")
        sb.appendLine()
        sb.appendLine("判据: >90% 进 Phase 1; 70~90% 看失败分布; <70% PSI 路线重估")
        sb.appendLine("本次 表.列 映射成功率: ${pct(r.mappedToColumn, r.inDenominator.size)}")
        return sb.toString()
    }

    private fun loc(r: RefRecord): String {
        val name = r.file.substringAfterLast('/')
        return "$name:${r.line}"
    }

    private fun renderJson(r: Phase0Result): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"project\": ${jsonStr(r.projectName)},")
        sb.appendLine("  \"timestamp\": ${jsonStr(r.timestamp)},")
        sb.appendLine("  \"totals\": { \"references\": ${r.totalReferences}, \"denominator\": ${r.inDenominator.size},")
        sb.appendLine("    \"resolvedProperty\": ${r.resolvedToProperty}, \"mappedColumn\": ${r.mappedToColumn},")
        sb.appendLine("    \"cTier\": ${r.cTier}, \"dTier\": ${r.dTier}, \"rTier\": ${r.rTier}, \"nonSFunction\": ${r.nonSFunction}, \"noContext\": ${r.noContext},")
        sb.appendLine("    \"sfunctionParamDeclarations\": ${r.sfunctionParamDeclarations} },")
        sb.appendLine("  \"records\": [")
        r.records.forEachIndexed { i, rec ->
            sb.append("    { \"file\": ${jsonStr(rec.file)}, \"line\": ${rec.line}, \"ref\": ${jsonStr(rec.refText)},")
            sb.append(" \"scenario\": ${rec.scenario?.let { jsonStr(it)} ?: "null"}, \"tier\": ${jsonStr(rec.tier.name)},")
            sb.append(" \"entity\": ${rec.entityFqn?.let { jsonStr(it) } ?: "null"}, \"property\": ${rec.property?.let { jsonStr(it) } ?: "null"},")
            sb.append(" \"table\": ${rec.table?.let { jsonStr(it) } ?: "null"}, \"column\": ${rec.column?.let { jsonStr(it) } ?: "null"},")
            sb.append(" \"getterResolved\": ${rec.getterResolved}, \"entitySource\": ${rec.entitySource?.let { jsonStr(it) } ?: "null"}, \"failure\": ${rec.failureReason?.let { jsonStr(it) } ?: "null"} }")
            if (i < r.records.size - 1) sb.appendLine(",") else sb.appendLine()
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
