#!/usr/bin/env python3
"""Regenerate the original fictional OCR fixtures without network access.

Requires Pillow and reportlab. Outputs are checked in, so phone preloading does
not require Python packages or fonts. Layout fonts can be supplied via --font-dir.
"""

import argparse
import io
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


NAVY = "#17324D"
TEAL = "#087E8B"
INK = "#172633"
PAPER = "#F5F8FA"


def pdf_page(c, title, subtitle, rows, page):
    c.setFillColor(HexColor(NAVY))
    c.rect(0, 670, 612, 122, fill=1, stroke=0)
    c.setFillColor(HexColor("#FFFFFF"))
    c.setFont("Helvetica-Bold", 15)
    c.drawString(44, 756, "DEMO / SAMPLE - FICTIONAL DOCUMENT")
    c.setFont("Helvetica-Bold", 25)
    c.drawString(44, 711, title)
    c.setFillColor(HexColor(INK))
    c.setFont("Helvetica", 13)
    c.drawString(44, 638, subtitle)
    y = 594
    for label, value in rows:
        c.setFillColor(HexColor(TEAL))
        c.setFont("Helvetica-Bold", 12)
        c.drawString(44, y, label.upper())
        c.setFillColor(HexColor(INK))
        c.setFont("Helvetica", 17)
        c.drawString(44, y - 25, value)
        y -= 70
    c.setStrokeColor(HexColor("#CBD5DF"))
    c.line(44, 62, 568, 62)
    c.setFont("Helvetica", 10)
    c.drawString(44, 43, "MobileClaw demo fixture. Not valid for travel, payment or booking.")
    c.drawRightString(568, 43, f"Page {page}")
    c.showPage()


def image_card(title, subtitle, rows, font_dir, size=(1400, 1500), mono_labels=()):
    image = Image.new("RGB", size, PAPER)
    draw = ImageDraw.Draw(image)
    regular = font_dir / "DejaVuSans.ttf"
    bold = font_dir / "DejaVuSans-Bold.ttf"

    def text(x, y, value, points, color=INK, heavy=False, font_file=None):
        font = ImageFont.truetype(str(font_file or (bold if heavy else regular)), points)
        if draw.textbbox((x, y), value, font=font)[2] > size[0] - 65:
            raise ValueError(f"Text exceeds card width: {value}")
        draw.text((x, y), value, font=font, fill=color)

    draw.rectangle((0, 0, size[0], 265), fill=NAVY)
    text(65, 38, "DEMO / SAMPLE - FICTIONAL", 35, "white", True)
    text(65, 108, title, 64, "white", True)
    text(65, 208, subtitle, 31, "white")
    y = 315
    for label, value in rows:
        text(65, y, label.upper(), 27, TEAL, True)
        # A separated phrase and a serifed mono numeral 1 keep the times legible to
        # OCR, avoiding confusion with lowercase l in this generated fixture.
        value_font = font_dir / "DejaVuSansMono.ttf" if label in mono_labels else None
        text(65, y + 46, value, 44, font_file=value_font)
        y += 145
    text(65, size[1] - 102, "MobileClaw sample. No real reservation or ticket.", 26)
    return image


def generate(output, font_dir):
    output.mkdir(parents=True, exist_ok=True)
    hotel = canvas.Canvas(str(output / "hotel-confirmation.pdf"), pagesize=(612, 792), invariant=1)
    hotel.setTitle("DEMO SAMPLE - Cedar House Austin confirmation")
    hotel.setAuthor("MobileClaw demo contributors")
    pdf_page(hotel, "Hotel confirmation", "Cedar House Austin - sample guest: Morgan Demo", [
        ("Confirmation", "LKVQ7X"),
        ("Check-in", "Sunday, October 25, 2026, after 15:00"),
        ("Check-out", "Friday, October 30, 2026, before 11:00"),
        ("Room", "King room, 14th floor, city view"),
        ("Stay", "5 nights at USD 209.00 per night"),
        ("Total including tax and parking", "USD 1,236.80"),
    ], 1)
    pdf_page(hotel, "Hotel guest information", "Cedar House Austin - applies to confirmation LKVQ7X", [
        ("Breakfast", "06:30-09:30, ground-floor dining room"),
        ("Wi-Fi network", "CedarDemo"),
        ("Wi-Fi access code", "MAPLEWAVE"),
        ("Early arrival", "Luggage storage available from 10:00"),
        ("Receipt", "Keep this confirmation for the sample expense claim"),
    ], 2)
    hotel.save()

    course = canvas.Canvas(str(output / "course-handout.pdf"), pagesize=(612, 792), invariant=1)
    course.setTitle("DEMO SAMPLE - CS2610 course handout")
    course.setAuthor("MobileClaw demo contributors")
    pdf_page(course, "CS2610 project handout", "Night Owls - fictional course exercise", [
        ("Final submission deadline", "Monday, November 2, 2026, 17:00"),
        ("Submit", "One ZIP per team through the sample course portal"),
        ("Report length", "6 pages excluding references"),
        ("Office hours", "Thursday, October 29, 2026, 15:00-16:00"),
        ("Office hours room", "Benedum 204"),
        ("Contact", "Course staff: Alex Demo"),
    ], 1)
    course.save()

    boarding = image_card("Boarding pass", "SAMPLE AIRLINE - NOT VALID FOR TRAVEL", [
        ("Flight / date", "UA1234 / October 25, 2026"),
        ("Route", "PIT to AUS"),
        ("Departure / boarding", "09:40 / 09:00"),
        ("Gate / seat", "B18 / 12A"),
        ("Record locator", "QXAT7P"),
        ("Passenger", "Morgan Demo"),
    ], font_dir)
    boarding.save(output / "boarding-pass.png", optimize=True)

    restaurant = image_card("Dinner reservation", "JUNIPER TABLE - FICTIONAL RESTAURANT", [
        ("Booking reference", "JNPR8K"),
        ("Date / arrival", "October 27, 2026 / 18:30"),
        ("Party size", "4 guests"),
        ("Table location", "Garden room"),
        ("Dietary note", "One vegetarian guest"),
        ("Change deadline", "Contact the host before 17:30"),
    ], font_dir)
    restaurant.save(output / "restaurant-card.png", optimize=True)

    note = image_card("Project sync notes", "OCTOBER 27, 2026 - FICTIONAL SCHEDULE", [
        ("Meeting", "Project sync, 14:00-15:30"),
        ("Location", "Benedum 204"),
        ("After the meeting", "Pack the demo phones until 15:45"),
        ("Return-call window", "From 15:45 until 16:15"),
        ("Next commitment", "Booth rehearsal at 16:30"),
        ("Participant", "Morgan Demo"),
    ], font_dir, mono_labels=("Return-call window",))
    # Image-only PDF exercises the render/OCR path, even with no text layer.
    raster = io.BytesIO()
    note.save(raster, format="PNG")
    raster.seek(0)
    meeting = canvas.Canvas(str(output / "meeting-note.pdf"), pagesize=(612, 656), invariant=1)
    meeting.setTitle("DEMO SAMPLE - scanned project sync note")
    meeting.setAuthor("MobileClaw demo contributors")
    meeting.drawImage(ImageReader(raster), 0, 0, width=612, height=656)
    meeting.showPage()
    meeting.save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "generated")
    parser.add_argument("--font-dir", type=Path, default=Path("/usr/share/fonts/truetype/dejavu"))
    args = parser.parse_args()
    generate(args.output, args.font_dir)
    print(f"Generated 3 PDFs and 2 PNGs in {args.output}")
