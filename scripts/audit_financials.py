#!/usr/bin/env python3
"""Independent audit of every computed figure in a bizsim database.

The point is not "the numbers add up on screen" — the product could do that while being wrong. This
script **re-derives** every figure from first principles (the scenario catalogue, the decisions, the
run's economy) and compares the result with what the engine stored. It shares no code with the
engine: it is a second implementation of the model, written from `docs/SIMULATION_MODEL.md`, so a
disagreement means one of them is wrong and can be argued about with numbers.

    BIZSIM_DSN=postgresql://postgres:postgres@127.0.0.1:55432/bizsim_audit \
        python scripts/audit_financials.py [--verbose]

What is re-derived, per company per round:
  unit cost, price, quality, planned/produced units, capacity use, materials, payroll, hiring,
  holding, quality spend, capacity spend, report cost, marketing, R&D spend, the R&D resolution
  (duration, partial credit, curves, seeded CHANCE roll, over-funding refund), demand allocation
  (attractiveness, pool, largest remainder), sales, revenue, COGS and stock value (weighted-average
  or replacement), stock, brand, profit, cash, market share, utilisation, cumulative totals.

Exit code 1 if anything disagrees beyond tolerance; 0 when every check passes.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
from collections import defaultdict
from decimal import Decimal as D
from decimal import ROUND_DOWN, ROUND_HALF_UP, getcontext

import psycopg

getcontext().prec = 40

DSN = os.environ.get("BIZSIM_DSN",
                     "postgresql://postgres:postgres@127.0.0.1:55432/bizsim_audit")
VERBOSE = "--verbose" in sys.argv

# Java's money()/units(): half-up, 2 decimals for both (MONEY_SCALE = UNIT_SCALE = 2), div() is 6.
MONEY = D("0.01")
UNIT = D("0.01")
# Environment indices are carried at three decimals (EnvironmentService.forRound), not two.
INDEX = D("0.001")
SIX = D("0.000001")
ZERO = D(0)
ONE = D(1)
HUNDRED = D(100)
REFERENCE_COMPANIES = 10
# The two tolerances: exact for decimal chains, a cent for anything that went through a double.
TOL = D("0.01")
ULP = D("0.001")


def money(value) -> D:
    return D(value).quantize(MONEY, ROUND_HALF_UP)


def units(value) -> D:
    return D(value).quantize(UNIT, ROUND_HALF_UP)


def div(a, b, scale=6) -> D:
    """Java's div(): six decimals, half-up, and 0 instead of an exception on a zero divisor."""
    a, b = D(a), D(b)
    return ZERO if b == 0 else (a / b).quantize(D(1).scaleb(-scale), ROUND_HALF_UP)


def java_roll(team_id: int, round_number: int, code: str) -> D:
    """The CHANCE curve's seeded threshold: SHA-256 of team:round:project, first 53 bits."""
    digest = hashlib.sha256(f"{team_id}:{round_number}:{code}".encode()).digest()
    bits = 0
    for byte in digest[:8]:
        bits = (bits << 8) | byte
    uniform = (bits & 0x7FFFFFFFFFFFFFFF) >> 11
    return D(uniform) / D(1 << 53)


class Team:
    """The state the engine carries between rounds."""

    def __init__(self, row):
        self.id = row["id"]
        self.cash = D(row["cash"])
        self.brand = D(row["brand_score"])
        self.machines = D(row["machine_workers"])
        self.status = row["status"]
        self.cumulative_revenue = D(row["cumulative_revenue"] or 0)
        self.cumulative_profit = D(row["cumulative_profit"] or 0)
        self.lost_total = D(row["lost_sales_total"] or 0)
        self.products = {}
        self.staff = {}
        self.rnd = {}

    @classmethod
    def from_snapshot(cls, snapshot: dict) -> "Team":
        team = cls.__new__(cls)
        team.id = None
        team.cash = D(snapshot["cash"])
        team.brand = D(snapshot["brand"])
        team.machines = D(snapshot.get("machineWorkers") or 0)
        team.status = snapshot.get("status") or "ACTIVE"
        team.cumulative_revenue = D(snapshot.get("cumulativeRevenue") or 0)
        team.cumulative_profit = D(snapshot.get("cumulativeProfit") or 0)
        team.lost_total = D(snapshot.get("lostSalesTotal") or 0)
        team.products = {}
        for product in snapshot.get("products", []):
            team.products[product["product"]] = {
                "price": D(product["price"]), "quality": D(product["quality"]),
                "unit_cost": D(product["unitCost"]), "stock": D(product["stock"]),
                "stock_value": D(product.get("stockValue") or 0)}
        team.staff = {k: int(v) for k, v in (snapshot.get("staff") or {}).items()}
        team.rnd = {}
        for project in snapshot.get("rnd", []):
            team.rnd[project["project"]] = {
                "invested": D(project["invested"]), "months": project["monthsInvested"],
                "started": project.get("startedRound"), "status": project["status"],
                "factor": D(project["benefitFactor"]), "completed": project.get("completedRound")}
        return team

    def clone(self):
        other = Team.__new__(Team)
        other.__dict__.update({k: v for k, v in self.__dict__.items()})
        other.products = {k: dict(v) for k, v in self.products.items()}
        other.staff = dict(self.staff)
        other.rnd = {k: dict(v) for k, v in self.rnd.items()}
        return other


