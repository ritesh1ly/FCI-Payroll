"""Compares the built screens with the v6.1 wireframe pack (layout reference; the SRS / FRS / DDL / KB stay
the source of truth, so a difference is a question, not automatically a defect).

Input: the JSON the pack extractor writes (card titles, table columns with their field-source badge, labels,
buttons per screen), e.g. node wireframe-extract.mjs wireframe.json (see the playbook). For each screen of
SCREENS it reads the component's source (frontend/src/modules/*/components/<Component>.tsx), resolves its
message keys to English, and reports per table column: in both (with the badge compared), wireframe only,
built only. Card titles are compared the same way.

Usage: python -I scripts/wireframe_compare.py <wireframe.json> [<live.json>] > docs/quality/wireframe-review.md
       With live.json (the same extraction run against the deployed app, one role per screen) the built side is
       what the screen renders; without it, the component source (misses shared components and dynamic keys).
"""

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MESSAGES = os.path.join(ROOT, "backend", "src", "main", "resources", "i18n", "messages_en.properties")

# Our screen code -> (component(s), the pack's screen code). The pack organises some functions under other codes.
SCREENS = {
    "X-05": (["ExceptionsWorklist"], "X-05"),
    "X-06": (["PayrollQueries"], "X-06"),
    "X-07": (["HrmsFallback"], "X-07"),
    "I-20": (["OffCycle"], "I-20"),
    "I-23": (["Supplementary"], "I-23"),
    "W-01": (["ApprovalInbox"], "X-02"),
    "W-02": (["MySubmissions"], "X-02"),
    "A-08": (["WorkflowMaster"], "X-01"),
    "A-01": (["UsersAdmin"], "X-01"),
    "A-02": (["RolesMatrix"], "X-01"),
    "A-03": (["AccessGroups"], "X-01"),
    "A-04": (["Assignments"], "X-01"),
    "A-05": (["AuditTrail"], "X-04"),
    "M-01": (["EmployeeMaster"], "M-01"),
    "M-04": (["StaffRates"], "M-04"),
    "M-11": (["StaffTransfers"], "M-11"),
    "M-14": (["NetPayFloors"], "M-14"),
    "M-15": (["CeilingNorms"], "M-15"),
    "M-16": (["ReferenceLists"], "M-16"),
    "M-22": (["SpecialRates"], "M-22"),
    "M-23": (["OtherParameters"], "M-23"),
    "M-24": (["GlMapping"], "M-24"),
    "M-25": (["Circulars"], "M-25"),
    "M-26": (["Labels"], "M-26"),
    "M-27": (["Operations"], "M-27"),
    "I-17": (["LifecycleEvents"], "I-17"),
    "C-01": (["CycleControl"], "C-01"),
    "C-02": (["PayrollCycle"], "C-02"),
    "E-01": (["EsevaRecord"], "E-01"),
}

STOP = {"the", "of", "a", "an", "to", "and", "or", "in", "on", "for", "by", "no", "nr", "eff"}


def messages() -> dict[str, str]:
    out = {}
    for line in open(MESSAGES, encoding="utf-8"):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            out[k.strip()] = v.strip()
    return out


def component_source(name: str) -> str:
    hits = glob.glob(os.path.join(ROOT, "frontend", "src", "modules", "*", "components", name + ".tsx"))
    return open(hits[0], encoding="utf-8").read() if hits else ""


def built(src: str, msg: dict[str, str]) -> dict[str, list]:
    """Column headers (with the field-source badge where the Th sets one), card titles."""
    cols = []
    for m in re.finditer(r"<Th\s+label=\{t\('([\w.\-]+)'(?:,\s*\[[^\]]*\])?\)\}\s*(?:s=\"([A-Z]+)\")?", src):
        cols.append((msg.get(m.group(1), m.group(1)), m.group(2)))
    for m in re.finditer(r"<th(?:\s[^>]*)?>\s*\{t\('([\w.\-]+)'", src):
        cols.append((msg.get(m.group(1), m.group(1)), None))
    cards = [msg.get(k, k) for k in re.findall(r"<Card\s+title=\{t\('([\w.\-]+)'", src)]
    return {"columns": cols, "cards": cards}


def norm(text: str) -> set[str]:
    text = re.sub(r"\{\d+\}|\(.*?\)|₹|%|\?|[^\w\s/]", " ", text.lower()).replace("/", " ")
    text = text.replace("effective", "eff").replace("amount", "amt")
    return {w for w in text.split() if w not in STOP}


