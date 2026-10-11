# QuantaEdge learning PDF draft distribution

This is a **distribution/import script**, not a PDF generator. It imports only the original QuantaEdge PDF files already inside the generated package ZIPs. It does not download textbook source PDFs, edit PDF pages, inject questions, or publish resources.

## What it does

- Maps each chapterwise PDF to its Class 6–8 Maths/Science chapter using package inventory metadata and an exact canonical chapter-code match (exact title is the only fallback).
- Maps each complete-book PDF to its Class/Subject book slot.
- Uploads the PDF once to the private QuantaEdge PDF library, then creates the matching learning-document assignment as **DRAFT**.
- Checks the authenticated staff session, active curriculum track, PDF signature/page count/size, and all chapter mappings before any upload.
- Uses SHA-256 and existing assignment metadata to skip duplicates. It is safe to rerun after a partial failure.
- Never calls a publish endpoint. Students cannot see the document assignments until a publisher reviews and publishes them in Admin.

## Important content rule

Chapterwise PDFs must contain concepts, worked examples/recap and QuantaEdge Advanced / Next Level material, but **no practice-question bank**. Complete-book PDFs must contain the current full set of concepts and questions.

I verified that the currently supplied expanded-practice ZIPs carry complete-book PDF bytes unchanged from the earlier packages. Therefore, the import script **does not add the newly expanded question bank to those PDF files**. Rebuild/replace the complete-book PDFs with the latest practice content before publishing them. The script attaches the supplied PDFs as drafts and records this warning in its JSON audit report; it intentionally does not claim the PDFs are publication-ready.

## Windows PowerShell

Run from `D:\QuantaEdge\QuantaEdge` after saving this file as `scripts\import_quantaedge_learning_pdfs.py` and ensuring all three class package ZIPs are alongside `docker-compose.yml`.

Install/verify the PDF-validation dependency once:

```powershell
& "D:\Tools\Python312\python.exe" -m pip install "PyMuPDF>=1.24,<2"
```

Sign into Admin (`http://localhost:3001/login`) as staff. Copy the current `QE_SESSION` cookie from browser DevTools → Application → Cookies; it is HttpOnly, so `document.cookie` will not show it. Do not share the cookie value.

```powershell
$secure = Read-Host "Paste fresh QE_SESSION value" -AsSecureString
$env:QUANTAEDGE_ADMIN_SESSION = [System.Net.NetworkCredential]::new("", $secure).Password
Remove-Variable secure

# DRY RUN: authenticates, validates all PDFs and chapter mappings, but makes no writes.
& "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_learning_pdfs.py `
  --package ".\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip" `
  --package ".\QuantaEdge_Class7_QuantaEdge_Hindi_DRAFT_Package.zip" `
  --package ".\QuantaEdge_Class8_QuantaEdge_Hindi_DRAFT_Package.zip" `
  --api-url "http://localhost:8080"

if ($LASTEXITCODE -ne 0) { throw "PDF preflight failed. No import was started; review the error/report." }

# APPLY only after dry-run reports PREFLIGHT_OK and every mapping is correct.
& "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_learning_pdfs.py `
  --package ".\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip" `
  --package ".\QuantaEdge_Class7_QuantaEdge_Hindi_DRAFT_Package.zip" `
  --package ".\QuantaEdge_Class8_QuantaEdge_Hindi_DRAFT_Package.zip" `
  --api-url "http://localhost:8080" `
  --apply

if ($LASTEXITCODE -ne 0) { throw "PDF import stopped; review quantaedge-pdf-import-report.json. Reruns are duplicate-safe." }
```

Expected current package inventory: **76 chapter PDFs + 6 complete-book PDFs = 82 draft assignment candidates** (22 Class 6 chapters, 27 Class 7 chapters, 27 Class 8 chapters; 2 book PDFs per class). The final numbers can be lower if an identical assignment already exists.

## Review before publish

Open Admin → **Textbook library**, filter status to **Drafts**, and review the resources class-wise and subject-wise. Check Hindi/Devanagari rendering and diagrams, chapter mapping, source edition/provenance, and that the PDF satisfies the chapterwise-vs-complete-book question rule. Change the edition/source metadata during review if needed. Publishing is a separate manual action.

If any chapter cannot be mapped exactly, the script stops before any upload. It does not guess a chapter from the chapter number. Resolve the mapping and rerun.