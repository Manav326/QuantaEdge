#!/usr/bin/env python3
"""Build an auditable bilingual question inventory from the user-provided TRE PDF ZIP.

Uses PDF text blocks rather than OCR. Legacy Hindi text is converted to Unicode with Lipi, but
both the raw source and a per-question source page are retained for comparison. This script does
not solve answers, create database records, or publish assessments.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
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
    except ImportError as exc:
        raise SystemExit("PyMuPDF is required: python -m pip install pymupdf") from exc

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
    return re.sub(r"\s+", " ", value).strip()


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
    if not raw:
        return "", "EMPTY"
    if HindiPreprocessor is None:
        return raw, "CONVERTER_UNAVAILABLE"
    protected, markers = protect_shared_latin_terms(raw, english_reference)
    detected_legacy, detected_font = HindiPreprocessor.detect_encoding(protected)
    font_type = detected_font if detected_font in {"krutidev", "chanakya"} else "krutidev"
    try:
        converted = HindiPreprocessor.convert(protected, font_type=font_type)
        converted = HindiPreprocessor.post_process(
            converted, repair_doubled_consonant_imatra=(font_type == "krutidev")
        )
    except Exception as exc:
        return raw, "CONVERSION_ERROR:" + type(exc).__name__
    for marker, token in markers:
        converted = converted.replace(marker, token)
    status = "CONVERTED_" + font_type.upper()
    if not detected_legacy:
        status += "_FORCED_LEGACY"
    return converted, status


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


def extract_page_questions(pdf: Any, cycle: str, meta: dict[str, Any]) -> list[dict[str, Any]]:
    collected: list[dict[str, Any]] = []
    for page_index in range(len(pdf)):
        page_num = page_index + 1
        page = pdf[page_index]
        columns: dict[str, list[tuple[float, str, float]]] = {"left": [], "right": []}
        for block in page.get_text("blocks"):
            x0, y0, x1, y1, text, *_ = block
            if y0 < 55 or y0 > 715:
                continue
            column = "left" if x0 < PAGE_SPLIT_X else "right"
            columns[column].append((float(y0), str(text), float(x0)))
        for column, blocks in columns.items():
            blocks.sort(key=lambda item: item[0])
            active: dict[str, Any] | None = None
            last_numeric_question: int | None = None
            for y0, text, x0 in blocks:
                # Question numbers align close to the column margin; this rejects numbered
                # statements/code lines indented within a question body.
                parsed = _question_prefix(text)
                candidate = None
                if x0 < 105 and parsed:
                    identity, number, _ = parsed
                    prefix = identity.split("-", 1)[0] if "-" in identity else ""
                    if prefix in {"E", "H", "U", "B"}:
                        allowed = number <= 30
                    else:
                        section = section_for_number(cycle, number)
                        allowed = section is not None
                    if allowed and (last_numeric_question is None or number > last_numeric_question):
                        candidate = (identity, number, prefix, parsed[2])
                if candidate:
                    if active:
                        active["raw_text"] = normalize_space("\n".join(active.pop("_lines")))
                        collected.append(active)
                    identity, number, prefix, rest = candidate
                    last_numeric_question = number
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
                        "_lines": [],
                    }
                    if rest.strip():
                        active["_lines"].append(rest.strip())
                    continue
                if active:
                    active["_lines"].append(text.strip())
            if active:
                active["raw_text"] = normalize_space("\n".join(active.pop("_lines")))
                collected.append(active)
    return collected


def parse_prompt_options(text: str, identity: str, language: str, cycle: str) -> tuple[str, list[dict[str, str]]]:
    normalized = text.replace("\u00a0", " ")
    # Native PDF text can place the first option on the same physical line as a stem.
    option_matches = list(OPTION_START.finditer(normalized))
    if not option_matches:
        prompt = normalize_space(normalized)
        return prompt, []
    prompt = normalize_space(normalized[:option_matches[0].start()])
    options: list[dict[str, str]] = []
    for i, match in enumerate(option_matches):
        end = option_matches[i + 1].start() if i + 1 < len(option_matches) else len(normalized)
        label = match.group(1).upper()
        value = normalize_space(normalized[match.end():end])
        options.append({"key": label, "raw_text": value, "text": value})
    return prompt, options


def expected_language_keys(cycle: str) -> set[str]:
    if cycle == "TRE 3.0":
        return {f"E-{i}" for i in range(1, 9)} | {f"H-{i}" for i in range(9, 31)}
    if cycle == "TRE 2.0":
        return {str(i) for i in range(1, 31)}
    return set()


def build_inventory(zip_path: Path, output_dir: Path, repo_root: Path) -> dict[str, Any]:
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
                candidate_length = len(prompt) + sum(len(o["text"]) for o in options)
                previous_length = 0
                if previous:
                    previous_length = len(previous.get("prompt_raw","")) + sum(
                        len(o.get("raw_text","")) for o in previous.get("options",[])
                    )
                if candidate_length >= previous_length:
                    entry["source_variants"][row["language"]] = {
                        "prompt_raw": prompt,
                        "options": options,
                        "conversion_status": "RAW_LEGACY" if row["language"] == "hi" else "SOURCE_TEXT",
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
                if hi:
                    converted_prompt, status = convert_hindi_text(hi.get("prompt_raw", ""), english_reference)
                    hi["prompt_i18n"] = converted_prompt
                    hi["conversion_status"] = status
                    for option in hi.get("options", []):
                        # The exam's standard D/E options are fixed phrases and should be exact.
                        if option["key"] in STANDARD_HI_OPTIONS:
                            option["text_i18n"] = STANDARD_HI_OPTIONS[option["key"]]
                        else:
                            option["text_i18n"], _ = convert_hindi_text(option.get("raw_text", ""), english_reference)
                if en:
                    en["prompt_i18n"] = en.get("prompt_raw", "")
                    for option in en.get("options", []):
                        if option["key"] in STANDARD_EN_OPTIONS:
                            option["text_i18n"] = STANDARD_EN_OPTIONS[option["key"]]
                        else:
                            option["text_i18n"] = option.get("raw_text", "")

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
                    if hi and hi.get("conversion_status","").endswith("FORCED_LEGACY"):
                        entry["review_flags"].append("HINDI_FONT_DETECTION_UNCERTAIN")
                else:
                    selected_lang = "en" if (key.startswith("E-") or (key.isdigit() and int(key) <= 8)) else "hi"
                    if selected_lang not in entry["source_variants"]:
                        entry["review_flags"].append("MISSING_SELECTED_LANGUAGE_TRACK")
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
            cycle_records.sort(key=sequence_key)
            for sequence, record in enumerate(cycle_records,1):
                record["sequence_no"]=sequence
            # Count all needed source pairs; counts reflect extracted native text blocks.
            paired_sections={}
            for section,(low,high) in meta["expected"].items():
                keys={str(q) for q in range(low,high+1)}
                rows=[r for r in cycle_records if r["section_code"]==section]
                paired_sections[section]={
                    "expected_questions":high-low+1,
                    "extracted_questions":len(rows),
                    "missing_question_numbers":sorted(keys-{str(r["source_question_number"]) for r in rows},key=lambda x:int(x)),
                    "questions_missing_english":sum("MISSING_ENGLISH_VARIANT" in r["review_flags"] for r in rows),
                    "questions_missing_hindi":sum("MISSING_HINDI_VARIANT" in r["review_flags"] for r in rows),
                    "questions_needing_visual_or_text_review":sum(bool(r["review_flags"]) for r in rows),
                }
            language_records=[r for r in cycle_records if r["section_code"]=="LANGUAGE_QUALIFYING"]
            expected_language=len(expected_language_keys(cycle))
            language_check={
                "expected_default_track_questions":expected_language if cycle!="TRE 1.0" else 0,
                "extracted_default_track_questions":len(language_records),
                "missing_language_question_keys":sorted(
                    expected_language_keys(cycle)-{str(r["source_question_number"]) for r in language_records}
                ) if cycle!="TRE 1.0" else [],
                "note":"For TRE 3.0, the default qualifying track uses E1–E8 and H9–H30; source variants for Urdu/Bengali are not mixed into this Hindi-track attempt."
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
                    "qualifying_section_question_count":30 if cycle in {"TRE 2.0","TRE 3.0"} else 0,
                },
                "extraction_summary":{
                    "raw_question_blocks_detected":len(raw_questions),
                    "question_inventory_count":len(cycle_records),
                    "section_counts":dict(Counter(r["section_code"] for r in cycle_records)),
                    "section_pair_audit":paired_sections,
                    "language_section_audit":language_check,
                    "question_flags":sum(len(r["review_flags"]) for r in cycle_records),
                },
                "questions":cycle_records,
                "source_notes":[
                    "Original PDF bytes are preserved exactly; SHA-256 is recorded.",
                    "Hindi values contain a Unicode conversion of the source's legacy Hindi encoding; raw text is retained beside it.",
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
