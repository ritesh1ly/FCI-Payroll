#!/usr/bin/env python3
"""Generates, from the pack's RENDERED sidebar (scripts/wireframe_nav_extract.mjs -> a JSON of entries and groups):

  db/migration/common/V078_1__wireframe_navigation_seed.sql   the navigation groups and entries (OD-121)
  db/migration/common/V078_4__wireframe_screen_access.sql     VIEW (and EXPORT where the screen exports) for every
                                                              role the pack lets see a screen (the pack's RBAC strip)
  messages_en / messages_hi                                   menu.section.<group>, nav.<id>, navicon.<id>

Usage: python -I scripts/gen_wireframe_navigation.py <nav_runtime.json>
The Hindi of a label is the Hindi of the screen title where the label is the title (screen.<code>), else the
translation table below; a label without one keeps the English (listed at the end for translation).
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILT_CODE = {"x2": "W-01", "x1": "A-08", "x4": "A-05", "m11": "M-17", "i10": "M-11", "c9": "C-11", "c9sim": "C-09"}
SUFFIX = {"m2d": "&engine=DPS", "m2n": "&engine=NWNP", "m5d": "&engine=DPS", "m5n": "&engine=NWNP", "i1d": "&engine=DPS",
          "i1n": "&engine=NWNP", "i2d": "&engine=DPS", "i2n": "&engine=NWNP", "o3d": "&engine=DPS", "m2": "&engine=DEPT",
          "m5": "&engine=DEPT", "i1": "&engine=DEPT", "i2": "&engine=DEPT", "o3": "&engine=DEPT", "i4b": "&view=LOANS"}
# The pack's GUARD rules that open a drawn action to a role, as (role, built screen, permission action, guard text).
# Extended screen by screen as the five menus are rebuilt (OD-124/OD-125).
GUARD_GRANTS = [
    ("PAYROLL_CHECKER", "X-05", "EDIT", "x5 assign: A-17, assignment by Checker or Administrator"),
    ("SYSTEM_ADMINISTRATOR", "X-05", "EDIT", "x5 assign: A-17, assignment by Checker or Administrator"),
]
# Pack grants never applied (OD-125 exceptions, docs/tracker/WIREFRAME_GAPS.md WF-075): the pack shows the role the
# screen, but the built screen's API is the staff register of every member, so the grant would break the role's data
# scope (an eSeva member sees the own record only, R-PII). The role's own-record function is served by its eSeva screen.
SKIP_GRANTS = {
    ("EMPLOYEE_ESEVA", "I-18"): "I-18 lists every member's savings; the member declares on E-01 (own record)",
}
ROLE = {"maker": "PAYROLL_MAKER", "checker": "PAYROLL_CHECKER", "ldh": "LABOUR_DEALING_HAND", "hr": "ESTABLISHMENT_HR",
        "fin": "FINANCE", "admin": "SYSTEM_ADMINISTRATOR", "emp": "EMPLOYEE_ESEVA"}
GROUP_HI = {"home": "होम", "general_cross_cutting": "सामान्य — क्रॉस-कटिंग", "employee": "कर्मचारी", "departmental_labour": "विभागीय श्रमिक",
            "dps_labour": "डीपीएस श्रमिक", "nwnp_labour": "एनडब्ल्यूएनपी श्रमिक", "cpf_pf_vpf": "सीपीएफ / पीएफ एवं वीपीएफ",
            "income_tax_tds_pt": "आयकर, टीडीएस एवं पीटी", "nps_deduction": "एनपीएस कटौती", "loans_advances": "ऋण एवं अग्रिम",
            "payroll_cycle_control": "वेतन चक्र नियंत्रण", "full_final_and_ex_gratia": "पूर्ण एवं अंतिम तथा अनुग्रह राशि",
            "increment_pay_fixation": "वेतनवृद्धि एवं वेतन निर्धारण", "penalty_recovery_o_a": "दंड, वसूली एवं ओ एंड ए",
            "allowances_reimbursements": "भत्ते एवं प्रतिपूर्ति", "employee_self_service_eseva": "कर्मचारी स्व-सेवा (ई-सेवा)",
            "mis_statutory_reporting": "एमआईएस एवं सांविधिक रिपोर्टिंग", "dynamic_rule_parameter_engine": "गतिशील नियम एवं मापदंड इंजन",
            "change_history_audit_trail": "परिवर्तन इतिहास एवं लेखा-परीक्षा अनुवर्तन", "leave_hrms_interface_pay_effects": "अवकाश — HRMS इंटरफ़ेस एवं वेतन प्रभाव"}
LABEL_HI = {"Labour Master ▸ DPS": "श्रमिक मास्टर ▸ डीपीएस", "Labour Master ▸ NWNP": "श्रमिक मास्टर ▸ एनडब्ल्यूएनपी",
            "Labour Rate Tables ▸ DPS": "श्रमिक दर तालिकाएँ ▸ डीपीएस", "Rate Tables ▸ ASOR / SOR": "दर तालिकाएँ ▸ एएसओआर / एसओआर",
            "Labour Attendance Capture": "श्रमिक उपस्थिति प्रविष्टि", "Work-Slip Capture": "वर्क-स्लिप प्रविष्टि",
            "Labour Registers & Reports": "श्रमिक रजिस्टर एवं रिपोर्टें", "Loans, Advances & CPF Sanctions": "ऋण, अग्रिम एवं सीपीएफ स्वीकृतियाँ",
            "Sign-in, OTP & Password": "साइन-इन, OTP एवं पासवर्ड", "Departmental Labour Computation": "विभागीय श्रमिक गणना", "DPS Labour Computation": "डीपीएस श्रमिक गणना", "NWNP Labour Computation": "एनडब्ल्यूएनपी श्रमिक गणना", "Labour Incentive Computation": "श्रमिक प्रोत्साहन गणना", "Payroll Computation — Exceptions Review": "वेतन गणना — अपवाद समीक्षा", "CPF Supplementary Payment": "सीपीएफ अनुपूरक भुगतान", "CPF Parent-Zone Change / Zone Surrender (from HRMS)": "सीपीएफ मूल-अंचल परिवर्तन / अंचल समर्पण (HRMS से)", "Payslip": "वेतन पर्ची", "Monthly & Year-End Remittance": "मासिक एवं वर्षांत प्रेषण", "Invoice Outputs": "बीजक आउटपुट", "CPF & e-Seva Reports": "सीपीएफ एवं ई-सेवा रिपोर्टें", "CPF Reconciliation & OBCB Reports": "सीपीएफ समाधान एवं ओबीसीबी रिपोर्टें"}


def code_of(gtitle: str) -> str:
    return "home" if gtitle == "Home" else re.sub(r"[^a-z0-9]+", "_", gtitle.lower()).strip("_")


def main() -> None:
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf8"))
    msg_en = dict(re.findall(r"^screen\.([A-Z]-\d+)=(.*)$", (ROOT / "backend/src/main/resources/i18n/messages_en.properties").read_text(encoding="utf8"), re.M))
    msg_hi = dict(re.findall(r"^screen\.([A-Z]-\d+)=(.*)$", (ROOT / "backend/src/main/resources/i18n/messages_hi.properties").read_text(encoding="utf8"), re.M))
    groups = sorted(((sec, g["title"], g["order"]) for sec, g in data["groups"].items()), key=lambda x: x[2])
    entries = data["entries"]

    def q(s):
        return "'" + s.replace("'", "''") + "'"

    # 1. the navigation seed
    lines = ["""-- V078_1: the workspace navigation exactly as Wireframes v6.1 RENDERS it (OD-121, owner 2026-11-06: the wireframe is a
-- frozen requirement, "compare menu by menu"; SRS-G-54 screen designs). Generated by scripts/gen_wireframe_navigation.py from
-- scripts/wireframe_nav_extract.mjs (the pack rewrites labels and adds entries at run time, so the rendered sidebar, not the
-- NAVGROUPS data, is the source). Vendor-neutral. Rollback: db/rollback/<vendor>/U078_1.
--
-- app_menu_group: the groups in order ('home' is the header-less first entry). app_menu_item: the entries of each group; the
-- id is the pack's screen id, so a screen drawn under several groups (M-02 / M-05 / I-01 / I-02 per labour engine, O-03,
-- the loans view of I-04) has one entry per group, each with its own label (nav.<id>) and view (route_suffix, NULL when
-- none: an empty string is NULL on Oracle). An entry of a screen not yet in app_screen is skipped by the join and inserted
-- with the screen (C-03 ... O-11, X-08: see docs/tracker/WIREFRAME_PARITY.md). SRS v0.5 "Screen Designs" orders the menu
-- by the payroll cycle (V033_1): the wireframe, the newer and frozen source, groups by module (WF-069).
"""]
    for n, (sec, title, _) in enumerate(groups, 1):
        lines.append(f"INSERT INTO app_menu_group (group_code, group_order, section_ref) VALUES ({q(code_of(title))}, {n}, {q(sec) if sec else 'NULL'});")
    lines.append("")
    by_group = {}
    for sid, e in entries.items():
        by_group.setdefault(e["section"], []).append((e["order"], sid, e))
    for sec, title, _ in groups:
        for n, (_, sid, e) in enumerate(sorted(by_group.get(sec, [])), 1):
            code = BUILT_CODE.get(sid, e["code"])
            suffix = SUFFIX.get(sid)
            lines.append(
                f"INSERT INTO app_menu_item (menu_item_id, group_code, item_order, screen_code, route_suffix)\n"
                f"SELECT {q(sid)}, {q(code_of(title))}, {n}, s.screen_code, {q(suffix) if suffix else 'NULL'} FROM app_screen s WHERE s.screen_code = {q(code)};")
    lines += ["", "-- The menu is cached per instance (region MENU): every instance reloads it.",
              "UPDATE cache_region SET version_no = version_no + 1, changed_at = CURRENT_TIMESTAMP, changed_by = 'V078_1'", " WHERE region_code = 'MENU';", ""]
    (ROOT / "db/migration/common/V078_1__wireframe_navigation_seed.sql").write_text("\n".join(lines), encoding="utf8", newline="\n")

    # 2. the role access of the pack's RBAC strip: VIEW only. The pack states who VIEWS a screen (rbac.v, the rendered
    # sidebar); it states no export right (an unguarded Export button is drawn for every role), so EXPORT stays as the
    # SRS role tables built it (SRS-G-13) and a drawn Export the role may not use says why when pressed.
    grants = []
    for sid, e in entries.items():
        code = BUILT_CODE.get(sid, e["code"])
        for r in e["roles"]:
            # The eSeva screens belong to the eSeva user category (SRS-G-35: a separate login of the member
            # for the own record): this generated file grants no staff role the E- screens. OD-135: the staff VIEW of
            # E-01 (support preview, SRS l.496, l.5499) comes from V081_50, with E-01 SELF for the member (WF-075).
            if code.startswith("E-") and ROLE[r] != "EMPLOYEE_ESEVA":
                continue
            if (ROLE[r], code) not in SKIP_GRANTS:
                grants.append((ROLE[r], code))
    grants = sorted(set(grants))
    sql = ["""-- V078_4: screen access as Wireframes v6.1 draws it (OD-121): every role the pack's RBAC strip lets VIEW a screen holds
