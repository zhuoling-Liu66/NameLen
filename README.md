# codeassistance — 代码伴读工具集（NameLens「见名知意」）

为英语一般、主要读 Java/Spring 代码的开发者打造的"看代码"工具集：**现成轮子组装 + 一个自研 IDEA 小插件**。

## 目录结构

```
codeassistance/
├── docs/
│   ├── 00-现成工具安装清单.md   # 第0层：灵码/CodeGeeX/Translation/DeepWiki（零代码，先装这些）
│   └── deepwiki-open/           # 可选：私有仓库整库 wiki 自托管部署参考
├── prompts/                     # LLM prompt 模板（单一来源，打进插件 jar，可独立调优）
├── idea-plugin/                 # ★ 自研 IntelliJ IDEA 插件 NameLens（唯一代码主体）
│   ├── build.gradle.kts         # IntelliJ Platform Gradle Plugin 2.x，目标 IC 2024.1+（since 241）
│   └── src/main/kotlin/com/namelens/
└── tools/                       # 本地构建工具（gitignore，不入库）
```

## NameLens 插件功能

| 功能 | 入口 | 说明 |
|---|---|---|
| **A. 中文解释卡片**（核心） | 编辑器里光标放方法上按 `Alt+Shift+E` 或右键 → NameLens: 中文解释此方法 | 命名逐词拆解（`resolveUserPrincipal` → 解析/用户/已认证主体）、入参、返回值、作用；来源行号可点击跳转；按"方法体内容哈希"缓存，命中秒出 |
| B. 仓库术语表 | 菜单 Tools → NameLens 见名知意 → 生成仓库术语表 | 扫描全仓类名/接口/高频方法/字段，LLM 分批生成中英对照 → `docs/namelens-glossary.md` + 工具窗口（可搜索） |
| C. 请求链路图 | 菜单 Tools → NameLens 见名知意 → 生成请求链路图 | 静态扫描 Spring（Security/Filter/Interceptor/Controller/Service/Mapper/Redis/MQ/DB）→ 结构图（确定性，无幻觉），LLM 只补每节点一句语义 → Mermaid 渲染在 NameLens 工具窗口，组件列表双击跳源码 |
| D. git 考古 | 选中代码按 `Alt+Shift+G` | git blame + 相关提交 → LLM 中文解释"为什么这样写"；事实与推断分区，标注置信度 |

LLM 走任意 OpenAI 兼容接口，配置：**Settings → Tools → NameLens**（默认智谱 `https://open.bigmodel.cn/api/paas/v4`，模型 `glm-4.6`；免费可改 `glm-4-flash`；DeepSeek/Ollama 填对应地址即可）。

## 构建 / 安装

前置：JDK 17+（本机已满足）。

```bash
cd idea-plugin
./gradlew buildPlugin          # 产物：idea-plugin/build/distributions/namelens-idea-plugin-0.1.0.zip
```

IDEA 中安装：`Settings → Plugins → ⚙ → Install Plugin from Disk...` → 选上面的 zip → 重启。

开发调试：`./gradlew runIde`（拉起沙箱 IDEA）。

> 注意：本机当前只有 PyCharm CE 和 VS Code，插件需要 **IntelliJ IDEA**（Community 免费版即可，含 Java 支持）。

## 设计要点

- **结构不靠 LLM**：链路图的节点/连线来自确定性静态扫描（PSI + import 特征），LLM 只补语义注释，杜绝"幻觉架构"
- **防幻觉**：解释卡片展示可点击的来源定义行号；prompt 强约束"只依据给定源码"；git 考古把 git 事实与 LLM 推断分区
- **缓存省钱**：解释按"方法体哈希"缓存在项目下 `.namelens-cache/`；术语表生成后悬停/复用零成本
- **prompt 版本化**：全部 prompt 在 `prompts/*.md`，改提示词不用动代码
- 依赖极简：LLM 调用用 JDK 自带 HttpClient + 平台自带 Gson，零第三方运行时依赖

## 调研结论速查（详见 docs/00-现成工具安装清单.md）

- 直接用：灵码/CodeGeeX（选中解释）、Translation（悬停译 JavaDoc）、deepwiki.com / gitdiagram.com（公开仓库整库 wiki+架构图）
- 放弃：Sourcetrail（归档5年）、RepoAgent（停更仅Python）、Continue（被Cursor收购后归档）、bloop（归档）、Cody（免费版关闭）
