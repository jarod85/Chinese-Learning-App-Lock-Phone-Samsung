# HanziLock · 汉字锁

A Mandarin practice gate for Android, set up for a **Samsung Galaxy S24 FE**. At the times you choose,
HanziLock keeps other apps closed until you've worked through a few Chinese words. For each word you have to:

1. **Say it.** Speech recognition checks your pronunciation, tones included. If you can't talk right
   now, you can type the pinyin with tones instead.
2. **Give the English meaning.**
3. **Use it correctly in a sentence.** Claude grades the sentence you write or dictate. Offline (or
   without an API key), you rebuild the word's example sentence from shuffled word tiles instead.

If you miss any of the three, the attempt is **logged as a loss** (with what you said or typed), you get
the correct answer with audio, and the quiz **moves on to a different word**. Your word list works like
Pleco flashcards. It's a registry that lives in this repo, and you can extend it from the built-in CC-CEDICT
dictionary, by importing a Pleco export, or by adding words in the app.

Phone calls, alarms, your calendar, SMS, emergency SOS, notifications and any apps you allow **keep working
while locked**. Emails from senders or accounts you choose can be opened too.

---

## Contents

- [How it works](#how-it-works)
- [Install on the Galaxy S24 FE](#install-on-the-galaxy-s24-fe)
- [First-run setup on the phone](#first-run-setup-on-the-phone)
- [Settings you control](#settings-you-control)
- [Updating the word registry](#updating-the-word-registry)
- [AI sentence grading with Claude (optional)](#ai-sentence-grading-with-claude-optional)
- [Emergency exits](#emergency-exits)
- [Limitations](#limitations)
- [Project layout and development](#project-layout-and-development)
- [Credits](#credits)

---

## How it works

| Piece | What it does |
|---|---|
| **Practice gate** (an Accessibility service) | Watches *which app* is in front (package names only, never screen content). While a session is due, any app that isn't allowed (including the home screen, Settings and Recents) is covered by the practice screen. |
| **Schedule** | *N* reset times a day (default 08:00 · 13:00 · 19:00). After each reset time, the next time you use the phone you practise first. If several reset times pass while the phone is off, one session clears them all. |
| **Session** | *N* words (default 5). You choose whether it ends after N words *attempted* (misses are logged as losses) or only after N *correct* answers. With "correct", every miss deals a new word. |
| **Word choice** | Spaced repetition (Leitner boxes). Words you missed come back within minutes, and well-known words fade out to 75-day intervals. New words are dealt in registry order. |
| **Allowed while locked** | Always: phone & in-call screens, your default dialer and SMS apps, keyboards, notification shade and quick settings, permission dialogs, emergency SOS. By default you can also use contacts, clock/alarm, calendar, camera, Google Maps/Waze and Samsung/Google Wallet. You can change the list in Settings. |
| **Priority emails** | Notifications are never hidden. If you open an email notification that matches your rules (e.g. `me@work.com`, a sender's name or a subject word), that email app is allowed for a few minutes. Matching emails are also listed on the practice screen. |
| **Master PIN** | Ends a session early (logged as *skipped*), pauses the lock for 1 or 3 hours, and protects the settings. |
| **Dictionary** | CC-CEDICT, about 120k entries, bundled and searchable by characters, pinyin (with or without tones) or English, Pleco-style. |
| **Stats** | Accuracy, streak, a 14-day chart, your weakest words and a full **loss log**. |

## Install on the Galaxy S24 FE

Everything needed to build is inside this repo. The toolchain goes into `.toolchain/`, which is gitignored.

**1. Build the APK** (Windows PowerShell, from the repo root):

```powershell
.\tools\build.ps1
```

On the first run this calls `tools\setup-toolchain.ps1`, which downloads a JDK 17, the Android SDK and
Gradle (~1 GB), and creates your personal signing key in `keystore\`. **Back up the `keystore\`
folder.** Updates must be signed with the same key, otherwise you have to uninstall first and lose
your progress.

**2. Prepare the phone** (one-time):

- *Settings > About phone > Software information*: tap **Build number** 7 times to enable Developer options.
- *Settings > Developer options*: turn on **USB debugging**.
- *Settings > Security and privacy > Auto Blocker*: **turn it off**. On One UI 6+ it blocks USB installs and
  apps from outside the Galaxy Store and Play Store. You can turn it back on after installing, but
  updates will need it off again.

**3. Install** with the phone plugged in (accept the *Allow USB debugging?* prompt):

```powershell
.\tools\install.ps1
```

This uses the `adb` on your PATH (e.g. the one that comes with scrcpy), so a running scrcpy session
isn't disturbed. Re-running it later updates the app in place and keeps your progress.

## First-run setup on the phone

Open **HanziLock**. The setup checklist walks you through the following:

1. **Master PIN.** There's no reset, so pick one you'll remember.
2. **Microphone** permission (for the pronunciation check).
3. **Practice gate.** *Settings > Accessibility > Installed apps > HanziLock practice gate > On.*
   If the switch is greyed out with "Restricted setting" (Android 13+ does this for sideloaded apps),
   tap **App info** in the checklist, then **⋮** (top right) > **Allow restricted settings**, and try again.
4. **Run in the background.** Allow the battery exemption, and also add HanziLock under
   *Settings > Battery > Background usage limits > Never sleeping apps* so One UI doesn't put it to sleep.
5. *Recommended:* **Appear on top**, so the practice screen comes to the front more reliably.
6. **Chinese voice** for pronunciation audio: *Settings > General management > Text-to-speech*. Pick
   Samsung or Google, tap ⚙ > *Install voice data* > Chinese (Mandarin). Use **Play 你好** to test it.
7. **Speech recognition** uses Google/Samsung voice input, which works best online. For offline use,
   download Chinese (Mandarin) in Google's offline speech recognition settings.
8. *Optional:* **Notification access** for priority emails.

Then switch the lock on from the Home tab. Use **Lock now (test)** to try it immediately.

## Settings you control

*Settings* is PIN-protected, so you can't quietly weaken the lock in a moment of weakness.

- **How often:** times per day (1-12), spread evenly between a first and last time. You can also move or
  remove individual times and add your own.
- **How many words** per session (1-30), and whether a session ends after N attempts or N correct answers.
- **Allowed apps** while locked, and **priority email** rules plus how long an email app stays open (1-60 min).
- **Checks:** pronunciation tries per word, start in typed-pinyin mode, whether tones are required when
  typing, and whether you can override the offline meaning check ("I was right". Your answer is then
  accepted for that word from now on).
- **AI grading:** Anthropic API key, model and mode (see below).
- **Your data:** export the word list (registry format) or a full backup. You can import either from the Words tab.

## Updating the word registry

The registry is [`content/registry/words.json`](content/registry/words.json). It ships with all 150 HSK 1 words,
each with pinyin, meanings and an example sentence. One word per line:

```json
{"hanzi":"学习","traditional":"學習","pinyin":"xué xí","meanings":["to study","to learn"],"examples":[{"zh":"我 每天 都 学习 汉语。","pinyin":"Wǒ měitiān dōu xuéxí Hànyǔ.","en":"I study Chinese every day."}],"tags":["HSK1"]}
```

- `pinyin`: tone marks, one space between syllables (neutral tone unmarked, as in CC-CEDICT: `dōng xi`).
- `examples[].zh`: put **spaces between words**. Offline practice turns them into word tiles.
- The app applies the file when its **`version`** is higher than the version it last applied. New words are
  added, and words you never edited in the app get the updated content. Your progress is never touched.

**From the repo** (uses the bundled CC-CEDICT, Python 3.7+):

```powershell
python tools\registry.py add 电话 咖啡 飞机场 --tag HSK2   # look up, append, bump version
python tools\registry.py check                            # validate
.\tools\install.ps1                                       # rebuild + update the app
```

`add` fills pinyin and meanings. Add example sentences by hand, or let the app suggest them (below).
If you edit `words.json` by hand, run `python tools\registry.py bump` so phones pick up the change.

**On the phone:**
- *Dictionary* tab: search, then **+** to add a word with its pinyin and meanings pre-filled.
- *Words* tab: **+** to add a word. **Fill from dictionary** and **Suggest an example with Claude** help.
- *Words* > **Import**: a Pleco flashcard export (`File > Import/Export > Export Cards`, text format
  `headword<TAB>pinyin<TAB>definition`), a CSV with the same columns, or a plain list of Chinese words. Missing
  pinyin and meanings are filled from CC-CEDICT.
- To bring phone-side changes back to the repo: *Settings > Export words*, then copy the file over
  `content/registry/words.json`.

## AI sentence grading with Claude (optional)

A free-form sentence can't be checked properly without a language model, so HanziLock can use Claude
to grade it:

- *Settings > AI grading*: paste an **Anthropic API key** (from console.anthropic.com) and tap **Test**.
  The default model is `claude-opus-5-5`, called with low effort for a quick verdict.
- Claude checks that your sentence contains the word, is grammatical and uses the word in the right sense.
  It explains what's wrong, suggests a better version, and translates your sentence. It is also asked
  when the offline meaning check rejects an answer, which catches valid synonyms.
- Requests use the API's server-side refusal fallback (`fallbacks: "default"`), so a request declined by
  a safety classifier is retried on Anthropic's recommended fallback model rather than failing.
- Each graded sentence is one small API call billed to your key. If you're offline, or the call fails,
  the app falls back to the tile exercise, so you're never stuck.
- The key is stored only in the app's private storage on the phone.

## Emergency exits

- **Master PIN**: tap **PIN** on the practice screen to skip the session or pause for 1 or 3 hours.
- **Forgot the PIN?** Safe mode (hold the side key, long-press *Power off*, tap *Safe mode*) disables
  third-party accessibility services, so you can uninstall HanziLock from Settings there. Alternatively
  run `adb uninstall com.hanzilock` from a computer. Both delete your progress, unless you made a backup.
- The lock only engages once a PIN is set, and an empty word list never keeps you locked.

## Limitations

This is a self-discipline tool, not a tamper-proof kiosk. Android doesn't let a normal app be one.
You can get around it on purpose:

- Safe mode disables it, as described above.
- A video already playing in picture-in-picture stays on top of the practice screen.
- Samsung pop-up view / split screen are covered on a best-effort basis (the practice screen is re-shown
  whenever a blocked window is visible).
- Speech recognizers use context and can be lenient with tones. The typed-pinyin mode is strict about tones.
- Without Claude, a sentence you write is only checked for containing the word. By default the offline
  mode uses the tile exercise instead.

## Project layout and development

```
content/registry/words.json   the word registry (bundled as an asset)
content/dictionary/*.gz       CC-CEDICT (bundled; refresh with tools/update-cedict.ps1)
app/src/main/java/com/hanzilock/
  lock/     accessibility service (gate), lock activity, priority-email notification listener
  core/     settings, schedule/lock engine, allow list, master PIN
  quiz/     pinyin, answer/speech matching, sentence tiles, spaced repetition, sessions, Claude grader
  data/     SQLite (words, sessions, attempts), CC-CEDICT import/search, registry sync, import/export
  speech/   speech recognition + text-to-speech wrappers
  ui/       Jetpack Compose screens
app/src/test/                 JVM unit tests (pinyin, matchers, tiles, schedule, import, registry file)
tools/                        setup-toolchain / build / install / update-cedict (PowerShell), registry.py
```

Kotlin, Jetpack Compose (Material 3), plain SQLite, minSdk 31 / targetSdk 35. No Google Play services are needed.

```powershell
.\tools\build.ps1              # unit tests + signed release APK
.\tools\build.ps1 -SkipTests
. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest   # just the tests
```

You can also open the folder in Android Studio. It uses the same Gradle wrapper and `local.properties`.

## Credits

- Dictionary: [CC-CEDICT](https://cc-cedict.org/wiki/) by MDBG, licensed
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The bundled copy is unmodified.
- Mic and speaker icons: Material Icons (Apache 2.0).
- Optional grading: the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java).
