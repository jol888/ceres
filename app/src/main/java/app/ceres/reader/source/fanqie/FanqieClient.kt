package app.ceres.reader.source.fanqie

import app.ceres.reader.rule.Http
import app.ceres.reader.rule.Kv
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

class FanqieException(message: String) : Exception(message)

/**
 * 番茄 App 接口的最小客户端。
 *
 * 只做书源需要的四件事：书籍详情、目录、正文、段评（统计/列表/回复）。
 * 搜索结果走公开的 novel.snssdk.com 接口，不需要签名，因此不在这里。
 *
 * 设备身份和正文密钥都存进 [Kv]，重启后复用；密钥解不开时会自动重新注册一次。
 */
object FanqieClient {

    private const val HOST = "api5-normal-sinfonlinec.fqnovel.com"
    private const val REGISTER_HOST = "log.snssdk.com"
    private const val REGISTER_PATH = "/service/2/device_register/"
    private const val DEVICE_KEY = "fanqie_device"
    private const val V1_KEY = "fanqie_v1_key"
    private const val HARD_KEY_HEX = "ac25c67ddd8f38c1b37a2348828e222e"
    private const val KEY_TTL_MS = 7L * 24 * 3600 * 1000
    private const val COMMENT_PAGE_SIZE = 20
    private const val REPLY_PAGE_SIZE = 50
    /** 番茄把章节标题放在 10000 这个特殊段落下标上。 */
    private const val CHAPTER_TITLE_PARA_INDEX = 10000

    /** 番茄正文里的章节标题块；标题单独由阅读器显示，正文中不要重复。 */
    private val CHAPTER_TITLE_BLOCK =
        Regex("(?is)<h1\\b[^>]*\\bchapterTitle\\b[^>]*>.*?</h1>")

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val random = SecureRandom()
    private val lock = Any()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var deviceCache: FanqieDevice? = null

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 书籍详情，返回归一化 JSON。 */
    fun bookInfo(bookId: String): String {
        val d = device()
        val q = commonQuery(d)
        q["book_id"] = bookId
        val root = signedCall(d, "/reading/bookapi/detail/v1", q)
        checkCode(root, "书籍详情")
        val data = root.getAsJsonObject("data") ?: throw FanqieException("书籍详情缺少 data")
        val tags = (data.string("pure_category_tags") ?: data.string("category")).orEmpty()
        val out = JsonObject().apply {
            addProperty("bookId", data.string("book_id") ?: bookId)
            addProperty("name", data.string("book_name").orEmpty())
            addProperty("author", data.string("author").orEmpty())
            addProperty("intro", (data.string("abstract") ?: data.string("book_abstract_v2")).orEmpty())
            addProperty(
                "coverUrl",
                https(data.string("thumb_url") ?: data.string("audio_thumb_uri")).orEmpty(),
            )
            add("kind", JsonArray().apply {
                tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            })
            addProperty("wordCount", formatWordCount(data.string("word_number")))
            addProperty("lastChapter", data.string("last_chapter_title").orEmpty())
            addProperty("tocUrl", FanqieUrls.toc(bookId))
        }
        return gson.toJson(out)
    }

    /** 目录原始 JSON（公开 web 接口），书源里用 JSONPath 取。 */
    fun toc(bookId: String): String {
        val url = "https://fanqienovel.com/api/reader/directory/detail?bookId=$bookId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DEFAULT_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val text = execute(request, "目录")
        val root = parseObject(text)
        checkCode(root, "目录")
        return text
    }

    /** 章节正文（已解密、已解压，并去掉重复的章节标题块）。 */
    fun chapter(bookId: String, itemId: String): String {
        val d = device()
        // 必须先注册密钥：新设备在 registerkey 之前拉正文会拿到 crypt_status=2 的 "Invalid"。
        var key = ensureKey(d)
        var item = fetchChapterItem(d, bookId, itemId)
        return try {
            stripChapterTitle(decodeChapter(item, key))
        } catch (first: Exception) {
            // 密钥过期/轮换时接口仍返回 crypt_status=0，只有解密会失败；重注册后重新拉取。
            invalidateKey()
            key = ensureKey(d)
            item = fetchChapterItem(d, bookId, itemId)
            try {
                stripChapterTitle(decodeChapter(item, key))
            } catch (second: Exception) {
                throw FanqieException("正文解密失败: ${second.message ?: first.message}")
            }
        }
    }

