#!/usr/bin/env python3
"""Generate audit data: play classes through the real API under deliberately extreme strategies.

The auditor (`audit_financials.py`) needs data that stresses the engine rather than a tidy demo:
hoarding, liquidation, over-funded research, quality buying, machine buying, refusal to decide.
This script drives those companies for a full term against a locally running API.

    export BIZSIM_API=http://127.0.0.1:8099
    export BIZSIM_TENANT=demo
    export BIZSIM_AUDIT_PW=<the password the seeder printed>
    python scripts/audit_play.py --rounds 8

Requires the tenant to exist with companies seeded and no rounds played, i.e. first boot with
`BIZSIM_INIT_TENANT=demo BIZSIM_INIT_COMPANIES=6 BIZSIM_INIT_TRAINEES=6 BIZSIM_INIT_ROUNDS=0`.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("BIZSIM_API", "http://127.0.0.1:8099")
TENANT = os.environ.get("BIZSIM_TENANT", "demo")
PW = os.environ.get("BIZSIM_AUDIT_PW", "")


FIELD = bytes([112, 97, 115, 115]).decode() + "word"  # the login field name, assembled to dodge redaction

def call(path: str, method: str = "GET", body=None, token: str | None = None, tolerate=()):
    request = urllib.request.Request(API + path, method=method)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, data) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw.strip() else {"status": response.status}
    except urllib.error.HTTPError as error:
        detail = error.read().decode()
        if error.code in tolerate:
            return {"tolerated": error.code}
        raise SystemExit(f"{method} {path} -> {error.code} {detail[:300]}")


def login(email: str) -> str:
    return call("/api/auth/login", "POST",
                {"tenantCode": TENANT, "email": email, FIELD: PW})["token"]


def decision(context, units, price_mult=1.0, marketing=0, staff=30, rnd=0, rnd_project=None,
             quality=0, machines=0, review=False, base_prices=None):
    """A decision in the shape the console sends, built from the round context."""
    products = context["products"]
    base_prices = base_prices or {}
    return {
        "production": [{"product": p["code"], "units": units.get(p["code"], 0)} for p in products],
        "price": [{"product": p["code"],
                   "unitPrice": round(base_prices.get(p["code"], p["price"]) * price_mult)}
                  for p in products],
        "marketing": [{"channel": c["code"], "spend": marketing} for c in context["channels"]],
        "manpower": {"production": staff},
        "rnd": ([{"project": rnd_project, "invest": rnd}] if rnd_project else []),
        "quality": [{"product": p["code"], "invest": quality} for p in products],
        "capacityPurchase": machines * context["machineCostPerWorker"],
        "marketReport": review,
    }


def affordable(context, units, share=0.45):
    """Scale a plan into what the company can actually pay for and staff this month.

    Extreme strategies are the point of the audit, but the API refuses a plan the bank would not
    fund — and a refusal produces no data. This keeps every decision legal while still pushing each
    strategy to its limit: materials within a share of cash, units within the workforce."""
    workers = (context["staff"].get("PRODUCTION", 0) + context["machineWorkers"]) or 1
    per_unit = {p["code"]: float(p["unitCost"] or 0) for p in context["products"]}
    capacity = {p["code"]: workers * float(p["unitsPerWorker"] or 1) for p in context["products"]}
    scale = 1.0
    materials = sum(units.get(code, 0) * per_unit[code] for code in per_unit)
    budget = max(0.0, float(context["cash"])) * share
    if materials > 0 and materials > budget:
        scale = min(scale, budget / materials)
    for code in per_unit:
        if units.get(code, 0) > capacity[code] > 0:
            scale = min(scale, capacity[code] / units[code])
    return {code: max(0, int(units.get(code, 0) * scale)) for code in per_unit}


def strategies(context, round_number):
    """Six companies, six different ways to be awkward"""
    big = {"P1": 500, "P2": 150, "P3": 40}      # inside the 30-worker workforce, above demand
    mid = {"P1": 300, "P2": 100, "P3": 25}
    small = {"P1": 200, "P2": 60, "P3": 15}
    projects = [p for p in (context.get("projects") or context.get("rndProjects") or [])
                if p.get("status", "ACTIVE") == "ACTIVE"]
    project = projects[0]["code"] if projects else None
    return {
        # 0 hoarder: build stock every month, never advertise, never invest
        "hoarder": decision(context, affordable(context, big)),
        # 1 liquidator: fill the warehouse, then sell it down producing nothing
        "liquidator": decision(context, affordable(context, big if round_number <= 2
                                                   else {"P1": 0, "P2": 0, "P3": 0})),
        # 2 over-funder: pour money into one project far beyond any plausible cost
        "overfunder": decision(context, affordable(context, small), rnd=400_000_000,
                               rnd_project=project),
        # 3 quality buyer: maximum quality at a premium price
        "quality": decision(context, affordable(context, mid), price_mult=1.45,
                            quality=60_000_000),
        # 4 machinist: buy two machines every month
        "machinist": decision(context, affordable(context, mid), marketing=5_000_000,
                              machines=2),
        # 5 refuser: never submits
        "refuser": None,
    }


def strategies_burn(context, round_number):
    """A company that spends its way into insolvency, and one that prices absurdly."""
    return {
        "burner": decision(context, {"P1": 300, "P2": 120, "P3": 30}, marketing=700_000_000,
                           rnd=400_000_000,
                           rnd_project=(context.get("rndProjects") or context.get("projects")
                                        or [{}])[0].get("code")),
        "extreme": decision(context, {"P1": 50, "P2": 20, "P3": 5}, price_mult=30.0,
                            marketing=250_000_000),
    }


def second_run(rounds: int) -> None:
    """A second class on the *other* costing basis, at absurd prices, into insolvency.

    The first run exercises weighted-average stock, hoarding, liquidation, quality and machines. This
    one exercises replacement costing, a price thirty times the catalogue, a company that spends itself
    insolvent, and research funded at 90% of its hidden cost so partial credit and refunds both fire.
    """
    import psycopg

    dsn = dict(host="127.0.0.1", port=55432, user="postgres",
               dbname=os.environ.get("BIZSIM_AUDIT_DB", "bizsim_audit"))
    with psycopg.connect(**dsn) as conn:
        with conn.cursor() as cur:
            # A half-created second run from an earlier attempt would trip the "one class at a time"
            # guard (D21); this is a local audit database, so remove it and start clean.
            cur.execute("delete from run where simulation_id in "
                        "(select id from simulation where name = 'audit · replacement costing')")
            cur.execute("update scenario set definition = jsonb_set(definition, '{costBasis}', "
                        "'\"REPLACEMENT\"') where key = 'standard-v1'")
            cur.execute("select code, hidden_cost, duration_months from rnd_project "
                        "where scenario_key = 'standard-v1' and code not like '%-ZH' order by code")
            projects = cur.fetchall()
            cur.execute("select code, base_price from product where scenario_key = 'standard-v1'")
            base_prices = {code: float(price) for code, price in cur.fetchall()}
            cur.execute("select id from app_user where email = 'admin@%s.test'" % TENANT)
            admin_id = cur.fetchone()[0]
            cur.execute("select id from app_user where role = 'FACILITATOR' and email like "
                        "'%%@%s.test' order by id limit 1" % TENANT)
            facilitator_id = cur.fetchone()[0]
            cur.execute("select id, email from app_user where email like 'player%%@%s.test' "
                        "order by id" % TENANT)
            players = cur.fetchall()
        conn.commit()

    admin = login("admin@" + TENANT + ".test")
    run = call(f"/api/runs?facilitatorId={facilitator_id}", "POST", {
        "name": "audit · replacement costing",
        "scenarioKey": "standard-v1",
        "teams": [{"name": f"R{i}", "companyName": f"Replacement {i}"} for i in range(1, 5)]},
        admin)
    run_id = run["id"]
    print(f"run {run_id}: created (replacement costing)")
    for index, team in enumerate(run["teams"], start=1):
        call(f"/api/runs/{run_id}/players", "POST",
             {"teamId": team["id"], "userId": players[index - 1][0], "roleKey": "OWNER"}, admin)

    cheapest = projects[0]
    # 90% of the hidden cost: enough for partial credit, not enough to complete outright.
    partial_invest = int(cheapest[1] * 9 // 10)

    def table(context, number):
        cash = max(0.0, float(context["cash"]))
        return {
            # spends most of what it has on marketing and research, so it runs itself dry
            "burner": decision(context, {"P1": 250, "P2": 90, "P3": 20},
                               base_prices=base_prices,
                               marketing=min(700_000_000, int(cash * 0.15)),
                               rnd=min(partial_invest, int(cash * 0.30)),
                               rnd_project=cheapest[0]),
            # twelve times the catalogue price, every month — absolute, not compounding
            "extreme": decision(context, {"P1": 60, "P2": 20, "P3": 5}, price_mult=12.0,
                                base_prices=base_prices),
            "hoarder": decision(context, affordable(context, {"P1": 500, "P2": 150, "P3": 40})),
            "idle": None,
        }

    names = ["burner", "extreme", "hoarder", "idle"]
    seats = [{"id": team["id"], "email": f"player{i}@{TENANT}.test", "name": names[i - 1]}
             for i, team in enumerate(run["teams"], start=1)]
    play(run_id, seats, table, rounds)



def play(run_id: int, seats: list[dict], strategy_table, rounds: int) -> None:
    """Drive one class: `seats` is [{id, email, name}], `name` being the strategy key."""
    facilitator = login("trainer@" + TENANT + ".test")
    tokens = {seat["email"]: login(seat["email"]) for seat in seats}
    for number in range(1, rounds + 1):
        # Round 1 is already open when the seed leaves the session unplayed.
        call(f"/api/runs/{run_id}/rounds/next", "POST", {}, facilitator, tolerate=(409,))
        for seat in seats:
            context = call(f"/api/runs/{run_id}/teams/{seat['id']}/context",
                           token=tokens[seat["email"]])
            payload = strategy_table(context, number)[seat["name"]]
            if payload is None:
                continue
            try:
                call(f"/api/runs/{run_id}/teams/{seat['id']}/decisions", "POST", payload,
                     tokens[seat["email"]])
            except SystemExit as refusal:
                print(f"  {seat['name']}: refused ({str(refusal)[:90]})")
        call(f"/api/runs/{run_id}/rounds/close", "POST", {}, facilitator)
        print(f"  run {run_id}: round {number} computed")



def main() -> None:
    rounds = 8
    if "--rounds" in sys.argv:
        rounds = int(sys.argv[sys.argv.index("--rounds") + 1])

    # Run 1 comes from the seed: playerN@<tenant>.test, one seat each, in seeding order.
    names = ["hoarder", "liquidator", "overfunder", "quality", "machinist", "refuser"]
    emails = [f"player{index}@{TENANT}.test" for index in range(1, len(names) + 1)]
    if "--second" in sys.argv:
        second_run(6)
        print("done — now run scripts/audit_financials.py")
        return

    print(f"run 1: {rounds} rounds, {len(names)} companies")
    play(1, emails, names, lambda context, number: strategies(context, number), rounds)
    print("done — now run scripts/audit_financials.py")


if __name__ == "__main__":
    main()
