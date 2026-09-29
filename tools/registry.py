#!/usr/bin/env python3
"""Maintain the HanziLock word registry (content/registry/words.json).

    python tools/registry.py add 学习 喜欢 --tag HSK2   # look words up in CC-CEDICT and append them
    python tools/registry.py check                      # validate the file
    python tools/registry.py bump                       # bump the version so phones re-apply it
    python tools/registry.py format                     # rewrite in the canonical one-word-per-line layout

Every command that changes words also bumps "version": the app only applies the bundled
registry when its version is higher than the one it applied last. After editing, rebuild and
reinstall (tools/install.ps1). Standard library only; works with Python 3.7+.
"""
import argparse
import glob
import gzip
import io
import json
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "content", "registry", "words.json")
DICT_GLOB = os.path.join(ROOT, "content", "dictionary", "*.gz")

MARKS = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "v": "ǖǘǚǜ"}
UNMARK = {m: (base, i + 1) for base, marks in MARKS.items() for i, m in enumerate(marks)}


def marked_syllable(numbered):
    """xue2 -> xué, lu:4 -> lǜ, ma5 -> ma, Bei3 -> Běi."""
    capital = numbered[:1].isupper()
    s = numbered.lower().replace("u:", "v")
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


def cedict_to_marked(pinyin):
    return " ".join(marked_syllable(s) for s in pinyin.split() if any(c.isalpha() for c in s))


def is_han(ch):
    return "CJK" in unicodedata.name(ch, "")


def load_registry():
    with io.open(REGISTRY, encoding="utf-8") as f:
        return json.load(f)


def save_registry(reg):
    lines = ["{", '  "version": %d,' % reg["version"], '  "words": [']
    words = reg["words"]
    for i, w in enumerate(words):
        entry = {"hanzi": w["hanzi"]}
        if w.get("traditional") and w["traditional"] != w["hanzi"]:
            entry["traditional"] = w["traditional"]
        entry["pinyin"] = w["pinyin"]
        entry["meanings"] = w["meanings"]
        if w.get("examples"):
            entry["examples"] = [{k: v for k, v in ex.items() if v} for ex in w["examples"]]
        if w.get("tags"):
            entry["tags"] = w["tags"]
        text = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
        lines.append("    " + text + ("," if i < len(words) - 1 else ""))
    lines += ["  ]", "}", ""]
    with io.open(REGISTRY, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def load_cedict():
    paths = sorted(glob.glob(DICT_GLOB))
    if not paths:
        sys.exit("No CC-CEDICT file in content/dictionary - run tools/update-cedict.ps1 first.")
    entries = {}
    pattern = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")
    with gzip.open(paths[0], "rt", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            m = pattern.match(line)
            if m:
                trad, simp, pinyin, defs = m.groups()
                entries.setdefault(simp, []).append((trad, pinyin, [d for d in defs.split("/") if d]))
    return entries


def best_entry(candidates):
    """Prefer entries that aren't surnames / variants."""
    def score(entry):
        defs = entry[2]
        return sum(1 for d in defs if d.startswith("surname") or "variant of" in d)
    return sorted(candidates, key=score)[0]


def cmd_add(args):
    reg = load_registry()
    known = {w["hanzi"] for w in reg["words"]}
    cedict = load_cedict()
    added = 0
    for word in args.words:
        word = word.strip()
        if not word:
            continue
        if word in known:
            print("skip  %s (already in the registry)" % word)
            continue
        if word not in cedict:
            print("MISS  %s (not in CC-CEDICT - add it by hand)" % word)
            continue
        trad, pinyin, defs = best_entry(cedict[word])
        entry = {
            "hanzi": word,
            "traditional": trad if trad != word else None,
            "pinyin": cedict_to_marked(pinyin),
            "meanings": defs[: args.max_meanings],
            "examples": [],
            "tags": args.tag or [],
        }
        reg["words"].append(entry)
        known.add(word)
        added += 1
        print("add   %s  %s  %s" % (word, entry["pinyin"], "; ".join(entry["meanings"])))
    if added:
        reg["version"] += 1
        save_registry(reg)
        print("Added %d words; registry version is now %d." % (added, reg["version"]))
        print("Tip: add an example sentence for each (\"examples\"), or let the app suggest one with Claude.")


def cmd_check(_args):
    reg = load_registry()
    problems = []
    seen = set()
    for w in reg["words"]:
        h = w.get("hanzi", "")
        if h in seen:
            problems.append("duplicate: %s" % h)
        seen.add(h)
        if not w.get("pinyin"):
            problems.append("%s: missing pinyin" % h)
        if not w.get("meanings"):
            problems.append("%s: missing meanings" % h)
        syllables = [s for s in re.split(r"[\s']+", w.get("pinyin", "")) if s]
        if len(syllables) != len(h):
            problems.append("%s: %d characters but %d pinyin syllables (%s)" % (h, len(h), len(syllables), w.get("pinyin")))
        for ex in w.get("examples", []):
            han = "".join(c for c in ex.get("zh", "") if is_han(c))
            if h not in han:
                problems.append("%s: example doesn't contain the word: %s" % (h, ex.get("zh")))
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print("OK: %d words, version %d" % (len(reg["words"]), reg["version"]))


def cmd_bump(_args):
    reg = load_registry()
    reg["version"] += 1
    save_registry(reg)
    print("Registry version is now %d." % reg["version"])


def cmd_format(_args):
    save_registry(load_registry())
    print("Formatted.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")
    add = sub.add_parser("add", help="add words, looked up in CC-CEDICT")
    add.add_argument("words", nargs="+")
    add.add_argument("--tag", action="append", help="tag to attach (repeatable)")
    add.add_argument("--max-meanings", type=int, default=4)
    sub.add_parser("check", help="validate the registry")
    sub.add_parser("bump", help="increase the version")
    sub.add_parser("format", help="rewrite in canonical layout")
    args = parser.parse_args()
    handlers = {"add": cmd_add, "check": cmd_check, "bump": cmd_bump, "format": cmd_format}
    if args.command not in handlers:
        parser.print_help()
        sys.exit(2)
    handlers[args.command](args)


if __name__ == "__main__":
    main()
