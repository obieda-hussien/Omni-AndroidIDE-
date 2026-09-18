package dev.mutwakil.androidide.omni

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

object OmniChatDialog {

    fun show(activity: EditorHandlerActivity) {
        val client = OmniAgentClient(activity)
        val conversations = OmniConversationStore(activity)
        val projectRoot = ProjectManagerImpl.getInstance().projectDirPath
        if (projectRoot.isBlank()) return

        val sheet = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(activity), 14.dp(activity), 16.dp(activity), 16.dp(activity))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "Omni"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurface
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val newChat = MaterialButton(activity).apply {
            text = "New"
            isAllCaps = false
        }
        header.addView(title)
        header.addView(newChat)
        root.addView(header)

        root.addView(TextView(activity).apply {
            text = "Project: " + projectRoot.substringAfterLast('/')
            textSize = 12f
            alpha = 0.72f
        })

        val modeGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp(activity) }
        }
        val chatButton = modeButton(activity, "Chat")
        val agentButton = modeButton(activity, "Agent")
        val teamButton = modeButton(activity, "Team")
        modeGroup.addView(chatButton)
        modeGroup.addView(agentButton)
        modeGroup.addView(teamButton)
        modeGroup.check(agentButton.id)
        root.addView(modeGroup)

        val status = TextView(activity).apply {
            text = "Ready — full Workspace tools, MCP and web search are available."
            textSize = 12f
            setPadding(0, 8.dp(activity), 0, 6.dp(activity))
        }
        root.addView(status)

        val transcript = TextView(activity).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(12.dp(activity), 10.dp(activity), 12.dp(activity), 10.dp(activity))
        }
        val scroll = ScrollView(activity).apply {
            addView(transcript)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                260.dp(activity)
            )
        }
        root.addView(scroll)

        val quickScroll = HorizontalScrollView(activity)
        val quickRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        quickScroll.addView(quickRow)
        root.addView(quickScroll)

        val prompt = EditText(activity).apply {
            hint = "Ask Omni about this project, file, error, or task…"
            minLines = 2
            maxLines = 5
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp(activity) }
        }
        root.addView(prompt)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val stop = MaterialButton(activity).apply {
            text = "Stop"
            isAllCaps = false
            isEnabled = false
        }
        val send = MaterialButton(activity).apply {
            text = "Send"
            isAllCaps = false
        }
        actions.addView(stop)
        actions.addView(send)
        root.addView(actions)

        fun setPrompt(value: String) {
            prompt.setText(value)
            prompt.setSelection(prompt.text?.length ?: 0)
            prompt.requestFocus()
        }

        addQuickAction(activity, quickRow, "Explain file") {
            setPrompt(
                "Explain the current file in depth: its responsibility, important flows, " +
                    "dependencies, risks, and how it fits into the whole project."
            )
        }
        addQuickAction(activity, quickRow, "Understand project") {
            setPrompt(
                "Analyze this whole Android project. Build a mental model of the architecture, " +
                    "important modules, entry points, data flow, build setup and likely problem areas."
            )
        }
        addQuickAction(activity, quickRow, "Fix build") {
            setPrompt(
                "Analyze the current build output and project context, fix the root cause, then " +
                    "use AndroidIDE build/test capabilities to verify the project until it succeeds " +
                    "or there is a concrete blocker. Keep all changes revision-safe."
            )
        }

        var currentTaskId: String? = null
        var runningJob: Job? = null
        var conversationId = conversations.current(projectRoot)

        fun append(text: String) {
            transcript.append(text)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        newChat.setOnClickListener {
            if (currentTaskId != null) return@setOnClickListener
            conversationId = conversations.newConversation(projectRoot)
            transcript.text = ""
            status.text = "New Omni conversation"
        }

        stop.setOnClickListener {
            val taskId = currentTaskId ?: return@setOnClickListener
            activity.lifecycleScope.launch {
                runCatching { client.cancel(taskId) }
                runningJob?.cancel()
                currentTaskId = null
                stop.isEnabled = false
                send.isEnabled = true
                status.text = "Stopped"
            }
        }

        send.setOnClickListener {
            val userText = prompt.text?.toString()?.trim().orEmpty()
            if (userText.isBlank() || currentTaskId != null) return@setOnClickListener

            val mode = when (modeGroup.checkedButtonId) {
                chatButton.id -> AgentClientMode.CHAT
                teamButton.id -> AgentClientMode.TEAM
                else -> AgentClientMode.AGENT
            }
            val taskId = "androidide-task-" + UUID.randomUUID()
            currentTaskId = taskId
            send.isEnabled = false
            stop.isEnabled = true
            status.text = "Collecting AndroidIDE context…"
            append("\n\nYou: " + userText + "\n\nOmni: ")
            prompt.text?.clear()

            runningJob = activity.lifecycleScope.launch {
                val contextJson = runCatching { OmniIdeStateBridge.collectContext() }
                    .getOrElse {
                        status.text = "Context error: " + (it.message ?: "unknown")
                        buildJsonObject {}
                    }
                val needsTitle = conversations.needsTitle(conversationId)
                val request = AgentTaskRequest(
                    taskId = taskId,
                    clientConversationId = conversationId,
                    title = if (needsTitle) topic(userText) else null,
                    appDisplayName = "Omni AndroidIDE",
                    prompt = userText,
                    scopePath = projectRoot,
                    mode = mode,
                    context = contextJson
                )
                if (needsTitle) conversations.markTitled(conversationId)

                var streamed = false
                client.runTask(request).collect { event ->
                    when (event) {
                        is AgentTaskEvent.Started -> {
                            status.text = "Running • saved in Workspace as “" +
                                event.conversationTitle + "”"
                        }
                        is AgentTaskEvent.Status -> {
                            status.text = event.label +
                                (event.detail?.let { " • " + it } ?: "")
                        }
                        is AgentTaskEvent.StreamChunk -> {
                            streamed = true
                            append(event.delta)
                        }
                        is AgentTaskEvent.Console -> {
                            val marker = if (event.isError) "⚠" else "⚙"
                            append(
                                "\n" + marker + " " +
                                    (event.name ?: event.kind) +
                                    ": " + event.summary.take(500) + "\n"
                            )
                        }
                        is AgentTaskEvent.FinalAnswer -> {
                            if (!streamed) append(event.content)
                            append("\n")
                            status.text =
                                "Completed • history and Agent Console saved in Workspace"
                        }
                        is AgentTaskEvent.Error -> {
                            append("\n⚠ " + event.message + "\n")
                            status.text = "Failed: " + event.code
                        }
                        is AgentTaskEvent.Cancelled -> {
                            append("\n[Cancelled]\n")
                            status.text = "Cancelled"
                        }
                    }
                }

                currentTaskId = null
                stop.isEnabled = false
                send.isEnabled = true
            }
        }

        sheet.setOnDismissListener {
            runningJob?.cancel()
            client.disconnect()
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun modeButton(activity: EditorHandlerActivity, label: String) =
        MaterialButton(activity).apply {
            id = View.generateViewId()
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

    private fun addQuickAction(
        activity: EditorHandlerActivity,
        row: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        row.addView(
            MaterialButton(activity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }
        )
    }

    private fun topic(prompt: String): String =
        prompt.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(72)
            ?: "AndroidIDE conversation"

    private fun Int.dp(activity: EditorHandlerActivity): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}
