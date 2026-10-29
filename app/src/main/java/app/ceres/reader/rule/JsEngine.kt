package app.ceres.reader.rule

import com.google.gson.GsonBuilder
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.Wrapper
import java.util.concurrent.ConcurrentHashMap

object JsEngine {
    private val wrapFactory = WrapFactory().apply { isJavaPrimitiveWrap = false }

    private val factory = object : ContextFactory() {
        override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
            Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
            else -> super.hasFeature(cx, featureIndex)
        }

        override fun makeContext(): Context = super.makeContext().apply {
            isInterpretedMode = true
            languageVersion = Context.VERSION_ES6
            wrapFactory = this@JsEngine.wrapFactory
        }
    }

    private val sharedScope: ScriptableObject by lazy {
        val cx = factory.enterContext()
        try {
            cx.initStandardObjects()
        } finally {
            Context.exit()
        }
    }

    private val libScopes = ConcurrentHashMap<String, Scriptable>()

    private val scripts = object : LinkedHashMap<String, Script>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?) = size > 256
    }

    private fun compile(cx: Context, js: String): Script =
        synchronized(scripts) { scripts[js] } ?: cx.compileString(js, "rule", 1, null).also {
            synchronized(scripts) { scripts[js] = it }
        }

    /** Returns the syntax error message, or null when [js] compiles. */
    fun syntaxError(js: String): String? {
        val cx = factory.enterContext()
        return try {
            cx.compileString(js, "check", 1, null)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            Context.exit()
        }
    }

    /** Pays Rhino's one-off initialisation cost off the critical path. */
    fun warmUp() {
        runCatching { eval("JSON.stringify({a: [1]})", emptyMap()) }
    }

    private fun libScope(cx: Context, jsLib: String): Scriptable = libScopes.getOrPut(jsLib) {
        val scope = cx.newObject(sharedScope)
        scope.prototype = sharedScope
        scope.parentScope = null
        runCatching { cx.evaluateString(scope, jsLib, "jsLib", 1, null) }
            .onFailure { RuleLog.log("jsLib 执行出错: ${it.message}") }
        scope
    }

    fun eval(js: String, bindings: Map<String, Any?>, jsLib: String? = null): Any? {
        val cx = factory.enterContext()
        try {
            val parent = jsLib?.takeIf { it.isNotBlank() }?.let { libScope(cx, it) } ?: sharedScope
            val scope = cx.newObject(parent)
            scope.prototype = parent
            scope.parentScope = null
            bindings.forEach { (k, v) ->
                ScriptableObject.putProperty(scope, k, Context.javaToJS(v, scope))
            }
            return unwrap(compile(cx, js).exec(cx, scope))
        } finally {
            Context.exit()
        }
    }

    fun unwrap(value: Any?): Any? = when (value) {
        null, is Undefined -> null
        Scriptable.NOT_FOUND -> null
        is ConsString -> value.toString()
        is Wrapper -> unwrap(value.unwrap())
        is NativeArray -> value.map { unwrap(it) }
        is NativeObject -> LinkedHashMap<String, Any?>().also { map ->
            value.ids.forEach { id ->
                map[id.toString()] = unwrap(
                    if (id is Int) value.get(id, value) else value.get(id.toString(), value)
                )
            }
        }
        is CharSequence -> value.toString()
        else -> value
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** Stringifies JS/Java values the way rules expect (no trailing `.0`, lists by line). */
    fun toStr(value: Any?): String = when (val v = unwrap(value)) {
        null -> ""
        is String -> v
        is Double -> if (v % 1.0 == 0.0 && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> toStr(v.toDouble())
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> gson.toJson(v)
        is List<*> -> v.joinToString("\n") { toStr(it) }
        is ByteArray -> String(v)
        else -> v.toString()
    }
}
