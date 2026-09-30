package org.dw.datamodelrobot.toolwindow

import org.dw.datamodelrobot.schema.SchemaTables
import org.dw.datamodelrobot.scope.ScopeStore
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** 树里的一行。sealed 让渲染器与详情面板都只能处理已知种类，将来加节点类型时编译期就会提醒 */
sealed class TreeNode {

    /** 三个根。**恒显示**，即使下面是空的 —— 会时有时无的根比空根更难发现 */
    data class ScopeRoot(val count: Int) : TreeNode()

    data class ModuleRoot(val count: Int) : TreeNode()

    data class ConflictRoot(val conflicts: List<ConflictView>) : TreeNode()

    /** 用户自建的业务范围 */
    data class Scope(val view: ScopeView) : TreeNode()

    /**
     * 表前缀模块（自动）。只是一个装表的文件夹，所以只带计数与「本模块作为 from 端的关系」
     * （后者给详情面板用，口径与 `Mermaid.byModule` 一致）。
     *
     * [tableCount] 说的是**树上实际挂着的**表数 —— 搜索过滤后它就是命中的那些，不是全库的。
     */
    data class Module(val name: String, val tableCount: Int, val ownRelations: List<RelationView>) : TreeNode()

    /**
     * 一张表，挂着它**全部**的关系（in + out 都算）—— 这是与旧树最大的口径差别：旧树按 from 端分组，
     * 一张表只看得到它指出去的关系，看不到谁指向它。
     *
     * @param view null = 这张表在关系里出现过、但**不在事实源的 `tables[]` 里**（代码引用了库里没有的表）。
     *   实测这种表有 35 张，**不能因为查不到就丢掉、更不能 NPE**，要标出来让人去核对
     * @param inScope 是否挂在业务范围下（决定右键有没有「从范围移除」）
     * @param stale 范围里引用了、但最新事实源里**压根没有**这张表（改名/删表/换了库）。标灰 + 提示，不静默丢
     */
    data class Table(
        val name: String,
        val view: TableView?,
        val relations: List<RelationView>,
        val inScope: Boolean,
        val stale: Boolean,
    ) : TreeNode() {
        /**
         * 这张表**有没有**一条过了 0.60 的关系。「低于 0.60 整行灰掉」的既有视觉规则要从关系节点
         * 延伸到表节点：最好的那条都没过线，说明关于它的一切都还得核对，不该看起来和可采信的表一样。
         * 没有关系时算「不灰」—— 孤表是另一回事（详情面板会说），别和低置信度混成一个颜色。
         */
        val anySolid: Boolean get() = relations.isEmpty() || relations.any { it.solid }
    }

    data class Relation(val view: RelationView) : TreeNode()

    data class Evidence(val view: EvidenceView) : TreeNode()

    data class Conflict(val view: ConflictView) : TreeNode()

    /** 冲突的出处（`文件名:行号`），可跳转 */
    data class Ref(val label: String) : TreeNode()
}

/**
 * 业务范围在面板上的读模型 = 存储里的范围 + 与最新事实源比对的结果。
 *
 * [missing] 是「范围里引用了、事实源里已没有」的表：**只标不删**（与事实源「不过滤，只压分 + 打标记」
 * 同一立场）。悄悄删掉的话，用户重扫一次就少了表、还不知道少了什么。
 */
data class ScopeView(
    val id: String,
    val name: String,
    val tables: List<String>,
    val missing: List<String>,
) {
    val label: String get() = if (missing.isEmpty()) name else "$name（${missing.size} 张表已失效）"

    companion object {
        /** [knownTables] 取 [TableIndex.knownNames]（小写）；比对大小写不敏感，所以传什么都行 */
        fun of(scopes: List<ScopeStore.Scope>, knownTables: Set<String>): List<ScopeView> =
            scopes.map { ScopeView(it.id, it.name, it.tables.toList(), it.missingIn(knownTables)) }
    }
}

