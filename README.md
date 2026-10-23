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
correct answer with audio, and the quiz **moves on to a different word**. Don't know a word at all? **Skip this
word** shows the answer without scoring it and deals another word in its place, so it never brings the unlock closer.

It comes with word sets from beginner to advanced for **Mandarin, Japanese, Korean, Spanish, French and
Italian**, and you can add other languages from the phone. Words are organised Pleco-style in **sets** that you
switch on and off. Your own lists become sets of their own, so they never mix into the built-in levels, and a
file with just the words is enough: readings, meanings and example sentences are filled in automatically.

Every word also has a **learning mode** guided by Claude: it explains the word only in the language you're
learning (no English), shows how it's used with example sentences, then corrects three sentences you write
yourself, without grading them.

A **Stories** tab gives you Chinese stories to read: written by Claude at your HSK level, graded stories from
Mandarin Bean, or any web page or text. Point at a word (or tap it) for its pinyin and meaning, click it to see what
its sentence means in English, search the dictionary without leaving the story, have the story read aloud, and answer
comprehension questions in Chinese at the end.

A **Chinese tutor** is docked at the bottom of every screen, like LinkedIn's messaging: ask Claude about a word,
grammar, pronunciation or a sentence you're reading, in any language, and it answers only in Chinese.

Phone calls, alarms, your calendar, SMS, emergency SOS, notifications and any apps you allow **keep working while
locked**. Emails from senders or accounts you choose can be opened too.

