package org.effekthasch.quickshopper3k.data.sync

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SyncHttpClient(private val baseUrl: String) {

    fun postIntents(listUuid: String, intents: JSONArray): Result<JSONObject> {
        return request("POST", "/list/$listUuid/intents", intents.toString())
            .mapCatching { JSONObject(it) }
    }

    fun getIntents(listUuid: String, sinceUlid: String?): Result<JSONArray> {
        val path = if (sinceUlid != null) {
            "/list/$listUuid/intents?since=$sinceUlid"
        } else {
            "/list/$listUuid/intents"
        }
        return request("GET", path, null).mapCatching { JSONArray(it) }
    }

    fun getSnapshot(listUuid: String): Result<JSONObject> {
        return request("GET", "/list/$listUuid/snapshot", null)
            .mapCatching { JSONObject(it) }
    }

    fun getDebug(listUuid: String): Result<JSONObject> {
        return request("GET", "/list/$listUuid/debug", null)
            .mapCatching { JSONObject(it) }
    }

    private fun request(method: String, path: String, body: String?): Result<String> {
        return try {
            val url = URL(baseUrl.trimEnd('/') + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")

            if (body != null) {
                val raw = body.toByteArray(Charsets.UTF_8)
                val compressed = ByteArrayOutputStream(raw.size / 2).also { baos ->
                    GZIPOutputStream(baos).use { it.write(raw) }
                }.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.outputStream.use { it.write(compressed) }
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val stream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Result.success(text)
            } else {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Result.failure(HttpException(code, error))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    class HttpException(val code: Int, val body: String) : IOException("HTTP $code: $body")
}