/**
 * 面板树的双根结构（Phase 4 A4）：
 *
 * ```
 * 业务范围（用户自建）        ← 工具栏：新建范围 / 删除范围 / 把选中表加入范围
 *   └ <范围名>               ← 双击出该范围 ER 图（A5）
 *       └ <表>               ← 双击出该表邻域 ER 图（A5）
 *           └ <关系>          ← 该表的全部关系（in + out）
 *               └ <证据>      ← 双击跳 文件:行号
 * 表前缀模块（自动）
 *   └ <module> → <表> → <关系> → <证据>
 * 冲突
 *   └ <冲突> → <出处>
 * ```
 *
 * 抽成纯函数（不进 `DataModelPanel`）是为了**能 headless 断言**：`DefaultMutableTreeNode` 与
 * `DefaultTreeModel` 都是纯 Java 类，构造它们不需要 EDT 也不需要真 IDE，所以整棵树的结构、
 * 排序、标记都能写成单测 —— 只有渲染与双击留给 `runIde` 人眼验。
 */
object DataModelTree {

    /** 同一张表下的关系按置信度降序、同分按 text（与 `FactSourceView.relationsByModule` 组内同口径） */
    private val BY_CONFIDENCE = compareBy<RelationView>({ -it.confidence }, { it.text })

    /**
     * @param filter 搜索框里的词。非空时**只留命中的表**（连同它们的父节点），并且不建冲突根 ——
     *   搜的是表，把 247 条冲突一起留着只会淹掉结果。命中集合走 [TableIndex.search]，
     *   所以匹配口径与搜索结果计数是同一份
     */
    fun build(view: FactSourceView, scopes: List<ScopeView>, filter: String = ""): DefaultMutableTreeNode {
        val matching = if (filter.isBlank()) {
            null
        } else {
            view.tableIndex.search(filter).map { it.name.lowercase() }.toSet()
        }
        val relationsByTable = indexRelationsByTable(view.relations)

        val root = DefaultMutableTreeNode()
        root.add(scopeRoot(view, scopes, relationsByTable, matching))
        root.add(moduleRoot(view, relationsByTable, matching))
        if (matching == null) {
            val conflicts = view.sortedConflicts()
            if (conflicts.isNotEmpty()) root.add(conflictRoot(conflicts))
        }
        return root
    }

    /**
     * 表名（**一律小写**）→ 该表参与的全部关系。两端都记，所以一条关系会出现在 from 表与 to 表下面 ——
     * 这正是「按表看」与旧的「按 from 端看」的差别。
     *
     * 小写归一是必须的：事实源里的表名一半来自 `@TableName`、一半来自库 introspection，
     * 两边大小写不保证一致，而 MySQL 表名不区分大小写（`ScopeStore.addTable` 同一口径）。
     */
    private fun indexRelationsByTable(relations: List<RelationView>): Map<String, List<RelationView>> {
        val map = HashMap<String, MutableList<RelationView>>()
        for (r in relations) {
            val from = r.fromTable.lowercase()
            val to = r.toTable.lowercase()
            map.getOrPut(from) { mutableListOf() } += r
            if (to != from) map.getOrPut(to) { mutableListOf() } += r
        }
        return map.mapValues { (_, v) -> v.sortedWith(BY_CONFIDENCE) }
    }

    /**
     * 范围按名字升序（存储里是创建顺序，但树上每一层都排过序，这一层不排会显得随机）。
     * 根上的计数 = **实际挂上来的**范围数：搜索时砍掉的分支不该还计在里面。
     */
    private fun scopeRoot(
        view: FactSourceView,
        scopes: List<ScopeView>,
        relationsByTable: Map<String, List<RelationView>>,
        matching: Set<String>?,
    ): DefaultMutableTreeNode {
        val kept = mutableListOf<DefaultMutableTreeNode>()
        for (scope in scopes.sortedBy { it.name.lowercase() }) {
            val scopeNode = DefaultMutableTreeNode(TreeNode.Scope(scope))
            var visible = 0
            for (name in scope.tables.sortedBy { it.lowercase() }) {
                if (matching != null && name.lowercase() !in matching) continue
                scopeNode.add(tableNode(view, name, relationsByTable, inScope = true))
                visible++
            }
            // 搜索时整个范围都没命中就别留一个空壳
            if (matching == null || visible > 0) kept += scopeNode
        }
        val root = DefaultMutableTreeNode(TreeNode.ScopeRoot(kept.size))
        kept.forEach { root.add(it) }
        return root
    }

