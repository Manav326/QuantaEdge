#!/usr/bin/env python3
"""Import complete official NCERT books, split their chapters locally, and stage both as DRAFTs.

Put the original PDFs in the repository's Books/ folder. Class 6 Maths/Science and Class 7-8 Science
use one complete-book PDF per class/subject/language. Class 7 and Class 8 Mathematics use two
official source PDFs (PART_1 and PART_2); those original PDFs are uploaded and assigned as separate
student textbook resources. A local combined reading copy is used only to map chapter page ranges
and is never uploaded or assigned. No chapter PDFs, CSV manifest, edition spreadsheet, or manually
entered chapter map is needed.

The importer:
- uses the live QuantaEdge curriculum to obtain canonical chapter IDs/codes;
- finds chapter-opening pages using official title aliases in the PDF's extracted text;
- fails closed unless every active chapter has a unique, sequentially verified start page;
- uploads single-volume PDFs byte-for-byte; uploads official Class 7/8 Mathematics Part I and Part II
  as separate textbook resources and never uploads their local combined chapter-mapping copy;
- extracts chapter ranges into private-review/ncert-book-splits without editing the source PDFs;
- validates all selected books and chapter mappings before writing chapter extracts or making the first API write;
- uploads each distinct PDF once and creates DRAFT assignments only;
- never publishes, archives, or alters an existing published assignment.

If the chapter titles in an official PDF cannot be read/identified, the script writes an error report
and performs no application-data writes. Do not use OCR or infer a page range by chapter number.

Authentication: set QUANTAEDGE_ADMIN_SESSION to the value of the QE_STAFF_SESSION cookie from a fresh Admin login.
API calls use the staff workspace identity; do not substitute the legacy QE_SESSION cookie.

Examples (always select the source languages explicitly):
  python scripts/import_official_ncert_books_and_chapters.py --languages english --api-url http://localhost:8080
  python scripts/import_official_ncert_books_and_chapters.py --languages english --api-url http://localhost:8080 --apply
  python scripts/import_official_ncert_books_and_chapters.py --languages english hindi --api-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlencode

try:
    import pymupdf as fitz
except ImportError as exc:
    raise SystemExit(
        'PyMuPDF is required. Install with: python -m pip install "PyMuPDF>=1.24,<2"'
    ) from exc

from import_quantaedge_learning_pdfs import api_call, api_url_valid, verify_session

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ("6", "7", "8")
SUBJECTS = ("maths", "science")
LANGUAGES = ("english", "hindi")
OFFICIAL_NCERT_CATALOG = "https://ncert.nic.in/textbook.php"
MAX_PDF_PAGES = 2000
ADMIN_MAX_BYTES = 500 * 1024 * 1024
STANDARD_MAX_BYTES = 50 * 1024 * 1024

# Exact current NCERT English chapter titles. Hindi aliases are read from the live curriculum
# and supplemented below where the canonical QuantaEdge title differs slightly from NCERT's.
ENGLISH_TITLES: dict[tuple[str, str], dict[str, list[str]]] = {
    ("6", "maths"): {
        "patterns-in-mathematics": ["Patterns in Mathematics"],
        "lines-and-angles": ["Lines and Angles"],
        "number-play": ["Number Play"],
        "data-handling": ["Data Handling and Presentation", "Data Handling"],
        "prime-time": ["Prime Time"],
        "perimeter-area": ["Perimeter and Area"],
        "fractions": ["Fractions"],
        "constructions": ["Playing with Constructions", "Playing with Construction"],
        "symmetry": ["Symmetry"],
        "integers-beyond-zero": ["The Other Side of Zero"],
    },
    ("6", "science"): {
        "wonderful-world-science": ["The Wonderful World of Science"],
        "diversity-living-world": ["Diversity in the Living World"],
        "mindful-eating": ["Mindful Eating: A Path to a Healthy Body"],
        "exploring-magnets": ["Exploring Magnets"],
        "measurement-length-motion": ["Measurement of Length and Motion"],
        "materials-around-us": ["Materials Around Us"],
        "temperature-measurement": ["Temperature and its Measurement", "Temperature and Its Measurement"],
        "states-of-water": ["A Journey through States of Water"],
        "separation-methods": ["Methods of Separation in Everyday Life"],
        "living-characteristics": ["Living Creatures: Exploring their Characteristics", "Living Creatures: Exploring Their Characteristics"],
        "natures-treasures": ["Nature's Treasures", "Nature’s Treasures"],
        "beyond-earth": ["Beyond Earth"],
    },
    ("7", "maths"): {
        "large-numbers-around-us": ["Large Numbers Around Us"],
        "arithmetic-expressions": ["Arithmetic Expressions"],
        "view-beyond-point": ["A Peek Beyond the Point"],
        "useful-expressions-with-letters": ["Expressions Using Letter-Numbers", "Expressions using Letter-Numbers"],
        "parallel-and-intersecting-lines": ["Parallel and Intersecting Lines"],
        "number-play": ["Number Play"],
        "story-of-three-intersecting-lines": ["A Tale of Three Intersecting Lines"],
        "working-with-fractions": ["Working with Fractions"],
        "geometric-twins": ["Geometric Twins"],
        "operations-with-integers": ["Operations with Integers"],
        "finding-common-ground": ["Finding Common Ground"],
        "another-peek-beyond-point": ["Another Peek Beyond the Point"],
        "connecting-the-dots": ["Connecting the Dots", "Connecting the Dots..."],
        "constructions-and-tilings": ["Constructions and Tilings"],
        "finding-the-unknown": ["Finding the Unknown"],
    },
    ("7", "science"): {
        "ever-growing-universe-of-science": ["The Ever-Evolving World of Science", "The Ever Evolving World of Science"],
        "acids-bases-neutrals": ["Exploring Substances: Acidic, Basic, and Neutral"],
        "electricity-circuits-components": ["Electricity: Circuits and their Components", "Electricity: Circuits and Their Components"],
        "world-of-metals-nonmetals": ["The World of Metals", "Metals and Non-metals"],
        "physical-chemical-changes": ["Changes Around Us: Physical and Chemical"],
        "adolescence-growth-change": ["Adolescence: A Stage of Growth and Change"],
        "heat-transfer-in-nature": ["Heat Transfer in Nature", "Heat Transfer"],
        "measurement-of-time-motion": ["Measurement of Time and Motion"],
        "animal-life-processes": ["Life Processes in Animals", "Life Processes"],
        "plant-life-processes": ["Life Processes in Plants"],
        "light-shadow-reflection": ["Light: Shadows and Reflections"],
        "earth-moon-sun": ["Earth, Moon, and the Sun", "Earth, Moon and the Sun"],
    },
    ("8", "maths"): {
        "squares-and-cubes": ["A Square and A Cube", "A Square and a Cube", "Squares and Cubes"],
        "exponents-play": ["Power Play", "Exponents Play"],
        "story-of-numbers": ["A Story of Numbers", "Story of Numbers"],
        "quadrilaterals": ["Quadrilaterals"],
        "number-play": ["Number Play"],
        "sharing-makes-grow": ["We Distribute, Yet Things Multiply", "Sharing Makes Grow"],
        "proportional-reasoning-1": ["Proportional Reasoning-1", "Proportional Reasoning–1", "Proportional Reasoning 1"],
        "fractions-in-disguise": ["Fractions in Disguise"],
        "baudhayana-pythagoras-theorem": [
            "The Baudhayana-Pythagoras Theorem", "The Baudhayana–Pythagoras Theorem",
            "Baudhayana-Pythagoras Theorem", "The Baudhāyana-Pythagoras Theorem",
            "The Baudhāyana–Pythagoras Theorem", "Baudhāyana-Pythagoras Theorem",
            "Baudhāyana–Pythagoras Theorem",
        ],
        "proportional-reasoning-2": ["Proportional Reasoning-2", "Proportional Reasoning–2", "Proportional Reasoning 2"],
        "exploring-geometric-themes": ["Exploring Some Geometric Themes", "Exploring Geometric Themes"],
        "tales-by-dots-and-lines": ["Tales by Dots and Lines"],
        "algebra-play": ["Algebra Play"],
        "area": ["Area"],
    },
    ("8", "science"): {
        "world-of-scientific-exploration": ["Exploring the Investigative World of Science", "The World of Scientific Exploration", "The Investigative World of Science"],
        "invisible-microbial-world": ["The Invisible Living World: Beyond Our Naked Eye", "The Invisible World of Microbes", "The Invisible Living World"],
        "health-priceless-treasure": ["Health: The Ultimate Treasure", "Health: An Ultimate Treasure"],
        "electricity-magnetic-thermal-effects": ["Electricity: Magnetic and Heating Effects", "Electricity: Magnetic and Thermal Effects"],
        "knowing-forces": ["Exploring Forces", "Knowing Forces"],
        "pressure-wind-storms-cyclones": ["Pressure, Winds, Storms, and Cyclones", "Pressure, Winds, Storms and Cyclones"],
        "particulate-nature-matter": ["Particulate Nature of Matter"],
        "elements-compounds-mixtures": ["Nature of Matter: Elements, Compounds, and Mixtures", "Nature of Matter: Elements, Compounds and Mixtures"],
        "solutes-solvents-solutions": ["The Amazing World of Solutes, Solvents, and Solutions", "The Amazing World of Solutes, Solvents and Solutions"],
        "light-mirrors-lenses": ["Light: Mirrors and Lenses"],
        "celestial-events-timekeeping": ["Keeping Time with the Skies", "Celestial Events and Timekeeping"],
        "nature-in-harmony": ["How Nature Works in Harmony", "Nature in Harmony"],
        "earth-unique-life-supporting-planet": ["Our Home: Earth, a Unique Life Sustaining Planet", "Our Home: Earth, a Unique Life-Sustaining Planet"],
    },
}

# Short official chapter titles are valid (e.g. Circle, Square, or Area). Any title with
# fewer than eight normalized characters is matched only as a standalone, positioned heading
# near the top of a page; it is never matched loosely inside body text.
MIN_CHAPTER_TITLE_LENGTH = 4

HINDI_TITLE_ALIASES: dict[tuple[str, str, str], list[str]] = {
    ("6", "science", "wonderful-world-science"): ["विज्ञान का अद्भुत संसार", "विज्ञान का अनूठा संसार"],
    ("6", "science", "mindful-eating"): ["उचित आहार: स्वस्थ शरीर का आधार", "उचित आहार—स्वस्थ शरीर का आधार"],
    ("6", "science", "measurement-length-motion"): ["लंबाई एवं गति का मापन", "लंबाई और गति का मापन"],
    ("6", "science", "living-characteristics"): ["सजीव—विशेषताओं का अन्वेषण", "सजीव: विशेषताओं का अन्वेषण"],
    ("6", "maths", "data-handling"): ["आँकड़ों का प्रबंधन और प्रस्तुतीकरण", "आँकड़ों का प्रबंधन एवं प्रस्तुतीकरण"],
    ("6", "maths", "constructions"): ["रचनाओं के साथ खेलना"],
    ("7", "maths", "large-numbers-around-us"): ["हमारे आस-पास की बड़ी संख्याएँ"],
    ("7", "maths", "arithmetic-expressions"): ["अंकगणितीय व्यंजक"],
    ("7", "maths", "view-beyond-point"): ["बिंदु से परे एक दृष्टि"],
    ("7", "maths", "useful-expressions-with-letters"): ["अक्षर-संख्याओं के उपयोगी व्यंजक"],
    ("7", "maths", "parallel-and-intersecting-lines"): ["समांतर और प्रतिच्छेदी रेखाएँ"],
    ("7", "maths", "story-of-three-intersecting-lines"): ["तीन प्रतिच्छेदी रेखाओं की एक कथा"],
    ("7", "maths", "geometric-twins"): ["ज्यामितीय जुड़वाँ आकृतियाँ"],
    ("7", "maths", "operations-with-integers"): ["पूर्णांकों पर संक्रियाएँ"],
    ("7", "maths", "finding-common-ground"): ["समान आधार खोजना"],
    ("7", "maths", "another-peek-beyond-point"): ["बिंदु से परे एक और झलक"],
    ("7", "maths", "connecting-the-dots"): ["बिंदुओं को जोड़ना"],
    ("7", "maths", "constructions-and-tilings"): ["रचनाएँ और फर्श-बिछावन"],
    ("7", "maths", "finding-the-unknown"): ["अज्ञात मान खोजना"],
    ("7", "science", "ever-growing-universe-of-science"): ["विज्ञान का निरंतर बढ़ता संसार"],
    ("7", "science", "acids-bases-neutrals"): ["पदार्थों का अन्वेषण — अम्लीय, क्षारीय एवं उदासीन", "पदार्थों का अन्वेषण"],
    ("7", "science", "electricity-circuits-components"): ["विद्युत — परिपथ एवं उनके घटक"],
    ("7", "science", "world-of-metals-nonmetals"): ["धातुओं और अधातुओं का संसार"],
    ("7", "science", "physical-chemical-changes"): ["हमारे आस-पास के परिवर्तन — भौतिक एवं रासायनिक"],
    ("7", "science", "adolescence-growth-change"): ["किशोरावस्था — वृद्धि एवं परिवर्तन की अवस्था"],
    ("7", "science", "heat-transfer-in-nature"): ["प्रकृति में ऊष्मा का स्थानांतरण"],
    ("7", "science", "measurement-of-time-motion"): ["समय एवं गति का मापन"],
    ("7", "science", "animal-life-processes"): ["जंतुओं में जैव प्रक्रम"],
    ("7", "science", "plant-life-processes"): ["पादपों में जैव प्रक्रम"],
    ("7", "science", "light-shadow-reflection"): ["प्रकाश — छाया एवं परावर्तन"],
    ("7", "science", "earth-moon-sun"): ["पृथ्वी, चंद्रमा एवं सूर्य"],
    ("8", "maths", "squares-and-cubes"): ["एक वर्ग और एक घन", "वर्ग और घन"],
    ("8", "maths", "exponents-play"): ["घातों का खेल"],
    ("8", "maths", "story-of-numbers"): ["संख्याओं की कहानी"],
    ("8", "maths", "sharing-makes-grow"): ["बाँटने से वृद्धि", "जितना बाँटेंगे उतना बढ़ेगा"],
    ("8", "maths", "proportional-reasoning-1"): ["समानुपातिक तर्कण-1"],
    ("8", "maths", "proportional-reasoning-2"): ["समानुपातिक तर्कण-2"],
    ("8", "maths", "baudhayana-pythagoras-theorem"): ["बौधायन–पाइथागोरस प्रमेय", "बौधायन-पाइथागोरस प्रमेय"],
    ("8", "maths", "exploring-geometric-themes"): ["कुछ ज्यामितीय विषयों की खोज"],
    ("8", "maths", "tales-by-dots-and-lines"): ["बिंदुओं और रेखाओं की कथाएँ"],
    ("8", "maths", "algebra-play"): ["बीजगणित का खेल"],
    ("8", "maths", "fractions-in-disguise"): ["भिन्नों के छिपे रूप"],
    ("8", "science", "world-of-scientific-exploration"): ["विज्ञान के अन्वेषी संसार की खोज यात्रा"],
    ("8", "science", "invisible-microbial-world"): ["अदृश्य जीव-जगत — हमारी आँखों की क्षमता से परे"],
    ("8", "science", "health-priceless-treasure"): ["स्वास्थ्य — एक अमूल्य निधि"],
    ("8", "science", "electricity-magnetic-thermal-effects"): ["विद्युत — चुंबकीय एवं तापीय प्रभाव"],
    ("8", "science", "knowing-forces"): ["बलों को जानें"],
    ("8", "science", "pressure-wind-storms-cyclones"): ["दाब, पवन, झंझावात एवं चक्रवात"],
    ("8", "science", "particulate-nature-matter"): ["द्रव्य की कणीय प्रकृति"],
    ("8", "science", "elements-compounds-mixtures"): ["द्रव्य की प्रकृति — तत्व, यौगिक और मिश्रण"],
    ("8", "science", "solutes-solvents-solutions"): ["विलेयों, विलायकों और विलयनों का अद्भुत संसार"],
    ("8", "science", "light-mirrors-lenses"): ["प्रकाश — दर्पण एवं लेंस"],
    ("8", "science", "celestial-events-timekeeping"): ["आकाशीय परिघटनाएँ और काल-निर्धारण"],
    ("8", "science", "nature-in-harmony"): ["प्रकृति कैसे सामंजस्य में कार्य करती है"],
    ("8", "science", "earth-unique-life-supporting-planet"): ["हमारा आवास — पृथ्वी एक अद्वितीय जीवनदायी ग्रह"],
}

DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
CONTENT_TERMS = (
    "contents", "tableofcontents", "विषयसूची", "विषयवस्तुसूची", "अनुक्रमणिका", "विषयक्रम"
)
END_MATTER_TITLES = (
    "index", "glossary", "bibliography", "references", "acknowledgements",
    "acknowledgments", "शब्दावली", "अनुक्रमणिका", "संदर्भ", "आभार"
)


def report_progress(message: str) -> None:
    """Emit a timestamped, immediately flushed progress line for interactive terminals."""
    stamp = datetime.now().astimezone().strftime("%H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


def format_bytes(byte_count: int) -> str:
    value = float(byte_count)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{value:.1f} GiB"


def label_subject(code: str) -> str:
    return "Mathematics" if code == "maths" else "Science"


def label_language(code: str) -> str:
    return "English" if code == "english" else "Hindi"


def book_filename(grade: str, subject: str, language: str) -> str:
    return f"NCERT_Class_{grade}_{'Maths' if subject == 'maths' else 'Science'}_{label_language(language)}_BOOK.pdf"


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", str(value or "")).translate(DEVANAGARI_DIGITS).casefold()
    return "".join(ch for ch in value if ch.isalnum())


def normalize_space(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", str(value or ""))).strip()


def chapter_is_active(row: dict[str, Any]) -> bool:
    value = row.get("chapter_active", row.get("active", True))
    return value is not False and str(value).strip().casefold() not in {"false", "0", "no"}


def dedupe_live_chapters(rows: list[Any], grade: str, subject: str) -> list[dict[str, Any]]:
    """The Admin content endpoint has one row per chapter lesson; dedupe by chapter ID."""
    by_id: dict[int, dict[str, Any]] = {}
    for raw in rows:
        if not isinstance(raw, dict) or raw.get("chapter_id") is None:
            continue
        if str(raw.get("class_code")) != grade or str(raw.get("subject_code")) != subject:
            continue
        if not raw.get("chapter_code") or not chapter_is_active(raw):
            continue
        chapter_id = int(raw["chapter_id"])
        candidate = dict(raw)
        existing = by_id.get(chapter_id)
        if existing is not None:
            if str(existing.get("chapter_code")) != str(candidate.get("chapter_code")):
                raise RuntimeError(
                    f"Chapter ID {chapter_id} has inconsistent chapter codes in the Admin content response."
                )
            if str(existing.get("chapter_name")) != str(candidate.get("chapter_name")):
                raise RuntimeError(
                    f"Chapter ID {chapter_id} has inconsistent chapter names in the Admin content response."
                )
            continue
        by_id[chapter_id] = candidate
    return sorted(
        by_id.values(),
        key=lambda row: (
            int(row.get("chapter_sort_order") or row.get("sort_order") or 0),
            str(row.get("chapter_code") or ""),
        ),
    )


def read_chapters(
    base: str,
    cookie: str,
    progress: Callable[[str], None] | None = None,
) -> dict[tuple[str, str], list[dict[str, Any]]]:
    result: dict[tuple[str, str], list[dict[str, Any]]] = {}
    tracks = [(grade, subject) for grade in CLASSES for subject in SUBJECTS]
    for track_index, (grade, subject) in enumerate(tracks, 1):
        if progress:
            progress(
                f"[Curriculum {track_index}/{len(tracks)}] Fetching active Class {grade} "
                f"{label_subject(subject)} chapters from the Admin API."
            )
        query = urlencode({"classCode": grade, "subjectCode": subject})
        # Use the real, permission-protected Admin content route. It includes current
        # canonical chapter IDs/codes and may repeat chapters once for each lesson.
        rows = api_call(base, cookie, "/api/v1/admin/content?" + query)
        if not isinstance(rows, list):
            raise RuntimeError(f"Unexpected Admin content response for Class {grade} / {subject}.")
        active = dedupe_live_chapters(rows, grade, subject)
        if not active:
            raise RuntimeError(f"No active curriculum chapter returned for Class {grade} / {subject}.")
        codes = [str(row["chapter_code"]) for row in active]
        if len(codes) != len(set(codes)):
            raise RuntimeError(f"Duplicate chapter codes returned for Class {grade} / {subject}.")
        result[(grade, subject)] = active
        if progress:
            progress(
                f"[Curriculum {track_index}/{len(tracks)}] Class {grade} "
                f"{label_subject(subject)}: {len(active)} active chapters loaded."
            )
    return result


def aliases_for(grade: str, subject: str, language: str, chapter: dict[str, Any]) -> list[str]:
    code = str(chapter.get("chapter_code") or "")
    live_name = normalize_space(str(chapter.get("chapter_name") or ""))
    english_titles = ENGLISH_TITLES.get((grade, subject), {}).get(code, [])

    if language == "english":
        # The live Admin curriculum can return the Hindi display name even when this
        # import selected an English PDF. Never leak that Hindi title into English
        # matching or its console diagnostics; use the known English NCERT title first.
        candidates = list(english_titles)
        if live_name and not re.search(r"[\u0900-\u097F]", live_name):
            candidates.append(live_name)
    else:
        candidates = [live_name]
        candidates.extend(HINDI_TITLE_ALIASES.get((grade, subject, code), []))
        candidates.extend(english_titles)

    clean: list[str] = []
    seen: set[str] = set()
    for item in candidates:
        item = normalize_space(item)
        norm = normalize_text(item)
        if len(norm) >= MIN_CHAPTER_TITLE_LENGTH and norm not in seen:
            seen.add(norm)
            clean.append(item)
    return clean


def strip_heading_prefix(line: str) -> str:
    line = normalize_space(line).translate(DEVANAGARI_DIGITS)
    line = re.sub(
        r"(?i)^\s*(?:chapter|अध्याय|पाठ)\s*[#.:–—-]?\s*\d{1,2}\s*[).:–—-]?\s*",
        "", line,
    )
    line = re.sub(r"^\s*\d{1,2}(?:\s*[).:–—-]\s*|\s+)", "", line)
    return line.strip()


def line_matches_title(line: str, alias: str) -> bool:
    core = normalize_text(strip_heading_prefix(line))
    target = normalize_text(alias)
    if not core or not target:
        return False
    if core == target:
        return True
    if not core.startswith(target):
        return False

    suffix = core[len(target):]
    # Table-of-contents lines commonly append a printed page number after the title.
    if suffix.isdigit() and len(suffix) <= 3:
        return True

    # In NCERT's Ganita Prakash PDFs, text extraction can concatenate the chapter
    # heading with its running header, e.g.:
    # "THE BAUDHĀYANA-PYTHAGORAS THEOREM 2Ganita Prakash | Grade 8 | Part-II".
    # Accept only this known, bounded book-header suffix, not arbitrary trailing prose.
    if re.fullmatch(r"\d{0,3}ganitaprakashgrade[678]part(?:i|ii)", suffix):
        return True
    return False


def page_title_hits(text: str, chapters: list[dict[str, Any]], aliases_by_code: dict[str, list[str]],
                    *, top_lines: int | None) -> set[str]:
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    if top_lines is not None:
        lines = lines[:top_lines]
    hits: set[str] = set()
    for chapter in chapters:
        code = str(chapter["chapter_code"])
        aliases = aliases_by_code[code]
        for alias in aliases:
            # Titles shorter than eight normalized characters are handled only through
            # positioned standalone-heading checks below; arbitrary top-line/body matches
            # can mistake short mathematical terms for a chapter boundary.
            if len(normalize_text(alias)) < 8:
                continue
            # Match one line or title wrapped across up to three consecutive lines.
            for idx, line in enumerate(lines):
                if line_matches_title(line, alias):
                    hits.add(code)
                    break
                if idx + 1 < len(lines) and line_matches_title(line + " " + lines[idx + 1], alias):
                    hits.add(code)
                    break
                if idx + 2 < len(lines) and line_matches_title(
                    line + " " + lines[idx + 1] + " " + lines[idx + 2], alias
                ):
                    hits.add(code)
                    break
            if code in hits:
                break
    return hits


def page_has_contents_marker(text: str) -> bool:
    # Restrict this check to page headers; the word "contents" can legitimately appear
    # inside a lesson paragraph and must not suppress a genuine chapter-opening page.
    header = normalize_text("\\n".join((text or "").splitlines()[:12]))
    return any(term in header for term in CONTENT_TERMS)


def positioned_page_title_hits(
    page: Any,
    chapters: list[dict[str, Any]],
    aliases_by_code: dict[str, list[str]],
    *,
    max_top_fraction: float = 0.72,
) -> set[str]:
    """Fallback to PDF text geometry when extraction order pushes a heading past line 18.

    Only exact alias searches whose text boxes are in the upper 72% of the page count.
    A page must still identify exactly one known chapter title, and contents pages are
    filtered separately. This does not guess page numbers or use fuzzy title matching.
    """
    page_height = float(page.rect.height)
    top_limit = page_height * max_top_fraction
    hits: set[str] = set()
    for chapter in chapters:
        code = str(chapter["chapter_code"])
        for alias in aliases_by_code[code]:
            if len(normalize_text(alias)) < 8:
                # Common short words are risky search terms. For any short official title,
                # require an entire extracted text line to equal the title (optionally with a
                # chapter-number prefix) and place it in the upper 55%
                # of the page. Do not accept an incidental word inside a sentence or a
                # lower-page section heading as a chapter opener.
                short_heading_found = False
                for block in page.get_text("dict").get("blocks", []):
                    for line in block.get("lines", []):
                        line_text = " ".join(
                            str(span.get("text", "")) for span in line.get("spans", [])
                        ).strip()
                        bbox = line.get("bbox", (0, page_height, 0, page_height))
                        if (
                            line_matches_title(line_text, alias)
                            and float(bbox[1]) <= page_height * 0.55
                        ):
                            short_heading_found = True
                            break
                    if short_heading_found:
                        break
                if short_heading_found:
                    hits.add(code)
                    break
                continue

            matches = page.search_for(alias)
            if any(float(rect.y0) <= top_limit for rect in matches):
                hits.add(code)
                break
    return hits


def detect_chapter_ranges(
    pdf_path: Path,
    grade: str,
    subject: str,
    language: str,
    chapters: list[dict[str, Any]],
    progress: Callable[[str], None] | None = None,
) -> list[dict[str, Any]]:
    """Find chapter boundaries by the actual printed title; fail closed on any ambiguity."""
    alias_by_code = {
        str(ch["chapter_code"]): aliases_for(grade, subject, language, ch)
        for ch in chapters
    }
    missing_aliases = [code for code, aliases in alias_by_code.items() if not aliases]
    if missing_aliases:
        raise ValueError(f"{pdf_path.name}: no chapter title aliases available for {missing_aliases}.")
    try:
        with fitz.open(pdf_path) as pdf:
            page_count = pdf.page_count
            if not 1 <= page_count <= MAX_PDF_PAGES:
                raise ValueError(f"{pdf_path.name}: page count {page_count} is outside 1–{MAX_PDF_PAGES}.")
            if progress:
                progress(
                    f"Scanning {page_count} pages of {pdf_path.name} for exact chapter-title headings "
                    f"({len(chapters)} active chapters)."
                )
            page_texts: list[str] = []
            positioned_hits_by_page: list[set[str]] = []
            progress_interval = max(1, page_count // 10)
            for page_index, page in enumerate(pdf):
                text = page.get_text("text") or ""
                page_texts.append(text)
                if text.strip() and not page_has_contents_marker(text):
                    positioned_hits_by_page.append(
                        positioned_page_title_hits(page, chapters, alias_by_code)
                    )
                else:
                    positioned_hits_by_page.append(set())
                scanned = page_index + 1
                if progress and (
                    scanned == 1 or scanned % progress_interval == 0 or scanned == page_count
                ):
                    progress(
                        f"PDF text scan: {scanned}/{page_count} pages "
                        f"({round(scanned * 100 / page_count)}%) — {pdf_path.name}"
                    )
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise
        raise ValueError(f"{pdf_path.name}: PDF is unreadable or text extraction failed: {exc}") from exc

    candidate_pages: dict[str, list[int]] = {str(ch["chapter_code"]): [] for ch in chapters}
    for zero_index, text in enumerate(page_texts):
        if not text.strip() or page_has_contents_marker(text):
            continue
        # Contents pages are excluded if multiple chapter names appear at the top.
        top_hits = page_title_hits(text, chapters, alias_by_code, top_lines=18)
        if len(top_hits) == 1:
            code = next(iter(top_hits))
        elif not top_hits:
            # PDF text extraction order is not necessarily visual order. For example,
            # a decorative chapter heading can appear after body text in the extracted
            # stream even though it is visibly at the top of the page. Recover only an
            # exact, positioned title hit; do not relax the unique-title requirement.
            positioned_hits = positioned_hits_by_page[zero_index]
            if len(positioned_hits) != 1:
                continue
            code = next(iter(positioned_hits))
        else:
            # Multiple heading hits remain ambiguous and are never guessed.
            continue
        candidate_pages[code].append(zero_index + 1)

    starts: list[dict[str, Any]] = []
    previous_page = 0
    for chapter in chapters:
        code = str(chapter["chapter_code"])
        # Page candidates are derived from title text, not from chapter number assumptions.
        available = [page for page in candidate_pages[code] if page > previous_page]
        if not available:
            aliases = alias_by_code[code]
            # Report the selected book's language, not a possibly Hindi live-curriculum label.
            title = aliases[0] if aliases else code
            raise ValueError(
                f"{pdf_path.name}: cannot identify the opening page for chapter {title!r} "
                f"(code={code}, expected active order {len(starts) + 1}). "
                f"Checked title aliases: {aliases}. No application files have been uploaded."
            )
        start_page = available[0]
        # Detect ambiguous same-title starts before the next chapter's title. The next pass
        # validates the actual sequence and exact active chapter count.
        starts.append({
            "chapter_id": int(chapter["chapter_id"]),
            "chapter_code": code,
            "chapter_name": str(chapter.get("chapter_name") or code),
            "start_page": start_page,
            "matched_candidate_pages": candidate_pages[code],
        })
        previous_page = start_page

    if len(starts) != len(chapters) or len({row["start_page"] for row in starts}) != len(starts):
        raise ValueError(f"{pdf_path.name}: chapter boundary mapping is not one-to-one.")

    # If the PDF has a printed back-matter section after the final chapter, keep it with the
    # complete-book PDF but exclude it from the last chapter PDF.
    last_end = page_count
    final_start = starts[-1]["start_page"]
    for zero_index in range(final_start, page_count):
        lines = [normalize_text(x) for x in page_texts[zero_index].splitlines() if x.strip()]
        if not lines:
            continue
        first = lines[0]
        if any(first.startswith(normalize_text(term)) for term in END_MATTER_TITLES):
            last_end = zero_index
            break

    for idx, row in enumerate(starts):
        end_page = starts[idx + 1]["start_page"] - 1 if idx + 1 < len(starts) else last_end
        if end_page < row["start_page"]:
            raise ValueError(f"{pdf_path.name}: invalid page range for {row['chapter_code']}.")
        row["end_page"] = end_page
        row["page_count"] = end_page - row["start_page"] + 1
        row["chapter_order"] = idx + 1
        # The live QuantaEdge curriculum is the authoritative chapter ID/code. A visible title
        # disagreement is reported for Admin preview rather than used to guess another ID.
        row["mapping_note"] = "Mapped by printed NCERT title to exact live curriculum chapter code."
        if progress:
            progress(
                f"Matched chapter {idx + 1}/{len(starts)}: {row['chapter_name']} "
                f"(printed PDF pages {row['start_page']}–{row['end_page']})."
            )
    return starts


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_book(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"Required complete-book PDF is missing: {path.name}")
    size = path.stat().st_size
    with path.open("rb") as handle:
        signature = handle.read(5)
    if signature != b"%PDF-" or size <= 0:
        raise ValueError(f"{path.name}: file is empty or does not have a valid PDF signature.")
    try:
        with fitz.open(path) as pdf:
            pages = pdf.page_count
            if not 1 <= pages <= MAX_PDF_PAGES:
                raise ValueError(f"{path.name}: page count {pages} is outside 1–{MAX_PDF_PAGES}.")
            first_text = "\n".join((page.get_text("text") or "") for page in list(pdf)[:15])
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise
        raise ValueError(f"{path.name}: cannot be opened: {exc}") from exc

    # Prefer an explicit reprint/session statement. If absent, keep a clear reviewer reminder.
    edition_match = re.search(
        r"(?im)\b(reprint(?:ed)?\s*(?:in\s*)?[-:]?\s*(?:20\d{2})(?:\s*[-/–]\s*\d{2,4})?)",
        first_text,
    )
    if not edition_match:
        edition_match = re.search(
            r"(?im)\b((?:first|revised)\s+edition\s+(?:of\s+)?20\d{2})\b", first_text
        )
    edition_detected = normalize_space(edition_match.group(1)) if edition_match else ""
    if not edition_detected:
        edition_detected = "NCERT source PDF — verify printed edition before publication"
    return {
        "path": str(path.resolve()),
        "filename": path.name,
        "size_bytes": size,
        "page_count": pages,
        "sha256": sha256_file(path),
        "edition": edition_detected,
        "edition_detected": bool(edition_match),
    }


def chapter_output_filename(grade: str, subject: str, language: str, code: str) -> str:
    return (
        f"NCERT_Class_{grade}_{'Maths' if subject == 'maths' else 'Science'}_"
        f"{label_language(language)}_CHAPTER_{code}.pdf"
    )


def student_title(grade: str, subject: str, language: str, scope: str,
                  chapter: dict[str, Any] | None = None,
                  book_part: int | None = None) -> str:
    base = f"NCERT | Class {grade} | {label_subject(subject)} | {label_language(language)}"
    if scope == "SUBJECT_BOOK":
        if book_part is None:
            return base + " | Complete Book"
        roman_part = ("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")[book_part - 1] if 1 <= book_part <= 9 else str(book_part)
        part_label = f"Part {roman_part}" if language == "english" else f"भाग {book_part}"
        return base + " | " + part_label
    assert chapter is not None
    visible_title = (
        chapter["chapter_name"] if language == "hindi"
        else ENGLISH_TITLES.get((grade, subject), {}).get(chapter["chapter_code"], [chapter["chapter_name"]])[0]
    )
    return base + f" | Chapter {chapter['chapter_order']:02d}: {visible_title}"


def split_chapter_pdf(source: Path, target: Path, start_page: int, end_page: int) -> dict[str, Any]:
    """Write a page-range extract without modifying or re-saving the original whole book."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + ".tmp")
    with fitz.open(source) as src, fitz.open() as out:
        out.insert_pdf(src, from_page=start_page - 1, to_page=end_page - 1, links=True, annots=True)
        out.set_metadata({
            **(src.metadata or {}),
            "title": target.stem,
            "subject": "Exact page-range extract from the supplied NCERT source PDF",
            "keywords": "NCERT; QuantaEdge; chapter PDF",
        })
        out.save(temp, garbage=3, deflate=True)
    temp.replace(target)
    return {
        "path": str(target.resolve()),
        "filename": target.name,
        "size_bytes": target.stat().st_size,
        "page_count": end_page - start_page + 1,
        "sha256": sha256_file(target),
        "source_page_start": start_page,
        "source_page_end": end_page,
    }


