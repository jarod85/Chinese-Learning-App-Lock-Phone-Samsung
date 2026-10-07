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
import com.hanzilock.lock.LockActivity
import com.hanzilock.ui.main.AppRoot
import com.hanzilock.ui.theme.HanziTheme

/**
 * Word sets, dictionary, stats and settings. Hidden behind the practice screen while locked.
 * Also receives word lists shared to / opened with Lingo Lock and offers to import them.
 */
class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming = fileFrom(intent)
        setContent {
            HanziTheme {
                AppRoot(incoming = incoming, onIncomingHandled = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        fileFrom(intent)?.let { incoming = it }
    }

    override fun onResume() {
        super.onResume()
        val app = HanziLockApp.get(this)
        if (app.lockEngine.isLockDue()) LockActivity.launch(this)
        app.updater.checkIfDue()
    }

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
