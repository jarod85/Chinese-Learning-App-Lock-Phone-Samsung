package com.hanzilock.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.hanzilock.quiz.SessionKind
import com.hanzilock.ui.lock.LockScreen
import com.hanzilock.ui.quiz.QuizViewModel
import com.hanzilock.ui.theme.HanziTheme

/** Full-screen practice gate shown instead of blocked apps while a session is due. */
class LockActivity : ComponentActivity() {
    companion object {
        /** True while the practice screen is in front (read by the accessibility service). */
        @Volatile
        var visible = false
            private set

        fun launch(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
        }
    }

    private val vm: QuizViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Back does nothing here: finish the words (or use the master PIN).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        vm.start(SessionKind.LOCK)
        setContent {
            HanziTheme {
                LockScreen(vm = vm, onClose = { finishAndRemoveTask() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        vm.onHostResume()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }
}
