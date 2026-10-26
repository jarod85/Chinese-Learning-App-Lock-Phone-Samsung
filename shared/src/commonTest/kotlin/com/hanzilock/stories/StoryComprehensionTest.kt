package com.hanzilock.stories

import com.hanzilock.data.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryComprehensionTest {
    @Test
    fun oldStoryQuestionsRemainLiteralAndOtherLevelsSurviveSerialization() {
        val old = AppJson.decodeFromString<StoryBody>(
            """{"chunks":[{"text":"小猫 回家 了 。","en":"The kitten went home.","p":true}],"questions":[{"question":"谁回家了？","pinyin":"Shéi huí jiā le?","english":"Who went home?"}]}""",
        )
        assertEquals("谁回家了？", old.questionsFor(ComprehensionLevel.LITERAL).single().question)
        assertTrue(old.questionsFor(ComprehensionLevel.INFERENTIAL).isEmpty())
        assertTrue(old.questionsFor(ComprehensionLevel.EVALUATIVE).isEmpty())

        val inferential = listOf(StoryQuestion("小猫回家以后，可能会做什么？为什么？"))
        val evaluative = listOf(StoryQuestion("你觉得小猫应该回家吗？请说明理由。"))
        val updated = old.withQuestions(ComprehensionLevel.INFERENTIAL, inferential)
            .withQuestions(ComprehensionLevel.EVALUATIVE, evaluative)
        val restored = AppJson.decodeFromString<StoryBody>(AppJson.encodeToString(StoryBody.serializer(), updated))

        assertEquals(old.chunks, restored.chunks)
        assertEquals(old.questions, restored.questionsFor(ComprehensionLevel.LITERAL))
        assertEquals(inferential, restored.questionsFor(ComprehensionLevel.INFERENTIAL))
        assertEquals(evaluative, restored.questionsFor(ComprehensionLevel.EVALUATIVE))

        val newLiteral = listOf(StoryQuestion("小猫去了哪里？"))
        val changed = restored.withQuestions(ComprehensionLevel.LITERAL, newLiteral)
        assertEquals(newLiteral, changed.questionsFor(ComprehensionLevel.LITERAL))
        assertEquals(restored.questionSets, changed.questionSets)
    }
}
