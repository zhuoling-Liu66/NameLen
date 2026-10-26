# NameLens 见名知意 🔍

> **给"英语一般、靠命名读代码"的 Java 开发者的 IDEA 伴读插件。**
> 选中一个方法，弹出的中文卡片会告诉你：这个名字每个单词是什么意思、参数怎么传、返回什么、它干了什么。

[![Release](https://img.shields.io/badge/release-v0.1.3-blue)](../../releases)
[![IDEA](https://img.shields.io/badge/IDEA-2024.1%2B-orange)](https://www.jetbrains.com/idea/download/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## 为什么做这个

规范命名的代码本身就是最好的注释——前提是你看得懂英文：

```java
public UserPrincipal resolveUserPrincipal(String token)
// resolve(解析) user(用户) principal(已认证主体)
// 入参 token：JWT 令牌……
```

- 通义灵码/CodeGeeX 能解释代码，但**不会逐词拆解命名**，也没法固定输出格式
- 翻译插件能翻译 JavaDoc，但**不解释方法语义**
- NameLens 把两者合在一起：**命名逐词拆解 + 入参/返回值 + 一句话作用**，输出格式固定、结果本地缓存、来源可点击跳转

## 功能

### A. 中文解释卡片（核心）`Alt+Shift+E`

光标放在**方法调用或方法定义**上 → 弹出卡片：

| 区块 | 内容 |
|---|---|
| 命名拆解 | `resolveUserPrincipal` → resolve(解析) / user(用户) / principal(已认证主体)，含义放进业务语境（Spring Security 术语等） |
| 入参 | 每个参数的类型、名字、用途 |
| 返回值 | 含义、可能为 null / 抛异常的情况 |
| 实现要点 | 方法体做了什么（≤3 条） |
| 来源 | 定义处 `文件:行号`，**点击直接跳转** |

按"方法体内容哈希"缓存（项目下 `.namelens-cache/`），同一个方法第二次看**秒出**，代码改动自动失效。

### B. 仓库术语表

菜单 `Tools → NameLens 见名知意 → 生成仓库术语表`：扫描全仓类名/接口/高频方法/字段，LLM 分批生成中英对照 → `docs/namelens-glossary.md` + 右侧工具窗口（可搜索）。接手新项目先跑一遍，全项目"黑话"尽在掌握。

### C. 请求链路图（Spring）

菜单 `Tools → NameLens 见名知意 → 生成请求链路图`：静态扫描出一次 HTTP 请求经过的组件——

```
前端 → Spring Security/Filter(鉴权) → DispatcherServlet → Interceptor
     → Controller → Service → Mapper → Redis / MQ / MySQL
```

生成 Mermaid 流程图，在 NameLens 工具窗口内嵌展示（无 JCEF 时自动用系统浏览器打开），组件列表**双击跳源码**。

> **结构不靠 LLM**：节点和连线来自确定性 PSI 扫描（不猜），LLM 只给每个节点补一句"在这里干什么"。反射/AOP 断链处建议用 Arthas `trace` 运行时确认。

### D. git 考古 `Alt+Shift+G`

选中一段代码 → 结合 `git blame` + 相关提交 → 中文回答"这段代码为什么这样写"。**git 事实与 LLM 推断分区展示**，推断标注把握程度（[高] 有提交依据 · [中] 有间接依据 · [低] 凭命名/惯例的猜测），防止把 AI 的猜测当成代码历史。

## 安装

1. 到 [Releases](../../releases) 下载 `namelens-idea-plugin-x.y.z.zip`（**不要解压**）
2. IDEA → `Settings → Plugins → ⚙ → Install Plugin from Disk...` → 选择 zip → 重启
3. 配置 LLM（见下）

要求：IntelliJ IDEA **2024.1+**（Community 即可）。

## 配置 LLM

`Settings → Tools → NameLens`，填任意 **OpenAI 兼容**服务的地址和 Key：

| 服务 | Base URL | 模型示例 | 说明 |
|---|---|---|---|
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4.6` / `glm-4-flash`（免费） | 国内直连，新用户有免费额度 |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` | 便宜 |
| 本地 Ollama | `http://localhost:11434/v1` | `qwen2.5-coder` | 完全离线，Key 随便填 |

单次解释约 1-3k token（几厘钱）；术语表跑一遍后缓存命中率高，日常开销可忽略。

## 常见问题

**Q: 术语表/链路图提示"正在建立索引"？**
等 IDEA 右下角索引进度条结束再用（首次打开项目需要几分钟）。

**Q: 卡片里的颜色在浅色/深色主题下都清楚吗？**
所有颜色按主题深浅自动切换，两种主题都做了对比度适配。

**Q: 代码隐私？**
只把**当前方法**的签名+方法体（截断至 200 行）和调用处上下文发给 LLM，不含其他文件；链路图只发送类名/注解等结构信息；配 Ollama 可完全不出本机。缓存存在项目目录下，建议把 `.namelens-cache/` 加入 `.gitignore`（插件不会自动改你的 git 配置）。

**Q: 和通义灵码/Translation 插件冲突吗？**
不冲突，互补使用更佳。见 [docs/00-现成工具安装清单.md](docs/00-现成工具安装清单.md)——还包含看开源项目的 DeepWiki/GitDiagram 等免部署工具推荐。

## 自己构建

```bash
git clone https://github.com/zhuoling-Liu66/namelens.git
cd namelens/idea-plugin
./gradlew buildPlugin
# 产物: build/distributions/namelens-idea-plugin-x.y.z.zip
```

- JDK 17+；Gradle wrapper 已配置国内镜像
- 开发调试：`./gradlew runIde`（拉起沙箱 IDEA）
- **调提示词不用动代码**：所有 prompt 在 [`prompts/`](prompts) 目录，随构建打进插件

## 项目结构

```
namelens/
├── idea-plugin/          # IDEA 插件源码（Kotlin）
│   └── src/main/kotlin/com/namelens/
│       ├── action/       # 4 个功能入口
│       ├── llm/          # OpenAI 兼容客户端（零第三方依赖）
│       ├── spring/       # Spring 静态扫描 + Mermaid 生成
│       ├── git/          # git blame/log 封装
│       ├── cache/        # 内容寻址缓存
│       └── ...
├── prompts/              # LLM 提示词模板（单一来源，可独立调优）
├── docs/                 # 配套工具清单、deepwiki-open 部署参考
└── tools/npe-test/       # Swing HTML 渲染回归测试
```

## Roadmap

- [ ] 悬停即显（无需按快捷键）
- [ ] 链路图支持点击节点跳转源码（网页端）
- [ ] 悬停卡片接入术语表预热结果
- [ ] 插件市场发布

## License

[MIT](LICENSE)