def query_library_assets(base: str, cookie: str) -> dict[str, dict[str, Any]]:
    rows = api_call(base, cookie, "/api/v1/admin/learning-pdfs")
    if not isinstance(rows, list):
        raise RuntimeError("PDF library API returned an unexpected response.")
    return {
        str(row.get("sha256") or "").lower(): row
        for row in rows if isinstance(row, dict) and row.get("sha256")
    }


def query_assignments(
    base: str,
    cookie: str,
    tracks: set[tuple[str, str]],
    progress: Callable[[str], None] | None = None,
) -> tuple[dict[tuple[str, str, str, str, int | None, int | None], set[str]], dict[str, dict[str, Any]]]:
    hashes_by_slot: dict[tuple[str, str, str, str, int | None, int | None], set[str]] = {}
    assignment_by_slot_hash: dict[str, dict[str, Any]] = {}
    ordered_tracks = sorted(tracks)
    for track_index, (grade, subject) in enumerate(ordered_tracks, 1):
        if progress:
            progress(
                f"[Existing assignments {track_index}/{len(ordered_tracks)}] Checking Class {grade} "
                f"{label_subject(subject)} for already-assigned PDFs."
            )
        query = urlencode({"classCode": grade, "subjectCode": subject, "status": "ALL"})
        rows = api_call(base, cookie, "/api/v1/admin/learning-documents?" + query)
        if not isinstance(rows, list):
            raise RuntimeError(f"Assignment list response is invalid for Class {grade}/{subject}.")
        for row in rows:
            if not isinstance(row, dict):
                continue
            key = (
                str(row.get("class_code")), str(row.get("subject_code")),
                str(row.get("language")), str(row.get("scope")),
                int(row["chapter_id"]) if row.get("chapter_id") is not None else None,
                int(row["book_part"]) if row.get("book_part") is not None else None,
            )
            digest = str(row.get("sha256") or "").lower()
            if digest:
                hashes_by_slot.setdefault(key, set()).add(digest)
                assignment_by_slot_hash[repr((key, digest))] = row
    return hashes_by_slot, assignment_by_slot_hash


