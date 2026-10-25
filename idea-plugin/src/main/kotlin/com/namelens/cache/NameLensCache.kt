package com.namelens.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 项目级缓存：内容寻址 JSON（explain/gitwhy/flownotes/glossary）+ 稳定文件名输出（flow.html 等）。
 * 存放在项目根 .namelens-cache/（已建议加入 .gitignore），透明可清理。
 */
@Service(Service.Level.PROJECT)
class NameLensCache(project: Project) {

    private val dir: Path = Path.of(project.basePath ?: ".", ".namelens-cache")

    fun get(kind: String, key: String): String? {
        val f = dir.resolve("$kind-${sha256("$kind:$key")}.json")
        return try {
            if (Files.isRegularFile(f)) Files.readString(f, StandardCharsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }
    }

    fun put(kind: String, key: String, value: String) {
        try {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("$kind-${sha256("$kind:$key")}.json"), value, StandardCharsets.UTF_8)
        } catch (ignored: Exception) {
        }
    }

    /** 以稳定文件名写入（如 flow.html、mermaid.min.js），返回绝对路径 */
    fun writeRaw(name: String, content: String): Path? = try {
        Files.createDirectories(dir)
        dir.resolve(name).also { Files.writeString(it, content, StandardCharsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    companion object {
        fun getInstance(project: Project): NameLensCache = project.getService(NameLensCache::class.java)

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
