package app.ceres.reader.rule

import app.ceres.reader.util.hostOf
import java.util.concurrent.ConcurrentHashMap

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryKv : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Global persistent key-value store; the app swaps in a Room-backed implementation. */
object Kv : KeyValueStore {
    @Volatile
    var store: KeyValueStore = MemoryKv()
    override fun get(key: String): String? = store.get(key)
    override fun put(key: String, value: String?) = store.put(key, value)
}

/** Debug log sink for rule execution; set by the source debugger. */
object RuleLog {
    @Volatile
    var sink: ((String) -> Unit)? = null
    fun log(msg: Any?) {
        sink?.invoke(msg?.toString() ?: "null")
    }
}

private val twoLevelSuffix = setOf("com", "net", "org", "gov", "edu", "co", "ac")

fun cookieDomain(url: String): String {
    val host = hostOf(url).ifEmpty { url }
    if (host.all { it.isDigit() || it == '.' }) return host
    val parts = host.split('.')
    if (parts.size <= 2) return host
    val keep = if (parts[parts.size - 2] in twoLevelSuffix && parts.last().length == 2) 3 else 2
    return parts.takeLast(keep).joinToString(".")
}

/** Exposed to JS as `cookie`; mirrors Legado's CookieStore API. */
object CookieStore {
    private fun k(url: String) = "cookie_${cookieDomain(url)}"

    fun getCookie(url: String): String = Kv.get(k(url)).orEmpty()

    fun getKey(url: String, key: String): String = cookieToMap(getCookie(url))[key].orEmpty()

    fun setCookie(url: String, cookie: String?) {
        Kv.put(k(url), cookie?.takeIf { it.isNotBlank() })
    }

    fun replaceCookie(url: String, cookie: String) {
        if (cookie.isBlank()) return
        val merged = cookieToMap(getCookie(url)) + cookieToMap(cookie)
        setCookie(url, mapToCookie(merged))
    }

    fun removeCookie(url: String) = setCookie(url, null)

    fun cookieToMap(cookie: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        cookie.split(';').forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                val key = pair.substring(0, i).trim()
                if (key.isNotEmpty()) map[key] = pair.substring(i + 1).trim()
            }
        }
        return map
    }

    fun mapToCookie(map: Map<String, String>): String =
        map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** Exposed to JS as `cache`; values may carry an expiry in seconds. */
object CacheStore {
    private val memory = ConcurrentHashMap<String, Any>()

    @JvmOverloads
    fun put(key: String, value: Any?, saveTime: Int = 0) {
        if (value == null) {
            delete(key); return
        }
        val deadline = if (saveTime > 0) System.currentTimeMillis() + saveTime * 1000L else 0L
        Kv.put("cache_$key", "$deadline|${JsEngine.toStr(value)}")
    }

    fun get(key: String): String? {
        val raw = Kv.get("cache_$key") ?: return null
        val sep = raw.indexOf('|')
        if (sep < 0) return raw
        val deadline = raw.substring(0, sep).toLongOrNull() ?: 0L
        if (deadline > 0 && deadline < System.currentTimeMillis()) {
            delete(key); return null
        }
        return raw.substring(sep + 1)
    }

    fun delete(key: String) = Kv.put("cache_$key", null)

    @JvmOverloads
    fun putFile(key: String, value: String, saveTime: Int = 0) = put(key, value, saveTime)
    fun getFile(key: String): String? = get(key)

    fun putMemory(key: String, value: Any) {
        memory[key] = value
    }

    fun getFromMemory(key: String): Any? = memory[key]
    fun deleteMemory(key: String) {
        memory.remove(key)
    }
}
