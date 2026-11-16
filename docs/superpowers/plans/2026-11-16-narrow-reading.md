# Narrow Reading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Narrow reading" card on the Stories screen: your own topics as chips, one tap writes a new nonfiction text on that topic at your level.

**Architecture:** Topics live in `Settings` as a shared (synced) setting. `writeStory` / `StoryImporter.write` get a `nonfiction` flag that swaps the "story" brief for a nonfiction one and labels the source "Claude · <topic>". A new card in `StoriesScreen.kt` uses the existing `StoryLauncher`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material 3), JUnit 4 desktop tests.

**Spec:** `docs/superpowers/specs/2026-11-16-narrow-reading-design.md`

## Global Constraints

- Topics are free text the user types; the list starts empty. No built-in topics.
- One tap writes at `settings.storyLevel` (or the highest switched-on HSK set when unset), `StoryLength.MEDIUM`, `settings.storyComprehensionLevel`.
- Source of a narrow-reading text: `"Claude · <topic>"`. No stories.db schema change.
- Same screen on Mac and Android (shared module only).
- Build locally per memory: Temurin 17 in scratchpad, `./gradlew :shared:compileKotlinDesktop :shared:desktopTest :desktop:test`.

## Review Focus

1. Sync from a device running an older app version (its `Settings.Shared` has no topics): must not wipe this device's topics. Pinned in Task 1.
2. Adding a blank, whitespace-padded or duplicate (case-insensitive) topic: blank ignored, trimmed, no duplicate chip. Pinned in Task 1.
3. Topic containing a newline (pasted text): stored as one topic, not split into several. Pinned in Task 1 (newlines collapse to a space).
4. Tapping a topic with no Claude key: launcher shows `NO_CLAUDE` in the snackbar, as "Write one" does. Existing behavior, no new test.
5. Tapping a second topic while one is being written: ignored (`StoryLauncher.run` already returns when busy). No new test.

---

### Task 1: Saved topics in Settings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hanzilock/core/Settings.kt` (stories section ~line 186; `Shared`, `shared()`, `applyShared` ~lines 260-320)
- Test: `desktop/src/test/kotlin/com/hanzilock/desktop/StoryComprehensionTest.kt` (add tests to this class; it already builds `AppCore(TestPlatform(dir))`)

**Interfaces:**
- Produces:
  - `var Settings.storyTopics: List<String>` — pref key `"story_topics"`, stored as the topics joined by `"\n"`; written with `editShared`.
  - `fun Settings.addStoryTopic(topic: String)` — collapse whitespace (incl. newlines) to single spaces, trim; ignore blank or case-insensitive duplicate; append.
  - `fun Settings.removeStoryTopic(topic: String)`
  - `Settings.Shared.storyTopics: List<String>? = null`; `applyShared` writes it only when non-null.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun storyTopicsAreTrimmedDedupedAndPersist() {
    val dir = tmp.newFolder("device")
    val core = AppCore(TestPlatform(dir))
    assertEquals(emptyList<String>(), core.settings.storyTopics)
    core.settings.addStoryTopic("  AI  ")
    core.settings.addStoryTopic("ai")
    core.settings.addStoryTopic("   ")
    core.settings.addStoryTopic("Second-language\nacquisition")
    assertEquals(listOf("AI", "Second-language acquisition"), core.settings.storyTopics)
    core.settings.removeStoryTopic("AI")
    assertEquals(listOf("Second-language acquisition"), AppCore(TestPlatform(dir)).settings.storyTopics)
}