def part_book_filename(grade: str, subject: str, language: str, part: int) -> str:
    return (
        f"NCERT_Class_{grade}_{'Maths' if subject == 'maths' else 'Science'}_"
        f"{label_language(language)}_BOOK_PART_{part}.pdf"
    )


def validate_input_book_presence(books_dir: Path, languages: tuple[str, ...]) -> None:
    """Fail early unless every selected class/subject/language slot has complete source input."""
    if not books_dir.is_dir():
        raise FileNotFoundError(f"NCERT source-books directory does not exist: {books_dir}")
    if not languages or any(language not in LANGUAGES for language in languages):
        raise ValueError(f"Unsupported or empty language selection: {languages!r}. Choose from {LANGUAGES}.")
    if len(set(languages)) != len(languages):
        raise ValueError(f"Duplicate language selection: {languages!r}.")

    missing: list[str] = []
    for grade in CLASSES:
        for subject in SUBJECTS:
            for language in languages:
                canonical = books_dir / book_filename(grade, subject, language)
                if canonical.is_file():
                    continue
                if grade in {"7", "8"} and subject == "maths":
                    part1 = books_dir / part_book_filename(grade, subject, language, 1)
                    part2 = books_dir / part_book_filename(grade, subject, language, 2)
                    if part1.is_file() and part2.is_file():
                        continue
                    if part1.is_file() != part2.is_file():
                        present = part1.name if part1.is_file() else part2.name
                        missing_name = part2.name if part1.is_file() else part1.name
                        missing.append(f"Found {present} but its matching part is missing: {missing_name}")
                    else:
                        missing.append(
                            f"Missing {canonical.name}; alternatively provide both {part1.name} and {part2.name}"
                        )
                else:
                    missing.append(f"Missing {canonical.name}")
    if missing:
        raise FileNotFoundError(
            f"Input precheck failed for {len(missing)} required source-book slot(s) in {books_dir}. "
            "No PDF was uploaded or rewritten.\n - " + "\n - ".join(missing)
        )


