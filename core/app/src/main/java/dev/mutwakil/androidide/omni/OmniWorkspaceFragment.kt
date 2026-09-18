package dev.mutwakil.androidide.omni

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.omnilink.sdk.AgentClientMode
import com.omnilink.sdk.AgentTaskEvent
import com.omnilink.sdk.AgentTaskRequest
import dev.mutwakil.androidide.activities.editor.EditorHandlerActivity
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

/**
 * Docked Omni workspace shown inside AndroidIDE's END drawer.
 *
 * Workspace remains the model/tool/memory runtime. This fragment is only an IDE-native client UI,
 * so closing it never terminates the durable conversation saved by Omni Dev Workspace.
 */
class OmniWorkspaceFragment : Fragment() {

    private var client: OmniAgentClient? = null
    private var conversations: OmniConversationStore? = null
    private var runningJob: Job? = null
    private var currentTaskId: String? = null
    private var conversationId: String = ""
    private var projectRoot: String = ""

    private lateinit var historyColumn: LinearLayout
    private lateinit var transcript: TextView
    private lateinit var console: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var consoleScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var prompt: EditText
    private lateinit var send: MaterialButton
    private lateinit var stop: MaterialButton
    private lateinit var chatSurfaceButton: MaterialButton
    private lateinit var consoleSurfaceButton: MaterialButton
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var chatModeButton: MaterialButton
    private lateinit var agentModeButton: MaterialButton
    private lateinit var teamModeButton: MaterialButton

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity() as EditorHandlerActivity
        val context = requireContext()
        projectRoot = ProjectManagerImpl.getInstance().projectDirPath
        client = OmniAgentClient(context)
        conversations = OmniConversationStore(context)
        conversationId = conversations!!.current(projectRoot)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            )
        }

        val historyPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp(), 10.dp(), 6.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(108.dp(), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainer
                )
            )
        }
        historyPane.addView(TextView(context).apply {
            text = "Chats"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(6.dp(), 0, 0, 6.dp())
        })
        historyPane.addView(MaterialButton(context).apply {
            text = "+ New"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { newConversation() }
        })
        val historyScroll = ScrollView(context)
        historyColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        historyScroll.addView(historyColumn)
        historyPane.addView(
            historyScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Omni"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(MaterialButton(context).apply {
            text = "×"
            textSize = 20f
            minWidth = 40.dp()
            isAllCaps = false
            setOnClickListener { activity.closeOmniWorkspace() }
        })
        main.addView(header)

        main.addView(TextView(context).apply {
            text = projectRoot.substringAfterLast('/').ifBlank { "No project" }
            textSize = 11f
            alpha = 0.72f
        })

        modeGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        chatModeButton = modeButton("Chat")
        agentModeButton = modeButton("Agent")
        teamModeButton = modeButton("Team")
        modeGroup.addView(chatModeButton)
        modeGroup.addView(agentModeButton)
        modeGroup.addView(teamModeButton)
        modeGroup.check(agentModeButton.id)
        main.addView(modeGroup)

        val surfaceGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        chatSurfaceButton = modeButton("Chat")
        consoleSurfaceButton = modeButton("Console")
        surfaceGroup.addView(chatSurfaceButton)
        surfaceGroup.addView(consoleSurfaceButton)
        surfaceGroup.check(chatSurfaceButton.id)
        main.addView(surfaceGroup)

        status = TextView(context).apply {
            text = "Ready • Workspace tools, MCP, web, Git, logs and IDE diagnostics connected"
            textSize = 11f
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        main.addView(status)

        val surfaces = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        transcript = TextView(context).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }
        transcriptScroll = ScrollView(context).apply { addView(transcript) }
        console = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }
        consoleScroll = ScrollView(context).apply {
            addView(console)
            visibility = View.GONE
        }
        surfaces.addView(
            transcriptScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surfaces.addView(
            consoleScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        main.addView(surfaces)

        surfaceGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val showConsole = checkedId == consoleSurfaceButton.id
            transcriptScroll.visibility = if (showConsole) View.GONE else View.VISIBLE
            consoleScroll.visibility = if (showConsole) View.VISIBLE else View.GONE
        }

        val quickScroll = HorizontalScrollView(context)
        val quickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        quickScroll.addView(quickRow)
        addQuickAction(quickRow, "Explain") {
            setPrompt("Explain the current file in depth and how it fits into this project.")
        }
        addQuickAction(quickRow, "Fix build") {
            setPrompt(
                "Inspect the latest AndroidIDE build output and diagnostics, fix the root cause, " +
                    "then build and test through native IDE capabilities until verified."
            )
        }
        addQuickAction(quickRow, "Diagnostics") {
            setPrompt(
                "Inspect AndroidIDE project diagnostics, active-file LSP diagnostics, IDE logs and " +
                    "recent build output. Explain the root causes and fix actionable project issues."
            )
        }
        addQuickAction(quickRow, "Git") {
            setPrompt(
                "Inspect the current native Git status and diff. Summarize changes and flag risks. " +
                    "Do not stage, commit, checkout, merge, pull or push without my approval."
            )
        }
        main.addView(quickScroll)

        prompt = EditText(context).apply {
            hint = "Ask Omni about code, logs, build, Git, diagnostics…"
            minLines = 2
            maxLines = 5
        }
        main.addView(prompt)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        stop = MaterialButton(context).apply {
            text = "Stop"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { stopTask() }
        }
        send = MaterialButton(context).apply {
            text = "Send"
            isAllCaps = false
            setOnClickListener { sendPrompt() }
        }
        actions.addView(stop)
        actions.addView(send)
        main.addView(actions)

        root.addView(historyPane)
        root.addView(main)
        refreshHistory()
        loadConversation(conversationId)
        return root
    }

    override fun onDestroyView() {
        runningJob?.cancel()
        runningJob = null
        client?.disconnect()
        client = null
        super.onDestroyView()
    }

    private fun sendPrompt() {
        val userText = prompt.text?.toString()?.trim().orEmpty()
        if (userText.isBlank() || currentTaskId != null || projectRoot.isBlank()) return

        val store = conversations ?: return
        val activeClient = client ?: return
        val mode = when (modeGroup.checkedButtonId) {
            chatModeButton.id -> AgentClientMode.CHAT
            teamModeButton.id -> AgentClientMode.TEAM
            else -> AgentClientMode.AGENT
        }

        val taskId = "androidide-task-" + UUID.randomUUID()
        currentTaskId = taskId
        send.isEnabled = false
        stop.isEnabled = true
        status.text = "Collecting live AndroidIDE context…"
        appendChat("\n\nYou: $userText\n\nOmni: ")
        prompt.text?.clear()
        store.saveTranscript(projectRoot, conversationId, transcript.text.toString())
        refreshHistory()

        runningJob = lifecycleScope.launch {
            val contextJson = runCatching { OmniIdeStateBridge.collectContext() }
                .getOrElse {
                    status.text = "Context error: " + (it.message ?: "unknown")
                    buildJsonObject {}
                }
            val needsTitle = store.needsTitle(conversationId)
            val title = if (needsTitle) topic(userText) else null
            val request = AgentTaskRequest(
                taskId = taskId,
                clientConversationId = conversationId,
                title = title,
                appDisplayName = "Omni AndroidIDE",
                prompt = userText,
                scopePath = projectRoot,
                mode = mode,
                context = contextJson
            )
            if (needsTitle && title != null) store.setTitle(projectRoot, conversationId, title)

            var streamed = false
            try {
                activeClient.runTask(request).collect { event ->
                    when (event) {
                        is AgentTaskEvent.Started -> {
                            status.text = "Running • saved in Workspace as “${event.conversationTitle}”"
                            store.setTitle(projectRoot, conversationId, event.conversationTitle)
                            refreshHistory()
                        }
                        is AgentTaskEvent.Status -> {
                            status.text = event.label + (event.detail?.let { " • $it" } ?: "")
                            appendConsole(
                                "[STATUS] ${event.label}" +
                                    (event.detail?.let { " • $it" } ?: "") + "\n"
                            )
                        }
                        is AgentTaskEvent.StreamChunk -> {
                            streamed = true
                            appendChat(event.delta)
                        }
                        is AgentTaskEvent.Console -> {
                            val marker = if (event.isError) "ERROR" else event.kind.uppercase()
                            appendConsole(
                                "[$marker] ${event.name ?: event.kind}: ${event.summary}\n" +
                                    (event.detail?.let { it.take(6_000) + "\n" } ?: "")
                            )
                        }
                        is AgentTaskEvent.FinalAnswer -> {
                            if (!streamed) appendChat(event.content)
                            appendChat("\n")
                            status.text = "Completed • Workspace history + Agent Console saved"
                        }
                        is AgentTaskEvent.Error -> {
                            appendChat("\n⚠ ${event.message}\n")
                            appendConsole("[ERROR/${event.code}] ${event.message}\n")
                            status.text = "Failed: ${event.code}"
                        }
                        is AgentTaskEvent.Cancelled -> {
                            appendChat("\n[Cancelled]\n")
                            appendConsole("[CANCELLED] $taskId\n")
                            status.text = "Cancelled"
                        }
                    }
                    store.saveTranscript(projectRoot, conversationId, transcript.text.toString())
                    store.saveConsole(projectRoot, conversationId, console.text.toString())
                }
            } catch (error: Exception) {
                appendChat("\n⚠ ${error.message ?: "Omni connection failed"}\n")
                appendConsole("[CONNECTION ERROR] ${error.stackTraceToString().take(6_000)}\n")
                status.text = "Connection failed"
            } finally {
                currentTaskId = null
                stop.isEnabled = false
                send.isEnabled = true
                store.saveTranscript(projectRoot, conversationId, transcript.text.toString())
                store.saveConsole(projectRoot, conversationId, console.text.toString())
                refreshHistory()
            }
        }
    }

    private fun stopTask() {
        val taskId = currentTaskId ?: return
        lifecycleScope.launch {
            runCatching { client?.cancel(taskId) }
            runningJob?.cancel()
            currentTaskId = null
            stop.isEnabled = false
            send.isEnabled = true
            status.text = "Stopped"
        }
    }

    private fun newConversation() {
        if (currentTaskId != null) return
        val store = conversations ?: return
        conversationId = store.newConversation(projectRoot)
        transcript.text = ""
        console.text = ""
        status.text = "New Omni conversation"
        refreshHistory()
    }

    private fun loadConversation(id: String) {
        if (currentTaskId != null) return
        val store = conversations ?: return
        conversationId = id
        store.select(projectRoot, id)
        transcript.text = store.transcript(id)
        console.text = store.console(id)
        status.text = store.title(id)
        refreshHistory()
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshHistory() {
        if (!::historyColumn.isInitialized) return
        val store = conversations ?: return
        historyColumn.removeAllViews()
        store.list(projectRoot).forEach { item ->
            historyColumn.addView(MaterialButton(requireContext()).apply {
                text = item.title.take(28)
                textSize = 10f
                maxLines = 2
                isAllCaps = false
                alpha = if (item.id == conversationId) 1f else 0.72f
                setOnClickListener { loadConversation(item.id) }
            })
        }
    }

    private fun setPrompt(value: String) {
        prompt.setText(value)
        prompt.setSelection(prompt.text?.length ?: 0)
        prompt.requestFocus()
    }

    private fun appendChat(value: String) {
        transcript.append(value)
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendConsole(value: String) {
        val merged = (console.text.toString() + value).takeLast(80_000)
        console.text = merged
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun modeButton(label: String) =
        MaterialButton(requireContext()).apply {
            id = View.generateViewId()
            text = label
            textSize = 11f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun addQuickAction(row: LinearLayout, label: String, action: () -> Unit) {
        row.addView(MaterialButton(requireContext()).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun topic(value: String): String =
        value.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(72)
            ?: "AndroidIDE conversation"

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()
}
