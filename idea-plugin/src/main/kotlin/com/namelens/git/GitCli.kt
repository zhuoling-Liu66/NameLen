package com.namelens.git

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** 极薄 git 命令行封装（合并 stderr 防管道阻塞；只在后台线程调用） */
object GitCli {

    fun run(workDir: File, vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("git") + args.toList())
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() == 0) out else null
    } catch (e: Exception) {
        null
    }

    fun repoRoot(dir: File): File? =
        run(dir, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
}