def prepare_source_book(
    root: Path,
    grade: str,
    subject: str,
    language: str,
    output_dir: Path,
) -> dict[str, Any]:
    """Accept a canonical whole book or merge a class 7/8 Maths part pair locally."""
    canonical_name = book_filename(grade, subject, language)
    canonical = root / canonical_name
    part_track = grade in {"7", "8"} and subject == "maths"
    part1 = root / part_book_filename(grade, subject, language, 1)
    part2 = root / part_book_filename(grade, subject, language, 2)
    have1, have2 = part1.is_file(), part2.is_file()

    # For Classes 7-8 Mathematics, the NCERT source is published as two volumes.
    # If both explicitly named source parts exist, prefer those over a same-named
    # canonical convenience PDF: it may be a stale/partial Part-I-only copy that
    # would otherwise fail at the first chapter of Part II (e.g. Geometric Twins).
    # A part pair is an explicit source selection, not a guessed page mapping.
    if not (part_track and have1 and have2):
        if canonical.is_file():
            return {
                "path": canonical,
                "slot_filename": canonical_name,
                "merged_from_parts": False,
                "source_files": [{
                    "filename": canonical.name,
                    "sha256": sha256_file(canonical),
                    "size_bytes": canonical.stat().st_size,
                }],
                "metadata_warning": None,
            }

        if not part_track:
            raise FileNotFoundError(f"Required complete-book PDF is missing: {canonical_name}")
        if not have1 and not have2:
            raise FileNotFoundError(
                f"Missing {canonical_name}; alternatively provide BOTH "
                f"{part1.name} and {part2.name}."
            )
        if have1 != have2:
            present = part1.name if have1 else part2.name
            missing = part2.name if have1 else part1.name
            raise FileNotFoundError(
                f"Only {present} was found. Both official Mathematics parts are required; "
                f"also add {missing}. No application writes have been made."
            )

    # Validate both original parts before producing the local combined reading copy.
    first_meta = inspect_book(part1)
    second_meta = inspect_book(part2)
    target_dir = output_dir / "combined-books"
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / canonical_name
    temporary = target.with_suffix(target.suffix + ".tmp")
    sidecar = target.with_suffix(target.suffix + ".source.json")
    input_fingerprint = {
        "part_1_filename": part1.name,
        "part_1_sha256": first_meta["sha256"],
        "part_2_filename": part2.name,
        "part_2_sha256": second_meta["sha256"],
    }
    reusable = False
    if target.is_file() and sidecar.is_file():
        try:
            reusable = json.loads(sidecar.read_text(encoding="utf-8")) == input_fingerprint
        except (OSError, json.JSONDecodeError):
            reusable = False
    if not reusable:
        try:
            with fitz.open(part1) as first, fitz.open(part2) as second, fitz.open() as combined:
                combined.insert_pdf(first, links=True, annots=True)
                combined.insert_pdf(second, links=True, annots=True)
                source_meta = dict(first.metadata or {})
                source_meta.update({
                    "title": f"NCERT Class {grade} Mathematics ({label_language(language)}) — Parts I and II",
                    "subject": "Combined reading copy assembled from the two supplied NCERT part PDFs",
                    "keywords": "NCERT; Mathematics; Parts I and II; QuantaEdge",
                })
                combined.set_metadata(source_meta)
                combined.save(temporary, garbage=3, deflate=True)
            temporary.replace(target)
            sidecar_tmp = sidecar.with_suffix(sidecar.suffix + ".tmp")
            sidecar_tmp.write_text(json.dumps(input_fingerprint, indent=2), encoding="utf-8")
            sidecar_tmp.replace(sidecar)
        except Exception as exc:
            temporary.unlink(missing_ok=True)
            raise ValueError(
                f"Could not prepare the local combined reading copy from {part1.name} and {part2.name}: {exc}"
            ) from exc

    combined_meta = inspect_book(target)
    same_edition = (
        first_meta["edition_detected"] and second_meta["edition_detected"]
        and first_meta["edition"].casefold() == second_meta["edition"].casefold()
    )
    metadata_warning = None
    if not same_edition:
        combined_meta["edition"] = "NCERT Parts I and II — verify both printed editions before publication"
        combined_meta["edition_detected"] = False
        metadata_warning = (
            f"{part1.name} and {part2.name}: printed edition/reprint could not be confirmed "
            "as identical in both files; verify before publication."
        )
    return {
        "path": target,
        "slot_filename": canonical_name,
        "merged_from_parts": True,
        "source_files": [
            {"filename": part1.name, "sha256": first_meta["sha256"], "size_bytes": first_meta["size_bytes"]},
            {"filename": part2.name, "sha256": second_meta["sha256"], "size_bytes": second_meta["size_bytes"]},
        ],
        "metadata_warning": metadata_warning,
        "combined_meta": combined_meta,
    }


