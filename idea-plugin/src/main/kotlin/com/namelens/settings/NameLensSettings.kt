package com.namelens.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "NameLensSettings", storages = [Storage("namelens.xml")])
class NameLensSettings : PersistentStateComponent<NameLensSettings> {

    /** OpenAI 兼容服务根地址（到版本路径，不含 /chat/completions） */
    var baseUrl: String = "https://open.bigmodel.cn/api/paas/v4"

    var apiKey: String = ""

    var model: String = "glm-4.6"

    val chatCompletionsUrl: String
        get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun getState(): NameLensSettings = this

    override fun loadState(state: NameLensSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): NameLensSettings =
            ApplicationManager.getApplication().getService(NameLensSettings::class.java)
    }
}
