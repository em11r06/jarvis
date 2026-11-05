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

    /**
     * Bağlantıyı önceden ısıtır: kullanıcı konuşurken sunucuyla güvenli bağlantı kurulmuş olur,
     * asıl istek gelince bekleme olmaz (OkHttp bağlantıyı birkaç dakika açık tutar).
     */
    fun warmUp(vararg hosts: String) {
        hosts.forEach { h ->
            runCatching {
                client.newCall(Request.Builder().url("https://$h/").head().build()).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
                })
            }
        }
    }

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
