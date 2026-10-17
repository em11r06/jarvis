package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Tüm internet istekleri için ortak HTTP istemcisi. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Result(val code: Int, val body: String)

    suspend fun postJson(url: String, body: String, headers: Map<String, String>): Result =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { Result(it.code, it.body?.string().orEmpty()) }
        }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Result =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).get().apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { Result(it.code, it.body?.string().orEmpty()) }
        }
}
