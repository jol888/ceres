package app.ceres.reader.util

import app.ceres.reader.data.model.BookInfoRule
import app.ceres.reader.data.model.BookListRule
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.ContentRule
import app.ceres.reader.data.model.ReviewRule
import app.ceres.reader.data.model.TocRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken

private val PLAIN_GSON: Gson = GsonBuilder()
    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
    .disableHtmlEscaping()
    .create()

/** Old Legado sources sometimes store rule objects as JSON strings. */
private inline fun <reified T> ruleDeserializer() = JsonDeserializer<T?> { json, _, _ ->
    when {
        json.isJsonObject -> PLAIN_GSON.fromJson(json, T::class.java)
        json.isJsonPrimitive -> runCatching {
            PLAIN_GSON.fromJson(json.asString, T::class.java)
        }.getOrNull()
        else -> null
    }
}

val GSON: Gson = GsonBuilder()
    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
    .disableHtmlEscaping()
    .registerTypeAdapter(BookListRule::class.java, ruleDeserializer<BookListRule>())
    .registerTypeAdapter(BookInfoRule::class.java, ruleDeserializer<BookInfoRule>())
    .registerTypeAdapter(TocRule::class.java, ruleDeserializer<TocRule>())
    .registerTypeAdapter(ContentRule::class.java, ruleDeserializer<ContentRule>())
    .registerTypeAdapter(ReviewRule::class.java, ruleDeserializer<ReviewRule>())
    // Some sources write `header` etc. as a JSON object instead of a string.
    .registerTypeAdapter(String::class.java, JsonDeserializer { json, _, _ ->
        if (json.isJsonPrimitive) json.asString else if (json.isJsonNull) null else json.toString()
    })
    .create()

fun parseStringMap(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { (k, v) -> k to jsonElementToString(v) }
    }.getOrDefault(emptyMap())
}

fun jsonElementToString(v: JsonElement): String =
    if (v.isJsonPrimitive) v.asString else if (v.isJsonNull) "" else v.toString()

fun parseAnyMap(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        PLAIN_GSON.fromJson<Map<String, Any?>>(json, object : TypeToken<Map<String, Any?>>() {}.type)
    }.getOrNull() ?: emptyMap()
}

fun toJsonString(any: Any?): String = PLAIN_GSON.toJson(any)

/** Accepts a single source object or an array of sources. */
fun parseBookSources(json: String): List<BookSource> {
    val root = JsonParser.parseString(json.trim())
    val elements = when {
        root.isJsonArray -> root.asJsonArray.toList()
        root.isJsonObject -> listOf(root)
        else -> emptyList()
    }
    return elements.mapNotNull { el ->
        runCatching { GSON.fromJson(el, BookSource::class.java) }.getOrNull()
    }.filter { it.bookSourceUrl.isNotBlank() }
}

fun String.isJsonLike(): Boolean {
    val s = trim()
    return (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))
}
