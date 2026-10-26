plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.namelens"
version = "0.1.2"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
    }
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets {
    main {
        // prompt 模板单一来源：仓库根目录 prompts/（见计划：独立版本化便于调优）
        resources.srcDir(layout.projectDirectory.dir("../prompts"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.namelens.plugin"
        name = "NameLens"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = provider { "241" }
            untilBuild = provider { null }
        }
    }
}

// searchable options 需要拉起无头 IDE，构建慢且本地安装用不到
tasks.matching { it.name == "buildSearchableOptions" || it.name == "jarSearchableOptions" }
    .configureEach { enabled = false }
