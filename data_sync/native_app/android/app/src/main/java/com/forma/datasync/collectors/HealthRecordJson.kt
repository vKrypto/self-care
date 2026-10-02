package com.forma.datasync.collectors

import java.lang.reflect.Modifier
import java.time.Instant
import java.time.temporal.TemporalAccessor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Preserve all SDK public properties, including samples, stages, exercise segments, units,
 * origin and source IDs. Java getter reflection avoids kotlin-reflect and tracks new SDK
 * properties. R8 must keep these SDK getters (see the application ProGuard rules).
 * Unsupported objects and failed getters fail the record instead of silently discarding data.
 */
internal object HealthRecordJson {
    fun encode(value: Any?): Any = encode(value, 0)

    private fun encode(value: Any?, depth: Int): Any {
        require(depth <= 24) { "Health record nesting exceeds serializer limit" }
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray, is String, is Boolean,
            is Byte, is Short, is Int, is Long -> value
            is Double -> finite(value)
            is Float -> finite(value.toDouble())
            is Instant -> JSONObject().put("iso", value.toString()).put("epoch_ms", value.toEpochMilli())
            is TemporalAccessor -> value.toString()
            is java.time.Duration -> JSONObject().put("iso", value.toString()).put("milliseconds", value.toMillis())
            is Enum<*> -> value.name
            is Collection<*> -> JSONArray().also { result -> value.forEach { result.put(encode(it, depth + 1)) } }
            is Array<*> -> JSONArray().also { result -> value.forEach { result.put(encode(it, depth + 1)) } }
            is Map<*, *> -> JSONObject().also { result ->
                value.forEach { (key, entry) -> result.put(key.toString(), encode(entry, depth + 1)) }
            }
            else -> encodeSdkObject(value, depth)
        }
    }

    private fun finite(value: Double): Any {
        require(value.isFinite()) { "Health record contains a non-finite measurement" }
        return value
    }

    private fun encodeSdkObject(value: Any, depth: Int): JSONObject {
        require(value.javaClass.name.startsWith("androidx.health.connect.client.")) {
            "Unsupported Health Connect property type"
        }
        val result = JSONObject().put("_type", value.javaClass.simpleName)
        value.javaClass.methods.asSequence()
            .filter { method ->
                Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() && method.returnType != Void.TYPE &&
                    method.name != "getClass" && !method.isSynthetic &&
                    (method.name.startsWith("get") || method.name.startsWith("is") || method.name == "hasExplicitTime")
            }
            .sortedBy { it.name }
            .forEach { method ->
                val property = when {
                    method.name.startsWith("get") -> method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
                    method.name.startsWith("is") -> method.name.substring(2).replaceFirstChar { it.lowercaseChar() }
                    else -> method.name
                }
                result.put(property, encode(method.invoke(value), depth + 1))
            }
        return result
    }
}
