package org.dw.datamodelrobot.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * `Data Model` 工具窗口。
 *
 * [DumbAware]：面板只读磁盘上的 JSON 产物，不碰 PSI 与索引，索引期间照样能开、能浏览。
 * 只有「跳到证据」那一步需要索引，届时 [EvidenceNavigator] 自己会说明并拒绝，不会抛异常；
 * 「扫描并加载」按钮则需要索引（要 resolve 方法引用），索引期间它自己会灰掉。
 *
 * [shouldBeAvailable] 恒真：没扫描过时面板显示的是操作指引而不是消失 ——
 * 一个会时有时无的工具窗口比一个空面板更难发现。
 */
class DataModelToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DataModelPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    companion object {

        /** 必须与 `plugin.xml` 里 `<toolWindow id="…">` 一致（描述符里引用不了常量，只能两边对齐） */
        const val ID = "Data Model"

        /**
         * 扫描完成后由 `ScanDataModelAction` 调用，把已打开的面板刷到最新产物。
         *
         * 用 `contentManagerIfCreated` 而不是 `contentManager`：后者会**顺手把内容创建出来**，
         * 于是「用户从没打开过这个工具窗口」也会因为一次扫描而平白构造一套 Swing 组件。
         * 没创建过就什么都不做 —— 面板 `init` 里本来就会读一遍磁盘。
         */
        fun reloadIfOpen(project: Project) {
            val manager = ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManagerIfCreated ?: return
            for (content in manager.contents) {
                (content.component as? DataModelPanel)?.reload()
            }
        }
    }
}
