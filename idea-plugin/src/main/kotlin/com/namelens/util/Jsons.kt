package com.namelens.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object Jsons {

    fun str(o: JsonObject, key: String): String {
        val v = o.get(key) ?: return ""
        return if (v.isJsonPrimitive) v.asString else v.toString()
    }

    fun arr(o: JsonObject, key: String): JsonArray = o.getAsJsonArray(key) ?: JsonArray()
}