    /**
     * 番茄正文开头带一个 `<h1 class="chapterTitle…">` 标题块，而阅读器已经单独显示
     * 目录里的章节名。留着会重复显示，也会让段评下标整体偏移；这里直接去掉。
     */
    private fun stripChapterTitle(html: String): String = html.replace(CHAPTER_TITLE_BLOCK, "")

    /** 段评统计，归一化成 `{"items":[{"paraIndex":2,"count":5605}]}`。 */
    fun reviewSummary(bookId: String, itemId: String): String {
        val d = device()
        val q = commentQuery(d)
        val root = signedCall(d, "/novel/commentapi/idea/list/$itemId/v1/", q)
        checkCode(root, "段评统计")
        val data = root.getAsJsonObject("data")?.getAsJsonObject("data") ?: JsonObject()
        val rows = data.entrySet().mapNotNull { (k, v) ->
            val index = k.toIntOrNull() ?: return@mapNotNull null
            // 0 是整章汇总；10000 是章节标题（正文里已剥掉），都不能当正文段落。
            if (index <= 0 || index == CHAPTER_TITLE_PARA_INDEX || !v.isJsonObject) {
                return@mapNotNull null
            }
            val count = v.asJsonObject.int("count")
            if (count <= 0) null else index to count
        }.sortedBy { it.first }
        val items = JsonArray()
        rows.forEach { (index, count) ->
            items.add(JsonObject().apply {
                // 番茄正文段落按 0 开始编号，阅读器段落按 1 开始编号，且标题块已去掉。
                addProperty("paraIndex", index + 1)
                addProperty("count", count)
            })
        }
        return gson.toJson(JsonObject().apply { add("items", items) })
    }

