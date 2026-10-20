package com.hanzilock

import com.hanzilock.core.GradingMode
import com.hanzilock.core.Languages
import com.hanzilock.core.LockEngine
import com.hanzilock.core.PinManager
import com.hanzilock.core.Settings
import com.hanzilock.data.AppDatabase
import com.hanzilock.data.DictionaryRepository
import com.hanzilock.data.ExampleCorpus
import com.hanzilock.data.ImportExport
import com.hanzilock.data.LanguagePacks
import com.hanzilock.data.SetSync
import com.hanzilock.data.WordFiller
import com.hanzilock.data.WordRepository
import com.hanzilock.platform.Platform
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.SessionManager
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.stories.StoryImporter
import com.hanzilock.stories.StoryRepository
import com.hanzilock.sync.SyncManager
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** App-wide singletons, the same on the phone and the Mac: shared by the UI and the practice gate. */
class AppCore(val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = Settings(platform.prefs)
    val languages = Languages(settings)
    val words = WordRepository(platform.openDatabase(AppDatabase))
    val dictionary = DictionaryRepository(platform.openDatabase(DictionaryRepository.Schema), platform.assets)
    val corpus = ExampleCorpus(platform.assets)
    val pin = PinManager(settings)
    val lockEngine = LockEngine(settings, pinSet = { pin.isSet })
    val sessions = SessionManager(settings, words, lockEngine)
    val setSync = SetSync(platform.assets, words, languages, settings)
    val languagePacks = LanguagePacks(words, languages)
    val filler = WordFiller(words, languages, settings) { graderOrNull() }
    val importExport = ImportExport(words, dictionary, corpus, languages, filler)
    val speaker: Speaker = platform.createSpeaker()   // started now so audio is ready for the first word
    val updater: Updater = platform.createUpdater(scope)
    val stories = StoryRepository(platform.openDatabase(StoryRepository.Schema))
    val storyImporter = StoryImporter(stories, dictionary) { graderOrNull() }
    val sync = SyncManager(this, platform.deviceName)

    private val _ready = MutableStateFlow(false)

    /** True once the active language's word sets are installed (first start / after an update). */
    val ready: StateFlow<Boolean> = _ready

    private var grader: Pair<String, ClaudeGrader>? = null

    /** Installs the bundled sets and the dictionary, and fills in new words, in the background. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching { setSync.syncAll(onActiveReady = { _ready.value = true }) }
            _ready.value = true
            dictionary.ensureImported()
        }
        // Words that reached your own sets without their details (e.g. a set imported on the PC).
        scope.launch(Dispatchers.IO) {
            ready.first { it }
            runCatching { filler.fillNewWords() }
        }
        sync.start()
    }

    fun newSpeechInput(): SpeechInput = platform.createSpeechInput()

    /** Claude fills in what the words [ids] are missing (reading, meanings, example), without blocking. */
    fun fillInBackground(lang: String, ids: Collection<Long>) {
        scope.launch(Dispatchers.IO) { runCatching { filler.fill(lang, ids) } }
    }

    /** Practice sessions (and the word list) switch to [code]; its first set is turned on if none is. */
    fun switchLanguage(code: String) {
        settings.activeLanguage = code
        scope.launch(Dispatchers.IO) { words.enableFirstSetIfNone(code) }
    }

    /** Claude grader for the configured key/model, or null when AI grading is off or offline. */
    @Synchronized
    fun graderOrNull(requireNetwork: Boolean = true): ClaudeGrader? {
        val key = settings.claudeApiKey
        if (key.isBlank() || settings.gradingMode == GradingMode.OFFLINE) return null
        if (requireNetwork && !isOnline()) return null
        val id = "$key|${settings.claudeModel}"
        grader?.let { (cachedId, g) -> if (cachedId == id) return g else g.close() }
        return ClaudeGrader(key, settings.claudeModel).also { grader = id to it }
    }

    fun isOnline(): Boolean = platform.isOnline()
}
