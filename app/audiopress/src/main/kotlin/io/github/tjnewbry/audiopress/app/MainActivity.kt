package io.github.tjnewbry.audiopress.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import io.github.tjnewbry.audiopress.app.ui.AppContent
import io.github.tjnewbry.audiopress.app.ui.AudioPressTheme

/** The app's one activity. The screens are in [AppContent]; the state they show is in [LibraryViewModel]. */
class MainActivity : ComponentActivity() {
    private val library: LibraryViewModel by viewModels()

    // A bake keeps running in the background with a notification, which Android (13 and newer, so every phone the app runs on)
    // asks the user to allow. The bake runs either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun askForNotifications() {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        // Leaving the app or turning the screen off pauses playback and saves the position. A rotation also stops the
        // activity, but the reading carries on through it.
        if (!isChangingConfigurations) library.pausePlayback()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioPressTheme {
                AppContent(
                    library,
                    exports = (application as AudioPressApp).exportCenter,
                    onVoiceScreenShown = ::askForNotifications,
                    onRunInBackground = { moveTaskToBack(true) },
                )
            }
        }
    }
}
