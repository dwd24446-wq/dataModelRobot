import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "org.dw"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app/Contents")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.database")
        // Lombok light method 是命门 2 的前提。id 就是官方这个历史拼写（Lombook）。
        bundledPlugin("Lombook Plugin")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        pluginVerifier("1.410")
    }
    testImplementation(kotlin("test"))
}

// 平台 262 的 jar 是 Java 25 字节码（major 69），toolchain 低于 25 时 javac 无法读取。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 平台接口（如 ToolWindowFactory）本身是 Kotlin 接口，且已编译成真 JVM default 方法。
        // 默认的 disable 模式会让 kotlinc 给继承来的每个 default 成员都生成一个 ACC_BRIDGE 覆写，
        // Plugin Verifier 于是把这些编译器桥接记成本插件「覆写并调用了 deprecated / experimental API」
        // —— 源码里根本没写（实测 8 条：isApplicable/isDoNotActivateOnStart 2 条 deprecated，
        // manage/getAnchor/getIcon 6 条 experimental）。
        // 三个模式实测过：disable 与 enable 都照旧生成 7 个桥接，只有 no-compatibility
        // （只出 JVM default、不再产出 DefaultImpls 兼容层）能把桥接清零。
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    useJUnit()
    maxHeapSize = "4g"
    // 真实项目全量（重）测试开关：./gradlew test -Dfullscan=1
    systemProperty("fullscan", System.getProperty("fullscan") ?: "0")
    // 全量测试的项目根目录。FullScanSources.root 的兜底链是
    // 「系统属性 fullscan.project.dir → 环境变量 FULLSCAN_PROJECT_DIR → fixtures/fullscan-project.dir」，
    // 用 ?: 串联，所以这里**只在命令行真给了值时才转发**：无条件设成空串会让后两级兜底永久失效
    // （空串不是 null，?: 不会继续往下走），全量测试会变成静默跳过。
    System.getProperty("fullscan.project.dir")?.let { systemProperty("fullscan.project.dir", it) }
    // 统一版里的 ultimate-plugin 注册了混淆的 postStartupActivity（Z.Z.Z.Z.Z），
    // 在测试环境无合适构造器、实例化必失败；TestLogger 把 Logger.error 判为测试失败。
    // 262 平台的禁用机制是 disabled.plugins.file.path 指向清单文件（每行一个插件 ID），
    // 禁用 com.intellij.modules.ultimate 会级联禁掉依赖它的 Ultimate 系插件。
    systemProperty("disabled.plugins.file.path", file("gradle/testing/disabled_plugins.txt").absolutePath)
    inputs.file(file("gradle/testing/disabled_plugins.txt"))
    // FUS 统计在 ActionManager 初始化时读 marketplace 缓存 pluginsXMLIds.json；
    // 该文件每次测试 JVM 退出时都可能被截断，下次运行解析必炸且被 TestLogger 判为失败。
    // 每轮测试前删掉，让其走「无缓存」路径。
    doFirst {
        delete(fileTree(".intellijPlatform/sandbox") { include("**/pluginsXMLIds.json") })
    }
    // 测试 cwd = 项目根目录；报告与 classpath.txt 都按此相对路径寻址
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
