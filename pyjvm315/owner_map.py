"""Tag each unsupported-probe reason with the file/pass and roadmap ticket that owns it.

Rules are ordered; first match wins. The lead uses ``--owner-map`` to assign tickets.
"""
from __future__ import annotations

import re

C = "pyjvm315/compiler.py"
R = "runtime/pyjvm315/runtime/PyRuntime.java"

# (regex on the normalized reason, owner file/pass, ticket)
RULES: list[tuple[str, str, str]] = [
    (r"Unsupported statement: Match\b", f"{C} (lowering: match)", "P1-01..P1-03"),
    (r"\bmatch\b", f"{C} (lowering: match)", "P1-01..P1-03"),
    (r"\b(exec|eval|compile)\b", "self-hosted compiler + hidden classes", "P1-07/P1-08"),
    (r"annotation-only assignments|annotat", f"{C} (PEP 649/749 __annotate__)", "P1-09"),
    (r"frozendict", f"{R} (dict table)", "P2-02"),
    (r"\bopen\b", "runtime _io module", "P4-xx (_io)"),
    (r"contextmanager|import of module .* is not available|import from module|package .* is not available|star import",
     "import system / stdlib jar", "P3-01..P3-04"),
    (r"relative import", "import system", "P3-01"),
    (r"yield inside|suspension inside|return/break/continue through|async comprehensions",
     f"{C} (generator/coroutine lowering)", "P1-10"),
    (r"except types|exception constructors|No active exception", f"{C} + {R} (exceptions)", "P2-09"),
    (r"starred class bases|\*\* class keyword", f"{C} (class creation)", "P2-04"),
    (r"unsupported f-string|print\(\)|range\(\)", f"{R} (builtins)", "P2-07"),
    (r"Unsupported (expression|statement|binary|comparison|assignment|deletion)", f"{C} (lowering)", "triage"),
]
UNOWNED = ("<unowned>", "triage")


def owner_for(reason: str) -> tuple[str, str]:
    for pattern, owner, ticket in RULES:
        if re.search(pattern, reason):
            return owner, ticket
    return UNOWNED


def owner_map(report: dict) -> list[dict]:
    """Group unsupported_reasons from a probe report by owner."""
    groups: dict[tuple[str, str], dict] = {}
    for item in report.get("unsupported_reasons", []):
        owner, ticket = owner_for(item["reason"])
        g = groups.setdefault((owner, ticket), {"owner": owner, "ticket": ticket, "count": 0, "reasons": []})
        g["count"] += item["count"]
        g["reasons"].append(item)
    return sorted(groups.values(), key=lambda g: -g["count"])


def format_owner_map(groups: list[dict], top: int = 5) -> str:
    lines = ["Unsupported bodies by owner:"]
    for g in groups:
        lines.append(f"  {g['count']:5}  {g['ticket']:14} {g['owner']}")
        for r in g["reasons"][:top]:
            lines.append(f"           {r['count']:5}  {r['reason']}")
    return "\n".join(lines)
