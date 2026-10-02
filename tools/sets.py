#!/usr/bin/env python3
"""Build and manage Lingo Lock word sets (content/sets).

    python tools/sets.py build                      # (re)build the built-in level sets for zh ja ko es fr it
    python tools/sets.py build zh ja                # only some languages
    python tools/sets.py import my_words.csv        # new custom set from a list of words (auto-filled)
    python tools/sets.py import food.csv --lang es --name "Food"
    python tools/sets.py add-language de            # download level sets for another language
    python tools/sets.py check                      # validate every set file

A set file holds one word per line. The app applies a set whenever its "rev" (a content hash)
changes, adding new words and updating words you haven't edited on the phone; your progress is
never touched. Source datasets are downloaded into .cache/sources (gitignored).
Standard library only; works with Python 3.7+.
"""
import argparse
import collections
import csv
import gzip
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTENT = os.path.join(ROOT, "content")
SETS_DIR = os.path.join(CONTENT, "sets")
INDEX = os.path.join(SETS_DIR, "index.json")
CORPUS_ZH = os.path.join(CONTENT, "corpus", "zh-en.tsv")
CACHE = os.path.join(ROOT, ".cache", "sources")
CURATED_HSK1 = os.path.join(ROOT, "tools", "data", "hsk1-curated.json")
CEDICT_GLOB_DIR = os.path.join(CONTENT, "dictionary")

# ---------------------------------------------------------------------------------------------
# Languages. script: han | japanese | hangul | latin | cyrillic | arabic | other.
# reading: pinyin | kana | none. rtl languages are written right to left.
LANGS = {
    "zh": dict(name="Mandarin Chinese", native="中文", locale="zh-CN", script="han", spaced=False, reading="pinyin"),
    "ja": dict(name="Japanese", native="日本語", locale="ja-JP", script="japanese", spaced=False, reading="kana"),
    "ko": dict(name="Korean", native="한국어", locale="ko-KR", script="hangul", spaced=True, reading="none"),
    "es": dict(name="Spanish", native="Español", locale="es-ES", script="latin", spaced=True, reading="none"),
    "fr": dict(name="French", native="Français", locale="fr-FR", script="latin", spaced=True, reading="none"),
    "it": dict(name="Italian", native="Italiano", locale="it-IT", script="latin", spaced=True, reading="none"),
    "de": dict(name="German", native="Deutsch", locale="de-DE", script="latin", spaced=True, reading="none"),
    "pt": dict(name="Portuguese", native="Português", locale="pt-BR", script="latin", spaced=True, reading="none"),
    "ru": dict(name="Russian", native="Русский", locale="ru-RU", script="cyrillic", spaced=True, reading="none"),
    "id": dict(name="Indonesian", native="Bahasa Indonesia", locale="id-ID", script="latin", spaced=True, reading="none"),
    "sw": dict(name="Swahili", native="Kiswahili", locale="sw-KE", script="latin", spaced=True, reading="none"),
    "ur": dict(name="Urdu", native="اردو", locale="ur-PK", script="arabic", spaced=True, reading="none", rtl=True),
    "fa": dict(name="Persian", native="فارسی", locale="fa-IR", script="arabic", spaced=True, reading="none", rtl=True),
    "he": dict(name="Hebrew", native="עברית", locale="he-IL", script="other", spaced=True, reading="none", rtl=True),
}
BUILT_IN = ["zh", "ja", "ko", "es", "fr", "it"]

# Where level sets come from, per language.
BANNERLESS = {"ko": "korean", "es": "spanish", "it": "italian", "de": "german", "ru": "russian",
              "id": "indonesian", "sw": "swahili", "ur": "urdu", "fa": "persian"}
NGRAM = {"fr": "french", "es": "spanish", "it": "italian", "de": "german", "ru": "russian", "he": "hebrew"}
TATOEBA = {"zh": "cmn", "ja": "jpn", "ko": "kor", "es": "spa", "fr": "fra", "it": "ita", "de": "deu", "pt": "por",
           "ru": "rus", "id": "ind", "sw": "swh", "ur": "urd", "fa": "pes", "he": "heb"}
JLPT_LEVELS = ["n5", "n4", "n3", "n2", "n1"]
LEVEL_LABEL = {"A1": "Beginner", "A2": "Beginner", "B1": "Intermediate", "B2": "Intermediate", "C1": "Advanced", "C2": "Advanced"}

# Attribution shown in the README / app About screen.
SOURCE_NOTES = {
    "hsk": "HSK word lists: github.com/drkameleon/complete-hsk-vocabulary (MIT); official HSK 2.0 pinyin: "
           "github.com/glxxyz/hskhsk.com (MIT)",
    "cedict": "CC-CEDICT (cc-cedict.org), CC BY-SA 4.0",
    "tatoeba": "Example sentences: Tatoeba (tatoeba.org) via manythings.org/anki, CC BY 2.0 FR",
    "jlpt": "JLPT vocabulary: github.com/evanclan/OpenJLPT, CC BY-SA 4.0",
    "bannerless": "A1-B1 word packs: github.com/Bannerless-Studio, CC BY-SA 4.0",
    "ngram": "Word frequencies + translations: github.com/orgtre/google-books-ngram-frequency, CC BY 3.0",
}


def log(msg):
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


# ---------------------------------------------------------------------------------------------
# Downloads

