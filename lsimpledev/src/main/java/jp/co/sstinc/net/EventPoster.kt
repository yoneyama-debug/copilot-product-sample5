package jp.co.sstinc.net

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume

class PostLimitExceededException : RuntimeException("同時送信数の上限に達しました")
class EventPoster(
    private val providedClient: OkHttpClient? = null,
    private val requestExecutor: ( suspend (Request) -> Result<Unit>)? = null,
) {
    // 念の為、マルチスレッドでも使えるようにSemaphoreにした
    // OkHttpのDispatcherの最大同時実行数はデフォルト5に合わせている
    private val MAX_POST_CONCURRENCY = 5
    private val postSemaphore = Semaphore(MAX_POST_CONCURRENCY)
    private val client by lazy {
        // okhttpのタイムアウトデフォルト値は10秒だが明示的に書くことにした。
        providedClient ?: OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // POSTでHTTP通信するメソッド
    suspend fun postEvent(httpUrl: HttpUrl, timestamp: String, data: String, dataSize: Int): Result<Unit> {
        if (!postSemaphore.tryAcquire()) {
            Log.d("EventPoster", "Maximum number of concurrent transmissions reached.")
            return Result.failure(PostLimitExceededException())
        }

        try {
            val requestJson = buildJson(timestamp, data, dataSize)
            val body = requestJson.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(httpUrl)
                .post(body)
                .build()

            requestExecutor?.let { executor ->
                return executor(request)
            }

            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                // コルーチンがキャンセルされたらネットワークリクエストもキャンセル
                continuation.invokeOnCancellation {
                    call.cancel()
                }

                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        continuation.resume(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        // キャンセル時にresumeを呼んだ場合、resumeは無視される
                        // 参考(https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/common/src/CancellableContinuationImpl.kt#L508-L514)
                        response.use {
                            if (!response.isSuccessful) {
                                continuation.resume(Result.failure(IOException("HTTP ${response.code}")))
                            } else {
                                continuation.resume(Result.success(Unit))
                            }
                        }
                    }
                })
            }
        } finally {
            postSemaphore.release()
        }
    }

    suspend fun postTestEvent(httpUrl: HttpUrl): Result<Unit> {
        return postEvent(httpUrl, "TEST", "test", "test".toByteArray().size)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun buildJson(timestamp: String, data: String, dataSize: Int): String {
            return "{" +
                "\"timestamp\":\"${escapeJson(timestamp)}\"," +
                "\"data\":\"${escapeJson(data)}\"," +
                "\"dataSize\":$dataSize" +
                "}"
        }

        private fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}
