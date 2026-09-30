package org.dw.datamodelrobot.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import org.dw.datamodelrobot.ScanDataModelAction
import org.dw.datamodelrobot.output.FactSource
import org.dw.datamodelrobot.output.ErDiagram
import org.dw.datamodelrobot.schema.SchemaTables
import org.dw.datamodelrobot.scope.ScopeStore
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Data Model 工具窗口的主面板：读磁盘上的 JSON 事实源，按**双根树**组织（用户自建的业务范围 /
 * 表前缀模块），双击证据跳到 `文件:行号`。
 *
 * 四条设计取舍：
 * - **数据只来自事实源 JSON + `.idea` 里的业务范围**，不持有管线结果。产物落盘后重启 IDE 仍能看，
 *   也符合「JSON 是唯一事实源」；扫描完成后由扫描侧回调 [DataModelToolWindowFactory.reloadIfOpen]
 *   推一把，所以不需要单独的重载按钮
 * - **树的结构由 [DataModelTree] 这个纯函数算**，本类只负责 Swing —— 结构、排序、标记因此能 headless 断言，
 *   只有渲染与双击留给 `runIde` 人眼验
 * - **图不在这里渲染**。`.mmd` 交给 IDE 自带的 `com.intellij.mermaid` 插件（打开即编辑器+预览双栏），
 *   所以本插件不 bundle 5MB 的 mermaid.js、不依赖 JCEF、不新增 `<depends>`
 * - **面板的价值是导航不是画图**：从一张表 / 一条关系一路点到写它的那行 Java，这是图片给不了的
 */
class DataModelPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(DataModelPanel::class.java)
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val details = JBTextArea()
    private val status = JBLabel(" ")
    private val search = SearchTextField(false)
    private var view: FactSourceView? = null
    private var loadedFrom: Path? = null

    /** 搜索去抖：每敲一个字符就重建几千个节点太浪费 */
    private val searchTimer = Timer(SEARCH_DEBOUNCE_MS) { rebuildTree() }.apply { isRepeats = false }

    private val scopes: ScopeStore get() = project.service()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        // TreeSpeedSearch 留着：它搜的是**已渲染**的节点，与上面那个搜索框（能搜到没展开的表）互补
        TreeSpeedSearch.installOn(tree)
        tree.addTreeSelectionListener { details.text = detailsOf(selectedNode()) }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) jump()
            }

            override fun mousePressed(e: MouseEvent) = maybePopup(e)

            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) jump()
            }
        })

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = searchTimer.restart()
        })
        search.toolTipText = "按表名 / 库注释 / 实体 Javadoc 模糊搜索（能找到没展开在树里的表）；清空即恢复全树"

        details.isEditable = false
        details.lineWrap = false
        details.font = JBFont.small()
        details.background = JBColor.background()

        val splitter = JBSplitter(true, 0.6f)
        splitter.firstComponent = JBScrollPane(tree)
        splitter.secondComponent = JBScrollPane(details)

        add(north(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        reload()
    }

    private fun north(): JComponent = JPanel(BorderLayout()).apply {
        add(toolbar(), BorderLayout.NORTH)
        add(searchRow(), BorderLayout.SOUTH)
    }

    private fun searchRow(): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 6, 4, 6)
        add(JBLabel("搜表　"), BorderLayout.WEST)
        add(search, BorderLayout.CENTER)
    }

    private fun toolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(ScanAction())
            add(OpenDiagramAction())
            addSeparator()
            add(NewScopeAction())
            add(DeleteScopeAction())
            add(AddTableToScopeAction())
            addSeparator()
            add(HelpAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    // ---------- 装载 ----------

    fun reload() {
        val path = locateFactSource()
        if (path == null) {
            view = null
            loadedFrom = null
            treeModel.setRoot(DefaultMutableTreeNode())
            details.text = ""
            status.text = " "
            showEmptyState(
                "还没有数据模型产物。\n\n" +
                    "1. 先在 Database 工具窗口配一次数据源（Schemas 里只勾目标那一个库），等 introspection 转完\n" +
                    "2. 点左上角的「扫描并加载」（等同于 Tools 菜单的 Scan Data Model）\n\n" +
                    "产物会落到 <项目根>/build/datamodel/<项目名>-datamodel.json，扫完自动加载到本面板。\n" +
                    "详细步骤与 MCP / Skill 的装法见工具栏的「使用方法」。",
            )
            return
        }
        // 2MB 级事实源的解析放后台，别在 EDT 上卡一下
        object : Task.Backgroundable(project, "DataModelRobot: 读取事实源", false) {
            private var parsed: FactSourceView? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    parsed = FactSourceView.parse(Files.readString(path))
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                val v = parsed
                if (v == null) {
                    showEmptyState("事实源解析失败：$failure\n\n$path")
                    status.text = "解析失败"
                    return
                }
                view = v
                loadedFrom = path
                tree.emptyText.clear()
                rebuildTree()
            }
        }.queue()
    }

    /**
     * 用当前事实源 + 当前业务范围 + 搜索框里的词重建整棵树。
     *
     * 换根（`setRoot`）而不是清空再逐个插入：一次性发一个结构变更事件，比几千次插入便宜得多。
     */
    private fun rebuildTree() {
        val v = view ?: return
        val filter = search.text.trim()
        val root = DataModelTree.build(v, ScopeView.of(scopes.scopes, v.tableIndex.knownNames), filter)
        treeModel.setRoot(root)
        if (root.childCount > 0) tree.expandPath(TreePath(root.path))
        if (filter.isEmpty()) {
            status.text = statusLine(v)
        } else {
            // 搜索时把整棵（已经很小的）树摊开，并定位到第一个命中，省掉一路点开的手续
            expandAll()
            val hits = v.tableIndex.search(filter)
            status.text = "搜索「$filter」命中 ${hits.size} 张表（全库 ${v.tableIndex.size} 张）· " +
                "搜索时只显示表，冲突根已隐藏"
            hits.firstOrNull()?.let { selectTable(it.name) }
        }
    }

    private fun expandAll() {
        var row = 0
        // 展开会新增行，所以循环要跟着 rowCount 长
        while (row < tree.rowCount) tree.expandRow(row++)
    }

    private fun selectTable(name: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        val path = DataModelTree.pathTo(root) { it is TreeNode.Table && it.name.equals(name, ignoreCase = true) }
            ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun statusLine(v: FactSourceView): String {
        val base = "${v.project} · 表 ${v.tableCount} · 关系 ${v.relationCount}" +
            " · 冲突 ${v.conflictCount} · 需复核 ${v.needsReviewCount} · 生成于 ${v.generatedAt}"
        return if (v.formatMismatch) {
            "$base —— 产物格式 ${v.format}，当前插件是 ${FactSource.FORMAT}，建议重扫"
        } else {
            base
        }
    }

    /** 优先按项目名找；找不到就取目录里最新的 `*-datamodel.json`（改过项目名或手工拷来的产物） */
    private fun locateFactSource(): Path? {
        val base = project.basePath?.let { Path.of(it) } ?: return null
        val dir = base.resolve("build/datamodel")
        val expected = dir.resolve("${project.name}-datamodel.json")
        if (Files.isRegularFile(expected)) return expected
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith("-datamodel.json") }
                .max(Comparator.comparingLong { it.toFile().lastModified() })
                .orElse(null)
        }
    }

    private fun showEmptyState(message: String) {
        // setText 返回 StatusText 而非 void，Kotlin 不会合成属性赋值，只能显式调方法
        tree.emptyText.setText(message)
    }

    // ---------- 交互 ----------

    private fun selectedNode(): TreeNode? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode

    private fun jump() {
        when (val node = selectedNode()) {
            is TreeNode.Evidence -> {
                val ref = node.view.jump
                if (ref?.file == null) {
                    status.text = "这条证据没有代码出处（schema 侧推断）"
                } else {
                    reportFailure(EvidenceNavigator.navigate(project, ref.file, ref.line))
                }
            }
            is TreeNode.Ref -> reportFailure(EvidenceNavigator.navigate(project, node.label))
            is TreeNode.Table -> openNeighborhood(node.name)
            is TreeNode.Scope -> openScope(node.view)
            else -> status.text = "只有证据与冲突出处能跳转"
        }
    }

    private fun reportFailure(message: String?) {
        if (message != null) status.text = message
    }

    /** 右键菜单：范围的重命名/删除、范围里表的移除 —— 这些操作只对特定节点有意义，放工具栏会一直灰着 */
    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val group = DefaultActionGroup()
        when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode) {
            is TreeNode.Scope -> {
                group.add(RenameScopeAction(node.view))
                group.add(DeleteScopeByIdAction(node.view))
            }
            is TreeNode.Table -> {
                val scope = enclosingScope(path)?.view ?: return
                group.add(RemoveTableFromScopeAction(scope, node.name))
            }
            else -> return
        }
        val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group)
        popup.component.show(tree, e.x, e.y)
    }

    /** 表节点的父节点在业务范围根下时就是它所属的范围（模块根下的表没有「所属范围」） */
    private fun enclosingScope(path: TreePath): TreeNode.Scope? =
        (path.parentPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode.Scope

    // ---------- 业务范围的增删改 ----------

    private fun askScopeName(title: String, hint: String, initial: String = ""): String? {
        val input = Messages.showInputDialog(project, hint, title, AllIcons.General.Add, initial, NonBlankName())
        return input?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun createScope(): ScopeStore.Scope? {
        val name = askScopeName("新建业务范围", "业务范围的名字（之后在树里双击它出关系图）") ?: return null
        val scope = scopes.create(name)
        if (scope == null) {
            status.text = "名字不能为空"
            return null
        }
        rebuildTree()
        status.text = "已新建业务范围「${scope.name}」—— 选中表后点「把选中表加入业务范围」往里加表"
        selectScope(scope.id)
        return scope
    }

    private fun selectScope(id: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        val path = DataModelTree.pathTo(root) { it is TreeNode.Scope && it.view.id == id } ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun deleteScope(view: ScopeView) {
        val yes = Messages.showYesNoDialog(
            project,
            "删除业务范围「${view.name}」？\n\n只删这个范围本身，表、扫描产物与关系都不受影响。",
            "删除业务范围",
            "删除",
            "取消",
            Messages.getWarningIcon(),
        )
        if (yes != Messages.YES) return
        scopes.delete(view.id)
        rebuildTree()
        status.text = "已删除业务范围「${view.name}」"
    }

    private fun renameScope(view: ScopeView) {
        val name = askScopeName("重命名业务范围", "新名字", view.name) ?: return
        if (!scopes.rename(view.id, name)) {
            status.text = "改名失败：名字不能为空"
            return
        }
        rebuildTree()
        status.text = "已改名为「$name」"
    }

    private fun addSelectedTableToScope() {
        val table = selectedNode() as? TreeNode.Table ?: return
        val existing = scopes.scopes
        val choices = existing.map { ScopeChoice.Existing(it) as ScopeChoice } + ScopeChoice.New
        val dialog = ListChooserDialog(project, "把 ${table.name} 加入哪个业务范围？", choices, "加入") { c, r ->
            when (c) {
                is ScopeChoice.Existing -> {
                    r.append(c.scope.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    r.append("　已有 ${c.scope.tables.size} 张表", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                ScopeChoice.New -> r.append("＋ 新建业务范围…", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
        if (!dialog.showAndGet()) return
        when (val choice = dialog.selected()) {
            is ScopeChoice.Existing -> addToScope(choice.scope.id, choice.scope.name, table.name)
            ScopeChoice.New -> createScope()?.let { addToScope(it.id, it.name, table.name) }
            null -> Unit
        }
    }

    private fun addToScope(scopeId: String, scopeName: String, tableName: String) {
        val added = scopes.addTable(scopeId, tableName)
        rebuildTree()
        status.text = if (added) {
            "已把 $tableName 加入「$scopeName」"
        } else {
            "$tableName 已经在「$scopeName」里了"
        }
        selectScope(scopeId)
    }

    private fun removeTableFromScope(view: ScopeView, tableName: String) {
        scopes.removeTable(view.id, tableName)
        rebuildTree()
        status.text = "已从「${view.name}」移除 $tableName"
        selectScope(view.id)
    }

    /** 名字空白时让「确定」按钮直接灰掉，比点了再报错好 */
    private class NonBlankName : InputValidator {
        override fun checkInput(input: String): Boolean = input.isNotBlank()
        override fun canClose(input: String): Boolean = checkInput(input)
    }

    private sealed class ScopeChoice {
        data class Existing(val scope: ScopeStore.Scope) : ScopeChoice()
        data object New : ScopeChoice()
    }

    /** 打开某个模块的 `.mmd`，渲染由 IDE 自带的 mermaid 插件负责 */
    private fun openDiagram() {
        val dir = mermaidDir()
        val files = if (dir == null || !Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { s ->
                s.filter { it.fileName.toString().endsWith(".mmd") }.sorted().toList()
            }
        }
        if (files.isEmpty()) {
            status.text = "没有 .mmd 产物（${dir ?: "mermaid 目录不存在"}），先跑一次 Scan Data Model"
            return
        }
        // 用对话框而不是弹窗选择器：弹窗拖不宽，中文标签会被截断（见 ListChooserDialog 的注释）
        val dialog = ListChooserDialog(project, "打开哪张关系图？", files) { path, renderer ->
            renderer.append(path.fileName.toString().removeSuffix(".mmd"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            renderer.append("　模块图（表框带中文注释）", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (dialog.showAndGet()) dialog.selected()?.let { openDiagramFile(it) }
    }

    private fun openDiagramFile(mmd: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(mmd.toFile())
        if (vf == null) {
            status.text = "IDE 里看不到这个文件：$mmd"
        } else {
            FileEditorManager.getInstance(project).openFile(vf, true)
            status.text = "已打开 ${vf.name}（右侧预览由 IDE 自带 Mermaid 插件渲染）"
        }
    }

    private fun mermaidDir(): Path? = (loadedFrom ?: locateFactSource())?.parent?.resolve("mermaid")

    private fun openNeighborhood(name: String) {
        val v = view ?: return
        if (name.lowercase() !in v.tableIndex.knownNames) {
            status.text = "$name 不在当前库的表清单里，不能生成邻域图"
            return
        }
        val tables = ErDiagramData.tables(v)
        val relations = ErDiagramData.relations(v)
        val initial = ErDiagram.neighborhood(name, tables, relations, ErDiagram.Options(name))
        val candidates = relations.filter { it.fromTable.equals(name, true) || it.toTable.equals(name, true) }
            .flatMap { listOf(it.fromTable, it.toTable) }.map { it.lowercase() }.toSet() + name.lowercase()
        val choices = tables.filter { it.name.lowercase() in candidates }
        val path = adhocPath("table-$name") ?: return
        if (!writeAdhoc(path, initial.text)) return
        ErScopeDialog(project, "$name · 邻域图", choices, initial.tables, name) { selected, min, direction ->
            updateAdhoc(path) {
                ErDiagram.neighborhood(
                    name, tables, relations,
                    ErDiagram.Options(name, min, direction, selected - name.lowercase()),
                ).text
            }
        }.show()
    }

    private fun openScope(scope: ScopeView) {
        val v = view ?: return
        val tables = ErDiagramData.tables(v)
        val relations = ErDiagramData.relations(v)
        val known = v.tableIndex.knownNames
        val initial = scope.tables.filter { it.lowercase() in known }.take(ErDiagram.MAX_SCOPE_TABLES)
        val path = adhocPath("scope-${scope.id}") ?: return
        val options = ErDiagram.Options(scope.name, omittedTables = scope.tables.size - initial.size)
        if (!writeAdhoc(path, ErDiagram.scope(tables, relations, initial, options).text)) return
        ErScopeDialog(project, "${scope.name} · 业务范围图", tables, initial, null) { selected, min, _ ->
            updateAdhoc(path) {
                ErDiagram.scope(tables, relations, selected, options.copy(minConfidence = min)).text
            }
        }.show()
        if (scope.tables.size > initial.size) status.text = "范围中有 ${scope.tables.size - initial.size} 张失效或超出 25 张的表未画"
    }

    private fun adhocPath(name: String): Path? {
        val base = loadedFrom?.parent ?: return null
        val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return base.resolve("adhoc").resolve("$safe-${name.hashCode().toUInt().toString(16)}.mmd")
    }

    private fun writeAdhoc(path: Path, text: String, open: Boolean = true): Boolean = try {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            ?: error("IDE 无法定位 $path")
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
                ?: error("IDE 无法打开 $path 的 Document")
        }
        if (document.text != text) WriteIntentReadAction.run {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        }
        if (open) WriteIntentReadAction.run { FileEditorManager.getInstance(project).openFile(file, true) }
        status.text = "${if (open) "已打开" else "已更新"} ${file.name} · ${text.length} 字符"
        true
    } catch (e: Exception) {
        log.warn("Failed to write or open ad-hoc ER diagram at $path", e)
        status.text = "关系图写入失败：${e.message}"
        false
    }

    private fun updateAdhoc(path: Path, render: () -> String) {
        // Swing Timer 的回调不具备平台的 write-safe 上下文。
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                try {
                    writeAdhoc(path, render(), open = false)
                } catch (e: IllegalArgumentException) {
                    status.text = "关系图无法生成：${e.message}"
                }
            }
        }, ModalityState.nonModal())
    }

    // ---------- 详情 ----------

    private fun detailsOf(node: TreeNode?): String = when (node) {
        is TreeNode.ScopeRoot -> scopeRootDetails(node)
        is TreeNode.Scope -> scopeDetails(node.view)
        is TreeNode.ModuleRoot -> moduleRootDetails(node)
        is TreeNode.Module -> moduleDetails(node)
        is TreeNode.Table -> tableDetails(node)
        is TreeNode.Relation -> relationDetails(node.view)
        is TreeNode.Evidence -> evidenceDetails(node.view)
        is TreeNode.ConflictRoot -> "冲突 ${node.conflicts.size} 条。展开单条看详情，双击出处跳到代码。"
        is TreeNode.Conflict -> conflictDetails(node.view)
        is TreeNode.Ref -> "双击跳到 ${node.label}"
        null -> "选中一张表、一条关系或冲突看详情。\n双击证据 / 冲突出处可跳到对应的 文件:行号。\n" +
            "搜索框按表名 / 库注释 / 实体 Javadoc 模糊匹配，能找到没展开在树里的表。"
    }

    private fun scopeRootDetails(n: TreeNode.ScopeRoot): String = buildString {
        appendLine("业务范围（用户自建）—— ${n.count} 个")
        appendLine()
        appendLine("自己圈的一组表，用来看「只有这几张表」的关系图。")
        appendLine("存在项目级配置 .idea/dataModelRobot.xml，不进扫描产物，所以重扫不会丢。")
        appendLine()
        appendLine("工具栏：新建业务范围 / 删除业务范围 / 把选中表加入业务范围")
        appendLine("右键范围：重命名 / 删除；右键范围里的表：从范围移除")
        if (n.count == 0) {
            appendLine()
            appendLine("还没有范围。点工具栏的「新建业务范围」，再选中表点「把选中表加入业务范围」。")
        }
    }

    private fun scopeDetails(s: ScopeView): String = buildString {
        appendLine("业务范围  ${s.name}")
        appendLine("表 ${s.tables.size} 张" + if (s.missing.isEmpty()) "" else "，其中 ${s.missing.size} 张已失效")
        appendLine()
        appendLine("包含的表")
        if (s.tables.isEmpty()) appendLine("  （空 —— 选中表后点工具栏「把选中表加入业务范围」）")
        for (t in s.tables.sortedBy { it.lowercase() }) {
            appendLine(if (t in s.missing) "  $t　[已失效]" else "  $t")
        }
        if (s.missing.isNotEmpty()) {
            appendLine()
            appendLine("⚠ ${s.missing.size} 张表在最新事实源里找不到（改名、删表，或换了库）。")
            appendLine("  故意不自动删：悄悄丢掉的话你不会知道少了什么。确认不要了就右键逐张移除。")
        }
    }

    private fun moduleRootDetails(n: TreeNode.ModuleRoot): String = buildString {
        appendLine("表前缀模块（自动）—— ${n.count} 个模块")
        appendLine()
        appendLine("模块 = 表名第一个下划线之前的那段（system_ / infra_ / trade_ / …），无下划线的表自成模块。")
        appendLine("这类脚手架的表前缀天然是模块边界，所以不用自己发明分组。")
        appendLine()
        appendLine("注意与「业务范围」的区别：模块是按表名自动算出来的，范围是你自己圈的。")
    }

    private fun moduleDetails(m: TreeNode.Module): String = buildString {
        appendLine("模块 ${m.name} —— ${m.tableCount} 张表")
        appendLine()
        appendLine("本模块作为 from 端（持外键）的关系 ${m.ownRelations.size} 条：")
        for (band in listOf("HIGH", "MEDIUM", "LOW")) {
            val n = m.ownRelations.count { it.band == band }
            if (n > 0) appendLine("  $band  $n")
        }
        appendLine("  需复核  ${m.ownRelations.count { it.needsReview }}")
        appendLine("  未对齐 schema  ${m.ownRelations.count { !it.schemaAligned }}")
        appendLine()
        appendLine("展开模块看它下面的表；每张表挂着它全部的关系（指出去的和被指向的都算）。")
    }

    private fun tableDetails(t: TreeNode.Table): String = buildString {
        val v = t.view
        appendLine(t.name)
        v?.label?.let { appendLine("中文名  $it") }
        appendLine("模块    ${v?.module?.takeIf { it.isNotEmpty() } ?: SchemaTables.moduleOf(t.name)}")
        appendLine()
        if (t.stale) {
            appendLine("⚠ 这张表在最新事实源里找不到（既不在 tables 里，也没有任何关系提到它）。")
            appendLine("  多半是改名、删表或换了库。范围里仍保留着它，确认不要了就右键移除。")
            appendLine()
        } else if (v == null) {
            appendLine("⚠ 代码引用了这张表，但本库里没有它（schemaAligned=false 的关系端点）。")
            appendLine("  可能是跨库、表已删除，或代码里的表名写错了 —— 相关关系已被压分并标了 needsReview。")
            appendLine()
        }
        // 表名大小写在事实源里不保证统一（一半来自 @TableName、一半来自 introspection），比对口径要松
        val out = t.relations.count { it.fromTable.equals(t.name, ignoreCase = true) }
        val incoming = t.relations.count { it.toTable.equals(t.name, ignoreCase = true) }
        appendLine("关系 ${t.relations.size} 条（指出去 $out · 被指向 $incoming）")
        for (band in listOf("HIGH", "MEDIUM", "LOW")) {
            val n = t.relations.count { it.band == band }
            if (n > 0) appendLine("  $band  $n")
        }
        appendLine("  需复核  ${t.relations.count { it.needsReview }}")
        if (!t.anySolid && t.relations.isNotEmpty()) {
            appendLine("  ⚠ 全部低于 0.60 —— 树里整行灰显，这些关系都还得核对")
        }
        if (t.relations.isEmpty()) appendLine("  （没有任何推断关系）")

        if (v != null) {
            appendLine()
            appendLine("字段 ${v.columns.size} 个")
            v.comment?.let { appendLine("  表注释  $it") }
            v.entityDoc?.let { appendLine("  实体 Javadoc  $it") }
            for (c in v.columns) {
                val marks = listOfNotNull(
                    if (c.key.equals("PRI", ignoreCase = true)) "PK" else null,
                    if (c.key.equals("UNI", ignoreCase = true)) "UK" else null,
                )
                appendLine(
                    "  ${c.name}  ${c.fullType}" +
                        (if (c.nullable) "  null" else "") +
                        (if (marks.isEmpty()) "" else "  ${marks.joinToString(",")} ") +
                        (c.comment?.let { "  // $it" } ?: ""),
                )
            }
        }
    }

    private fun relationDetails(r: RelationView): String = buildString {
        appendLine("${r.fromTable}.${r.fromColumn}  →  ${r.toTable}.${r.toColumn}")
        appendLine()
        appendLine("置信度  ${num(r.confidence)}   档位 ${r.band}   " +
            if (r.solid) "实线（可采信）" else "虚线（低于 0.60，去核对一眼）")
        appendLine("算式    ${r.explain}")
        appendLine("支撑    ${r.tier} · schema ${if (r.schemaAligned) "已对齐" else "未对齐（表/列不在本库）"}" +
            " · 需复核 ${if (r.needsReview) "是" else "否"}")

        r.signals?.let { s ->
            appendLine()
            appendLine("信号")
            appendLine("  类型        ${s.fromType ?: "?"} → ${s.toType ?: "?"}   ${s.typeMatch}")
            appendLine("  落点        主键 ${yes(s.toIsPrimaryKey)} · 唯一键 ${yes(s.toIsUniqueKey)}")
            appendLine("  from 有索引 ${yes(s.fromIndexed)}   同模块 ${yes(s.sameModule)}" +
                "   公共前缀段数 ${s.sharedNameSegments}")
            appendLine("  在库        from ${yes(s.fromTableInSchema)} · to ${yes(s.toTableInSchema)}")
        }

        if (r.penalties.isNotEmpty()) {
            appendLine()
            appendLine("惩罚 (${r.penalties.size})")
            for (p in r.penalties) {
                appendLine("  ×${num(p.factor)}  ${p.trigger}")
                appendLine("      ${p.reason}")
            }
        }

        if (r.ambiguityOutcome != null) {
            appendLine()
            appendLine("多义指向  ${r.ambiguityOutcome}")
            appendLine("  候选 ${r.ambiguityTargets.joinToString(", ")}")
            r.ambiguityReason?.let { appendLine("  依据 $it") }
        }

        appendLine()
        appendLine("证据 (${r.evidences.size})")
        for (e in r.evidences) {
            appendLine("  ${e.typeLabel}  ${num(e.weight)}  ${e.jump?.location ?: "（无代码出处）"}")
            e.from.refText?.let { appendLine("      $it") }
        }

        if (r.reviewReasons.isNotEmpty()) {
            appendLine()
            appendLine("需复核理由")
            r.reviewReasons.forEach { appendLine("  - $it") }
        }
    }

    private fun evidenceDetails(e: EvidenceView): String = buildString {
        // 中文名给人读，括号里的枚举名留着对事实源与 MCP 的过滤参数
        appendLine("${e.typeLabel}（${e.type}）   权重 ${num(e.weight)}   schema ${if (e.schemaAligned) "已对齐" else "未对齐"}")
        appendLine()
        appendLine(side("from", e.from))
        appendLine(side("to  ", e.to))

        val ctx = listOfNotNull(
            e.joinType?.let { "JOIN $it" },
            e.callName?.let { "调用 $it" },
            e.enclosingMethod?.let { "所在方法 $it" },
            e.scenario?.let { "场景 $it" },
        )
        if (ctx.isNotEmpty()) {
            appendLine()
            appendLine(ctx.joinToString(" · "))
        }
        if (e.notes.isNotEmpty()) {
            appendLine()
            appendLine("备注")
            e.notes.forEach { appendLine("  - $it") }
        }
        if (e.jump == null) {
            appendLine()
            appendLine("这条证据来自 schema 侧推断，没有可跳转的代码位置。")
        }
    }

    private fun side(label: String, r: RefView): String = buildString {
        appendLine("$label  ${r.table}.${r.column}")
        r.entityFqn?.let { appendLine("      实体 $it") }
        appendLine("      出处 ${r.location ?: "（无）"}")
        r.refText?.let { appendLine("      代码 $it") }
    }

    private fun conflictDetails(c: ConflictView): String = buildString {
        appendLine("${c.type}   ${c.severity}")
        appendLine("对象  ${c.subject}")
        appendLine()
        appendLine(c.detail)
        c.resolution?.let {
            appendLine()
            appendLine("消歧结论  $it")
        }
        appendLine()
        appendLine("需复核  ${if (c.needsReview) "是（工具下不了结论，要人确认）" else "否"}")
        appendLine("出处 (${c.refs.size})")
        if (c.refs.isEmpty()) appendLine("  （无代码出处）") else c.refs.forEach { appendLine("  $it") }
    }

    private fun yes(b: Boolean): String = if (b) "是" else "否"

    // ---------- 渲染 ----------

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeNode) {
                is TreeNode.ScopeRoot -> {
                    icon = AllIcons.Nodes.Favorite
                    append("业务范围（用户自建）  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("(${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TreeNode.Scope -> {
                    icon = AllIcons.Nodes.Bookmark
                    val s = node.view
                    // 有失效表的范围整体压暗：一眼看出这个范围需要核对
                    val attrs = if (s.missing.isEmpty()) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
                    }
                    append(s.name, attrs)
                    append("  (${s.tables.size} 张表)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (s.missing.isNotEmpty()) {
                        append("  [${s.missing.size} 张已失效]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
                is TreeNode.ModuleRoot -> {
                    icon = AllIcons.Nodes.DataTables
                    append("表前缀模块（自动）  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("(${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TreeNode.Module -> {
                    icon = AllIcons.Nodes.Folder
                    append("${node.name}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("(${node.tableCount} 张表)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TreeNode.Table -> {
                    icon = AllIcons.Nodes.DataSchema
                    // 低于 0.60 整行灰掉的规则在表节点上同样成立：这张表**最好的**一条关系都没过线，
                    // 说明关于它的一切都还得核对，不该看起来和可采信的表一样
                    val attrs = when {
                        node.stale -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                        node.view == null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        !node.anySolid -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append(node.name, attrs)
                    node.view?.label?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    append("  (${node.relations.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    when {
                        node.stale -> append("  [已失效]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        node.view == null -> append("  [未入库]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
                is TreeNode.Relation -> {
                    icon = AllIcons.Nodes.Method
                    val r = node.view
                    // 低置信度整行灰掉：视觉上一眼分出「可采信」与「去核对」，与 Mermaid 虚实线共用 0.60 这条线
                    val main = if (r.solid) {
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    }
                    append("${num(r.confidence)}  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append(r.text, main)
                    if (r.needsReview) append("  [需复核]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    if (!r.schemaAligned) append("  [未对齐]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is TreeNode.Evidence -> {
                    val e = node.view
                    icon = AllIcons.Actions.Find
                    append("${e.typeLabel}  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(num(e.weight), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val loc = e.jump?.location
                    if (loc != null) {
                        append("  $loc", SimpleTextAttributes.LINK_ATTRIBUTES)
                        e.from.refText?.let { append("   $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    } else {
                        append("  （无代码出处）", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
                is TreeNode.ConflictRoot -> {
                    icon = AllIcons.General.Warning
                    append("冲突  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("(${node.conflicts.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TreeNode.Conflict -> {
                    val c = node.view
                    icon = if (c.severity == "HIGH") AllIcons.General.Error else AllIcons.General.InspectionsEye
                    val attrs = if (c.severity == "HIGH") {
                        SimpleTextAttributes.ERROR_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append("${c.severity}  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append("${c.type}  ", attrs)
                    append(c.subject, attrs)
                    if (c.needsReview) append("  [需复核]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is TreeNode.Ref -> {
                    icon = AllIcons.Actions.Find
                    append(node.label, SimpleTextAttributes.LINK_ATTRIBUTES)
                }
                null -> Unit
            }
        }
    }

    // ---------- 工具栏动作 ----------

    /**
     * 工具栏与右键动作的 `update` 要读树的选中状态（Swing），所以必须在 EDT 上跑 ——
     * 平台默认的 `ActionUpdateThread.BGT` 会让 `selectedNode()` 在后台线程碰 Swing。
     */
    private abstract inner class UiAction(text: String, description: String, icon: Icon) :
        AnAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * 与 Tools 菜单的 `Scan Data Model` 是同一条实现（[ScanDataModelAction.scan]）：跑完整管线 →
     * 落盘 → 成功后由扫描侧回调 `reloadIfOpen` 刷新本面板，所以不需要单独的「重载事实源」按钮。
     * 扫描失败（没配数据源等）时通知会说原因，面板退回显示磁盘上次的产物。
     */
    private inner class ScanAction :
        AnAction(
            "扫描并加载",
            "跑完整管线（需先在 Database 工具窗口配好数据源）：读 schema → 推断关系 → 算置信度 → 落盘并加载到本面板",
            AllIcons.Actions.Execute,
        ) {
        override fun actionPerformed(e: AnActionEvent) = ScanDataModelAction.scan(project)

        // 扫描要 resolve 方法引用，索引期间跑出来的结果是残缺的，所以跟菜单项一样灰掉
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !DumbService.isDumb(project)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenDiagramAction :
        AnAction("打开 Mermaid 关系图", "打开某个模块的 .mmd，由 IDE 自带的 mermaid 插件渲染", AllIcons.Nodes.DataTables) {
        override fun actionPerformed(e: AnActionEvent) = openDiagram()
    }

    private inner class NewScopeAction :
        UiAction("新建业务范围", "圈一组表当成一个业务范围（存 .idea/dataModelRobot.xml，重扫不会丢）", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            createScope()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = view != null
        }
    }

    private inner class DeleteScopeAction :
        UiAction("删除业务范围", "删除选中的业务范围（只删范围本身，表与扫描产物都不受影响）", AllIcons.General.Remove) {
        override fun actionPerformed(e: AnActionEvent) {
            (selectedNode() as? TreeNode.Scope)?.let { deleteScope(it.view) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode() is TreeNode.Scope
        }
    }

    private inner class AddTableToScopeAction :
        UiAction("把选中表加入业务范围", "把树里选中的表加进某个业务范围（没有范围时可以就地新建）", AllIcons.Actions.AddToDictionary) {
        override fun actionPerformed(e: AnActionEvent) = addSelectedTableToScope()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode() is TreeNode.Table
        }
    }

    private inner class RenameScopeAction(private val target: ScopeView) :
        UiAction("重命名", "改这个业务范围的名字", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) = renameScope(target)
    }

    private inner class DeleteScopeByIdAction(private val target: ScopeView) :
        UiAction("删除业务范围", "删除这个业务范围（只删范围本身）", AllIcons.General.Remove) {
        override fun actionPerformed(e: AnActionEvent) = deleteScope(target)
    }

    private inner class RemoveTableFromScopeAction(private val scope: ScopeView, private val tableName: String) :
        UiAction("从范围移除", "把 $tableName 从「${scope.name}」里移除（不删表，也不动事实源）", AllIcons.Actions.Cancel) {
        override fun actionPerformed(e: AnActionEvent) = removeTableFromScope(scope, tableName)
    }

    private inner class HelpAction :
        AnAction("使用方法", "功能介绍与操作步骤：配数据源、扫描、看结果，以及怎么装 MCP Server 与 Skill", AllIcons.General.ContextHelp) {
        override fun actionPerformed(e: AnActionEvent) = UsageHelp.show(project)
    }

    private companion object {
        /** 搜索去抖。重建整棵树是几千个节点，逐字符重建会明显掉帧 */
        const val SEARCH_DEBOUNCE_MS = 250
    }
}