**On your Mac too.** The same app runs on a Mac with Apple silicon: the same words, sets, stories, dictionary and
stats, kept in sync with the phone through a private GitHub repository. It has the practice gate as well, off until you
switch it on. See [Lingo Lock on the Mac](#lingo-lock-on-the-mac) and
[Sync between your phone and Mac](#sync-between-your-phone-and-mac).

---

## Contents

- [How it works](#how-it-works)
- [Languages and word sets](#languages-and-word-sets)
- [Install on the Galaxy S24 FE](#install-on-the-galaxy-s24-fe)
- [Updating the app](#updating-the-app)
- [Lingo Lock on the Mac](#lingo-lock-on-the-mac)
- [Sync between your phone and Mac](#sync-between-your-phone-and-mac)
- [First-run setup on the phone](#first-run-setup-on-the-phone)
- [Settings you control](#settings-you-control)
- [Adding your own words](#adding-your-own-words)
- [How the built-in sets are made](#how-the-built-in-sets-are-made)
- [AI grading with Claude (optional)](#ai-grading-with-claude-optional)
- [Learning mode](#learning-mode)
- [Stories](#stories)
- [Chinese tutor](#chinese-tutor)
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
| **Stories** | Chinese stories to read page by page, with word lookup, English translation, reading aloud and comprehension questions (see [Stories](#stories)). |
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
- **Combine sets:** *Sets > Combine*. Tick two or more sets and name the new one. It's a set of your own with
  all their words (progress carries over); the originals stay, and are switched off unless you untick that.
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

Updates come **over the internet, without a cable**. Every build is published as a release of this GitHub
repository; Lingo Lock checks for a newer one whenever you open it and shows **Update available** on the Home
screen (or *Settings > App updates > Check for updates*). Tap **Install** and confirm with **Update**.

- Your progress, stats, settings, PIN and the words and sets you added on the phone are kept: the update is
  signed with your own key, so Android installs it over the app.
- The first time, Android asks you to allow Lingo Lock to install apps: tap **Allow**, turn on *Allow from this
  source*, come back and tap **Install** again.
- Auto Blocker must stay off (*Settings > Security and privacy > Auto Blocker*): it blocks installs from
  anywhere but the Galaxy Store and Play Store.
- Updated built-in sets are merged in: new words are added, and words you haven't edited on the phone get the
  new content. Your progress on them is never touched.
- *Settings > About* shows the build number, so you can check the update landed. It goes up with every commit.

**Publishing builds.** Cloud builds are set up: GitHub builds the app on every push to `main`
([`.github/workflows/release.yml`](.github/workflows/release.yml)) and publishes it as a release, about
5-10 minutes after the push. The Mac app (`lingo-lock-mac.zip`, built on a macOS machine) is added to the same release
a few minutes later. So a change reaches the phone like this: commit, push, then on the phone
*Settings > App updates > Check for updates* and **Install**. No PC is needed, e.g. when you ask Claude Code on
the web for a change.

- The cloud builds are signed with your key from `keystore\`, stored as encrypted secrets of the repository by
  `.\tools\setup-cloud-builds.ps1` (run once, with the [GitHub CLI](https://cli.github.com):
  `winget install --id GitHub.cli`, then `gh auth login`). Run it again if you ever replace the key. Without
  the secrets the workflow skips the build.
- Alternative that keeps the signing key on your PC only: delete the secrets
  (*GitHub > Settings > Secrets and variables > Actions*), then after each commit and push run
  `.\tools\publish.ps1`.

**With a cable** (first install, or recovery if an in-app update can't be installed): **double-click `update-phone.cmd`** (or run
`.\tools\install.ps1`). It rebuilds the app and installs it over the one on the phone. The phone needs USB
debugging on and Auto Blocker off, as for the first install. `update-phone.cmd -NoBuild` reinstalls the last
build without rebuilding. Wireless debugging works too: on the phone, *Developer options > Wireless debugging >
Pair device with pairing code*, then on the PC (same Wi-Fi):

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe pair 192.168.1.23:37000
```

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe connect 192.168.1.23:41000
```

Use the addresses your phone shows: `pair` takes the address in the pairing dialog and asks for its 6-digit
code, and `connect` takes the *IP address & port* on the Wireless debugging screen (a different port).

Before an update that changes a lot, a backup costs nothing: *Settings > Backup*.

## Lingo Lock on the Mac

The Mac app is the same app with a desktop layout: a sidebar instead of the tabs at the bottom, and keyboard
shortcuts (⌘1 to ⌘6 for the tabs, Esc to go back, Return to check an answer, ← and → to turn a story's pages, ⌘F to
search while reading). In a story, point at a word for its pinyin and meaning, click it for its sentence's translation,
and right-click it for its dictionary card. It needs a Mac with Apple silicon (M1 or newer) and macOS 13 or later.

### Setting up the Mac, step by step

**1. Download the app.** On the Mac, open this repository on GitHub in Safari: *Releases* (right-hand side) > the
newest *Build N* > *Assets* > `lingo-lock-mac.zip`. Safari unpacks it into *Lingo Lock.app* in *Downloads* (if you
see only the zip, double-click it).

**2. Put it in Applications.** Drag *Lingo Lock.app* from *Downloads* into *Applications* (Finder sidebar). Updates
replace it there later, so keep it in that folder.

**3. Open it the first time.** Double-click *Lingo Lock* in *Applications*. macOS says *"Lingo Lock" Not Opened* /
it can't check the app for malware, because it isn't signed with an Apple Developer account. Click **Done**. Then:

- open *System Settings > Privacy & Security*, scroll down to *Security*: *"Lingo Lock" was blocked to protect your
  Mac*, click **Open Anyway** and confirm with your password or Touch ID;
- in the dialog that follows, click **Open Anyway** again.

This is needed only once (the button shows for about an hour after you tried to open the app). If macOS instead says
the app *is damaged and can't be opened*, open *Terminal* and run
`xattr -dr com.apple.quarantine "/Applications/Lingo Lock.app"`, then open the app again.

**4. Wait for the first start.** The first time, Lingo Lock installs the word sets and the dictionary in the
background (about half a minute).

**5. Setup checklist** (it opens by itself; later: *Settings > Setup checklist*):

- **Master PIN** > **Set PIN**: 4-12 digits. The Mac has its own PIN; it can be the same as the phone's. There's no
  reset, so pick one you'll remember.
- **Microphone and speech recognition** > **Allow**: macOS asks twice, *Allow* both. If you said no, the row shows
  **Open**: allow Lingo Lock under *System Settings > Privacy & Security > Microphone* and *> Speech Recognition*.
- **Mandarin Chinese voice** (or the language you practise) > **Open**: in *Spoken Content* click the *ⓘ* next to
  *System voice* (*Manage Voices…*), find the language, and download a voice (the *Premium* or *Enhanced* ones sound
  best). Back in Lingo Lock, **Play "你好"** checks it.
- **Done**.

**6. Claude (optional, as on the phone).** *Settings* (asks for the PIN) > *AI grading (Claude)*: paste your Anthropic
API key, **Save**, then **Test**. On the Mac it's kept in your keychain.

**7. Sync with the phone.** Make the GitHub token first if you haven't (see
[Sync between your phone and Mac](#sync-between-your-phone-and-mac); the same token works on both devices). Then
*Settings > Sync between your devices*:

- *Repository*: `jarod85/lingo-lock-data` (yours: `<you>/lingo-lock-data`)
- *GitHub token*: the token
- *This device's name*: e.g. *MacBook Air*
- **Save & sync now**. It should say *Synced … with 1 other device* once the phone has synced too. Your words, sets,
  progress, stats, stories and the language you practise then appear on the Mac; the next sync of the phone brings the
  Mac's back.

**8. The practice gate (optional).** It's off on the Mac until you want it: *Settings > Practice lock on* (or the switch
on Home). Choose the apps that stay usable under *Settings > Allowed apps*, then try it with **Lock now (test)** on Home.
From then on Lingo Lock starts when you log in and stays in the menu bar.

**9. Updates.** *Settings > App updates > Check for updates* > **Install**: Lingo Lock replaces itself and restarts. You
don't need GitHub or Safari for updates.

**Updates** come the same way as on the phone: *Settings > App updates > Check for updates* and **Install**. Lingo Lock
downloads the new version, replaces itself and restarts. Your data stays in `~/Library/Application Support/Lingo Lock`
(the Claude key and the sync token are in your keychain). After an update macOS may ask again for the microphone and
speech recognition.

**The practice gate** is off until you switch it on (*Settings > Practice lock on*, PIN-protected). Then:

- After each reset time, a full-screen practice window covers your screen (another screen gets a plain cover) and stays
  in front of other apps until you finish the words, or use the master PIN to skip or pause.
- The apps you allow (*Settings > Allowed apps*; by default Calendar, Clock, FaceTime, Messages, Phone, Zoom, Teams and
  Webex) can be used: the practice window steps aside while one of them is in front.
- Lingo Lock then starts when you log in and keeps running in the menu bar when you close its window. Quitting it
  (⌘Q, or the menu bar icon) asks for the master PIN.
- Like the phone's, it's a self-discipline tool, not a kiosk: Force Quit still ends it (macOS starts it again), and
  switching the lock off removes it from the login items.

Phone only: priority emails, and sharing a web page from Chrome (on the Mac, paste the link on the Stories tab).

## Sync between your phone and Mac

Your phone and your Mac keep the same words, sets (which are switched on, and your own), progress, stats, stories,
languages and schedule. They sync through a private GitHub repository: each device uploads its own data there and
merges what the others uploaded - at start, shortly after a change, every few minutes, and just before the practice
gate comes up.

- Sets you make on one device (imported lists, set files, *Combine*, words you add) appear on the other with their
  words, and renaming them or adding words there comes back. The *Sets* screen says whether sync is on; **Sync now**
  there fetches what the other device sent without waiting for the next sync (a device sends a change about half a
  minute after you make it).
- A practice session finished on one device unlocks the other until its next reset time. Skipping with the PIN doesn't.
- If both devices changed the same thing, the newer change wins; a word you edited beats an update from a built-in
  set, and the most recent practice of a word wins. Deleting a set, word or story deletes it on the other device too.
- The master PIN, the Claude API key, the lock switch, the allowed apps and priority emails stay per device.

**Set it up** (once):

1. On the PC: `.\tools\setup-sync.ps1`. It creates the private repository `<you>/lingo-lock-data` with the GitHub CLI
   and opens GitHub's page for a token. Without the PC: on github.com, *+ > New repository*, name `lingo-lock-data`,
   *Private*, tick *Add a README file*, **Create repository**; then open *Settings > Developer settings > Personal access
   tokens > Fine-grained tokens > Generate new token*.
2. Make a fine-grained token: *Repository access > Only select repositories > lingo-lock-data*;
   *Permissions > Contents: Read and write*. Copy it.
3. On the phone and on the Mac: *Settings > Sync between your devices*: the repository (`<you>/lingo-lock-data`) and
   the token, then **Save & sync now**.

The token can only read and write that one repository. It's kept in the app's private storage on the phone and in the
keychain on the Mac. When it expires, Sync shows an error: make a new one and paste it on both devices.

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
- **App updates:** check for and install a new build (see [Updating the app](#updating-the-app)).
- **Your words & progress:** a full backup of every language, set, word and your history. Restore it by
  importing the file (*Words > Import*).

Which sets you practise is chosen on the Sets screen, not in Settings.

## Adding your own words

A list of words is enough, and every import becomes **its own set**, so it never mixes into HSK 1 or the other
built-in levels. Words you already have (in any set) are linked rather than duplicated and keep their progress.
Everything else is filled in automatically:

- **Chinese:** pinyin and meanings from CC-CEDICT, example sentences from the bundled Tatoeba corpus.
- **Every language:** words that are in the built-in sets reuse their content.
- **With a Claude API key** (and online), Claude adds whatever is still missing to every word you add, by
  import or one at a time: the reading (pinyin for Chinese), meanings, and an example sentence **with its
  pinyin** (kana for Japanese) and English translation. Words that arrive another way, e.g. a set imported on
  the PC, are filled in the next time you open the app.
- Without Claude, words with no meaning wait, marked *incomplete* and left out of practice, until you fill them
  in on the word's page (*Fill in with Claude* or by hand).

**On the phone:** tap **Import** on the Sets or Words screen, or **share** a `.csv` / `.txt` file to Lingo Lock
(or open it with Lingo Lock from My Files), or **drag** it onto the Sets or Words screen in split screen or DeX.
You name the set and confirm the language before importing.

**From the PC:** drag the file onto **`add-words.cmd`** in the repo folder. It creates the set in
`content/sets/custom/` (the language is detected from the writing, or asked), then offers to publish it: it
commits and pushes the set, and the phone offers the update a few minutes later (*Settings > App updates*).
Dropping a file with the same name again updates that set. The same from a terminal:

```powershell
python tools\sets.py import travel.csv
```

```powershell
python tools\sets.py import comida.csv --lang es --name "Food"
```

**Here, with Claude Code:** in this repo you can also just ask, e.g. *"make a set called Travel with 机场, 护照,
行李 and 登机牌"*. That runs the same import; then commit and push, and update the app on the phone.

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
Type the word: for Chinese the pinyin and meanings fill in from CC-CEDICT as you type, and after you save,
Claude adds an example sentence with its pinyin. With Claude, the word alone is enough for any language.

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
  offline meaning check rejects an answer, which catches valid synonyms. It also fills in what new words are
  missing (example sentences with their pinyin, readings, meanings) and runs [learning mode](#learning-mode) and the
  [Chinese tutor](#chinese-tutor).
- Requests use the API's server-side refusal fallback (`fallbacks: "default"`), so a request declined by a safety
  classifier is retried on Anthropic's recommended fallback model rather than failing.
- Each graded sentence is one small API call billed to your key. If you're offline, or the call fails, the app
  falls back to the tile exercise, so you're never stuck.
- The key is stored only in the app's private storage on the phone.

## Learning mode

Every word has a **Learn with Claude** button on its page (*Words* > tap a word), and after you answer a word
in a practice or lock session, **Learn this word with Claude** opens the same lesson over the quiz (*Back to
the quiz* returns to it). The lesson is written **only in the language you're learning, without English**:

1. **What it means:** a short explanation in simple words.
2. **How to use it:** the usual patterns and words it goes with, plus three example sentences with audio.
3. **Your turn:** you write three sentences of your own with the word. Claude says what's wrong and gives a
   corrected version. Nothing is graded: a wrong sentence still counts, and the word's review schedule and
   stats don't change. You can keep writing more after the three.

For Chinese and Japanese, *Show pinyin* / *Show reading* puts the pinyin (or kana) under every sentence. Each
lesson and each checked sentence is one API call billed to your key. Learning mode needs the API key and a
connection, and isn't available when AI grading is set to *Always offline*.

## Stories

The **Stories** tab is for reading practice in Chinese (simplified characters). Get a story in one of three ways:

- **Write one for my level (Claude):** pick HSK 1-6, a length and, if you like, a topic. Claude writes an original
  story with mostly words of that level, its English translation and three comprehension questions (one API call).
  Without a topic it picks a different kind of story each time.
- **Graded stories from Mandarin Bean:** the stories on [mandarinbean.com](https://mandarinbean.com), sorted by HSK
  level. The one you tap is loaded onto your phone for your own reading.
- **From a link or text:** paste a link to any web page with a Chinese story (the app keeps the story text and
  leaves out menus and footers), or paste the Chinese text itself. Even quicker: in Chrome, tap **Share > Lingo
  Lock** on the page you're reading. Traditional characters are changed to simplified, and very long pages are cut
  to the first 8,000 characters.

Reading a story:

- The text comes in **pages**: swipe or use the arrows at the bottom. The story opens where you left off.
- **Point at a word** with the mouse (on the Mac): it turns red, its pinyin shows above it, and its meaning from
  CC-CEDICT appears under *Word meaning* at the top (character by character if it isn't in the dictionary), with
  buttons to hear it or add it to your words. On the phone, tap the word.
- **Click (or tap) a word** to see how it's used: its sentence is highlighted and *Sentence meaning* at the top shows
  the sentence in English. Click it again to let go. **Whole page** shows the translation of the whole page instead
  (**One sentence** goes back). Translations of stories from the web come from Claude the first time you need them
  (a page and the next one at a time), then they're saved.
- **Press and hold a word** (right-click on the Mac) for its dictionary card with every reading.
- **Search** (the magnifier at the top, or ⌘F on the Mac) looks words up in the dictionary by characters, pinyin
  (`xuexi` or `xue2xi2`) or English while you read. Words that are in the story come first with the pages they're on;
  click one to go there. A word you search for is marked wherever it is in the story. On the Mac the search opens
  beside the story; Esc closes it.
- Word lookup and search use the dictionary on the device, so they work without a connection.
- The **speaker button** (top right) **reads the story aloud** from the page you're on, highlighting each sentence
  and turning the pages; tap it again to pause. **Normal / Slow** sets the speed. It uses the phone's Chinese
  text-to-speech voice (see the setup checklist).
- After the last page come the **questions**: answer each one in Chinese, typed or spoken with the mic, then
  **explain the story in your own words in Chinese**. Claude says whether you understood it, corrects your Chinese
  (with pinyin and an English translation) and shows a good answer. **Finish story** ticks it off in your list.

Writing stories, translating and checking answers need the Claude API key (*Settings > AI grading*, not set to
*Always offline*) and a connection; each is one small call billed to your key. Reading, word lookup and reading
aloud work offline once a story is on the phone.

## Chinese tutor

A chat with Claude for questions about Chinese, docked at the bottom of the app like LinkedIn's messaging. Write in
any language (English, Chinese or a mix); the tutor **answers only in Chinese**, in simple words, with example
sentences. Paste a word with the sentence it came from, for example *What does 主要 mean in 你理解了故事的主要内容?*,
and it explains what the word means in that sentence and how it's usually used. It also checks sentences you write.

- **On the phone:** the tutor's bar sits above the tabs. Tap it to open the conversation full screen; the arrow at the
  top (or Back) closes it again. While it's closed, the bar shows the start of the last answer.
- **On the Mac** (and any wide window): the bar sits in the bottom-right corner. Click it to open the conversation
  above it, and click it again (or press Esc) to close it. **Enter** sends, **Shift+Enter** starts a new line.
- The answer appears as Claude writes it. The speaker button reads an answer aloud, and you can select its text to
  copy it into the dictionary. **+** starts a new conversation. The conversation lasts until you close the app.

Each question is one API call billed to your key, with the whole conversation so far. The tutor needs the Claude API
key (*Settings > AI grading*, not set to *Always offline*) and a connection; if a question can't be answered, it goes
back into the box so you can send it again.

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
shared/src/commonMain/kotlin/com/hanzilock/   everything the phone and the Mac share
  core/     settings, languages, schedule/lock engine, master PIN
  quiz/     pinyin, kana, answer/speech matching, sentence tiles, spaced repetition, sessions, Claude grader
  data/     SQLite (words, sets, sessions, attempts), CC-CEDICT, set sync, language packs, import/export,
            filling in new words with Claude
  stories/  stories: word cutting and pages, fetching from the web, Claude's stories, translations and questions
  sync/     syncing your devices through a private GitHub repository
  platform/ what each device provides (database, settings, bundled content, files, parts of the UI)
  speech/, update/   speech and in-app update interfaces (each device implements them)
  ui/       Compose screens (ui/learn: learning mode, ui/stories: the Stories tab and reader,
            ui/tutor: the Chinese tutor)
shared/src/commonTest/        JVM unit tests (pinyin, kana, matchers, tiles, schedule, import, bundled sets, stories)
app/src/main/java/com/hanzilock/   the phone app
  lock/     accessibility service (gate), lock activity, priority-email notification listener
  platform/, speech/, update/   Android's database, settings, speech recognition, text-to-speech, APK installer
desktop/src/main/kotlin/com/hanzilock/desktop/   the Mac app: window and keyboard, practice gate, menu bar, login item,
                              SQLite through JDBC, keychain, the Mac updater (tests: syncing two devices)
macos/lingo-helper/           Swift helper for the Mac: Apple's voices, speech recognition, installed apps, app in front
tools/                        setup-toolchain / build / install / update-cedict / publish / setup-cloud-builds /
                              setup-sync (PowerShell), sets.py
.github/workflows/release.yml cloud build + GitHub release on every push to main (phone, then Mac)
update-phone.cmd, add-words.cmd   double-click helpers for the two everyday jobs
```

Kotlin, Compose Multiplatform (Jetpack Compose and Material 3 on the phone), plain SQLite, minSdk 31 / targetSdk 35. No
Google Play services are needed. The code and application id still use the working name `hanzilock` (`com.hanzilock`); changing the
application id would make Android treat it as a different app, so updates couldn't keep your progress.

```powershell
.\tools\build.ps1              # unit tests + signed release APK
```

```powershell
. .\tools\env.ps1; .\gradlew.bat :shared:testDebugUnitTest :shared:desktopTest :desktop:test :app:lintDebug
```

```powershell
. .\tools\env.ps1; .\gradlew.bat :desktop:run    # the Mac app on the PC, for development (no voices or speech recognition)
```

The Mac app itself (`:desktop:createDistributable`, with the helper from `macos/lingo-helper/build.sh`) can only be
built on a Mac; the release workflow does that.

You can also open the folder in Android Studio. It uses the same Gradle wrapper and `local.properties`.

## Credits and licences

- Dictionary: [CC-CEDICT](https://cc-cedict.org/wiki/) by MDBG, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The bundled copy is unmodified.
- HSK lists: [drkameleon/complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) (MIT) and the official HSK 2.0 lists from [hskhsk.com](https://github.com/glxxyz/hskhsk.com) (MIT).
- Japanese: [OpenJLPT](https://github.com/evanclan/OpenJLPT), CC BY-SA 4.0.
- Korean, Spanish, Italian (A1-B1) and the downloadable languages: [Bannerless Studio](https://github.com/Bannerless-Studio) word packs, CC BY-SA 4.0.
- Word frequencies and glosses: [orgtre/google-books-ngram-frequency](https://github.com/orgtre/google-books-ngram-frequency), CC BY 3.0.
- Example sentences: [Tatoeba](https://tatoeba.org) via [manythings.org](https://www.manythings.org/anki/), CC BY 2.0 FR.
- Because they adapt CC BY-SA material, the files in `content/sets` are shared under CC BY-SA 4.0.
- Graded stories in the Stories tab: [Mandarin Bean](https://mandarinbean.com), fetched on your phone for personal
  reading; they aren't part of this repository.
- Mic, speaker, pause and book icons: Material Icons (Apache 2.0).
- Optional grading: the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java).
- The Mac app: [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) (Apache 2.0) and
  [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache 2.0).