def create_import_plan(
    root: Path,
    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]],
    max_bytes: int,
    books_dir: Path | None = None,
    languages: tuple[str, ...] = LANGUAGES,
    progress: Callable[[str], None] | None = None,
) -> tuple[list[dict[str, Any]], list[str]]:
    """Validate requested source-book languages and prepare splits under the work root."""
    if not languages or any(language not in LANGUAGES for language in languages):
        raise ValueError(f"Unsupported language selection: {languages!r}. Choose from {LANGUAGES}.")
    if len(set(languages)) != len(languages):
        raise ValueError(f"Duplicate language selection: {languages!r}.")
    books_dir = (books_dir or root).expanduser().resolve()
    if not books_dir.is_dir():
        raise FileNotFoundError(f"NCERT source-books directory does not exist: {books_dir}")
    output_dir = root / "private-review" / "ncert-book-splits"
    records: list[dict[str, Any]] = []
    chapter_source_records: list[dict[str, Any]] = []
    warnings: list[str] = []
    missing: list[str] = []
    total_input_slots = len(CLASSES) * len(SUBJECTS) * len(languages)
    slot_index = 0

    for grade in CLASSES:
        for subject in SUBJECTS:
            for language in languages:
                slot_index += 1
                name = book_filename(grade, subject, language)
                if progress:
                    progress(
                        f"[Textbook {slot_index}/{total_input_slots}] Preparing {name}; "
                        "checking source files and printed edition."
                    )
                try:
                    source = prepare_source_book(books_dir, grade, subject, language, output_dir)
                    path = Path(source["path"])
                    book = source.get("combined_meta") or inspect_book(path)
                except FileNotFoundError as exc:
                    missing.append(str(exc))
                    continue
                if not source["merged_from_parts"] and book["size_bytes"] > max_bytes:
                    raise ValueError(
                        f"{name}: {book['size_bytes']:,} bytes exceeds the verified staff-role upload "
                        f"limit of {max_bytes // (1024 * 1024)} MiB."
                    )
                if progress:
                    if source["merged_from_parts"]:
                        parts = " + ".join(item["filename"] for item in source["source_files"])
                        progress(
                            f"Local chapter-mapping copy prepared from {parts}; {book['page_count']} pages, "
                            f"{format_bytes(book['size_bytes'])}. The combined copy will NOT be uploaded; "
                            "the two original part PDFs will be student resources."
                        )
                    else:
                        progress(
                            f"Source opened: {name}; {book['page_count']} pages, "
                            f"{format_bytes(book['size_bytes'])}."
                        )
                chapter_args = (path, grade, subject, language, chapters_by_track[(grade, subject)])
                if progress:
                    chapter_ranges = detect_chapter_ranges(*chapter_args, progress=progress)
                else:
                    # Keep the original callable contract for tests/tools that intentionally
                    # replace the detector but do not request progress reporting.
                    chapter_ranges = detect_chapter_ranges(*chapter_args)
                if progress:
                    progress(
                        f"Validated all {len(chapter_ranges)} chapter openings for {name}; "
                        "page mapping is sequential and unambiguous."
                    )
                source_title = (
                    f"National Council of Educational Research and Training (NCERT) — "
                    f"Class {grade} {label_subject(subject)} textbook"
                )
                book_record = {
                    **book,
                    "path": str(path.resolve()),
                    "filename": source["slot_filename"],
                    "input_source_files": source["source_files"],
                    "merged_from_parts": bool(source["merged_from_parts"]),
                    "source_bytes_preserved": not bool(source["merged_from_parts"]),
                    "class_code": grade,
                    "subject_code": subject,
                    "language": language,
                    "scope": "SUBJECT_BOOK",
                    "chapter_code": None,
                    "chapter_id": None,
                    "book_part": None,
                    "title": student_title(grade, subject, language, "SUBJECT_BOOK"),
                    "source_title": source_title,
                    "source_url": OFFICIAL_NCERT_CATALOG,
                    "chapter_ranges": chapter_ranges,
                    "edition_warning": not bool(book["edition_detected"]),
                    "action": "PENDING",
                }
                # This record points to the actual chapter-mapping source. For two-part NCERT
                # Maths books, that is a local combined copy and is not a student-facing resource.
                chapter_source_records.append(book_record)

                if source.get("metadata_warning"):
                    warnings.append(str(source["metadata_warning"]))

                if source["merged_from_parts"]:
                    for part_number, source_part in enumerate(source["source_files"], start=1):
                        part_filename = str(source_part["filename"])
                        part_path = books_dir / part_filename
                        part_book = inspect_book(part_path)
                        if part_book["size_bytes"] > max_bytes:
                            raise ValueError(
                                f"{part_filename}: {part_book['size_bytes']:,} bytes exceeds the verified "
                                f"staff-role upload limit of {max_bytes // (1024 * 1024)} MiB."
                            )
                        roman_part = ("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")[part_number - 1] if 1 <= part_number <= 9 else str(part_number)
                        part_record = {
                            **part_book,
                            "path": str(part_path.resolve()),
                            "filename": part_filename,
                            "input_source_files": [{
                                "filename": part_filename,
                                "sha256": part_book["sha256"],
                                "size_bytes": part_book["size_bytes"],
                            }],
                            "merged_from_parts": False,
                            "source_bytes_preserved": True,
                            "class_code": grade,
                            "subject_code": subject,
                            "language": language,
                            "scope": "SUBJECT_BOOK",
                            "chapter_code": None,
                            "chapter_id": None,
                            "book_part": part_number,
                            "title": student_title(
                                grade, subject, language, "SUBJECT_BOOK", book_part=part_number
                            ),
                            "source_title": f"{source_title} — Part {roman_part}",
                            "source_url": OFFICIAL_NCERT_CATALOG,
                            "edition_warning": not bool(part_book["edition_detected"]),
                            "action": "PENDING",
                        }
                        records.append(part_record)
                        if progress:
                            progress(
                                f"Part-wise assignment planned: Class {grade} {label_subject(subject)} "
                                f"Part {roman_part} — {part_book['page_count']} pages, "
                                f"{format_bytes(part_book['size_bytes'])}; original PDF unchanged."
                            )
                        if not part_book["edition_detected"]:
                            warnings.append(
                                f"{part_filename}: no clear printed reprint/session was detected in the "
                                "first 15 pages; edition metadata is marked for Admin verification before publication."
                            )
                else:
                    records.append(book_record)
                    if not book["edition_detected"]:
                        warnings.append(
                            f"{name}: no clear printed reprint/session was detected in the first 15 pages; "
                            "edition metadata is marked for Admin verification before publication."
                        )

    if missing:
        raise FileNotFoundError(
            f"{len(missing)} required complete-book PDFs are missing. No API writes occurred.\n - "
            + "\n - ".join(missing)
        )

    # Build all extracts in a temporary staging folder first. No previous local extract is
    # touched unless every requested chapter can be generated and all overwrite conflicts pass.
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    book_plans = chapter_source_records
    chapter_records: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix=".ncert-book-splits-stage-", dir=output_dir.parent) as stage_name:
        stage_root = Path(stage_name)
        staged_outputs: list[tuple[Path, Path, dict[str, Any]]] = []
        total_chapters_to_split = sum(len(item["chapter_ranges"]) for item in book_plans)
        split_index = 0
        if progress:
            progress(
                f"All source books passed chapter-boundary validation. Preparing "
                f"{total_chapters_to_split} chapter PDF extracts in a temporary staging folder."
            )
        for book_record in book_plans:
            grade = str(book_record["class_code"])
            subject = str(book_record["subject_code"])
            language = str(book_record["language"])
            source_path = Path(book_record["path"])
            for chapter in book_record["chapter_ranges"]:
                split_index += 1
                filename = chapter_output_filename(grade, subject, language, chapter["chapter_code"])
                final_target = output_dir / f"class-{grade}" / subject / language / filename
                stage_target = stage_root / f"class-{grade}" / subject / language / filename
                if progress:
                    progress(
                        f"[Local extract {split_index}/{total_chapters_to_split}] "
                        f"Class {grade} {label_subject(subject)}, chapter "
                        f"{chapter['chapter_order']}: "
                        f"{chapter.get('chapter_name') or chapter['chapter_code']} "
                        f"(source pages {chapter['start_page']}–{chapter['end_page']})."
                    )
                split = split_chapter_pdf(
                    source_path, stage_target, int(chapter["start_page"]), int(chapter["end_page"])
                )
                if split["size_bytes"] > max_bytes:
                    raise ValueError(
                        f"{split['filename']}: generated chapter extract exceeds the staff-role "
                        f"upload limit of {max_bytes // (1024 * 1024)} MiB."
                    )
                record = {
                    **split,
                    "path": str(final_target.resolve()),
                    "class_code": grade,
                    "subject_code": subject,
                    "language": language,
                    "scope": "CHAPTER_PDF",
                    "chapter_code": chapter["chapter_code"],
                    "chapter_id": chapter["chapter_id"],
                    "chapter_name": chapter["chapter_name"],
                    "title": student_title(grade, subject, language, "CHAPTER_PDF", chapter),
                    "source_title": book_record["source_title"],
                    "source_url": book_record["source_url"],
                    "edition": book_record["edition"],
                    "edition_detected": book_record["edition_detected"],
                    "parent_book_filename": book_record["filename"],
                    "parent_book_sha256": book_record["sha256"],
                    "parent_book_source_files": book_record["input_source_files"],
                    "merged_from_parts": book_record["merged_from_parts"],
                    "chapter_order": chapter["chapter_order"],
                    "source_page_start": chapter["start_page"],
                    "source_page_end": chapter["end_page"],
                    "mapping_note": chapter["mapping_note"],
                    "action": "PENDING",
                }
                staged_outputs.append((stage_target, final_target, record))

        conflicts: list[str] = []
        for stage_path, final_path, record in staged_outputs:
            if final_path.exists():
                if not final_path.is_file() or sha256_file(final_path) != record["sha256"]:
                    conflicts.append(str(final_path))
        if conflicts:
            raise FileExistsError(
                "Existing generated chapter file(s) differ from the newly validated extracts. "
                "Nothing was overwritten. Back up/move these local files before retrying:\n - "
                + "\n - ".join(conflicts[:20])
            )

        # All files are generated and all conflicts are checked before the first promotion.
        for stage_path, final_path, record in staged_outputs:
            if final_path.is_file():
                stage_path.unlink(missing_ok=True)  # identical prior output: retain it unchanged
            else:
                final_path.parent.mkdir(parents=True, exist_ok=True)
                stage_path.replace(final_path)
            record["path"] = str(final_path.resolve())
            chapter_records.append(record)

    records.extend(chapter_records)

    keys = [
        (
            r["class_code"], r["subject_code"], r["language"], r["scope"],
            r.get("chapter_id"), r.get("book_part"),
        )
        for r in records
    ]
    # One asset can be assigned to one chapter and one complete book slot; detect duplicate
    # chapter slot mappings before allowing uploads.
    slots = [k for k in keys]
    if len(slots) != len(set(slots)):
        duplicate = [k for k, count in Counter(slots).items() if count > 1]
        raise ValueError(f"Duplicate assignment slots in import plan: {duplicate[:10]}. No API writes occurred.")

    return records, warnings