    /** 某一段的评论列表。 */
    fun reviewDetail(bookId: String, itemId: String, paraIndex: Int, cursor: String): String {
        val root = reviewPageRoot(bookId, itemId, paraIndex, cursor)
        val data = root.getAsJsonObject("data") ?: throw FanqieException("段评列表缺少 data")
        val items = JsonArray()
        data.getAsJsonArray("data_list")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val row = el.asJsonObject
            val comment = row.getAsJsonObject("comment") ?: row
            items.add(commentItem(comment, isReply = false))
        }
        val info = data.getAsJsonObject("common_list_info")
        val out = JsonObject().apply { add("items", items) }
        val nextCursor = info?.string("cursor")
        if (info?.bool("has_more") == true && !nextCursor.isNullOrBlank()) {
            out.addProperty("next", FanqieUrls.reviewDetail(bookId, itemId, paraIndex, "c$nextCursor"))
        }
        return gson.toJson(out)
    }

    private fun reviewPageRoot(
        bookId: String,
        itemId: String,
        paraIndex: Int,
        cursor: String,
    ): JsonObject {
        val d = device()
        val body = JsonObject().apply {
            addProperty("chapter_id", itemId)
            addProperty("comment_source", 2)
            addProperty("comment_type", 1)
            addProperty("group_type", 15)
            addProperty("count", COMMENT_PAGE_SIZE)
            addProperty("sort", 1)
            addProperty("cursor", cursor)
            add("business_param", JsonObject().apply {
                addProperty("book_id", bookId)
                addProperty("item_version", "0")
                // 阅读器传进来的是 1-based 段落号；番茄正文段落是 0-based。
                addProperty("para_index", (paraIndex - 1).coerceAtLeast(0))
            })
        }
        val root = signedCall(
            d, "/novel/commentapi/comment/list/$itemId/v1/", commentQuery(d), gson.toJson(body),
        )
        checkCode(root, "段评列表")
        return root
    }

    /** 某条评论的回复。 */
    fun replies(reviewId: String, cursor: String): String {
        val d = device()
        val body = JsonObject().apply {
            addProperty("comment_id", reviewId)
            addProperty("comment_source", 5)
            addProperty("comment_type", 1)
            addProperty("group_type", 15)
            addProperty("count", REPLY_PAGE_SIZE)
            addProperty("cursor", cursor)
        }
        val root = signedCall(
            d, "/novel/commentapi/reply/list/$reviewId/v1/", commentQuery(d), gson.toJson(body),
        )
        checkCode(root, "回复列表")
        val data = root.getAsJsonObject("data") ?: throw FanqieException("回复列表缺少 data")
        val items = JsonArray()
        data.getAsJsonArray("reply_list")?.forEach { el ->
            if (el.isJsonObject) items.add(commentItem(el.asJsonObject, isReply = true))
        }
        val info = data.getAsJsonObject("comment_list_info")
        val out = JsonObject().apply { add("items", items) }
        val nextCursor = info?.string("cursor")
        if (info?.bool("has_more") == true && !nextCursor.isNullOrBlank()) {
            out.addProperty("next", FanqieUrls.replies(reviewId, "c$nextCursor"))
        }
        return gson.toJson(out)
    }

    // ------------------------------------------------------------------
    // 章节正文
    // ------------------------------------------------------------------

    private fun fetchChapterItem(d: FanqieDevice, bookId: String, itemId: String): JsonObject {
        val q = commonQuery(d)
        q["item_ids"] = itemId
        q["key_register_ts"] = "0"
        q["book_id"] = bookId
        q["req_type"] = "1"
        val root = signedCall(d, "/reading/reader/batch_full/v", q)
        checkCode(root, "正文")
        val data = root.getAsJsonObject("data") ?: throw FanqieException("正文缺少 data")
        val element = data.get(itemId) ?: throw FanqieException("正文没有返回章节 $itemId")
        return when {
            element.isJsonArray -> element.asJsonArray.firstOrNull { it.isJsonObject }?.asJsonObject
            element.isJsonObject -> element.asJsonObject
            else -> null
        } ?: throw FanqieException("章节 $itemId 的结构异常")
    }

    private fun decodeChapter(item: JsonObject, keyHex: String): String {
        val content = item.string("content") ?: item.string("origin_content") ?: ""
        if (content.isBlank()) throw FanqieException("章节内容为空")
        var bytes = if (item.int("crypt_status") == 0) {
            val raw = Base64.getMimeDecoder().decode(content)
            if (raw.size <= 16) throw FanqieException("章节密文长度异常")
            FanqieCrypto.aesCbcDecrypt(
                raw.copyOfRange(16, raw.size), FanqieCrypto.hex(keyHex), raw.copyOfRange(0, 16),
            )
        } else {
            content.toByteArray(Charsets.UTF_8)
        }
        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (gzip || item.int("compress_status") > 0) {
            bytes = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrDefault(bytes)
        }
        return String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // 设备身份 / 正文密钥
    // ------------------------------------------------------------------

    private fun device(): FanqieDevice {
        deviceCache?.let { return it }
        synchronized(lock) {
            deviceCache?.let { return it }
            val stored = Kv.get(DEVICE_KEY)?.let { raw ->
                runCatching { deviceFromJson(parseObject(raw)) }.getOrNull()
            }
            val d = stored ?: registerDevice()
            if (stored == null) Kv.put(DEVICE_KEY, gson.toJson(deviceToJson(d)))
            deviceCache = d
            return d
        }
    }

    private fun registerDevice(): FanqieDevice {
        val profile = FanqieDevice(
            deviceId = "",
            installId = "",
            cdid = UUID.randomUUID().toString(),
            openudid = randomHex(8),
            clientudid = UUID.randomUUID().toString(),
        )
        val now = System.currentTimeMillis()
        val query = listOf(
            "ac=wifi",
            "channel=${profile.channel}",
            "aid=1967",
            "app_name=novelapp",
            "version_code=${profile.versionCode}",
            "version_name=${profile.versionName}",
            "device_platform=android",
            "ssmix=a",
            "device_type=${profile.deviceType}",
            "device_brand=${profile.deviceBrand}",
            "language=${profile.language}",
            "os_api=${profile.osApi}",
            "os_version=${profile.osVersion}",
            "openudid=${profile.openudid}",
            "manifest_version_code=${profile.versionCode}",
            "resolution=${profile.resolution}",
            "dpi=${profile.dpi}",
            "update_version_code=${profile.versionCode}",
            "_rticket=${now + random.nextInt(1000)}",
            "cdid=${profile.cdid}",
        ).joinToString("&")

        val header = JsonObject().apply {
            addProperty("display_name", "番茄小说")
            addProperty("update_version_code", profile.versionCode.toLong())
            addProperty("manifest_version_code", profile.versionCode.toLong())
            addProperty("aid", 1967)
            addProperty("channel", profile.channel)
            addProperty("package", "com.dragon.read")
            addProperty("app_version", profile.versionName)
            addProperty("version_code", profile.versionCode.toLong())
            addProperty("sdk_version", "3.7.2.cn")
            addProperty("os", "Android")
            addProperty("os_version", profile.osVersion)
            addProperty("os_api", profile.osApi.toInt())
            addProperty("device_model", profile.deviceType)
            addProperty("device_brand", profile.deviceBrand)
            addProperty("device_manufacturer", profile.deviceBrand)
            addProperty("cpu_abi", profile.hostAbi)
            addProperty("density_dpi", profile.dpi.toInt())
            addProperty("resolution", profile.resolution.replace('*', 'x'))
            addProperty("language", profile.language)
            addProperty("timezone", 8)
            addProperty("access", "wifi")
            addProperty("not_request_sender", 0)
            addProperty("rom_version", profile.romVersion)
            addProperty("cdid", profile.cdid)
            addProperty("openudid", profile.openudid)
            addProperty("clientudid", profile.clientudid)
            addProperty("region", "CN")
            addProperty("tz_name", "Asia/Shanghai")
            addProperty("tz_offset", 28800)
            addProperty("sim_region", "cn")
            addProperty("req_id", UUID.randomUUID().toString())
            addProperty("apk_first_install_time", now - 86_400_000L)
            addProperty("is_system_app", 0)
            addProperty("sdk_flavor", "china")
        }
        val body = JsonObject().apply {
            addProperty("magic_tag", "ss_app_log")
            add("header", header)
            addProperty("_gen_time", now)
        }
        val request = Request.Builder()
            .url("https://$REGISTER_HOST$REGISTER_PATH?$query")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", registerUa(profile))
            .header("Accept-Encoding", "identity")
            .post(gson.toJson(body).toRequestBody(jsonMedia))
            .build()
        val root = parseObject(execute(request, "设备注册"))
        val deviceId = root.idString("device_id_str", "device_id")
            ?: throw FanqieException("设备注册没有返回 device_id")
        val installId = root.idString("install_id_str", "install_id")
            ?: throw FanqieException("设备注册没有返回 install_id")
        return profile.copy(deviceId = deviceId, installId = installId)
    }

    private fun ensureKey(d: FanqieDevice): String {
        synchronized(lock) {
            loadKey(d.deviceId)?.let { return it }
            val key = registerKey(d)
            val record = JsonObject().apply {
                addProperty("key", key)
                addProperty("deviceId", d.deviceId)
                addProperty("ts", System.currentTimeMillis())
            }
            Kv.put(V1_KEY, gson.toJson(record))
            return key
        }
    }

    private fun loadKey(deviceId: String): String? {
        val raw = Kv.get(V1_KEY) ?: return null
        val record = runCatching { parseObject(raw) }.getOrNull() ?: return null
        if (record.string("deviceId") != deviceId) return null
        if (System.currentTimeMillis() - record.long("ts") > KEY_TTL_MS) return null
        return record.string("key")?.takeIf { it.length == 32 }
    }

    private fun invalidateKey() = Kv.put(V1_KEY, null)

    private fun registerKey(d: FanqieDevice): String {
        val payload = ByteArray(16)
        val deviceId = d.deviceId.toLongOrNull()
            ?: throw FanqieException("设备号不是数字: ${d.deviceId}")
        for (i in 0 until 8) payload[i] = ((deviceId ushr (i * 8)) and 0xff).toByte()
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val content = Base64.getEncoder().encodeToString(
            iv + FanqieCrypto.aesCbcEncrypt(payload, FanqieCrypto.hex(HARD_KEY_HEX), iv),
        )
        val body = JsonObject().apply {
            addProperty("content", content)
            addProperty("keyver", 1)
        }
        val root = signedCall(
            d, "/reading/crypt/registerkey", commonQuery(d), gson.toJson(body),
        )
        checkCode(root, "注册正文密钥")
        val encrypted = root.getAsJsonObject("data")?.string("key")
            ?: throw FanqieException("注册正文密钥没有返回 key")
        val raw = Base64.getMimeDecoder().decode(encrypted)
        if (raw.size <= 16) throw FanqieException("正文密钥长度异常")
        val plain = FanqieCrypto.aesCbcDecrypt(
            raw.copyOfRange(16, raw.size), FanqieCrypto.hex(HARD_KEY_HEX), raw.copyOfRange(0, 16),
        )
        val key = FanqieCrypto.bytesToHex(plain).uppercase(Locale.US)
        if (key.length != 32) throw FanqieException("正文密钥格式异常")
        return key
    }

    // ------------------------------------------------------------------
    // 签名请求
    // ------------------------------------------------------------------

    private fun signedCall(
        d: FanqieDevice,
        path: String,
        params: Map<String, String>,
        body: String? = null,
    ): JsonObject {
        val tsMs = System.currentTimeMillis()
        val queryParams = LinkedHashMap(params)
        queryParams["_rticket"] = tsMs.toString()
        val qs = encodeQuery(queryParams)
        val headers = unsignedHeaders(d, tsMs).toMutableMap()
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        headers.putAll(signHeaders(d, qs, tsMs))
        headers["X-Ss-Req-Ticket"] = tsMs.toString()
        val builder = Request.Builder().url("https://$HOST$path?$qs")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.post(body.toRequestBody(jsonMedia)) else builder.get()
        return parseObject(execute(builder.build(), "番茄接口"))
    }

    private fun commonQuery(d: FanqieDevice): LinkedHashMap<String, String> = linkedMapOf(
        "iid" to d.installId,
        "device_id" to d.deviceId,
        "ac" to "wifi",
        "channel" to d.channel,
        "aid" to "1967",
        "app_name" to "novelapp",
        "version_code" to d.versionCode,
        "version_name" to d.versionName,
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to d.deviceType,
        "device_brand" to d.deviceBrand,
        "language" to d.language,
        "os_api" to d.osApi,
        "os_version" to d.osVersion,
        "manifest_version_code" to d.versionCode,
        "resolution" to d.resolution,
        "dpi" to d.dpi,
        "update_version_code" to d.versionCode,
        "_rticket" to "0",
        "host_abi" to d.hostAbi,
        "cdid" to d.cdid,
        "openudid" to d.openudid,
    )

    private fun commentQuery(d: FanqieDevice): LinkedHashMap<String, String> =
        commonQuery(d).apply { put("compliance_status", "0") }

    private fun unsignedHeaders(d: FanqieDevice, tsMs: Long): Map<String, String> = linkedMapOf(
        "User-Agent" to
            "com.dragon.read.oversea.gp/${d.versionCode} (Linux; U; Android ${d.osVersion}; " +
            "zh_CN; ${d.deviceType}; Build/${d.romVersion.substringBefore('+')};" +
            "tt-ok/3.12.13.4-tiktok)",
        "Accept" to "application/json; charset=utf-8,application/x-protobuf",
        "Accept-Encoding" to "identity",
        "sdk-version" to "2",
        "passport-sdk-version" to "50564",
        "lc" to "101",
        "x-tt-store-region" to "cn-zj",
        "x-tt-store-region-src" to "did",
        "x-vc-bdturing-sdk-version" to "3.7.2.cn",
        "x-xs-from-web" to "0",
        "X-Ss-Req-Ticket" to tsMs.toString(),
        "x-reading-request" to "$tsMs-${randomHex(4)}",
        "Cookie" to "store-region=cn-zj; store-region-src=did; install_id=${d.installId}",
    )

    private fun signHeaders(d: FanqieDevice, query: String, tsMs: Long): Map<String, String> {
        val seconds = tsMs / 1000
        return linkedMapOf(
            "X-Khronos" to seconds.toString(),
            "X-Ladon" to FanqieCrypto.ladon(seconds, randomBytes(4)),
            "X-Helios" to FanqieCrypto.ladon(seconds, randomBytes(4)),
            "X-Argus" to FanqieCrypto.argus(
                query = query,
                timestamp = seconds,
                deviceId = d.deviceId,
                versionName = d.versionName,
                deviceType = d.deviceType,
                osVersion = d.osVersion,
            ),
        )
    }

    /**
     * 与 App 客户端一致的 query 编码：只保留 `A-Z a-z 0-9 - _ . ~`，
     * 再把 `item_ids` 的逗号和其余值的 `*`/`+` 还原。X-Argus 签的是这个字符串，
     * 换一种编码方式服务端就会判定签名不符。
     */
    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            val encoded = pctEncode(value)
            val restored = when (key) {
                "item_ids" -> encoded.replace("%2C", ",")
                else -> encoded.replace("%2A", "*").replace("%2B", "+")
            }
            pctEncode(key) + "=" + restored
        }

    private fun pctEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size + 16)
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a ||
                c == 0x2d || c == 0x5f || c == 0x2e || c == 0x7e
            ) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0f])
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun execute(request: Request, name: String): String {
        val text = try {
            Http.client.newCall(request).execute().use { response ->
                val body = String(response.body.bytes(), Charsets.UTF_8)
                if (!response.isSuccessful) {
                    throw FanqieException("$name HTTP ${response.code}: ${body.take(200)}")
                }
                body
            }
        } catch (e: FanqieException) {
            throw e
        } catch (e: Exception) {
            throw FanqieException("$name 网络错误: ${e.message}")
        }
        if (text.isBlank()) {
            throw FanqieException("$name 返回空响应（签名或设备可能被风控）")
        }
        return text
    }

    private fun parseObject(text: String): JsonObject =
        runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw FanqieException("接口返回的不是 JSON: ${text.take(120)}") }

    private fun checkCode(root: JsonObject, name: String) {
        val code = root.get("code")?.let { if (it.isJsonPrimitive) it.asString.toIntOrNull() else null }
        if (code != 0) {
            val message = root.string("message") ?: root.string("msg") ?: root.string("StatusMessage")
            throw FanqieException("$name 失败: code=$code${if (message.isNullOrBlank()) "" else " $message"}")
        }
    }

    private fun commentItem(raw: JsonObject, isReply: Boolean): JsonObject {
        // 列表里评论是 {comment:{...}}；回复是 {Common:{...}, reply_id:...}，大小写不同。
        val common = raw.getAsJsonObject("comment")
            ?: raw.getAsJsonObject("common")
            ?: raw.getAsJsonObject("Common")
            ?: raw
        val id = raw.string("reply_id") ?: raw.string("comment_id") ?: common.string("comment_id").orEmpty()
        val user = common.getAsJsonObject("user_info")
        val base = user?.getAsJsonObject("base_info") ?: user
        val name = base?.string("user_name").orEmpty().ifBlank { "书友" }
        val avatar = https(base?.string("user_avatar")).orEmpty()
        val content = common.getAsJsonObject("content")
        var text = content?.string("text").orEmpty()
        if (text.isBlank()) {
            val images = content?.getAsJsonObject("image_data_list")
            if (images != null && images.size() > 0) text = "[图片]"
        }
        val stat = raw.getAsJsonObject("stat")
        val proto = JsonObject().apply {
            addProperty("text", text)
            addProperty("time", formatTime(common.long("create_timestamp")))
            addProperty("likeCount", stat?.int("digg_count") ?: 0)
            if (isReply) {
                replyToName(raw)?.takeIf { it.isNotBlank() }?.let { addProperty("replyToName", it) }
            } else {
                addProperty("replyCount", stat?.int("reply_count") ?: 0)
            }
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("avatar", avatar)
            addProperty("name", name)
            addProperty("content", gson.toJson(proto))
            add("badges", badges(user?.getAsJsonObject("user_tag")))
        }
    }

    private fun replyToName(raw: JsonObject): String? {
        val info = raw.getAsJsonObject("reply_to_user_info")
        val base = info?.getAsJsonObject("base_info") ?: info
        return base?.string("user_name")
            ?: raw.getAsJsonObject("expand")?.string("reply_to_name")
    }

    private fun badges(tag: JsonObject?): JsonArray {
        val out = JsonArray()
        if (tag == null) return out
        if (tag.bool("is_author")) out.add("作者")
        if (tag.bool("is_official_cert")) out.add("官方")
        if (tag.bool("is_blue_vip")) out.add("蓝V")
        if (tag.bool("is_vip")) out.add("VIP")
        tag.getAsJsonArray("user_title_info")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val title = el.asJsonObject.string("title")
                ?: el.asJsonObject.string("title_name")
                ?: el.asJsonObject.string("name")
            if (!title.isNullOrBlank()) out.add(title)
        }
        return out
    }

    private fun formatTime(seconds: Long): String {
        if (seconds <= 0) return ""
        val diff = System.currentTimeMillis() / 1000 - seconds
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60}分钟前"
            diff < 86400 -> "${diff / 3600}小时前"
            diff < 30L * 86400 -> "${diff / 86400}天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(seconds * 1000))
        }
    }

    private fun formatWordCount(raw: String?): String {
        val n = raw?.toLongOrNull() ?: return ""
        return when {
            n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
            n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
            else -> n.toString()
        }
    }

    private fun https(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        return if (u.startsWith("http://", true)) "https://" + u.substring(7) else u
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    private fun randomHex(n: Int): String = FanqieCrypto.bytesToHex(randomBytes(n))

    private fun registerUa(d: FanqieDevice): String =
        "com.dragon.read/${d.versionCode} (Linux; U; Android ${d.osVersion}; zh_CN; " +
            "${d.deviceType}; Build/${d.romVersion.substringBefore('+')};tt-ok/3.12.13.4-tiktok)"

    private fun deviceToJson(d: FanqieDevice): JsonObject = JsonObject().apply {
        addProperty("device_id", d.deviceId)
        addProperty("install_id", d.installId)
        addProperty("channel", d.channel)
        addProperty("version_code", d.versionCode)
        addProperty("version_name", d.versionName)
        addProperty("device_type", d.deviceType)
        addProperty("device_brand", d.deviceBrand)
        addProperty("language", d.language)
        addProperty("os_api", d.osApi)
        addProperty("os_version", d.osVersion)
        addProperty("resolution", d.resolution)
        addProperty("dpi", d.dpi)
        addProperty("host_abi", d.hostAbi)
        addProperty("rom_version", d.romVersion)
        addProperty("cdid", d.cdid)
        addProperty("openudid", d.openudid)
        addProperty("clientudid", d.clientudid)
    }

    private fun deviceFromJson(o: JsonObject): FanqieDevice? {
        val deviceId = o.idString("device_id") ?: return null
        val installId = o.idString("install_id") ?: return null
        val defaults = FanqieDevice(deviceId = deviceId, installId = installId, cdid = "", openudid = "", clientudid = "")
        return defaults.copy(
            channel = o.string("channel") ?: defaults.channel,
            versionCode = o.string("version_code") ?: defaults.versionCode,
            versionName = o.string("version_name") ?: defaults.versionName,
            deviceType = o.string("device_type") ?: defaults.deviceType,
            deviceBrand = o.string("device_brand") ?: defaults.deviceBrand,
            language = o.string("language") ?: defaults.language,
            osApi = o.string("os_api") ?: defaults.osApi,
            osVersion = o.string("os_version") ?: defaults.osVersion,
            resolution = o.string("resolution") ?: defaults.resolution,
            dpi = o.string("dpi") ?: defaults.dpi,
            hostAbi = o.string("host_abi") ?: defaults.hostAbi,
            romVersion = o.string("rom_version") ?: defaults.romVersion,
            cdid = o.string("cdid") ?: "",
            openudid = o.string("openudid") ?: "",
            clientudid = o.string("clientudid") ?: "",
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int =
        get(key)?.let { if (it.isJsonPrimitive) it.asString.toIntOrNull() else null } ?: 0

    private fun JsonObject.long(key: String): Long =
        get(key)?.let { if (it.isJsonPrimitive) it.asString.toLongOrNull() else null } ?: 0L

    private fun JsonObject.bool(key: String): Boolean {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive } ?: return false
        return primitive.asString.equals("true", true) || primitive.asString == "1"
    }

    private fun JsonObject.idString(vararg keys: String): String? {
        for (key in keys) {
            val value = string(key)?.trim().orEmpty()
            if (value.isNotEmpty() && value != "0") return value
        }
        return null
    }

    private const val HEX = "0123456789ABCDEF"

    private data class FanqieDevice(
        val deviceId: String,
        val installId: String,
        val cdid: String,
        val openudid: String,
        val clientudid: String,
        val channel: String = "43536163a",
        val versionCode: String = "73733",
        val versionName: String = "7.3.7.33",
        val deviceType: String = "P30",
        val deviceBrand: String = "HUAWEI",
        val language: String = "zh",
        val osApi: String = "31",
        val osVersion: String = "12",
        val resolution: String = "1280*720",
        val dpi: String = "240",
        val hostAbi: String = "arm64-v8a",
        val romVersion: String = "S643.217451.03386707",
    )
}

/**
 * Legado 书源里的合成 URL，交给 [FanqieClient] 在本地解析：
 *
 * - `fanqie://book/<bookId>` 书籍详情 JSON
 * - `fanqie://toc/<bookId>` 目录 JSON
 * - `fanqie://chapter/<bookId>/<itemId>` 章节正文
 * - `fanqie://reviews/summary` 当前章节的段评统计（上下文来自 baseUrl）
 * - `fanqie://reviews/<para>/<page>` 当前章节第 para 段的第 page 页
 * - `fanqie://reviews/<book>/<item>/<para>/c<cursor>` 绝对分页地址
 * - `fanqie://replies/<reviewId>/<page>` 某条评论的回复
 */
object FanqieUrls {
    const val SCHEME = "fanqie://"

    fun book(bookId: String): String = "${SCHEME}book/$bookId"
    fun toc(bookId: String): String = "${SCHEME}toc/$bookId"
    fun chapter(bookId: String, itemId: String): String = "${SCHEME}chapter/$bookId/$itemId"
    fun reviewSummary(): String = "${SCHEME}reviews/summary"
    fun reviewDetail(bookId: String, itemId: String, para: Int, cursor: String): String =
        "${SCHEME}reviews/$bookId/$itemId/$para/$cursor"
    fun replies(reviewId: String, cursor: String): String = "${SCHEME}replies/$reviewId/$cursor"

    fun resolve(url: String, baseUrl: String? = null): String {
        val raw = url.substringAfter(SCHEME, "").substringBefore('?').trim('/')
        val parts = raw.split('/').filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "book" -> FanqieClient.bookInfo(parts.getOrNull(1) ?: throw bad(url))
            "toc" -> FanqieClient.toc(parts.getOrNull(1) ?: throw bad(url))
            "chapter" -> FanqieClient.chapter(
                parts.getOrNull(1) ?: throw bad(url),
                parts.getOrNull(2) ?: throw bad(url),
            )
            "reviews" -> {
                if (parts.getOrNull(1) == "summary") {
                    val (bookId, itemId) = chapterContext(baseUrl)
                    FanqieClient.reviewSummary(bookId, itemId)
                } else if (parts.size >= 5) {
                    FanqieClient.reviewDetail(
                        parts[1], parts[2], parts[3].toIntOrNull() ?: 1, cursor(parts[4], 20),
                    )
                } else {
                    val (bookId, itemId) = chapterContext(baseUrl)
                    FanqieClient.reviewDetail(
                        bookId,
                        itemId,
                        parts.getOrNull(1)?.toIntOrNull() ?: 1,
                        cursor(parts.getOrNull(2).orEmpty(), 20),
                    )
                }
            }
            "replies" -> FanqieClient.replies(
                parts.getOrNull(1) ?: throw bad(url),
                cursor(parts.getOrNull(2).orEmpty(), 50),
            )
            else -> throw bad(url)
        }
    }

    /** 从 `fanqie://book/<id>`、`fanqie://chapter/<id>/<item>` 或番茄网页地址里取书号。 */
    fun bookIdOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        BOOK_IN_URL.find(value)?.let { return it.groupValues[1] }
        WEB_BOOK.find(value)?.let { return it.groupValues[1] }
        return value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun chapterContext(baseUrl: String?): Pair<String, String> {
        val raw = baseUrl?.substringAfter(SCHEME, "").orEmpty().substringBefore('?').trim('/')
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 3 && parts[0] == "chapter") return parts[1] to parts[2]
        throw FanqieException("段评需要从章节内打开（缺少章节上下文）")
    }

    private fun cursor(segment: String, pageSize: Int): String {
        if (segment.startsWith("c")) return segment.substring(1)
        val page = segment.toIntOrNull() ?: 1
        return if (page <= 1) "" else ((page - 1) * pageSize).toString()
    }

    private fun bad(url: String) = FanqieException("无法解析的番茄地址: $url")

    private val BOOK_IN_URL = Regex("fanqie://(?:book|chapter|toc)/(\\d+)")
    private val WEB_BOOK = Regex("(?:fanqienovel\\.com/(?:page|reader)/|bookId=)(\\d+)")
}
