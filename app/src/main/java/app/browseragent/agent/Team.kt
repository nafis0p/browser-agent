package app.browseragent.agent

import app.browseragent.browser.BrowserController
import app.browseragent.data.Store
import app.browseragent.llm.LlmException
import app.browseragent.llm.LlmRouter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val DEFAULT_PLANNER_PROMPT: String = """
You are the planner of a small team that controls a web browser. You never use tools yourself.
You get the user's goal and the current page. Write a short numbered plan (3 to 8 steps) that the executor can follow. Name the sites or pages to use. If the goal needs personal data such as an email or a password, tell the executor to use the personal-data placeholders (written with a dollar sign) and never real values.
End with one line that starts with 'Done when:' and states how to know that the goal is achieved. Keep it under 150 words.
If the goal is only a question that needs no browsing, start your reply with 'No browsing needed' and then answer it.
""".trimIndent()

val DEFAULT_EXECUTOR_PROMPT: String = """
You are the executor of a small team that controls a web browser. A planner wrote the plan and a checker will verify your work afterwards.
Follow the plan step by step with the browser tools. Adapt only when the page differs from what the planner expected. Do not write a new plan and do not redo steps that are already done.
When you are finished, reply with a short report: what you did and what the page shows now.
""".trimIndent()

val DEFAULT_CHECKER_PROMPT: String = """
You are the checker of a small team that controls a web browser. You never use tools yourself.
You get the goal, the plan, the executor's report and the current page state. Decide whether the goal is really achieved, judging from the page state and not only from the report.
The first line of your reply must be exactly PASS or FAIL. After FAIL, add short bullet points: what is missing or wrong, and what the executor should do next.
Be strict about irreversible actions: say so if something was submitted, paid or sent twice, or not at all. Keep it under 120 words.
""".trimIndent()

private fun tmsg(role: String, content: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

/**
 * Team mode: a planner writes the steps, the executor (the normal Agent) carries them out,
 * and a checker verifies the result. If the checker says FAIL the executor gets the feedback
 * and tries again, up to the configured number of fix attempts.
 */
class Orchestrator(
    private val router: LlmRouter,
    private val browser: BrowserController,
    private val store: Store,
    private val ui: AgentUi,
    private val lastAssistant: () -> String,
    private val clearLastAssistant: () -> Unit,
) {
    /** Everything the planner and checker read has stored private values turned into placeholders. */
    private fun redact(text: String): String {
        var out = text
        for ((k, v) in store.vault.entries.sortedByDescending { it.value.length }) {
            if (v.length >= 4) out = out.replace(v, "\$" + k, ignoreCase = true)
        }
        return out
    }

    private suspend fun pageState(maxChars: Int): String {
        val raw = try {
            if (browser.url == "about:blank") "(blank page)" else browser.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "(page state unavailable)"
        }
        return redact(raw).take(maxChars)
    }

    /** One plain model call (no tools) for the planner or the checker. */
    private suspend fun ask(aliasName: String, system: String, user: String): String {
        val alias = aliasName.ifBlank { null }
            ?: store.activeAlias
            ?: throw LlmException("Add a model in the Providers tab first.")
        val res = router.chat(alias, listOf(tmsg("system", system), tmsg("user", user)), JsonArray(emptyList()))
        if (res.attempts.isNotEmpty()) {
            ui.log(Kind.INFO, "Fallback: " + res.attempts.joinToString("; ") + " -> " + res.via)
        }
        return res.content.orEmpty().trim()
    }

    /** Runs the executor with its own model (the override is never saved). */
    private suspend fun runExecutor(agent: Agent, text: String) {
        store.aliasOverride = store.executorAlias.ifBlank { null }
        try {
            agent.run(text)
        } finally {
            store.aliasOverride = null
        }
    }

    suspend fun run(agent: Agent, goalRaw: String) {
        val goal = redact(goalRaw)

        ui.log(Kind.INFO, "Planner is working out the steps...")
        val plannerPrompt = store.plannerPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_PLANNER_PROMPT
        val plan = ask(
            store.plannerAlias,
            plannerPrompt,
            "Goal:\n" + goal + "\n\nCurrent page:\n" + pageState(5000),
        )
        ui.log(Kind.ASSISTANT, "Plan\n" + plan)
        if (plan.trimStart().startsWith("No browsing needed", ignoreCase = true)) return

        val executorPrompt = store.executorPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_EXECUTOR_PROMPT
        val checkerPrompt = store.checkerPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_CHECKER_PROMPT

        var instruction = goalRaw +
            "\n\n[Your role]\n" + executorPrompt +
            "\n\n[Plan from the planner]\n" + plan
        var round = 0
        while (true) {
            ui.log(
                Kind.INFO,
                if (round == 0) "Executor is carrying out the plan..." else "Executor is fixing the problems...",
            )
            clearLastAssistant()
            runExecutor(agent, instruction)
            val report = lastAssistant()

            ui.log(Kind.INFO, "Checker is verifying the result...")
            val verdict = ask(
                store.checkerAlias,
                checkerPrompt,
                "Goal:\n" + goal +
                    "\n\nPlan:\n" + plan +
                    "\n\nExecutor report:\n" + redact(report) +
                    "\n\nCurrent page:\n" + pageState(7000),
            )
            val pass = verdict.trimStart().startsWith("PASS", ignoreCase = true)
            ui.log(Kind.ASSISTANT, "Checker\n" + verdict)
            if (pass) return
            if (round >= store.maxFixRounds) {
                ui.log(
                    Kind.INFO,
                    "The checker still sees problems after " + round + " fix attempt(s). Please review the page yourself.",
                )
                return
            }
            round += 1
            instruction = "[Checker feedback] The checker found problems with your work. " +
                "Fix only what is wrong. Do NOT redo steps that are already done, and never submit anything twice.\n" +
                verdict
        }
    }
}