class Run:
    def __init__(self, cur, row):
        self.id = row["id"]
        self.tenant = row["tenant_id"]
        self.scenario_key = row["scenario_key"]
        self.rounds = row["rounds"] or 8
        self.lang = row["lang"]
        definition = cur.execute("select definition from scenario where key = ?"
                                 .replace("?", "%s"), (self.scenario_key,)).fetchone()["definition"]
        self.params = json.loads(definition) if isinstance(definition, str) else definition
        self.weighted = str(self.params.get("costBasis", "REPLACEMENT")).upper() == "WEIGHTED"
        # The definition nests the demand elasticities and the market's baseline competitor; both are
        # read here once so every calculation below uses the same numbers the engine does.
        self.elasticity = self.params.get("elasticity") or {}
        self.baseline_share = D(str((self.params.get("baselineCompetitor") or {}).get("share", 0)))
        self.products = {r["code"]: r for r in cur.execute(
            "select code, base_price, base_unit_cost, base_quality, base_demand, units_per_worker, "
            "income_elasticity, trend_affinity from product where scenario_key = %s",
            (self.scenario_key,)).fetchall()}
        self.channels = {r["code"]: r for r in cur.execute(
            "select code, reach_weight, brand_alpha from channel where scenario_key = %s",
            (self.scenario_key,)).fetchall()}
        self.projects = {r["code"]: r for r in cur.execute(
            "select code, target_product, advantage_type, advantage_value, hidden_cost, "
            "duration_months, partial_benefit, benefit_curve, benefit_exponent, overfunding "
            "from rnd_project where scenario_key = %s", (self.scenario_key,)).fetchall()}
        self.economy = {r["round_number"]: r for r in cur.execute(
            "select round_number, demand_index, cost_index, wage_index, trend_strength "
            "from run_round_economy where run_id = %s", (self.id,)).fetchall()}
        self.adjustments = defaultdict(lambda: ZERO)
        for row in cur.execute(
                "select team_id, round_number, amount from facilitator_action "
                "where run_id = %s and action = 'CASH_ADJUST' and amount is not null", (self.id,)
        ).fetchall():
            self.adjustments[(row["team_id"], row["round_number"])] += D(row["amount"])
        self.fired = cur.execute(
            "select event_code, starts_round, duration_months from run_environment_event "
            "where run_id = %s", (self.id,)).fetchall()
        self.events = {r["code"]: r for r in cur.execute(
            "select code, demand_multiplier, cost_multiplier, wage_multiplier, target_product "
            "from environment_event where scenario_key = %s", (self.scenario_key,)).fetchall()}
        self.teams = {}
        for team_row in cur.execute(
                "select id, cash, brand_score, machine_workers, status, cumulative_revenue, "
                "cumulative_profit, lost_sales_total from team where run_id = %s order by id",
                (self.id,)).fetchall():
            self.teams[team_row["id"]] = Team(team_row)
        for product_row in cur.execute(
                "select team_id, product_code, unit_price, quality_level, unit_cost, stock, stock_value "
                "from company_product cp join team t on t.id = cp.team_id where t.run_id = %s",
                (self.id,)).fetchall():
            self.teams[product_row["team_id"]].products[product_row["product_code"]] = {
                "price": D(product_row["unit_price"]), "quality": D(product_row["quality_level"]),
                "unit_cost": D(product_row["unit_cost"]), "stock": D(product_row["stock"]),
                "stock_value": D(product_row["stock_value"] or 0)}
        for staff_row in cur.execute(
                "select team_id, function, headcount from company_staff cs "
                "join team t on t.id = cs.team_id where t.run_id = %s", (self.id,)).fetchall():
            self.teams[staff_row["team_id"]].staff[staff_row["function"]] = staff_row["headcount"]
        for rnd_row in cur.execute(
                "select cr.team_id, cr.project_code, cr.invested, cr.months_invested, "
                "cr.started_round, cr.status, cr.benefit_factor, cr.completed_round "
                "from company_rnd cr join team t on t.id = cr.team_id where t.run_id = %s",
                (self.id,)).fetchall():
            self.teams[rnd_row["team_id"]].rnd[rnd_row["project_code"]] = {
                "invested": D(rnd_row["invested"]), "months": rnd_row["months_invested"],
                "started": rnd_row["started_round"], "status": rnd_row["status"],
                "factor": D(rnd_row["benefit_factor"]), "completed": rnd_row["completed_round"]}

    # ---------------------------------------------------------------- environment
    def environment(self, round_number: int) -> dict:
        base = self.economy.get(round_number)
        if base is None:
            raise SystemExit(f"run {self.id}: no economy row for round {round_number} — "
                             "the audit needs the generated economy, not a guess")
        demand = D(base["demand_index"])
        cost = D(base["cost_index"])
        wage = D(base["wage_index"])
        trend = D(base["trend_strength"] or 0)
        by_product = {code: ONE for code in self.products}
        for fired in self.fired:
            if not (fired["starts_round"] <= round_number
                    < fired["starts_round"] + fired["duration_months"]):
                continue
            event = self.events.get(fired["event_code"])
            if event is None:
                continue
            cost = cost * D(event["cost_multiplier"])
            wage = wage * D(event["wage_multiplier"])
            target = event["target_product"]
            if target and target in by_product:
                by_product[target] = by_product[target] * D(event["demand_multiplier"])
            elif not target:
                demand = demand * D(event["demand_multiplier"])
        for code, product in self.products.items():
            elastic = D(str(float(demand) ** float(D(product["income_elasticity"] or 1))))
            trend_factor = ONE + trend * D(product["trend_affinity"] or 0)
            by_product[code] = (by_product[code] * elastic * trend_factor).quantize(SIX, ROUND_HALF_UP)
        return {"round": round_number, "demand": demand.quantize(INDEX, ROUND_HALF_UP),
                "cost": cost.quantize(INDEX, ROUND_HALF_UP), "wage": wage.quantize(INDEX, ROUND_HALF_UP),
                "trend": trend.quantize(MONEY, ROUND_HALF_UP), "by_product": by_product}

    # ---------------------------------------------------------------- R&D effects
    def effects(self, team: Team) -> dict:
        cost_mult = {code: ONE for code in self.products}
        upw_mult = {code: ONE for code in self.products}
        quality_add = {code: ZERO for code in self.products}
        attract_mult = {code: ONE for code in self.products}
        for code, state in team.rnd.items():
            project = self.projects.get(code)
            if project is None or state["status"] != "COMPLETED" or state["factor"] <= 0:
                continue
            amount = D(project["advantage_value"]) * state["factor"]
            targets = list(cost_mult) if not project["target_product"] else [project["target_product"]]
            for target in targets:
                if target not in cost_mult:
                    continue
                kind = project["advantage_type"]
                if kind == "UNIT_COST_DOWN":
                    cost_mult[target] = cost_mult[target] * (ONE - amount)
                elif kind == "CAPACITY_UP":
                    upw_mult[target] = upw_mult[target] * (ONE + amount)
                elif kind == "QUALITY_UP":
                    quality_add[target] = quality_add[target] + amount
                elif kind == "ATTRACTIVENESS_UP":
                    attract_mult[target] = attract_mult[target] * (ONE + amount)
        return {"cost": cost_mult, "upw": upw_mult, "quality": quality_add, "attract": attract_mult}


