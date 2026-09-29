package com.hanzilock.quiz

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.hanzilock.data.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Optional AI grading with Claude: judges free-form example sentences (which can't be checked
 * offline), gives a second opinion on meanings the offline matcher rejected, and drafts example
 * sentences for new words. Calls block - use a background thread.
 */
class ClaudeGrader(apiKey: String, private val model: String) : AutoCloseable {
    class GraderException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class WordInfo(val hanzi: String, val pinyin: String, val meanings: List<String>)

    @Serializable
    data class SentenceVerdict(
        val correct: Boolean,
        @SerialName("uses_target_word") val usesTargetWord: Boolean,
        val feedback: String,
        @SerialName("corrected_sentence") val correctedSentence: String,
        val translation: String,
    )

    @Serializable
    data class MeaningVerdict(val correct: Boolean, val feedback: String)

    @Serializable
    data class GeneratedExample(val zh: String, val pinyin: String, val en: String)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(45))
        .maxRetries(1)
        .build()

    fun gradeSentence(word: WordInfo, sentence: String): SentenceVerdict = ask(
        system = """
            You check answers from someone learning Mandarin Chinese (simplified characters). They were shown a word and asked to write their own sentence using it.
            Decide whether the sentence uses the word correctly: grammatical, natural enough that a native speaker would understand it, and using the word in a sense that fits its meaning. Ignore punctuation and small slips in other characters.
            The sentence must contain the word itself. Just the word, or the word padded with filler, does not count.
            Feedback: one or two short sentences in English. Also give a corrected or more natural version of the sentence in simplified Chinese (repeat it if it was already good) and an English translation of the learner's sentence.
        """.trimIndent(),
        user = "Word: ${word.hanzi} (${word.pinyin})\nMeanings: ${word.meanings.joinToString("; ")}\nLearner's sentence: $sentence",
        properties = linkedMapOf(
            "correct" to ("boolean" to "true if the sentence uses the word correctly"),
            "uses_target_word" to ("boolean" to "true if the sentence contains the target word"),
            "feedback" to ("string" to "one or two short sentences in English"),
            "corrected_sentence" to ("string" to "corrected or more natural sentence in simplified Chinese"),
            "translation" to ("string" to "English translation of the learner's sentence"),
        ),
    ).let { AppJson.decodeFromString<SentenceVerdict>(it) }

    fun checkMeaning(word: WordInfo, answer: String): MeaningVerdict = ask(
        system = """
            You check answers from someone learning Mandarin Chinese. They were shown a Chinese word and asked for its English meaning.
            Accept any answer that shows they know what the word means: synonyms, a different part of speech, or one of several senses is fine. Reject answers with a wrong, opposite or much too vague meaning.
            Feedback: one short sentence in English.
        """.trimIndent(),
        user = "Word: ${word.hanzi} (${word.pinyin})\nDictionary meanings: ${word.meanings.joinToString("; ")}\nLearner's answer: $answer",
        properties = linkedMapOf(
            "correct" to ("boolean" to "true if the answer shows they know the meaning"),
            "feedback" to ("string" to "one short sentence in English"),
        ),
    ).let { AppJson.decodeFromString<MeaningVerdict>(it) }

    fun generateExample(word: WordInfo): GeneratedExample = ask(
        system = """
            You write example sentences for a learner of Mandarin Chinese. Given a word, write one short, natural sentence (5 to 15 characters) in simplified Chinese that shows how the word is typically used, with mostly common vocabulary.
            In the Chinese sentence put a space between words, for example "我 每天 学习 汉语。". Also give the pinyin with tone marks and an English translation.
        """.trimIndent(),
        user = "Word: ${word.hanzi} (${word.pinyin})\nMeanings: ${word.meanings.joinToString("; ")}",
        properties = linkedMapOf(
            "zh" to ("string" to "the sentence in simplified Chinese, words separated by spaces"),
            "pinyin" to ("string" to "pinyin with tone marks"),
            "en" to ("string" to "English translation"),
        ),
    ).let { AppJson.decodeFromString<GeneratedExample>(it) }

    /** Cheap end-to-end check for the Settings screen. */
    fun testConnection(): String {
        val verdict = checkMeaning(WordInfo("你好", "nǐhǎo", listOf("hello", "hi")), "hello")
        return if (verdict.correct) "Connected to $model." else "Connected, but got an unexpected answer: ${verdict.feedback}"
    }

    /**
     * One request with a JSON-schema constrained answer. Grading is a quick judgement, so effort
     * is kept low for speed; a refused request is retried server-side on the recommended fallback
     * model (the "fallbacks" option) before we give up.
     */
    private fun ask(system: String, user: String, properties: Map<String, Pair<String, String>>): String {
        val schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(properties.mapValues { (_, v) -> mapOf("type" to v.first, "description" to v.second) }),
            )
            .putAdditionalProperty("required", JsonValue.from(properties.keys.toList()))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
        val output = OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build())
        if (supportsEffort(model)) output.effort(OutputConfig.Effort.LOW)
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(output.build())
            .addUserMessage(user)
        if (supportsServerFallback(model)) {
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val response = try {
            client.messages().create(params.build())
        } catch (e: UnauthorizedException) {
            throw GraderException("Claude rejected the API key. Check it in Settings.", e)
        } catch (e: PermissionDeniedException) {
            throw GraderException("This API key isn't allowed to use $model.", e)
        } catch (e: NotFoundException) {
            throw GraderException("Model \"$model\" was not found. Check the model name in Settings.", e)
        } catch (e: RateLimitException) {
            throw GraderException("Claude is rate limiting this key. Try again in a minute.", e)
        } catch (e: BadRequestException) {
            throw GraderException("Claude rejected the request: ${e.message}", e)
        } catch (e: AnthropicServiceException) {
            throw GraderException("Claude API error: ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw GraderException("Couldn't reach Claude (network problem).", e)
        } catch (e: AnthropicException) {
            throw GraderException("Claude request failed: ${e.message}", e)
        }
        when (response.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw GraderException("Claude declined to grade this answer.")
            StopReason.MAX_TOKENS -> throw GraderException("Claude's answer was cut off.")
            else -> Unit
        }
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw GraderException("Claude returned an empty answer.")
        return text
    }

    override fun close() = client.close()

    companion object {
        /** Effort isn't accepted by Haiku 4.5 / Sonnet 4.5 and older models. */
        fun supportsEffort(model: String): Boolean =
            !model.contains("haiku") && !model.contains("-4-5") && !model.contains("-3-") && !model.contains("-4-0") &&
                !model.contains("-4-1") && model != "claude-sonnet-4" && model != "claude-opus-4"

        /** Models documented to accept the server-side refusal fallback ("fallbacks": "default"). */
        fun supportsServerFallback(model: String): Boolean =
            model in setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")
    }
}
