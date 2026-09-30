package org.dw.datamodelrobot

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import org.dw.datamodelrobot.pipeline.DataModelPipeline
import org.dw.datamodelrobot.schema.DatabaseToolsSchemaProvider
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaUnavailableException
import org.dw.datamodelrobot.score.ConfidenceBand
import org.dw.datamodelrobot.toolwindow.DataModelToolWindowFactory
import java.nio.file.Path

/**
 * Tools 菜单入口：对当前打开的项目跑**完整数据模型管线**（schema → 证据 → 关系 → 冲突 → 置信度），
 * 产出 JSON 事实源 + 按模块分的 Mermaid 图（表框带中文注释）到 `<项目根>/build/datamodel/`。
 *
 * 与 headless 测试调同一条编排入口 [DataModelPipeline]，两条路径产出字节一致的同一份事实源。
 * schema 走 DatabaseTools（读 IDE 里已配置的数据源，驱动由 IDE 提供，插件不 bundle JDBC）；
 * 没配数据源时弹通知告诉用户怎么配，不静默失败。
 *
 * **菜单项与 `Data Model` 工具窗口的「扫描并加载」按钮是同一条实现**（见 [scan]），
 * 区别只是入口位置与标签；扫描完成后两边都会把已打开的面板刷新到新产物。
 */
class ScanDataModelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        scan(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        // 扫描要 resolve SFunction 方法引用的泛型实参，索引没建完跑出来的结果是残缺的（或直接抛
        // IndexNotReadyException），所以索引期间灰掉 —— 工具窗口本身是 DumbAware 的，用户很可能在索引期就点进来
        e.presentation.isEnabled = project != null && !DumbService.isDumb(project)
    }

    companion object {

        /**
         * 完整管线的唯一入口。跑完（**无论成功还是失败**）都刷一次已打开的面板：
         * - 成功 → 面板显示刚落盘的新产物，用户不必再手动点一次重载（原来那个「重载事实源」按钮因此被合并掉）
         * - 失败（没配数据源、introspection 没跑完、扫描报错）→ 通知已经说清原因，面板退回显示磁盘上次的产物，
         *   总比停在一份不知道新旧的数据上强
         *
         * `run` 内部把异常都吃掉了，所以 `onSuccess` 总会触发，这正是上面两条共用的落点。
         */
        fun scan(project: Project) {
            object : Task.Backgroundable(project, "DataModelRobot: 扫描数据模型", false) {
                override fun run(indicator: ProgressIndicator) {
                    // 报告落在项目根下的 build/，不依赖 IDE 进程的工作目录（从 Finder 启动时那是 /）
                    val outDir = Path.of(project.basePath ?: ".").resolve("build/datamodel")
                    try {
                        indicator.text = "读取数据库 schema（DatabaseTools 数据源）…"
                        val schema = ReadAction.computeBlocking<DbSchema, RuntimeException> {
                            DatabaseToolsSchemaProvider(project).load()
                        }
                        indicator.text = "扫描代码、推断关系、计算置信度…"
                        val result = DataModelPipeline.runInReadAction(project, schema)
                        indicator.text = "写出事实源与关系图…"
                        val artifacts = DataModelPipeline.writeArtifacts(result, project.name, outDir)

                        val st = result.scored.stats
                        val summary = "表 ${result.schema.stats.tables} · 关系 ${st.relations}" +
                            "（HIGH ${st.byBand[ConfidenceBand.HIGH] ?: 0} / 实线 ${st.solidLine} / 虚线 ${st.dashedLine}）" +
                            " · 冲突 ${result.conflicts.size} · 需复核 ${st.needsReview}" +
                            " · 耗时 ${result.elapsedMs}ms · 事实源 ${artifacts.jsonFile}" +
                            " · Mermaid 模块图 ${artifacts.diagrams.size} 张（表框带中文注释）"
                        notify(project, "数据模型扫描完成", summary, NotificationType.INFORMATION)
                    } catch (ex: SchemaUnavailableException) {
                        notify(
                            project,
                            "需要先配置数据源",
                            (ex.message ?: "") + "；配好后等 introspection 完成再跑本动作。" +
                                "点工具窗口的「使用方法」可以看完整步骤。",
                            NotificationType.WARNING,
                        )
                    } catch (ex: Exception) {
                        notify(project, "扫描失败", ex.message ?: ex.javaClass.simpleName, NotificationType.ERROR)
                    }
                }

                override fun onSuccess() = DataModelToolWindowFactory.reloadIfOpen(project)
            }.queue()
        }

        private fun notify(project: Project, title: String, content: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DataModelRobot")
                .createNotification(title, content, type)
                .notify(project)
        }
    }
}
