package com.hanzilock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hanzilock.lock.LockActivity
import com.hanzilock.ui.main.AppRoot
import com.hanzilock.ui.theme.HanziTheme

/** Word list, dictionary, stats and settings. Hidden behind the practice screen while locked. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HanziTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (HanziLockApp.get(this).lockEngine.isLockDue()) LockActivity.launch(this)
    }
}
