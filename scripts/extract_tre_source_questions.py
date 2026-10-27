#!/usr/bin/env python3
"""Build an auditable bilingual question inventory from the user-provided TRE PDF ZIP.

Uses PDF text blocks and applies Hindi OCR only to source prompt blocks explicitly rendered with
SHREE-DEV-0708/0709, where generic legacy-font conversion is not trusted. Original legacy text and
per-question page provenance are retained, and reconstructed prompts remain flagged for human review.
This script does not solve answers, create database records, or publish assessments.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

try:
    import pymupdf as fitz
except ImportError:
    try:
        import fitz  # compatibility with older PyMuPDF
    except ImportError:
        fitz = None  # pure text-classifier tests can run without the optional PDF dependency

try:
    from lipi.preprocessor import HindiPreprocessor
except ImportError:
    HindiPreprocessor = None

MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_PDF_BYTES = 80 * 1024 * 1024
MAX_PDFS = 10
PAGE_SPLIT_X = 297.6
QUESTION_START = re.compile(
    r"^\s*(?:(?P<prefix>[EHUB])\s*[-–]\s*(?P<prefnum>\d{1,3})|(?P<number>\d{1,3}))\s*[.)]\s*(?P<rest>.*)$",
    re.IGNORECASE,
)
OPTION_START = re.compile(r"(?m)^\s*\(([A-E])\)\s*")
KNOWN_STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "be", "been", "by", "can", "do", "does",
    "for", "from", "if", "in", "is", "it", "of", "on", "or", "the", "to", "was",
    "were", "what", "when", "where", "which", "who", "why", "with", "will", "not",
    "than", "then", "that", "this", "these", "those", "their", "there", "they",
    "you", "your", "we", "he", "she", "his", "her", "its", "into", "also",
}

PAPERS = {
    "TRE 1.0": {
        "match": ("tre1_0", "tre1.0", "tre-1", "computer_11_12"),
        "expected": {"GENERAL_STUDIES": (1, 40), "COMPUTER_SCIENCE": (41, 120)},
        "total_questions": 120,
        "duration_minutes": 120,
        "negative_marking_fraction": 0.25,
        "exam_date": "2023-08-24",
        "pdf_name": "BPSC_TRE1_0_COMPUTER_11_12 by csestudy247.pdf",
        "source_key": "TRE 1.0",
    },
    "TRE 2.0": {
        "match": ("tre2.0", "tre-2", "tre2"),
        "expected": {"GENERAL_STUDIES": (31, 70), "COMPUTER_SCIENCE": (71, 150)},
        "total_questions": 150,
        "duration_minutes": 150,
        "negative_marking_fraction": 0.0,
        "exam_date": "2023-12-15",
        "pdf_name": "BPSC TRE2.0 question paper by csestud247.pdf",
        "source_key": "TRE 2.0",
    },
    "TRE 3.0": {
        "match": ("tre3.0", "tre-3", "tre3"),
        "expected": {"GENERAL_STUDIES": (31, 70), "COMPUTER_SCIENCE": (71, 150)},
        # Preserve the 150 items: 40 GS + 80 CS + 30 qualifying-language questions.
        "total_questions": 150,
        "duration_minutes": 150,
        "negative_marking_fraction": 0.0,
        "exam_date": "2024-07-22",
        "pdf_name": "BPSC TRE3.0 question paper by csestudy247.pdf",
        "source_key": "TRE 3.0",
    },
}
STANDARD_EN_OPTIONS = {
    "D": "More than one of the above",
    "E": "None of the above",
}
STANDARD_HI_OPTIONS = {
    "D": "उपर्युक्त में से एक से अधिक",
    "E": "उपर्युक्त में से कोई नहीं",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_space(value: str) -> str:
    """Normalize ordinary labels only; never use this for source question/code text."""
    return re.sub(r"\s+", " ", value).strip()


def preserve_layout(value: str) -> str:
    """Keep line breaks and leading spaces that may encode code or formula notation."""
    value=value.replace("\r\n","\n").replace("\r","\n").replace("\u00a0"," ")
    lines=[line.rstrip() for line in value.split("\n")]
    while lines and not lines[0].strip(): lines.pop(0)
    while lines and not lines[-1].strip(): lines.pop()
    return re.sub(r"\n{3,}","\n\n","\n".join(lines))


CODE_LINE_RE = re.compile(
    r"""(?ix)^\s*(?:\#\s*include\b|\#\s*define\b|import\s+[\w.]+|from\s+[\w.]+\s+import\b|
    using\s+namespace\b|public\s+(?:static|class|void)\b|private\s+(?:static|class|void)\b|
    protected\s+|class\s+\w+|def\s+\w+\s*\(|function\s+\w*\s*\(|SELECT\b.*\bFROM\b|
    INSERT\s+INTO\b|CREATE\s+TABLE\b|UPDATE\s+\w+\s+SET\b|DELETE\s+FROM\b|
    printf\s*\(|scanf\s*\(|cout\s*<<|cin\s*>>|System\.out\.|console\.log\s*\(|print\s*\(|
    return\b|break\s*;|continue\s*;|for\s*\(|while\s*\(|if\s*\(|switch\s*\(|case\s+.+:|
    int\s+[*\s]*[A-Za-z_]\w*|float\s+[*\s]*[A-Za-z_]\w*|double\s+[*\s]*[A-Za-z_]\w*|
    char\s+[*\s]*[A-Za-z_]\w*|void\s+[*\s]*[A-Za-z_]\w*|bool\s+[*\s]*[A-Za-z_]\w*|
    var\s+\w+\s*=|let\s+\w+\s*=|const\s+\w+\s*=|[\{\}]\s*;?|\w+\s*(?:\+\+|--)\s*;?
    )"""
)


def looks_like_code_line(value: str) -> bool:
    text=value.strip()
    if not text:
        return False
    if CODE_LINE_RE.search(text):
        return True
    operators=sum(text.count(token) for token in ("==","!=","<=",">=","++","--","->","&&","||","<<",">>","{","}",";"))
    if operators>=2 and re.search(r"[A-Za-z_]",text):
        return True
    if re.match(r"^[A-Za-z_]\w*(?:\[[^\]]+\])?\s*=\s*[^=].*[;]?$",text) and re.search(r"[+\-*/%<>]",text):
        return True
    if re.match(r"^\s{2,}\S",value) and re.search(r"(?:[{};]|\+\+|--|==|<=|>=|<<|>>)",text):
        return True
    return False


def looks_like_math_line(value: str) -> bool:
    return bool(
        re.search(r"(?:\d\s*(?:[+\-*/=<>^√×÷]|mod\b)|[A-Za-z]\s*=\s*[-+\d(]|\\(?:frac|sqrt|sum|int)\b)",value)
        and re.search(r"\d|[=√×÷^]",value)
        and not looks_like_code_line(value)
    )


def classify_content(value: str) -> str:
    lines=[line for line in value.splitlines() if line.strip()]
    code_indices=[i for i,line in enumerate(value.splitlines()) if looks_like_code_line(line)]
    math_indices=[i for i,line in enumerate(value.splitlines()) if looks_like_math_line(line)]
    if code_indices and math_indices: return "MIXED"
    if code_indices: return "CODE" if len(code_indices)==len(lines) else "MIXED"
    if math_indices: return "MATHEMATICAL" if len(math_indices)==len(lines) else "MIXED"
    return "PROSE"


def detected_code_line_indices(value: str) -> list[int]:
    return [i for i,line in enumerate(value.splitlines()) if looks_like_code_line(line)]


def detected_math_line_indices(value: str) -> list[int]:
    return [i for i,line in enumerate(value.splitlines()) if looks_like_math_line(line)]


def paper_for_name(name: str) -> str | None:
    normal = name.lower().replace(" ", "_")
    if "tre1_0" in normal or "tre1.0" in normal or "computer_11_12" in normal:
        return "TRE 1.0"
    if "tre2.0" in normal or "tre2" in normal:
        return "TRE 2.0"
    if "tre3.0" in normal or "tre3" in normal:
        return "TRE 3.0"
    return None


def section_for_number(cycle: str, number: int) -> str | None:
    if cycle == "TRE 1.0":
        low, high = 1, 120
        if not low <= number <= high:
            return None
        return "GENERAL_STUDIES" if number <= 40 else "COMPUTER_SCIENCE"
    if 1 <= number <= 30:
        return "LANGUAGE_QUALIFYING"
    return "GENERAL_STUDIES" if number <= 70 else "COMPUTER_SCIENCE"


def protect_shared_latin_terms(hindi_text: str, english_reference: str) -> tuple[str, list[tuple[str, str]]]:
    """Protect shared acronyms/code/technical terms from context-free glyph substitution."""
    if not hindi_text:
        return hindi_text, []
    candidates = re.findall(r"[A-Za-z][A-Za-z0-9_+./#:-]*", english_reference or "")
    candidates = sorted(
        {t for t in candidates if len(t) >= 2 and
         (any(ch.isupper() for ch in t) or any(ch.isdigit() for ch in t)
          or any(ch in t for ch in "+./#:-") or t.lower() not in KNOWN_STOPWORDS)},
        key=len, reverse=True,
    )
    protected: list[tuple[str, str]] = []
    result = hindi_text
    next_codepoint = 0xE000
    for token in candidates:
        if next_codepoint > 0xF8FF:
            break
        if not re.search(re.escape(token), result, flags=re.IGNORECASE):
            continue
        marker = chr(next_codepoint)
        next_codepoint += 1
        result = re.sub(re.escape(token), lambda _: marker, result, flags=re.IGNORECASE)
        protected.append((marker, token))
    return result, protected


def convert_hindi_text(raw: str, english_reference: str = "") -> tuple[str, str]:
    """Convert legacy Hindi prose while preserving code lines, shared tokens and operators."""
    if not raw:
        return "", "EMPTY"
    if HindiPreprocessor is None:
        return raw, "CONVERTER_UNAVAILABLE"
    try:
        detected_legacy, detected_font = HindiPreprocessor.detect_encoding(raw)
    except Exception:
        return raw, "LEGACY_FONT_UNCERTAIN_RAW_PRESERVED"
    # Do not guess KrutiDev when the detector cannot establish a supported legacy
    # encoding. That can corrupt otherwise recoverable source text without warning.
    if not detected_legacy or detected_font not in {"krutidev", "chanakya"}:
        return raw, "LEGACY_FONT_UNCERTAIN_RAW_PRESERVED"
    font_type = detected_font
    converted_lines: list[str] = []
    preserved_code = False
    try:
        for line in raw.splitlines():
            if looks_like_code_line(line):
                converted_lines.append(line)
                preserved_code = True
                continue
            protected, markers = protect_shared_latin_terms(line, english_reference)
            # Operators are language-neutral. Hide them from legacy-font substitution so
            # expressions such as +=, <=, ::, #include and formula symbols cannot be mangled.
            next_marker = 0xE800
            operator_markers: list[tuple[str, str]] = []
            operators = sorted(
                set(re.findall(r"==|!=|<=|>=|<<|>>|\+\+|--|\+=|-=|\*=|/=|%=|&&|\|\||->|[{};=<>+*/%#]", protected)),
                key=len, reverse=True,
            )
            for operator in operators:
                if next_marker > 0xF8FF:
                    break
                marker = chr(next_marker)
                next_marker += 1
                if operator in protected:
                    protected = protected.replace(operator, marker)
                    operator_markers.append((marker, operator))
            converted = HindiPreprocessor.convert(protected, font_type=font_type)
            converted = HindiPreprocessor.post_process(
                converted, repair_doubled_consonant_imatra=(font_type == "krutidev")
            )
            for marker, token in markers + operator_markers:
                converted = converted.replace(marker, token)
            converted_lines.append(converted)
        status = "CONVERTED_" + font_type.upper()
        if not detected_legacy:
            status += "_FORCED_LEGACY"
        if preserved_code:
            status += "_CODE_PRESERVED"
        return "\n".join(converted_lines), status
    except Exception as exc:
        return raw, "CONVERSION_ERROR:" + type(exc).__name__


def _question_prefix(text: str) -> tuple[str | None, int | None, str] | None:
    first_line = next((line.strip() for line in text.splitlines() if line.strip()), "")
    match = QUESTION_START.match(first_line)
    if not match:
        return None
    prefix = (match.group("prefix") or "").upper()
    number_text = match.group("prefnum") or match.group("number")
    if not number_text:
        return None
    try:
        number = int(number_text)
    except ValueError:
        return None
    rest = match.group("rest") or ""
    if prefix:
        identity = f"{prefix}-{number}"
    else:
        identity = str(number)
    return identity, number, rest


def _parse_question_range(text: str) -> tuple[str | None, int, int] | None:
    """Read a question range from a passage/section direction, including legacy-Hindi headings."""
    prefixed = re.search(
        r"(?is)([EHUB])\s*[-–]\s*(\d{1,3})\s*(?:to|\bgo\b|से|तक|[-–])[\s\S]{0,40}?([EHUB])\s*[-–]\s*(\d{1,3})",
        text,
    )
    if prefixed and prefixed.group(1).upper() == prefixed.group(3).upper():
        return prefixed.group(1).upper(), int(prefixed.group(2)), int(prefixed.group(4))
    numeric = re.search(
        r"(?is)(?:Q\.?\s*Nos?\.?|questions?\s*(?:nos?\.?)?)\s*(\d{1,3})\s*(?:to|through|[-–])\s*(\d{1,3})",
        text,
    )
    if numeric:
        return None, int(numeric.group(1)), int(numeric.group(2))
    return None


def _is_passage_instruction(text: str) -> bool:
    lower = text.lower()
    return (
        "passage" in lower
        or "answer the questions that follow" in lower
        or "an{r>v" in lower
        or "júm§e" in lower
    )


def _question_is_allowed(cycle: str, identity: str, number: int, prefix: str) -> bool:
    if prefix in {"E", "H", "U", "B"}:
        return number <= 30
    # TRE 3.0 marks language-track questions with E-/H- prefixes. Unprefixed
    # 1–30 labels inside those passages can be answer fragments, not new questions.
    if cycle == "TRE 3.0":
        return number >= 31 and section_for_number(cycle, number) is not None
    return section_for_number(cycle, number) is not None


def _split_block_questions(text: str, cycle: str) -> tuple[str, list[tuple[str, int, str, str]]]:
    """Split a PDF text block at every question label, even when a passage/directive precedes Q1."""
    offsets: list[tuple[int, str, int, str]] = []
    cursor = 0
    for line in text.splitlines(keepends=True):
        parsed = _question_prefix(line)
        if parsed:
            identity, number, _ = parsed
            prefix = identity.split("-", 1)[0] if "-" in identity else ""
            if _question_is_allowed(cycle, identity, number, prefix):
                offsets.append((cursor, identity, number, prefix))
        cursor += len(line)
    if not offsets:
        return text, []
    preamble = text[:offsets[0][0]]
    questions: list[tuple[str, int, str, str]] = []
    for index, (start, identity, number, prefix) in enumerate(offsets):
        end = offsets[index + 1][0] if index + 1 < len(offsets) else len(text)
        questions.append((identity, number, prefix, text[start:end]))
    return preamble, questions


SHREE_DEV_FONT_NAMES = {
    "SHREE-DEV-0708", "SHREE-DEV-0709", "SHREE-DEV7-0708", "SHREE-DEV7-0709",
}
_TESSERACT_HINDI_AVAILABLE: bool | None = None
LEGACY_HINDI_GLYPH_MARKERS = re.compile(r"[{}$~‹›¶³àñ’‘§‡Œœ„±¤ü°¢¥]")
QUESTION_LABEL_ONLY = re.compile(r"^\s*(?:[EHUB]\s*[-–]\s*)?\d{1,3}\s*[.)]?\s*$", re.IGNORECASE)


def uses_legacy_hindi_glyphs(text: str, fonts: set[str] | None = None) -> bool:
    """Detect known Shree font names and obvious legacy glyph streams in mislabeled CIDFonts."""
    normalized_fonts = {str(font or "").strip().upper() for font in (fonts or set())}
    if any(
        font in SHREE_DEV_FONT_NAMES
        or font.startswith(("SHREE-DEV-0708", "SHREE-DEV-0709", "SHREE-DEV7-0708", "SHREE-DEV7-0709"))
        for font in normalized_fonts
    ):
        return True
    value = str(text or "")
    if not value.strip() or re.search(r"[\u0900-\u097F]", value):
        return False
    if QUESTION_LABEL_ONLY.fullmatch(value.strip()):
        return False
    letters = len(re.findall(r"[A-Za-z]", value))
    markers = len(LEGACY_HINDI_GLYPH_MARKERS.findall(value))
    return letters >= 5 and markers >= 2


def normalize_ocr_prompt(value: str) -> str:
    """Normalize an OCR-reconstructed Hindi question stem, removing the printed question label."""
    lines = [line.strip() for line in str(value or "").replace("\r\n", "\n").replace("\r", "\n").split("\n") if line.strip()]
    if not lines:
        return ""
    # Tesseract may read the Latin-font question number as punctuation, I, or a wrong digit.
    lines[0] = re.sub(
        r"^\s*(?:(?:[A-Za-zअ-ह]{1,2}\s*[-–]\s*)?[0-9०-९Il|]{1,3}\s*[.)]\s*|[.)]\s*)",
        "", lines[0],
    ).strip()
    normalized = "\n".join(line for line in lines if line)
    # Reject OCR noise; genuine Hindi prose must contain a usable Devanagari word.
    if len(re.findall(r"[\u0900-\u097F]", normalized)) < 4:
        return ""
    return normalized


def _tesseract_hindi_available() -> bool:
    global _TESSERACT_HINDI_AVAILABLE
    if _TESSERACT_HINDI_AVAILABLE is not None:
        return _TESSERACT_HINDI_AVAILABLE
    if not shutil.which("tesseract"):
        _TESSERACT_HINDI_AVAILABLE = False
        return False
    try:
        result = subprocess.run(
            ["tesseract", "--list-langs"], capture_output=True, text=True, timeout=8, check=False
        )
        _TESSERACT_HINDI_AVAILABLE = result.returncode == 0 and re.search(
            r"(?m)^\s*hin\s*$", result.stdout or ""
        ) is not None
    except (OSError, subprocess.SubprocessError):
        _TESSERACT_HINDI_AVAILABLE = False
    return _TESSERACT_HINDI_AVAILABLE


def _ocr_shree_dev_prompt(page: Any, bbox: tuple[float, float, float, float]) -> tuple[str, str]:
    """OCR only the Hindi prompt block rendered with a known SHREE-DEV legacy font.

    OCR is a reconstruction aid, never an approval signal. If Hindi trained data or a usable
    prompt is unavailable, leave the original encoded text unchanged and mark it for review.
    """
    if not _tesseract_hindi_available():
        return "", "SHREEDEV_OCR_UNAVAILABLE_RAW_PRESERVED"
    try:
        x0, y0, x1, y1 = bbox
        clip = fitz.Rect(x0 - 2, y0 - 2, x1 + 3, y1 + 3) & page.rect
        if clip.width < 8 or clip.height < 8:
            return "", "SHREEDEV_OCR_INVALID_CROP_RAW_PRESERVED"
        pixmap = page.get_pixmap(matrix=fitz.Matrix(4, 4), clip=clip, alpha=False)
        result = subprocess.run(
            ["tesseract", "stdin", "stdout", "-l", "hin", "--psm", "6"],
            input=pixmap.tobytes("png"), capture_output=True, timeout=15, check=False,
        )
        if result.returncode != 0:
            return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"
        decoded = result.stdout.decode("utf-8", errors="replace")
        normalized = normalize_ocr_prompt(decoded)
        if not normalized:
            return "", "SHREEDEV_OCR_NO_USABLE_HINDI_RAW_PRESERVED"
        return normalized, "OCR_SHREEDEV_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"




def normalize_ocr_option(value: str) -> str:
    """Remove the printed option marker from OCR output and reject non-Hindi OCR noise."""
    lines = [line.strip() for line in str(value or "").replace("\r\n", "\n").replace("\r", "\n").split("\n") if line.strip()]
    if not lines:
        return ""
    lines[0] = re.sub(r"^\s*[\(\[]?\s*[A-E0-9Il|]\s*[\)\].:-]\s*", "", lines[0], count=1, flags=re.IGNORECASE).strip()
    normalized = "\n".join(line for line in lines if line)
    return normalized if len(re.findall(r"[\u0900-\u097F]", normalized)) >= 2 else ""


def _ocr_shree_dev_stimulus(page: Any, bbox: tuple[float, float, float, float]) -> tuple[str, str]:
    """OCR a shared passage paragraph rendered in a recognized legacy Hindi glyph stream."""
    if not _tesseract_hindi_available():
        return "", "SHREEDEV_OCR_UNAVAILABLE_RAW_PRESERVED"
    try:
        x0, y0, x1, y1 = bbox
        clip = fitz.Rect(x0 - 2, y0 - 2, x1 + 3, y1 + 3) & page.rect
        if clip.width < 8 or clip.height < 8:
            return "", "SHREEDEV_OCR_INVALID_CROP_RAW_PRESERVED"
        pixmap = page.get_pixmap(matrix=fitz.Matrix(4, 4), clip=clip, alpha=False)
        result = subprocess.run(
            ["tesseract", "stdin", "stdout", "-l", "hin", "--psm", "6"],
            input=pixmap.tobytes("png"), capture_output=True, timeout=25, check=False,
        )
        if result.returncode != 0:
            return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"
        text = result.stdout.decode("utf-8", errors="replace").strip()
        if len(re.findall(r"[\u0900-\u097F]", text)) < 20:
            return "", "SHREEDEV_OCR_NO_USABLE_HINDI_RAW_PRESERVED"
        return preserve_layout(text), "OCR_SHREEDEV_STIMULUS_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"


def _ocr_shree_dev_option(page: Any, bbox: tuple[float, float, float, float]) -> tuple[str, str]:
    """OCR one legacy-font Hindi option block; the result always remains review-gated."""
    if not _tesseract_hindi_available():
        return "", "SHREEDEV_OCR_UNAVAILABLE_RAW_PRESERVED"
    try:
        x0, y0, x1, y1 = bbox
        clip = fitz.Rect(x0 - 2, y0 - 2, x1 + 3, y1 + 3) & page.rect
        if clip.width < 8 or clip.height < 8:
            return "", "SHREEDEV_OCR_INVALID_CROP_RAW_PRESERVED"
        pixmap = page.get_pixmap(matrix=fitz.Matrix(4, 4), clip=clip, alpha=False)
        result = subprocess.run(
            ["tesseract", "stdin", "stdout", "-l", "hin", "--psm", "6"],
            input=pixmap.tobytes("png"), capture_output=True, timeout=15, check=False,
        )
        if result.returncode != 0:
            return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"
        normalized = normalize_ocr_option(result.stdout.decode("utf-8", errors="replace"))
        if not normalized:
            return "", "SHREEDEV_OCR_NO_USABLE_HINDI_RAW_PRESERVED"
        return normalized, "OCR_SHREEDEV_OPTION_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        return "", "SHREEDEV_OCR_FAILED_RAW_PRESERVED"


def union_pdf_boxes(boxes: list[tuple[float, float, float, float]]) -> tuple[float, float, float, float] | None:
    """Return the minimum rectangle covering a set of PyMuPDF text-line boxes."""
    if not boxes:
        return None
    return (
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    )


def extract_page_questions(pdf: Any, cycle: str, meta: dict[str, Any]) -> list[dict[str, Any]]:
    collected: list[dict[str, Any]] = []
    for page_index in range(len(pdf)):
        page_num = page_index + 1
        # The question booklet's language section and scored sections run on pages 2–45.
        if page_num < 2 or page_num > 45:
            continue
        page = pdf[page_index]
        # Correlate positioned text blocks with span-level fonts. OCR is restricted to the
        # source blocks explicitly rendered with SHREE-DEV-0708/0709, never applied globally.
        block_metadata: dict[tuple[float, float], dict[str, Any]] = {}
        for text_block in page.get_text("dict").get("blocks", []):
            if not text_block.get("lines") or not text_block.get("bbox"):
                continue
            bx0, by0, bx1, by1 = (float(value) for value in text_block["bbox"])
            fonts: set[str] = set()
            line_metadata: list[dict[str, Any]] = []
            for line in text_block.get("lines", []):
                spans = line.get("spans", [])
                line_fonts = {str(span.get("font", "")).strip().upper() for span in spans}
                fonts.update(line_fonts)
                line_box = tuple(float(value) for value in line.get("bbox", (0, 0, 0, 0)))
                line_metadata.append({
                    "text": "".join(str(span.get("text", "")) for span in spans),
                    "bbox": line_box,
                    "fonts": line_fonts,
                })
            block_metadata[(round(by0, 2), round(bx0, 2))] = {
                "bbox": (bx0, by0, bx1, by1),
                "fonts": fonts,
                "lines": line_metadata,
            }
        columns: dict[str, list[tuple[float, str, float]]] = {"left": [], "right": []}
        for block in page.get_text("blocks"):
            x0, y0, x1, y1, block_text, *_ = block
            if y0 < 55 or y0 > 715:
                continue
            column = "left" if x0 < PAGE_SPLIT_X else "right"
            columns[column].append((float(y0), str(block_text), float(x0)))

        # Passage ranges are page-local and language-neutral. They let each question retain a
        # shared stimulus identity without making copies of the paragraph in the content model.
        passage_specs: list[dict[str, Any]] = []
        for column, blocks in columns.items():
            blocks.sort(key=lambda item: item[0])
            for block_index, (y0, block_text, x0) in enumerate(blocks):
                question_range = _parse_question_range(block_text)
                if not question_range or not _is_passage_instruction(block_text):
                    continue
                range_prefix, first_number, last_number = question_range
                group_id = (
                    f"{cycle.lower().replace(' ', '-')}-stimulus-"
                    f"{(range_prefix or 'q').lower()}-{first_number}-{last_number}"
                )
                passage_specs.append({
                    "column": column,
                    "header_y": y0,
                    "header_text": block_text,
                    "header_index": block_index,
                    "prefix": range_prefix,
                    "first": first_number,
                    "last": last_number,
                    "group_id": group_id,
                    "language_only": (
                        (range_prefix in {"E", "H", "U", "B"} and first_number <= 30)
                        or (range_prefix is None and section_for_number(cycle, first_number) == "LANGUAGE_QUALIFYING")
                    ),
                })

        # Find the actual paragraph for each declared range. Some PDFs combine the first
        # question with the end of its passage (TRE 3.0 E-1); others put the paragraph in
        # its own block (TRE 3.0 H-9/H-15).
        stimulus_by_group: dict[str, dict[str, Any]] = {}
        stimulus_source_keys: set[tuple[str, float, float, str]] = set()
        for spec in passage_specs:
            blocks = columns[spec["column"]]
            for y0, block_text, x0 in blocks:
                if y0 <= spec["header_y"] or block_text == spec["header_text"]:
                    continue
                preamble, question_parts = _split_block_questions(block_text, cycle)
                matching_part = next((
                    part for identity, number, prefix, part in question_parts
                    if number == spec["first"] and
                    (spec["prefix"] is None or prefix == spec["prefix"])
                ), None)
                passage_text = ""
                source_y = y0
                source_x = x0
                if matching_part is not None and len(preserve_layout(preamble)) >= 80:
                    passage_text = preserve_layout(preamble)
                elif not question_parts and len(preserve_layout(block_text)) >= 100:
                    # Skip running headers, page identifiers and tiny headings; only the large
                    # first paragraph between the direction and its first question is the stimulus.
                    stripped = preserve_layout(block_text)
                    if not re.fullmatch(r"[\d\s./_-]+", stripped) and not stripped.lower().startswith("part -"):
                        passage_text = stripped
                if passage_text:
                    stimulus_record = {
                        "text": passage_text,
                        "raw_text": passage_text,
                        "source_language": (
                            {"E": "en", "H": "hi", "U": "ur", "B": "bn"}.get(str(spec["prefix"]).upper())
                            if spec["prefix"] in {"E", "H", "U", "B"} else
                            ("en" if spec["first"] <= 8 else "hi")
                        ),
                        "page": page_num,
                        "column": spec["column"],
                        "top_y": round(source_y, 2),
                    }
                    if stimulus_record["source_language"] == "hi":
                        stimulus_metadata = block_metadata.get((round(source_y, 2), round(source_x, 2)), {})
                        if uses_legacy_hindi_glyphs(passage_text, stimulus_metadata.get("fonts", set())):
                            lines = stimulus_metadata.get("lines", [])
                            mixed_fonts = any(
                                str(line.get("text", "")).strip()
                                and not uses_legacy_hindi_glyphs(str(line.get("text", "")), line.get("fonts", set()))
                                and not re.fullmatch(r"[\d\s.,:/()+=%-]+", str(line.get("text", "")).strip())
                                for line in lines
                            )
                            if mixed_fonts:
                                stimulus_record["ocr_status"] = "SHREEDEV_OCR_MIXED_FONT_RAW_PRESERVED"
                            else:
                                stimulus_bbox = stimulus_metadata.get("bbox")
                                if stimulus_bbox:
                                    ocr_text, ocr_status = _ocr_shree_dev_stimulus(page, stimulus_bbox)
                                    stimulus_record["ocr_status"] = ocr_status
                                    if ocr_text:
                                        stimulus_record["ocr_text"] = ocr_text
                    stimulus_by_group[spec["group_id"]] = stimulus_record
                    # Mark the original paragraph or question-block preamble so it is not appended
                    # to a previous question as if it were an answer option.
                    stimulus_source_keys.add((spec["column"], round(source_y, 2), source_x, passage_text))
                    break

        def identity_matches_spec(identity: str, number: int, prefix: str, spec: dict[str, Any]) -> bool:
            return spec["first"] <= number <= spec["last"] and (
                spec["prefix"] is None or prefix == spec["prefix"]
            )

        # Extract question labels anywhere on a text block's physical lines, not only its first
        # non-empty line. This fixes missed E-1/E-6 labels after a passage or direction paragraph.
        for column, blocks in columns.items():
            numbered_x: list[float] = []
            for _, candidate_text, candidate_x in blocks:
                _, candidates = _split_block_questions(candidate_text, cycle)
                if candidates:
                    numbered_x.append(candidate_x)
            column_origin = min(numbered_x) if numbered_x else min((item[2] for item in blocks), default=0)
            question_margin = column_origin + 55
            active: dict[str, Any] | None = None

            def flush_active() -> None:
                nonlocal active
                if active:
                    active["raw_text"] = preserve_layout("\n".join(active.pop("_lines")))
                    prompt_bbox = active.pop("_prompt_ocr_bbox", None)
                    prompt_ocr_status = active.pop("_prompt_ocr_status", None)
                    if prompt_bbox and active.get("language") == "hi":
                        ocr_prompt, ocr_status = _ocr_shree_dev_prompt(page, prompt_bbox)
                        active["prompt_ocr_status"] = ocr_status
                        if ocr_prompt:
                            active["prompt_ocr_text"] = ocr_prompt
                    elif prompt_ocr_status and active.get("language") == "hi":
                        active["prompt_ocr_status"] = prompt_ocr_status
                    option_texts = active.pop("_ocr_option_texts", {})
                    option_statuses = active.pop("_ocr_option_statuses", {})
                    for option_key, option_bbox in active.pop("_ocr_option_bboxes", {}).items():
                        option_text, option_status = _ocr_shree_dev_option(page, option_bbox)
                        option_statuses[option_key] = option_status
                        if option_text:
                            option_texts[option_key] = option_text
                    if option_texts:
                        active["option_ocr_texts"] = option_texts
                        active["option_ocr_statuses"] = option_statuses
                    collected.append(active)
                    active = None

            logical: list[tuple[float, str, float, str | None, int | None, str | None, str | None]] = []
            block_question_counts: dict[tuple[str, float, float], tuple[int, bool]] = {}
            for y0, block_text, x0 in blocks:
                preamble, question_parts = _split_block_questions(block_text, cycle)
                block_question_counts[(column, round(y0, 2), round(x0, 2))] = (
                    len(question_parts), bool(preamble.strip())
                )
                if preamble.strip():
                    logical.append((y0, preamble, x0, None, None, None, None))
                for part_index, (identity, number, prefix, part_text) in enumerate(question_parts):
                    logical.append((y0 + (part_index + 1) * 0.001, part_text, x0, identity, number, prefix, identity))
                if not question_parts and not preamble.strip():
                    logical.append((y0, block_text, x0, None, None, None, None))

            logical.sort(key=lambda item: item[0])
            for y0, block_text, x0, identity, number, prefix, _ in logical:
                if identity is None:
                    # Instructions introduce ranges; they are not part of a previous stem.
                    parsed_range = _parse_question_range(block_text)
                    if parsed_range and (
                        _is_passage_instruction(block_text) or "direction" in block_text.lower()
                        or "fill in the blanks" in block_text.lower()
                    ):
                        flush_active()
                        continue
                    normalized_block = preserve_layout(block_text)
                    if any(
                        col == column and round(y0, 2) == round(source_y, 2)
                        and normalized_block == source_text
                        for col, source_y, _source_x, source_text in stimulus_source_keys
                    ):
                        flush_active()
                        continue
                    # When a passage and its first question share a PDF block, the passage was
                    # split off into a separate logical item above; skip that preamble here.
                    if len(normalized_block) >= 80 and any(
                        spec["column"] == column and spec["header_y"] < y0
                        and _is_passage_instruction(spec["header_text"])
                        for spec in passage_specs
                    ):
                        flush_active()
                        continue
                    if active:
                        active["_lines"].append(block_text.rstrip())
                        option_match = OPTION_START.match(block_text)
                        font_metadata = block_metadata.get((round(y0, 2), round(x0, 2)), {})
                        if (
                            active.get("language") == "hi"
                            and option_match
                            and len(OPTION_START.findall(block_text)) == 1
                            and not looks_like_code_line(block_text)
                            and uses_legacy_hindi_glyphs(block_text, font_metadata.get("fonts", set()))
                            and font_metadata.get("bbox")
                        ):
                            option_key = option_match.group(1).upper()
                            option_text, option_status = _ocr_shree_dev_option(page, font_metadata["bbox"])
                            active.setdefault("_ocr_option_statuses", {})[option_key] = option_status
                            if option_text:
                                active.setdefault("_ocr_option_texts", {})[option_key] = option_text
                    continue

                assert number is not None and prefix is not None
                if x0 > question_margin or not _question_is_allowed(cycle, identity, number, prefix):
                    if active:
                        active["_lines"].append(block_text.rstrip())
                    continue
                flush_active()
                if prefix:
                    locale = {"E": "en", "H": "hi", "U": "ur", "B": "bn"}[prefix]
                    section = "LANGUAGE_QUALIFYING"
                elif cycle in {"TRE 2.0", "TRE 3.0"} and 1 <= number <= 8:
                    locale, section = "en", "LANGUAGE_QUALIFYING"
                elif cycle in {"TRE 2.0", "TRE 3.0"} and 9 <= number <= 30:
                    locale, section = "hi", "LANGUAGE_QUALIFYING"
                else:
                    locale = "en" if page_num % 2 == 0 else "hi"
                    section = section_for_number(cycle, number)

                first_line = next((line for line in block_text.splitlines() if line.strip()), "")
                parsed = _question_prefix(first_line)
                rest = parsed[2] if parsed else ""
                lines = block_text.splitlines()
                first_nonempty = next((i for i, line in enumerate(lines) if line.strip()), -1)
                tail = lines[first_nonempty + 1:] if first_nonempty >= 0 else []
                question_lines = ([rest] if rest.strip() else []) + tail
                active = {
                    "question_identity": identity,
                    "question_number": number,
                    "source_prefix": prefix or None,
                    "section_code": section,
                    "language": locale,
                    "pdf_page": page_num,
                    "column": column,
                    "top_y": round(y0, 2),
                    "raw_text": "",
                    "_lines": question_lines,
                }
                source_block_key = (column, round(y0, 2), round(x0, 2))
                font_metadata = block_metadata.get((round(y0, 2), round(x0, 2)), {})
                question_count, has_preamble = block_question_counts.get(source_block_key, (0, True))
                if (
                    locale == "hi"
                    and question_count == 1
                    and not has_preamble
                    and uses_legacy_hindi_glyphs(block_text, font_metadata.get("fonts", set()))
                    and font_metadata.get("bbox")
                ):
                    line_metadata = font_metadata.get("lines", [])
                    option_line_indices = [
                        index for index, line in enumerate(line_metadata)
                        if OPTION_START.match(str(line.get("text", "")))
                        and uses_legacy_hindi_glyphs(str(line.get("text", "")), line.get("fonts", set()))
                    ]
                    first_option_index = option_line_indices[0] if option_line_indices else len(line_metadata)
                    prompt_lines = [
                        line for line in line_metadata[:first_option_index]
                        if not QUESTION_LABEL_ONLY.fullmatch(str(line.get("text", "")).strip())
                    ]
                    prompt_has_mixed_fonts = any(
                        not uses_legacy_hindi_glyphs(str(line.get("text", "")), line.get("fonts", set()))
                        and str(line.get("text", "")).strip()
                        for line in prompt_lines
                    )
                    if prompt_has_mixed_fonts:
                        # Do not let OCR silently drop formula/code/Latin-font lines from a Hindi stem.
                        active["_prompt_ocr_status"] = "SHREEDEV_OCR_MIXED_FONT_RAW_PRESERVED"
                    else:
                        prompt_line_boxes = [
                            line["bbox"] for line in prompt_lines
                            if uses_legacy_hindi_glyphs(str(line.get("text", "")), line.get("fonts", set()))
                        ]
                        prompt_bbox = union_pdf_boxes(prompt_line_boxes)
                        if prompt_bbox:
                            active["_prompt_ocr_bbox"] = prompt_bbox
                    for line_index in option_line_indices:
                        line = line_metadata[line_index]
                        option_match = OPTION_START.match(str(line.get("text", "")))
                        if option_match:
                            active.setdefault("_ocr_option_bboxes", {})[option_match.group(1).upper()] = line["bbox"]
                # Attach the same stimulus ID/content to every item within its declared range.
                for spec in passage_specs:
                    if identity_matches_spec(identity, number, prefix, spec) and spec["group_id"] in stimulus_by_group:
                        active["stimulus_id"] = spec["group_id"]
                        stimulus_record = stimulus_by_group[spec["group_id"]]
                        active["stimulus_raw_text"] = stimulus_record["raw_text"]
                        active["stimulus_ocr_text"] = stimulus_record.get("ocr_text") or ""
                        active["stimulus_ocr_status"] = stimulus_record.get("ocr_status") or ""
                        active["stimulus_source_language"] = stimulus_record["source_language"]
                        active["stimulus_source_location"] = {
                            "pdf_page": stimulus_record["page"],
                            "column": stimulus_record["column"],
                            "top_y": stimulus_record["top_y"],
                        }
                        active["native_language_only"] = bool(spec["language_only"])
                        break
            flush_active()
    return collected

def parse_prompt_options(text: str, identity: str, language: str, cycle: str) -> tuple[str, list[dict[str, Any]]]:
    """Separate option labels without flattening the question's original layout."""
    normalized = preserve_layout(text.replace("\u00a0", " "))
    # Option labels must start a physical source line; an indented code line such as
    # (a == b) must not be split into an MCQ option.
    option_matches = list(OPTION_START.finditer(normalized))
    if not option_matches:
        prompt = preserve_layout(normalized)
        return prompt, []
    prompt = preserve_layout(normalized[:option_matches[0].start()])
    options: list[dict[str, Any]] = []
    for i, match in enumerate(option_matches):
        end = option_matches[i + 1].start() if i + 1 < len(option_matches) else len(normalized)
        label = match.group(1).upper()
        value = preserve_layout(normalized[match.end():end])
        options.append({
            "key": label,
            "raw_text": value,
            "text": value,
            "content_kind": classify_content(value),
            "code_line_indices": detected_code_line_indices(value),
            "math_line_indices": detected_math_line_indices(value),
        })
    return prompt, options


def expected_language_keys(cycle: str) -> set[str]:
    if cycle == "TRE 3.0":
        return {f"E-{i}" for i in range(1, 9)} | {f"H-{i}" for i in range(9, 31)}
    if cycle == "TRE 2.0":
        return {str(i) for i in range(1, 31)}
    return set()


def native_source_language(cycle: str, identity: str) -> str | None:
    """Return the actual source-language track for qualifying items, without translation."""
    key = str(identity).strip().upper()
    if cycle == "TRE 3.0":
        if re.fullmatch(r"E-[1-8]", key):
            return "en"
        if re.fullmatch(r"H-(?:[9]|[12][0-9]|30)", key):
            return "hi"
    if cycle == "TRE 2.0" and key.isdigit():
        number = int(key)
        if 1 <= number <= 8:
            return "en"
        if 9 <= number <= 30:
            return "hi"
    return None


def normalize_source_language_code(value: Any, fallback: str = "en") -> str:
    """Normalize booklet prefixes (E/H/U/B) and full language codes consistently."""
    code = str(value or fallback).strip().lower()
    return {"e": "en", "h": "hi", "u": "ur", "b": "bn"}.get(
        code, code if code in {"en", "hi", "ur", "bn"} else fallback
    )


def hindi_conversion_needs_review(status: str) -> bool:
    """Fail closed when a legacy Hindi glyph stream was not reliably decoded."""
    value = str(status or "")
    return (
        "FORCED_LEGACY" in value
        or "REQUIRES_HUMAN_REVIEW" in value
        or value.startswith("SHREEDEV_OCR_")
        or value in {
            "LEGACY_FONT_UNCERTAIN_RAW_PRESERVED",
            "CONVERTER_UNAVAILABLE",
        }
        or value.startswith("CONVERSION_ERROR:")
    )


def build_inventory(zip_path: Path, output_dir: Path, repo_root: Path) -> dict[str, Any]:
    if fitz is None:
        raise ValueError("PyMuPDF is required for PDF extraction: python -m pip install pymupdf")
    if not zip_path.is_file():
        raise ValueError(f"Source ZIP not found: {zip_path}")
    if zip_path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Source ZIP exceeds the configured 200 MiB limit.")
    output_dir.mkdir(parents=True, exist_ok=True)
    source_dir = repo_root / "data" / "TRE-Papres" / "sources"
    source_dir.mkdir(parents=True, exist_ok=True)
    records: dict[str, Any] = {}
    with zipfile.ZipFile(zip_path) as archive:
        infos = [i for i in archive.infolist() if not i.is_dir()]
        pdf_infos = [i for i in infos if i.filename.lower().endswith(".pdf")]
        if not pdf_infos or len(pdf_infos) > MAX_PDFS:
            raise ValueError(f"Expected 1–{MAX_PDFS} source PDFs; found {len(pdf_infos)}.")
        if any(i.file_size <= 0 or i.file_size > MAX_PDF_BYTES for i in pdf_infos):
            raise ValueError("A source PDF is empty or exceeds 80 MiB.")
        for info in pdf_infos:
            cycle = paper_for_name(Path(info.filename).name)
            if cycle not in PAPERS:
                raise ValueError(f"Unrecognized TRE PDF name in ZIP: {info.filename}")
            meta = PAPERS[cycle]
            source_pdf = source_dir / meta["pdf_name"]
            with archive.open(info, "r") as source, source_pdf.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            file_hash = sha256_file(source_pdf)
            with fitz.open(source_pdf) as pdf:
                raw_questions = extract_page_questions(pdf, cycle, meta)
                pages = len(pdf)
            # Keep every source-language variant for audit; default Hindi track is E1-E8 + H9-H30.
            variants: dict[str, dict[str, Any]] = {}
            for row in raw_questions:
                identity = row["question_identity"]
                # Discard optional Urdu/Bengali versions from the default test inventory, but retain
                # an index of their availability for future language-track variants.
                if row["language"] in {"ur", "bn"}:
                    continue
                if cycle == "TRE 3.0" and identity.startswith("E-"):
                    row["section_code"] = "LANGUAGE_QUALIFYING"
                if row["section_code"] != "LANGUAGE_QUALIFYING":
                    key = str(row["question_number"])
                else:
                    key = identity
                entry = variants.setdefault(key, {
                    "source_question_number": key if key.startswith(("E-","H-")) else (
                        int(key) if key.isdigit() else key
                    ),
                    "section_code": row["section_code"],
                    "source_variants": {},
                    "source_locations": {},
                    "review_flags": [],
                })
                # Deduplicate repeated headers/overlapping source blocks by keeping the fuller text.
                previous = entry["source_variants"].get(row["language"])
                prompt, options = parse_prompt_options(row["raw_text"], identity, row["language"], cycle)
                option_ocr_texts = row.get("option_ocr_texts") or {}
                option_ocr_statuses = row.get("option_ocr_statuses") or {}
                for option in options:
                    if option["key"] in option_ocr_texts:
                        option["ocr_text_i18n"] = option_ocr_texts[option["key"]]
                        option["ocr_status"] = option_ocr_statuses.get(option["key"], "OCR_SHREEDEV_OPTION_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW")
                candidate_length = len(prompt) + sum(len(o["text"]) for o in options)
                previous_length = 0
                if previous:
                    previous_length = len(previous.get("prompt_raw","")) + sum(
                        len(o.get("raw_text","")) for o in previous.get("options",[])
                    )
                if candidate_length >= previous_length:
                    entry["source_variants"][row["language"]] = {
                        "prompt_raw": prompt,
                        "prompt_ocr_text": row.get("prompt_ocr_text") or "",
                        "prompt_ocr_status": row.get("prompt_ocr_status") or "",
                        "options": options,
                        "conversion_status": "RAW_LEGACY" if row["language"] == "hi" else "SOURCE_TEXT",
                        "stimulus_id": row.get("stimulus_id"),
                        "stimulus_raw": row.get("stimulus_raw_text"),
                        "stimulus_ocr_text": row.get("stimulus_ocr_text") or "",
                        "stimulus_ocr_status": row.get("stimulus_ocr_status") or "",
                        "stimulus_source_location": row.get("stimulus_source_location") or {},
                        "stimulus_source_language": row.get("stimulus_source_language"),
                        "native_language_only": bool(row.get("native_language_only")),
                    }
                    entry["source_locations"][row["language"]] = {
                        "pdf_page": row["pdf_page"],
                        "column": row["column"],
                        "top_y": row["top_y"],
                    }

            # Apply contextual Hindi conversion only after the English sibling is known.
            for key, entry in variants.items():
                en = entry["source_variants"].get("en")
                hi = entry["source_variants"].get("hi")
                english_reference = ""
                if en:
                    english_reference = en.get("prompt_raw", "") + " " + " ".join(
                        option.get("raw_text", "") for option in en.get("options", [])
                    )
                native_language_only = entry["section_code"] == "LANGUAGE_QUALIFYING"
                # Language-paper items are source-language questions, not translations of a
                # parallel sibling. Font decoding may be required for Hindi glyphs, but never
                # use the English sibling to reconstruct the Hindi language-test wording.
                conversion_reference = "" if native_language_only else english_reference
                if hi:
                    if hi.get("prompt_ocr_text"):
                        converted_prompt = str(hi["prompt_ocr_text"])
                        status = str(hi.get("prompt_ocr_status") or "OCR_SHREEDEV_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW")
                    elif str(hi.get("prompt_ocr_status") or "").startswith("SHREEDEV_OCR_"):
                        converted_prompt = str(hi.get("prompt_raw", ""))
                        status = str(hi["prompt_ocr_status"])
                    else:
                        converted_prompt, status = convert_hindi_text(hi.get("prompt_raw", ""), conversion_reference)
                    hi["prompt_i18n"] = preserve_layout(converted_prompt)
                    hi["conversion_status"] = status
                    hi["translation_policy"] = "NATIVE_SOURCE_ONLY" if native_language_only else "BILINGUAL_SOURCE_VARIANTS"
                    hi["prompt_content_kind"] = classify_content(hi["prompt_i18n"])
                    hi["code_line_indices"] = detected_code_line_indices(hi["prompt_i18n"])
                    hi["math_line_indices"] = detected_math_line_indices(hi["prompt_i18n"])
                    hi["source_code_preservation_notes"] = "Original line breaks and indentation preserved; code-looking lines and operators bypass legacy-Hindi glyph substitution."
                    for option in hi.get("options", []):
                        # OCR is restricted to the source-rendered Hindi option block. Standard
                        # scored-paper D/E phrases remain canonical; language-paper options stay source-specific.
                        if option.get("ocr_text_i18n") and (native_language_only or option["key"] not in STANDARD_HI_OPTIONS):
                            option["text_i18n"] = option["ocr_text_i18n"]
                            option["hindi_ocr_status"] = option.get("ocr_status", "OCR_SHREEDEV_OPTION_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW")
                        elif option["key"] in STANDARD_HI_OPTIONS and not native_language_only:
                            option["text_i18n"] = STANDARD_HI_OPTIONS[option["key"]]
                        else:
                            option["text_i18n"], _ = convert_hindi_text(option.get("raw_text", ""), conversion_reference)
                        option["text_i18n"] = preserve_layout(option["text_i18n"])
                        option["content_kind"] = classify_content(option["text_i18n"])
                        option["code_line_indices"] = detected_code_line_indices(option["text_i18n"])
                        option["math_line_indices"] = detected_math_line_indices(option["text_i18n"])
                        option["source_code_preservation_notes"] = "Preserve code/formula glyphs and indentation; compare against the source page."
                if en:
                    en["prompt_i18n"] = preserve_layout(en.get("prompt_raw", ""))
                    en["translation_policy"] = "NATIVE_SOURCE_ONLY" if native_language_only else "BILINGUAL_SOURCE_VARIANTS"
                    en["prompt_content_kind"] = classify_content(en["prompt_i18n"])
                    en["code_line_indices"] = detected_code_line_indices(en["prompt_i18n"])
                    en["math_line_indices"] = detected_math_line_indices(en["prompt_i18n"])
                    en["source_code_preservation_notes"] = "Original English source, line breaks and indentation preserved."
                    for option in en.get("options", []):
                        if option["key"] in STANDARD_EN_OPTIONS and not native_language_only:
                            option["text_i18n"] = STANDARD_EN_OPTIONS[option["key"]]
                        else:
                            option["text_i18n"] = preserve_layout(option.get("raw_text", ""))
                        option["text_i18n"] = preserve_layout(option["text_i18n"])
                        option["content_kind"] = classify_content(option["text_i18n"])
                        option["code_line_indices"] = detected_code_line_indices(option["text_i18n"])
                        option["math_line_indices"] = detected_math_line_indices(option["text_i18n"])
                        option["source_code_preservation_notes"] = "Original English source, line breaks and indentation preserved."

                # Preserve and localize the shared paragraph separately from each question.
                # Repeated questions use the same stimulus_id, letting the UI render the paragraph
                # once and add any number of question controls beneath it.
                stimulus_i18n: dict[str, str] = {}
                stimulus_ids: set[str] = set()
                stimulus_languages: dict[str, str] = {}
                for lang, variant in (("en", en), ("hi", hi)):
                    if not variant or not variant.get("stimulus_raw"):
                        continue
                    raw_stimulus = str(variant["stimulus_raw"])
                    if lang == "hi":
                        if variant.get("stimulus_ocr_text"):
                            stimulus_value = str(variant["stimulus_ocr_text"])
                            stimulus_status = str(
                                variant.get("stimulus_ocr_status")
                                or "OCR_SHREEDEV_STIMULUS_RECONSTRUCTED_REQUIRES_HUMAN_REVIEW"
                            )
                        elif str(variant.get("stimulus_ocr_status") or "").startswith("SHREEDEV_OCR_"):
                            stimulus_value = raw_stimulus
                            stimulus_status = str(variant["stimulus_ocr_status"])
                        else:
                            stimulus_value, stimulus_status = convert_hindi_text(
                                raw_stimulus, "" if native_language_only else english_reference
                            )
                        variant["stimulus_conversion_status"] = stimulus_status
                    else:
                        stimulus_value = raw_stimulus
                    stimulus_value = preserve_layout(stimulus_value)
                    variant["stimulus_i18n"] = stimulus_value
                    variant["stimulus_content_kind"] = classify_content(stimulus_value)
                    variant["stimulus_code_line_indices"] = detected_code_line_indices(stimulus_value)
                    variant["stimulus_math_line_indices"] = detected_math_line_indices(stimulus_value)
                    stimulus_i18n[lang] = stimulus_value
                    if lang == "hi" and variant.get("stimulus_ocr_text"):
                        entry["hindi_stimulus_reconstruction"] = {
                            "method": "TESSERACT_HINDI_OCR",
                            "status": "REQUIRES_HUMAN_REVIEW",
                            "raw_legacy_text": raw_stimulus,
                            "source_location": variant.get("stimulus_source_location") or {},
                        }
                    if variant.get("stimulus_id"):
                        stimulus_ids.add(str(variant["stimulus_id"]))
                    stimulus_languages[lang] = normalize_source_language_code(
                        variant.get("stimulus_source_language"), lang
                    )
                if stimulus_i18n:
                    if len(stimulus_ids) > 1:
                        entry["review_flags"].append("STIMULUS_ID_MISMATCH_ACROSS_SOURCE_LANGUAGES")
                    entry["stimulus_id"] = sorted(stimulus_ids)[0] if stimulus_ids else None
                    entry["stimulus_i18n"] = stimulus_i18n
                    entry["stimulus_source_languages"] = stimulus_languages
                    entry["stimulus_content_kind"] = classify_content(next(iter(stimulus_i18n.values())))
                    entry["native_language_only"] = native_language_only
                    if entry["stimulus_id"]:
                        # One-to-many relationship within the existing assessment question model:
                        # all child questions retain their own source number, marks and response ID.
                        entry["question_set_id"] = entry["stimulus_id"]
                        entry["question_set_type"] = "PASSAGE"
                elif entry["section_code"] == "LANGUAGE_QUALIFYING":
                    entry["native_language_only"] = True
                    entry["content_language_policy"] = "NATIVE_SOURCE_ONLY"

                if entry["section_code"] in {"GENERAL_STUDIES", "COMPUTER_SCIENCE"}:
                    if not en:
                        entry["review_flags"].append("MISSING_ENGLISH_VARIANT")
                    if not hi:
                        entry["review_flags"].append("MISSING_HINDI_VARIANT")
                    for lang, variant in (("en",en),("hi",hi)):
                        if variant:
                            prompt_value = variant.get("prompt_i18n", "")
                            if not prompt_value.strip():
                                entry["review_flags"].append(f"EMPTY_{lang.upper()}_PROMPT")
                            if len(variant.get("options", [])) < 2:
                                entry["review_flags"].append(f"OPTIONS_NEED_REVIEW_{lang.upper()}")
                else:
                    selected_lang = native_source_language(cycle, key) or (
                        "en" if (key.startswith("E-") or (key.isdigit() and int(key) <= 8)) else "hi"
                    )
                    entry["native_language_only"] = True
                    entry["native_source_language"] = selected_lang
                    entry["content_language_policy"] = "NATIVE_SOURCE_ONLY"
                    # Apply the same source-language contract to every variant, including
                    # standalone questions that do not belong to a shared stimulus.
                    for source_variant in entry["source_variants"].values():
                        source_variant["native_language_only"] = True
                        source_variant["native_source_language"] = selected_lang
                        source_variant["content_language_policy"] = "NATIVE_SOURCE_ONLY"
                        source_variant["translation_policy"] = "NATIVE_SOURCE_ONLY"
                    if selected_lang not in entry["source_variants"]:
                        entry["review_flags"].append("MISSING_SELECTED_LANGUAGE_TRACK")
                # Apply the same glyph-conversion warning to qualifying-language questions.
                # They often have no English sibling by design, but must still never appear
                # as review-ready merely because the question is in the native language track.
                has_reconstructed_hindi_option = bool(
                    hi and any(
                        option.get("hindi_ocr_status")
                        for option in hi.get("options", [])
                    )
                )
                if hi and (
                    hindi_conversion_needs_review(hi.get("conversion_status", ""))
                    or has_reconstructed_hindi_option
                ):
                    if "HINDI_FONT_DETECTION_UNCERTAIN" not in entry["review_flags"]:
                        entry["review_flags"].append("HINDI_FONT_DETECTION_UNCERTAIN")
            cycle_records = []
            for key, entry in variants.items():
                entry["question_id"] = (
                    f"{cycle.lower().replace(' ', '-')}-{entry['section_code'].lower()}-{str(key).lower().replace(' ','-')}"
                )
                entry["sequence_no"] = None
                cycle_records.append(entry)
            # Preserve actual-paper order: language questions first, then GS, then CS.
            def sequence_key(record: dict[str, Any]) -> tuple[int, int]:
                code = record["section_code"]
                qnum = record["source_question_number"]
                if code == "LANGUAGE_QUALIFYING":
                    if isinstance(qnum, str) and qnum.startswith(("E-","H-")):
                        number = int(qnum.split("-")[1])
                        return (0,number)
                    number=int(qnum)
                    return (0,number)
                return (1 if code=="GENERAL_STUDIES" else 2,int(qnum))
            all_source_records=cycle_records
            language_records=[r for r in all_source_records if r["section_code"]=="LANGUAGE_QUALIFYING"]
            # Preserve every question present in each original booklet. TRE 2.0/3.0 have
            # 30 qualifying-language questions in addition to 40 GS + 80 CS. Only GS and CS
            # contribute to the 120-mark scored result; qualifying items remain in the same attempt.
            course_records=list(all_source_records)
            course_records.sort(key=sequence_key)
            for sequence, record in enumerate(course_records,1):
                record["sequence_no"]=sequence
            for sequence, record in enumerate(sorted(language_records,key=sequence_key),1):
                record["source_inventory_sequence_no"]=sequence
            # Audit the GS/CS sections scored out of 120; preserve any qualifying-language questions too.
            paired_sections={}
            for section,(low,high) in meta["expected"].items():
                keys={str(q) for q in range(low,high+1)}
                rows=[r for r in course_records if r["section_code"]==section]
                paired_sections[section]={
                    "expected_questions":high-low+1,
                    "extracted_questions":len(rows),
                    "missing_question_numbers":sorted(keys-{str(r["source_question_number"]) for r in rows},key=lambda x:int(x)),
                    "questions_missing_english":sum("MISSING_ENGLISH_VARIANT" in r["review_flags"] for r in rows),
                    "questions_missing_hindi":sum("MISSING_HINDI_VARIANT" in r["review_flags"] for r in rows),
                    "questions_needing_visual_or_text_review":sum(bool(r["review_flags"]) for r in rows),
                }
            expected_language=len(expected_language_keys(cycle))
            language_check={
                "expected_default_track_questions":expected_language if cycle!="TRE 1.0" else 0,
                "extracted_default_track_questions":len(language_records),
                "missing_language_question_keys":sorted(
                    expected_language_keys(cycle)-{str(r["source_question_number"]) for r in language_records}
                ) if cycle!="TRE 1.0" else [],
                "included_in_tre4_practice_test":cycle in {"TRE 2.0","TRE 3.0"},
                "included_in_scored_result":False,
                "note":"Preserve all questions from each original booklet. TRE 1.0 has 120 questions (40 GS + 80 CS); TRE 2.0 and TRE 3.0 have 150 each (30 qualifying language + 40 GS + 80 CS). Only GS and CS contribute to the two scored result sections."
            }
            # Also retain alternative Urdu/Bengali source-track availability without adding those
            # questions to the TRE-4 CS/GS test.
            alternate_language_tracks={}
            for track in ("ur","bn"):
                matching=[r for r in raw_questions if r["language"]==track and r["section_code"]=="LANGUAGE_QUALIFYING"]
                if matching:
                    alternate_language_tracks[track]={
                        "question_count":len(matching),
                        "source_questions":[
                            {
                                "source_question_number":r["question_identity"],
                                "pdf_page":r["pdf_page"],
                                "raw_text":r["raw_text"],
                                "conversion_status":"RAW_SOURCE_LANGUAGE_PRESERVED_REQUIRES_NATIVE_LANGUAGE_REVIEW"
                            } for r in matching
                        ]
                    }
            records[cycle]={
                "title":f"BPSC {cycle} — Computer Science Previous-Year Paper",
                "cycle":cycle,
                "source_file":{
                    "archive_member":info.filename,
                    "repository_path":str(source_pdf.relative_to(repo_root)).replace("\\","/"),
                    "size_bytes":source_pdf.stat().st_size,
                    "sha256":file_hash,
                    "pdf_page_count":pages,
                    "source_page_status":"EXTRACTED_FROM_USER_PROVIDED_REPOSITORY_ZIP",
                    "repository_distribution_rights":"CLEARED_FOR_PUBLIC_REPOSITORY",
                },
                "exam_configuration":{
                    "duration_minutes":meta["duration_minutes"],
                    "total_questions":meta["total_questions"],
                    "marks_per_question":1,
                    "negative_marking_fraction":meta["negative_marking_fraction"],
                    "scored_question_count":120,
                    "scored_sections":{"GENERAL_STUDIES":40,"COMPUTER_SCIENCE":80},
                    "qualifying_section_question_count":len(expected_language_keys(cycle)) if cycle in {"TRE 2.0","TRE 3.0"} else 0,
                },
                "extraction_summary":{
                    "raw_question_blocks_detected":len(raw_questions),
                    "practice_test_question_count":len(course_records),
                    "source_inventory_question_count":len(all_source_records),
                    "qualifying_language_question_count_included":len(language_records),
                    "section_counts":dict(Counter(r["section_code"] for r in course_records)),
                    "section_pair_audit":paired_sections,
                    "language_section_audit":language_check,
                    "question_flags":sum(len(r["review_flags"]) for r in course_records),
                },
                "questions":course_records,
                "source_language_qualifying_questions":language_records,
                "alternate_language_tracks":alternate_language_tracks,
                "source_notes":[
                    "Original PDF bytes are preserved exactly; SHA-256 is recorded.",
                    "Supported legacy Hindi encodings are converted to Unicode. SHREE-DEV-0708/0709 prompt/option lines use Hindi OCR only when Tesseract Hindi data is available; reconstructed text remains pending human review, and raw legacy text plus page provenance are preserved. Mixed-font stems containing non-Hindi formula/code lines stay raw for manual reconstruction.",
                    "All questions present in each original booklet are preserved. TRE 2.0 and TRE 3.0 each have 30 qualifying-language items in addition to 40 General Studies + 80 Computer Science, for 150 total. Only GS and Computer Science contribute to the scored result, reported in two sections.",
                    "Do not release until question/option pair completeness, Hindi rendering, answers, concise explanations, paper rules and any mathematical/code expressions have been reviewed.",
                ],
            }

    inventory={
        "schema":"quantaedge.tre-question-source-inventory.v1",
        "source_archive":{
            "repository_path":"data/TRE-4_PYQ.zip",
            "size_bytes":zip_path.stat().st_size,
            "sha256":sha256_file(zip_path),
        },
        "language_contract":{
            "scored_sections":["GENERAL_STUDIES","COMPUTER_SCIENCE"],
            "source_language_variants_required":["en","hi"],
            "qualifying_section_policy":"QUALIFYING_ONLY",
            "explanation_style":{
                "direct":"One or two simple sentences; state the reason clearly.",
                "stepwise":"For numerical/formula/code-tracing work, show 2–8 short ordered steps before the conclusion."
            },
        },
        "papers":records,
        "publication_state":"SOURCE_INVENTORY_ONLY_NOT_READY_FOR_STUDENT_DELIVERY",
    }
    out_path=output_dir/"tre-question-inventory.json"
    out_path.write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

    # Update the committed source register to reference exact stored binaries and their checksums.
    register_path=repo_root/"data"/"TRE-Papres"/"source-register.json"
    if register_path.is_file():
        register=json.loads(register_path.read_text(encoding="utf-8"))
        for existing in register.get("sources",[]):
            cycle=existing.get("cycle")
            if cycle in records:
                binary=records[cycle]["source_file"]
                existing["source_status"]="VERIFIED_SOURCE_FILE"
                existing["binary_path"]=binary["repository_path"]
                existing["sha256"]=binary["sha256"]
                existing["size_bytes"]=binary["size_bytes"]
                existing["pdf_page_count"]=binary["pdf_page_count"]
                existing["repository_distribution_rights"]="CLEARED_FOR_PUBLIC_REPOSITORY"
                existing["verification_note"]="Exact PDF bytes extracted from the user-provided data/TRE-4_PYQ.zip; file existence and SHA-256 verified. Question-level content and answer review are still pending."
        register["binary_collection_path"]="data/TRE-Papres/sources/"
        register["binary_collection_status"]="SOURCE_PDFS_EXTRACTED_AND_HASHED"
        register_path.write_text(json.dumps(register,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

    report={
        "schema":"quantaedge.tre-question-extraction-report.v1",
        "source_archive_sha256":inventory["source_archive"]["sha256"],
        "papers":{},
        "status":"REVIEW_REQUIRED",
    }
    for cycle,record in records.items():
        report["papers"][cycle]=record["extraction_summary"]
    (output_dir/"tre-question-extraction-report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return inventory


def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,required=True)
    parser.add_argument("--repo-root",type=Path,default=Path.cwd())
    args=parser.parse_args()
    try:
        inventory=build_inventory(args.archive,args.output_dir,args.repo_root)
        if any(
            details.get("questions_missing_english",0) or details.get("questions_missing_hindi",0)
            or details.get("missing_question_numbers")
            for paper in inventory["papers"].values()
            for details in paper["extraction_summary"]["section_pair_audit"].values()
        ):
            print("WARNING: question-pair audit has gaps; do not publish.",file=sys.stderr)
    except (OSError,ValueError,zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}",file=sys.stderr)
        return 1
    return 0


if __name__=="__main__":
    raise SystemExit(main())
