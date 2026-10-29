"""Import ECDICT's UTF-8 CSV into word_dictionary using the MySQL CLI.

No Python packages are required. The target schema must be created/migrated first.
The import is repeatable: the unique word_key keeps the original row ID.
"""

import argparse
import csv
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path


COLUMNS = (
    "word", "word_key", "phonetic", "definition", "meaning", "pos",
    "collins", "oxford", "tag", "bnc", "frq", "exchange", "detail",
    "audio", "source", "dataset_version",
)
SOURCE_COLUMNS = (
    "word", "phonetic", "definition", "translation", "pos", "collins",
    "oxford", "tag", "bnc", "frq", "exchange", "detail", "audio",
)


def normalize(word: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", word).strip()).lower()


def sql_value(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    text = str(value)
    for source, target in (("\\", "\\\\"), ("\0", "\\0"), ("\n", "\\n"),
                           ("\r", "\\r"), ("\t", "\\t"), ("\x1a", "\\Z"), ("'", "\\'")):
        text = text.replace(source, target)
    return "'" + text + "'"


def optional_text(value: str):
    return value if value and value.strip() else None


def optional_number(value: str, maximum: int):
    if not value or not value.strip() or value.strip() == "0":
        return None
    number = int(value)
    if number < 0 or number > maximum:
        raise ValueError("numeric field out of range")
    return number


def convert(row: dict, version: str):
    word = row["word"].strip()
    key = normalize(word)
    if not key or len(key) > 255 or not re.search(r"[A-Za-zÀ-ÖØ-öø-ÿ]", key):
        raise ValueError("invalid word or word_key exceeds 255 characters")
    for column, limit in (("word", 255), ("phonetic", 255), ("pos", 255), ("audio", 1000)):
        if len(row[column]) > limit:
            raise ValueError(f"{column} exceeds {limit} characters")
    return (
        word, key, optional_text(row["phonetic"]), optional_text(row["definition"]),
        optional_text(row["translation"]), optional_text(row["pos"]),
        optional_number(row["collins"], 255), optional_number(row["oxford"], 255),
        optional_text(row["tag"]), optional_number(row["bnc"], 4294967295),
        optional_number(row["frq"], 4294967295), optional_text(row["exchange"]),
        optional_text(row["detail"]), optional_text(row["audio"]), "ECDICT", version,
    )


def insert_statement(rows):
    values = ",\n".join("(" + ",".join(map(sql_value, row)) + ")" for row in rows)
    columns = ",".join(f"`{column}`" for column in COLUMNS)
    return (
        f"INSERT INTO `word_dictionary` ({columns}) VALUES\n{values}\n"
        "ON DUPLICATE KEY UPDATE "
        "`word`=VALUES(`word`), `phonetic`=IF(`source`='MANUAL',`phonetic`,COALESCE(VALUES(`phonetic`),`phonetic`)), "
        "`definition`=VALUES(`definition`), "
        "`meaning`=IF(`source`='MANUAL',`meaning`,COALESCE(NULLIF(VALUES(`meaning`),''),`meaning`)), "
        "`pos`=VALUES(`pos`), `collins`=VALUES(`collins`), "
        "`oxford`=VALUES(`oxford`), `tag`=VALUES(`tag`), "
        "`bnc`=VALUES(`bnc`), `frq`=VALUES(`frq`), "
        "`exchange`=VALUES(`exchange`), `detail`=VALUES(`detail`), "
        "`audio`=VALUES(`audio`), `dataset_version`=VALUES(`dataset_version`), "
        "`source`=IF(`source`='MANUAL','MANUAL',IF(VALUES(`meaning`) IS NOT NULL AND VALUES(`meaning`)<>'', "
        "'ECDICT', IF(`source`='AI' OR `source`='AI_ENRICHED', 'AI_ENRICHED', 'ECDICT')));\n"
    )


def run(args):
    if not re.fullmatch(r"[A-Za-z0-9_]+", args.database):
        raise ValueError("database must contain only letters, digits and underscore")
    if not args.csv.is_file():
        raise FileNotFoundError(args.csv)
    csv.field_size_limit(16 * 1024 * 1024)
    process = None
    if not args.dry_run:
        command = [args.mysql, "--default-character-set=utf8mb4", "--binary-mode",
                   "--max-allowed-packet=64M", f"--host={args.host}", f"--port={args.port}",
                   f"--user={args.user}", args.database]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, encoding="utf-8")
        process.stdin.write("SET SESSION sql_mode=REPLACE(@@sql_mode,'NO_BACKSLASH_ESCAPES','');\n")
    count = skipped = empty = 0
    batch = []
    with args.csv.open("r", encoding="utf-8-sig", newline="") as input_file, \
            args.rejects.open("w", encoding="utf-8", newline="") as reject_file:
        reader = csv.DictReader(input_file)
        if tuple(reader.fieldnames or ()) != SOURCE_COLUMNS:
            raise ValueError("unexpected ECDICT CSV header")
        reject_writer = csv.writer(reject_file, delimiter="\t")
        reject_writer.writerow(("row", "word", "reason"))
        for row_number, row in enumerate(reader, start=2):
            try:
                if None in row:
                    raise ValueError("extra CSV fields")
                converted = convert(row, args.version)
            except (ValueError, TypeError, KeyError) as ex:
                skipped += 1
                reject_writer.writerow((row_number, row.get("word", ""), str(ex)))
                continue
            if converted[4] is None:
                empty += 1
            count += 1
            batch.append(converted)
            if len(batch) >= args.batch_size:
                if process:
                    process.stdin.write(insert_statement(batch))
                batch.clear()
                if count % (args.batch_size * 100) == 0:
                    print(f"processed={count} skipped={skipped}", flush=True)
        if batch and process:
            process.stdin.write(insert_statement(batch))
    if process:
        process.stdin.close()
        output = process.stdout.read()
        error = process.stderr.read()
        status = process.wait()
        if status:
            raise RuntimeError(f"mysql exited {status}: {error.strip()}")
        if output.strip():
            print(output.strip())
    print(f"valid_rows={count} empty_translation={empty} rejected_rows={skipped} rejects={args.rejects}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--version", required=True, help="ECDICT commit SHA or release identifier")
    parser.add_argument("--database", default="xueling_db")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default=os.environ.get("MYSQL_USER", "root"))
    parser.add_argument("--mysql", default="mysql", help="path to mysql CLI")
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--rejects", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.batch_size < 1 or args.batch_size > 1000:
        parser.error("batch-size must be between 1 and 1000")
    args.rejects = args.rejects or args.csv.with_suffix(".rejects.tsv")
    try:
        run(args)
    except (OSError, ValueError, RuntimeError) as ex:
        print(f"import failed: {ex}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
