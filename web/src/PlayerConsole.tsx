import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "./i18n";
import { describeError } from "./errors";
import StatementDetail from "./StatementDetail";
import {
  api,
  ApiError,
  type DecisionFormContext,
  type DecisionInput,
  type MarketForecast,
  type Run,
  type StatementRow,
  type Team,
} from "./api";

/* ------------------------------------------------------------------ helpers */

function errorText(error: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  return describeError(error, t);
}

/** The shortfall body carries a per-line breakdown; that is the useful part to show. */
function shortfallLines(error: unknown): { cash: number; required: number; lines: [string, number][] } | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const body = error.body;
  if (!body || typeof body.required !== "number" || typeof body.lines !== "object" || !body.lines) {
    return null;
  }
  return {
    cash: body.cash,
    required: body.required,
    lines: Object.entries(body.lines as Record<string, number>).filter(([, v]) => v > 0),
  };
}

interface Draft {
  production: Record<string, number>;
  price: Record<string, number>;
  marketing: Record<string, number>;
  rnd: Record<string, number>;
  manpower: number;
  capacity: number;
  marketReport: boolean;
}

/**
 * Builds the form state. When the company already submitted this round the server sends the stored
 * decision back, so reopening the page shows what they sent rather than a blank month (D1: the
 * decision is one per company per month, and it stays editable until the round closes).
 */
function draftFrom(context: DecisionFormContext): Draft {
  const saved = context.decision;

  const units = (code: string) =>
    Number((saved?.production ?? []).find((r) => r.product === code)?.units ?? 0);
  const priceOf = (code: string, fallback: number) =>
    Number((saved?.price ?? []).find((r) => r.product === code)?.unitPrice ?? fallback);

  const production: Record<string, number> = {};
  const price: Record<string, number> = {};
  for (const product of context.products) {
    production[product.code] = units(product.code);
    price[product.code] = priceOf(product.code, product.price);
  }

  const marketing: Record<string, number> = {};
  for (const channel of context.channels) {
    const hit = (saved?.marketing ?? []).find((r) => r.channel === channel.code);
    marketing[channel.code] = hit ? Number(hit.spend) : 0;
  }

  const rnd: Record<string, number> = {};
  for (const project of context.rndProjects) {
    const hit = (saved?.rnd ?? []).find((r) => r.project === project.code);
    rnd[project.code] = hit ? Number(hit.invest) : 0;
  }

  return {
    production,
    price,
    marketing,
    rnd,
    manpower: saved?.manpower?.production ?? context.staff.PRODUCTION ?? 0,
    capacity: saved?.capacityPurchase ?? 0,
    marketReport: saved?.marketReport ?? false,
  };
}

function toPayload(context: DecisionFormContext, draft: Draft): DecisionInput {
  return {
    production: context.products.map((p) => ({ product: p.code, units: draft.production[p.code] ?? 0 })),
    price: context.products.map((p) => ({ product: p.code, unitPrice: draft.price[p.code] ?? p.price })),
    marketing: context.channels.map((c) => ({ channel: c.code, spend: draft.marketing[c.code] ?? 0 })),
    manpower: { production: draft.manpower },
    rnd: context.rndProjects.map((r) => ({ project: r.code, invest: draft.rnd[r.code] ?? 0 })),
    capacityPurchase: draft.capacity,
    marketReport: draft.marketReport,
  };
}

/* ------------------------------------------------------------------ screen */

