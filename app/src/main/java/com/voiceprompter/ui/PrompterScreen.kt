package com.voiceprompter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceprompter.match.MatchMode
import com.voiceprompter.prompter.PrompterStatus
import com.voiceprompter.prompter.PrompterViewModel

@Composable
fun PrompterScreen(
    viewModel: PrompterViewModel,
    onRequestMicPermission: () -> Unit,
    hasMicPermission: Boolean,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    KeepScreenOnWhile(state.isRunning)

    LaunchedEffect(state.currentIndex) {
        if (state.phrases.isNotEmpty()) {
            listState.animateScrollToItem(state.currentIndex.coerceAtMost(state.phrases.lastIndex))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        StatusBanner(state.status, state.error, state.modelProgress)

        LinearProgressIndicator(
            progress = { state.phraseProgress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.phrases) { index, phrase ->
                val isCurrent = index == state.currentIndex
                val isPast = index < state.currentIndex

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = phrase.text,
                        fontSize = if (isCurrent) 30.sp else 22.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = if (isCurrent) 38.sp else 30.sp,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            isPast -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        if (state.lastHeard.isNotBlank()) {
            Text(
                "heard: ${state.lastHeard}  (${"%.2f".format(state.lastScore)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MatchMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.matchMode == mode,
                    onClick = { viewModel.setMatchMode(mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // While this is on, the volume keys scroll the script — including with
        // the screen off — and stop changing the volume. Set your earpiece
        // level before starting, or turn this off to adjust it.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.volumeKeysScroll,
                onCheckedChange = viewModel::setVolumeKeysScroll,
            )
            Text(
                if (state.volumeKeysScroll) {
                    "Volume keys scroll the script"
                } else {
                    "Volume keys change volume"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::previous) { Text("Back") }

            when {
                !hasMicPermission -> Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier.weight(1f),
                ) { Text("Allow microphone") }

                state.status == PrompterStatus.PAUSED -> Button(
                    onClick = viewModel::resume,
                    modifier = Modifier.weight(1f),
                ) { Text("Resume") }

                state.isRunning -> Button(
                    onClick = viewModel::pause,
                    modifier = Modifier.weight(1f),
                ) { Text("Pause") }

                else -> Button(
                    onClick = viewModel::start,
                    modifier = Modifier.weight(1f),
                ) { Text("Start") }
            }

            OutlinedButton(onClick = viewModel::next) { Text("Skip") }
        }
    }
}

/**
 * Stops the display sleeping while a script is running.
 *
 * This is the opposite concern to the foreground service: the service keeps
 * the *session* alive when the screen goes off, this keeps the *screen* alive
 * so a speaker reading from the phone is not left staring at a dark panel
 * halfway through a sermon. The flag is cleared on dispose, so it never leaks
 * past this screen.
 */
@Composable
private fun KeepScreenOnWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        val window = (view.context as? Activity)?.window
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun StatusBanner(status: PrompterStatus, error: String?, modelProgress: Float) {
    val label = when (status) {
        PrompterStatus.IDLE -> "Ready"
        PrompterStatus.PREPARING ->
            if (modelProgress > 0f) {
                "Downloading speech model… ${(modelProgress * 100).toInt()}%"
            } else {
                "Preparing…"
            }
        PrompterStatus.SPEAKING -> "Reading aloud — microphone off"
        PrompterStatus.LISTENING -> "Listening — your turn"
        PrompterStatus.AD_LIB -> "Off script — holding position"
        PrompterStatus.PAUSED -> "Paused"
        PrompterStatus.FINISHED -> "End of script"
        PrompterStatus.ERROR -> error ?: "Something went wrong"
    }

    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (status == PrompterStatus.ERROR) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
