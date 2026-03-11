package com.github.copilotsilent.orchestrator

import com.github.copilot.agent.chatMode.ChatModeService
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.github.copilotsilent.service.CopilotSilentChatService
import com.github.copilotsilent.store.SessionStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes a playbook: resolves parameters, runs steps in dependency order
 * (parallel where possible), collects results, substitutes variables, and
 * publishes progress events.
 */
@Service(Service.Level.PROJECT)
class PlaybookExecutor(
    private val project: Project,
) : Disposable {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.getInstance(PlaybookExecutor::class.java)
    private val gson = Gson()

    private val service: CopilotSilentChatService
        get() = project.service<CopilotSilentChatService>()

    private val sessionStore: SessionStore
        get() = project.service<SessionStore>()

    /** Currently running playbook ID (null if none). */
    @Volatile
    var activePlaybookId: String? = null
        private set

    /** Step results keyed by step ID. */
    val stepResults = ConcurrentHashMap<String, String>()

    /** Step states keyed by step ID. */
    val stepStates = ConcurrentHashMap<String, StepExecutionState>()

    override fun dispose() {
        coroutineScope.cancel()
    }

    /**
     * Execute a playbook with the given parameter values.
     *
     * @param playbookJson raw JSON string of the playbook file
     * @param paramValues user-supplied parameter values keyed by param name
     */
    fun execute(playbookJson: String, paramValues: Map<String, String>) {
        coroutineScope.launch {
            try {
                val pb = JsonParser.parseString(playbookJson).asJsonObject
                val playbookName = pb.get("name")?.asString ?: "Playbook"
                val steps = parseSteps(pb)
                val synthesisPrompt = pb.get("synthesisPrompt")?.asString

                // Add implicit synthesis step
                if (synthesisPrompt != null) {
                    val depTargets = steps.flatMap { it.dependsOn }.toSet()
                    val leafIds = steps.filter { !depTargets.contains(it.id) || it.dependsOn.isEmpty() }
                        .map { it.id }
                    if (leafIds.isNotEmpty()) {
                        steps.add(PlaybookStepDef(
                            id = "__synthesis__",
                            name = "Synthesis",
                            prompt = synthesisPrompt,
                            agentMode = false,
                            dependsOn = leafIds,
                        ))
                    }
                }

                // Create playbook run in DB
                val playbookId = sessionStore.createPlaybook()
                activePlaybookId = playbookId

                // Initialize state
                stepResults.clear()
                stepStates.clear()
                steps.forEach { stepStates[it.id] = StepExecutionState.IDLE }

                // Publish initial state
                publishProgress(playbookId, playbookName, steps)

                // Execute in dependency order
                executeSteps(playbookId, playbookName, steps, paramValues)

                // Complete
                sessionStore.completePlaybook(playbookId)
                activePlaybookId = null
                publishProgress(playbookId, playbookName, steps)

            } catch (e: Exception) {
                log.warn("Playbook execution failed", e)
                activePlaybookId = null
            }
        }
    }

    private suspend fun executeSteps(
        playbookId: String,
        playbookName: String,
        steps: List<PlaybookStepDef>,
        paramValues: Map<String, String>,
    ) {
        val stepMap = steps.associateBy { it.id }
        val remaining = steps.map { it.id }.toMutableSet()

        while (remaining.isNotEmpty()) {
            // Find steps whose dependencies are all satisfied
            val ready = remaining.filter { id ->
                val step = stepMap[id]!!
                step.dependsOn.all { depId ->
                    stepStates[depId] == StepExecutionState.SUCCESS
                }
            }

            if (ready.isEmpty()) {
                // Check if all remaining steps have failed dependencies
                val allBlocked = remaining.all { id ->
                    val step = stepMap[id]!!
                    step.dependsOn.any { depId ->
                        stepStates[depId] == StepExecutionState.ERROR
                    }
                }
                if (allBlocked) {
                    remaining.forEach { stepStates[it] = StepExecutionState.ERROR }
                    publishProgress(playbookId, playbookName, steps)
                    break
                }
                // Shouldn't happen — safety break
                log.warn("No ready steps but not all blocked. Remaining: $remaining")
                break
            }

            // Execute ready steps in parallel
            val completions = ready.map { stepId ->
                val step = stepMap[stepId]!!
                val deferred = CompletableDeferred<Boolean>()

                stepStates[stepId] = StepExecutionState.RUNNING
                publishProgress(playbookId, playbookName, steps)

                coroutineScope.launch {
                    try {
                        val result = executeStep(playbookId, step, paramValues)
                        stepResults[stepId] = result
                        stepStates[stepId] = StepExecutionState.SUCCESS
                        deferred.complete(true)
                    } catch (e: Exception) {
                        log.warn("Step ${step.id} failed", e)
                        stepResults[stepId] = "ERROR: ${e.message}"
                        stepStates[stepId] = StepExecutionState.ERROR
                        deferred.complete(false)
                    }
                    publishProgress(playbookId, playbookName, steps)
                }

                stepId to deferred
            }

            // Await all parallel steps
            for ((stepId, deferred) in completions) {
                deferred.await()
                remaining.remove(stepId)
            }
        }
    }

    private suspend fun executeStep(
        playbookId: String,
        step: PlaybookStepDef,
        paramValues: Map<String, String>,
    ): String {
        // Substitute parameters and step results in prompt
        var prompt = step.prompt

        // 1. Substitute user parameters: {paramName}
        for ((key, value) in paramValues) {
            prompt = prompt.replace("{$key}", value)
        }

        // 2. Substitute step results: {step_xxx_result}
        for ((stepId, result) in stepResults) {
            prompt = prompt.replace("{step_${stepId}_result}", result)
        }

        // 3. Substitute {results} with all completed step results (for synthesis)
        if (prompt.contains("{results}")) {
            val allResults = stepResults.entries
                .filter { it.key != "__synthesis__" }
                .joinToString("\n\n---\n\n") { (id, result) ->
                    "## Step: $id\n$result"
                }
            prompt = prompt.replace("{results}", allResults)
        }

        // Send to Copilot and collect the full reply
        val replyAccumulator = AtomicReference("")
        val completion = CompletableDeferred<String>()

        val conn = project.messageBus.connect()
        var targetSessionId: String? = null

        conn.subscribe(SilentChatListener.TOPIC, object : SilentChatListener {
            override fun onEvent(sessionId: String, event: SilentChatEvent) {
                when (event) {
                    is SilentChatEvent.SessionReady -> {
                        if (targetSessionId == null) {
                            targetSessionId = event.sessionId
                            sessionStore.assignSessionToPlaybook(event.sessionId, playbookId)
                        }
                    }
                    is SilentChatEvent.Reply -> {
                        if (sessionId == targetSessionId) {
                            replyAccumulator.set(event.accumulated)
                        }
                    }
                    is SilentChatEvent.Complete -> {
                        if (sessionId == targetSessionId) {
                            completion.complete(event.fullReply)
                        }
                    }
                    is SilentChatEvent.Error -> {
                        if (sessionId == targetSessionId) {
                            completion.completeExceptionally(RuntimeException(event.message))
                        }
                    }
                    else -> {}
                }
            }
        })

        try {
            val mode = if (step.agentMode) ChatModeService.BuiltInChatModes.Agent else null
            service.sendMessage(
                message = prompt,
                mode = mode,
                newSession = true,
                silent = true,
            )

            return completion.await()
        } finally {
            conn.disconnect()
        }
    }

    private fun publishProgress(playbookId: String, playbookName: String, steps: List<PlaybookStepDef>) {
        val data = mapOf(
            "playbookId" to playbookId,
            "playbookName" to playbookName,
            "steps" to steps.map { step ->
                mapOf(
                    "id" to step.id,
                    "name" to step.name,
                    "state" to (stepStates[step.id]?.name?.lowercase() ?: "idle"),
                    "result" to stepResults[step.id],
                    "dependsOn" to step.dependsOn,
                )
            },
        )
        // Publish via the existing SilentChatListener topic as a custom event won't work
        // We'll use a dedicated topic instead — push directly to any listening panel
        project.messageBus.syncPublisher(PlaybookProgressListener.TOPIC)
            .onProgress(gson.toJson(data))
    }

    private fun parseSteps(pb: JsonObject): MutableList<PlaybookStepDef> {
        val stepsArray = pb.getAsJsonArray("steps") ?: return mutableListOf()
        return stepsArray.map { el ->
            val obj = el.asJsonObject
            val dependsOn = if (obj.has("dependsOn")) {
                obj.getAsJsonArray("dependsOn").map { it.asString }
            } else emptyList()
            PlaybookStepDef(
                id = obj.get("id").asString,
                name = obj.get("name").asString,
                prompt = obj.get("prompt").asString,
                agentMode = obj.get("agentMode")?.asBoolean ?: false,
                dependsOn = dependsOn,
            )
        }.toMutableList()
    }
}

data class PlaybookStepDef(
    val id: String,
    val name: String,
    val prompt: String,
    val agentMode: Boolean,
    val dependsOn: List<String>,
)

enum class StepExecutionState {
    IDLE, RUNNING, SUCCESS, ERROR
}