export default function PlayerConsole({ userId, onSignOut }: { userId: number; onSignOut: () => void }) {
  const { t, money, number: plain } = useI18n();
  const [run, setRun] = useState<Run | null>(null);
  const [team, setTeam] = useState<Team | null>(null);
  const [context, setContext] = useState<DecisionFormContext | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [shortfall, setShortfall] = useState<ReturnType<typeof shortfallLines>>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<{ forecast: MarketForecast[] } | null>(null);
  /** The trainee's own computed rounds: same statements the facilitator's debrief reads. */
  const [statements, setStatements] = useState<StatementRow[]>([]);
  const [resultRound, setResultRound] = useState<number | null>(null);
  const roundRef = useRef<number | null>(null);

  const load = useCallback(async () => {
    try {
      const runs = await api.runs();
      const active = runs.find((r) => r.status === "OPEN") ?? runs[0] ?? null;
      setRun(active);
      if (!active) {
        setTeam(null);
        setContext(null);
        return;
      }
      const seat = active.players.find((p) => p.userId === userId);
      const mine = seat ? active.teams.find((t) => t.id === seat.teamId) ?? null : null;
      setTeam(mine);
      if (!mine) {
        setContext(null);
        return;
      }
      const next = await api.context(active.id, mine.id);
      setContext(next);
      try {
        const history = await api.statements(active.id, mine.id);
        setStatements(history);
        setResultRound((current) => {
          const latest = history.length > 0 ? history[history.length - 1].round : null;
          const stillThere = current !== null && history.some((row) => row.round === current);
          return stillThere ? current : latest;
        });
      } catch {
        // Results are a convenience: a trainee must still be able to decide if this call fails.
        setStatements([]);
      }
      // A new round must reset the form; a poll inside the same round must not wipe edits.
      if (roundRef.current !== next.round) {
        roundRef.current = next.round;
        setDraft(draftFrom(next));
        setReport(null);
      }
    } catch (error) {
      setProblem(errorText(error, t));
    }
  }, [userId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Light poll: the facilitator opens and computes rounds, so the screen has to notice without a
  // reload. SSE would be nicer, but the player only needs to know "is a round open, is it closed".
  useEffect(() => {
    const timer = window.setInterval(() => void load(), 10_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const workers = useMemo(() => {
    if (!context || !draft) return { available: 0, needed: 0, perProduct: {} as Record<string, number> };
    const available = draft.manpower + (context.machineWorkers ?? 0);
    const perProduct: Record<string, number> = {};
    let needed = 0;
    for (const product of context.products) {
      const units = draft.production[product.code] ?? 0;
      const cost = product.unitsPerWorker > 0 ? units / product.unitsPerWorker : 0;
      perProduct[product.code] = cost;
      needed += cost;
    }
    return { available, needed, perProduct };
  }, [context, draft]);

  const committed = useMemo(() => {
    if (!context || !draft) return 0;
    const marketing = context.channels.reduce((sum, c) => sum + (draft.marketing[c.code] ?? 0), 0);
    const rnd = context.rndProjects.reduce((sum, r) => sum + (draft.rnd[r.code] ?? 0), 0);
    const machines = draft.capacity * context.machineCostPerWorker;
    const review = draft.marketReport ? context.marketReportCost : 0;
    return marketing + rnd + machines + review;
  }, [context, draft]);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((current) => (current ? { ...current, [key]: value } : current));

  const setIn = (group: "production" | "price" | "marketing" | "rnd", code: string, value: number) =>
    setDraft((current) =>
      current ? { ...current, [group]: { ...current[group], [code]: value } } : current,
    );

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!run || !team || !context || !draft) return;
    setBusy(true);
    setProblem(null);
    setShortfall(null);
    setNotice(null);
    try {
      await api.submitDecision(run.id, team.id, toPayload(context, draft));
      setNotice(`Submitted for round ${context.round}. You can change it until the round closes.`);
      await load();
    } catch (error) {
      setShortfall(shortfallLines(error));
      setProblem(errorText(error, t));
    } finally {
      setBusy(false);
    }
  };

  const buyReport = async () => {
    if (!run || !team) return;
    try {
      const bought = await api.marketReport(run.id, team.id);
      setReport({ forecast: bought.forecast });
      setProblem(null);
    } catch (error) {
      setProblem(errorText(error, t));
    }
  };

  const signOut = (
    <div className="brand">
      <h1>bizsim · {team?.companyName ?? "trainee"}</h1>
      <button className="link muted" onClick={onSignOut}>
        sign out
      </button>
    </div>
  );

  if (!run) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>No simulation yet</h2>
          <p className="muted">Your facilitator has not opened a simulation for your company. This page checks again every 10 seconds.</p>
        </div>
      </div>
    );
  }

  if (!team) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>Not assigned to a company</h2>
          <p className="muted">You are signed in, but no company in this simulation is yours yet. Ask your facilitator to assign you.</p>
        </div>
      </div>
    );
  }

  if (!context || !draft) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>{team.companyName}</h2>
          <p className="muted">
            {run.currentRound === 0 ? "Waiting for your facilitator to open round 1." : "Loading the decision form…"}
          </p>
          {problem && <p className="error">{problem}</p>}
        </div>
      </div>
    );
  }

  const overCapacity = workers.needed > workers.available;
  const overCash = committed > context.cash;

  return (
    // noValidate on purpose: browser constraint validation (min/step) blocks the submit event with
    // no call and, in some layouts, no visible message -- the trainee would press the button and see
    // nothing happen. The server validates every field precisely and returns readable messages, so
    // the form lets the request through and shows what comes back.
    <form className="player" onSubmit={submit} noValidate>
      {signOut}

      <div className="card">
        <div className="run-head">
          <div>
            <h2 style={{ marginBottom: ".2rem" }}>{team.companyName}</h2>
            <p className="muted" style={{ margin: 0 }}>
              {t("player.round", { round: context.round, total: run.rounds })} · {context.market.outlook} ·{" "}
              {context.market.trendLabel}
            </p>
          </div>
          <div className="stats">
            <span>
              <small className="muted">{t("player.cash")}</small>
              <strong className={context.cash < 0 ? "negative" : ""}>{money(context.cash)}</strong>
            </span>
            <span>
              <small className="muted">{t("player.brand")}</small>
              <strong>{plain(context.brand)}</strong>
            </span>
            <span>
              <small className="muted">{t("player.machines")}</small>
              <strong>{plain(context.machineWorkers ?? 0)}</strong>
            </span>
          </div>
        </div>
        {context.submitted && (
          <p className="banner good">You have submitted a decision for this round. Editing and submitting again replaces it.</p>
        )}
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}
        {shortfall && (
          <div className="shortfall">
            <p className="error">
              {t("shortfall.title", {
                required: money(shortfall.required),
                available: money(shortfall.cash),
              })}
            </p>
            <ul>
              {shortfall.lines.map(([line, amount]) => (
                <li key={line}>
                  <span className="muted">{t(`line.${line}`)}</span> {money(amount)}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>

      <section className="card">
        <h2>{t("player.production")}</h2>
        <table>
          <thead>
            <tr>
              <th>{t("player.product")}</th>
              <th className="num">{t("player.stock")}</th>
              <th className="num">{t("player.unitCost")}</th>
              <th className="num">{t("player.units")}</th>
              <th className="num">{t("player.workers")}</th>
              <th className="num">{t("player.price")}</th>
            </tr>
          </thead>
          <tbody>
            {context.products.map((product) => (
              <tr key={product.code}>
                <td>
                  {product.name} <small className="muted">{product.code}</small>
                </td>
                <td className="num">{plain(product.stock)}</td>
                <td className="num">{money(product.unitCost)}</td>
                <td className="num">
                  <input
                    className="num"
                    type="number"
                    min={0}
                    step={1}
                    value={draft.production[product.code] ?? 0}
                    onChange={(e) => setIn("production", product.code, Number(e.target.value))}
                  />
                </td>
                <td className="num">{plain(workers.perProduct[product.code] ?? 0)}</td>
                <td className="num">
                  <input
                    className="num"
                    type="number"
                    min={1}
                    step="any"
                    value={draft.price[product.code] ?? product.price}
                    onChange={(e) => setIn("price", product.code, Number(e.target.value))}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className={overCapacity ? "error" : "muted"} style={{ marginBottom: 0 }}>
          {t("player.workforce", { used: plain(workers.needed), available: plain(workers.available) })}
          {overCapacity ? " — hire production staff or buy machines, the round will be refused otherwise." : "."}
        </p>
      </section>

      <section className="card">
        <h2>{t("player.marketing")}</h2>
        <div className="row">
          {context.channels.map((channel) => (
            <label key={channel.code}>
              {channel.name}
              <input
                type="number"
                min={0}
                step="any"
                value={draft.marketing[channel.code] ?? 0}
                onChange={(e) => setIn("marketing", channel.code, Number(e.target.value))}
              />
            </label>
          ))}
        </div>
      </section>

      <section className="card">
        <h2>{t("player.capacity")}</h2>
        <div className="row">
          <label>
            {t("player.staff")}
            <input
              type="number"
              min={0}
              step={1}
              value={draft.manpower}
              onChange={(e) => set("manpower", Number(e.target.value))}
            />
          </label>
          <label>
            Buy machines (workers of capacity, {money(context.machineCostPerWorker)} each)
            <input
              type="number"
              min={0}
              step={1}
              value={draft.capacity}
              onChange={(e) => set("capacity", Number(e.target.value))}
            />
          </label>
        </div>
        <p className="muted" style={{ marginBottom: 0 }}>
          Fixed staff on the payroll:{" "}
          {Object.entries(context.staff)
            .filter(([role]) => role !== "PRODUCTION")
            .map(([role, count]) => `${role.toLowerCase()} ${count}`)
            .join(" · ")}{" "}
          · fixed cost {money(context.fixedCostPerMonth)} per month
        </p>
      </section>

      <section className="card">
        <h2>{t("player.rnd")}</h2>
        {context.rndProjects.map((project) => (
          <div className="project" key={project.code}>
            <div>
              <strong>{project.name}</strong>
              <p className="muted">
                {project.advantageType.replaceAll("_", " ").toLowerCase()} {plain(project.advantageValue)} ·{" "}
                {project.durationMonths} months · published estimate {money(project.hintLow)}–{money(project.hintHigh)}
              </p>
              <p className="muted">
                Invested so far {money(project.invested)} over {project.monthsInvested} month(s) · {project.status.toLowerCase()}
                {project.overfunding === "REFUND" ? " · over-funding refunded" : ""}
              </p>
            </div>
            <label className="project-input">
              {t("player.rndThisMonth")}
              <input
                type="number"
                min={0}
                step="any"
                value={draft.rnd[project.code] ?? 0}
                onChange={(e) => setIn("rnd", project.code, Number(e.target.value))}
              />
            </label>
          </div>
        ))}
      </section>

      <section className="card">
        <h2>{t("player.review")}</h2>
        <label className="inline">
          <input
            type="checkbox"
            checked={draft.marketReport}
            onChange={(e) => set("marketReport", e.target.checked)}
            style={{ width: "auto" }}
          />
          Buy this month's market review for {money(context.marketReportCost)}
        </label>
        <p className="muted">
          Demand index {plain(context.market.demandIndex)} · cost index {plain(context.market.costIndex)} · wages{" "}
          {plain(context.market.wageIndex)}
        </p>
        <button type="button" onClick={buyReport} disabled={!context.submitted}>
          {t("player.showReview")}
        </button>
        {report && (
          <ul className="forecast">
            {report.forecast.map((row) => (
              <li key={row.round}>
                <span className="muted">Round {row.round}</span>{" "}
                {row.outlook} · {row.trendLabel} · demand{" "}
                {plain(row.demandIndex)} · costs {plain(row.costIndex)}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card submit-bar">
        <div>
          <p className="muted" style={{ margin: 0 }}>
            Committed cash this month (marketing, R&amp;D, machines, review): <strong className={overCash ? "negative" : ""}>{money(committed)}</strong>{" "}
            of {money(context.cash)} · payroll, materials and holding cost are charged when the round is computed.
          </p>
        </div>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? t("player.submitting") : context.submitted ? t("player.replace") : t("player.submit", { round: context.round })}
        </button>
      </section>

      {statements.length > 0 && (
        <section className="card">
          <div className="row">
            <h2>{t("player.results")}</h2>
            <label className="inline">
              {t("debrief.round")}
              <select
                value={resultRound ?? statements[statements.length - 1].round}
                onChange={(e) => setResultRound(Number(e.target.value))}
              >
                {statements.map((row) => (
                  <option key={row.round} value={row.round}>
                    {row.round}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="muted">{t("player.resultsHint")}</p>
          {(() => {
            const row =
              statements.find((entry) => entry.round === (resultRound ?? statements[statements.length - 1].round)) ??
              statements[statements.length - 1];
            return <StatementDetail row={row} />;
          })()}
        </section>
      )}
    </form>
  );
}
