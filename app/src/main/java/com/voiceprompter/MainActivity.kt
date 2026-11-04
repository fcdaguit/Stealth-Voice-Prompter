package com.voiceprompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.voiceprompter.prompter.PrompterViewModel
import com.voiceprompter.ui.PrompterScreen
import com.voiceprompter.ui.ScriptEditorScreen
import com.voiceprompter.ui.ScriptListScreen
import com.voiceprompter.ui.ScriptsViewModel
import com.voiceprompter.ui.theme.VoicePrompterTheme

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)
    private var prompterViewModel: PrompterViewModel? = null

    // RECORD_AUDIO is required. POST_NOTIFICATIONS is requested alongside it
    // because with the screen off the session notification is the only
    // control surface the user has; denying it does not block the session.
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasMicPermission = result[Manifest.permission.RECORD_AUDIO]
            ?: hasMicPermission
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            VoicePrompterTheme {
                val navController = rememberNavController()
                val scriptsViewModel: ScriptsViewModel = viewModel()
                val prompter: PrompterViewModel = viewModel()
                prompterViewModel = prompter

                NavHost(navController, startDestination = "scripts") {

                    composable("scripts") {
                        ScriptListScreen(
                            viewModel = scriptsViewModel,
                            onEdit = { id -> navController.navigate("editor/$id") },
                            onRun = { id -> navController.navigate("prompter/$id") },
                        )
                    }

                    composable(
                        "editor/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        ScriptEditorScreen(
                            viewModel = scriptsViewModel,
                            scriptId = entry.arguments?.getLong("id") ?: 0L,
                            onDone = { navController.popBackStack() },
                        )
                    }

                    composable(
                        "prompter/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        androidx.compose.runtime.LaunchedEffect(id) {
                            scriptsViewModel.byId(id)?.let {
                                prompter.loadScript(it.body, it.title)
                            }
                        }
                        PrompterScreen(
                            viewModel = prompter,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                requestPermissions.launch(requiredPermissions())
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Volume keys scroll the script, per the spec. This only works while the
     * app is in the foreground — Android does not hand volume keys to a
     * backgrounded app, so a Bluetooth clicker that sends media or page keys
     * is the route for hands-free control from a distance.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = prompterViewModel ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                vm.previous(); true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                vm.next(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
