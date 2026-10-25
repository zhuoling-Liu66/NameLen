package com.namelens

/**
 * Prompt 模板单一来源：仓库根目录 prompts/，经 Gradle 打进插件资源，
 * 调优时只改 prompts 目录下的 md 文件即可，无需动代码。
 */
object PromptLoader {

    fun load(resourcePath: String): String {
        val name = resourcePath.removePrefix("/")
        return PromptLoader::class.java.classLoader
            .getResourceAsStream(name)
            ?.readBytes()
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("未找到 prompt 资源: prompts/$name")
    }
}