# ---------------------------------------------------------------- the plan (mirrors ScoringEngine.plan)
def plan(run: Run, team: Team, decision: dict, env: dict) -> dict:
    insolvent = team.status == "INSOLVENT"
    effects = run.effects(team)
    staff = dict(team.staff)
    target = (decision.get("manpower") or {}).get("production")
    if not insolvent and target is not None:
        staff["PRODUCTION"] = max(0, int(target))
    workers = D(staff.get("PRODUCTION", 0)) + team.machines

    prices, planned, produced = {}, {}, {}
    cost_now, quality_now, quality_bought = {}, {}, {}
    quality_spend = ZERO
    for code, spec in run.products.items():
        state = team.products.get(code, {"price": D(spec["base_price"]),
                                         "quality": D(spec["base_quality"]),
                                         "unit_cost": D(spec["base_unit_cost"])})
        price = price_of(decision, code)
        prices[code] = D(price) if price and D(price) > 0 else D(state["price"])
        cost_now[code] = money(D(state["unit_cost"])
                               * effects["cost"].get(code, ONE)
                               * D(env["cost"]))
        quality = D(state["quality"]) + effects["quality"].get(code, ZERO)
        bought = ZERO
        rate = D(run.params.get("qualityCostPerPoint") or 0)
        if run.params.get("qualityUpgrade") and not insolvent and rate > 0:
            wanted = quality_invest(decision, code)
            if wanted > 0:
                room = max(ZERO, HUNDRED - quality)
                bought = min(div(wanted, rate), room).quantize(MONEY, ROUND_HALF_UP)
                quality_spend += bought * rate
        quality_bought[code] = bought
        quality_now[code] = quality + bought
        planned[code] = ZERO if insolvent else max(ZERO, units_of(decision, code))
        produced[code] = planned[code]

    required = ZERO
    for code, spec in run.products.items():
        required += div(produced[code], D(spec["units_per_worker"]) * effects["upw"].get(code, ONE))
    capacity_factor = ONE
    if required > 0:
        capacity_factor = ZERO if workers <= 0 else min(ONE, div(workers, required))
    if capacity_factor < ONE:
        produced = {code: units(value * capacity_factor) for code, value in produced.items()}

    spend = {code: (ZERO if insolvent else D(spend_of(decision, code))) for code in run.channels}
    marketing = sum(spend.values(), ZERO)
    invest = {code: (ZERO if insolvent else D(invest_of(decision, code))) for code in run.projects}
    rnd_spend = sum(invest.values(), ZERO)
    capacity_cost = ZERO if insolvent else D(decision.get("capacityPurchase") or 0)

    materials = money(sum((produced[code] * cost_now[code] for code in run.products), ZERO))

    payroll = ZERO
    hiring = ZERO
    wage = D(env["wage"])
    for function, headcount in staff.items():
        salary = D((run.params.get("salaries") or {}).get(function, 0)) * wage
        payroll += salary * D(headcount)
        delta = headcount - team.staff.get(function, 0)
        if delta > 0:
            hiring += salary * D(delta) * D(run.params.get("hiringCostMonths") or 0)
        elif delta < 0:
            hiring += salary * D(-delta) * D(run.params.get("severanceMonths") or 0)

    holding = ZERO
    for code in run.products:
        state = team.products.get(code)
        if state is not None:
            holding += D(state["stock"]) * D(run.params.get("holdingCostPerUnit") or 0)

    capacity_used = ZERO
    for code, spec in run.products.items():
        capacity_used += div(produced[code], D(spec["units_per_worker"]) * effects["upw"].get(code, ONE))

    report_cost = D(run.params.get("marketReportCost") or 0) if decision.get("marketReport") else ZERO

    body = {
        "prices": prices, "planned": planned, "produced": produced, "cost": cost_now,
        "quality": quality_now, "quality_bought": quality_bought, "spend": spend,
        "invest": invest, "staff": staff, "materials": materials,
        "payroll": money(payroll), "hiring": money(hiring), "marketing": money(marketing),
        "rnd": money(rnd_spend), "quality_spend": money(quality_spend), "holding": money(holding),
        "capacity_cost": money(capacity_cost), "report": money(report_cost),
        "machine_added": div(capacity_cost, D(run.params.get("machineCostPerWorker") or 1)),
        "capacity_required": units(required), "capacity_used": units(capacity_used),
        "capacity_available": workers, "effects": effects,
    }
    return fit_to_cash(run, team, body, env)


