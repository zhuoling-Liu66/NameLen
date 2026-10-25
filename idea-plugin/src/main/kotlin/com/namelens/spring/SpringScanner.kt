package com.namelens.spring

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.namelens.util.PsiUtils

data class FlowNode(
    val id: String,
    val kind: String,
    val label: String,
    val detail: String,
    val file: String?,
    val offset: Int,
)

class FlowGraph(
    val projectName: String,
    val client: FlowNode,
    val dispatcher: FlowNode,
    val security: List<FlowNode>,
    val filters: List<FlowNode>,
    val interceptors: List<FlowNode>,
    val controllers: List<FlowNode>,
    val services: List<FlowNode>,
    val repositories: List<FlowNode>,
    val exits: List<FlowNode>,
    val extras: String,
) {
    fun allNodes(): List<FlowNode> =
        listOf(client) + security + filters + listOf(dispatcher) + interceptors +
            controllers + services + repositories + exits
}

/**
 * Spring 静态扫描：只回答"一次前端 HTTP 请求会经过哪些真实存在的组件"。
 * 结构由确定性扫描产生（不靠 LLM，避免幻觉）；语义注释交给 LLM（flow-note.md）。
 * 反射/AOP/动态代理断链处，运行时请用 Arthas trace 确认。
 */
object SpringScanner {

    private const val CTRL_CAP = 8
    private const val SVC_CAP = 6

    private class Acc {
        val controllers = ArrayList<FlowNode>()
        val filters = ArrayList<FlowNode>()
        val interceptors = ArrayList<FlowNode>()
        val security = ArrayList<FlowNode>()
        val serviceEntries = ArrayList<Triple<String, String?, Int>>() // name, file, offset
        var serviceTransactional = 0
        val mapperNames = ArrayList<String>()
        val jpaNames = ArrayList<String>()
        var totalControllers = 0
        var totalServices = 0
    }

    fun scan(project: Project, indicator: ProgressIndicator): FlowGraph? {
        val files = ArrayList<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (indicator.isCanceled) return@iterateContent false
            if (!vf.isValid || vf.isDirectory || !vf.name.endsWith(".java")) return@iterateContent true
            val p = vf.path.replace('\\', '/')
            if ("/build/" in p || "/out/" in p || "/target/" in p) return@iterateContent true
            files.add(vf)
            true
        }
        if (files.isEmpty()) return null

        // 第一遍：文件级特征（import/常见标识），快且不依赖索引
        val flags = HashMap<String, Int>()
        for (vf in files) {
            val text = try {
                VfsUtilCore.loadText(vf)
            } catch (e: Exception) {
                continue
            }
            merge(flags, text)
        }

        // 第二遍：PSI 注解/继承识别
        val acc = Acc()
        var i = 0
        for (vf in files) {
            if (indicator.isCanceled) break
            if (++i % 25 == 0) {
                indicator.fraction = 0.1 + 0.7 * i / files.size
                indicator.text = "扫描 ${vf.name}"
            }
            val psi = try {
                PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile
            } catch (e: Exception) {
                null
            } ?: continue
            val classes = try {
                psi.classes
            } catch (e: Exception) {
                continue
            }
            for (cls in classes) visitClass(cls, vf, acc)
        }

        val isSpring = acc.totalControllers > 0 || acc.security.isNotEmpty() ||
            acc.interceptors.isNotEmpty() || acc.totalServices > 0
        if (!isSpring) return null

        val services: List<FlowNode> = if (acc.serviceEntries.isEmpty()) {
            emptyList()
        } else if (acc.serviceEntries.size <= SVC_CAP) {
            acc.serviceEntries.mapIndexed { idx, s ->
                FlowNode("svc$idx", "service", s.first, "", s.second, s.third)
            }
        } else {
            listOf(
                FlowNode(
                    "svc0", "service", "Service 层（${acc.serviceEntries.size} 个）",
                    acc.serviceEntries.take(5).joinToString("、") { it.first } + " 等",
                    null, 0
                )
            )
        }

