package com.hanzilock.desktop

import com.hanzilock.AppCore
import com.hanzilock.stories.StoryBody
import com.hanzilock.stories.StoryChunk
import com.hanzilock.stories.StoryDraft
import com.hanzilock.ui.stories.StoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reading a story: pointing at words, clicking them for their sentence, and finding a word in the story. */
class StoryReadingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun reader(): StoryViewModel = runBlocking(Dispatchers.Swing) {
        val core = AppCore(TestPlatform(tmp.newFolder("device")))
        val id = core.stories.add(
            StoryDraft(
                "菜系", "Cuisines", "Pasted text", null, null,
                StoryBody(
                    listOf(
                        StoryChunk("中国 第 九 大 菜系", "China's ninth cuisine", p = true),
                        StoryChunk("中国 菜 很 多 。", "Chinese food is varied.", p = true),
                        StoryChunk("我 喜欢 这个 菜系 。", "I like this cuisine."),
                    ),
                ),
            ),
        )
        StoryViewModel(core).also { vm ->
            vm.start(id)
            withTimeout(5_000) { while (vm.ui.value.story == null) delay(10) }
        }
    }

    /** Where [word] is on page 0: its block and its index among the block's words. */
    private fun StoryViewModel.find(word: String, nth: Int = 0): Pair<Int, Int> =
        ui.value.pages[0].blocks.withIndex().flatMap { (b, block) -> block.words.withIndex().filter { it.value.word == word }.map { b to it.index } }[nth]

    @Test
    fun clickingAWordPicksItsSentenceAndClickingAgainLetsGo() {
        val vm = reader()
        val (block, word) = vm.find("菜系", nth = 1)
        vm.select(0, block, word)
        assertEquals(2, vm.ui.value.selectedSentence)
        assertEquals("菜系", vm.ui.value.shown?.span?.word)

        vm.select(0, block, word)
        assertNull(vm.ui.value.selected)
        assertEquals(-1, vm.ui.value.selectedSentence)
        // The meaning stays up until you point at another word.
        assertEquals("菜系", vm.ui.value.shown?.span?.word)
    }

    @Test
    fun pointingFromOneParagraphToTheNextKeepsTheNewWord() {
        val vm = reader()
        val (first, w1) = vm.find("菜系")
        val (second, w2) = vm.find("菜")
        vm.hover(0, first, w1)
        // The next paragraph may hear of the pointer before this one hears it left.
        vm.hover(0, second, w2)
        vm.hover(0, first, -1)
        assertEquals("菜", vm.ui.value.hovered?.span?.word)
        vm.hover(0, second, -1)
        assertNull(vm.ui.value.hovered)
        assertEquals("菜", vm.ui.value.shown?.span?.word)
    }

    @Test
    fun aSearchedWordIsMarkedWithItsPages() {
        val vm = reader()
        vm.search("菜系")
        assertEquals("菜系", vm.ui.value.marked)
        assertEquals(listOf(0), vm.ui.value.markedPages)
        vm.closeSearch()
        assertNull(vm.ui.value.marked)
    }
}
