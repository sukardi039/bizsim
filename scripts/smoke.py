"""End-to-end smoke run against a live backend. Prints a transcript; exits non-zero on failure."""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
DEMO = "demo1234"


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            if not raw:
                return response.status, None
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode() or "{}")


def login(email):
    status, body = call("POST", "/api/auth/login",
                        body={"tenantCode": "demo", "email": email, "password": DEMO})
    assert status == 200, (status, body)
    return body["token"]


def main():
    status, pong = call("GET", "/api/ping")
    print(f"ping                     {status} {pong}")

    trainer = login("trainer@demo.test")
    player = login("player@demo.test")
    status, me = call("GET", "/api/me", player)
    print(f"player identity          user={me.get('userId')} role={me.get('role')} tenant={me.get('tenantCode')}")

    status, run = call("POST", "/api/runs", trainer, {
        "name": "Live smoke",
        "scenarioKey": "standard-v1",
        "cycles": 2,
        "teams": [
            {"name": "Alpha", "companyName": "Alpha Industries"},
            {"name": "Beta", "companyName": "Beta Corp"},
            {"name": "Gamma", "companyName": "Gamma Ltd"},
        ],
    })
    assert status == 201, (status, run)
    run_id = run["id"]
    print(f"run created              id={run_id} rounds={run['rounds']} cycles={run['cycles']} "
          f"teams={len(run['teams'])} cash={run['teams'][0]['cash']}")

    team = run["teams"][0]["id"]
    status, _ = call("POST", f"/api/runs/{run_id}/players", trainer,
                     {"teamId": team, "userId": me["userId"], "roleKey": "OWNER"})
    print(f"player assigned          {status}")

    status, round_state = call("POST", f"/api/runs/{run_id}/rounds/next", trainer)
    print(f"round opened             {status} {round_state}")

    status, context = call("GET", f"/api/runs/{run_id}/teams/{team}/context", player)
    print(f"form context             {status} products={len(context['products'])} "
          f"projects={len(context['rndProjects'])} market={context['market']['outlook']} "
          f"trend={context['market']['trendLabel']} reportCost={context['marketReportCost']}")
    assert "hiddenCost" not in json.dumps(context), "the hidden cost leaked into the form context"

    status, body = call("GET", f"/api/runs/{run_id}/teams/{team}/market-report", player)
    print(f"report before buying     {status} {body.get('error')}")

    decision = {
        "production": [{"product": "P1", "units": 600},
                       {"product": "P2", "units": 150},
                       {"product": "P3", "units": 30}],
        "price": [{"product": "P1", "unitPrice": 1000000},
                  {"product": "P2", "unitPrice": 2500000},
                  {"product": "P3", "unitPrice": 5000000}],
        "marketing": [{"channel": "DIGITAL", "spend": 15000000},
                      {"channel": "TRADE", "spend": 10000000}],
        "manpower": {"production": 30},
        "rnd": [{"project": "RND-P1-COST", "invest": 100000000}],
        "capacityPurchase": 0,
        "marketReport": True,
    }
    status, body = call("POST", f"/api/runs/{run_id}/teams/{team}/decisions", player, decision)
    print(f"decision submitted       {status} {body}")

    status, report = call("GET", f"/api/runs/{run_id}/teams/{team}/market-report", player)
    print(f"report after buying      {status} cost={report.get('cost')} "
          f"forecast={[(row['round'], row['outlook'], row['trendLabel']) for row in report.get('forecast', [])]}")

    status, over = call("POST", f"/api/runs/{run_id}/teams/{team}/decisions", player,
                        {**decision, "production": [
                            {"product": "P1", "units": 10000},
                            {"product": "P2", "units": 0},
                            {"product": "P3", "units": 0}]})
    print(f"over-capacity refused    {status} {over.get('details')}")

    status, _ = call("POST", f"/api/runs/{run_id}/rounds/close", trainer)
    print(f"round closed             {status}")

    status, board = call("GET", f"/api/runs/{run_id}/scoreboard", player)
    for row in board:
        print(f"scoreboard               {row['company_name']:<18} profit={row['profit']:>12} "
              f"share={row['market_share']}% cash={row['cash']} stock={row['stock']}")

    status, statements = call("GET", f"/api/runs/{run_id}/teams/{team}/statements", player)
    st = statements[0]
    print(f"statement                revenue={st['revenue']} cogs={st['cogs']} payroll={st['payroll']} "
          f"report={st['report_cost']} profit={st['profit']} cash={st['cash_after']}")

    status, closed = call("POST", f"/api/runs/{run_id}/rounds/close", trainer)
    print(f"double close             {status} (204 means nothing computed twice)")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as failure:
        print("FAILED:", failure)
        sys.exit(1)