        val repositories = if (acc.mapperNames.isEmpty() && acc.jpaNames.isEmpty()) emptyList() else listOf(
            FlowNode(
                "repo0", "repository", "数据访问层",
                listOfNotNull(
                    "MyBatis Mapper ×${acc.mapperNames.size}".takeIf { acc.mapperNames.isNotEmpty() },
                    "JPA Repository ×${acc.jpaNames.size}".takeIf { acc.jpaNames.isNotEmpty() },
                ).joinToString(" · "),
                null, 0
            )
        )

        val exits = ArrayList<FlowNode>()
        if (flags.containsKey("mybatis") || flags.containsKey("jpa")) {
            exits.add(FlowNode("db", "db", "数据库", "SQL 落库", null, 0))
        }
        flags["redis"]?.let {
            exits.add(FlowNode("redis", "cache", "Redis", "缓存（引用 $it 个文件）", null, 0))
        }
        if (flags.containsKey("kafka")) exits.add(FlowNode("mq_kafka", "mq", "Kafka", "消息（引用 ${flags["kafka"]} 个文件）", null, 0))
        if (flags.containsKey("rabbitmq")) exits.add(FlowNode("mq_rabbit", "mq", "RabbitMQ", "消息（引用 ${flags["rabbitmq"]} 个文件）", null, 0))
        if (flags.containsKey("rocketmq")) exits.add(FlowNode("mq_rocket", "mq", "RocketMQ", "消息（引用 ${flags["rocketmq"]} 个文件）", null, 0))
        if (flags.containsKey("feign") || flags.containsKey("httpclient")) {
            exits.add(FlowNode("httpout", "http", "外部 HTTP 调用", "Feign ${flags["feign"] ?: 0} / RestTemplate|WebClient ${flags["httpclient"] ?: 0} 个文件", null, 0))
        }

        val extras = buildString {
            append("扫描 Java 文件 ${files.size} 个")
            append(" · Controller ${acc.totalControllers}").append(if (acc.totalControllers > CTRL_CAP) "（图示前 $CTRL_CAP）" else "")
            append(" · Service ${acc.totalServices}")
            append(" · Filter ${acc.filters.size + acc.security.size}")
            append(" · Interceptor ${acc.interceptors.size}")
            if (acc.serviceTransactional > 0) append(" · @Transactional 类 ${acc.serviceTransactional}")
        }

