package com.namelens.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout

import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 设置页：Settings → Tools → NameLens。纯 Swing 实现（避免对不同版本 UI DSL 的兼容问题） */
class NameLensConfigurable : Configurable {

    private val s = NameLensSettings.getInstance()
    private val baseUrlField = JBTextField(s.baseUrl)
    private val apiKeyField = JBTextField(s.apiKey)
    private val modelField = JBTextField(s.model)

    override fun getDisplayName() = "NameLens"

    override fun createComponent(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        val hint = JBLabel(
            "<html><b>LLM 服务（任意 OpenAI 兼容 /chat/completions）</b><br>" +
                "智谱 GLM：<b>https://open.bigmodel.cn/api/paas/v4</b>，模型 glm-4.6（或免费的 glm-4-flash）<br>" +
                "DeepSeek：<b>https://api.deepseek.com</b>，模型 deepseek-chat<br>" +
                "本地 Ollama：<b>http://localhost:11434/v1</b>，API Key 随便填，模型如 qwen2.5-coder</html>"
        )
        add(hint, BorderLayout.NORTH)
        val form = JPanel(GridLayout(3, 2, 8, 8))
        form.add(JBLabel("API Base URL:"))
        form.add(baseUrlField)
        form.add(JBLabel("API Key:"))
        form.add(apiKeyField)
        form.add(JBLabel("模型:"))
        form.add(modelField)
        add(form, BorderLayout.CENTER)
    }

    override fun isModified(): Boolean =
        baseUrlField.text != s.baseUrl || apiKeyField.text != s.apiKey || modelField.text != s.model

    override fun apply() {
        s.baseUrl = baseUrlField.text.trim()
        s.apiKey = apiKeyField.text.trim()
        s.model = modelField.text.trim()
    }

    override fun reset() {
        baseUrlField.text = s.baseUrl
        apiKeyField.text = s.apiKey
        modelField.text = s.model
    }

    override fun getPreferredFocusedComponent(): JComponent? = baseUrlField
}
