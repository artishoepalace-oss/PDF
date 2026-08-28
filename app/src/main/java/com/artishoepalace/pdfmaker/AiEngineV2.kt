package com.artishoepalace.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

fun v2AiReadImage(bitmap: Bitmap, key: String, model: String, jsonMode: Boolean): String {
    val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
    val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    val prompt = if (jsonMode) "Read this document accurately and return valid JSON with text, summary, key_values and tables." else "Read this document accurately. Return clean text, a concise summary, and preserve names, dates, amounts, identifiers and tables."
    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)).put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$data"))
    return v2OpenAi(key, model, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
}

fun v2AiReadPdf(context: Context, uri: Uri, key: String, model: String, jsonMode: Boolean): String {
    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    require(bytes.size <= 18 * 1024 * 1024) { "PDF is too large for this in-app AI request." }
    val name = v2QueryName(context, uri).ifBlank { "document.pdf" }
    val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val prompt = if (jsonMode) "Read this PDF and return valid JSON with document_type, summary, key_values, tables and important_text." else "Read this PDF and return important text, summary, key dates, names, amounts and table data."
    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)).put(JSONObject().put("type", "input_file").put("filename", name).put("file_data", "data:application/pdf;base64,$data"))
    return v2OpenAi(key, model, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
}

private fun v2OpenAi(key: String, model: String, input: JSONArray): String {
    val body = JSONObject().put("model", model.ifBlank { "gpt-5.4" }).put("input", input).toString()
    val c = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 120000
        setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json")
    }
    c.outputStream.use { it.write(body.toByteArray()) }
    val code = c.responseCode
    val raw = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
    if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull() ?: "HTTP $code")
    val output = JSONObject(raw).optJSONArray("output") ?: return "No text returned."
    val sb = StringBuilder()
    for (i in 0 until output.length()) {
        val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
        for (j in 0 until content.length()) content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
    }
    return sb.toString().trim().ifBlank { "No readable text returned." }
}
