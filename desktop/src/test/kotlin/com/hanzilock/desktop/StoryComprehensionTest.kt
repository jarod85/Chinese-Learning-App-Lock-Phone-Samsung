package com.hanzilock.desktop

import androidx.lifecycle.ViewModelStore
import com.hanzilock.AppCore
import com.hanzilock.core.Settings
import com.hanzilock.data.AppJson
import com.hanzilock.stories.ComprehensionLevel
import com.hanzilock.stories.StoryBody
import com.hanzilock.stories.StoryChunk
import com.hanzilock.stories.StoryDraft
import com.hanzilock.stories.StoryQuestion
import com.hanzilock.ui.stories.StoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoryComprehensionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val models = ViewModelStore()
    private val literal = listOf(StoryQuestion("谁回家了？"))
    private val inferential = listOf(StoryQuestion("小猫为什么想回家？请用故事里的线索说明。"), StoryQuestion("接下来可能发生什么？为什么？"))
    private val evaluative = listOf(StoryQuestion("你觉得小猫应该回家吗？请说明理由。"))
    private val body = StoryBody(listOf(StoryChunk("小猫 回家 了 。")), literal)
        .withQuestions(ComprehensionLevel.INFERENTIAL, inferential)
        .withQuestions(ComprehensionLevel.EVALUATIVE, evaluative)

    @After fun clearModels() = runBlocking(Dispatchers.Swing) { models.clear() }

    private suspend fun reader(core: AppCore, saved: StoryBody = body): StoryViewModel {
        val id = core.stories.add(StoryDraft("小猫", "The kitten", "Pasted text", null, "HSK 2", saved))
        return StoryViewModel(core).also { vm ->
            models.put("story-$id", vm)
            vm.start(id)
            withTimeout(5_000) { vm.ui.first { it.story != null } }
        }
    }

    @Test
    fun switchingCachedLevelsKeepsTheirAnswersAndTheRetelling() = runBlocking(Dispatchers.Swing) {
        val core = AppCore(TestPlatform(tmp.newFolder("device")))
        val vm = reader(core)
        assertEquals(ComprehensionLevel.INFERENTIAL, vm.ui.value.comprehensionLevel)
        assertEquals(inferential, vm.ui.value.questions)
        vm.onAnswer(0, "小猫可能饿了。")
        vm.onAnswer(1, "它可能会吃饭。")
        vm.onAnswer(StoryViewModel.EXPLANATION, "这是一个小猫回家的故事。")

        vm.setComprehensionLevel(ComprehensionLevel.EVALUATIVE)
        assertEquals(evaluative, vm.ui.value.questions)
        assertEquals(listOf(""), vm.ui.value.answers)
        vm.onAnswer(0, "我觉得它应该回家，因为家里有人等它。")

        vm.setComprehensionLevel(ComprehensionLevel.LITERAL)
        assertEquals(literal, vm.ui.value.questions)
        assertEquals(listOf(""), vm.ui.value.answers)
        vm.onAnswer(0, "小猫回家了。")

        vm.setComprehensionLevel(ComprehensionLevel.INFERENTIAL)
        assertEquals(listOf("小猫可能饿了。", "它可能会吃饭。"), vm.ui.value.answers)
        assertEquals(listOf(null, null), vm.ui.value.reviews)
        vm.setComprehensionLevel(ComprehensionLevel.EVALUATIVE)
        assertEquals(listOf("我觉得它应该回家，因为家里有人等它。"), vm.ui.value.answers)
        assertEquals("这是一个小猫回家的故事。", vm.ui.value.explanation)
        assertNull(vm.ui.value.questionsError)
        assertFalse(vm.ui.value.questionsLoading)
        assertEquals(ComprehensionLevel.EVALUATIVE, core.settings.storyComprehensionLevel)
        val reopened = StoryViewModel(core).also { models.put("reopened", it) }
        assertEquals(ComprehensionLevel.EVALUATIVE, reopened.ui.value.comprehensionLevel)
    }

    @Test
    fun missingLevelShowsItsOwnErrorAndLegacyQuestionsRemainUsableOffline() = runBlocking(Dispatchers.Swing) {
        val core = AppCore(TestPlatform(tmp.newFolder("device")))
        val vm = reader(core, StoryBody(body.chunks, literal))
        vm.loadQuestions()
        assertNotNull(vm.ui.value.questionsError)
        assertFalse(vm.ui.value.questionsLoading)
        assertEquals(emptyList<StoryQuestion>(), vm.ui.value.questions)

        vm.setComprehensionLevel(ComprehensionLevel.LITERAL)
        assertNull(vm.ui.value.questionsError)
        assertEquals(literal, vm.ui.value.questions)
        vm.onAnswer(0, "小猫。")
        vm.setComprehensionLevel(ComprehensionLevel.EVALUATIVE)
        assertNotNull(vm.ui.value.questionsError)
        assertEquals(emptyList<String>(), vm.ui.value.answers)

        vm.setComprehensionLevel(ComprehensionLevel.LITERAL)
        assertEquals(listOf("小猫。"), vm.ui.value.answers)
        assertNull(vm.ui.value.questionsError)
    }

    @Test
    fun preferencePersistsAndOldSettingsSnapshotsUseTheNewDefault() {
        val dir = tmp.newFolder("device")
        val core = AppCore(TestPlatform(dir))
        core.settings.storyComprehensionLevel = ComprehensionLevel.LITERAL
        assertEquals(ComprehensionLevel.LITERAL, AppCore(TestPlatform(dir)).settings.storyComprehensionLevel)

        val json = AppJson.encodeToString(Settings.Shared.serializer(), core.settings.shared())
        val legacyJson = JsonObject(AppJson.parseToJsonElement(json).jsonObject - "storyComprehensionLevel").toString()
        val oldSettings = AppJson.decodeFromString<Settings.Shared>(legacyJson)
        core.settings.applyShared(oldSettings)
        assertEquals(ComprehensionLevel.INFERENTIAL, core.settings.storyComprehensionLevel)
        // Unknown preferences also fall back safely instead of preventing the reader from opening.
        core.settings.applyShared(oldSettings.copy(storyComprehensionLevel = "UNKNOWN"))
        assertEquals(ComprehensionLevel.INFERENTIAL, core.settings.storyComprehensionLevel)
    }
}
