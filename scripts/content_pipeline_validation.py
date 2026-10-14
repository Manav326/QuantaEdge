"""Shared strict metadata and script checks for the textbook-to-content pipeline.

These checks catch obvious metadata/script mismatches; they do not replace human
review of source editions, OCR accuracy, subject alignment, or model-generated answers.
"""
from __future__ import annotations

import re
from typing import Any

_PLACEHOLDER_EXACT = {
    "", "unknown", "unverified", "current", "n/a", "na", "not specified",
    "official listing", "official catalogue", "official catalog",
}
_PLACEHOLDER_PHRASES = (
    "unverified",
    "to be confirmed",
    "needs confirmation",
    "verify edition",
    "verify the edition",
    "verify edition/session",
    "record the printed",
    "retain printed",
    "printed edition/reprint year from the source pdf",
    "edition/session from pdf",
    "edition from the source pdf",
    "official catalogue; retain",
    "official catalog; retain",
    "current official catalogue",
    "current official catalog",
    "official scert bihar e-resources; verify",
    "official listing",
    "not specified",
    "not available",
    "pending verification",
)

def is_placeholder_edition(value: Any) -> bool:
    """Return True when a value is blank or still describes a verification task."""
    normalized = re.sub(r"\s+", " ", str(value or "")).strip().casefold()
    if normalized in _PLACEHOLDER_EXACT:
        return True
    return any(phrase in normalized for phrase in _PLACEHOLDER_PHRASES)


def normalize_medium(value: Any) -> str | None:
    normalized = str(value or "").strip().casefold()
    if normalized in {"hi", "hindi", "हिंदी", "हिन्दी"}:
        return "hindi"
    if normalized in {"en", "english", "अंग्रेज़ी", "अंग्रेजी"}:
        return "english"
    return None


def script_profile(text: str) -> dict[str, int | float]:
    letters = [char for char in str(text or "") if char.isalpha()]
    devanagari = sum(1 for char in letters if "\u0900" <= char <= "\u097f")
    latin = sum(1 for char in letters if ("a" <= char.lower() <= "z"))
    return {
        "letter_count": len(letters),
        "devanagari_letter_count": devanagari,
        "latin_letter_count": latin,
        "devanagari_ratio": (devanagari / len(letters)) if letters else 0.0,
    }


def language_script_problem(text: str, language: Any, min_letters: int = 40) -> str | None:
    """Catch wrong-medium or insufficiently readable text; never claim semantic correctness."""
    medium = normalize_medium(language)
    if medium is None:
        return "Language must be explicitly identified as Hindi (hi) or English (en)."
    profile = script_profile(text)
    letters = int(profile["letter_count"])
    devanagari_ratio = float(profile["devanagari_ratio"])
    if letters < min_letters:
        return (
            f"Only {letters} alphabetic characters were extractable; there is insufficient text "
            "to verify the source/output language. Run OCR where appropriate and review the PDF."
        )
    if medium == "hindi" and devanagari_ratio < 0.08:
        return (
            f"Hindi was declared, but only {devanagari_ratio:.1%} of extracted letters are Devanagari. "
            "Check the selected medium, PDF edition, text layer, and OCR before proceeding."
        )
    if medium == "english" and devanagari_ratio > 0.25:
        return (
            f"English was declared, but {devanagari_ratio:.1%} of extracted letters are Devanagari. "
            "The source/output may be Hindi or mixed-medium; verify it before proceeding."
        )
    return None
