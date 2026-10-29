"""Prepare a CET4 word list and match it to Wiktionary US audio metadata.

This script does not download audio or write to the database. It uses only the
Python standard library and streams Kaikki JSONL (plain or gzip compressed).
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import gzip
import html
import json
import re
import unicodedata
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen


def normalize(word):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", word).strip()).casefold()


def candidates(args):
    rows = []
    with args.ecdict.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if "cet4" not in (row.get("tag") or "").lower().split():
                continue
            if not (row.get("translation") or "").strip():
                continue
            word = (row.get("word") or "").strip()
            if word:
                rows.append({"word_id": "", "word": word,
                             "word_key": normalize(word), "frq": row.get("frq") or ""})
    rows.sort(key=lambda r: (int(r["frq"]) if r["frq"].isdigit() and int(r["frq"]) > 0 else 10**12,
                             r["word_key"]))
    write_csv(args.output, rows, ["word_id", "word", "word_key", "frq"])
    print(f"ECDICT CET4 candidates: {len(rows)} -> {args.output}")


def write_csv(path, rows, fields):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def load_words(path, limit):
    # Accepts either the candidates CSV or a mysql --batch production TSV.
    with path.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t" if path.suffix.lower() == ".tsv" else ","))
    if not rows or "word" not in rows[0]:
        raise ValueError("Input CSV must have a word column")
    rows = rows[:limit] if limit else rows
    return rows, {normalize(row["word"]) for row in rows}


def us_audio(entry):
    choices = []
    for sound in entry.get("sounds", []):
        if not isinstance(sound, dict):
            continue
        tags = {str(tag).casefold() for tag in sound.get("tags", [])}
        name = str(sound.get("audio") or "")
        is_us = bool(tags & {"us", "general-american", "american"}) or name.casefold().startswith("en-us-")
        if not is_us or not (sound.get("ogg_url") or sound.get("mp3_url")):
            continue
        choices.append({"audio_file": name, "mp3_url": sound.get("mp3_url") or "",
                        "ogg_url": sound.get("ogg_url") or "", "audio_tags": ",".join(sorted(tags))})
    return choices


def fetch_one(word):
    key = normalize(word)
    url = source_page(word)
    request = Request(url, headers={"User-Agent": "XuelingAudioPreparation/1.0 (offline dataset preparation)"})
    with urlopen(request, timeout=35) as response:
        page = response.read().decode("utf-8")
    entries = []
    decoder = json.JSONDecoder()
    for raw in re.findall(r"<pre\b[^>]*>(.*?)</pre>", page, flags=re.DOTALL | re.IGNORECASE):
        raw = html.unescape(re.sub(r"<[^>]+>", "", raw)).strip()
        while raw:
            try:
                entry, end = decoder.raw_decode(raw)
            except json.JSONDecodeError:
                break
            if isinstance(entry, dict) and entry.get("lang_code") == "en" and normalize(entry.get("word", "")) == key:
                entries.append(entry)
            raw = raw[end:].strip()
    return url, entries


def source_page(word):
    spelling = unicodedata.normalize("NFKC", word).strip()
    return "https://kaikki.org/dictionary/English/meaning/{}/{}/{}.html".format(
        quote(spelling[:1]), quote(spelling[:2]), quote(spelling, safe=""))


def fetch(args):
    words, _ = load_words(args.words, args.limit)
    unique = list(dict.fromkeys(row["word"] for row in words))
    completed = set()
    if args.resume and args.output.exists():
        with args.output.open(encoding="utf-8") as existing:
            for line in existing:
                if line.strip():
                    completed.add(normalize(json.loads(line)["word"]))
    remaining = [word for word in unique if normalize(word) not in completed]
    errors = []
    entry_count = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("a" if args.resume else "w", encoding="utf-8") as target:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(fetch_one, word): word for word in remaining}
            for done, future in enumerate(as_completed(futures), 1):
                word = futures[future]
                try:
                    url, entries = future.result()
                    for entry in entries:
                        target.write(json.dumps({"word": entry["word"], "lang_code": "en",
                                                 "sounds": entry.get("sounds", [])}, ensure_ascii=False) + "\n")
                    target.flush()
                    entry_count += len(entries)
                    if not entries:
                        errors.append({"word": word, "error": "no_english_entry", "url": url})
                except Exception as exc:
                    errors.append({"word": word,
                                   "error": f"{type(exc).__name__}:{getattr(exc, 'code', '')}",
                                   "url": source_page(word)})
                if done % 100 == 0 or done == len(remaining):
                    print(f"Fetched {done}/{len(remaining)} remaining words; {len(errors)} errors", flush=True)
    write_csv(args.errors, errors, ["word", "error", "url"])
    print(f"Fetched {len(unique)} words: {len(completed)} cached, {entry_count} new English entries, {len(errors)} errors -> {args.output}")


def match(args):
    words, wanted = load_words(args.words, args.limit)
    fetch_errors = set()
    if args.errors and args.errors.exists():
        with args.errors.open(encoding="utf-8-sig", newline="") as source:
            fetch_errors = {normalize(row["word"]) for row in csv.DictReader(source)}
    matches = {}
    opener = gzip.open if args.kaikki.suffix == ".gz" else open
    with opener(args.kaikki, "rt", encoding="utf-8") as source:
        for line in source:
            entry = json.loads(line)
            if entry.get("lang_code") != "en":
                continue
            key = normalize(entry.get("word", ""))
            if key in wanted:
                for sound in us_audio(entry):
                    matches.setdefault(key, {})[sound["ogg_url"] or sound["mp3_url"]] = sound
    result = []
    for row in words:
        options = sorted(matches.get(normalize(row["word"]), {}).values(),
                         key=lambda item: (not item["audio_file"].casefold().startswith("en-us-"),
                                           not bool(item["mp3_url"]), item["audio_file"]))
        selected = options[0] if options else {}
        result.append({"word_id": row.get("word_id", ""), "word": row["word"],
                       "source_page": source_page(row["word"]),
                       "status": "matched_license_pending" if len(options) == 1 else
                                 "multiple_review" if options else
                                 "source_page_missing" if normalize(row["word"]) in fetch_errors else "no_us_audio",
                       "option_count": len(options), **{field: selected.get(field, "") for field in
                                                      ("audio_file", "mp3_url", "ogg_url", "audio_tags")}})
    fields = ["word_id", "word", "source_page", "status", "option_count", "audio_file", "mp3_url", "ogg_url", "audio_tags"]
    write_csv(args.output, result, fields)
    counts = {status: sum(row["status"] == status for row in result)
              for status in ("matched_license_pending", "multiple_review", "no_us_audio", "source_page_missing")}
    print(f"Matched {len(result)} words -> {args.output}: {counts}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    export = sub.add_parser("candidates", help="Export local ECDICT CET4 candidates")
    export.add_argument("--ecdict", type=Path, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.set_defaults(func=candidates)
    matcher = sub.add_parser("match", help="Match words against a Kaikki English JSONL dump")
    matcher.add_argument("--words", type=Path, required=True)
    matcher.add_argument("--kaikki", type=Path, required=True)
    matcher.add_argument("--output", type=Path, required=True)
    matcher.add_argument("--errors", type=Path, help="Fetch error CSV, to keep missing pages separate")
    matcher.add_argument("--limit", type=int, default=0, help="First N words for a pilot (0 = all)")
    matcher.set_defaults(func=match)
    fetcher = sub.add_parser("fetch", help="Fetch public Kaikki word pages for a small pilot")
    fetcher.add_argument("--words", type=Path, required=True)
    fetcher.add_argument("--output", type=Path, required=True)
    fetcher.add_argument("--errors", type=Path, required=True)
    fetcher.add_argument("--limit", type=int, default=100)
    fetcher.add_argument("--workers", type=int, default=4)
    fetcher.add_argument("--resume", action="store_true", help="Keep existing JSONL and skip completed words")
    fetcher.set_defaults(func=fetch)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
