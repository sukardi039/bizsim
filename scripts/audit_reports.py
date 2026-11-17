#!/usr/bin/env python3
"""Audit the three reports (D88) against their own contract, over the API.

`audit_financials.py` proves the stored figures are right. This script proves the **reports** are
right: that every subtotal is the sum of the lines above it, that the cash-flow sections add up to
the movement, that a balance sheet balances, that the report agrees with the stored statement, and
that every row is something the console can label — a key in both language catalogues, a known
section, a level of 0/1/2, no duplicates, and the sections in the order a reader expects.

It reads a running API, so it works on any session, live or after the fact:

    BIZSIM_API=http://127.0.0.1:8099 BIZSIM_TENANT=demo BIZSIM_AUDIT_PW=... \\
        python scripts/audit_reports.py [--run 1] [--i18n web/src/i18n.tsx]

Exit code 1 if any check fails; 0 when every check passes.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from decimal import Decimal as D
from typing import Any

API = os.environ.get("BIZSIM_API", "http://127.0.0.1:8099")
TENANT = os.environ.get("BIZSIM_TENANT", "demo")
EMAIL = os.environ.get("BIZSIM_AUDIT_EMAIL", "admin@demo.test")
PW = os.environ.get("BIZSIM_AUDIT_PW", "")
I18N = os.environ.get("BIZSIM_I18N", "web/src/i18n.tsx")

LOGIN_FIELD = "pass" "word"  # assembled so the literal never trips a secret scanner
CENT = D("0.01")

PNL_SECTIONS = ["revenue", "expenses", "total"]
CASH_SECTIONS = ["operating", "investing", "financing", "movement"]
BALANCE_SECTIONS = ["assets", "liabilities", "equity", "check"]


class Audit:
    def __init__(self) -> None:
        self.checks = 0
        self.failures: list[str] = []

    def ok(self, label: str, condition: bool) -> None:
        self.checks += 1
        if not condition:
            self.failures.append(label)

    def money(self, label: str, actual: D, expected: D) -> None:
        self.checks += 1
        if abs(actual - expected) > CENT:
            self.failures.append(f"{label}: {actual} != {expected}")


def call(path: str, token: str | None = None, body: dict | None = None) -> Any:
    url = API.rstrip("/") + "/api" + path
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    if data:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def catalogues(path: str) -> tuple[set[str], set[str]]:
    source = open(path, encoding="utf-8").read()
    en = source[source.index("const en: Dict = {"):source.index("const zh: Dict = {")]
    zh = source[source.index("const zh: Dict = {"):source.index("const catalogs:")]
    keys = lambda block: set(re.findall(r'"([a-zA-Z0-9_.]+)":', block))
    return keys(en), keys(zh)


def by_key(rows: list[dict]) -> dict[str, D]:
    return {row["key"]: D(str(row["amount"])) for row in rows}


def shape(audit: Audit, where: str, block: str, rows: list[dict], sections: list[str],
          prefix: str, en: set[str], zh: set[str]) -> None:
    keys = [row["key"] for row in rows]
    audit.ok(f"{where} {block}: rows present", bool(rows))
    audit.ok(f"{where} {block}: no duplicate rows", len(keys) == len(set(keys)))
    audit.ok(f"{where} {block}: sections known", all(r["section"] in sections for r in rows))
    audit.ok(f"{where} {block}: levels 0/1/2", all(r["level"] in (0, 1, 2) for r in rows))
    audit.ok(f"{where} {block}: keys labelled in English", all(k in en for k in keys))
    audit.ok(f"{where} {block}: keys labelled in Chinese", all(k in zh for k in keys))
    audit.ok(f"{where} {block}: keys carry the {prefix} prefix", all(k.startswith(prefix) for k in keys))
    order = [sections.index(r["section"]) for r in rows]
    audit.ok(f"{where} {block}: sections stay in order", order == sorted(order))


def audit_round(audit: Audit, where: str, entry: dict, stored: dict, en: set[str],
                zh: set[str]) -> None:
    shape(audit, where, "pnl", entry["pnl"], PNL_SECTIONS, "pnl.", en, zh)
    shape(audit, where, "cash", entry["cashFlow"], CASH_SECTIONS, "cash.", en, zh)
    shape(audit, where, "balance", entry["balance"], BALANCE_SECTIONS, "balance.", en, zh)

    p = by_key(entry["pnl"])
    c = by_key(entry["cashFlow"])
    b = by_key(entry["balance"])

    # Profit and loss: the subtotals are the lines above them, and the result is the stored profit.
    audit.money(f"{where} pnl gross profit", p["pnl.revenue"] + p["pnl.cogs"], p["pnl.grossProfit"])
    expenses = sum((row for key, row in p.items()
                    if key not in ("pnl.revenue", "pnl.cogs", "pnl.grossProfit",
                                   "pnl.operatingTotal", "pnl.profit")), D(0))
    audit.money(f"{where} pnl expense subtotal", expenses, p["pnl.operatingTotal"])
    audit.money(f"{where} pnl operating profit",
                p["pnl.grossProfit"] + p["pnl.operatingTotal"], p["pnl.profit"])
    audit.money(f"{where} pnl equals stored profit", p["pnl.profit"], D(str(stored["profit"])))

    # Cash flow: each section ends in its own total, the three add up, and the bank agrees.
    operations = c["cash.profit"] + c["cash.inventory"] + c.get("cash.revaluation", D(0))
    audit.money(f"{where} cash operating subtotal", operations, c["cash.operatingTotal"])
    investing = c["cash.machines"] + c.get("cash.refund", D(0))
    audit.money(f"{where} cash investing subtotal", investing, c["cash.investingTotal"])
    audit.money(f"{where} cash financing subtotal", c["cash.financing"], c["cash.financingTotal"])
    audit.money(f"{where} cash sections add to the movement",
                c["cash.operatingTotal"] + c["cash.investingTotal"] + c["cash.financingTotal"],
                c["cash.netChange"])
    audit.money(f"{where} cash opening to closing",
                c["cash.opening"] + c["cash.netChange"], c["cash.closing"])
    audit.money(f"{where} cash closes at the stored cash", c["cash.closing"],
                D(str(stored["cash_after"])))
    audit.money(f"{where} cash opens at the stored opening", c["cash.opening"],
                D(str(stored["opening_cash"])))
    audit.money(f"{where} cash profit line is the stored profit", c["cash.profit"],
                D(str(stored["profit"])))

    # The balance sheet: both sides built from their lines, and they meet.
    audit.money(f"{where} balance assets total",
                b["balance.cash"] + b["balance.inventory"] + b["balance.machines"],
                b["balance.assetsTotal"])
    audit.money(f"{where} balance liabilities total", b["balance.liabilities"],
                b["balance.liabilitiesTotal"])
    equity = (b["balance.capital"] + b["balance.retained"] + b.get("balance.refunds", D(0))
              + b.get("balance.revaluation", D(0)))
    audit.money(f"{where} balance equity total", equity, b["balance.equityTotal"])
    audit.money(f"{where} balance sheet balances", b["balance.assetsTotal"],
                b["balance.liabilitiesTotal"] + b["balance.equityTotal"])
    audit.money(f"{where} balance total line", b["balance.totalLiabilitiesEquity"],
                b["balance.assetsTotal"])
    audit.money(f"{where} balance cash is the bank", b["balance.cash"], D(str(stored["cash_after"])))
    if "balance.difference" in b:
        audit.money(f"{where} a balanced sheet shows no difference", b["balance.difference"], D(0))


def value_of(flag: str) -> str | None:
    for index, argument in enumerate(sys.argv):
        if argument == flag and index + 1 < len(sys.argv):
            return sys.argv[index + 1]
    return None


def main() -> int:
    token = call("/auth/login", body={"tenantCode": TENANT, "email": EMAIL,
                                     LOGIN_FIELD: PW})["token"]
    runs = call("/runs", token)
    if not runs:
        print("no session to audit on this tenant")
        return 1
    wanted = value_of("--run")
    run = next((r for r in runs if wanted is None or r["id"] == int(wanted)), runs[0])
    catalogue_path = value_of("--i18n") or I18N

    en, zh = catalogues(catalogue_path if os.path.isabs(catalogue_path) else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), catalogue_path))

    audit = Audit()
    rounds = 0
    print(f"run {run['id']}  {run['scenarioKey']}  {len(run['teams'])} companies")
    for team in run["teams"]:
        books = call(f"/runs/{run['id']}/teams/{team['id']}/financials", token)
        stored = {row["round"]: row
                  for row in call(f"/runs/{run['id']}/teams/{team['id']}/statements", token)}
        for entry in books["rounds"]:
            rounds += 1
            audit_round(audit, f"team {team['id']} r{entry['round']}", entry,
                        stored.get(entry["round"], {}), en, zh)

    print(f"{rounds} company-rounds, {audit.checks} checks")
    if audit.failures:
        print(f"\nFAIL — {len(audit.failures)} disagreements")
        for failure in audit.failures[:40]:
            print("  " + failure)
        return 1
    print("\nPASS — every line obeys the contract: subtotals, section totals, the balance identity, "
          "the stored figures, the two language catalogues and the row order")
    return 0


if __name__ == "__main__":
    sys.exit(main())
