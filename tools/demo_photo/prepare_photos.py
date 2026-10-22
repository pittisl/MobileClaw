#!/usr/bin/env python3
"""Create EXIF-dated demo copies without changing JPEG pixels or source files.

The dates are synthetic fixture data, not claims about when the source photographs
were taken. Requires Pillow for independent EXIF and decoded-pixel verification.
Use a uv environment with Pillow; no network or device access is performed here.
"""

import argparse
import csv
from datetime import date, datetime, time
import hashlib
import io
import json
from pathlib import Path
import struct
from zoneinfo import ZoneInfo

from PIL import Image


FIXTURES = (
    ("golden retriever", "09:15:00", "target", True),
    ("siberian husky snow", "12:30:00", "target", True),
    ("border collie running", "17:45:00", "target", True),
    ("dachshund puppy", "10:20:00", "other-date", False),
    ("cat", "14:10:00", "target", False),
)
DATE_TIME, EXIF_POINTER = 306, 34665
EXIF_DATE_TAGS = {
    "DateTimeOriginal": 36867,
    "DateTimeDigitized": 36868,
    "OffsetTime": 36880,
    "OffsetTimeOriginal": 36881,
    "OffsetTimeDigitized": 36882,
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def jpeg_headers(data):
    """Return pre-scan segments and untouched SOS-through-EOF compressed data."""
    if data[:2] != b"\xff\xd8":
        raise ValueError("Source is not a JPEG")
    segments, position = [], 2
    while position < len(data):
        start = position
        if data[position] != 255:
            raise ValueError("Invalid JPEG marker")
        while data[position] == 255:
            position += 1
        marker = data[position]
        position += 1
        if marker == 0xDA:
            return segments, data[start:]
        if marker in (0, 0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            raise ValueError("Unexpected JPEG marker before image scan")
        length = int.from_bytes(data[position:position + 2], "big")
        if length < 2 or position + length > len(data):
            raise ValueError("Invalid JPEG segment length")
        end = position + length
        segments.append((marker, data[position + 2:end], data[start:end]))
        position = end
    raise ValueError("JPEG has no image scan")


def is_exif(segment):
    return segment[0] == 0xE1 and segment[1].startswith(b"Exif\0\0")


def dated_exif(original, date_text, offset_text):
    """Append new IFDs, preserving unknown source entries and their offsets.

    The original TIFF bytes remain at their original offsets; only the TIFF
    header's root pointer changes. This also preserves maker-note offsets,
    thumbnails, copyright tags, and vendor metadata that a serializer may drop.
    """
    tiff = bytearray(original[6:] if original else b"II*\0\0\0\0\0")
    byte_order = bytes(tiff[:2])
    if byte_order not in (b"II", b"MM"):
        raise ValueError("Invalid TIFF byte order")
    endian = "<" if byte_order == b"II" else ">"
    unpack = lambda pattern, data: struct.unpack(endian + pattern, data)
    pack = lambda pattern, *values: struct.pack(endian + pattern, *values)
    if unpack("H", tiff[2:4])[0] != 42:
        raise ValueError("Unsupported EXIF TIFF format")

    def read_ifd(offset):
        if offset == 0:
            return {}, 0
        count = unpack("H", tiff[offset:offset + 2])[0]
        if offset + 2 + count * 12 + 4 > len(tiff):
            raise ValueError("IFD lies outside EXIF data")
        entries = {}
        for position in range(offset + 2, offset + 2 + count * 12, 12):
            entry = bytes(tiff[position:position + 12])
            tag = unpack("H", entry[:2])[0]
            if tag in entries:
                raise ValueError("Duplicate TIFF tag; refusing ambiguous metadata")
            entries[tag] = entry
        next_ifd = unpack("I", tiff[offset + 2 + count * 12:offset + 6 + count * 12])[0]
        return entries, next_ifd

    def append(data):
        if len(tiff) % 2:
            tiff.append(0)
        offset = len(tiff)
        tiff.extend(data)
        return offset

    def ascii_entry(tag, value):
        data = value.encode("ascii") + b"\0"
        pointer = data.ljust(4, b"\0") if len(data) <= 4 else pack("I", append(data))
        return pack("HHI", tag, 2, len(data)) + pointer

    def write_ifd(entries, next_ifd):
        return append(pack("H", len(entries)) + b"".join(entries[tag] for tag in sorted(entries))
                      + pack("I", next_ifd))

    root, root_next = read_ifd(unpack("I", tiff[4:8])[0])
    exif_offset = 0
    if EXIF_POINTER in root:
        pointer = root[EXIF_POINTER]
        if unpack("HI", pointer[2:8]) != (4, 1):
            raise ValueError("Unexpected EXIF IFD pointer type")
        exif_offset = unpack("I", pointer[8:12])[0]
    exif, exif_next = read_ifd(exif_offset)
    for name, tag in EXIF_DATE_TAGS.items():
        exif[tag] = ascii_entry(tag, offset_text if name.startswith("Offset") else date_text)
    root[DATE_TIME] = ascii_entry(DATE_TIME, date_text)
    root[EXIF_POINTER] = pack("HHII", EXIF_POINTER, 4, 1, write_ifd(exif, exif_next))
    new_root = write_ifd(root, root_next)
    tiff[4:8] = pack("I", new_root)
    payload = b"Exif\0\0" + tiff
    if len(payload) + 2 > 65535:
        raise ValueError("EXIF exceeds JPEG APP1 size limit")
    if original and (payload[6:10] != original[6:10] or payload[14:len(original)] != original[14:]):
        raise AssertionError("Original TIFF bytes changed outside its root pointer")
    return payload


def make_copy(data, date_text, offset_text):
    segments, scan = jpeg_headers(data)
    exif_segments = [segment for segment in segments if is_exif(segment)]
    if len(exif_segments) > 1:
        raise ValueError("Multiple EXIF APP1 segments; refusing ambiguous metadata")
    old_exif = exif_segments[0][1] if exif_segments else b""
    new_exif = dated_exif(old_exif, date_text, offset_text)
    new_segment = b"\xff\xe1" + struct.pack(">H", len(new_exif) + 2) + new_exif
    output_segments, inserted = [], False
    for segment in segments:
        if is_exif(segment):
            output_segments.append(new_segment)
            inserted = True
        else:
            output_segments.append(segment[2])
    if not inserted:
        # Keep JFIF first, if present, as specified by its interchange format.
        position = 1 if segments and segments[0][0] == 0xE0 else 0
        output_segments.insert(position, new_segment)
    return b"\xff\xd8" + b"".join(output_segments) + scan


def inspect(data):
    with Image.open(io.BytesIO(data)) as image:
        pixels = image.tobytes()
        root = image.getexif()
        exif = root.get_ifd(EXIF_POINTER)
        return {
            "size": list(image.size), "mode": image.mode,
            "decoded_pixels_sha256": sha256(pixels),
            "exif": {"DateTime": root.get(DATE_TIME),
                     **{name: exif.get(tag) for name, tag in EXIF_DATE_TAGS.items()}},
            "artist": root.get(315), "copyright": root.get(33432),
        }


def prepare(args):
    target_date = date.fromisoformat(args.target_date)
    other_date = date.fromisoformat(args.other_date)
    if other_date == target_date:
        raise ValueError("The negative control date must differ from the target date")
    timezone = ZoneInfo(args.timezone)
    cache = args.cache.resolve()
    output = args.output.resolve()
    if output == cache or cache in output.parents:
        raise ValueError("Output must be outside the immutable source cache")
    with args.manifest.open() as stream:
        rows = list(csv.reader((line for line in stream if not line.startswith("#")), delimiter="\t"))
    by_term = {term: (name, title, license_hint) for name, title, license_hint, term in rows}
    attributions = {item["device_name"]: item for item in json.loads((cache / "ATTRIBUTION.json").read_text())}
    planned = []
    for term, clock, date_kind, expected_match in FIXTURES:
        name, title, license_hint = by_term[term]
        source = cache / name
        captured = datetime.combine(target_date if date_kind == "target" else other_date,
                                    time.fromisoformat(clock), timezone)
        date_text = captured.strftime("%Y:%m:%d %H:%M:%S")
        raw_offset = captured.strftime("%z")
        offset_text = raw_offset[:3] + ":" + raw_offset[3:]
        output_name = f"IMG_{captured:%Y%m%d_%H%M%S}.jpg"
        planned.append((source, output / output_name, attributions[name], title, license_hint,
                        term, captured, date_text, offset_text, expected_match))
    if any(destination.exists() for _, destination, *_ in planned) or (output / "manifest.json").exists():
        raise FileExistsError("Output fixtures already exist; choose a new output directory")
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for source, destination, attribution, title, license_hint, term, captured, date_text, offset_text, expected_match in planned:
        original = source.read_bytes()
        source_stat = source.stat()
        result = make_copy(original, date_text, offset_text)
        before, after = inspect(original), inspect(result)
        old_segments, old_scan = jpeg_headers(original)
        new_segments, new_scan = jpeg_headers(result)
        assert old_scan == new_scan, "Compressed image scans changed"
        assert [x[2] for x in old_segments if not is_exif(x)] == [x[2] for x in new_segments if not is_exif(x)], "Non-EXIF segments changed"
        assert before["decoded_pixels_sha256"] == after["decoded_pixels_sha256"], "Decoded pixels changed"
        assert before["size"] == after["size"] and before["mode"] == after["mode"]
        assert before["artist"] == after["artist"] and before["copyright"] == after["copyright"]
        assert after["exif"] == {"DateTime": date_text, **{name: offset_text if name.startswith("Offset") else date_text for name in EXIF_DATE_TAGS}}
        destination.write_bytes(result)
        assert destination.read_bytes() == result
        assert source.read_bytes() == original and source.stat().st_mtime_ns == source_stat.st_mtime_ns
        records.append({
            "output_name": destination.name, "output_path": str(destination),
            "source_path": str(source), "source_sha256": sha256(original),
            "output_sha256": sha256(result), "source_size_bytes": len(original),
            "output_size_bytes": len(result), "search_term": term,
            "synthetic_demo_timestamp": captured.isoformat(),
            "expected_match_for_dog_on_target_date": expected_match,
            "original_exif": before["exif"], "output_exif": after["exif"],
            "image_size": after["size"], "image_mode": after["mode"],
            "decoded_pixels_sha256": after["decoded_pixels_sha256"],
            "compressed_scan_sha256": sha256(new_scan),
            "verification": {"source_bytes_and_mtime_unchanged": True,
                             "compressed_image_scan_unchanged": True,
                             "decoded_pixels_unchanged": True,
                             "non_exif_segments_unchanged": True,
                             "original_tiff_bytes_preserved_except_root_pointer": True,
                             "artist_and_copyright_unchanged": True,
                             "exif_dates_read_back_match": True},
            "attribution": {"manifest_title": title, "manifest_license_hint": license_hint,
                            "cache_attribution": attribution},
        })
    manifest = {
        "purpose": "Synthetic demo timestamps for photo-search validation; not real capture dates.",
        "target_date": target_date.isoformat(), "other_date": other_date.isoformat(),
        "timezone": args.timezone,
        "metadata_changes": ["DateTime", *EXIF_DATE_TAGS],
        "preservation": "Source files are unchanged. All non-EXIF JPEG segments and compressed image scans are byte-identical. Original TIFF bytes, including unknown metadata, are retained; only its root pointer is redirected to appended updated directories. Existing XMP dates are deliberately preserved.",
        "fixtures": records,
        "verified_count": len(records),
        "expected_matching_count": sum(item["expected_match_for_dog_on_target_date"] for item in records),
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"output": str(output), "verified_count": len(records),
                      "expected_matching_count": manifest["expected_matching_count"]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[2]
    parser.add_argument("--target-date", required=True, help="Explicit target capture date, YYYY-MM-DD")
    parser.add_argument("--other-date", required=True, help="Explicit negative-control capture date, YYYY-MM-DD")
    parser.add_argument("--timezone", required=True, help="IANA time zone, e.g. America/New_York")
    parser.add_argument("--cache", type=Path, default=root / "tools/demo_preload/cache")
    parser.add_argument("--manifest", type=Path, default=root / "tools/demo_preload/PHOTOS.tsv")
    parser.add_argument("--output", type=Path, required=True)
    prepare(parser.parse_args())
