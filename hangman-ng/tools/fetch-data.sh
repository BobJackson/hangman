#!/usr/bin/env bash
#
# Fetches and preprocesses the external word-frequency corpus used by hangman-ng.
#
#   * The raw download lands in data/ and is NOT committed (see ../.gitignore).
#   * The derived, cleaned artifact IS committed, so `mvn test` works without network:
#         src/main/resources/word-ranks.txt.gz
#
# The source list is ordered by descending frequency, so a word's line number is its
# frequency rank. That single file therefore serves two purposes:
#   1. the simulator's answer pool (take the top-N entries)
#   2. the solver's prior weights   (weight = f(rank))
#
# Source : https://github.com/david47k/top-english-wordlists
#          derived from Google ngrams English (version 20120701, years 1950-2012)
# License: CC BY 3.0  -- attribution required, credited in ../README.md
#
# Usage: bash tools/fetch-data.sh [--force]
#
set -euo pipefail

cd "$(dirname "$0")/.."

RAW_DIR="data"
RAW_FILE="${RAW_DIR}/top_english_words_lower_100000.txt"
OUT_FILE="src/main/resources/word-ranks.txt.gz"
URL="https://raw.githubusercontent.com/david47k/top-english-wordlists/master/top_english_words_lower_100000.txt"

MIN_LEN=3    # the server never asks for 1-2 letter words in practice
MAX_LEN=20

mkdir -p "$RAW_DIR" "$(dirname "$OUT_FILE")"

if [[ -s "$RAW_FILE" && "${1:-}" != "--force" ]]; then
    echo "[skip] $RAW_FILE already present (use --force to re-download)"
else
    echo "[get ] $URL"
    curl -fsSL --max-time 60 -o "$RAW_FILE" "$URL"
fi

RAW_LINES=$(wc -l < "$RAW_FILE" | tr -d ' ')
echo "[info] raw lines: $RAW_LINES"

# Clean: keep pure a-z words of a playable length, drop duplicates preserving rank
# order, then upper-case (the server only accepts capital letters).
awk -v lo="$MIN_LEN" -v hi="$MAX_LEN" 'length($0)>=lo && length($0)<=hi && $0 ~ /^[a-z]+$/' "$RAW_FILE" \
    | awk '!seen[$0]++' \
    | tr 'a-z' 'A-Z' \
    | gzip -9 > "$OUT_FILE"

KEPT=$(gzip -dc "$OUT_FILE" | wc -l | tr -d ' ')
DROPPED=$((RAW_LINES - KEPT))
echo "[ok  ] $OUT_FILE : kept $KEPT words, dropped $DROPPED"

echo "[stat] length distribution of the answer pool:"
gzip -dc "$OUT_FILE" | awk '{ n=length($0)
    if (n<=5)      a++
    else if (n<=8) b++
    else if (n<=12)c++
    else           d++ }
    END { printf "         len<=5 : %d\n         len6-8 : %d\n         len9-12: %d\n         len>12 : %d\n", a, b, c, d }'
