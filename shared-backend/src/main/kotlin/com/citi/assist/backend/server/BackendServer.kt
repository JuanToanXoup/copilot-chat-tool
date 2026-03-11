package com.citi.assist.backend.server

import com.citi.assist.backend.store.SessionEvent
import com.citi.assist.backend.store.SessionStoreCore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.logging.Logger

/**
 * Stdio JSON-lines server. The VS Code extension spawns this as a child process
 * and communicates via newline-delimited JSON on stdin/stdout.
 *
 * Protocol:
 *   Request:  { "id": 1, "method": "getSessions", "params": { ... } }
 *   Response: { "id": 1, "result": { ... } }
 *   Event:    { "event": "statusChanged", "data": { ... } }
 *
 * Methods:
 *   - init(projectSlug)        → { ok: true }
 *   - getSessions()            → [ChatSession...]
 *   - getSession(sessionId)    → ChatSession | null
 *   - getPlaybooks()           → [PlaybookRun...]
 *   - handleEvent(sessionId, event) → { ok: true }
 *   - recordPrompt(sessionId, prompt) → { ok: true }
 *   - ping()                   → { pong: true }
 */
class BackendServer {

    private val log = Logger.getLogger(BackendServer::class.java.name)
    private val gson = Gson()
    private var store: SessionStoreCore? = null
    private lateinit var writer: PrintWriter

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        writer = PrintWriter(System.out, true)

        // Send ready signal
        sendEvent("ready", mapOf("version" to "0.1.0"))

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            try {
                val json = JsonParser.parseString(line).asJsonObject
                val id = json.get("id")?.asInt
                val method = json.get("method")?.asString ?: continue
                val params = json.getAsJsonObject("params") ?: JsonObject()

                val result = handleMethod(method, params)
                if (id != null) {
                    sendResponse(id, result)
                }
            } catch (e: Exception) {
                log.warning("Error processing request: ${e.message}")
                // Try to extract id for error response
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val id = json.get("id")?.asInt
                    if (id != null) {
                        sendError(id, e.message ?: "Unknown error")
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleMethod(method: String, params: JsonObject): Any? {
        return when (method) {
            "init" -> {
                val projectSlug = params.get("projectSlug")?.asString
                    ?: throw IllegalArgumentException("projectSlug required")
                val s = SessionStoreCore(projectSlug)
                s.onStatusChanged = { sessionId, status ->
                    sendEvent("statusChanged", mapOf("sessionId" to sessionId, "status" to status.name))
                }
                s.init()
                store = s
                mapOf("ok" to true)
            }

            "getSessions" -> {
                requireStore().allSessions()
            }

            "getSession" -> {
                val sessionId = params.get("sessionId")?.asString
                    ?: throw IllegalArgumentException("sessionId required")
                requireStore().getSession(sessionId)
            }

            "getPlaybooks" -> {
                requireStore().allPlaybooks()
            }

            "recordPrompt" -> {
                val sessionId = params.get("sessionId")?.asString
                    ?: throw IllegalArgumentException("sessionId required")
                val prompt = params.get("prompt")?.asString
                    ?: throw IllegalArgumentException("prompt required")
                requireStore().recordPrompt(sessionId, prompt)
                mapOf("ok" to true)
            }

            "handleEvent" -> {
                val sessionId = params.get("sessionId")?.asString
                    ?: throw IllegalArgumentException("sessionId required")
                val eventJson = params.getAsJsonObject("event")
                    ?: throw IllegalArgumentException("event required")
                val event = deserializeEvent(eventJson)
                requireStore().handleEvent(sessionId, event)
                mapOf("ok" to true)
            }

            "ping" -> mapOf("pong" to true)

            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }

    private fun deserializeEvent(json: JsonObject): SessionEvent {
        val type = json.get("type")?.asString
            ?: throw IllegalArgumentException("event.type required")

        return when (type) {
            "sessionReady" -> SessionEvent.SessionReady(
                sessionId = json.get("sessionId").asString
            )
            "turnIdSync" -> SessionEvent.TurnIdSync(
                turnId = json.get("turnId").asString,
                parentTurnId = json.get("parentTurnId")?.asString,
            )
            "reply" -> SessionEvent.Reply(
                delta = json.get("delta")?.asString ?: "",
                accumulated = json.get("accumulated")?.asString ?: "",
                parentTurnId = json.get("parentTurnId")?.asString,
            )
            "toolCallUpdate" -> SessionEvent.ToolCallUpdate(
                turnId = json.get("turnId")?.asString,
                parentTurnId = json.get("parentTurnId")?.asString,
                roundId = json.get("roundId")?.asInt,
                toolCallId = json.get("toolCallId")?.asString,
                toolName = json.get("toolName")?.asString,
                toolType = json.get("toolType")?.asString,
                input = json.get("input")?.asString,
                inputMessage = json.get("inputMessage")?.asString,
                status = json.get("status")?.asString,
                output = json.get("output")?.asString,
                error = json.get("error")?.asString,
                progressMessage = json.get("progressMessage")?.asString,
                durationMs = json.get("durationMs")?.asLong,
            )
            "steps" -> {
                val steps = json.getAsJsonArray("steps")?.map { s ->
                    val obj = s.asJsonObject
                    SessionEvent.StepData(
                        id = obj.get("id")?.asString,
                        status = obj.get("status")?.asString,
                        title = obj.get("title")?.asString,
                        description = obj.get("description")?.asString,
                    )
                } ?: emptyList()
                SessionEvent.Steps(
                    steps = steps,
                    parentTurnId = json.get("parentTurnId")?.asString,
                )
            }
            "complete" -> SessionEvent.Complete(
                fullReply = json.get("fullReply")?.asString ?: ""
            )
            "error" -> SessionEvent.Error(
                message = json.get("message")?.asString ?: "Unknown error",
                code = json.get("code")?.asInt ?: 0,
                reason = json.get("reason")?.asString,
            )
            "cancel" -> SessionEvent.Cancel
            else -> throw IllegalArgumentException("Unknown event type: $type")
        }
    }

    private fun requireStore(): SessionStoreCore {
        return store ?: throw IllegalStateException("Not initialized — call init first")
    }

    private fun sendResponse(id: Int, result: Any?) {
        val response = mapOf("id" to id, "result" to result)
        writer.println(gson.toJson(response))
    }

    private fun sendError(id: Int, message: String) {
        val response = mapOf("id" to id, "error" to mapOf("message" to message))
        writer.println(gson.toJson(response))
    }

    private fun sendEvent(event: String, data: Any) {
        val msg = mapOf("event" to event, "data" to data)
        writer.println(gson.toJson(msg))
    }
}

fun main() {
    // Redirect JUL logging to stderr so it doesn't corrupt the stdio protocol
    val rootLogger = Logger.getLogger("")
    for (handler in rootLogger.handlers) {
        rootLogger.removeHandler(handler)
    }
    val stderrHandler = java.util.logging.StreamHandler(System.err, java.util.logging.SimpleFormatter())
    stderrHandler.level = java.util.logging.Level.ALL
    rootLogger.addHandler(stderrHandler)

    BackendServer().run()
}
