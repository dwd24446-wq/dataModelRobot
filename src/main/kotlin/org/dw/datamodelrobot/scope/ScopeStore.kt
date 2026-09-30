package org.dw.datamodelrobot.scope

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * 用户自建的**业务范围**：一组表的命名集合，用来看「只有这几张表」的关系图。
 *
 * 存哪：**IDE 项目级配置 `.idea/dataModelRobot.xml`**，绝不进 `build/datamodel/<项目名>-datamodel.json` ——
 * 那是扫描产物、每次扫描重写，而用户数据必须活得比它久。默认是个人本地配置，
 * 团队共享取决于对方是否把 `.idea` 入库（真要共享再写 import/export）。
 *
 * 扁平一层，不做父子嵌套：需求里的「树形结构的一个节点」指业务范围作为**面板树里的节点**，
 * 不是业务范围自己再套一层。真需要时再加 `parentId`。
 *
 * **失效表不静默丢**：范围引用的表在最新事实源里消失了（改名、删表、换了库），用 [Scope.missingIn]
 * 标出来交给 UI 标灰 + 提示，与事实源「不过滤，只压分 + 打标记」同一立场 ——
 * 悄悄删掉的话，用户重扫一次就丢了范围，还不知道丢了什么。
 */
@Service(Service.Level.PROJECT)
@State(name = "DataModelRobotScopes", storages = [Storage("dataModelRobot.xml")])
class ScopeStore : PersistentStateComponent<ScopeStore.State> {

    /**
     * xmlb 绑定要**无参构造 + 可写属性**，所以持久化这一层刻意不用 data class 的 `val`。
     * 写成 `data class Scope(val id: String, …)` 会编译通过、却在反序列化时静默丢字段 ——
     * 由 `ScopeStoreTest` 的 XML 往返断言把守。
     */
    class State {
        var scopes: MutableList<Scope> = mutableListOf()
    }

    class Scope {
        /** 稳定身份。重名是允许的，所以树上与 `.idea` 里都靠它认，不靠 [name] */
        var id: String = ""
        var name: String = ""

        /** 表名**原样保留**（不 lowercase）：显示要用库里的真实拼写。插入序，不重排用户数据 */
        var tables: MutableList<String> = mutableListOf()

        /** 本范围里引用了、但 [knownTables] 里没有的表。参数大小写随意。判定**不改动存储** */
        fun missingIn(knownTables: Set<String>): List<String> {
            val known = knownTables.map { it.lowercase() }.toSet()
            return tables.filter { it.lowercase() !in known }
        }
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    /**
     * 当前全部范围。列表是副本（对它增删不影响存储），但**元素是活对象** ——
     * 改名、加表请走下面的方法，别直接写 `scope.name = …`。
     */
    val scopes: List<Scope> get() = state.scopes.toList()

    fun byId(id: String): Scope? = state.scopes.firstOrNull { it.id == id }

    /**
     * 新建范围；名字空白返回 null（否则树上会多一行空节点）。**重名允许** ——
     * 拦重名只会逼用户起「订单域2」这种更难认的名字。
     */
    fun create(name: String): Scope? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return Scope().also {
            it.id = UUID.randomUUID().toString()
            it.name = trimmed
            state.scopes += it
        }
    }

    /** 改名；范围不存在或新名空白都返回 false，且**不清掉原名字** */
    fun rename(id: String, newName: String): Boolean {
        val scope = byId(id) ?: return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        scope.name = trimmed
        return true
    }

    fun delete(id: String): Boolean = state.scopes.removeAll { it.id == id }

    /** 加表。同一张表重复加是 no-op（返回 false），大小写不同也算同一张（MySQL 表名不区分大小写） */
    fun addTable(id: String, table: String): Boolean {
        val scope = byId(id) ?: return false
        val name = table.trim()
        if (name.isEmpty()) return false
        if (scope.tables.any { it.equals(name, ignoreCase = true) }) return false
        scope.tables += name
        return true
    }

    fun removeTable(id: String, table: String): Boolean {
        val scope = byId(id) ?: return false
        return scope.tables.removeAll { it.equals(table, ignoreCase = true) }
    }
}
