#!/usr/bin/env python3
"""Build the private comparison set for the recipe-import spike from a plain list of Instagram links.

No Mealie involved. Runs on any machine with Python, yt-dlp and (only for --transcript) ffmpeg plus a
Whisper server, e.g. Surfacelab.

Input: a text file with one Instagram link per line (lines starting with # are ignored).
Output:
  <out>/samples-private/<id>/caption.txt      caption fetched with yt-dlp (no login)
  <out>/samples-private/<id>/transcript.txt   only with --transcript
  <out>/samples-private/<id>/source.url
  <out>/expected-private.csv                  id;line, one row per expected ingredient

The expectation has to be right, so a person writes or checks it:
  - default: one comment row per sample, you type the ingredients in yourself
  - --draft-with-claude: Claude drafts the list from caption and transcript (needs ANTHROPIC_API_KEY,
    Nils' cloud exception for the importer from 01.11.2026). That is the quality the current importer
    reaches, i.e. the bar the on-device chain has to meet. Check every line before measuring.
Both outputs are in .gitignore and must never be committed.

Usage:
  python3 collect_samples.py links.txt <out-dir> [--transcript] [--draft-with-claude]
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
import time

import requests

WHISPER_URL = os.environ.get("WHISPER_URL", "http://127.0.0.1:8000/v1/audio/transcriptions")
CLAUDE_MODEL = os.environ.get("CLOUD_MODEL", "claude-opus-5-5")


def read_links(path):
    links = []
    for line in pathlib.Path(path).read_text().splitlines():
        m = re.search(r"https?://\S+", line)
        if m and not line.lstrip().startswith("#"):
            links.append(m.group(0).split("?")[0])
    return list(dict.fromkeys(links))


def sample_id(url):
    m = re.search(r"/(?:reel|reels|p|tv)/([\w-]+)", url)
    return m.group(1) if m else hashlib.sha1(url.encode()).hexdigest()[:10]


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


def draft_with_claude(caption, transcript):
    import anthropic
    schema = {"type": "object", "properties": {"recipeIngredient": {"type": "array", "items": {"type": "string"}}},
              "required": ["recipeIngredient"], "additionalProperties": False}
    prompt = ("Lies aus Bildunterschrift und Transkript eines Instagram-Rezepts die Zutatenliste ab. "
              "Eine Zutat pro Eintrag im Format 'Menge Einheit Zutat', z. B. '250 g Kirschtomaten'. "
              "Nichts erfinden, nichts weglassen. Gibt es keine Zutaten, gib eine leere Liste zurück.\n\n"
              f"Bildunterschrift:\n\"\"\"{caption[:3000]}\"\"\"\n\nTranskript:\n\"\"\"{transcript[:3000]}\"\"\"")
    client = anthropic.Anthropic(timeout=180, max_retries=2)
    response = client.messages.create(
        model=CLAUDE_MODEL, max_tokens=4000,
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": schema}},
        messages=[{"role": "user", "content": prompt}],
    )
    text = next(b.text for b in response.content if b.type == "text")
    return json.loads(text)["recipeIngredient"]


def csv_line(text):
    return re.sub(r"[;\s]+", " ", text).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("links")
    ap.add_argument("out")
    ap.add_argument("--transcript", action="store_true")
    ap.add_argument("--draft-with-claude", action="store_true")
    args = ap.parse_args()
    if args.draft_with_claude and not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ANTHROPIC_API_KEY fehlt (oder ohne --draft-with-claude starten)")

    out = pathlib.Path(args.out)
    samples = out / "samples-private"
    samples.mkdir(parents=True, exist_ok=True)
    rows = ["id;line"]
    for url in read_links(args.links):
        sid = sample_id(url)
        target = samples / sid
        target.mkdir(exist_ok=True)
        (target / "source.url").write_text(url + "\n")
        try:
            caption = fetch_caption(url)
            (target / "caption.txt").write_text(caption)
            transcript = fetch_transcript(url) if args.transcript else ""
            if args.transcript:
                (target / "transcript.txt").write_text(transcript)
            if args.draft_with_claude:
                rows.append(f"# {sid}: Entwurf von Claude, bitte prüfen ({url})")
                rows += [f"{sid};{csv_line(line)}" for line in draft_with_claude(caption, transcript)]
            else:
                rows.append(f"# {sid}: Zutaten eintragen, eine pro Zeile als '{sid};250 g Mehl' ({url})")
            print(f"ok   {sid}")
        except Exception as e:  # keep going; a missing caption is itself a finding
            print(f"FAIL {sid} {url}: {e}")
        time.sleep(3)  # one request at a time, like a person sharing posts
    (out / "expected-private.csv").write_text("\n".join(rows) + "\n")
    print(f"-> {out / 'expected-private.csv'} (vor dem Messen prüfen)")


if __name__ == "__main__":
    main()
