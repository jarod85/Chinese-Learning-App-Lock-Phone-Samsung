# Narrow reading in Stories

## Intent

Read many texts on a few niche topics (language learning, education, AI…) so the
topic's vocabulary comes back naturally from text to text and sticks.

## Goals

- Pick a topic and get a new Chinese text on it at your level with one tap.
- Keep a short list of your own topics, the same on phone and Mac.
- Texts are short nonfiction (explainer, news-style report, opinion piece), not fiction.

## Non-goals

- No deliberate carry-over of key words between texts (user chose "topic only").
- No filtering of the story list by topic, no new database column.
- No fetching real articles from the web.

## Approaches

1. Topic chips inside the existing "Write one for my level" dialog: smallest, but every text
   still goes through the dialog.
2. **Chosen:** its own "Narrow reading" card on the Stories screen; one tap on a topic writes
   a new piece right away.
3. Option 2 plus filtering the list by topic: needs a stories.db schema change and sync work.

## Design

- **Card** under "New story" on the Stories screen (shared Compose, so Mac and Android):
  title "Narrow reading", one line on what it does, the topics as chips, a **+** chip to
  add a topic (small dialog, one line of text), press and hold a topic to remove it (confirm
  dialog). A line shows the level used ("HSK 3, medium length").
- **Tap a topic:** the existing story launcher runs "Claude is writing about AI…" and opens
  the new text when ready, like "Write one for my level" does.
- **Level and length:** the last story level (`settings.storyLevel`, or the highest HSK set
  you have on, as the Write dialog already does); medium length; questions at your saved
  comprehension level.
- **Topics** are anything you type (any language, any subject), saved in
  `Settings.storyTopics`, part of the shared settings that sync between devices. The list
  starts empty: the card then shows only the **+** chip and a hint to add a topic.
- **Prompt:** `writeStory` gets a nonfiction mode: an original short nonfiction piece about
  the topic, varying the angle each time; same HSK vocabulary rules, sentence split,
  translations and questions. Recent titles are passed as now, so it doesn't repeat itself.
- **Source** of the saved text is "Claude · AI", so the story list shows the topic without
  a schema change (source already syncs).

## Left to the builder

Exact wording of the prompt and card text; chip styling.