def fit_to_cash(run: Run, team: Team, body: dict, env: dict) -> dict:
    fixed = D(run.params.get("fixedCostPerMonth") or 0)
    other = (body["payroll"] + body["hiring"] + body["marketing"] + body["rnd"]
             + body["quality_spend"] + body["holding"] + fixed + body["capacity_cost"] + body["report"])
    available = max(ZERO, team.cash - other)
    if body["materials"] <= available:
        return body
    factor = ZERO if body["materials"] == 0 else div(available, body["materials"])
    produced = {code: units(value * factor) for code, value in body["produced"].items()}
    materials = money(sum((produced[code] * body["cost"][code] for code in run.products), ZERO))
    capacity_used = ZERO
    for code, spec in run.products.items():
        capacity_used += div(produced[code], D(spec["units_per_worker"])
                             * body["effects"]["upw"].get(code, ONE))
    body.update({"produced": produced, "materials": materials,
                 "capacity_used": units(capacity_used)})
    return body


# ---------------------------------------------------------------- the market
def pool_for(run: Run, code: str, round_number: int, companies: int) -> D:
    spec = run.products[code]
    season = D((run.params.get("seasonality") or [1])[(round_number - 1) % max(
        1, len(run.params.get("seasonality") or [1]))])
    scale = D(max(companies, 1)) / D(REFERENCE_COMPANIES)
    return units(D(spec["base_demand"]) * season * scale)


def whole_pool(run: Run, code: str, env: dict, companies: int) -> D:
    return (pool_for(run, code, env["round"], companies)
            * env["by_product"].get(code, ONE)).quantize(D(1), ROUND_HALF_UP)


def reach(run: Run, body: dict) -> float:
    weighted = sum((body["spend"].get(code, ZERO) * D(channel["reach_weight"])
                    for code, channel in run.channels.items()), ZERO)
    reference = D(run.params.get("referenceSpend") or 0)
    if reference <= 0:
        return 1.0
    return float(weighted / reference) ** 0.5


def attractiveness(run: Run, code: str, team: Team, body: dict) -> float:
    params = run.params
    price = float(body["prices"][code])
    quality = float(body["quality"][code])
    brand = max(float(team.brand), 1.0)
    value = (float(D(run.products[code]["base_price"]) / D(str(price))) ** float(run.elasticity.get("price", 1)))
    value *= (quality / float(params.get("referenceQuality") or 1)) ** float(run.elasticity.get("quality", 1))
    value *= (brand / 50.0) ** float(run.elasticity.get("brand", 1))
    value *= max(reach(run, body), 0.01) ** float(run.elasticity.get("marketing", 1))
    value *= float(body["effects"]["attract"].get(code, ONE))
    return value


def allocate(run: Run, env: dict, teams: list[Team], bodies: list[dict]) -> dict:
    result = {team.id: {} for team in teams}
    baseline = float(run.baseline_share)
    n = len(teams)
    for code in run.products:
        scores = [attractiveness(run, code, team, body) for team, body in zip(teams, bodies)]
        total = sum(scores)
        pool = whole_pool(run, code, env, n)
        target = (pool * D(str(1 - baseline))).quantize(D(1), ROUND_HALF_UP)
        if total <= 0 or n == 0 or target <= 0:
            for team in teams:
                result[team.id][code] = 0
            continue
        raw = [target * D(str(score / total)) for score in scores]
        pieces = [value.quantize(D(1), ROUND_DOWN) for value in raw]
        assigned = sum(pieces, ZERO)
        leftover = int(min(target - assigned, D(n)))
        order = sorted(range(n), key=lambda i: (-(raw[i] - pieces[i]), i))
        for k in range(leftover):
            pieces[order[k]] += ONE
        for i, team in enumerate(teams):
            result[team.id][code] = int(pieces[i])
    return result


