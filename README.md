# Lingo Lock

A language-practice gate for Android, set up for a **Samsung Galaxy S24 FE**. At the times you choose,
Lingo Lock keeps other apps closed until you've worked through a few words of the language you're learning.
For each word you have to:

1. **Say it.** Speech recognition checks your pronunciation (for Mandarin, tones included). If you can't talk
   right now, you can type the reading instead (pinyin for Chinese, kana or romaji for Japanese) or, for other
   languages, skip this step.
2. **Give the English meaning.**
3. **Use it correctly in a sentence.** Claude grades the sentence you write or dictate. Offline (or without an
   API key), you rebuild the word's example sentence from shuffled word tiles instead.

If you miss any of the three, the attempt is **logged as a loss** (with what you said or typed), you get the
correct answer with audio, and the quiz **moves on to a different word**.

It comes with word sets from beginner to advanced for **Mandarin, Japanese, Korean, Spanish, French and
Italian**, and you can add other languages from the phone. Words are organised Pleco-style in **sets** that you
switch on and off. Your own lists become sets of their own, so they never mix into the built-in levels, and a
file with just the words is enough: readings, meanings and example sentences are filled in automatically.

Phone calls, alarms, your calendar, SMS, emergency SOS, notifications and any apps you allow **keep working while
locked**. Emails from senders or accounts you choose can be opened too.

---

## Contents

