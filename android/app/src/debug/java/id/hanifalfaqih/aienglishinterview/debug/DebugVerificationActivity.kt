package id.hanifalfaqih.aienglishinterview.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.MainActivity
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import id.hanifalfaqih.aienglishinterview.navigation.Routes
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY verification harness (ships in debug builds only via
 * `src/debug`; never in release). Bypasses microphone/ASR by submitting
 * scripted English answers through the REAL text-turn contract
 * ([ConversationRepository.sendTurn] with unique clientTurnIds) so the
 * backend naturally interviews, wraps up, and closes.
 *
 * Launch: `adb shell am start -n
 * id.hanifalfaqih.aienglishinterview/.debug.DebugVerificationActivity`
 *
 * Flow: Run to closed → Open Completion → normal completion/feedback/
 * paywall verification continues in the production UI. NOTHING here fakes
 * state, feedback, entitlement, or purchases.
 */
class DebugVerificationActivity : ComponentActivity() {

    companion object {
        /** Deterministic demo answers; stops early if the backend closes. */
        val SCRIPT = listOf(
            "I am a computer science student who built a campus store app in Kotlin.",
            "My role was Android developer and I owned the cart feature.",
            "The hardest part was the flaky stock API, which I fixed with retries.",
            "I learned to design offline-first data sync.",
            "We demoed at the campus fair and got good feedback.",
            "I would improve the checkout flow next time.",
            "Testing taught me to write unit tests early.",
            "Overall it was a great experience and I want to do more mobile work.",
            // Fallbacks: the backend closes on question count, so keep
            // answering (cycling) until closing=true rather than stopping
            // early if some replies contain no question.
            "I also improved app startup time by lazy-loading screens.",
            "Code reviews with friends helped me catch bugs earlier.",
            "I documented the API so new contributors could onboard faster.",
            "I would love to intern and keep building mobile apps.",
        )

        /** Answers cycle if the backend needs more turns to wrap up. */
        private fun answerAt(index: Int): String = SCRIPT[index % SCRIPT.size]

        const val EXTRA_START_DESTINATION = "debug_start_destination"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIEnglishInterviewTheme {
                val scope = rememberCoroutineScope()
                val log = remember { mutableStateListOf<String>() }
                var running by remember { mutableStateOf(false) }
                var conversationId by remember { mutableStateOf<String?>(null) }
                var closed by remember { mutableStateOf(false) }

                fun append(line: String) {
                    log.add(line)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "DEBUG verification (not part of the product)",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Button(
                        onClick = {
                            running = true
                            closed = false
                            scope.launch {
                                runToClosed(
                                    append = ::append,
                                    onConversation = { conversationId = it },
                                    onClosed = { closed = true },
                                )
                                running = false
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Run interview to closed")
                    }
                    if (running) CircularProgressIndicator()
                    OutlinedButton(
                        onClick = { openStage(Routes.completion(conversationId ?: return@OutlinedButton)) },
                        enabled = !running && conversationId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Completion in app")
                    }
                    OutlinedButton(
                        onClick = { openStage(Routes.feedback(conversationId ?: return@OutlinedButton)) },
                        enabled = !running && closed,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Feedback in app")
                    }
                    log.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private fun openStage(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(
                EXTRA_START_DESTINATION,
                route,
            ),
        )
    }

    private suspend fun runToClosed(
        append: (String) -> Unit,
        onConversation: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        val experiences = ExperienceRepository()
        val conversations = ConversationRepository()
        append("Creating experience profile…")
        val profileId = when (
            val profile = experiences.createExperienceProfile(
                listOf(
                    ExperienceItem(
                        title = "Campus Store",
                        organization = "Tech Club",
                        role = "Android developer",
                        description = "Built a campus store app in Kotlin.",
                        skills = listOf("Kotlin"),
                    ),
                ),
            )
        ) {
            is ApiResult.Error -> {
                append("Profile failed: ${profile.message}")
                return
            }
            is ApiResult.Success -> profile.value
        }
        append("Profile $profileId")
        val conversationId = when (
            val conv = conversations.createConversation(profileId)
        ) {
            is ApiResult.Error -> {
                append("Conversation failed: ${conv.message}")
                return
            }
            is ApiResult.Success -> conv.value.id
        }
        append("Conversation $conversationId")
        onConversation(conversationId)
        var index = 0
        while (index < 16) {
            val answer = answerAt(index)
            val turn = when (
                val result = conversations.sendTurn(
                    conversationId,
                    UUID.randomUUID().toString(),
                    answer,
                )
            ) {
                is ApiResult.Error -> {
                    append("Turn ${index + 1} failed: ${result.message}")
                    return
                }
                is ApiResult.Success -> result.value
            }
            append(
                "Turn ${index + 1}: status=${turn.status} closing=${turn.closing} " +
                    "assistant=${turn.assistantMessage.take(60)}…",
            )
            if (turn.closing || turn.isClosed) {
                append("CLOSED after ${index + 1} turns.")
                onClosed()
                return
            }
            index++
        }
        append("Script exhausted without closure; check backend state.")
    }
}