# ---------------------------------------------------------------- settlement
def settle(run: Run, env: dict, team: Team, body: dict, demand: dict) -> dict:
    revenue = cogs = lost_all = ZERO
    stock, stock_values, lines = {}, {}, []
    stock_change = ZERO
    for code, spec in run.products.items():
        state = team.products.get(code, {"stock": ZERO, "stock_value": ZERO, "price": D(spec["base_price"]),
                                         "quality": D(spec["base_quality"]),
                                         "unit_cost": D(spec["base_unit_cost"])})
        opening = D(state["stock"])
        produced = body["produced"][code]
        price = body["prices"][code]
        unit_cost = body["cost"][code]
        demanded = D(demand.get(code, 0))
        available = opening + produced
        sold = min(demanded, available)
        lost = max(ZERO, demanded - sold)
        closing = max(ZERO, available - sold)
        line_revenue = money(sold * price)
        opening_value = D(state["stock_value"] or 0)
        added_value = money(produced * unit_cost)
        available_value = opening_value + added_value
        if run.weighted:
            average = div(available_value, available) if available > 0 else unit_cost
            line_cogs = money(sold * average)
            closing_value = max(ZERO, available_value - line_cogs)
        else:
            average = unit_cost
            line_cogs = money(sold * unit_cost)
            closing_value = money(closing * unit_cost)
        revenue += line_revenue
        cogs += line_cogs
        lost_all += lost
        stock[code] = closing
        stock_values[code] = closing_value
        stock_change += closing_value - opening_value
        lines.append({"product": code, "opening": opening, "produced": produced, "demanded": demanded,
                      "sold": sold, "closing": closing, "lost": lost, "price": price,
                      "unit_cost": unit_cost, "average_cost": average, "revenue": line_revenue,
                      "cogs": line_cogs, "stock_value": closing_value})

    # R&D resolution
    min_benefit = D(run.params.get("minRndBenefitRatio") or 0)
    refund = ZERO
    rnd_next = {}
    for code, project in run.projects.items():
        state = team.rnd.get(code, {"invested": ZERO, "months": 0, "started": None,
                                    "status": "ACTIVE", "factor": ZERO, "completed": None})
        status, months = state["status"], state["months"]
        started, factor, completed = state["started"], state["factor"], state["completed"]
        invested = state["invested"]
        invest = body["invest"].get(code, ZERO) if status == "ACTIVE" else ZERO
        if status == "ACTIVE" and (invest > 0 or started is not None):
            if started is None:
                started = env["round"]
            months = env["round"] - started + 1
            invested = invested + invest
            if months >= int(project["duration_months"]):
                completed = env["round"]
                ratio = div(invested, D(project["hidden_cost"]))
                if ratio >= ONE:
                    status, factor = "COMPLETED", ONE
                elif not project["partial_benefit"] or ratio < min_benefit:
                    status, factor = "FAILED", ZERO
                else:
                    progress = min(ONE, div(ratio - min_benefit, ONE - min_benefit))
                    curve = project["benefit_curve"] or "LINEAR"
                    if curve == "CHANCE":
                        threshold = (min_benefit + (ONE - min_benefit) * java_roll(
                            team.id, env["round"], code)).quantize(D("0.0001"), ROUND_HALF_UP)
                        ok = ratio >= threshold
                        status, factor = ("COMPLETED", ONE) if ok else ("FAILED", ZERO)
                    elif curve == "POWER":
                        factor = min(ONE, D(str(float(progress) ** float(project["benefit_exponent"]))))
                        status = "COMPLETED"
                    else:
                        factor = progress
                        status = "COMPLETED"
                if status == "COMPLETED":
                    over = invested - D(project["hidden_cost"])
                    if over > 0 and project["overfunding"] != "LOST":
                        refund += over
                        invested = D(project["hidden_cost"])
        rnd_next[code] = {"invested": money(invested), "months": months, "started": started,
                          "status": status, "factor": factor.quantize(D("0.0001"), ROUND_HALF_UP),
                          "completed": completed, "spend": invest}

    fixed = D(run.params.get("fixedCostPerMonth") or 0)
    profit = money(revenue - cogs - body["payroll"] - body["marketing"] - body["rnd"]
                   - body["quality_spend"] - fixed - body["holding"] - body["hiring"] - body["report"])
    cash_after = money(team.cash + revenue - body["materials"] - body["payroll"] - body["marketing"]
                       - body["rnd"] - body["quality_spend"] - fixed - body["holding"]
                       - body["hiring"] - body["capacity_cost"] - body["report"] + refund)
    brand_after = brand(run, team.brand, body["spend"])
    return {"lines": lines, "revenue": money(revenue), "cogs": money(cogs), "profit": profit,
            "cash": cash_after, "brand": brand_after, "stock": stock, "stock_values": stock_values,
            "stock_change": money(stock_change), "refund": money(refund), "rnd": rnd_next,
            "lost": units(lost_all), "machines": money(team.machines + body["machine_added"]),
            "capacity_used": body["capacity_used"], "capacity_available": body["capacity_available"]}


def brand(run: Run, opening: D, spend: dict) -> D:
    value = D(opening) * (ONE - D(run.params.get("brandDecay") or 0))
    for code, channel in run.channels.items():
        amount = D(spend.get(code, ZERO))
        if amount > 0:
            value += D(str(float(D(channel["brand_alpha"])) * (float(amount) / 1_000_000) ** 0.5))
    return money(max(ZERO, min(HUNDRED, value)))


# ---------------------------------------------------------------- decision helpers
def rows_of(decision: dict, field: str) -> list[dict]:
    return decision.get(field) or []


def price_of(decision: dict, code: str):
    for row in rows_of(decision, "price"):
        if row.get("product") == code:
            return row.get("unitPrice")
    return None


