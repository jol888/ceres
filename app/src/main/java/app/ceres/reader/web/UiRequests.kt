package app.ceres.reader.web

import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Lets a rule thread block until an Activity it started reports back. */
internal object UiRequests {
    const val EXTRA_ID = "request_id"

    private val pending = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> await(timeoutMs: Long, start: (id: String) -> Unit): T? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "不能在主线程等待界面操作" }
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<Any?>()
        pending[id] = future
        try {
            start(id)
            return future.get(timeoutMs, TimeUnit.MILLISECONDS) as T?
        } catch (e: TimeoutException) {
            throw IOException("等待操作超时")
        } finally {
            pending.remove(id)
        }
    }

    fun complete(id: String?, value: Any?) {
        id?.let { pending[it]?.complete(value) }
    }
}