def slot_key(row: dict[str, Any]) -> tuple[str, str, str, str, int | None, int | None]:
    return (
        str(row["class_code"]), str(row["subject_code"]),
        "en" if row["language"] == "english" else "hi",
        str(row["scope"]), int(row["chapter_id"]) if row.get("chapter_id") is not None else None,
        int(row["book_part"]) if row.get("book_part") is not None else None,
    )

def report_record_identity(row: dict[str, Any]) -> tuple[str, str, str, str, str, str, str, str]:
    return (
        str(row.get("class_code") or ""),
        str(row.get("subject_code") or ""),
        str(row.get("language") or ""),
        str(row.get("scope") or ""),
        str(row.get("chapter_code") or ""),
        str(row.get("chapter_id") if row.get("chapter_id") is not None else ""),
        str(row.get("book_part") if row.get("book_part") is not None else ""),
        str(row.get("sha256") or "").lower(),
    )

def sync_report_record(report: dict[str, Any], row: dict[str, Any]) -> None:
    """Persist the latest state for one planned PDF in the audit report."""
    files = report.setdefault("files", [])
    identity = report_record_identity(row)
    for index, existing in enumerate(files):
        if isinstance(existing, dict) and report_record_identity(existing) == identity:
            files[index] = make_report_record(row)
            return
    files.append(make_report_record(row))


