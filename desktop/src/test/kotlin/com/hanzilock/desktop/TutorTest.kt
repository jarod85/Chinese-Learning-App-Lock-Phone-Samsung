package com.hanzilock.desktop

import com.hanzilock.AppCore
import com.hanzilock.ui.tutor.TutorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Chinese tutor's conversation, without Claude (the test device is offline and has no API key). */
class TutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aQuestionClaudeCantAnswerGoesBackIntoTheInput() = runBlocking(Dispatchers.Swing) {
        val vm = TutorViewModel(AppCore(TestPlatform(tmp.newFolder("device"))))
        vm.open()
        vm.onInput("  What does 主要 mean?  ")
        vm.send()
        withTimeout(5_000) { while (vm.ui.value.answering) delay(10) }

        val ui = vm.ui.value
        assertTrue(ui.open)
        assertTrue(ui.messages.isEmpty())
        assertEquals("What does 主要 mean?", ui.input)
        assertEquals(TutorViewModel.NO_CLAUDE, ui.error)
    }

    @Test
    fun aNewConversationKeepsThePanelOpen() = runBlocking(Dispatchers.Swing) {
        val vm = TutorViewModel(AppCore(TestPlatform(tmp.newFolder("device"))))
        vm.open()
        vm.onInput("你好")
        vm.newConversation()
        assertTrue(vm.ui.value.open)
        assertEquals("", vm.ui.value.input)
        vm.toggle()
        assertFalse(vm.ui.value.open)
    }
}
