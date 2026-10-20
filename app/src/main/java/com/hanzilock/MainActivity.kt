package com.hanzilock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.hanzilock.lock.LockActivity
import com.hanzilock.platform.PickedFile
import com.hanzilock.ui.LingoLockContent
import com.hanzilock.ui.main.AppRoot
import com.hanzilock.ui.readPickedFile
import kotlinx.coroutines.launch

/**
 * Word sets, dictionary, stats and settings. Hidden behind the practice screen while locked.
 * Also receives word lists shared to / opened with Lingo Lock and offers to import them, and web
 * pages or Chinese text shared to it, which open as stories.
 */
class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<PickedFile?>(null)
    private var incomingText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            receive(fileFrom(intent))
            incomingText = textFrom(intent)
        }
        setContent {
            LingoLockContent(HanziLockApp.get(this)) {
                AppRoot(
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                    incomingText = incomingText,
                    onIncomingTextHandled = { incomingText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(fileFrom(intent))
        textFrom(intent)?.let { incomingText = it }
    }

    override fun onResume() {
        super.onResume()
        val app = HanziLockApp.get(this)
        if (app.lockEngine.isLockDue()) LockActivity.launch(this)
        app.updater.checkIfDue()
    }

    /** A word list shared to / opened with Lingo Lock: read it, then offer to import it. */
    private fun receive(uri: Uri?) {
        if (uri != null) lifecycleScope.launch { readPickedFile(this@MainActivity, uri)?.let { incoming = it } }
    }

    /** A link or text shared without a file (e.g. a web page from Chrome): offered as a story. */
    private fun textFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && fileFrom(intent) == null) intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } else null

    private fun fileFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
}
