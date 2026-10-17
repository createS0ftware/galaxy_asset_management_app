package com.galaxy.assettrail.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One HTTP exchange. Throws an IOException when the request couldn't complete. */
interface HttpTransport {
    suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult
}

class HttpResult(val status: Int, val body: String)

/**
 * OkHttp transport. Reads wait up to [readTimeoutSeconds]: a check-in returns once its
 * transaction is in a closed ledger, which the backend polls for up to 60 s.
 */
class OkHttpTransport(readTimeoutSeconds: Long = 90) : HttpTransport {
    private val client = OkHttpClient.Builder()
        // No silent re-sends: a check-in POST that reached the backend must not go twice. The
        // app's queue retries on its own, and the backend recognises a re-sent payload.
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .method(method, body?.toRequestBody(JSON))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use { response -> HttpResult(response.code, response.body?.string().orEmpty()) }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Where the driver's session token (the backend's 30-day JWT) is kept. */
interface TokenStore {
    fun read(): String?

    fun write(token: String)

    fun clear()
}

class MemoryTokenStore(private var token: String? = null) : TokenStore {
    override fun read(): String? = token

    override fun write(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}