def units_of(decision: dict, code: str) -> D:
    for row in rows_of(decision, "production"):
        if row.get("product") == code:
            return D(row.get("units") or 0)
    return ZERO


def spend_of(decision: dict, code: str) -> D:
    for row in rows_of(decision, "marketing"):
        if row.get("channel") == code:
            return D(row.get("spend") or 0)
    return ZERO


def invest_of(decision: dict, code: str) -> D:
    for row in rows_of(decision, "rnd"):
        if row.get("project") == code:
            return D(row.get("invest") or 0)
    return ZERO


def quality_invest(decision: dict, code: str) -> D:
    for row in rows_of(decision, "quality"):
        if row.get("product") == code:
            return D(row.get("invest") or 0)
    return ZERO


# ---------------------------------------------------------------- comparison
class Audit:
    def __init__(self):
        self.failures: list[str] = []
        self.checks = 0
        self.team_rounds = 0

    def check(self, label: str, stored, computed, tolerance=TOL, where=""):
        self.checks += 1
        stored_d = D(str(stored)) if stored is not None else ZERO
        computed_d = D(str(computed))
        delta = abs(stored_d - computed_d)
        if delta > tolerance:
            self.failures.append(f"{where} {label}: stored {stored_d} computed {computed_d} "
                                 f"(delta {delta})")
        elif VERBOSE and delta > 0:
            print(f"    ~ {where} {label}: delta {delta} within tolerance")

    def equals(self, label: str, stored, computed, where=""):
        self.checks += 1
        if (stored is None) != (computed is None) or (stored is not None and str(stored) != str(computed)):
            self.failures.append(f"{where} {label}: stored {stored!r} computed {computed!r}")


def load_statements(cur, run_id: int) -> dict:
    rows = cur.execute("""
        select r.number as round_number, s.team_id, s.revenue, s.cogs, s.payroll, s.marketing,
               s.rnd_spend, s.fixed_cost, s.holding_cost, s.hiring_cost, s.capacity_cost,
               s.report_cost, s.quality_cost, s.opening_cash, s.profit, s.cash_after, s.brand_after,
               s.lost_sales, s.capacity_used, s.capacity_available, s.detail, s.cost_basis
        from round_statement s join round r on r.id = s.round_id
        where r.run_id = %s and r.status = 'COMPUTED' order by r.number, s.team_id
        """, (run_id,)).fetchall()
    return {(row["round_number"], row["team_id"]): row for row in rows}


def load_results(cur, run_id: int) -> dict:
    rows = cur.execute("""
        select r.number as round_number, rr.team_id, rr.score, rr.total_score
        from round_result rr join round r on r.id = rr.round_id
        where r.run_id = %s order by r.number, rr.team_id
        """, (run_id,)).fetchall()
    return {(row["round_number"], row["team_id"]): row for row in rows}


def load_decisions(cur, run_id: int) -> dict:
    rows = cur.execute("""
        select r.number as round_number, d.team_id, d.payload
        from decision d join round r on r.id = d.round_id
        where r.run_id = %s
        """, (run_id,)).fetchall()
    return {(row["round_number"], row["team_id"]):
            (row["payload"] if isinstance(row["payload"], dict) else json.loads(row["payload"]))
            for row in rows}


