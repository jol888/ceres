package app.ceres.reader.rule

import org.junit.Assume.assumeTrue
import org.junit.Test

class JsFeatureProbe {
    @Test
    fun probe() {
        assumeTrue(System.getenv("CERES_BENCH") == "1")
        val cases = linkedMapOf(
            "arrow" to "[1,2].map(x => x * 2).join(',')",
            "template literal" to "var a = 1; `v=\${a}`",
            "let/const" to "let a = 1; const b = 2; { let a = 5; } a + b",
            "const in loop" to "var s=0; for (const x of [1,2,3]) { s += x } s",
            "destructuring" to "var {a, b} = {a: 1, b: 2}; var [c] = [3]; a + b + c",
            "spread" to "Math.max(...[1, 5, 3])",
            "default param" to "function f(a, b = 2) { return a + b } f(1)",
            "Object.entries" to "Object.entries({a: 1}).length",
            "Object.assign" to "Object.assign({}, {a: 1}).a",
            "Array.from" to "Array.from('ab').length",
            "includes" to "[1,2].includes(2) && 'abc'.includes('b')",
            "padStart" to "'5'.padStart(3, '0')",
            "Map/Set" to "new Map([[1,2]]).get(1) + new Set([1,1]).size",
            "class" to "class A { f() { return 1 } } new A().f()",
            "Promise" to "typeof Promise",
            "Packages.jsoup" to "Packages.org.jsoup.Jsoup.parse('<p>x</p>').text()",
            "org.jsoup direct" to "org.jsoup.Jsoup.parse('<p>y</p>').text()",
            "JavaImporter" to "var J = new JavaImporter(Packages.org.jsoup); with (J) { Jsoup.parse('<b>z</b>').text() }",
            "java.lang via Packages" to "String(new Packages.java.lang.String('q'))",
            "Java string methods" to "var s = new Packages.java.lang.String('a,b'); s.split(',').length",
            "JSON" to "JSON.stringify({a: [1]})",
            "regex named group" to "'2024-01'.match(/(?<y>\\d{4})/).groups.y",
            "optional chaining" to "var o = {}; o?.a?.b === undefined",
            "nullish" to "null ?? 'd'",
            "exponent" to "2 ** 3",
            "async fn" to "async function f() { return 1 } typeof f",
            "generator" to "function* g() { yield 1 } g().next().value",
            "String.raw" to "String.raw`a\\nb`",
            "Number.isInteger" to "Number.isInteger(5)",
            "Array.find" to "[1,2,3].find(x => x > 1)",
            "Symbol.iterator" to "typeof Symbol.iterator",
        )
        var ok = 0
        cases.forEach { (name, js) ->
            val r = runCatching { JsEngine.toStr(JsEngine.eval(js, emptyMap())) }
            if (r.isSuccess) ok++
            println("[js] %-24s %s".format(name, r.fold({ "ok  -> $it" }, { "FAIL " + (it.message ?: it.javaClass.simpleName).take(70) })))
        }
        println("[js] $ok / ${cases.size} supported")
    }
}
