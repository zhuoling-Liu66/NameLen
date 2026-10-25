# deepwiki-open 自托管部署（可选，M5）

给**私有仓库**生成整库 wiki + 架构图（公开仓库直接用 deepwiki.com 即可）。
上游：https://github.com/AsyncFuncAI/deepwiki-open （MIT，18k★，活跃维护，调研日期 2026-10-25）

## 部署步骤

```bash
git clone --depth 1 https://github.com/AsyncFuncAI/deepwiki-open.git
cd deepwiki-open
cp .env.example .env        # 编辑 .env，填入下方变量
docker compose up -d --build
# 浏览器访问 http://localhost:3000
```

## .env 关键变量（以上游 README 为准，此处为调研确认的常用项）

| 变量 | 说明 |
|---|---|
| `LLM_PROVIDER` | 提供商：`openai` / `openrouter` / `gemini` / `ollama` |
| `OPENAI_API_KEY` | OpenAI 兼容服务的 Key（智谱/DeepSeek 走 LiteLLM 变体时填在 LiteLLM 配置里） |
| `OPENROUTER_API_KEY` | OpenRouter（国内可直连，模型多） |
| `GEMINI_API_KEY` | Google |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` | 向量检索用，可用 openai 兼容或本地 |

国内网络推荐两条路：
1. **OpenRouter**：充值即用，无需代理，模型选 `anthropic/claude-sonnet-*` 或 `deepseek/deepseek-chat`
2. **完全离线**：用上游提供的 `Dockerfile-ollama-local`（本地 Ollama 跑 qwen2.5 等，代码不出本机）
3. **国产 API**：用 `Dockerfile-litellm` 变体，通过 LiteLLM 网关接智谱 GLM / DeepSeek

## 资源要求

- 内存：compose 默认 `mem_limit 6g / reservation 2g`
- 端口：3000（Web）、8001（API）
- 数据卷：`~/.adalflow`（仓库缓存与向量数据）、`./api/logs`

## 与 NameLens 插件的分工

| 场景 | 用谁 |
|---|---|
| 写代码时选中方法要秒出中文解释 | NameLens 插件（功能 A） |
| 接手新项目看整体结构 | NameLens 链路图（功能 C，结构精确）+ deepwiki-open wiki（全景文档，含 Ask 追问） |
| 离线/隐私 | NameLens 可配 Ollama；deepwiki-open 用 ollama 变体 |
