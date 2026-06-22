package jp.co.sstinc.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// android.util.Log 呼び出しで落ちないよう、RobolectricTestRunner を使用する
@RunWith(RobolectricTestRunner::class)
class EventPosterTest {

    // メソッドと JSON bodyを検証
    @Test
    fun postEvent_sendsJsonBody() = runBlocking {
        var capturedRequest: Request? = null
        val poster = EventPoster(
            requestExecutor = { request ->
                capturedRequest = request
                Result.success(Unit)
            }
        )

        val result = poster.postEvent(
            httpUrl = "http://localhost/events".toHttpUrlOrNull()!!,
            timestamp = "2024-01-01T00:00:00.000",
            data = "a1b2",
            dataSize = 2
        )

        assertTrue(result.isSuccess)

        val request = capturedRequest!!
        assertEquals("POST", request.method)
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains("\"timestamp\":\"2024-01-01T00:00:00.000\""))
        assertTrue(body.contains("\"data\":\"a1b2\""))
        assertTrue(body.contains("\"dataSize\":2"))
    }

    // 同時送信上限超過で失敗することを確認する
    @Test
    fun postEvent_failsWhenConcurrentLimitExceeded() = runBlocking {
        val entered = CountDownLatch(5)
        val release = CountDownLatch(1)
        val poster = EventPoster(
            requestExecutor = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                Result.success(Unit)
            }
        )
        val url = "http://localhost/events".toHttpUrlOrNull()!!

        // 5件を実行中にして同時実行枠を埋める
        val firstFive = (1..5).map { i ->
            async(Dispatchers.Default) {
                poster.postEvent(url, "t$i", "d$i", 1)
            }
        }

        // CountDownLatchが0になるまで待つ(ここでタイムアウトした場合はテスト失敗になる)
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        // 6件目(上限超過、EventPoster内の例外が発生するはず。)
        val sixthResult = async(Dispatchers.Default) {
            poster.postEvent(url, "t6", "d6", 1)
        }.await()

        // 上限超過で失敗することを確認
        assertTrue(sixthResult.isFailure)
        assertTrue(sixthResult.exceptionOrNull() is PostLimitExceededException)

        // 解放後、先行5件が成功することを確認
        release.countDown()
        val firstResults = firstFive.awaitAll()
        assertTrue(firstResults.all { it.isSuccess })
    }

    // requestExecutor の失敗がそのまま返ることを確認する
    @Test
    fun postEvent_propagatesFailureFromRequestExecutor() = runBlocking {
        val expected = IOException("executor failed")
        val poster = EventPoster(
            requestExecutor = {
                Result.failure(expected)
            }
        )

        val result = poster.postEvent(
            httpUrl = "http://localhost/events".toHttpUrlOrNull()!!,
            timestamp = "t",
            data = "d",
            dataSize = 1
        )

        assertTrue(result.isFailure)
        assertSame(expected, result.exceptionOrNull())
    }

    // 失敗時でもセマフォが解放されることを確認する
    @Test
    fun postEvent_releasesSemaphoreAfterFailure() = runBlocking {
        val callCounter = AtomicInteger(0)
        val entered = CountDownLatch(5)
        val release = CountDownLatch(1)
        val poster = EventPoster(
            requestExecutor = {
                // 1件目を意図的に失敗させる
                if (callCounter.incrementAndGet() == 1) {
                    Result.failure(IOException("first failure"))

                // 2件目以降
                } else {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    Result.success(Unit)
                }
            }
        )
        val url = "http://localhost/events".toHttpUrlOrNull()!!

        // 1件目は失敗する
        val first = poster.postEvent(url, "t0", "d0", 1)
        assertTrue(first.isFailure)

        // 失敗後も後続5件が実行できることを確認（セマフォ解放の確認）
        val nextFive = (1..5).map { i ->
            async(Dispatchers.Default) {
                poster.postEvent(url, "t$i", "d$i", 1)
            }
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        release.countDown()
        val results = nextFive.awaitAll()
        assertTrue(results.all { it.isSuccess })
    }

    // HTTP 2xx で success になることを確認する（MockWebServer）
    @Test
    fun postEvent_returnsSuccessFor2xx() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val poster = EventPoster()

            val result = poster.postEvent(
                httpUrl = server.url("/events"),
                timestamp = "t",
                data = "d",
                dataSize = 1
            )

            assertTrue(result.isSuccess)
        }
    }

    // HTTP 非2xx で IOException("HTTP xxx") になることを確認する
    @Test
    fun postEvent_returnsIOExceptionForNon2xx() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("ng"))
            val poster = EventPoster()

            val result = poster.postEvent(
                httpUrl = server.url("/events"),
                timestamp = "t",
                data = "d",
                dataSize = 1
            )

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertTrue(ex is IOException)
            assertEquals("HTTP 503", ex?.message)
        }
    }

    // 通信失敗時に IOException の failure になることを確認する
    @Test
    fun postEvent_returnsFailureOnNetworkIOException() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("forced network failure") }
            .build()
        val poster = EventPoster(providedClient = client)

        val result = poster.postEvent(
            httpUrl = "http://localhost/events".toHttpUrlOrNull()!!,
            timestamp = "t",
            data = "d",
            dataSize = 1
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IOException)
        assertEquals("forced network failure", ex?.message)
    }

    // コルーチンキャンセル時に invokeOnCancellation / finally 経由でクラッシュしないことを確認する
     @Test
     fun postEvent_cancellationDoesNotCrash() = runBlocking {
         MockWebServer().use { server ->
             // サーバは接続を受けるがレスポンスを返さない
             server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
             val poster = EventPoster()

             val job = launch(Dispatchers.Default) {
                 poster.postEvent(server.url("/events"), "t", "d", 1)
             }

             // サーバがリクエストを受信したことを確認してからキャンセル
             // (= call.enqueue 後で invokeOnCancellation が登録済み状態)
             assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
             job.cancelAndJoin()

             // ここまで例外が伝播せず到達できれば OK
             assertTrue(job.isCancelled)
         }
     }

    // readTimeout を超過した時に Result.failure が返ることを確認する。
    // 通信レイヤのエラーが返ってくる異常系の例として実施する。
    // production の readTimeout(10秒) ではテストが長くなるため、注入クライアントで短縮している。
    // connectTimeout 側は安定したテスト手段が無いため意図的にテストを書いていない。
     @Test
     fun postEvent_returnsFailureOnReadTimeout() = runBlocking {
         MockWebServer().use { server ->
             // サーバはレスポンスを返さない
             server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

             // テスト時間短縮のため readTimeout を短くしたクライアントを注入
             val client = OkHttpClient.Builder()
                 .readTimeout(200, TimeUnit.MILLISECONDS)
                 .build()
             val poster = EventPoster(providedClient = client)

             val result = poster.postEvent(server.url("/events"), "t", "d", 1)

             assertTrue(result.isFailure)
             assertTrue(result.exceptionOrNull() is SocketTimeoutException)
         }
     }
}