def write_report(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def write_failure_report(args: argparse.Namespace, error: Exception) -> Path:
    """Write a supplemental failure report without overwriting an apply checkpoint."""
    root = Path(args.root).expanduser().resolve()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    if args.report:
        preferred = Path(args.report).expanduser()
        if not preferred.is_absolute():
            preferred = root / preferred
    else:
        preferred = root / "reports" / "ncert-pdf-imports" / f"ncert-book-chapter-import-failure-{stamp}.json"

    report_path = preferred
    if report_path.exists():
        suffix = report_path.suffix or ".json"
        report_path = report_path.with_name(f"{report_path.stem}.failure-{stamp}{suffix}")

    books_dir = Path(args.books_dir).expanduser() if args.books_dir else (root / "books")
    if not books_dir.is_absolute():
        books_dir = root / books_dir
    write_report(report_path, {
        "schema": "quantaedge.official-ncert-book-chapter-import-report.v1",
        "started_at": datetime.now(timezone.utc).isoformat(),
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "result": "STOPPED_SAFELY",
        "mode": "apply" if args.apply else "dry_run",
        "root": str(root),
        "input_books_directory": str(books_dir.resolve()),
        "languages_requested": list(args.languages),
        "api_url": str(args.api_url).rstrip("/"),
        "application_writes_may_have_started": bool(args.apply),
        "publish_actions_taken": 0,
        "credentials_recorded": False,
        "errors": [{"type": type(error).__name__, "message": str(error)}],
        "files": [],
        "notes": [
            "This failure report never overwrites an existing progress/checkpoint report.",
            "Original source PDFs are never edited by the importer.",
            "When --apply was supplied, inspect the checkpoint report before retrying; this supplemental report cannot prove whether a draft write began.",
            "The script never publishes or archives learning-document assignments.",
        ],
    })
    return report_path


def upload_or_reuse_asset(
    base: str,
    cookie: str,
    record: dict[str, Any],
    approved_assets: dict[str, dict[str, Any]],
    assets_created: dict[str, dict[str, Any]],
    report: dict[str, Any],
    report_path: Path,
    progress: Callable[[str], None] | None = None,
) -> dict[str, Any]:
    digest = str(record["sha256"]).lower()
    existing = approved_assets.get(digest) or assets_created.get(digest)
    if existing:
        report["reused_assets"] += 1
        record["asset_id"] = int(existing.get("pdf_asset_id", existing.get("id")))
        if progress:
            progress(
                f"Reusing checksum-matched PDF library asset {record['asset_id']} for "
                f"{record['title']} (no upload needed)."
            )
        sync_report_record(report, record)
        write_report(report_path, report)
        return existing

    reference_parts = [
        "asset_origin=OFFICIAL_NCERT_BOOK_IMPORT",
        "class=" + record["class_code"],
        "subject=" + record["subject_code"],
        "language=" + record["language"],
        "scope=" + record["scope"],
        "chapter_code=" + str(record.get("chapter_code") or "WHOLE_BOOK"),
        "edition=" + str(record["edition"]),
        "sha256=" + digest,
    ]
    if record.get("parent_book_sha256"):
        reference_parts.append("parent_book_sha256=" + str(record["parent_book_sha256"]))
    if record.get("source_page_start") is not None:
        reference_parts.append(
            f"source_pages={record['source_page_start']}-{record['source_page_end']}"
        )
    input_sources = record.get("input_source_files") or record.get("parent_book_source_files") or []
    for index, source in enumerate(input_sources[:2], 1):
        source_hash = str(source.get("sha256") or "")
        if source_hash:
            reference_parts.append(f"source_part_{index}_sha256={source_hash}")
    if record.get("merged_from_parts"):
        reference_parts.append("combined_reading_copy=true")
    if progress:
        progress(
            f"Uploading {record['filename']} ({format_bytes(int(record['size_bytes']))}) "
            "to the Admin PDF library."
        )
    data = Path(record["path"]).read_bytes()
    asset = api_call(
        base, cookie, "/api/v1/admin/learning-pdfs", method="POST",
        multipart=(
            record["source_title"],
            ";".join(reference_parts)[:500],
            record["filename"],
            data,
        ),
        timeout=300,
    )
    if not isinstance(asset, dict):
        raise RuntimeError(f"{record['filename']}: PDF upload returned an unexpected response.")
    asset_id = asset.get("pdf_asset_id", asset.get("id"))
    if not asset_id:
        raise RuntimeError(f"{record['filename']}: PDF upload returned no asset ID.")
    if asset.get("duplicate") is True:
        # The upload API returns duplicate=true even if the existing asset is not approved.
        # Only a checksum-matched, approved library asset can be attached to a student draft.
        query = urlencode({"query": digest})
        matching = api_call(base, cookie, "/api/v1/admin/learning-pdfs?" + query)
        approved = [
            row for row in matching if isinstance(row, dict)
            and str(row.get("sha256") or "").lower() == digest
        ] if isinstance(matching, list) else []
        if not approved:
            raise RuntimeError(
                f"{record['filename']}: identical bytes already exist as a non-approved asset. "
                "Review/approve the existing asset in Admin before retrying; no assignment was created for it."
            )
        asset = approved[0]
        asset_id = asset.get("pdf_asset_id", asset.get("id"))
        report["reused_assets"] += 1
    else:
        report["created_assets"] += 1
    asset["pdf_asset_id"] = int(asset_id)
    assets_created[digest] = asset
    record["asset_id"] = int(asset_id)
    if progress:
        progress(f"PDF library asset ready: id={int(asset_id)} for {record['filename']}.")
    sync_report_record(report, record)
    write_report(report_path, report)
    return asset


def run(args: argparse.Namespace) -> int:
    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        raise ValueError(f"Work/repository directory does not exist: {root}")
    if args.books_dir:
        books_dir = Path(args.books_dir).expanduser()
        if not books_dir.is_absolute():
            books_dir = root / books_dir
        books_dir = books_dir.resolve()
    else:
        books_dir = (root / "books").resolve()
    if not books_dir.is_dir():
        raise FileNotFoundError(
            f"NCERT books folder not found: {books_dir}. Create it and put the named whole-book PDFs inside."
        )
    report_progress(
        f"Starting NCERT import in {'APPLY' if args.apply else 'DRY-RUN'} mode; "
        "progress will be printed as each phase completes."
    )
    report_progress("Phase 1/6: checking required source filenames and selected languages.")
    validate_input_book_presence(books_dir, tuple(args.languages))
    report_progress(f"Source folder verified: {books_dir}")
    report_progress(f"Languages selected: {', '.join(args.languages)}")
    base = args.api_url.rstrip("/")
    report_progress("Phase 2/6: validating the API URL and current staff session.")
    api_url_valid(base)
    cookie = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    me = verify_session(base, cookie)
    role = str(me.get("role") or "")
    max_bytes = ADMIN_MAX_BYTES if role.casefold() == "admin" else STANDARD_MAX_BYTES
    report_progress(f"Staff session verified: role={role or 'unknown'}; CONTENT_EDIT confirmed.")
    report_progress(f"Maximum size per uploaded PDF: {max_bytes // (1024 * 1024)} MiB.")
    report_progress("Phase 3/6: loading the active Class 6–8 Maths/Science curriculum.")
    chapters_by_track = read_chapters(base, cookie, progress=report_progress)
    report_progress("Phase 4/6: inspecting PDFs, validating chapter starts, and preparing local chapter extracts.")
    records, warnings = create_import_plan(
        root, chapters_by_track, max_bytes,
        books_dir=books_dir, languages=tuple(args.languages),
        progress=report_progress,
    )
    source_slot_count = len({
        (row["class_code"], row["subject_code"], row["language"])
        for row in records
    })
    report_progress(
        f"Source analysis complete: {source_slot_count} source-book slots validated, "
        f"{sum(1 for row in records if row['scope'] == 'SUBJECT_BOOK')} separate subject-book PDF resources "
        f"and {sum(1 for row in records if row['scope'] == 'CHAPTER_PDF')} chapter extracts planned."
    )
    tracks = {(row["class_code"], row["subject_code"]) for row in records}
    report_progress("Phase 5/6: checking PDF-library checksums and existing assignments.")
    approved_assets = query_library_assets(base, cookie)
    report_progress(f"PDF library read complete: {len(approved_assets)} checksum-indexed assets found.")
    hashes_by_slot, _assignment_by_slot_hash = query_assignments(
        base, cookie, tracks, progress=report_progress
    )

    report_progress(f"Checking duplicate assignments for {len(records)} planned PDF resources.")
    for row_index, row in enumerate(records, 1):
        key = slot_key(row)
        hashes = hashes_by_slot.get(key, set())
        if str(row["sha256"]).lower() in hashes:
            row["action"] = "SKIP_IDENTICAL_ASSIGNMENT"
        else:
            row["action"] = "WOULD_CREATE_DRAFT"
        if row_index % 10 == 0 or row_index == len(records):
            report_progress(f"Duplicate check complete: {row_index}/{len(records)} planned resources.")

    # The entire local split and chapter mapping plan is available before any API write.
    report_stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_path = Path(args.report).expanduser() if args.report else (
        root / "reports" / "ncert-pdf-imports" / f"ncert-book-chapter-import-{report_stamp}.json"
    )
    if not report_path.is_absolute():
        report_path = root / report_path
    report: dict[str, Any] = {
        "schema": "quantaedge.official-ncert-book-chapter-import-report.v1",
        "started_at": datetime.now(timezone.utc).isoformat(),
        "result": "PREFLIGHT_OK" if not args.apply else "APPLY_IN_PROGRESS",
        "mode": "apply" if args.apply else "dry_run",
        "root": str(root),
        "input_books_directory": str(books_dir),
        "api_url": base,
        "authenticated_role": role,
        "permissions_required": ["CONTENT_EDIT"],
        "publish_permission_required_later": "CONTENT_PUBLISH",
        "input_books_expected": len(CLASSES) * len(SUBJECTS) * len(args.languages),
        "languages_requested": list(args.languages),
        "input_books_validated": source_slot_count,
        "chapter_splits_created_locally": sum(1 for row in records if row["scope"] == "CHAPTER_PDF"),
        "planned_draft_assignments": len(records),
        "created_assets": 0,
        "created_draft_assignments": 0,
        "reused_assets": 0,
        "skipped_identical_assignments": (
            sum(1 for row in records if row["action"] == "SKIP_IDENTICAL_ASSIGNMENT")
            if not args.apply else 0
        ),
        "publish_actions_taken": 0,
        "warnings": warnings,
        "files": [make_report_record(row) for row in records],
        "errors": [],
        "notes": [
            "Single-volume source books are uploaded byte-for-byte unchanged.",
            "Class 7/8 Mathematics Part I and Part II are uploaded as separate SUBJECT_BOOK resources. The local combined copy is used only for chapter mapping and is never uploaded or assigned.",
            "Chapter PDF files are exact page-range extracts from the source pages; original source files remain untouched.",
            "All new learning-document assignments remain DRAFT.",
            "Existing published assignments remain live throughout import and review.",
            "Publishing a replacement later requires CONTENT_PUBLISH and archives the old assignment without deleting its PDF asset.",
        ],
        "credentials_recorded": False,
    }
    if not args.apply:
        report_progress("Phase 6/6: writing the dry-run audit report; no PDF uploads or assignment writes.")
        write_report(report_path, report)
        print(f"Source-book slots validated: {report['input_books_validated']}/{report['input_books_expected']}")
        print(f"Separate student subject-book resources planned: {sum(1 for row in records if row['scope'] == 'SUBJECT_BOOK')}")
        print(f"Chapter PDFs extracted locally: {report['chapter_splits_created_locally']}")
        print(f"Total complete-book + chapter assignments planned: {report['planned_draft_assignments']}")
        print(f"Existing identical assignments to skip: {report['skipped_identical_assignments']}")
        print(f"Edition warnings: {len(warnings)}")
        print("DRY RUN: no PDFs were uploaded and no assignment statuses were changed.")
        print(f"Report: {report_path}")
        print("Preview generated chapter files under private-review/ncert-book-splits before running --apply.")
        return 0

    # Apply only after complete preflight. Reports are persisted before each API write for resume.
    report_progress("Phase 6/6: applying the validated plan. New assignments will remain DRAFT.")
    write_report(report_path, report)
    assets_created: dict[str, dict[str, Any]] = {}
    for row_index, row in enumerate(records, 1):
        report_progress(f"[Apply {row_index}/{len(records)}] {row['title']}: {row['action']}.")
        if row["action"] == "SKIP_IDENTICAL_ASSIGNMENT":
            row["status"] = "SKIPPED"
            report["skipped_identical_assignments"] += 1
            report_progress(f"Skipped identical existing assignment: {row['title']}.")
            sync_report_record(report, row)
            write_report(report_path, report)
            continue

        asset = upload_or_reuse_asset(
            base, cookie, row, approved_assets, assets_created, report, report_path,
            progress=report_progress,
        )
        asset_id = int(asset.get("pdf_asset_id", asset.get("id")))
        row["action"] = "ASSIGNMENT_WRITE_PENDING"
        report_progress(f"Creating DRAFT assignment for {row['title']} using PDF asset {asset_id}.")
        sync_report_record(report, row)
        write_report(report_path, report)
        source_page_start = int(row.get("source_page_start", 1))
        source_page_end = int(row.get("source_page_end", row["page_count"]))
        body = {
            "pdfAssetId": asset_id,
            "scope": row["scope"],
            "classCode": row["class_code"],
            "subjectCode": row["subject_code"],
            "language": "en" if row["language"] == "english" else "hi",
            "chapterId": row.get("chapter_id"),
            "bookPart": row.get("book_part"),
            "title": row["title"],
            "sourceTitle": row["source_title"],
            "sourceUrl": row["source_url"],
            "edition": row["edition"],
            "pageStart": 1,
            "pageEnd": int(row["page_count"]) if row["scope"] == "CHAPTER_PDF" else int(row["page_count"]),
        }
        # The chapter asset already contains its exact chapter pages, so its assignment uses 1..n.
        doc = api_call(
            base, cookie, "/api/v1/admin/learning-documents",
            method="POST", body=body, timeout=120,
        )
        if not isinstance(doc, dict) or str(doc.get("status", "")).upper() != "DRAFT":
            raise RuntimeError(
                f"{row['filename']}: API did not return a DRAFT assignment. "
                "Stop and inspect the checkpoint report before retrying."
            )
        document_id = doc.get("document_id", doc.get("id"))
        if not document_id:
            raise RuntimeError(f"{row['filename']}: DRAFT assignment response has no document ID.")
        row["document_id"] = int(document_id)
        row["asset_id"] = asset_id
        row["status"] = "DRAFT"
        row["action"] = "CREATED_DRAFT"
        report["created_draft_assignments"] += 1
        report_progress(
            f"DRAFT assignment created: document_id={int(document_id)}, "
            f"asset_id={asset_id}, title={row['title']}."
        )
        sync_report_record(report, row)
        write_report(report_path, report)

    report["result"] = "COMPLETED"
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    report_progress("All planned resources processed. Finalizing the audit report.")
    write_report(report_path, report)
    print(f"Import result: {report['result']}")
    print(f"Created new PDF assets: {report['created_assets']}")
    print(f"Reused approved PDF assets: {report['reused_assets']}")
    print(f"Created DRAFT assignments: {report['created_draft_assignments']}")
    print(f"Skipped identical assignments: {report['skipped_identical_assignments']}")
    print("Published or archived assignments: 0")
    print(f"Report: {report_path}")
    print("Next: Admin → Textbook library. Review chapter boundary/edition, then publish chosen resources with CONTENT_PUBLISH.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(ROOT), help="Repository/work folder for generated splits and reports (default: repo root).")
    parser.add_argument("--books-dir", default="", help="Folder containing source books (default: <root>/books). Relative paths are resolved from --root.")
    parser.add_argument(
        "--languages", nargs="+", choices=LANGUAGES, required=True,
        help="Required explicit source-language selection, e.g. --languages english or --languages english hindi.",
    )
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--apply", action="store_true", help="Upload all validated books/splits and create DRAFT assignments; default only creates a report/splits.")
    parser.add_argument("--report", default="", help="Optional audit report path.")
    args = parser.parse_args()
    try:
        return run(args)
    except Exception as exc:
        print(f"STOPPED SAFELY: {exc}", file=sys.stderr)
        print("No publication/archive action was started; source PDFs were not modified.", file=sys.stderr)
        try:
            failure_path = write_failure_report(args, exc)
            print(f"Failure report: {failure_path}", file=sys.stderr)
        except Exception as report_exc:
            print(f"Could not write supplemental failure report: {report_exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())