def fetch(url, name):
    """Downloads url into .cache/sources/name once and returns the local path."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    log("  downloading %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Lingo Lock set builder)"})
    with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as out:
        out.write(r.read())
    os.replace(path + ".part", path)
    return path


def src_hsk():
    return fetch("https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/main/complete.min.json",
                 "hsk-complete.min.json")


def src_hsk_official(level):
    """The official HSK 2.0 (2012) word lists with the intended pinyin (hskhsk.com, MIT)."""
    return fetch("https://raw.githubusercontent.com/glxxyz/hskhsk.com/main/data/lists/"
                 "HSK%%20Official%%20With%%20Definitions%%202012%%20L%d.txt" % level,
                 "hsk-official-2012-l%d.txt" % level)


def src_jlpt(level):
    return fetch("https://raw.githubusercontent.com/evanclan/OpenJLPT/main/data/json/vocab/%s.json" % level,
                 "openjlpt-%s.json" % level)


def src_bannerless(repo, what):
    return fetch("https://raw.githubusercontent.com/Bannerless-Studio/%s/main/pack/%s.json" % (repo, what),
                 "bannerless-%s-%s.json" % (repo, what))


def src_ngram(name):
    return fetch("https://raw.githubusercontent.com/orgtre/google-books-ngram-frequency/main/ngrams/1grams_%s.csv" % name,
                 "ngram-%s.csv" % name)


def src_tatoeba(iso3):
    return fetch("https://www.manythings.org/anki/%s-eng.zip" % iso3, "tatoeba-%s-eng.zip" % iso3)


def load_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def tatoeba_pairs(iso3):
    """[(target sentence, english)] from a manythings.org Tatoeba export, de-duplicated."""
    try:
        path = src_tatoeba(iso3)
    except Exception as e:  # not every language has an export
        log("  (no Tatoeba sentences for %s: %s)" % (iso3, e))
        return []
    with zipfile.ZipFile(path) as z:
        name = [n for n in z.namelist() if n.endswith(".txt") and not n.startswith("_")][0]
        text = z.read(name).decode("utf-8")
    seen = set()
    out = []
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        en, target = parts[0].strip(), parts[1].strip()
        if target and en and target not in seen:
            seen.add(target)
            out.append((target, en))
    return out


# ---------------------------------------------------------------------------------------------
# Pinyin (same rules as the app)

MARKS = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "v": "ǖǘǚǜ"}


def marked_syllable(numbered):
    capital = numbered[:1].isupper()
    s = numbered.lower().replace("u:", "v").replace("ü", "v")
    tone = int(s[-1]) if s[-1:].isdigit() else 5
    letters = s[:-1] if s[-1:].isdigit() else s
    idx = -1
    if 1 <= tone <= 4:
        for probe in ("a", "e", "ou"):
            if probe in letters:
                idx = letters.index(probe)
                break
        else:
            for i in range(len(letters) - 1, -1, -1):
                if letters[i] in "iouv":
                    idx = i
                    break
    if idx >= 0:
        letters = letters[:idx] + MARKS[letters[idx]][tone - 1] + letters[idx + 1:]
    out = letters.replace("v", "ü")
    return out[:1].upper() + out[1:] if capital else out


def numbered_to_marked(pinyin):
    return " ".join(marked_syllable(s) for s in pinyin.split() if any(c.isalpha() for c in s))


TONE_BASE = {m: ("v" if base == "v" else base) for base, marks in MARKS.items() for m in marks}
TONE_BASE["ü"] = "v"


def split_pinyin(reading, count, syllables):
    """'zhìlìyú' -> 'zhì lì yú': cuts run-together pinyin into exactly `count` known syllables."""
    word = reading.strip()
    base = "".join(TONE_BASE.get(c, c) for c in word.lower())
    if len(base) != len(word):
        return None
    memo = {}

    def cuts(i, k):
        if i == len(base):
            return [] if k == 0 else None
        if k == 0 or (i, k) in memo:
            return memo.get((i, k))
        for j in range(min(len(base), i + 6), i, -1):
            if base[i:j] in syllables:
                rest = cuts(j, k - 1)
                if rest is not None:
                    memo[(i, k)] = [j] + rest
                    return memo[(i, k)]
        memo[(i, k)] = None
        return None

    ends = cuts(0, count)
    if ends is None:
        return None
    starts = [0] + ends[:-1]
    return " ".join(word[a:b] for a, b in zip(starts, ends))


# ---------------------------------------------------------------------------------------------
# Text helpers

def is_han(c):
    return "CJK UNIFIED IDEOGRAPH" in unicodedata.name(c, "") or "CJK COMPATIBILITY IDEOGRAPH" in unicodedata.name(c, "")


def is_kana(c):
    n = unicodedata.name(c, "")
    return "HIRAGANA" in n or "KATAKANA" in n or c == "ー"


def is_hangul(c):
    return "HANGUL" in unicodedata.name(c, "")


def dedupe(items):
    seen = set()
    out = []
    for x in items:
        k = x.strip()
        if k and k.lower() not in seen:
            seen.add(k.lower())
            out.append(k)
    return out


def split_meanings(gloss, limit=4):
    """'change, shift' / 'to do; to make' -> ['change', 'shift'] (commas inside brackets kept)."""
    parts = []
    for chunk in re.split(r";|/", gloss):
        depth = 0
        cur = ""
        for ch in chunk:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth = max(0, depth - 1)
            if ch == "," and depth == 0:
                parts.append(cur)
                cur = ""
            else:
                cur += ch
        parts.append(cur)
    return dedupe(p.strip() for p in parts if p.strip())[:limit]


# Slang and archaic senses are only used when a word has nothing else (机场 is "airport", not a VPN service).
MINOR_SENSE = re.compile(r"^\((slang|internet slang|archaic|old|dialect)\)", re.I)


def clean_cedict_meanings(defs, limit=4):
    out = []
    for d in defs:
        d = d.strip()
        if not d or d.startswith("CL:") or d.startswith("surname ") or "variant of" in d or d.startswith("see "):
            continue
        d = re.sub(r"\s*\(CL:[^)]*\)", "", d)
        out.append(d)
    main = [d for d in out if not MINOR_SENSE.match(d)]
    return dedupe(main or out)[:limit] or dedupe(defs)[:limit]


def latin_tokens(s):
    return re.findall(r"[^\W\d_]+", s.lower())


def slug(text):
    s = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s or hashlib.sha1(text.encode("utf-8")).hexdigest()[:8]


# ---------------------------------------------------------------------------------------------
# Set files

def word_entry(term, meanings, reading=None, traditional=None, examples=None, forms=None, tags=None):
    e = collections.OrderedDict()
    e["term"] = term
    if traditional and traditional != term:
        e["traditional"] = traditional
    if reading:
        e["reading"] = reading
    e["meanings"] = meanings
    if examples:
        e["examples"] = [collections.OrderedDict((k, v) for k, v in ex.items() if v) for ex in examples]
    if forms:
        e["forms"] = forms
    if tags:
        e["tags"] = tags
    return e


def write_set(path, key, lang, name, words):
    """Writes one word per line; returns the content revision (hash of the words)."""
    lines = [json.dumps(w, ensure_ascii=False, separators=(",", ":")) for w in words]
    rev = hashlib.sha1("\n".join(lines).encode("utf-8")).hexdigest()[:12]
    out = ["{", '  "key": %s,' % json.dumps(key), '  "lang": %s,' % json.dumps(lang),
           '  "name": %s,' % json.dumps(name, ensure_ascii=False), '  "rev": %s,' % json.dumps(rev), '  "words": [']
    for i, line in enumerate(lines):
        out.append("    " + line + ("," if i < len(lines) - 1 else ""))
    out += ["  ]", "}", ""]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    return rev


def load_index():
    if os.path.exists(INDEX):
        return load_json(INDEX)
    return {"languages": [], "sets": []}


def save_index(index):
    index["languages"].sort(key=lambda l: (BUILT_IN.index(l["code"]) if l["code"] in BUILT_IN else 99, l["code"]))
    index["sets"].sort(key=lambda s: (s["lang"], s.get("custom", False), s["sort"], s["key"]))
    lines = ["{", '  "languages": [']
    for i, lang in enumerate(index["languages"]):
        lines.append("    " + json.dumps(lang, ensure_ascii=False, separators=(",", ":")) +
                     ("," if i < len(index["languages"]) - 1 else ""))
    lines += ["  ],", '  "sets": [']
    for i, s in enumerate(index["sets"]):
        lines.append("    " + json.dumps(s, ensure_ascii=False, separators=(",", ":")) + ("," if i < len(index["sets"]) - 1 else ""))
    lines += ["  ]", "}", ""]
    with io.open(INDEX, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def register_language(index, code):
    meta = LANGS.get(code)
    if meta is None:
        sys.exit("Unknown language code %r - add it to LANGS in tools/sets.py (name, native name, locale)." % code)
    profile = collections.OrderedDict([("code", code)])
    for k in ("name", "native", "locale", "script", "spaced", "reading"):
        profile[k] = meta[k]
    if meta.get("rtl"):
        profile["rtl"] = True
    index["languages"] = [l for l in index["languages"] if l["code"] != code] + [profile]


def register_set(index, key, lang, name, level, sort, rel_file, count, rev, enabled=False, custom=False, source=None):
    entry = collections.OrderedDict([("key", key), ("lang", lang), ("name", name), ("level", level), ("sort", sort),
                                     ("file", rel_file), ("count", count), ("rev", rev)])
    if enabled:
        entry["enabled"] = True
    if custom:
        entry["custom"] = True
    if source:
        entry["source"] = source
    index["sets"] = [s for s in index["sets"] if s["key"] != key] + [entry]


def drop_sets(index, lang, keep_custom=True):
    index["sets"] = [s for s in index["sets"] if s["lang"] != lang or (keep_custom and s.get("custom"))]


def load_set_words(lang):
    """term -> word entry for every set of a language (used to auto-fill imports)."""
    out = {}
    for s in load_index()["sets"]:
        if s["lang"] != lang:
            continue
        path = os.path.join(CONTENT, s["file"])
        if os.path.exists(path):
            for w in load_json(path)["words"]:
                out.setdefault(w["term"], w)
    return out


# ---------------------------------------------------------------------------------------------
# CC-CEDICT

class Cedict(object):
    def __init__(self):
        path = [os.path.join(CEDICT_GLOB_DIR, f) for f in sorted(os.listdir(CEDICT_GLOB_DIR)) if f.endswith(".gz")][0]
        self.entries = collections.defaultdict(list)
        self.t2s = {}
        self.syllables = set()
        pat = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                if line.startswith("#"):
                    continue
                m = pat.match(line)
                if not m:
                    continue
                trad, simp, pinyin, defs = m.groups()
                self.entries[simp].append((trad, pinyin, [d for d in defs.split("/") if d]))
                for syl in pinyin.lower().replace("u:", "v").split():
                    letters = syl.rstrip("012345")
                    if letters.isalpha():
                        self.syllables.add(letters)
                if len(trad) == 1 and len(simp) == 1 and trad != simp:
                    self.t2s.setdefault(trad, simp)
        self.words = set(self.entries)

    def to_simplified(self, text):
        return "".join(self.t2s.get(c, c) for c in text)

    def best(self, term):
        cands = self.entries.get(term)
        if not cands:
            return None
        return sorted(cands, key=lambda e: sum(1 for d in e[2] if d.startswith("surname") or "variant of" in d))[0]

    def segment_around(self, sentence, target):
        """Segmentation that keeps `target` whole: 念 几 年级 (plain max-match gives 念 几年 级)."""
        i = sentence.find(target)
        if i < 0:
            return self.segment(sentence, target)
        parts = [p for p in (self.segment(sentence[:i], target), target, self.segment(sentence[i + len(target):], target)) if p]
        out = []
        for t in " ".join(parts).split(" "):
            if out and all(unicodedata.category(c).startswith("P") for c in t):
                out[-1] += t
            else:
                out.append(t)
        return " ".join(out)

    def segment(self, sentence, target):
        """Forward maximum matching; punctuation is glued to the previous word."""
        out = []
        i = 0
        while i < len(sentence):
            if target and sentence.startswith(target, i):
                out.append(target)
                i += len(target)
                continue
            c = sentence[i]
            if not is_han(c):
                if out and not c.isspace():
                    out[-1] += c
                elif not c.isspace():
                    out.append(c)
                i += 1
                continue
            n = min(4, len(sentence) - i)
            while n > 1 and sentence[i:i + n] not in self.words:
                n -= 1
            out.append(sentence[i:i + n])
            i += n
        return " ".join(out)


# ---------------------------------------------------------------------------------------------
# Mandarin: HSK 2.0 levels 1-6

def zh_corpus(cedict):
    pairs = []
    seen = set()
    for zh, en in tatoeba_pairs("cmn"):
        s = cedict.to_simplified(zh)
        if re.search(r"[A-Za-z0-9]", s) or s in seen or len(s) > 40:
            continue
        seen.add(s)
        pairs.append((s, en))
    return pairs


class ZhExamples(object):
    def __init__(self, corpus):
        self.corpus = corpus
        self.by_char = collections.defaultdict(list)
        for i, (s, _) in enumerate(corpus):
            for c in set(s):
                if is_han(c):
                    self.by_char[c].append(i)

    def pick(self, term, meanings, known_chars, cedict, count=2):
        """Short sentences using the word, easy characters first.

        Best are sentences whose segmentation has the word as a token. Plain max-match also splits some
        real uses (几年级 -> 几年 级), so a sentence that only contains the characters is accepted when
        its English translation confirms the meaning - that keeps 年级 in 念几年级 but drops 当地 in
        相当地 ("quite") and 亲爱 in 父亲爱.
        """
        if not term or not is_han(term[0]):
            return []
        keys = meaning_keys(meanings)
        scored = []
        for i in self.by_char.get(term[0], ()):
            s, en = self.corpus[i]
            if term not in s:
                continue
            han = [c for c in s if is_han(c)]
            if len(han) < len(term) + 2 or len(han) > 30:
                continue
            # Punctuation is glued to the token before it ("年级？").
            tokens = ["".join(c for c in t if not unicodedata.category(c).startswith("P")) for t in cedict.segment(s, None).split(" ")]
            standalone = term in tokens
            if not standalone and not mentions(en, keys):
                continue
            unknown = sum(1 for c in han if c not in known_chars)
            scored.append((0 if standalone else 1, unknown, abs(len(han) - 10), i))
        scored.sort()
        return [{"text": cedict.segment_around(self.corpus[i][0], term), "en": self.corpus[i][1]} for *_, i in scored[:count]]


MEANING_STOPWORDS = set("""to the a an of and or in on at for with by be is are was one one's oneself someone something sb sth
    etc from as up out off into over used also very more most kind sort some any this that these those have has make
    get""".split())


def meaning_keys(meanings):
    """Content words of the English meanings, cut to 5 letters as a crude stem (sympathize ~ sympathy)."""
    keys = set()
    for m in meanings:
        for w in re.findall(r"[a-z]+", re.sub(r"\([^)]*\)", " ", m.lower())):
            if len(w) >= 4 and w not in MEANING_STOPWORDS:
                keys.add(w[:5])
    return keys


def mentions(english, keys):
    return any(w[:5] in keys for w in re.findall(r"[a-z]+", (english or "").lower()) if len(w) >= 4)


def curated_hsk1():
    out = collections.OrderedDict()
    for w in load_json(CURATED_HSK1)["words"]:
        out[w["hanzi"]] = word_entry(
            w["hanzi"], w["meanings"], reading=w["pinyin"], traditional=w.get("traditional"),
            examples=[{"text": ex["zh"], "reading": ex.get("pinyin"), "en": ex.get("en")} for ex in w.get("examples", [])],
            tags=w.get("tags"))
    return out


def pinyin_key(numbered):
    """'Chang2 zhang3' / "nv3'er2" / 'lu:4' / 'ba5' / 'ba' -> 'chang2zhang3', 'nv3er2', 'lv4', 'ba', 'ba'."""
    return re.sub(r"[\s5']", "", numbered.lower().replace("u:", "v").replace("\u00fc", "v"))


def syllable_key(marked):
    """'dào' -> ('dao', 4), 'lǜ' -> ('lv', 4), 'zi' -> ('zi', 5)."""
    tone, letters = 5, ""
    for c in marked.lower():
        base = TONE_BASE.get(c)
        if base is not None and c != "\u00fc":
            tone = MARKS[base].index(c) + 1
        letters += base or c
    return letters, tone


def official_variants(term, reading, wants):
    """The official HSK pinyin where it differs from the dictionary's only in tones - mostly neutral tones
    (知道 zhīdao for zhī dào, 太阳 tàiyáng for tài yang) - so typing either is right. Tone sandhi of 一/不 is
    left out: the app accepts it anyway."""
    ours = [syllable_key(s) for s in reading.split()]
    out = []
    for want in wants:
        pos, alt = 0, []
        for letters, _ in ours:
            if want[pos:pos + len(letters)] != letters:
                alt = None
                break
            pos += len(letters)
            tone = 5
            if pos < len(want) and want[pos].isdigit():
                tone, pos = int(want[pos]), pos + 1
            alt.append((letters, tone))
        if alt is None or pos != len(want) or alt == ours:
            continue
        aligned = len(term) == len(ours)
        if aligned and all(a == b or term[i] in "\u4e00\u4e0d" for i, (a, b) in enumerate(zip(alt, ours))):
            continue
        variant = " ".join(marked_syllable(letters + str(tone)) for letters, tone in alt)
        if variant not in out:
            out.append(variant)
    return out


def official_hsk_readings():
    """Simplified word -> (traditional, [numbered pinyin]) from the official HSK list (a few words have two: 长)."""
    out = {}
    for level in range(1, 7):
        with io.open(src_hsk_official(level), encoding="utf-8-sig") as f:
            for line in f:
                cells = line.rstrip("\r\n").split("\t")
                if len(cells) >= 3 and cells[0]:
                    out.setdefault(cells[0], (cells[1], [pinyin_key(x) for x in cells[2].split(",") if x.strip()]))
    return out


def minor_form(meanings):
    """A dictionary reading nobody means by the HSK word: only a surname, place, variant or archaic sense."""
    return not any(not (MINOR_SENSE.match(d) or "variant of" in d or d.startswith(
        ("surname ", "used in ", "see ", "(onom.)", "abbr. for", "Japanese variant", "erhua variant", "(classical)",
         "(literary)"))) for d in meanings)


def choose_form(term, forms, official):
    """The HSK data lists every CC-CEDICT reading of a word in dictionary order (吧: bā "bar", ba, biā; 还: Huán
    the surname, hái, huán). Take the reading the official HSK list gives - among several entries with that
    reading, the one with its traditional form (钟 鐘 "clock", not 鍾 "goblet") and not a surname. Failing that,
    skip proper nouns and readings with only surname/variant/archaic senses, and for a single character prefer
    its particle use (吧 ba)."""
    trad, wants = official or (None, [])

    def proper(f):
        return f["i"].get("y", "")[:1].isupper()

    def rank(f):
        return proper(f), minor_form(f.get("m", [])), trad is not None and f.get("t") != trad, -len(f.get("m", []))

    good = [f for f in forms if not proper(f) and not minor_form(f.get("m", []))] or forms
    for want in wants:
        matching = [f for f in forms if pinyin_key(f["i"].get("n", "")) == want]
        best = min(matching, key=rank) if matching else None
        # The list occasionally gives a reading that only exists in compounds (俩 liǎng, "used in 伎俩").
        if best is not None and (best in good or good is forms):
            return best
    if len(term) == 1:
        for f in good:
            if any("particle" in m or "marker" in m for m in f.get("m", [])):
                return f
    return good[0]


# Readings for HSK words that aren't in the official list and have several plausible dictionary entries.
ZH_FORM_HINTS = {
    "钟": ("鐘", ["zhong1"]),  # clock, not 鍾 "goblet"
    "弹": (None, ["tan2"]),  # to play (an instrument), not dàn "bullet"
    "分量": (None, ["fen4liang"]),  # weight, quantity
}

# Source entries whose English gloss is unusable as a quiz answer.
ZH_MEANING_FIXES = {
    "致力于": ["to devote oneself to", "to be committed to"],
}


def build_zh(index):
    log("Mandarin (HSK 2.0 levels 1-6)")
    cedict = Cedict()
    hsk = load_json(src_hsk())
    corpus = zh_corpus(cedict)
    os.makedirs(os.path.dirname(CORPUS_ZH), exist_ok=True)
    with io.open(CORPUS_ZH, "w", encoding="utf-8", newline="\n") as f:
        for s, en in sorted(corpus, key=lambda p: len(p[0])):
            f.write("%s\t%s\n" % (s, en.replace("\t", " ")))
    examples = ZhExamples(corpus)
    curated = curated_hsk1()
    official = official_hsk_readings()

    by_level = collections.defaultdict(list)
    for e in hsk:
        levels = [int(l[1:]) for l in e.get("l", []) if re.match(r"^o[1-6]$", l)]
        if levels:
            by_level[min(levels)].append(e)
    drop_sets(index, "zh")
    register_language(index, "zh")
    known = set()
    placed = set()
    for level in range(1, 7):
        entries = sorted(by_level[level], key=lambda e: e.get("q", 10 ** 9))
        for e in entries:
            known.update(c for c in e["s"] if is_han(c))
        words = []
        for e in entries:
            term = e["s"]
            if term in placed:
                continue
            placed.add(term)
            hint = ZH_FORM_HINTS.get(term) or official.get(term)
            if term in curated:
                w = curated[term]
                alts = official_variants(term, w.get("reading", ""), hint[1] if hint else [])
                if alts:
                    w = word_entry(w["term"], w["meanings"], reading=w.get("reading"), traditional=w.get("traditional"),
                                   examples=w.get("examples"), forms=alts, tags=w.get("tags"))
                words.append(w)
                continue
            form = choose_form(term, e["f"], hint)
            meanings = ZH_MEANING_FIXES.get(term) or clean_cedict_meanings(form.get("m", []))
            reading = numbered_to_marked(form["i"].get("n", "")) or form["i"].get("y", "")
            han = sum(1 for c in term if is_han(c))
            if han == len(term) and len(reading.split()) != han:
                # A few source entries write the pinyin run together ("zhìlìyú").
                reading = split_pinyin(reading.replace(" ", ""), han, cedict.syllables) or reading
            words.append(word_entry(
                term, meanings, reading=reading, traditional=form.get("t"),
                examples=examples.pick(term, meanings, known, cedict),
                forms=official_variants(term, reading, hint[1] if hint else []), tags=["HSK%d" % level]))
        if level == 1:
            words += [w for t, w in curated.items() if t not in placed]
            placed.update(curated)
        rel = "sets/zh/hsk%d.json" % level
        rev = write_set(os.path.join(CONTENT, rel), "zh.hsk%d" % level, "zh", "HSK %d" % level, words)
        label = "Beginner" if level <= 2 else "Intermediate" if level <= 4 else "Advanced"
        register_set(index, "zh.hsk%d" % level, "zh", "HSK %d" % level, label, level, rel, len(words), rev,
                     enabled=(level == 1), source="hsk")
        with_ex = sum(1 for w in words if w.get("examples"))
        log("  HSK %d: %d words (%d with example sentences)" % (level, len(words), with_ex))


# ---------------------------------------------------------------------------------------------
# Japanese: JLPT N5-N1

def clean_kana(reading):
    reading = re.sub(r"[（(][^）)]*[）)]", "", reading or "")
    return "".join(c for c in reading if is_kana(c))


JA_PARTICLES = set("はがをにでとものへよねかな")


def ja_uses(sentence, term):
    """True if the sentence uses the word - a kana-only word must not just be part of a longer word."""
    if not all(is_kana(c) for c in term):
        return term in sentence
    start = sentence.find(term)
    while start >= 0:
        before = sentence[start - 1] if start > 0 else ""
        after = sentence[start + len(term)] if start + len(term) < len(sentence) else ""
        hira = lambda c: "HIRAGANA" in unicodedata.name(c, "") if c else False
        if (not hira(before) or before in JA_PARTICLES) and (not hira(after) or after in JA_PARTICLES):
            return True
        start = sentence.find(term, start + 1)
    return False


def build_ja(index):
    log("Japanese (JLPT N5-N1)")
    drop_sets(index, "ja")
    register_language(index, "ja")
    seen = set()
    for n, level in enumerate(JLPT_LEVELS):
        words = []
        for e in load_json(src_jlpt(level)):
            term = (e.get("word") or "").strip()
            if not term or term in seen:
                continue
            seen.add(term)
            reading = clean_kana(e.get("reading", ""))
            if reading == term:
                reading = ""
            exs = [x for x in e.get("examples", []) if ja_uses(x.get("ja", ""), term) or (reading and ja_uses(x.get("ja", ""), reading))]
            meanings = dedupe(m for chunk in e.get("meanings", []) for m in split_meanings(chunk, 8))[:4]
            if not meanings:
                continue
            words.append(word_entry(term, meanings, reading=reading,
                                    examples=[{"text": x["ja"], "en": x.get("en")} for x in exs[:2]],
                                    tags=["JLPT %s" % level.upper()]))
        name = "JLPT %s" % level.upper()
        rel = "sets/ja/jlpt-%s.json" % level
        rev = write_set(os.path.join(CONTENT, rel), "ja.jlpt-%s" % level, "ja", name, words)
        label = ["Beginner", "Beginner", "Intermediate", "Advanced", "Advanced"][n]
        register_set(index, "ja.jlpt-%s" % level, "ja", name, label, n + 1, rel, len(words), rev, source="jlpt")
        log("  %s: %d words (%d with examples)" % (name, len(words), sum(1 for w in words if w.get("examples"))))


# ---------------------------------------------------------------------------------------------
# Bannerless A1-B1 packs (Korean, Spanish, Italian, German, Russian, ...)

def build_bannerless(index, lang, repo, sort_start=1):
    """Adds <lang>.a1/.a2/.b1 and returns every known form (lower-case) so frequency levels skip them."""
    words_json = load_json(src_bannerless(repo, "words"))
    sentences = load_json(src_bannerless(repo, "sentences"))
    by_word = collections.defaultdict(list)
    for s in sentences:
        for wid in set(s.get("words", [])):
            by_word[wid].append(s)
    lemma_of = {w["id"]: (w.get("lemma") or w["w"]).strip() for w in words_json}

    def written(s, wid):
        """How the word is written in the sentence ("es" for ser, "tengo" for tener)."""
        out = []
        for span in s.get("spans", []):
            if len(span) >= 3 and span[2] == wid:
                form = s["t"][span[0]:span[1]].strip()
                # Only the capital at the start of a sentence is dropped (German nouns keep theirs).
                initial = not any(c.isalpha() for c in s["t"][:span[0]])
                if initial and form[:1].isupper() and lemma_of.get(wid, "")[:1].islower():
                    form = form[:1].lower() + form[1:]
                out.append(form)
        return out

    order = {"A1": 1, "A2": 2, "B1": 3, "B2": 4}
    # Homographs (same spelling, different part of speech) become one word with all the meanings,
    # filed under the easiest level they appear in.
    merged = collections.OrderedDict()
    for w in sorted(words_json, key=lambda w: (order.get(w.get("lv"), 9), w.get("rank", 10 ** 6))):
        term = w["w"].strip()
        if w.get("lv") not in ("A1", "A2", "B1") or not term:
            continue
        entry = merged.setdefault(term, {"level": w["lv"], "meanings": [], "forms": [], "ids": []})
        entry["meanings"] += split_meanings(w.get("en", ""))
        entry["forms"] += [(w.get("lemma") or term).strip()] + list(w.get("alt", [])) + list(w.get("forms", []))
        entry["ids"].append(w["id"])
    known = set()
    sort = sort_start
    for level in ("A1", "A2", "B1"):
        words = []
        for term, entry in merged.items():
            if entry["level"] != level:
                continue
            meanings = dedupe(entry["meanings"])[:5]
            sentences_for = [s for wid in entry["ids"] for s in by_word.get(wid, [])]
            exs = sorted({s["id"]: s for s in sentences_for}.values(),
                         key=lambda s: (order.get(s.get("lv"), 9) > order[level], len(s["t"])))
            # Up to 24 forms: the pack's own, then how the word is written in its sentences. The forms in the
            # two examples shown are always kept, so the sentence check recognises "es" as ser.
            in_examples = [f for s in exs[:2] for wid in entry["ids"] for f in written(s, wid)]
            elsewhere = [f for s in exs[2:] for wid in entry["ids"] for f in written(s, wid)]
            must = set(f.lower() for f in in_examples)
            ordered = [f for f in dedupe(entry["forms"] + in_examples + elsewhere) if f.lower() != term.lower()]
            room = 24 - sum(1 for f in ordered if f.lower() in must)
            forms = []
            for f in ordered:
                if f.lower() in must:
                    forms.append(f)
                elif room > 0:
                    forms.append(f)
                    room -= 1
            known.update(x.lower() for x in [term] + forms)
            if not meanings:
                continue
            words.append(word_entry(term, meanings, examples=[{"text": s["t"], "en": s.get("en")} for s in exs[:2]],
                                    forms=forms, tags=[level]))
        rel = "sets/%s/%s.json" % (lang, level.lower())
        rev = write_set(os.path.join(CONTENT, rel), "%s.%s" % (lang, level.lower()), lang, level, words)
        register_set(index, "%s.%s" % (lang, level.lower()), lang, level, LEVEL_LABEL[level], sort, rel, len(words), rev,
                     source="bannerless")
        sort += 1
        log("  %s %s: %d words (%d with examples)" % (lang, level, len(words), sum(1 for w in words if w.get("examples"))))
    return known


# ---------------------------------------------------------------------------------------------
# Frequency-ranked levels (French, and B2/C1 on top of the A1-B1 packs)

class TatoebaIndex(object):
    def __init__(self, pairs, rank):
        self.pairs = pairs
        self.rank = rank
        self.by_token = collections.defaultdict(list)
        for i, (s, _) in enumerate(pairs):
            for t in set(latin_tokens(s)):
                self.by_token[t].append(i)

    def pick(self, word, count=2):
        scored = []
        for i in self.by_token.get(word.lower(), ()):
            toks = latin_tokens(self.pairs[i][0])
            if not 3 <= len(toks) <= 12:
                continue
            difficulty = max(self.rank.get(t, 20000) for t in toks)
            scored.append((difficulty, len(self.pairs[i][0]), i))
        scored.sort()
        return [{"text": self.pairs[i][0], "en": self.pairs[i][1]} for _, _, i in scored[:count]]


def build_frequency(index, lang, bands, exclude=frozenset(), sort_start=1):
    rows = []
    with io.open(src_ngram(NGRAM[lang]), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append((row["ngram"].strip(), (row.get("en") or "").strip()))
    rank = {w.lower(): i for i, (w, _) in enumerate(rows)}
    entries = []
    for w, en in rows:
        if not w or not en or w[:1].isupper() or w.lower() in exclude:
            continue
        if not re.match(r"^[^\W\d_]+(?:['’-][^\W\d_]+)*$", w):
            continue
        if en.lower() == "nan":
            continue
        entries.append((w, en))
    tatoeba = TatoebaIndex(tatoeba_pairs(TATOEBA[lang]), rank)
    pos = 0
    sort = sort_start
    for level, count in bands:
        chunk = entries[pos:pos + count]
        pos += count
        words = [word_entry(w, split_meanings(en), examples=tatoeba.pick(w), tags=[level]) for w, en in chunk]
        rel = "sets/%s/%s.json" % (lang, level.lower())
        rev = write_set(os.path.join(CONTENT, rel), "%s.%s" % (lang, level.lower()), lang, level, words)
        register_set(index, "%s.%s" % (lang, level.lower()), lang, level, LEVEL_LABEL[level], sort, rel, len(words), rev,
                     source="ngram")
        sort += 1
        log("  %s %s: %d words (%d with examples)" % (lang, level, len(words), sum(1 for w in words if w.get("examples"))))


def build_european(index, lang):
    meta = LANGS[lang]
    log("%s (levels %s)" % (meta["name"], "A1-B1" if lang in BANNERLESS and lang not in NGRAM else "A1-C1"))
    drop_sets(index, lang)
    register_language(index, lang)
    if lang in BANNERLESS:
        known = build_bannerless(index, lang, BANNERLESS[lang])
        if lang in NGRAM:
            build_frequency(index, lang, [("B2", 1500), ("C1", 2000)], exclude=known, sort_start=4)
    elif lang in NGRAM:
        build_frequency(index, lang, [("A1", 600), ("A2", 700), ("B1", 700), ("B2", 1500), ("C1", 2000)])
    else:
        sys.exit("No level-set source known for %s." % lang)


def cmd_build(args):
    langs = args.langs or BUILT_IN
    index = load_index()
    for lang in langs:
        if lang == "zh":
            build_zh(index)
        elif lang == "ja":
            build_ja(index)
        else:
            build_european(index, lang)
        save_index(index)
    log("Index: %s" % os.path.relpath(INDEX, ROOT))


def cmd_add_language(args):
    code = args.code.lower()
    if code in ("zh", "ja"):
        return cmd_build(argparse.Namespace(langs=[code]))
    if code not in BANNERLESS and code not in NGRAM:
        sys.exit("No downloadable level sets known for %r. Known: %s. You can still add the language in the app "
                 "and import your own lists." % (code, ", ".join(sorted(set(BANNERLESS) | set(NGRAM) | {"zh", "ja"}))))
    index = load_index()
    build_european(index, code)
    save_index(index)
    log("Added %s. Rebuild and install the app (tools/install.ps1)." % LANGS[code]["name"])


# ---------------------------------------------------------------------------------------------
# Importing a list of words as a custom set (drag a CSV onto add-words.cmd)

TERM_HEADERS = {"word", "words", "term", "hanzi", "chinese", "character", "characters", "kanji", "japanese", "korean",
                "hangul", "vocab", "vocabulary", "expression", "spanish", "french", "italian", "german", "portuguese",
                "russian"}
READING_HEADERS = {"pinyin", "reading", "kana", "furigana", "hiragana", "pronunciation", "romaji"}
MEANING_HEADERS = {"meaning", "meanings", "english", "definition", "definitions", "translation", "gloss"}
TONE_MARKED = re.compile("[" + "".join(TONE_BASE) + "]")


def has_cjk(s):
    return any(is_han(c) or is_kana(c) or is_hangul(c) for c in s)


def kana_only(s):
    return bool(s) and all(is_kana(c) or c == "ー" for c in s)


def can_split_pinyin(chunk, syllables):
    """True if toneless or tone-marked/numbered pinyin like "xuexi" / "xue2xi2" is made of real syllables."""
    base = re.sub(r"[1-5]", "", "".join(TONE_BASE.get(c, c) for c in chunk.lower()))
    ok = [True] + [False] * len(base)
    for i in range(1, len(base) + 1):
        ok[i] = any(ok[j] and base[j:i] in syllables for j in range(max(0, i - 6), i))
    return bool(base) and ok[-1]


def looks_like_pinyin(s, syllables=None):
    low = s.lower().strip()
    if TONE_MARKED.search(low) or re.search(r"[a-zü][1-5](?:[\s']|$|[a-z])", low):
        return True
    chunks = [c for c in re.split(r"[\s']+", low) if c]
    return bool(syllables) and bool(chunks) and all(can_split_pinyin(c, syllables) for c in chunks)


def parse_rows(text, syllables=None):
    """(word, reading, meaning) rows from a CSV / TSV / plain list, by the same rules as the app's importer:
    a header row can name the columns (word / pinyin or reading / meaning or english); a Pleco export or TSV
    is word<TAB>reading<TAB>meaning; otherwise it's just words, one per line or comma-separated."""
    lines = [l.strip().lstrip("﻿").strip() for l in text.splitlines()]
    lines = [l for l in lines if l and not l.startswith("#") and not l.startswith("//")]
    if not lines:
        return []

    def cells(line):
        return [c.strip() for c in (line.split("\t") if "\t" in line else next(csv.reader([line])))]

    if len(cells(lines[0])) == 1 and lines[0].lower() in TERM_HEADERS:
        lines = lines[1:]  # a one-column list with a heading ("Hanzi")
        if not lines:
            return []
    rows = []
    header = [c.lower() for c in cells(lines[0])]
    term_col = next((i for i, h in enumerate(header) if h in TERM_HEADERS), -1)
    if term_col >= 0 and len(header) >= 2:
        read_col = next((i for i, h in enumerate(header) if h in READING_HEADERS), -1)
        mean_col = next((i for i, h in enumerate(header) if h in MEANING_HEADERS), -1)
        for line in lines[1:]:
            row = cells(line)

            def cell(i):
                return row[i] if 0 <= i < len(row) and row[i] else None

            if cell(term_col):
                rows.append((cell(term_col), cell(read_col), cell(mean_col)))
    else:
        for line in lines:
            row = [c for c in cells(line) if c]
            if not row:
                continue
            cjk = has_cjk(row[0])
            if "\t" in line or (len(row) >= 2 and cjk and all(not has_cjk(c) or kana_only(c) for c in row[1:])):
                rest, reading = row[1:], None
                if cjk and rest and (kana_only(rest[0]) or looks_like_pinyin(rest[0], syllables)):
                    reading, rest = rest[0], rest[1:]
                rows.append((row[0], reading, "; ".join(rest) or None))
                continue
            for c in row:
                parts = re.split(r"[\s、，；;/|]+", c) if has_cjk(c) else re.split(r"[；;/|]+", c)
                rows.extend((part.strip(), None, None) for part in parts if part.strip())
    out, seen = [], set()
    for term, reading, meaning in rows:
        m = re.match(r"^([^\[]+)\[([^\]]+)\]$", term)  # Pleco headword 学习[學習]
        if m and has_cjk(m.group(1)):
            term = m.group(1).strip()
        if term.lower() not in seen:
            seen.add(term.lower())
            out.append((term, reading, meaning))
    return out


