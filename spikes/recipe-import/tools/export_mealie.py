#!/usr/bin/env python3
"""Build the private comparison set for the recipe-import spike from the existing Mealie collection.

Runs on Surfacelab, next to Mealie and the current importer (needs: requests, yt-dlp; ffmpeg and the
local Whisper server only with --transcript).

For the newest recipes whose source is an Instagram link it writes
  <out>/samples-private/<slug>/caption.txt      caption fetched again with yt-dlp (no login)
  <out>/samples-private/<slug>/transcript.txt   only with --transcript, via the local Whisper server
  <out>/samples-private/<slug>/source.url
  <out>/samples-private/<slug>/mealie.json      the recipe as Mealie stores it, for reference
  <out>/expected-private.csv                    id;line, one row per ingredient as stored in Mealie

The expectation comes from the current importer (Claude). Please read through expected-private.csv once
and fix obvious mistakes before measuring. Both outputs are in .gitignore and must never be committed.

Usage:
  MEALIE_BASE_URL=http://127.0.0.1:9000 MEALIE_API_TOKEN=... \
    python3 export_mealie.py <out-dir> [--limit 30] [--transcript]
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time

import requests

BASE = os.environ.get("MEALIE_BASE_URL", "http://127.0.0.1:9000").rstrip("/")
TOKEN = os.environ.get("MEALIE_API_TOKEN", "")
WHISPER_URL = os.environ.get("WHISPER_URL", "http://127.0.0.1:8000/v1/audio/transcriptions")


def api(path, **params):
    r = requests.get(f"{BASE}{path}", params=params, headers={"Authorization": f"Bearer {TOKEN}"}, timeout=30)
    r.raise_for_status()
    return r.json()


def newest_instagram_recipes(limit):
    found, page = [], 1
    while len(found) < limit:
        data = api("/api/recipes", page=page, perPage=50, orderBy="created_at", orderDirection="desc")
        items = data.get("items", [])
        if not items:
            break
        for item in items:
            if "instagram.com" in (item.get("orgURL") or ""):
                found.append(item)
        if page >= data.get("total_pages", page):
            break
        page += 1
    return found[:limit]


def ingredient_lines(recipe):
    lines = []
    for ing in recipe.get("recipeIngredient") or []:
        text = ing.get("originalText") or ing.get("display") or ing.get("note") or ""
        text = re.sub(r"\s+", " ", text.replace(";", ",")).strip()
        if text:
            lines.append(text)
    return lines


def fetch_caption(url):
    import yt_dlp
    with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "ignore_no_formats_error": True}) as ydl:
        info = ydl.extract_info(url, download=False)
    return info.get("description") or info.get("title") or ""


def fetch_transcript(url):
    import yt_dlp
    with tempfile.TemporaryDirectory() as tmp:
        opts = {"format": "bestaudio/best", "outtmpl": os.path.join(tmp, "a.%(ext)s"), "quiet": True,
                "no_warnings": True, "noplaylist": True,
                "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3"}]}
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.extract_info(url, download=True)
        audio = pathlib.Path(tmp, "a.mp3")
        if not audio.exists():
            return ""
        with audio.open("rb") as f:
            r = requests.post(WHISPER_URL, files={"file": ("a.mp3", f, "audio/mpeg")},
                              data={"model": "whisper-1", "language": "de"}, timeout=300)
        r.raise_for_status()
        return r.json().get("text", "").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--transcript", action="store_true")
    args = ap.parse_args()
    if not TOKEN:
        sys.exit("MEALIE_API_TOKEN fehlt")

    out = pathlib.Path(args.out)
    samples = out / "samples-private"
    samples.mkdir(parents=True, exist_ok=True)
    rows = ["id;line"]
    for item in newest_instagram_recipes(args.limit):
        slug = item["slug"]
        recipe = api(f"/api/recipes/{slug}")
        url = recipe.get("orgURL")
        target = samples / slug
        target.mkdir(exist_ok=True)
        (target / "source.url").write_text(url + "\n")
        (target / "mealie.json").write_text(json.dumps(recipe, ensure_ascii=False, indent=2))
        rows += [f"{slug};{line}" for line in ingredient_lines(recipe)]
        try:
            (target / "caption.txt").write_text(fetch_caption(url))
            if args.transcript:
                (target / "transcript.txt").write_text(fetch_transcript(url))
            print(f"ok   {slug}")
        except Exception as e:  # keep going; a missing caption is itself a finding
            print(f"FAIL {slug}: {e}")
        time.sleep(3)  # one request at a time, like a person sharing posts
    (out / "expected-private.csv").write_text("\n".join(rows) + "\n")
    print(f"{len(rows) - 1} Zutatenzeilen -> {out / 'expected-private.csv'}")


if __name__ == "__main__":
    main()