def same(a: str, b: str) -> bool:
    x, y = norm(a), norm(b)
    if not x or not y:
        return False
    return len(x & y) / min(len(x), len(y)) >= 0.6


def live(screen: dict) -> dict[str, list]:
    cols = []
    for table in screen.get("columns", []):
        for c in table:
            m = re.match(r"^(.*?)\s*\[([A-Z]+)\]$", c)
            cols.append((m.group(1), m.group(2)) if m else (c, None))
    return {"columns": [c for c in cols if c[0]], "cards": screen.get("cards", [])}


def wire_columns(screen: dict) -> list[tuple[str, str | None]]:
    out = []
    for table in screen.get("columns", []):
        for c in table:
            m = re.match(r"^(.*?)\s*\[([A-Z]+)\]$", c)
            label, badge = (m.group(1), m.group(2)) if m else (c, None)
            if label and label.lower() not in ("actions", ""):
                out.append((label, badge))
    return out


def main() -> int:
    pack = json.load(open(sys.argv[1], encoding="utf-8"))["screens"]
    rendered = json.load(open(sys.argv[2], encoding="utf-8"))["screens"] if len(sys.argv) > 2 else None
    msg = messages()
    w = sys.stdout.write
    w("# Wireframe review — built screens against Wireframes v6.1 (layout reference)\n\n")
    w("Generated by `scripts/wireframe_compare.py` from the v6.1 developer pack. The wireframe is a layout\n")
    w("reference only: SRS / FRS / DDL / KB govern, so each difference is triaged, not copied blindly.\n\n")
    totals = {"match": 0, "badge": 0, "wire_only": 0, "built_only": 0}
    sections = []
    for code, (components, pcode) in SCREENS.items():
        if rendered is not None:
            if code not in rendered or "notOpened" in rendered[code]:
                sections.append(f"## {code}\n\nNot opened in the live app (no demo user with access).\n")
                continue
            ours = live(rendered[code])
        else:
            ours = built("\n".join(component_source(c) for c in components), msg)
        ref = pack.get(pcode, {})
        if not ref or ref.get("missing"):
            sections.append(f"## {code}\n\nNot in the pack.\n")
            continue
        wcols = wire_columns(ref)
        matched, badge_diff, wire_only = [], [], []
        used = set()
        for label, badge in wcols:
            hit = next((i for i, (o, _) in enumerate(ours["columns"]) if i not in used and same(label, o)), None)
            if hit is None:
                wire_only.append(f"{label} [{badge or '-'}]")
                continue
            used.add(hit)
            ob = ours["columns"][hit][1]
            matched.append(label)
            if badge and ob and badge != ob:
                badge_diff.append(f"{label}: wireframe {badge}, built {ob}")
        built_only = [f"{o} [{b or '-'}]" for i, (o, b) in enumerate(ours["columns"]) if i not in used]
        totals["match"] += len(matched)
        totals["badge"] += len(badge_diff)
        totals["wire_only"] += len(wire_only)
        totals["built_only"] += len(built_only)
        s = [f"## {code} — {ref.get('name', '').replace('&amp;', '&')}" + (f" (pack {pcode})" if pcode != code else "")]
        s.append(f"\nColumns in both: {len(matched)}; badge differs: {len(badge_diff)}; wireframe only: {len(wire_only)}; built only: {len(built_only)}.\n")
        if badge_diff:
            s.append("- **Field source differs:** " + "; ".join(badge_diff))
        if wire_only:
            s.append("- **Wireframe only:** " + "; ".join(wire_only))
        if built_only:
            s.append("- Built only: " + "; ".join(built_only))
        wcards = [c for c in ref.get("cards", []) if c]
        missing_cards = [c for c in wcards if not any(same(c, o) for o in ours["cards"])]
        if missing_cards:
            s.append("- Wireframe cards without a matching card title: " + "; ".join(missing_cards[:8]))
        sections.append("\n".join(s) + "\n")
    w(
        f"Summary: {totals['match']} columns in both ({totals['badge']} with a different field-source badge), "
        f"{totals['wire_only']} wireframe-only, {totals['built_only']} built-only, over {len(SCREENS)} screens.\n\n"
    )
    w("\n".join(sections))
    return 0


if __name__ == "__main__":
    sys.exit(main())