    private fun moduleRoot(
        view: FactSourceView,
        relationsByTable: Map<String, List<RelationView>>,
        matching: Set<String>?,
    ): DefaultMutableTreeNode {
        val names = allTableNames(view, relationsByTable)
        val byModule = names.groupBy { SchemaTables.moduleOf(it) }.toSortedMap()
        val ownRelations = view.relationsByModule()
        val kept = mutableListOf<DefaultMutableTreeNode>()
        for ((module, tables) in byModule) {
            val moduleNode = DefaultMutableTreeNode()
            for (name in tables) {
                if (matching != null && name !in matching) continue
                moduleNode.add(tableNode(view, name, relationsByTable, inScope = false))
            }
            if (matching != null && moduleNode.childCount == 0) continue
            // userObject 要等表挂完才知道数量，所以最后再设（计数说的是**看得见**的表，不是全库的）
            moduleNode.userObject = TreeNode.Module(module, moduleNode.childCount, ownRelations[module].orEmpty())
            kept += moduleNode
        }
        val root = DefaultMutableTreeNode(TreeNode.ModuleRoot(kept.size))
        kept.forEach { root.add(it) }
        return root
    }

    /**
     * 树上要出现的表 = 事实源 `tables[]` 里的（有列与注释）**加上**只在关系里出现的
     * （代码引用了库里没有的表，实测 35 张）。后者照样要能看见 —— 「不过滤」在树上也成立。
     */
    private fun allTableNames(view: FactSourceView, relationsByTable: Map<String, List<RelationView>>): List<String> {
        val fromSchema = view.tables.map { it.name.lowercase() }
        val fromRelations = relationsByTable.keys
        return (fromSchema + fromRelations).distinct().sorted()
    }

    private fun tableNode(
        view: FactSourceView,
        name: String,
        relationsByTable: Map<String, List<RelationView>>,
        inScope: Boolean,
    ): DefaultMutableTreeNode {
        val lower = name.lowercase()
        val relations = relationsByTable[lower].orEmpty()
        val table = DefaultMutableTreeNode(
            TreeNode.Table(
                name = view.tableIndex[lower]?.name ?: name,
                view = view.tableIndex[lower],
                relations = relations,
                inScope = inScope,
                // 范围里引用了、而事实源里既没有这张表、也没有任何关系提到它 = 失效
                stale = view.tableIndex[lower] == null && relations.isEmpty(),
            ),
        )
        for (r in relations) {
            val relationNode = DefaultMutableTreeNode(TreeNode.Relation(r))
            for (e in r.evidences) relationNode.add(DefaultMutableTreeNode(TreeNode.Evidence(e)))
            table.add(relationNode)
        }
        return table
    }

    private fun conflictRoot(conflicts: List<ConflictView>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode(TreeNode.ConflictRoot(conflicts))
        for (c in conflicts) {
            val node = DefaultMutableTreeNode(TreeNode.Conflict(c))
            for (ref in c.refs) node.add(DefaultMutableTreeNode(TreeNode.Ref(ref)))
            root.add(node)
        }
        return root
    }

    /** 深度优先（前序）收集某一类节点，给测试与「选中某张表后定位到树上哪一行」用 */
    inline fun <reified T : TreeNode> collect(root: DefaultMutableTreeNode): List<T> {
        val out = mutableListOf<T>()
        // 显式栈而不是递归：inline 函数里声明局部函数编译器直接拒绝（Local functions are not yet
        // supported in inline functions），顺带也不吃调用栈深度
        val stack = ArrayDeque<DefaultMutableTreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            (node.userObject as? T)?.let { out += it }
            // 倒着压栈，出栈顺序才等于子节点顺序
            for (i in node.childCount - 1 downTo 0) stack.addLast(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        return out
    }

    /** 找到第一棵满足 [predicate] 的节点路径，用于搜索后把树定位过去。找不到返回 null */
    fun pathTo(root: DefaultMutableTreeNode, predicate: (TreeNode) -> Boolean): TreePath? {
        fun walk(node: DefaultMutableTreeNode): TreePath? {
            // 根的 userObject 是 null（面板故意不显示根），所以这里不能因为取不到 TreeNode 就整棵放弃
            val user = node.userObject as? TreeNode
            if (user != null && predicate(user)) return TreePath(node.path)
            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i) as DefaultMutableTreeNode)?.let { return it }
            }
            return null
        }
        return walk(root)
    }
}
