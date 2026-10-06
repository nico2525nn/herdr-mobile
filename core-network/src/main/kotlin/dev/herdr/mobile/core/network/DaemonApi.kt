package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.AgentReport
import dev.herdr.mobile.core.model.HealthReport
import dev.herdr.mobile.core.model.InputRequest
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.ResizeRequest
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Workspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Thrown when the daemon answered with an error status or an error body. */
class DaemonException(val code: String, override val message: String, val httpStatus: Int = 0) :
    IOException("$code: $message")

/**
 * Thin suspend wrapper over the daemon REST surface. No caching, no retry, no state: every
 * call is one HTTP round trip so [HerdrClient] owns the only synchronization policy.
 */
class DaemonApi(
    private val http: OkHttpClient,
    private val endpoint: DaemonEndpoint,
) {
    private fun request(method: String, path: String, body: String? = null): Request {
        val url = endpoint.httpBase.resolve(path.trimStart('/'))
            ?: throw DaemonException("bad_url", "Cannot resolve $path against ${endpoint.httpBase}")
        val builder = Request.Builder().url(url)
        endpoint.token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "").toRequestBody(JSON_MEDIA))
            else -> error("unsupported method $method")
        }
        return builder.build()
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapError(response.code, text)
            }
            return text
        }
    }

    private fun mapError(status: Int, text: String): DaemonException {
        if (status == 401) return DaemonException("unauthorized", "Daemon rejected the bearer token", 401)
        return try {
            val obj = HerdrJson.parseToJsonElement(text).jsonObject
            val err = obj["error"]?.jsonObject
            val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_$status"
            val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: text.ifBlank { "HTTP $status" }
            DaemonException(code, message, status)
        } catch (_: Exception) {
            DaemonException("http_$status", text.ifBlank { "HTTP $status" }, status)
        }
    }

    private fun post(path: String, body: String): JsonObject {
        val text = execute(request("POST", path, body))
        return HerdrJson.parseToJsonElement(text).jsonObject
    }

    private inline fun <reified T> decode(text: String): T = HerdrJson.decodeFromString(text)

    fun health(): HealthReport = decode(execute(request("GET", "v1/health")))

    fun snapshot(): SessionSnapshot = decode(execute(request("GET", "v1/snapshot")))

    fun workspaces(): WorkspacesResponse {
        val text = execute(request("GET", "v1/workspaces"))
        val obj = HerdrJson.parseToJsonElement(text).jsonObject
        return WorkspacesResponse(
            revision = obj["revision"]?.jsonPrimitive?.long ?: 0,
            seq = obj["seq"]?.jsonPrimitive?.long ?: 0,
            workspaces = HerdrJson.decodeFromJsonElement(
                ListSerializer(Workspace.serializer()),
                obj["workspaces"] ?: JsonArray(emptyList()),
            ),
        )
    }

    fun panes(): PanesResponse {
        val text = execute(request("GET", "v1/panes"))
        val obj = HerdrJson.parseToJsonElement(text).jsonObject
        return PanesResponse(
            revision = obj["revision"]?.jsonPrimitive?.long ?: 0,
            seq = obj["seq"]?.jsonPrimitive?.long ?: 0,
            panes = HerdrJson.decodeFromJsonElement(
                ListSerializer(Pane.serializer()),
                obj["panes"] ?: JsonArray(emptyList()),
            ),
        )
    }

    fun sendInput(paneId: String, text: String, base64: Boolean = false) {
        val body = HerdrJson.encodeToString(
            InputRequest.serializer(),
            InputRequest(encoding = if (base64) "base64" else "utf-8", text = text),
        )
        post("v1/pane/${paneId.url()}/input", body)
    }

    fun interrupt(paneId: String) {
        post("v1/pane/${paneId.url()}/interrupt", "{}")
    }

    fun resizePane(paneId: String, cols: Int, rows: Int) {
        val body = HerdrJson.encodeToString(ResizeRequest.serializer(), ResizeRequest(cols, rows))
        post("v1/pane/${paneId.url()}/resize", body)
    }

    fun reportAgent(report: AgentReport) {
        post("v1/agent/report", HerdrJson.encodeToString(AgentReport.serializer(), report))
    }

    fun createTab(workspaceId: String, label: String?): String {
        val body = buildJsonObject {
            put("workspaceId", workspaceId)
            if (label != null) put("label", label)
        }.toString()
        val obj = post("v1/tab", body)
        return obj["tabId"]?.jsonPrimitive?.contentOrNull
            ?: throw DaemonException("bad_response", "tab.create returned no tabId")
    }

    private fun String.url(): String = URLEncoder.encode(this, "UTF-8")
}

data class WorkspacesResponse(
    val revision: Long,
    val seq: Long,
    val workspaces: List<Workspace>,
)

data class PanesResponse(
    val revision: Long,
    val seq: Long,
    val panes: List<Pane>,
)