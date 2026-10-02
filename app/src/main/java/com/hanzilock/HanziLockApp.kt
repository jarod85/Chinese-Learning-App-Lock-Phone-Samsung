package com.hanzilock

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hanzilock.core.AllowList
import com.hanzilock.core.EmailPasses
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
import com.hanzilock.data.WordRepository
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.SessionManager
import com.hanzilock.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** App-wide singletons, shared by the UI, the accessibility service and the notification listener. */
class HanziLockApp : Application() {
    lateinit var settings: Settings private set
    lateinit var languages: Languages private set
    lateinit var words: WordRepository private set
    lateinit var dictionary: DictionaryRepository private set
    lateinit var corpus: ExampleCorpus private set
    lateinit var pin: PinManager private set
    lateinit var lockEngine: LockEngine private set
    lateinit var emailPasses: EmailPasses private set
    lateinit var allowList: AllowList private set
    lateinit var sessions: SessionManager private set
    lateinit var setSync: SetSync private set
    lateinit var languagePacks: LanguagePacks private set
    lateinit var importExport: ImportExport private set
    lateinit var speaker: Speaker private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)

    /** True once the active language's word sets are installed (first start / after an update). */
    val ready: StateFlow<Boolean> = _ready

    private var grader: Pair<String, ClaudeGrader>? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        languages = Languages(settings)
        words = WordRepository(AppDatabase(this))
        dictionary = DictionaryRepository(this)
        corpus = ExampleCorpus(this)
        pin = PinManager(settings)
        lockEngine = LockEngine(settings, pinSet = { pin.isSet })
        emailPasses = EmailPasses()
        allowList = AllowList(this, settings, emailPasses)
        sessions = SessionManager(settings, words, lockEngine)
        setSync = SetSync(this, words, languages, settings)
        languagePacks = LanguagePacks(words, languages)
        importExport = ImportExport(this, words, dictionary, corpus, languages) { graderOrNull() }
        speaker = Speaker(this)   // started now so audio is ready for the first word

        scope.launch(Dispatchers.IO) {
            runCatching { setSync.syncAll(onActiveReady = { _ready.value = true }) }
            _ready.value = true
            dictionary.ensureImported()
        }
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

    fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        fun get(context: Context): HanziLockApp = context.applicationContext as HanziLockApp
    }
}
