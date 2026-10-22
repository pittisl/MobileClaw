#!/usr/bin/env python3
"""Cache the PHOTOS.tsv corpus and Commons-provided attribution for offline reuse."""

import argparse
import csv
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request


USER_AGENT = "MobileClaw-demo-preload/1.1 (https://github.com/pittisl/MobileClaw)"


def fetch(url):
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def cache_photos(manifest, cache):
    cache.mkdir(parents=True, exist_ok=True)
    credits_path = cache / "ATTRIBUTION.json"
    existing = json.loads(credits_path.read_text()) if credits_path.exists() else []
    credits = {item["device_name"]: item for item in existing}
    with manifest.open() as stream:
        rows = list(csv.reader((line for line in stream if not line.startswith("#")), delimiter="\t"))
    for name, title, license_hint, term in rows:
        local_path = cache / name
        if local_path.exists() and local_path.stat().st_size > 0 and name in credits:
            print(f"Cached: {name}")
            continue
        query = urllib.parse.urlencode({
            "action": "query", "titles": title, "prop": "imageinfo", "iiurlwidth": 1280,
            "iiprop": "url|extmetadata", "format": "json",
        })
        payload = json.loads(fetch("https://commons.wikimedia.org/w/api.php?" + query))
        page = next(iter(payload["query"]["pages"].values()))
        info = page["imageinfo"][0]
        download_url = info.get("thumburl", info["url"])
        data = fetch(download_url)
        # Commons artist and license fields can be HTML; keep them verbatim.
        credit = {
            "device_name": name, "title": title, "source_page": info["descriptionurl"],
            "download_url": download_url, "manifest_license_hint": license_hint,
            "search_term": term, "extmetadata": info.get("extmetadata", {}),
        }
        partial_path = local_path.with_suffix(local_path.suffix + ".part")
        partial_path.write_bytes(data)
        partial_path.replace(local_path)
        credits[name] = credit
        credits_path.write_text(json.dumps(list(credits.values()), indent=2, ensure_ascii=False) + "\n")
        print(f"Downloaded: {name} ({license_hint}; see ATTRIBUTION.json)")
        time.sleep(0.5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    cache_photos(args.manifest, args.cache)