def detect_language(terms):
    text = "".join(terms)
    if any(is_kana(c) for c in text):
        return "ja"
    if any(is_hangul(c) for c in text):
        return "ko"
    if any(is_han(c) for c in text):
        return "zh"
    return None


def import_set_file(args, data):
    """A set exported from the phone (Words > Sets > Export) or an old registry file: kept as it is."""
    lang = (args.lang or data.get("lang") or "zh").lower()
    if lang not in LANGS:
        sys.exit("Unknown language %s - pass --lang." % lang)
    name = args.name or data.get("name") or os.path.splitext(os.path.basename(args.file))[0]
    words = []
    for w in data.get("words", []):
        term = (w.get("term") or w.get("hanzi") or "").strip()
        if term:
            examples = [{"text": x.get("text") or x.get("zh"), "reading": x.get("reading") or x.get("pinyin"), "en": x.get("en")}
                        for x in w.get("examples", []) if x.get("text") or x.get("zh")]
            words.append(word_entry(term, w.get("meanings", []), reading=w.get("reading") or w.get("pinyin"),
                                    traditional=w.get("traditional"),
                                    examples=[{k: v for k, v in x.items() if v} for x in examples],
                                    forms=w.get("forms"), tags=w.get("tags")))
    return lang, name, words


def cmd_import(args):
    with io.open(args.file, encoding="utf-8-sig") as f:
        text = f.read()
    if text.lstrip().startswith("{"):
        lang, name, words = import_set_file(args, json.loads(text))
        save_custom_set(lang, name, words)
        return
    rows = parse_rows(text)
    if not rows:
        sys.exit("No words found in %s" % args.file)
    lang = (args.lang or detect_language([t for t, _, _ in rows]) or "").lower()
    if lang not in LANGS and sys.stdin.isatty():
        print("Which language are these words? " + ", ".join("%s = %s" % (c, LANGS[c]["name"]) for c in sorted(LANGS)))
        lang = input("Language code: ").strip().lower()
    if lang not in LANGS:
        sys.exit("Couldn't tell the language of these words - pass --lang (e.g. --lang es).")
    name = args.name or os.path.splitext(os.path.basename(args.file))[0].replace("_", " ").strip().capitalize()
    known = load_set_words(lang)
    cedict = Cedict() if lang == "zh" else None
    if cedict is not None:
        rows = parse_rows(text, cedict.syllables)  # recognises a toneless pinyin column too
    zh_examples = None
    if lang == "zh" and os.path.exists(CORPUS_ZH):
        with io.open(CORPUS_ZH, encoding="utf-8") as f:
            zh_examples = ZhExamples([tuple(l.rstrip("\n").split("\t", 1)) for l in f if "\t" in l])
    ngram = {}
    if lang in NGRAM:
        with io.open(src_ngram(NGRAM[lang]), encoding="utf-8") as f:
            for row in csv.DictReader(f):
                ngram.setdefault(row["ngram"].strip().lower(), (row.get("en") or "").strip())
    tatoeba = None
    words = []
    missing = []
    for term, reading, meaning in rows:
        given = split_meanings(meaning, 5) if meaning else []  # meanings in the file win over the dictionary's
        if term in known:
            w = collections.OrderedDict((k, v) for k, v in known[term].items() if k != "tags")
            words.append(w)
            continue
        if cedict is not None:
            e = cedict.best(term)
            if e:
                meanings = given or clean_cedict_meanings(e[2])
                exs = zh_examples.pick(term, meanings, set(), cedict) if zh_examples else []
                words.append(word_entry(term, meanings, reading=numbered_to_marked(e[1]), traditional=e[0], examples=exs))
                continue
        if given or ngram.get(term.lower()):
            if tatoeba is None and lang in TATOEBA:
                tatoeba = TatoebaIndex(tatoeba_pairs(TATOEBA[lang]), {})
            words.append(word_entry(term, given or split_meanings(ngram[term.lower()]), reading=reading,
                                    examples=tatoeba.pick(term) if tatoeba else []))
            continue
        missing.append(term)
        words.append(word_entry(term, [], reading=reading))
    save_custom_set(lang, name, words)
    if missing:
        log("%d words had no dictionary entry: %s" % (len(missing), " ".join(missing[:20])))
        log("The app fills these in with Claude if you've added an API key (Settings), or edit them there.")


