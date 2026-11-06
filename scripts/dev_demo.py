#!/usr/bin/env python3
"""Play a demo session against the local dev stack, for eyeballing the debrief.

Creates two companies, two trainee accounts, a package with its class list, starts the session and
computes two rounds with real decisions. Every round closes at full capacity, so revenue, COGS,
stock and payroll are all non-zero and the detail view has something to show.

    python scripts/dev_demo.py [--rounds 2]
"""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"
ADMIN = {"tenantCode": "demo", "email": "admin@demo.test", "password": "demo1234"}
PLANS = {
    "ana@demo.test": {"P1": 800, "P2": 100, "P3": 20, "marketing": 1_000_000},
    "bo@demo.test": {"P1": 400, "P2": 60, "P3": 10, "marketing": 1_000_000},
}


def call(method: str, path: str, token: str | None = None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode() or "{}")


def login(email: str, password: str) -> str:
    status, body = call("POST", "/auth/login", body={
        "tenantCode": "demo", "email": email, "password": password})
    if status != 200:
        raise SystemExit(f"cannot sign in as {email}: {status} {body}")
    return body["token"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()

    admin = login(ADMIN["email"], ADMIN["password"])

    profiles = {}
    for name, line in (("Alpha Industries", ["P1", "P2"]), ("Beta Corp", [])):
        status, body = call("POST", "/companies", admin, {
            "name": name, "companyName": name, "products": line})
        if status == 409:
            body = next(c for c in call("GET", "/companies", admin)[1] if c["name"] == name)
        profiles[name] = body["id"]

    trainees = {}
    for email, name in (("ana@demo.test", "Ana"), ("bo@demo.test", "Bo")):
        status, body = call("POST", "/users", admin, {
            "email": email, "displayName": name, "password": "trainee1234", "role": "PLAYER"})
        if status == 409:
            body = next(u for u in call("GET", "/users", admin)[1] if u["email"] == email)
        trainees[email] = body["id"]

    status, package = call("POST", "/packages", admin, {
        "name": "Demo cohort",
        "templateKey": "standard-v1",
        "companyProfileIds": [profiles["Alpha Industries"], profiles["Beta Corp"]],
        "traineeIds": [trainees["ana@demo.test"], trainees["bo@demo.test"]],
        # D66: the class language is part of the package and is required.
        "locale": "en",
    })
    if status != 201:
        raise SystemExit(f"publish failed: {status} {package}")
    print("package", package["id"], "seats", [(s["position"], s["displayName"]) for s in package["seats"]])

    status, run = call("POST", f"/packages/{package['id']}/sessions", admin, {"name": "Demo session"})
    if status != 201:
        raise SystemExit(f"session start failed: {status} {run}")
    run_id = run["id"]
    print("session", run_id, [team["companyName"] for team in run["teams"]])

    for round_number in range(1, args.rounds + 1):
        call("POST", f"/runs/{run_id}/rounds/next", admin, {})
        for email, plan in PLANS.items():
            token = login(email, "trainee1234")
            mine = call("GET", "/runs", token)[1][0]
            team = next(p["teamId"] for p in mine["players"] if p["userId"] == trainees[email])
            context = call("GET", f"/runs/{run_id}/teams/{team}/context", token)[1]
            payload = {
                "production": [{"product": p["code"], "units": plan[p["code"]]}
                               for p in context["products"]],
                "price": [{"product": p["code"], "unitPrice": p["price"]}
                          for p in context["products"]],
                "marketing": [{"channel": c["code"], "spend": plan["marketing"]}
                              for c in context["channels"]],
                "manpower": {"production": 30},
                "rnd": [{"project": r["code"], "invest": 0} for r in context["rndProjects"]],
                "capacityPurchase": 0,
                "marketReport": False,
            }
            status, body = call("POST", f"/runs/{run_id}/teams/{team}/decisions", token, payload)
            print(f"  r{round_number} {email} submit -> {status}", "" if status == 200 else body)
        status, _ = call("POST", f"/runs/{run_id}/rounds/close", admin, {})
        print(f"  r{round_number} close -> {status}")

    board = call("GET", f"/runs/{run_id}/scoreboard?round={args.rounds}", admin)[1]
    for row in board:
        print(f"  {row['company_name']}: profit {row['profit']:,.0f} cash {row['cash']:,.0f}")


if __name__ == "__main__":
    main()
