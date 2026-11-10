#!/usr/bin/env python3
"""Fidelity check of the requirement sources: each .docx original against its .md conversion.

The knowledge base cites the .md conversions in docs/requirements/src by line number. When a new
original arrives (an SRS, FRS or HLD version), run this before trusting the conversion:

    python scripts/source_docs_check.py                       # fail (exit 1) if text or tables differ
    python scripts/source_docs_check.py --figures <out-dir>   # also extract every figure with its caption

Checks per document: the same requirement IDs and the same number of tables in both; content a
converter may drop or misread (struck-through, hidden or highlighted text, text boxes, footnotes,
comments, tracked changes, embedded objects); the document properties (version, status, dates);
and the figures, which the .md cannot carry, listed with their captions. Two figures with the same
image bytes under different captions are reported: one of them is very likely the wrong image.
Read-only on docs/requirements (CLAUDE.md section 7); output goes to stdout and <out-dir>.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "requirements" / "src"
PAIRS = [
    ("FCI_Payroll_SRS_v0.5_Final.docx", "FCI_Payroll_SRS_v0.5_Final.md"),
    ("FCI_Payroll_FRS_V0.5.docx", "FCI_Payroll_FRS_V0.5.md"),
    ("FCI_Payroll_HLD_v0.6.docx", "FCI_Payroll_HLD_v0.6.md"),
]

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PR = "{http://schemas.openxmlformats.org/package/2006/relationships}"
# Requirement-style IDs of the sources (SRS-G-21, BR-CYC-005, FR-10, OQ-35, SC-..., LA-01 ...).
ID = re.compile(r"\b(?:SRS|BR|FR|NFR|SC|CF|OQ|FAP|DB|LA|CYC|INT|O)-[A-Z0-9]+(?:-[A-Z0-9]+)*\b")
CAPTION_WINDOW = 3  # paragraphs after an image searched for its caption


def text_of(element: ET.Element) -> str:
    return "".join(t.text or "" for t in element.iter(W + "t"))


def flag(rpr: ET.Element | None, tag: str) -> bool:
    if rpr is None or rpr.find(W + tag) is None:
        return False
    return rpr.find(W + tag).get(W + "val") not in ("0", "false", "none")


def properties(z: zipfile.ZipFile) -> dict[str, str]:
    found: dict[str, str] = {}
    for part in ("docProps/core.xml", "docProps/app.xml"):
        if part in z.namelist():
            for key, value in re.findall(r"<(?:\w+:)?(\w+)[^>]*>([^<]{1,160})</", z.read(part).decode("utf-8", "replace")):
                if key.lower() in ("title", "description", "category", "created", "modified", "pages", "words"):
                    found[key] = value
    return found


def figures(z: zipfile.ZipFile, body: ET.Element) -> list[dict[str, str]]:
    rels = ET.fromstring(z.read("word/_rels/document.xml.rels"))
    target = {r.get("Id"): r.get("Target") for r in rels.iter(PR + "Relationship")}
    paragraphs = list(body.iter(W + "p"))
    found = []
    for i, p in enumerate(paragraphs):
        for blip in p.iter(A + "blip"):
            media = target.get(blip.get(R + "embed"))
            if not media:
                continue
            caption = next(
                (t for t in (text_of(q).strip() for q in paragraphs[i + 1 : i + 1 + CAPTION_WINDOW]) if t), ""
            )
            data = z.read("word/" + media)
            found.append({"media": media, "caption": caption, "sha1": hashlib.sha1(data).hexdigest(), "data": data})
    return found


def check(docx: Path, md: Path, out: Path | None) -> bool:
    z = zipfile.ZipFile(docx)
    body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    md_text = md.read_text(encoding="utf-8")
    doc_text = "\n".join(text_of(p) for p in body.iter(W + "p"))
    ids_doc, ids_md = set(ID.findall(doc_text)), set(ID.findall(md_text))
    tables_doc = len(list(body.iter(W + "tbl")))
    tables_md = len(re.findall(r"^\|\s*-{3}", md_text, re.M))
    runs = [r.find(W + "rPr") for r in body.iter(W + "r") if text_of(r).strip()]
    risky = Counter(
        {
            "struck": sum(flag(rpr, "strike") or flag(rpr, "dstrike") for rpr in runs),
            "hidden": sum(flag(rpr, "vanish") for rpr in runs),
            "highlighted": sum(rpr is not None and rpr.find(W + "highlight") is not None for rpr in runs),
            "text boxes": len(list(body.iter(W + "txbxContent"))),
            "tracked changes": len(list(body.iter(W + "ins"))) + len(list(body.iter(W + "del"))),
            "embedded objects": sum(n.startswith("word/embeddings/") for n in z.namelist()),
            "comments": int("word/comments.xml" in z.namelist()),
        }
    )
    for part in ("word/footnotes.xml", "word/endnotes.xml"):
        if part in z.namelist():
            # The two separator notes every document has carry no text.
            risky["footnotes"] += sum(1 for n in ET.fromstring(z.read(part)) if text_of(n).strip())
    figs = figures(z, body)
    print(f"== {docx.name}")
    for key, value in properties(z).items():
        print(f"   {key}: {value}")
    print(f"   requirement IDs: docx {len(ids_doc)}, md {len(ids_md)}; only in docx {sorted(ids_doc - ids_md)[:20]}; only in md {sorted(ids_md - ids_doc)[:20]}")
    print(f"   tables: docx {tables_doc}, md {tables_md}")
    print(f"   content a converter may drop: {dict(risky) if +risky else 'none'}")
    print(f"   figures: {len(figs)} (not in the .md)")
    by_hash: dict[str, list[str]] = {}
    for f in figs:
        by_hash.setdefault(f["sha1"], []).append(f["caption"][:70])
    for captions in by_hash.values():
        if len(set(captions)) > 1:
            print(f"   SAME IMAGE under different captions: {captions}")
    if out is not None:
        out.mkdir(parents=True, exist_ok=True)
        stem = docx.stem.replace("FCI_Payroll_", "")
        for n, f in enumerate(figs, 1):
            slug = re.sub(r"[^A-Za-z0-9]+", "_", f["caption"])[:60].strip("_") or "untitled"
            (out / f"{stem}-{n:03d}-{slug}.{f['media'].rsplit('.', 1)[-1]}").write_bytes(f["data"])
    return ids_doc == ids_md and tables_doc == tables_md


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--figures", type=Path, help="extract every figure into this folder")
    args = parser.parse_args()
    ok = True
    for docx, md in PAIRS:
        if not (SRC / docx).exists():
            print(f"== {docx}: not present, skipped")
            continue
        ok &= check(SRC / docx, SRC / md, args.figures)
    print("RESULT:", "the .md conversions carry the full text and tables" if ok else "MISMATCH - see above")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
