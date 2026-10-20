package com.hanzilock.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hanzilock.ui.learn.LearnContent

/** Learning mode for one word, from its word page. */
@Composable
fun LearnScreen(nav: Navigator, id: Long) {
    ScreenScaffold(title = "Learn with Claude", onBack = nav::back) { padding ->
        LearnContent(id, onClose = nav::back, modifier = Modifier.padding(padding))
    }
}
