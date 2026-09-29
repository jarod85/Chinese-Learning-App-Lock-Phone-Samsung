package com.hanzilock.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.quiz.SessionKind
import com.hanzilock.ui.quiz.QuizContent
import com.hanzilock.ui.quiz.QuizViewModel

/** Voluntary practice from the app. Leaving keeps your place; "End" closes the session. */
@Composable
fun PracticeScreen(nav: Navigator) {
    val vm: QuizViewModel = viewModel(key = "practice")
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.start(SessionKind.PRACTICE) }
    LaunchedEffect(ui.phase) {
        if (vm.ui.value.phase == QuizViewModel.Phase.CLOSED) nav.back()
    }
    ScreenScaffold(
        title = "Practice",
        onBack = nav::back,
        actions = {
            if (ui.phase == QuizViewModel.Phase.QUIZ && ui.kind == SessionKind.PRACTICE) {
                TextButton(onClick = vm::leavePractice) { Text("End") }
            }
        },
    ) { padding ->
        QuizContent(vm, onDone = nav::back, modifier = Modifier.padding(padding))
    }
}
