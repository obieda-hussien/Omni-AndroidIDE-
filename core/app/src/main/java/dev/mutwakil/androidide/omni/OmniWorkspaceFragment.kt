package dev.mutwakil.androidide.omni

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omnilink.sdk.AgentClientMode
import com.omnilink.sdk.AgentTaskState
import com.omnilink.sdk.AgentConversationSnapshot
import com.omnilink.sdk.AgentConversationReadQuery
import com.omnilink.sdk.AgentConversationQuery
import com.omnilink.sdk.AgentTaskEvent
import com.omnilink.sdk.AgentTaskRequest
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.activities.editor.EditorHandlerActivity
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray

/**
 * OmniDev-styled workspace embedded directly inside AndroidIDE.
 *
 * The fragment intentionally mirrors OmniDev Workspace's chat shell instead of looking like an
 * IDE utility panel: full header, mode segmented control, message bubbles, live Agent Console,
 * bottom composer and an overlay chat-history drawer. AndroidIDE remains only the native IDE client;
 * Workspace still owns the model, tools, MCP, memory, web research and durable remote history.
 */
class OmniWorkspaceFragment : Fragment() {

    companion object {
        private const val WORKSPACE_PACKAGE = "com.omnidev.workspace"

        private val BG = Color.rgb(255, 251, 254)
        private val SURFACE = Color.WHITE
        private val PRIMARY = Color.rgb(108, 99, 255)
        private val PRIMARY_DARK = Color.rgb(74, 66, 212)
        private val PRIMARY_CONTAINER = Color.rgb(235, 221, 255)
        private val SEGMENT_BG = Color.rgb(241, 232, 247)
        private val SECONDARY_CONTAINER = Color.rgb(238, 226, 244)
        private val TERTIARY = Color.rgb(255, 107, 157)
        private val ON_SURFACE = Color.rgb(35, 34, 43)
        private val ON_SURFACE_MUTED = Color.rgb(108, 106, 116)
        private val OUTLINE = Color.rgb(130, 126, 135)
        private val TERMINAL = Color.rgb(13, 17, 23)
        private val TERMINAL_ROW = Color.rgb(20, 25, 33)
        private val TERMINAL_TEXT = Color.rgb(230, 237, 243)
        private val SUCCESS = Color.rgb(34, 197, 94)
        private val ERROR = Color.rgb(239, 68, 68)
    }

    private var client: OmniAgentClient? = null
    private var conversations: OmniConversationStore? = null
    private var runningJob: Job? = null
    private var historyRefreshJob: Job? = null
    private var currentTaskId: String? = null
    private var conversationId: String = ""
    private var projectRoot: String = ""

    private var selectedMode: AgentClientMode = AgentClientMode.CHAT
    private var localTranscript: String = ""
    private var localConsole: String = ""
    private var streamedAssistant: StringBuilder? = null
    private var activeAssistantView: TextView? = null

    private lateinit var rootFrame: FrameLayout
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var emptyState: LinearLayout
    private lateinit var status: TextView
    private lateinit var prompt: EditText
    private lateinit var sendButton: TextView
    private lateinit var modeChat: TextView
    private lateinit var modeAgent: TextView
    private lateinit var modeTeam: TextView

    private lateinit var historyScrim: View
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var historyCount: TextView
    private lateinit var historySearch: EditText

    private var liveConsoleCard: LinearLayout? = null
    private var liveConsoleHeader: TextView? = null
    private var liveConsoleBody: TextView? = null
    private var liveConsoleEvents: Int = 0

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity() as EditorHandlerActivity
        projectRoot = ProjectManagerImpl.getInstance().projectDirPath
        client = OmniAgentClient(requireContext())
        conversations = OmniConversationStore(requireContext())
        conversationId = conversations!!.current(projectRoot)

        rootFrame = FrameLayout(requireContext()).apply {
            setBackgroundColor(BG)
        }