def audit_run(cur, run_row, audit: Audit) -> None:
    run = Run(cur, run_row)
    statements = load_statements(cur, run.id)
    results = load_results(cur, run.id)
    decisions = load_decisions(cur, run.id)
    rounds = sorted({key[0] for key in statements})
    if not rounds:
        return
    print(f"run {run.id}  {run.scenario_key}  {'WEIGHTED' if run.weighted else 'REPLACEMENT'}  "
          f"{len(run.teams)} companies  {len(rounds)} computed rounds")
    # The team table holds the state after the *last* round. The audit must start where the engine
    # started: round 1's snapshot, written as the books opened.
    snapshots = {(row["round_number"], row["team_id"]): row["state"] for row in cur.execute(
        "select r.number as round_number, rs.team_id, rs.state from round_snapshot rs "
        "join round r on r.id = rs.round_id where r.run_id = %s", (run.id,)).fetchall()}
    first = rounds[0]
    for team_id in list(run.teams):
        state = snapshots.get((first, team_id))
        if state is None:
            raise SystemExit(f"run {run.id}: no snapshot for round {first} team {team_id}; "
                             "the audit refuses to guess the opening state")
        team = Team.from_snapshot(state)
        team.id = team_id
        run.teams[team_id] = team
    teams = [run.teams[team_id] for team_id in sorted(run.teams)]
    for round_number in rounds:
        env = run.environment(round_number)
        bodies = [plan(run, team, decisions.get((round_number, team.id), {}), env)
                  for team in teams]
        demand = allocate(run, env, teams, bodies)
        pool = sum((whole_pool(run, code, env, len(teams)) for code in run.products), ZERO)
        # D34: the cohort's demand must add up to the whole pieces the market offers it.
        target_total = sum(((whole_pool(run, code, env, len(teams))
                             * D(str(1 - float(run.baseline_share))))
                            .quantize(D(1), ROUND_HALF_UP) for code in run.products), ZERO)
        allocated = sum((D(sum(demand[team.id].values())) for team in teams), ZERO)
        audit.check("demand allocated equals the cohort's market", target_total, allocated,
                    tolerance=D(0), where=f"run {run.id} r{round_number}")

        for team, body in zip(teams, bodies):
            stored = statements[(round_number, team.id)]
            # Cash handed to a company by the facilitator between rounds is part of the opening
            # balance: it is the one inflow with no statement line of its own.
            team.cash += run.adjustments.get((team.id, round_number), ZERO)
            # D80: the basis is a property of the statement, not of the template it came from.
            run.weighted = (stored.get("cost_basis") or "REPLACEMENT").upper() == "WEIGHTED"
            outcome = settle(run, env, team, body, demand[team.id])
            where = f"run {run.id} r{round_number} team {team.id}"
            audit.team_rounds += 1

            for label, copy in (("revenue", outcome["revenue"]), ("cogs", outcome["cogs"]),
                                ("payroll", body["payroll"]), ("marketing", body["marketing"]),
                                ("rnd spend", body["rnd"]), ("fixed", money(D(run.params.get(
                                    "fixedCostPerMonth") or 0))), ("holding", body["holding"]),
                                ("hiring", body["hiring"]), ("capacity bought", body["capacity_cost"]),
                                ("report", body["report"]), ("quality spend", body["quality_spend"]),
                                ("opening cash", team.cash), ("profit", outcome["profit"]),
                                ("cash after", outcome["cash"]), ("brand after", outcome["brand"]),
                                ("lost sales", outcome["lost"]),
                                ("capacity used", outcome["capacity_used"]),
                                ("capacity available", outcome["capacity_available"])):
                column = {"rnd spend": "rnd_spend", "capacity bought": "capacity_cost",
                          "opening cash": "opening_cash", "cash after": "cash_after",
                          "brand after": "brand_after", "lost sales": "lost_sales",
                          "capacity used": "capacity_used",
                          "capacity available": "capacity_available", "quality spend": "quality_cost",
                          "fixed": "fixed_cost", "holding": "holding_cost",
                          "hiring": "hiring_cost", "report": "report_cost"}.get(label, label)
                # Lost sales inherits the allocation tie (see the per-line note): one unit, no more.
                audit.check(label, stored[column], copy,
                            tolerance=D(1) if label == "lost sales" else TOL, where=where)

            # Per-product lines, from the statement detail.
            detail = stored["detail"] if isinstance(stored["detail"], dict) else json.loads(stored["detail"])
            for line in outcome["lines"]:
                stored_line = next((row for row in detail.get("products", [])
                                    if row.get("product") == line["product"]), None)
                if stored_line is None:
                    audit.failures.append(f"{where} missing product line {line['product']}")
                    continue
                for label, stored_key, computed_key in (
                        ("units sold", "sold", "sold"),
                        ("units produced", "produced", "produced"),
                        ("closing stock", "closingStock", "closing"),
                        ("lost", "lost", "lost"),
                        ("demand", "demand", "demanded"),
                        ("line revenue", "revenue", "revenue"),
                        ("line cogs", "cogs", "cogs"),
                        ("stock value", "stockValue", "stock_value")):
                    units_field = label in ("units sold", "units produced", "closing stock", "lost", "demand")
                    tie = label in ("demand", "lost")
                    audit.check(f"{line['product']} {label}", stored_line[stored_key],
                                line[computed_key],
                                tolerance=(D(1) if tie else ULP) if units_field else TOL, where=where)
            # The identity the whole product rests on: everything profit charges that cash did not
            # pay, plus everything cash paid that profit did not charge. COGS at the round's basis
            # minus materials at the same basis is the inventory term, whatever the basis is.
            audit.check("cash − profit vs inventory/capacity/refund",
                        D(stored["cash_after"]) - D(stored["opening_cash"]) - D(stored["profit"]),
                        outcome["cogs"] - body["materials"] - body["capacity_cost"] + outcome["refund"],
                        where=where)
            if run.weighted:
                # Under weighted average the inventory term is exactly the change in stock value.
                audit.check("inventory term equals the stock-value change",
                            outcome["cogs"] - body["materials"], -outcome["stock_change"], where=where)
            audit.check("profit = revenue − every cost",
                        D(stored["profit"]),
                        D(stored["revenue"]) - D(stored["cogs"]) - D(stored["payroll"])
                        - D(stored["marketing"]) - D(stored["rnd_spend"]) - D(stored["quality_cost"])
                        - D(stored["fixed_cost"]) - D(stored["holding_cost"])
                        - D(stored["hiring_cost"]) - D(stored["report_cost"]),
                        where=where)

            # The score map must agree with the statement it is derived from.
            score = results[(round_number, team.id)]["score"]
            score = score if isinstance(score, dict) else json.loads(score)
            audit.check("score.cash vs statement", score.get("cash"), stored["cash_after"], where=where)
            audit.check("score.revenue vs statement", score.get("revenue"), stored["revenue"], where=where)
            audit.check("score.profit vs statement", score.get("profit"), stored["profit"], where=where)
            share = (D(sum(line["sold"] for line in outcome["lines"])) * HUNDRED / pool
                     if pool > 0 else ZERO).quantize(MONEY, ROUND_HALF_UP)
            audit.check("score.marketShare", score.get("marketShare"), share, tolerance=D("0.02"),
                        where=where)
            utilisation = (outcome["capacity_used"] * HUNDRED / outcome["capacity_available"]
                           if outcome["capacity_available"] > 0 else ZERO).quantize(MONEY, ROUND_HALF_UP)
            audit.check("score.capacityUtilisation", score.get("capacityUtilisation"), utilisation,
                        tolerance=D("0.02"), where=where)

            # Advance the model state the way the engine's applyOutcome does.
            team.cash = outcome["cash"]
            team.brand = outcome["brand"]
            team.machines = outcome["machines"]
            team.status = "INSOLVENT" if outcome["cash"] < 0 else "ACTIVE"
            team.cumulative_revenue += outcome["revenue"]
            team.cumulative_profit += outcome["profit"]
            team.lost_total += outcome["lost"]
            for line in outcome["lines"]:
                state = team.products[line["product"]]
                state["price"] = line["price"]
                state["quality"] = units(body["quality"][line["product"]])
                state["stock"] = line["closing"]
                state["stock_value"] = line["stock_value"]
            for code, state in outcome["rnd"].items():
                team.rnd[code].update({"invested": state["invested"], "months": state["months"],
                                       "started": state["started"], "status": state["status"],
                                       "factor": state["factor"], "completed": state["completed"]})
            team.staff = dict(body["staff"])

    # cumulative totals and the chain between rounds
    for team in teams:
        stored_team = cur.execute(
            "select cash, brand_score, machine_workers, cumulative_revenue, cumulative_profit, "
            "lost_sales_total from team where id = %s", (team.id,)).fetchone()
        where = f"run {run.id} team {team.id} (end of term)"
        audit.check("cumulative revenue", stored_team["cumulative_revenue"], team.cumulative_revenue,
                    where=where)
        audit.check("cumulative profit", stored_team["cumulative_profit"], team.cumulative_profit,
                    where=where)
        audit.check("closing cash", stored_team["cash"], team.cash, where=where)
        audit.check("closing brand", stored_team["brand_score"], team.brand, where=where)
        audit.check("machine workers", stored_team["machine_workers"], team.machines, where=where)
        for code, state in team.products.items():
            stored_product = cur.execute(
                "select stock, stock_value from company_product where team_id = %s and product_code = %s",
                (team.id, code)).fetchone()
            audit.check(f"{code} closing stock", stored_product["stock"], state["stock"], tolerance=ULP,
                        where=where)
            audit.check(f"{code} closing stock value", stored_product["stock_value"],
                        state["stock_value"], where=where)

    # chains: opening of round n+1 equals closing of round n
    for round_number in rounds[1:]:
        for team in teams:
            previous = statements[(round_number - 1, team.id)]
            current = statements[(round_number, team.id)]
            where = f"run {run.id} r{round_number} team {team.id}"
            adjustment = run.adjustments.get((team.id, round_number), ZERO)
            audit.check("opening cash continues the previous round"
                        + (f" (net of a facilitator adjustment of {adjustment})" if adjustment else ""),
                        current["opening_cash"], D(previous["cash_after"]) + adjustment,
                        tolerance=D(0), where=where)
            current_lines = (current["detail"] if isinstance(current["detail"], dict)
                             else json.loads(current["detail"])).get("products", [])
            previous_lines = (previous["detail"] if isinstance(previous["detail"], dict)
                              else json.loads(previous["detail"])).get("products", [])
            for line in current_lines:
                before = next((row for row in previous_lines
                               if row["product"] == line["product"]), None)
                if before is None:
                    continue
                audit.check(f"{line['product']} opening stock continues the previous round",
                            D(str(line["openingStock"])), D(str(before["closingStock"])),
                            tolerance=D(0), where=where)

    # share is a share of the whole market, so the class plus the baseline is 100%
    for round_number in rounds:
        env = run.environment(round_number)
        pool = sum((whole_pool(run, code, env, len(teams)) for code in run.products), ZERO)
        total = sum((D((results[(round_number, team.id)]["score"] if isinstance(
            results[(round_number, team.id)]["score"], dict) else json.loads(
            results[(round_number, team.id)]["score"])).get("marketShare", 0)) for team in teams), ZERO)
        # Share is measured on units *sold*, so a class that stocks out cannot reach the market it
        # competes for: the ceiling is the cohort's share of the pool, the floor is whatever it served.
        ceiling = D(str(1 - float(run.baseline_share))) * HUNDRED
        audit.checks += 1
        if total > ceiling + D("0.5"):
            audit.failures.append(f"run {run.id} r{round_number} class share {total} exceeds the "
                                  f"market it competes for ({ceiling})")


def main() -> None:
    audit = Audit()
    with psycopg.connect(DSN) as conn:
        with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
            runs = cur.execute(
                "select id, tenant_id, scenario_key, rounds, lang from run order by id").fetchall()
            for run_row in runs:
                audit_run(cur, run_row, audit)
    print()
    if audit.failures:
        print(f"FAIL — {len(audit.failures)} disagreement(s) in {audit.checks} checks "
              f"over {audit.team_rounds} company-rounds")
        for failure in audit.failures[:60]:
            print("  " + failure)
        if len(audit.failures) > 60:
            print(f"  … and {len(audit.failures) - 60} more")
        sys.exit(1)
    print(f"PASS — {audit.checks} checks over {audit.team_rounds} company-rounds, every figure "
          f"re-derived from the decisions, the catalogue and the run's economy")


if __name__ == "__main__":
    main()