        return FlowGraph(
            projectName = project.name,
            client = FlowNode("client", "client", "前端 / 调用方", "HTTP 请求", null, 0),
            dispatcher = FlowNode("dispatcher", "dispatcher", "DispatcherServlet", "Spring MVC 分发", null, 0),
            security = acc.security,
            filters = acc.filters,
            interceptors = acc.interceptors,
            controllers = acc.controllers,
            services = services,
            repositories = repositories,
            exits = exits,
            extras = extras,
        )
    }

    private fun merge(flags: MutableMap<String, Int>, text: String) {
        if ("org.springframework.data.redis" in text) flags.merge("redis", 1, Int::plus)
        if ("org.springframework.kafka" in text || "KafkaTemplate" in text) flags.merge("kafka", 1, Int::plus)
        if ("org.springframework.amqp" in text) flags.merge("rabbitmq", 1, Int::plus)
        if ("org.apache.rocketmq" in text) flags.merge("rocketmq", 1, Int::plus)
        if ("org.apache.ibatis" in text || "org.mybatis" in text) flags.merge("mybatis", 1, Int::plus)
        if ("jakarta.persistence" in text || "javax.persistence" in text || "JpaRepository" in text) flags.merge("jpa", 1, Int::plus)
        if ("openfeign" in text || "FeignClient" in text) flags.merge("feign", 1, Int::plus)
        if ("RestTemplate" in text || "WebClient" in text) flags.merge("httpclient", 1, Int::plus)
        if ("@Cacheable" in text || "@CacheEvict" in text) flags.merge("rediscfg", 1, Int::plus)
    }

    private fun visitClass(cls: PsiClass, vf: VirtualFile, acc: Acc) {
        val name = cls.name ?: return
        val annos = try {
            cls.annotations.map { (it.qualifiedName ?: it.text).substringAfterLast('.') }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val supers = try {
            cls.superTypes.joinToString(" ") { it.presentableText } + " " +
                cls.interfaces.joinToString(" ") { it.qualifiedName ?: it.name ?: "" }
        } catch (e: Exception) {
            ""
        }

        when {
            "RestController" in annos || ("Controller" in annos && "Configuration" !in annos) -> {
                acc.totalControllers++
                if (acc.controllers.size < CTRL_CAP) {
                    val base = firstMappingValue(cls)
                    val endpoints = countEndpoints(cls)
                    acc.controllers.add(
                        FlowNode(
                            "ctrl${acc.controllers.size}", "controller", name,
                            (if (base.isNotBlank()) "base=$base " else "") + "$endpoints 个接口",
                            vf.path, cls.textOffset
                        )
                    )
                }
            }
            "SecurityFilterChain" in returnTypes(cls) || definesSecurityChain(cls) -> {
                for (m in securityChainMethods(cls)) {
                    acc.security.add(
                        FlowNode("sec${acc.security.size}", "security", "${name}#${m.name}", securityDetail(m), vf.path, m.textOffset)
                    )
                }
            }
            "Filter" in supers || "OncePerRequestFilter" in supers -> {
                acc.filters.add(FlowNode("flt${acc.filters.size}", "filter", name, "", vf.path, cls.textOffset))
            }
            "HandlerInterceptor" in supers -> {
                acc.interceptors.add(FlowNode("int${acc.interceptors.size}", "interceptor", name, "", vf.path, cls.textOffset))
            }
            "Service" in annos -> {
                acc.totalServices++
                if ("Transactional" in annos) acc.serviceTransactional++
                acc.serviceEntries.add(Triple(name, vf.path, cls.textOffset))
            }
            cls.isInterface -> {
                when {
                    "Mapper" in annos || "BaseMapper" in supers -> acc.mapperNames.add(name)
                    "JpaRepository" in supers || "CrudRepository" in supers || "Repository" in supers && "Mapper" !in annos ->
                        if ("JpaRepository" in supers || "CrudRepository" in supers) acc.jpaNames.add(name)
                }
            }
        }

        try {
            for (inner in cls.innerClasses) visitClass(inner, vf, acc)
        } catch (e: Exception) {
        }
    }

    private fun returnTypes(cls: PsiClass): String =
        try {
            PsiUtils.ownMethods(cls).joinToString(" ") { it.returnType?.presentableText ?: "" }
        } catch (e: Exception) {
            ""
        }

    private fun securityChainMethods(cls: PsiClass): List<PsiMethod> =
        try {
            PsiUtils.ownMethods(cls).filter { (it.returnType?.presentableText ?: "").contains("SecurityFilterChain") }
        } catch (e: Exception) {
            emptyList()
        }

    private fun definesSecurityChain(cls: PsiClass): Boolean = securityChainMethods(cls).isNotEmpty()

    private fun securityDetail(m: PsiMethod): String {
        val t = try {
            m.body?.text
        } catch (e: Exception) {
            null
        } ?: return "SecurityFilterChain 定义"
        val matchers = Regex("requestMatchers?\\(([^)]*)\\)").findAll(t).take(5)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.joinToString("; ")
        val sb = StringBuilder()
        if (matchers.isNotBlank()) sb.append("保护: ").append(matchers).append(' ')
        for (kw in listOf("permitAll", "hasRole", "hasAuthority", "authenticated", "csrf", "sessionManagement", "oauth2ResourceServer", "jwt"))
            if (kw in t) sb.append("[").append(kw).append("] ")
        return sb.toString().ifBlank { "SecurityFilterChain 定义" }
    }

    private fun countEndpoints(cls: PsiClass): Int = try {
        PsiUtils.ownMethods(cls).count { m ->
            m.annotations.any {
                (it.qualifiedName ?: it.text).substringAfterLast('.') in
                    setOf("GetMapping", "PostMapping", "RequestMapping", "PutMapping", "DeleteMapping", "PatchMapping")
            }
        }
    } catch (e: Exception) {
        0
    }

    private fun firstMappingValue(cls: PsiClass): String = try {
        cls.annotations.firstOrNull { (it.qualifiedName ?: "").substringAfterLast('.') == "RequestMapping" }
            ?.let { Regex("\"([^\"]+)\"").find(it.text)?.groupValues?.get(1) } ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 由确定性结构生成 Mermaid；notes 是 LLM 给每个节点补的一句语义（可为空） */
    fun toMermaid(g: FlowGraph, notes: Map<String, String>): String {
        val sb = StringBuilder("flowchart TD\n")

        fun text(n: FlowNode): String {
            var t = n.label.replace("\"", "'")
            if (n.detail.isNotBlank()) t += "<br/><small>" + n.detail.replace("\"", "'").take(70) + "</small>"
            val note = notes[n.id]?.take(60)?.replace("\"", "'")?.replace("\n", " ")
            if (!note.isNullOrBlank() && note != "（静态信息不足）") t += "<br/>💡" + note
            return t
        }

        fun emit(n: FlowNode) {
            sb.append("    ").append(n.id).append("[\"").append(text(n)).append("\"]\n")
        }

        fun sub(id: String, title: String, list: List<FlowNode>) {
            if (list.isEmpty()) return
            sb.append("    subgraph ").append(id).append("[\"").append(title).append("\"]\n")
            list.forEach { emit(it) }
            sb.append("    end\n")
        }

        fun edge(from: String, to: String, label: String = "") {
            sb.append("    ").append(from).append(" -->")
            if (label.isNotEmpty()) sb.append('|').append(label).append('|')
            sb.append(' ').append(to).append('\n')
        }

        emit(g.client)
        sub("sgsec", "过滤器链 / Spring Security", g.security + g.filters)
        emit(g.dispatcher)
        sub("sgint", "拦截器 Interceptor", g.interceptors)
        sub("sgctrl", "控制层 Controller", g.controllers)
        sub("sgsvc", "业务层 Service", g.services)
        sub("sgrepo", "数据访问层", g.repositories)
        sub("sgext", "中间件出口", g.exits)

        val secChain = g.security + g.filters
        var prev = g.client.id
        if (secChain.isNotEmpty()) {
            secChain.forEachIndexed { i, n ->
                edge(prev, n.id, if (i == 0) "HTTP 请求" else "")
                prev = n.id
            }
            edge(prev, g.dispatcher.id)
        } else {
            edge(prev, g.dispatcher.id, "HTTP 请求")
        }

        var svcEntry: String? = g.services.firstOrNull()?.id
        if (g.interceptors.isNotEmpty()) {
            var p = g.dispatcher.id
            for (n in g.interceptors) {
                edge(p, n.id)
                p = n.id
            }
            for (c in g.controllers) edge(p, c.id)
        } else {
            for (c in g.controllers) edge(g.dispatcher.id, c.id)
        }

        val repoId = g.repositories.firstOrNull()?.id
        for (c in g.controllers) {
            val target = svcEntry ?: repoId ?: g.exits.firstOrNull { it.kind == "db" }?.id
            if (target != null) edge(c.id, target)
        }
        if (svcEntry != null && repoId != null) {
            g.services.forEach { s -> edge(s.id, repoId) }
        }
        val dbId = g.exits.firstOrNull { it.kind == "db" }?.id
        if (dbId != null) {
            val from = repoId ?: svcEntry ?: g.controllers.firstOrNull()?.id ?: g.dispatcher.id
            edge(from, dbId, "SQL")
        }
        val extFrom = svcEntry ?: g.controllers.firstOrNull()?.id ?: g.dispatcher.id
        for (exit in g.exits) {
            when (exit.kind) {
                "cache" -> edge(extFrom, exit.id, "读写缓存")
                "mq" -> edge(extFrom, exit.id, "收发消息")
                "http" -> edge(extFrom, exit.id, "外部调用")
            }
        }

        return sb.toString()
    }
}