-- its VIEW permission (OD-125). No EXPORT: the pack states no export right (SRS-G-13 role tables keep it). The
-- eSeva member gets no staff register (SKIP_GRANTS, WF-075).
-- Generated by scripts/gen_wireframe_navigation.py from the rendered sidebar; a screen not in app_screen or a permission
-- not defined is skipped by the joins (it comes with the screen). Grants only - nothing is revoked here: a role the pack
-- does NOT list but the build grants is WF-071 in docs/tracker/WIREFRAME_GAPS.md for FCI. Vendor-neutral.
-- Rollback: db/rollback/<vendor>/U078_4.
-- One statement per role and screen: a single UNION ALL of every grant (700+ branches) exhausts H2's planner heap.
"""]
    for role, code in grants:
        sql.append("INSERT INTO app_role_permission (role_id, permission_id, effective_from)")
        sql.append(f"SELECT r.role_id, p.permission_id, DATE '2026-11-06' FROM app_role r JOIN app_permission p")
        sql.append(f"    ON p.screen_code = {q(code)} AND p.action_code = 'VIEW'")
        sql.append(f" WHERE r.role_code = {q(role)}")
        sql.append("   AND NOT EXISTS (SELECT 1 FROM app_role_permission x WHERE x.role_id = r.role_id AND x.permission_id = p.permission_id);")
    sql.append("")
    (ROOT / "db/migration/common/V078_4__wireframe_screen_access.sql").write_text("\n".join(sql), encoding="utf8", newline="\n")
    for v in ("h2", "oracle"):
        (ROOT / f"db/rollback/{v}/U078_4__wireframe_screen_access.sql").write_text(
            "-- Rollback of V078_4: the grants it added. They alone carry effective_from 2026-11-06 (every earlier grant is\n"
            "-- dated 2026-04-01 or 1900-01-01), so the date identifies the rows without a column of their own.\n"
            "DELETE FROM app_role_permission WHERE effective_from = DATE '2026-11-06'\n"
            "   AND permission_id IN (SELECT permission_id FROM app_permission WHERE action_code IN ('VIEW', 'EXPORT'));\n",
            encoding="utf8", newline="\n")

    # 2b. OD-125 (owner 2026-11-06, "do as per wireframe"): a wireframe role the pack does NOT let see a screen loses the
    # screen - every permission of it is end-dated (R-DEL: a new effective date, never a delete), and the actions the pack's
    # guards open to a role (GUARD_GRANTS) are granted. Only the seven wireframe roles and only screens the pack draws; the
    # other roles (Auditor, Super User, CPF Division ...) are not drawn and keep their access.
    seen = {}
    for sid, e in entries.items():
        seen.setdefault(BUILT_CODE.get(sid, e["code"]), set()).update(ROLE[r] for r in e["roles"])
    revoke = sorted(
        {(role, code) for code, roles in seen.items() for role in ROLE.values() if role not in roles}
        | set(SKIP_GRANTS)
    )
    out = ["""-- V078_5: screen access exactly as Wireframes v6.1 draws it (OD-121, OD-125: "do as per wireframe"). Generated by
