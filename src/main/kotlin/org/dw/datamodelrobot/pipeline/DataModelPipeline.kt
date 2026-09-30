package org.dw.datamodelrobot.pipeline

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import org.dw.datamodelrobot.conflict.Conflict
import org.dw.datamodelrobot.conflict.ConflictDetector
import org.dw.datamodelrobot.conflict.IsolationScopeCollector
import org.dw.datamodelrobot.evidence.CodeEvidenceCollector
import org.dw.datamodelrobot.evidence.ColumnReferenceIndex
import org.dw.datamodelrobot.evidence.KeyTupleEvidenceCollector
import org.dw.datamodelrobot.evidence.NamingEvidenceCollector
import org.dw.datamodelrobot.evidence.TypeHint
import org.dw.datamodelrobot.evidence.TypeHintIndex
import org.dw.datamodelrobot.output.FactSource
import org.dw.datamodelrobot.output.Mermaid
import org.dw.datamodelrobot.phase0.EntityIndex
import org.dw.datamodelrobot.phase0.Phase0Result
import org.dw.datamodelrobot.phase0.Phase0Scan
import org.dw.datamodelrobot.relation.RelationGraph
import org.dw.datamodelrobot.relation.RelationInference
import org.dw.datamodelrobot.relation.TargetResolver
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.score.ConfidenceScorer
import org.dw.datamodelrobot.score.ScoredGraph
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 全管线跑一次的结果。Action（DatabaseTools schema）与 headless 测试（JsonDump schema）
 * 共用同一条编排，避免两边的组装顺序漂移。纯计算、不含 IO；落盘见 [DataModelPipeline.writeArtifacts]。
 */
data class PipelineResult(
    val schema: DbSchema,
    val index: EntityIndex,
    val scan: Phase0Result,
    val graph: RelationGraph,
    val refs: ColumnReferenceIndex.Result,
    val isolation: List<Conflict>,
    val hints: Map<String, TypeHint>,
    val conflicts: List<Conflict>,
    val scored: ScoredGraph,
    val elapsedMs: Long,
) {
    /** schema 侧四条依据（不含代码侧类型提示）能消歧的多义列数 —— 用来量化决策②翻案了多少。 */
    val baselineResolved: Int
        get() = graph.competing().count { (_, list) -> TargetResolver.resolve(list).resolved }

    /** schema 侧四条依据各自选出的落点，用来对照代码侧提示推翻了哪些（两套依据打架时必须看得见）。 */
    val baselineWinners: Map<String, String?>
        get() = graph.competing().mapValues { (_, list) -> TargetResolver.resolve(list).winner?.key?.toEnd }
}

/** 落盘的标准产物：事实源 JSON + 按模块分的 Mermaid 图（模块图合起来就是全库，不再出总览图）。 */
data class WrittenArtifacts(
    val jsonFile: Path,
    val json: String,
    val mermaidDir: Path,
    val diagrams: Map<String, String>,
)

object DataModelPipeline {

    /**
     * 跑完整管线：schema → 实体索引 → 证据（代码 / 命名 / 键元组）→ 关系聚合 → 引用索引 →
     * 隔离缺失 → 代码侧类型提示 → 冲突 → 置信度。**必须在 ReadAction 里**（全程访问 PSI）。
     *
     * schema 由调用方提供：生产走 `DatabaseToolsSchemaProvider`，headless 测试走 `JsonDumpSchemaProvider`。
     * 组装顺序与各步的依赖一致，调整顺序前先确认下游步骤拿到的仍是它需要的输入。
     */
    fun run(project: Project, schema: DbSchema): PipelineResult {
        val started = System.currentTimeMillis()
        val index = EntityIndex.build(project)
        val code = CodeEvidenceCollector.collect(project, index, schema)
        val graph = RelationInference.infer(
            code.evidences + NamingEvidenceCollector.collect(schema) + KeyTupleEvidenceCollector.collect(schema),
            schema,
        )
        val scan = Phase0Scan.scan(project)
        val refs = ColumnReferenceIndex.scan(project, scan.records)
        val isolation = IsolationScopeCollector.collect(code.evidences, scan.records, index, schema)
        // 决策②：schema 侧消歧不了的跨模块通用名，靠「引用这一列的文件 import 了哪个实体家族」翻案
        val groups = graph.competing().mapValues { (_, v) -> v.map { it.key.toTable } }
        val hints = TypeHintIndex.collect(project, scan.records, index, groups)
        val conflicts = ConflictDetector.detect(graph, schema, refs, index, isolation, hints)
        val scored = ConfidenceScorer.score(graph, conflicts, hints)
        return PipelineResult(
            schema, index, scan, graph, refs, isolation, hints, conflicts, scored,
            System.currentTimeMillis() - started,
        )
    }

    fun runInReadAction(project: Project, schema: DbSchema): PipelineResult =
        ReadAction.computeBlocking<PipelineResult, RuntimeException> { run(project, schema) }

    /**
     * 把管线结果写成标准产物。Action 与测试共用，保证两条路径产出字节一致的同一份事实源。
     * Mermaid 目录每次先清空：模块集合会随过滤规则变化，留着旧文件会让人以为还在出图。
     */
    fun writeArtifacts(
        result: PipelineResult,
        projectName: String,
        outDir: Path,
        jsonFileName: String = "$projectName-datamodel.json",
        generatedAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        mermaidTitle: String? = projectName,
    ): WrittenArtifacts {
        Files.createDirectories(outDir)
        val docs = result.index.docsByTable
        val json = FactSource.render(result.schema, projectName, generatedAt, result.scored, result.conflicts, docs)
        val jsonFile = outDir.resolve(jsonFileName)
        Files.writeString(jsonFile, json)
        val mermaidOptions = Mermaid.Options(title = mermaidTitle, entityDocs = docs)
        val diagrams = Mermaid.byModule(result.scored.relations, result.schema, mermaidOptions)
        val mermaidDir = outDir.resolve("mermaid")
        if (Files.isDirectory(mermaidDir)) mermaidDir.toFile().listFiles()?.forEach { it.delete() }
        Files.createDirectories(mermaidDir)
        diagrams.forEach { (module, text) -> Files.writeString(mermaidDir.resolve("$module.mmd"), text) }
        return WrittenArtifacts(jsonFile, json, mermaidDir, diagrams)
    }
}