- [How it works](#how-it-works)
- [Languages and word sets](#languages-and-word-sets)
- [Install on the Galaxy S24 FE](#install-on-the-galaxy-s24-fe)
- [Updating the app](#updating-the-app)
- [First-run setup on the phone](#first-run-setup-on-the-phone)
- [Settings you control](#settings-you-control)
- [Adding your own words](#adding-your-own-words)
- [How the built-in sets are made](#how-the-built-in-sets-are-made)
- [AI grading with Claude (optional)](#ai-grading-with-claude-optional)
- [Emergency exits](#emergency-exits)
- [Limitations](#limitations)
- [Project layout and development](#project-layout-and-development)
- [Credits and licences](#credits-and-licences)

---

## How it works

| Piece | What it does |
|---|---|
| **Practice gate** (an Accessibility service) | Watches *which app* is in front (package names only, never screen content). While a session is due, any app that isn't allowed (including the home screen, Settings and Recents) is covered by the practice screen. |
| **Schedule** | *N* reset times a day (default 08:00 · 13:00 · 19:00). After each reset time, the next time you use the phone you practise first. If several reset times pass while the phone is off, one session clears them all. |
| **Session** | *N* words (default 5) in the language you're practising. You choose whether it ends after N words *attempted* (misses are logged as losses) or only after N *correct* answers. With "correct", every miss deals a new word. |
| **Word choice** | Spaced repetition (Leitner boxes) over the sets you've switched on. Words you missed come back within minutes, and well-known words fade out to 75-day intervals. New words are dealt from the easiest switched-on set first. A word that's in several sets shares one progress record. |
| **Allowed while locked** | Always: phone & in-call screens, your default dialer and SMS apps, keyboards, notification shade and quick settings, permission dialogs, emergency SOS. By default you can also use contacts, clock/alarm, calendar, camera, Google Maps/Waze and Samsung/Google Wallet. You can change the list in Settings. |
| **Priority emails** | Notifications are never hidden. If you open an email notification that matches your rules (e.g. `me@work.com`, a sender's name or a subject word), that email app is allowed for a few minutes. Matching emails are also listed on the practice screen. |
| **Master PIN** | Ends a session early (logged as *skipped*), pauses the lock for 1 or 3 hours, and protects the settings. |
| **Dictionary** | For Chinese, CC-CEDICT (about 120k entries), searchable by characters, pinyin (with or without tones) or English, Pleco-style. For other languages, a search across all of that language's sets. |
| **Stats** | Per language: accuracy, streak, a 14-day chart, your weakest words and a full **loss log**. |

## Languages and word sets

| Language | Built-in sets (words) | Source |
|---|---|---|
| Mandarin Chinese | HSK 1 (156) · HSK 2 (146) · HSK 3 (298) · HSK 4 (598) · HSK 5 (1,298) · HSK 6 (2,500) | HSK 2.0 lists, pinyin from the official list, CC-CEDICT meanings, Tatoeba examples. HSK 1 has hand-written example sentences. |
| Japanese | JLPT N5 (662) · N4 (630) · N3 (1,709) · N2 (1,785) · N1 (3,303) | OpenJLPT |
| Korean | A1 (593) · A2 (696) · B1 (700) | Bannerless Studio word pack |
| Spanish | A1 (597) · A2 (698) · B1 (699) · B2 (1,500) · C1 (2,000) | A1-B1: Bannerless Studio word pack. B2-C1: word frequency |
| Italian | A1 (593) · A2 (700) · B1 (700) · B2 (1,500) · C1 (2,000) | A1-B1: Bannerless Studio word pack. B2-C1: word frequency |
| French | A1 (600) · A2 (700) · B1 (700) · B2 (1,500) · C1 (2,000) | word frequency |

- **Switch language:** *Home > Change*. Each language has its own sets, progress and stats. The first time you
  switch to a language its easiest set is switched on.
- **Choose what you're tested on:** *Home > Choose sets* (or *Words > Sets*). Switch sets on and off. Only
  switched-on sets are used for practice. On a new install only HSK 1 is on.
- **Add another language:** *Home > Change > Add a language…*. German, Russian, Indonesian, Swahili, Urdu and
  Persian download a ready-made A1-B1 list (about 2,000 words with example sentences). Others (Portuguese,
  Dutch, Swedish, Polish, Turkish, Greek, Hebrew, Arabic, Hindi, Vietnamese, Thai, Cantonese) start empty for
  your own lists.
- The **pronunciation** check uses the phone's speech recognition in that language, and word audio uses its
  text-to-speech voice. The setup checklist shows whether a voice is installed for the language you practise.

## Install on the Galaxy S24 FE

Everything needed to build is inside this repo. The toolchain goes into `.toolchain/`, which is gitignored.

**1. Build the APK** (Windows PowerShell, from the repo root):

```powershell
.\tools\build.ps1
```

On the first run this calls `tools\setup-toolchain.ps1`, which downloads a JDK 17, the Android SDK and Gradle
(~1 GB), and creates your personal signing key in `keystore\`. **Back up the `keystore\` folder.** Updates must
be signed with the same key, otherwise you have to uninstall first and lose your progress.

**2. Prepare the phone** (one-time):

- *Settings > About phone > Software information*: tap **Build number** 7 times to enable Developer options.
- *Settings > Developer options*: turn on **USB debugging**.
- *Settings > Security and privacy > Auto Blocker*: **turn it off**. On One UI 6+ it blocks USB installs and apps
  from outside the Galaxy Store and Play Store. You can turn it back on after installing, but updates need it
  off again.

**3. Install** with the phone plugged in (accept the *Allow USB debugging?* prompt): double-click
**`update-phone.cmd`** in the repo folder, or run

```powershell
.\tools\install.ps1
```

This uses the `adb` on your PATH (e.g. the one that comes with scrcpy), so a running scrcpy session isn't
disturbed, and falls back to the one in `.toolchain`.

## Updating the app

Changed something in the repo (new words, a code change, a `git pull`)? **Double-click `update-phone.cmd`**
(or run `.\tools\install.ps1`). It rebuilds the app and installs it over the one on the phone:

- Your progress, stats, settings, PIN and the words and sets you added on the phone are kept.
- Updated built-in sets are merged in: new words are added, and words you haven't edited on the phone get the
  new content. Your progress on them is never touched.
- *Settings > About* shows the build number, so you can check the update landed. It goes up with every commit.
- The phone needs USB debugging on and Auto Blocker off, as for the first install. `update-phone.cmd -NoBuild`
  reinstalls the last build without rebuilding.

**Without a cable:** on the phone, *Developer options > Wireless debugging > Pair device with pairing code*,
then on the PC (same Wi-Fi):

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe pair 192.168.1.23:37000
```

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe connect 192.168.1.23:41000
```

Use the addresses your phone shows: `pair` takes the address in the pairing dialog and asks for its 6-digit
code, and `connect` takes the *IP address & port* on the Wireless debugging screen (a different port).
After that, `update-phone.cmd` installs over Wi-Fi.

Before an update that changes a lot, a backup costs nothing: *Settings > Backup*.

## First-run setup on the phone

Open **Lingo Lock**. The setup checklist walks you through the following:

1. **Master PIN.** There's no reset, so pick one you'll remember.
2. **Microphone** permission (for the pronunciation check).
3. **Practice gate.** *Settings > Accessibility > Installed apps > Lingo Lock practice gate > On.*
   If the switch is greyed out with "Restricted setting" (Android 13+ does this for sideloaded apps), tap
   **App info** in the checklist, then **⋮** (top right) > **Allow restricted settings**, and try again.
4. **Run in the background.** Allow the battery exemption, and also add Lingo Lock under
   *Settings > Battery > Background usage limits > Never sleeping apps* so One UI doesn't put it to sleep.
5. *Recommended:* **Appear on top**, so the practice screen comes to the front more reliably.
6. **A voice** for the language you practise: *Settings > General management > Text-to-speech*. Pick Samsung or
   Google, tap ⚙ > *Install voice data* and choose the language. The checklist has a button to test it.
7. **Speech recognition** uses Google/Samsung voice input, which works best online. For offline use, download the
   language in Google's offline speech recognition settings.
8. *Optional:* **Notification access** for priority emails.

Then switch the lock on from the Home tab. Use **Lock now (test)** to try it immediately.

## Settings you control

*Settings* is PIN-protected, so you can't quietly weaken the lock in a moment of weakness.

- **How often:** times per day (1-12), spread evenly between a first and last time. You can also move or remove
  individual times and add your own.
- **How many words** per session (1-30), and whether a session ends after N attempts or N correct answers.
- **Allowed apps** while locked, and **priority email** rules plus how long an email app stays open (1-60 min).
- **Checks:** pronunciation tries per word; start in typed-reading mode; strict typed readings (tones in pinyin,
  long vowels in Japanese); allow skipping pronunciation for languages without a typed reading; and whether you
  can override the offline meaning check ("I was right", after which your answer is accepted for that word).
- **AI grading:** Anthropic API key, model and mode (see below).
- **Your words & progress:** a full backup of every language, set, word and your history. Restore it by
  importing the file (*Words > Import*).

Which sets you practise is chosen on the Sets screen, not in Settings.

## Adding your own words

A list of words is enough, and every import becomes **its own set**, so it never mixes into HSK 1 or the other
built-in levels. Words you already have (in any set) are linked rather than duplicated and keep their progress.
Everything else is filled in automatically:

- **Chinese:** pinyin and meanings from CC-CEDICT, example sentences from the bundled Tatoeba corpus.
- **Every language:** words that are in the built-in sets reuse their content. For the rest, Claude writes the
  reading, meaning and an example sentence if you've added an API key. Otherwise they wait, marked
  *incomplete* and left out of practice, until you fill them in on the word's page (*Fill in with Claude* or
  by hand).

**On the phone:** tap **Import** on the Sets or Words screen, or **share** a `.csv` / `.txt` file to Lingo Lock
(or open it with Lingo Lock from My Files), or **drag** it onto the Sets or Words screen in split screen or DeX.
You name the set and confirm the language before importing.

**From the PC:** drag the file onto **`add-words.cmd`** in the repo folder. It creates the set in
`content/sets/custom/` (the language is detected from the writing, or asked), then offers to update the phone.
Dropping a file with the same name again updates that set. The same from a terminal:

```powershell
python tools\sets.py import travel.csv
```

```powershell
python tools\sets.py import comida.csv --lang es --name "Food"
```

**Here, with Claude Code:** in this repo you can also just ask, e.g. *"make a set called Travel with 机场, 护照,
行李 and 登机牌"*. That runs the same import; then update the phone.

**File formats** (UTF-8, `.csv`, `.tsv` or `.txt`):

- Just the words: one per line, or separated by commas. For Chinese, Japanese and Korean spaces and `、` work too.
- A header row naming the columns: `word`, `pinyin` / `reading`, `meaning` / `english`. Given meanings and
  readings are kept; missing ones are filled in.

  ```
  word,meaning
  la playa,beach
  el pasaporte,passport
  ```
- A Pleco flashcard export (*Import/Export > Export Cards*, text): `headword<TAB>pinyin<TAB>definition`.
- A set exported from the phone (*Words > Sets > ⋮ > Export*): drop it on `add-words.cmd` to bundle it with the
  app, e.g. to keep it safe or put it on another phone.

On the phone you can also add single words from the Dictionary tab (**+**) or with **+** on the Words tab.

## How the built-in sets are made

The sets live in [`content/sets`](content/sets) (one word per line, bundled into the app) and are generated by
[`tools/sets.py`](tools/sets.py) (Python 3.7+, standard library only) from open datasets it downloads into
`.cache/` (gitignored):

```powershell
python tools\sets.py build
```

```powershell
python tools\sets.py check
```

- `build [zh ja ko es fr it]` regenerates the built-in sets: HSK words get the official HSK pinyin (other
  accepted readings, such as neutral tones in 知道 *zhīdao*, are kept too) and short example sentences where the
  word is used on its own; words from the A1-B1 packs get the forms seen in their example sentences, so
  "Él es mi amigo" counts as a sentence with *ser*.
- `add-language de` adds another language's level sets to the repo (German, Russian, Indonesian, Swahili, Urdu,
  Persian, Hebrew).
- `check` validates every set file. The unit tests also check every bundled set (`SetFilesTest`).
- A set's `rev` is a content hash. The app applies a set whenever its `rev` changes.

The hand-written HSK 1 entries (with their example sentences) are in
[`tools/data/hsk1-curated.json`](tools/data/hsk1-curated.json).

## AI grading with Claude (optional)

A free-form sentence can't be checked properly without a language model, so Lingo Lock can use Claude to grade it:

- *Settings > AI grading*: paste an **Anthropic API key** (from console.anthropic.com) and tap **Test**. The
  default model is `claude-opus-5-5`, called with low effort for a quick verdict.
- Claude checks that your sentence contains the word, is grammatical and uses the word in the right sense. It
  explains what's wrong, suggests a better version, and translates your sentence. It is also asked when the
  offline meaning check rejects an answer, which catches valid synonyms, and it fills in words you import that
  aren't in any dictionary or set.
- Requests use the API's server-side refusal fallback (`fallbacks: "default"`), so a request declined by a safety
  classifier is retried on Anthropic's recommended fallback model rather than failing.
- Each graded sentence is one small API call billed to your key. If you're offline, or the call fails, the app
  falls back to the tile exercise, so you're never stuck.
- The key is stored only in the app's private storage on the phone.

## Emergency exits

- **Master PIN**: tap **PIN** on the practice screen to skip the session or pause for 1 or 3 hours.
- **Forgot the PIN?** Safe mode (hold the side key, long-press *Power off*, tap *Safe mode*) disables third-party
  accessibility services, so you can uninstall Lingo Lock from Settings there. Alternatively run
  `adb uninstall com.hanzilock` from a computer. Both delete your progress, unless you made a backup.
- The lock only engages once a PIN is set, and a language with no words switched on never keeps you locked.

## Limitations

This is a self-discipline tool, not a tamper-proof kiosk. Android doesn't let a normal app be one. You can get
around it on purpose:

- Safe mode disables it, as described above.
- A video already playing in picture-in-picture stays on top of the practice screen.
- Samsung pop-up view / split screen are covered on a best-effort basis (the practice screen is re-shown
  whenever a blocked window is visible).

And some limits of the checks and the data:

- Speech recognizers use context and can be lenient with tones. The typed-pinyin mode is strict about tones.
- Without Claude, a sentence you write is only checked for using the word. By default the offline mode uses the
  tile exercise instead. Words without an example sentence (a quarter of HSK 5, over half of HSK 6, where the
  corpus has no short sentence using the word) need Claude or a sentence you write.
- The B2-C1 sets for Spanish and Italian and all French levels are ranked by **word frequency**, not by an
  official syllabus. They include some inflected forms, and their English glosses are rougher.
- Korean goes up to B1: there's no open advanced Korean list with English meanings yet.

## Project layout and development

```
content/sets/                 word sets per language + index.json (bundled as assets; built by tools/sets.py)
content/corpus/zh-en.tsv      Chinese example sentences (Tatoeba) used when importing Chinese words
content/dictionary/*.gz       CC-CEDICT (bundled; refresh with tools/update-cedict.ps1)
app/src/main/java/com/hanzilock/
  lock/     accessibility service (gate), lock activity, priority-email notification listener
  core/     settings, languages, schedule/lock engine, allow list, master PIN
  quiz/     pinyin, kana, answer/speech matching, sentence tiles, spaced repetition, sessions, Claude grader
  data/     SQLite (words, sets, sessions, attempts), CC-CEDICT, set sync, language packs, import/export
  speech/   speech recognition + text-to-speech wrappers
  ui/       Jetpack Compose screens
app/src/test/                 JVM unit tests (pinyin, kana, matchers, tiles, schedule, import, bundled sets)
tools/                        setup-toolchain / build / install / update-cedict (PowerShell), sets.py
update-phone.cmd, add-words.cmd   double-click helpers for the two everyday jobs
```

Kotlin, Jetpack Compose (Material 3), plain SQLite, minSdk 31 / targetSdk 35. No Google Play services are
needed. The code and application id still use the working name `hanzilock` (`com.hanzilock`); changing the
application id would make Android treat it as a different app, so updates couldn't keep your progress.

```powershell
.\tools\build.ps1              # unit tests + signed release APK
```

```powershell
. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

You can also open the folder in Android Studio. It uses the same Gradle wrapper and `local.properties`.

## Credits and licences

- Dictionary: [CC-CEDICT](https://cc-cedict.org/wiki/) by MDBG, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The bundled copy is unmodified.
- HSK lists: [drkameleon/complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) (MIT) and the official HSK 2.0 lists from [hskhsk.com](https://github.com/glxxyz/hskhsk.com) (MIT).
- Japanese: [OpenJLPT](https://github.com/evanclan/OpenJLPT), CC BY-SA 4.0.
- Korean, Spanish, Italian (A1-B1) and the downloadable languages: [Bannerless Studio](https://github.com/Bannerless-Studio) word packs, CC BY-SA 4.0.
- Word frequencies and glosses: [orgtre/google-books-ngram-frequency](https://github.com/orgtre/google-books-ngram-frequency), CC BY 3.0.
- Example sentences: [Tatoeba](https://tatoeba.org) via [manythings.org](https://www.manythings.org/anki/), CC BY 2.0 FR.
- Because they adapt CC BY-SA material, the files in `content/sets` are shared under CC BY-SA 4.0.
- Mic and speaker icons: Material Icons (Apache 2.0).
- Optional grading: the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java).