-- scripts/gen_wireframe_navigation.py. (1) A wireframe role whose rendered sidebar does not carry a screen loses it: its
-- VIEW and EXPORT of that screen are end-dated the day before 2026-11-06, unless the role holds a functional right on it
-- (CREATE, SUBMIT, APPROVE ... from the SRS role tables: the wireframe decides read-only visibility, never an SRS
-- function); action rights stay with their approval routes (R-DEL - a grant is never deleted; AppRolePermission
-- .isActiveOn ignores it from then on). Only the seven wireframe roles and only the screens the pack draws: roles the pack
-- does not draw keep their access. (2) The actions the pack's guards open to a role (GUARD) are granted. Vendor-neutral.
-- Rollback: db/rollback/<vendor>/U078_5.
"""]
    for role, code in revoke:
        out.append(f"UPDATE app_role_permission SET effective_to = DATE '2026-11-05'\n"
                   f" WHERE effective_to IS NULL\n"
                   f"   AND role_id = (SELECT role_id FROM app_role WHERE role_code = {q(role)})\n"
                   f"   AND permission_id IN (SELECT permission_id FROM app_permission WHERE screen_code = {q(code)}"
                   f" AND action_code IN ('VIEW', 'EXPORT'))\n"
                   # A role with a functional right on the screen (CREATE, SUBMIT, APPROVE ...: the SRS role tables,
                   # e.g. the Labour Dealing Hand enters labour claims on I-03, SC-10-0023) keeps seeing it: the
                   # wireframe decides read-only visibility, never an SRS function (OD-125, WIREFRAME_GAPS WF-071).
                   f"   AND NOT EXISTS (SELECT 1 FROM app_role_permission y JOIN app_permission a ON a.permission_id = y.permission_id\n"
                   f"                    WHERE y.role_id = (SELECT role_id FROM app_role WHERE role_code = {q(role)})\n"
                   f"                      AND a.screen_code = {q(code)} AND a.action_code NOT IN ('VIEW', 'EXPORT')\n"
                   f"                      AND y.effective_to IS NULL);")
    out.append("")
    out.append("-- The guards of the pack: the role the drawn action is open to holds its permission.")
    for role, code, action, why in GUARD_GRANTS:
        out.append(f"-- {code} {action} for {role}: {why}")
        out.append("INSERT INTO app_role_permission (role_id, permission_id, effective_from)")
        out.append("SELECT r.role_id, p.permission_id, DATE '2026-11-06' FROM app_role r JOIN app_permission p")
        out.append(f"    ON p.screen_code = {q(code)} AND p.action_code = {q(action)}")
        out.append(f" WHERE r.role_code = {q(role)}")
        out.append("   AND NOT EXISTS (SELECT 1 FROM app_role_permission x WHERE x.role_id = r.role_id AND x.permission_id = p.permission_id);")
        out.append(f"UPDATE app_role_permission SET effective_to = NULL, effective_from = DATE '2026-11-06'\n"
                   f" WHERE effective_to IS NOT NULL\n"
                   f"   AND role_id = (SELECT role_id FROM app_role WHERE role_code = {q(role)})\n"
                   f"   AND permission_id = (SELECT permission_id FROM app_permission WHERE screen_code = {q(code)} AND action_code = {q(action)});")
    out += ["", "-- Every instance reloads the sessions' permissions and the menu.",
            "UPDATE cache_region SET version_no = version_no + 1, changed_at = CURRENT_TIMESTAMP, changed_by = 'V078_5'",
            " WHERE region_code IN ('ACCESS', 'MENU');", ""]
    (ROOT / "db/migration/common/V078_5__wireframe_access_alignment.sql").write_text("\n".join(out), encoding="utf8", newline="\n")
    for v in ("h2", "oracle"):
        rb = ["-- Rollback of V078_5: the end-dated grants come back (only V078_5 end-dates on 2026-11-05) and the guard grants go.",
              "UPDATE app_role_permission SET effective_to = NULL WHERE effective_to = DATE '2026-11-05';"]
        for role, code, action, _ in GUARD_GRANTS:
            rb.append("DELETE FROM app_role_permission WHERE effective_from = DATE '2026-11-06'"
                      f" AND role_id = (SELECT role_id FROM app_role WHERE role_code = {q(role)})"
                      f" AND permission_id = (SELECT permission_id FROM app_permission WHERE screen_code = {q(code)} AND action_code = {q(action)});")
        rb.append("UPDATE cache_region SET version_no = version_no + 1, changed_at = CURRENT_TIMESTAMP, changed_by = 'U078_5' WHERE region_code IN ('ACCESS', 'MENU');")
        (ROOT / f"db/rollback/{v}/U078_5__wireframe_access_alignment.sql").write_text("\n".join(rb) + "\n", encoding="utf8", newline="\n")

    # 3. the labels (en = as rendered; hi = the screen's Hindi title when the label is the title, else the table)
    untranslated = []
    for lang in ("en", "hi"):
        p = ROOT / f"backend/src/main/resources/i18n/messages_{lang}.properties"
        t = p.read_text(encoding="utf8").replace("\r\n", "\n")
        # Replace only this generator's own lines - its comment markers and the menu.section / nav.<id> /
        # navicon.<id> keys (an entry id carries a digit: nav.x8, nav.m2d; the workspace's own nav.expandAll
        # etc. do not) - never anything else: other blocks follow in the same file.
        # menu.section keys only of the groups generated here: the module sections of MenuCatalog's fallback
        # (menu.section.calculation, .masters_setup ... of V033_1) belong to the screens and stay.
        sections = "|".join(re.escape(code_of(title)) for _, title, _ in groups)
        own = re.compile(rf"^(menu\.section\.(?:{sections})|nav\.[a-z]+\d[a-z0-9]*|navicon\.[a-z0-9]+|navcode\.[a-z0-9]+)=")
        markers = ("# Navigation of Wireframes v6.1", "# Navigation icons of Wireframes v6.1", "# (nav.<wireframe id>)",
                   "# glyph (navicon.<id>)", "# (navicon.<wireframe id>)", "# The code the wireframe prints")
        t = "\n".join(line for line in t.split("\n") if not own.match(line) and not line.startswith(markers))
        block = ["", "# Navigation of Wireframes v6.1 (V078, OD-121): the group names (menu.section.<group>), the label (nav.<wireframe id>) and the",
                 "# glyph (navicon.<id>) of every sidebar entry, exactly as the pack renders them; generated by scripts/gen_wireframe_navigation.py."]
        for sec, title, _ in groups:
            block.append(f"menu.section.{code_of(title)}=" + (title if lang == "en" else GROUP_HI.get(code_of(title), title)))
        for sec, title, _ in groups:
            for _, sid, e in sorted(by_group.get(sec, [])):
                code = BUILT_CODE.get(sid, e["code"])
                label = e["label"]
                if lang == "en":
                    hi = label
                elif label == msg_en.get(code):
                    hi = msg_hi.get(code, label)
                elif label in LABEL_HI:
                    hi = LABEL_HI[label]
                else:
                    hi = label
                    untranslated.append((sid, label))
                block.append(f"nav.{sid}=" + hi)
        for sec, title, _ in groups:
            for _, sid, e in sorted(by_group.get(sec, [])):
                block.append(f"navicon.{sid}=" + e["icon"])
        block.append("# The code the wireframe prints on an entry whose function is built under another code (navcode.<id>, OD-125).")
        for sec, title, _ in groups:
            for _, sid, e in sorted(by_group.get(sec, [])):
                if BUILT_CODE.get(sid, e["code"]) != e["code"]:
                    block.append(f"navcode.{sid}=" + e["code"])
        t = t.rstrip("\n") + "\n" + "\n".join(block) + "\n"
        p.write_text(t, encoding="utf8", newline="\n")
    print(f"{len(groups)} groups, {len(entries)} entries, {len(grants)} role-screen grants")
    if untranslated:
        print("labels kept in English for Hindi (translate):", sorted(set(untranslated)))


if __name__ == "__main__":
    main()