@Test
fun storyTopicsSyncButOlderDevicesDoNotWipeThem() {
    val core = AppCore(TestPlatform(tmp.newFolder("device")))
    core.settings.addStoryTopic("教育")
    val json = AppJson.encodeToString(Settings.Shared.serializer(), core.settings.shared())
    val legacy = AppJson.decodeFromString<Settings.Shared>(JsonObject(AppJson.parseToJsonElement(json).jsonObject - "storyTopics").toString())
    core.settings.applyShared(legacy)
    assertEquals(listOf("教育"), core.settings.storyTopics)
    core.settings.applyShared(legacy.copy(storyTopics = listOf("AI")))
    assertEquals(listOf("AI"), core.settings.storyTopics)
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :desktop:test --tests '*StoryComprehensionTest*'`
Expected: compile failure (`storyTopics` unresolved).

- [ ] **Step 3: Implement the Interfaces above in `Settings.kt`**

`shared()` sends `storyTopics = storyTopics`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :desktop:test --tests '*StoryComprehensionTest*'`
Expected: PASS (all tests in the class).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hanzilock/core/Settings.kt desktop/src/test/kotlin/com/hanzilock/desktop/StoryComprehensionTest.kt
git commit -m "Stories: save your own narrow-reading topics, shared between devices"
```

### Task 2: Nonfiction writing and the Narrow reading card

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hanzilock/stories/StoryClaude.kt` (`writeStory`, ~line 101)
- Modify: `shared/src/commonMain/kotlin/com/hanzilock/stories/StoryImporter.kt` (`write`, ~line 23)
- Modify: `shared/src/commonMain/kotlin/com/hanzilock/ui/stories/StoriesScreen.kt`

**Interfaces:**
- Consumes: Task 1's `storyTopics`, `addStoryTopic`, `removeStoryTopic`; `Settings.changes: StateFlow<Long>` to recompose when topics change.
- Produces:
  - `ClaudeGrader.writeStory(level, length, topic, avoidTitles, comprehension, nonfiction: Boolean = false)`
  - `StoryImporter.write(level, length, topic, comprehension, nonfiction: Boolean = false)`; source = `"$SOURCE_CLAUDE · $topic"` when `nonfiction`, else `SOURCE_CLAUDE`.

No new automated test: the prompt needs a live Claude call (no API key on the Mac, `ClaudeGrader` is final) and the card is UI. Verify with the build and a headless screenshot.

- [ ] **Step 1: Nonfiction brief in `writeStory`**

When `nonfiction`: system prompt says "graded reading texts" instead of "stories", and replaces the "Write an original story … characters, a small problem…" sentence with: an original short nonfiction text of about `$chars` characters on the topic, choosing one angle (an explainer, a news-style report, or an opinion piece) and a specific aspect not covered by the avoided titles; informative and natural, using the topic's everyday core vocabulary; short paragraphs of 1-4 sentences. `subject` is the topic as given (no random genre). All other rules unchanged.

- [ ] **Step 2: Pass `nonfiction` through `StoryImporter.write` and set the source as in Interfaces**

- [ ] **Step 3: `NarrowReadingCard` in `StoriesScreen.kt`**

`private fun NarrowReadingCard(topics: List<String>, level: Int, onRead: (String) -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit)`, placed as the `item` after `NewStoryCard`. Contents: title "Narrow reading"; body "Read text after text on one topic, so its words keep coming back. Tap a topic for a new text."; `FlowRow` of topic chips (tap = `onRead`, press and hold = `onRemove` via `combinedClickable`) plus a "+ Add topic" chip; when `topics` is empty only the add chip and "Add any topic you want to read about."; small line "HSK $level · medium length · press and hold a topic to remove it".

In `StoriesScreen`: `onRead = { t -> launcher.run("Claude is writing about $t…") { app.storyImporter.write(defaultLevel, StoryLength.MEDIUM, t, app.settings.storyComprehensionLevel, nonfiction = true) } }`. Add-topic `AlertDialog` with a single-line `OutlinedTextField` ("Topic", placeholder "AI, education, football…"), Add enabled when not blank, calls `addStoryTopic`. Remove asks `Remove "$t"?` with Remove / Cancel, then `removeStoryTopic`. Topics read as `remember(settingsVersion) { app.settings.storyTopics }` from `app.settings.changes.collectAsStateWithLifecycle()`.

- [ ] **Step 4: Build and run all desktop tests**

Run: `./gradlew :shared:compileKotlinDesktop :shared:desktopTest :desktop:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Headless screenshot of the card (throwaway test, per memory), empty and with two topics; delete the test after**

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hanzilock/stories/StoryClaude.kt shared/src/commonMain/kotlin/com/hanzilock/stories/StoryImporter.kt shared/src/commonMain/kotlin/com/hanzilock/ui/stories/StoriesScreen.kt docs/superpowers
git commit -m "Stories: narrow reading - one tap for a new text on your own topic"
```