# ---------------------------------------------------------------------------------------------
# Validation

def save_custom_set(lang, name, words):
    """Writes content/sets/custom/<lang>-<name>.json and lists it in the index (replacing a set of the same name)."""
    key = "custom.%s.%s" % (lang, slug(name))
    rel = "sets/custom/%s-%s.json" % (lang, slug(name))
    rev = write_set(os.path.join(CONTENT, rel), key, lang, name, words)
    index = load_index()
    if not any(l["code"] == lang for l in index["languages"]):
        register_language(index, lang)
    register_set(index, key, lang, name, "Custom", 100, rel, len(words), rev, enabled=True, custom=True, source="import")
    save_index(index)
    log("Set \"%s\" (%s): %d words -> %s" % (name, LANGS[lang]["name"], len(words), rel))


def cmd_check(_args):
    index = load_index()
    problems = []
    warnings = []
    langs = {l["code"] for l in index["languages"]}
    for s in index["sets"]:
        path = os.path.join(CONTENT, s["file"])
        if s["lang"] not in langs:
            problems.append("%s: language %s not declared" % (s["key"], s["lang"]))
        if not os.path.exists(path):
            problems.append("%s: missing file %s" % (s["key"], s["file"]))
            continue
        data = load_json(path)
        if data.get("key") != s["key"] or data.get("rev") != s["rev"] or len(data["words"]) != s["count"]:
            problems.append("%s: index is out of date (run the build/import again)" % s["key"])
        terms = set()
        for w in data["words"]:
            t = w.get("term", "")
            if not t:
                problems.append("%s: word without term" % s["key"])
            if t in terms:
                problems.append("%s: duplicate %s" % (s["key"], t))
            terms.add(t)
            if not w.get("meanings"):
                (warnings if s.get("custom") else problems).append("%s: %s has no meaning" % (s["key"], t))
    for w in warnings[:20]:
        log("warning: " + w)
    if problems:
        log("\n".join(problems[:50]))
        sys.exit(1)
    log("OK: %d languages, %d sets, %d words" % (len(langs), len(index["sets"]), sum(s["count"] for s in index["sets"])))


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")
    b = sub.add_parser("build", help="build the built-in level sets")
    b.add_argument("langs", nargs="*")
    i = sub.add_parser("import", help="new custom set from a CSV/TXT list of words")
    i.add_argument("file")
    i.add_argument("--lang")
    i.add_argument("--name")
    a = sub.add_parser("add-language", help="download level sets for another language")
    a.add_argument("code")
    sub.add_parser("check", help="validate the set files")
    args = parser.parse_args()
    handlers = {"build": cmd_build, "import": cmd_import, "add-language": cmd_add_language, "check": cmd_check}
    if args.command not in handlers:
        parser.print_help()
        sys.exit(2)
    handlers[args.command](args)


if __name__ == "__main__":
    main()