        val main = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(18.dp(), 10.dp(), 18.dp(), 10.dp())
        }
        rootFrame.addView(
            main,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        main.addView(buildHeader(activity))
        main.addView(buildModeSwitcher())

        status = TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(ON_SURFACE_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(8.dp(), 5.dp(), 8.dp(), 4.dp())
        }
        main.addView(status)

        val contentFrame = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        messagesColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp(), 0, 18.dp())
        }
        messagesScroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                messagesColumn,
                ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        contentFrame.addView(
            messagesScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        emptyState = buildEmptyState()
        contentFrame.addView(
            emptyState,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        main.addView(contentFrame)

        main.addView(buildComposer())

        historyScrim = View(requireContext()).apply {
            setBackgroundColor(Color.argb(55, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { hideHistory() }
        }
        rootFrame.addView(
            historyScrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        historyPanel = buildHistoryPanel()
        val panelWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            .coerceAtLeast(280.dp())
        rootFrame.addView(
            historyPanel,
            FrameLayout.LayoutParams(
                panelWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )

        loadConversation(conversationId)
        return rootFrame
    }

    override fun onDestroyView() {
        runningJob?.cancel()
        runningJob = null
        historyRefreshJob?.cancel()
        historyRefreshJob = null
        client?.disconnect()
        client = null
        super.onDestroyView()
    }

    private fun buildHeader(activity: EditorHandlerActivity): View {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 12.dp())
        }

        header.addView(iconButton("⚙", "Workspace options") {
            showWorkspaceMenu(it, activity)
        })

        val titleBlock = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(requireContext()).apply {
            text = "OmniDev Workspace"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ON_SURFACE)
            gravity = Gravity.CENTER
        })
        titleBlock.addView(TextView(requireContext()).apply {
            text = "AndroidIDE • Workspace Connected ⚡"
            textSize = 12f
            setTextColor(TERTIARY)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(titleBlock)

        header.addView(iconButton("↶", "Chat history") {
            showHistory()
        })
        return header
    }

    private fun buildModeSwitcher(): View {
        val shell = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(SEGMENT_BG, 18f)
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                50.dp()
            ).apply {
                bottomMargin = 8.dp()
            }
        }

        modeTeam = segment("Team Agents", AgentClientMode.TEAM)
        modeAgent = segment("Agent", AgentClientMode.AGENT)
        modeChat = segment("Chat", AgentClientMode.CHAT)
        shell.addView(modeTeam)
        shell.addView(modeAgent)
        shell.addView(modeChat)
        updateModeVisuals()
        return shell
    }

    private fun segment(label: String, mode: AgentClientMode): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ON_SURFACE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                if (currentTaskId != null) return@setOnClickListener
                selectedMode = mode
                updateModeVisuals()
            }
        }

    private fun updateModeVisuals() {
        if (!::modeChat.isInitialized) return
        listOf(
            modeTeam to AgentClientMode.TEAM,
            modeAgent to AgentClientMode.AGENT,
            modeChat to AgentClientMode.CHAT
        ).forEach { (view, mode) ->
            val selected = selectedMode == mode
            view.background = if (selected) rounded(PRIMARY_CONTAINER, 16f) else null
            view.setTextColor(if (selected) Color.rgb(48, 27, 78) else ON_SURFACE_MUTED)
            view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun buildEmptyState(): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28.dp(), 40.dp(), 28.dp(), 80.dp())

            addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_omni_agent)
                setColorFilter(Color.rgb(189, 178, 255))
                layoutParams = LinearLayout.LayoutParams(92.dp(), 92.dp()).apply {
                    bottomMargin = 18.dp()
                }
            })
            addView(TextView(requireContext()).apply {
                text = "OmniDev Workspace"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(104, 101, 112))
                gravity = Gravity.CENTER
            })
            addView(TextView(requireContext()).apply {
                text = "Set a Target Context and start coding with AI"
                textSize = 15f
                setTextColor(Color.rgb(164, 160, 168))
                gravity = Gravity.CENTER
                setPadding(0, 8.dp(), 0, 0)
            })
        }

    private fun buildComposer(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp(), 0, 2.dp())
        }

        sendButton = TextView(requireContext()).apply {
            text = "➤"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(PRIMARY)
            setOnClickListener {
                if (currentTaskId != null) stopTask() else sendPrompt()
            }
        }
        row.addView(
            sendButton,
            LinearLayout.LayoutParams(56.dp(), 56.dp()).apply {
                marginEnd = 10.dp()
            }
        )

        val inputShell = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedWithStroke(SURFACE, 28f, OUTLINE, 1)
            setPadding(14.dp(), 2.dp(), 8.dp(), 2.dp())
        }
        prompt = EditText(requireContext()).apply {
            hint = "…Ask OmniDev anything"
            textSize = 16f
            minLines = 1
            maxLines = 4
            setTextColor(ON_SURFACE)
            setHintTextColor(Color.rgb(103, 100, 108))
            background = null
            setPadding(0, 4.dp(), 0, 4.dp())
        }
        inputShell.addView(
            prompt,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        inputShell.addView(TextView(requireContext()).apply {
            text = "📎"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(ON_SURFACE)
            setOnClickListener {
                showStatus("Current editor file is already included in Omni context.")
            }
        }, LinearLayout.LayoutParams(42.dp(), 48.dp()))

        row.addView(
            inputShell,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(TextView(requireContext()).apply {
            text = "+"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(PRIMARY)
            setOnClickListener { showQuickActions(it) }
        }, LinearLayout.LayoutParams(48.dp(), 56.dp()))

        return row
    }

    private fun buildHistoryPanel(): LinearLayout {
        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(249, 248, 253))
            visibility = View.GONE
            elevation = 18f
        }

        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 12.dp(), 10.dp(), 12.dp())
            setBackgroundColor(Color.rgb(244, 241, 252))
        }
        top.addView(iconButton("×", "Close history") { hideHistory() })
        top.addView(iconButton("+", "New conversation") {
            newConversation()
            hideHistory()
        })

        val historyTitle = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(6.dp(), 0, 8.dp(), 0)
        }
        historyTitle.addView(TextView(requireContext()).apply {
            text = "Chat history"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ON_SURFACE)
            gravity = Gravity.END
        })
        historyCount = TextView(requireContext()).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ON_SURFACE_MUTED)
            gravity = Gravity.END
        }
        historyTitle.addView(historyCount)
        top.addView(historyTitle)
        top.addView(TextView(requireContext()).apply {
            text = "↶"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(PRIMARY_DARK)
            background = circle(PRIMARY_CONTAINER)
        }, LinearLayout.LayoutParams(46.dp(), 46.dp()))
        panel.addView(top)

        historySearch = EditText(requireContext()).apply {
            hint = "Search chats"
            textSize = 17f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTextColor(ON_SURFACE)
            setHintTextColor(ON_SURFACE_MUTED)
            background = roundedWithStroke(Color.TRANSPARENT, 18f, OUTLINE, 1)
            setPadding(16.dp(), 0, 16.dp(), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshHistory()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        panel.addView(
            historySearch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                58.dp()
            ).apply {
                setMargins(14.dp(), 14.dp(), 14.dp(), 8.dp())
            }
        )

        val filters = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 2.dp(), 14.dp(), 6.dp())
        }
        filters.addView(TextView(requireContext()).apply {
            text = "⋮"
            textSize = 27f
            setTextColor(ON_SURFACE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(40.dp(), 44.dp()))
        filters.addView(historyFilter("Select"))
        filters.addView(historyFilter("Recent"))
        filters.addView(historyFilter("All chats", true))
        panel.addView(filters)

        panel.addView(View(requireContext()).apply {
            setBackgroundColor(Color.rgb(215, 211, 220))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
            setMargins(14.dp(), 0, 14.dp(), 0)
        })

        historyList = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 18.dp())
        }
        panel.addView(
            ScrollView(requireContext()).apply { addView(historyList) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return panel
    }

    private fun historyFilter(label: String, selected: Boolean = false): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            if (selected) background = rounded(Color.argb(22, 108, 99, 255), 12f)
            layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f)
        }

    private fun sendPrompt() {
        val userText = prompt.text?.toString()?.trim().orEmpty()
        if (userText.isBlank() || currentTaskId != null || projectRoot.isBlank()) return

        val store = conversations ?: return
        val activeClient = client ?: return
        val taskId = "androidide-task-" + UUID.randomUUID()
        currentTaskId = taskId
        store.saveRunCursor(conversationId, taskId, 0L)
        updateSendState(true)
        showStatus("Collecting live AndroidIDE context…")

        emptyState.visibility = View.GONE
        addUserBubble(userText)
        beginLiveConsole()
        activeAssistantView = addAssistantBubble("")
        streamedAssistant = StringBuilder()

        localTranscript += "\n\nYou: $userText\n\nOmni: "
        prompt.text?.clear()
        store.saveTranscript(projectRoot, conversationId, localTranscript)
        refreshHistory()

        runningJob = lifecycleScope.launch {
            val contextJson = runCatching { OmniIdeStateBridge.collectContext() }
                .getOrElse {
                    showStatus("Context error: " + (it.message ?: "unknown"))
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
                mode = selectedMode,
                context = contextJson
            )
            if (needsTitle && title != null) store.setTitle(projectRoot, conversationId, title)

            var terminalReceived = false
            var lastLocalCheckpoint = 0L

            fun persistLocal(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastLocalCheckpoint < 650L) return
                lastLocalCheckpoint = now
                store.saveTranscript(projectRoot, conversationId, localTranscript)
                store.saveConsole(projectRoot, conversationId, localConsole)
            }

            try {
                activeClient.runTask(request).collect { event ->
                    terminalReceived = applyAgentEvent(event, taskId, store) || terminalReceived
                    persistLocal(force = terminalReceived)
                    store.saveRunCursor(conversationId, taskId, event.sequence)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendConsoleLine(
                    "[LINK] Live callback interrupted: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                showStatus("Connection interrupted • reconnecting from saved event sequence…")
                terminalReceived = recoverExistingTask(taskId, store)
            } finally {
                if (terminalReceived) {
                    store.clearRunCursor(conversationId)
                    runCatching { syncConversationFromWorkspace(conversationId) }
                }
                currentTaskId = null
                updateSendState(false)
                persistLocal(force = true)
                refreshHistory()
            }
        }
    }

    private fun applyAgentEvent(
        event: AgentTaskEvent,
        taskId: String,
        store: OmniConversationStore
    ): Boolean {
        when (event) {
            is AgentTaskEvent.Started -> {
                showStatus("Running • saved in Workspace as “" + event.conversationTitle + "”")
                store.setTitle(projectRoot, conversationId, event.conversationTitle)
                refreshHistory()
            }
            is AgentTaskEvent.Status -> {
                showStatus(event.label + (event.detail?.let { " • " + it } ?: ""))
                appendConsoleLine(
                    "[STATUS] " + event.label +
                        (event.detail?.let { " • " + it } ?: "")
                )
            }
            is AgentTaskEvent.StreamChunk -> {
                if (activeAssistantView == null) activeAssistantView = addAssistantBubble("")
                if (streamedAssistant == null) streamedAssistant = StringBuilder()
                streamedAssistant?.append(event.delta)
                localTranscript += event.delta
                activeAssistantView?.text = streamedAssistant.toString()
                scrollMessagesToBottom()
            }
            is AgentTaskEvent.Console -> {
                val marker = if (event.isError) "ERROR" else event.kind.uppercase()
                appendConsoleLine(
                    "[" + marker + "] " + (event.name ?: event.kind) + ": " + event.summary
                )
                event.detail?.takeIf { it.isNotBlank() }?.let {
                    appendConsoleLine(it.take(3_500))
                }
            }
            is AgentTaskEvent.FinalAnswer -> {
                if (activeAssistantView == null) activeAssistantView = addAssistantBubble("")
                if (streamedAssistant.isNullOrEmpty()) {
                    streamedAssistant = StringBuilder(event.content)
                    localTranscript += event.content
                }
                activeAssistantView?.text = event.content
                localTranscript += "\n"
                finishLiveConsole(true)
                showStatus("Completed • canonical history + Agent Console saved in Workspace")
                return true
            }
            is AgentTaskEvent.Error -> {
                val message = "⚠ " + event.message
                if (activeAssistantView == null) activeAssistantView = addAssistantBubble("")
                if (streamedAssistant.isNullOrEmpty()) {
                    streamedAssistant = StringBuilder(message)
                    localTranscript += message
                    activeAssistantView?.text = message
                }
                appendConsoleLine("[ERROR/" + event.code + "] " + event.message)
                finishLiveConsole(false)
                showStatus("Failed: " + event.code)
                return true
            }
            is AgentTaskEvent.Cancelled -> {
                appendConsoleLine("[CANCELLED] " + taskId)
                finishLiveConsole(false)
                showStatus("Cancelled")
                return true
            }
        }
        return false
    }

    private suspend fun recoverExistingTask(
        taskId: String,
        store: OmniConversationStore
    ): Boolean {
        val activeClient = client ?: return false
        val replaySupported = runCatching { activeClient.supportsEventReplay() }
            .getOrDefault(false)
        if (!replaySupported) {
            return recoverLegacyTaskBySnapshot(taskId, store)
        }

        var afterSequence = store.runCursor(conversationId)?.lastSequence ?: 0L

        while (true) {
            try {
                val page = activeClient.replayTaskEvents(
                    taskId = taskId,
                    afterSequence = afterSequence,
                    limit = 50
                )
                for (event in page.events) {
                    if (event.sequence <= afterSequence) continue
                    val terminal = applyAgentEvent(event, taskId, store)
                    store.saveTranscript(projectRoot, conversationId, localTranscript)
                    store.saveConsole(projectRoot, conversationId, localConsole)
                    afterSequence = event.sequence
                    store.saveRunCursor(conversationId, taskId, afterSequence)
                    if (terminal) return true
                }

                val snapshot = activeClient.taskSnapshot(taskId)
                when (snapshot.state) {
                    AgentTaskState.COMPLETED -> {
                        showStatus("Completed • restoring canonical Workspace history…")
                        return true
                    }
                    AgentTaskState.FAILED -> {
                        showStatus("Failed • restoring saved Workspace checkpoint…")
                        return true
                    }
                    AgentTaskState.CANCELLED -> {
                        showStatus("Cancelled")
                        return true
                    }
                    AgentTaskState.QUEUED,
                    AgentTaskState.RUNNING -> {
                        showStatus("Live • resumed from event #" + afterSequence)
                    }
                }
                delay(if (page.hasMore) 50L else 550L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                val terminalLookupFailure =
                    message.contains("Unknown task id", ignoreCase = true) ||
                        message.contains("task_not_found", ignoreCase = true) ||
                        message.contains("different connected application", ignoreCase = true)
                if (terminalLookupFailure) {
                    store.clearRunCursor(conversationId)
                    showStatus("Live run is no longer replayable • restoring saved Workspace history…")
                    runCatching { syncConversationFromWorkspace(conversationId) }
                    return true
                }
                showStatus("Reconnecting to Workspace… " + message)
                delay(1_500L)
            }
        }
    }

    private suspend fun recoverLegacyTaskBySnapshot(
        taskId: String,
        store: OmniConversationStore
    ): Boolean {
        val activeClient = client ?: return false
        while (true) {
            try {
                val snapshot = activeClient.taskSnapshot(taskId)
                when (snapshot.state) {
                    AgentTaskState.COMPLETED -> {
                        val answer = snapshot.finalAnswer?.takeIf { it.isNotBlank() }
                        if (answer != null) {
                            if (activeAssistantView == null) {
                                activeAssistantView = addAssistantBubble(answer)
                            } else {
                                activeAssistantView?.text = answer
                            }
                            streamedAssistant = StringBuilder(answer)

                            val assistantMarker = "\n\nOmni: "
                            val index = localTranscript.lastIndexOf(assistantMarker)
                            localTranscript = if (index >= 0) {
                                localTranscript.substring(0, index) + assistantMarker + answer
                            } else {
                                localTranscript + assistantMarker + answer
                            }
                            store.saveTranscript(projectRoot, conversationId, localTranscript)
                        }
                        finishLiveConsole(true)
                        showStatus(
                            "Completed • legacy Workspace snapshot restored. " +
                                "Update Workspace to v1.2+ for event-by-event replay."
                        )
                        return true
                    }
                    AgentTaskState.FAILED -> {
                        appendConsoleLine(
                            "[LEGACY SNAPSHOT ERROR] " +
                                (snapshot.error ?: "Agent task failed")
                        )
                        finishLiveConsole(false)
                        showStatus("Failed • restored from Workspace snapshot")
                        return true
                    }
                    AgentTaskState.CANCELLED -> {
                        finishLiveConsole(false)
                        showStatus("Cancelled")
                        return true
                    }
                    AgentTaskState.QUEUED,
                    AgentTaskState.RUNNING -> {
                        showStatus(
                            "Live task continues in Workspace • snapshot polling " +
                                "(update Workspace to v1.2+ for replay)"
                        )
                    }
                }
                delay(1_000L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                val stale =
                    message.contains("Unknown task id", ignoreCase = true) ||
                        message.contains("task_not_found", ignoreCase = true)
                if (stale) {
                    store.clearRunCursor(conversationId)
                    showStatus("Workspace no longer has this legacy live task.")
                    return true
                }
                showStatus("Waiting for Workspace snapshot… " + message)
                delay(1_500L)
            }
        }
    }

    private fun stopTask() {
        val taskId = currentTaskId ?: return
        lifecycleScope.launch {
            runCatching { client?.cancel(taskId) }
            runningJob?.cancel()
            currentTaskId = null
            appendConsoleLine("[CANCELLED] $taskId")
            finishLiveConsole(false)
            updateSendState(false)
            showStatus("Stopped")
        }
    }

    private fun updateSendState(running: Boolean) {
        sendButton.text = if (running) "■" else "➤"
        sendButton.background = circle(if (running) ERROR else PRIMARY)
    }

    private fun newConversation() {
        if (currentTaskId != null) {
            showStatus("Stop the current task before starting a new conversation.")
            return
        }
        val store = conversations ?: return
        conversationId = store.newConversation(projectRoot)
        localTranscript = ""
        localConsole = ""
        clearMessages()
        showStatus("New Omni conversation")
        refreshHistory()
    }

    private fun loadConversation(id: String) {
        if (currentTaskId != null) return
        val store = conversations ?: return
        conversationId = id
        store.select(projectRoot, id)

        // Fast offline/local cache first.
        localTranscript = store.transcript(id)
        localConsole = store.console(id)
        clearMessages()
        renderTranscript(localTranscript)
        if (localConsole.isNotBlank()) {
            messagesColumn.addView(createConsoleCard(localConsole, true))
        }
        emptyState.visibility =
            if (messagesColumn.childCount == 0) View.VISIBLE else View.GONE
        showStatus(store.title(id))
        scrollMessagesToBottom()

        // Workspace is authoritative. Replace the cache when the remote snapshot arrives.
        lifecycleScope.launch {
            runCatching { syncConversationFromWorkspace(id) }
                .onFailure {
                    if (conversationId == id) {
                        val message = it.message ?: it.javaClass.simpleName
                        val isUnsavedNewChat =
                            message.contains("conversation_not_found", ignoreCase = true) &&
                                store.transcript(id).isBlank()
                        if (isUnsavedNewChat) {
                            showStatus("New Omni conversation")
                        } else {
                            showStatus("Offline cache • Workspace history unavailable: " + message)
                        }
                    }
                }
            if (conversationId == id) resumeTaskIfNeeded(id)
            refreshHistory()
        }
    }

    private suspend fun syncConversationFromWorkspace(id: String) {
        val activeClient = client ?: return
        val store = conversations ?: return
        val snapshot = activeClient.getConversation(
            conversationId = id,
            query = AgentConversationReadQuery(limit = 100)
        )
        if (conversationId != id || !isAdded) return
        renderCanonicalSnapshot(snapshot)
        store.setTitle(projectRoot, id, snapshot.conversation.title)
    }

    private fun renderCanonicalSnapshot(snapshot: AgentConversationSnapshot) {
        if (conversationId != snapshot.conversation.clientConversationId) return
        val store = conversations ?: return

        clearMessages()
        localTranscript = ""
        localConsole = ""

        snapshot.messages.forEach { message ->
            when (message.role.uppercase()) {
                "USER" -> {
                    addUserBubble(message.content)
                    localTranscript += "\n\nYou: " + message.content
                }
                "ASSISTANT" -> {
                    message.consoleJson?.takeIf { it.isNotBlank() }?.let { raw ->
                        val pretty = formatPersistedConsole(raw)
                        if (pretty.isNotBlank()) {
                            messagesColumn.addView(createConsoleCard(pretty, true))
                            localConsole = (localConsole + pretty + "\n").takeLast(80_000)
                        }
                    }
                    activeAssistantView = addAssistantBubble(message.content)
                    streamedAssistant = StringBuilder(message.content)
                    localTranscript += "\n\nOmni: " + message.content
                }
                else -> {
                    addAssistantBubble("[" + message.role + "]\n" + message.content)
                }
            }
        }

        store.saveTranscript(projectRoot, conversationId, localTranscript)
        store.saveConsole(projectRoot, conversationId, localConsole)
        emptyState.visibility =
            if (messagesColumn.childCount == 0) View.VISIBLE else View.GONE

        val statusSuffix = snapshot.conversation.status
            ?.takeIf { it.isNotBlank() }
            ?.let { " • " + it }
            .orEmpty()
        showStatus(snapshot.conversation.title + statusSuffix + " • synced from Workspace")
        scrollMessagesToBottom()
    }

    private fun formatPersistedConsole(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val array = JSONArray(raw)
            buildString {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = item.optString("type")
                    val line = when (type) {
                        "thinking" ->
                            "[THINK] Reasoning iteration " + item.optInt("iteration")
                        "deep_thinking" ->
                            "[DEEP] " + item.optString("snippet")
                        "tool" ->
                            "[TOOL] " + item.optString("toolName") + ": " +
                                item.optString("params")
                        "result" -> {
                            val marker = if (item.optBoolean("isError")) "ERROR" else "RESULT"
                            val duration = item.optLong("durationMs", 0L)
                            "[" + marker + "] " + item.optString("toolName") + ": " +
                                item.optString("snippet") +
                                if (duration > 0L) " [" + duration + "ms]" else ""
                        }
                        "token" -> {
                            val budget = if (item.has("budget")) "/" + item.optInt("budget") else ""
                            "[TOK] " + item.optInt("totalTokens") + budget
                        }
                        "phase" ->
                            "[PHASE] " + item.optString("phase") +
                                item.optString("detail").takeIf { it.isNotBlank() }
                                    ?.let { " • " + it }.orEmpty()
                        "context_summary" ->
                            "[CONTEXT] " + item.optString("summary")
                        "error" ->
                            "[ERROR] " + item.optString("message")
                        "reply" -> "[REPLY] Final response"
                        else -> null
                    }
                    if (!line.isNullOrBlank()) appendLine(line)
                }
            }.trimEnd()
        }.getOrElse {
            "[CONSOLE] " + raw.take(4_000)
        }
    }

    private suspend fun resumeTaskIfNeeded(id: String) {
        val store = conversations ?: return
        val activeClient = client ?: return
        val cursor = store.runCursor(id) ?: return

        val snapshot = runCatching { activeClient.taskSnapshot(cursor.taskId) }
            .getOrElse {
                store.clearRunCursor(id)
                return
            }

        if (
            snapshot.state == AgentTaskState.COMPLETED ||
            snapshot.state == AgentTaskState.FAILED ||
            snapshot.state == AgentTaskState.CANCELLED
        ) {
            store.clearRunCursor(id)
            runCatching { syncConversationFromWorkspace(id) }
            return
        }

        if (conversationId != id) return
        currentTaskId = cursor.taskId
        // Keep the client-observed sequence. snapshot.lastSequence is the server's head and
        // advancing the cursor to it here would skip exactly the events replay is meant to recover.
        store.saveRunCursor(id, cursor.taskId, cursor.lastSequence)
        updateSendState(true)
        beginLiveConsole()
        showStatus("Live • reconnecting to running Workspace task…")

        runningJob = lifecycleScope.launch {
            val terminal = recoverExistingTask(cursor.taskId, store)
            if (terminal) {
                store.clearRunCursor(id)
                runCatching { syncConversationFromWorkspace(id) }
            }
            if (conversationId == id) {
                currentTaskId = null
                updateSendState(false)
                refreshHistory()
            }
        }
    }

    private fun clearMessages() {
        messagesColumn.removeAllViews()
        liveConsoleCard = null
        liveConsoleHeader = null
        liveConsoleBody = null
        activeAssistantView = null
        streamedAssistant = null
        liveConsoleEvents = 0
        emptyState.visibility = View.VISIBLE
    }

    private fun renderTranscript(raw: String) {
        if (raw.isBlank()) return

        val marker = Regex("(?m)(?:^|\\n\\n)(You|Omni): ")
        val matches = marker.findAll(raw).toList()
        if (matches.isEmpty()) {
            addAssistantBubble(raw.trim())
            return
        }

        matches.forEachIndexed { index, match ->
            val role = match.groupValues[1]
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else raw.length
            val body = raw.substring(start, end).trim()
            if (body.isBlank()) return@forEachIndexed
            if (role == "You") addUserBubble(body) else addAssistantBubble(body)
        }
    }

    private fun addUserBubble(text: String) {
        emptyState.visibility = View.GONE
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 4.dp(), 0, 8.dp())
        }

        row.addView(TextView(requireContext()).apply {
            text = "●"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(TERTIARY)
        }, LinearLayout.LayoutParams(44.dp(), 44.dp()).apply {
            marginEnd = 10.dp()
        })

        row.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            setTextColor(ON_SURFACE)
            setTextIsSelectable(true)
            background = rounded(PRIMARY_CONTAINER, 16f)
            setPadding(15.dp(), 11.dp(), 15.dp(), 11.dp())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        messagesColumn.addView(row)
        scrollMessagesToBottom()
    }

    private fun addAssistantBubble(text: String): TextView {
        emptyState.visibility = View.GONE
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 4.dp(), 0, 8.dp())
        }

        val body = TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            setTextColor(ON_SURFACE)
            setTextIsSelectable(true)
            background = rounded(SECONDARY_CONTAINER, 14f)
            setPadding(15.dp(), 11.dp(), 15.dp(), 11.dp())
        }
        row.addView(
            body,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10.dp()
            }
        )
        row.addView(TextView(requireContext()).apply {
            text = "🤖"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(PRIMARY)
        }, LinearLayout.LayoutParams(44.dp(), 44.dp()))

        messagesColumn.addView(row)
        scrollMessagesToBottom()
        return body
    }

    private fun beginLiveConsole() {
        localConsole = ""
        liveConsoleEvents = 0
        liveConsoleCard = createConsoleCard("", false)
        liveConsoleCard?.let { messagesColumn.addView(it) }
        scrollMessagesToBottom()
    }

    private fun createConsoleCard(initial: String, done: Boolean): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(TERMINAL, 14f)
            setPadding(0, 0, 0, 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dp()
                bottomMargin = 10.dp()
            }
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setBackgroundColor(TERMINAL_ROW)
        }
        val state = TextView(requireContext()).apply {
            text = if (done) "DONE" else "LIVE"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (done) SUCCESS else Color.rgb(88, 166, 255))
        }
        headerRow.addView(state)
        headerRow.addView(TextView(requireContext()).apply {
            text = "  Agent Console <>"
            textSize = 16f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(SUCCESS)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = TextView(requireContext()).apply {
            text = if (done) "" else "events 0"
            textSize = 10f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.rgb(139, 148, 158))
        }
        headerRow.addView(count)
        card.addView(headerRow)

        val body = TextView(requireContext()).apply {
            text = initial.takeLast(60_000)
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(TERMINAL_TEXT)
            setTextIsSelectable(true)
            setPadding(14.dp(), 12.dp(), 14.dp(), 2.dp())
        }
        card.addView(body)

        if (!done) {
            liveConsoleHeader = count
            liveConsoleBody = body
        }
        return card
    }

    private fun appendConsoleLine(line: String) {
        if (line.isBlank()) return
        liveConsoleEvents += 1
        localConsole = (localConsole + line.trimEnd() + "\n").takeLast(80_000)
        liveConsoleBody?.text = localConsole.takeLast(60_000)
        liveConsoleHeader?.text = "events $liveConsoleEvents"
        scrollMessagesToBottom()
    }

    private fun finishLiveConsole(success: Boolean) {
        liveConsoleHeader?.text = if (success) "DONE • events $liveConsoleEvents" else "STOP • events $liveConsoleEvents"
        liveConsoleHeader?.setTextColor(if (success) SUCCESS else ERROR)
    }

    private fun refreshHistory() {
        if (!::historyList.isInitialized) return
        val store = conversations ?: return
        val query = if (::historySearch.isInitialized) {
            historySearch.text?.toString()?.trim().orEmpty()
        } else {
            ""
        }

        // Render the local cache immediately, then replace it with canonical Workspace history.
        renderHistoryItems(store.list(projectRoot), query)

        historyRefreshJob?.cancel()
        historyRefreshJob = lifecycleScope.launch {
            delay(120L)
            val activeClient = client ?: return@launch
            val result = runCatching {
                activeClient.listConversations(
                    AgentConversationQuery(
                        limit = 100,
                        search = query.takeIf { it.isNotBlank() }
                    )
                )
            }.getOrNull() ?: return@launch

            val latestQuery = if (::historySearch.isInitialized) {
                historySearch.text?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            if (latestQuery != query || !isAdded) return@launch

            val remoteItems = result.conversations.map { remote ->
                OmniConversationStore.ConversationSummary(
                    id = remote.clientConversationId,
                    title = remote.title,
                    updatedAt = remote.lastUpdated,
                    status = remote.status
                )
            }

            // An empty unsent local chat has no Workspace row yet. Keep only that temporary row.
            val remoteIds = remoteItems.asSequence().map { it.id }.toHashSet()
            val pendingLocal = store.list(projectRoot).firstOrNull { item ->
                item.id == conversationId &&
                    item.id !in remoteIds &&
                    (store.transcript(item.id).isBlank() || currentTaskId != null)
            }
            val merged = if (pendingLocal != null) listOf(pendingLocal) + remoteItems else remoteItems
            renderHistoryItems(merged, query)
        }
    }

    private fun renderHistoryItems(
        all: List<OmniConversationStore.ConversationSummary>,
        query: String
    ) {
        if (!::historyList.isInitialized) return
        val visible = if (query.isBlank()) {
            all
        } else {
            all.filter { it.title.contains(query, ignoreCase = true) }
        }

        historyCount.text = "conversations " + all.size
        historyList.removeAllViews()

        var lastSection: String? = null
        visible.forEach { item ->
            val section = historySection(item.updatedAt)
            if (section != lastSection) {
                lastSection = section
                historyList.addView(TextView(requireContext()).apply {
                    text = section
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ON_SURFACE_MUTED)
                    setPadding(8.dp(), 14.dp(), 8.dp(), 5.dp())
                })
            }
            historyList.addView(historyRow(item))
        }

        if (visible.isEmpty()) {
            historyList.addView(TextView(requireContext()).apply {
                text = if (query.isBlank()) "No conversations yet" else "No conversations found"
                textSize = 14f
                setTextColor(ON_SURFACE_MUTED)
                gravity = Gravity.CENTER
                setPadding(16.dp(), 44.dp(), 16.dp(), 44.dp())
            })
        }
    }

    private fun historyRow(item: OmniConversationStore.ConversationSummary): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (item.id == conversationId) {
                rounded(Color.rgb(232, 216, 248), 14f)
            } else {
                rounded(Color.TRANSPARENT, 14f)
            }
            setPadding(8.dp(), 7.dp(), 4.dp(), 7.dp())
            setOnClickListener {
                loadConversation(item.id)
                hideHistory()
            }
        }

        row.addView(TextView(requireContext()).apply {
            text = "🔌"
            textSize = 16f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(239, 231, 244), 10f)
        }, LinearLayout.LayoutParams(38.dp(), 38.dp()).apply {
            marginEnd = 8.dp()
        })

        val textBlock = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        textBlock.addView(TextView(requireContext()).apply {
            text = item.title
            textSize = 14f
            maxLines = 1
            setTextColor(ON_SURFACE)
            if (item.id == conversationId) setTypeface(typeface, Typeface.BOLD)
        })
        textBlock.addView(TextView(requireContext()).apply {
            text = "AndroidIDE • " + relativeTime(item.updatedAt) +
                (item.status?.takeIf { it.isNotBlank() }?.let { " • " + it } ?: "")
            textSize = 10f
            setTextColor(ON_SURFACE_MUTED)
        })
        row.addView(textBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (item.id == conversationId) {
            row.addView(TextView(requireContext()).apply {
                text = "Current"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(PRIMARY, 8f)
                setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
            })
        }

        row.addView(TextView(requireContext()).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ON_SURFACE)
            setOnClickListener { anchor ->
                showConversationMenu(anchor, item)
            }
        }, LinearLayout.LayoutParams(38.dp(), 38.dp()))

        return row
    }

    private fun showConversationMenu(
        anchor: View,
        item: OmniConversationStore.ConversationSummary
    ) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Open in Workspace")
            menu.add("Refresh from Workspace")
            setOnMenuItemClickListener { selected ->
                when (selected.title.toString()) {
                    "Open in Workspace" -> openWorkspace(false)
                    "Refresh from Workspace" -> {
                        lifecycleScope.launch {
                            runCatching { syncConversationFromWorkspace(item.id) }
                            refreshHistory()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun renameConversation(item: OmniConversationStore.ConversationSummary) {
        val input = EditText(requireContext()).apply {
            setText(item.title)
            setSelection(text.length)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename conversation")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                conversations?.setTitle(
                    projectRoot,
                    item.id,
                    input.text?.toString().orEmpty()
                )
                refreshHistory()
            }
            .show()
    }

    private fun deleteConversation(item: OmniConversationStore.ConversationSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete conversation?")
            .setMessage(item.title)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                conversations?.remove(projectRoot, item.id)
                if (item.id == conversationId) {
                    conversationId = conversations?.current(projectRoot).orEmpty()
                    if (conversationId.isBlank()) {
                        conversationId = conversations?.newConversation(projectRoot).orEmpty()
                    }
                    loadConversation(conversationId)
                }
                refreshHistory()
            }
            .show()
    }

    private fun showHistory() {
        refreshHistory()
        historyScrim.visibility = View.VISIBLE
        historyPanel.visibility = View.VISIBLE
        historyPanel.alpha = 0f
        historyPanel.translationX = 48.dp().toFloat()
        historyPanel.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180L)
            .start()
    }

    private fun hideHistory() {
        if (!::historyPanel.isInitialized || historyPanel.visibility != View.VISIBLE) return
        historyPanel.animate()
            .alpha(0f)
            .translationX(48.dp().toFloat())
            .setDuration(150L)
            .withEndAction {
                historyPanel.visibility = View.GONE
                historyScrim.visibility = View.GONE
                historyPanel.alpha = 1f
                historyPanel.translationX = 0f
            }
            .start()
    }

    private fun showWorkspaceMenu(anchor: View, activity: EditorHandlerActivity) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Open Workspace app")
            menu.add("Open side-by-side")
            menu.add("Refresh IDE context")
            menu.add("Close Omni panel")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Open Workspace app" -> openWorkspace(false)
                    "Open side-by-side" -> openWorkspace(true)
                    "Refresh IDE context" -> showStatus("IDE context refreshes automatically on every send.")
                    "Close Omni panel" -> activity.closeOmniWorkspace()
                }
                true
            }
            show()
        }
    }

    private fun showQuickActions(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Explain current file")
            menu.add("Fix current build")
            menu.add("Inspect diagnostics & logs")
            menu.add("Inspect Git status & diff")
            menu.add("Open Workspace side-by-side")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Explain current file" -> setPrompt(
                        "Explain the current file in depth: responsibility, important flows, " +
                            "dependencies, risks, and how it fits into the whole project."
                    )
                    "Fix current build" -> setPrompt(
                        "Inspect the latest AndroidIDE build output and diagnostics, fix the root " +
                            "cause, then build and test through native IDE capabilities until verified."
                    )
                    "Inspect diagnostics & logs" -> setPrompt(
                        "Inspect AndroidIDE project diagnostics, IDE logs, app logs and recent build " +
                            "output. Explain root causes and fix actionable project issues."
                    )
                    "Inspect Git status & diff" -> setPrompt(
                        "Inspect the current native Git status and diff. Summarize changes, flag risks, " +
                            "and do not stage, commit, merge, pull or push without my approval."
                    )
                    "Open Workspace side-by-side" -> openWorkspace(true)
                }
                true
            }
            show()
        }
    }

    private fun openWorkspace(adjacent: Boolean) {
        val context = requireContext()
        val launch = context.packageManager.getLaunchIntentForPackage(WORKSPACE_PACKAGE)
        if (launch == null) {
            showStatus("Omni Dev Workspace is not installed.")
            return
        }
        val intent = Intent(launch).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (adjacent) {
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
        }
        runCatching { startActivity(intent) }
            .onFailure { showStatus("Could not open Workspace: " + (it.message ?: "unknown")) }
    }

    private fun setPrompt(value: String) {
        prompt.setText(value)
        prompt.setSelection(prompt.text?.length ?: 0)
        prompt.requestFocus()
    }

    private fun showStatus(value: String) {
        status.text = value
        status.visibility = View.VISIBLE
    }

    private fun scrollMessagesToBottom() {
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun iconButton(
        glyph: String,
        description: String,
        onClick: (View) -> Unit
    ): TextView =
        TextView(requireContext()).apply {
            text = glyph
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(ON_SURFACE)
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(50.dp(), 50.dp())
        }

    private fun historySection(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - timestamp).coerceAtLeast(0L)
        val day = 86_400_000L
        return when {
            diff < day -> "Today"
            diff < 2 * day -> "Yesterday"
            diff < 7 * day -> "Previous 7 days"
            else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun relativeTime(timestamp: Long): String {
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "Just now"
            diff < hour -> "${diff / minute}m ago"
            diff < day -> "${diff / hour}h ago"
            diff < 2 * day -> "Yesterday"
            diff < 7 * day -> "${diff / day}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun topic(value: String): String =
        value.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(72)
            ?: "AndroidIDE conversation"

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun roundedWithStroke(
        color: Int,
        radiusDp: Float,
        strokeColor: Int,
        strokeDp: Int
    ): GradientDrawable =
        rounded(color, radiusDp).apply {
            setStroke(strokeDp.dp(), strokeColor)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()
}
