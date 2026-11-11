import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "./i18n";
import { describeError } from "./errors";
import StatementDetail from "./StatementDetail";
import NumberInput from "./NumberInput";
import ClockBadge from "./ClockBadge";
import {
  api,
  ApiError,
  downloadDebrief,
  downloadEvaluation,
  type DecisionFormContext,
  type DecisionInput,
  type MarketForecast,
  type Run,
  type StandingsRow,
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
  /** D15: money per product spent raising its quality level this month. */
  quality: Record<string, number>;
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

  const quality: Record<string, number> = {};
  for (const product of context.products) {
    const hit = (saved?.quality ?? []).find((r) => r.product === product.code);
    quality[product.code] = hit ? Number(hit.invest) : 0;
  }

  return {
    production,
    price,
    marketing,
    rnd,
    quality,
    manpower: saved?.manpower?.production ?? context.staff.PRODUCTION ?? 0,
    // The API speaks money (D77): the stored figure is what the machines cost, the field shows how
    // many. Converting here keeps the form in machines and the contract in currency.
    capacity: saved?.capacityPurchase
      ? Number(saved.capacityPurchase) / Math.max(1, context.machineCostPerWorker)
      : 0,
    marketReport: saved?.marketReport ?? false,
  };
}

function toPayload(context: DecisionFormContext, draft: Draft): DecisionInput {
  return {
    production: context.products.map((p) => ({ product: p.code, units: draft.production[p.code] ?? 0 })),
    price: context.products.map((p) => ({ product: p.code, unitPrice: draft.price[p.code] ?? p.price })),
    marketing: context.channels.map((c) => ({ channel: c.code, spend: draft.marketing[c.code] ?? 0 })),
    manpower: { production: draft.manpower },
    // A finished project takes no more money; sending its row as 0 keeps the shape complete without
    // proposing an investment the server must refuse (D35).
    rnd: context.rndProjects.map((r) => ({
      project: r.code,
      invest: r.status === "ACTIVE" ? (draft.rnd[r.code] ?? 0) : 0,
    })),
    // Sent only when the template sells the mechanic: a template that does not would refuse the
    // money with quality.disabled (D15).
    quality: context.qualityUpgrade
      ? context.products.map((p) => ({ product: p.code, invest: draft.quality[p.code] ?? 0 }))
      : [],
    // D77: `capacityPurchase` is money, not a count — the engine divides by the machine cost to get
    // the workers of capacity added, so sending the count bought nothing and charged single units.
    capacityPurchase: draft.capacity * context.machineCostPerWorker,
    marketReport: draft.marketReport,
  };
}

/* ------------------------------------------------------------------ screen */

export default function PlayerConsole({ userId, onSignOut }: { userId: number; onSignOut: () => void }) {
  const { t, money, number: plain, applySession, locale } = useI18n();
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
  /** The class table: rank, company and profit — the only competitor figures a trainee may see (D51). */
  const [standings, setStandings] = useState<StandingsRow[]>([]);
  /** D61: the round whose form arrived pre-filled by the teaching guide, if any. */
  const [guidedRound, setGuidedRound] = useState<number | null>(null);
  const [downloading, setDownloading] = useState<"pdf" | "xlsx" | null>(null);
  /** D76: their own development report, separate from the round-by-round debrief. */
  const [downloadingReport, setDownloadingReport] = useState(false);
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
      // The package decides the class language; it applies for this session only.
      applySession(active.locale);
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
      try {
        setStandings(await api.standings(active.id));
      } catch {
        setStandings([]);
      }
      if (!next.roundOpen) {
        setNotice(null);
      }
      // A new round must reset the form; a poll inside the same round must not wipe edits.
      if (roundRef.current !== next.round) {
        roundRef.current = next.round;
        setDraft(draftFrom(next));
        setGuidedRound(null);
        setReport(null);
        // D68: a confirmation belongs to the round it was about; a new round must not show the last
        // one's "submitted" over a form that has not been submitted.
        setNotice(null);
        // D61: the opening rounds come with a suggested plan for a human seat. Only when the company
        // has nothing stored for this round — a saved draft is the trainee's own work, never replaced.
        if (!next.decision) {
          try {
            const suggestion = await api.suggestion(active.id, mine.id);
            if (suggestion.guided && suggestion.payload) {
              setDraft(draftFrom({ ...next, decision: suggestion.payload }));
              setGuidedRound(next.round);
            }
          } catch {
            // No guide is a normal round, not an error: leave the form blank.
          }
        }
      }
    } catch (error) {
      setProblem(errorText(error, t));
    }
  }, [userId, applySession, t]);

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
    const quality = context.qualityUpgrade
      ? context.products.reduce((sum, p) => sum + (draft.quality[p.code] ?? 0), 0)
      : 0;
    const machines = draft.capacity * context.machineCostPerWorker;
    const review = draft.marketReport ? context.marketReportCost : 0;
    return marketing + rnd + quality + machines + review;
  }, [context, draft]);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((current) => (current ? { ...current, [key]: value } : current));

  const setIn = (group: "production" | "price" | "marketing" | "rnd" | "quality", code: string, value: number) =>
    setDraft((current) =>
      current ? { ...current, [group]: { ...current[group], [code]: value } } : current,
    );

  /** The trainee's own report: every computed round, their company only (D43/D21). */
  const download = async (format: "pdf" | "xlsx") => {
    if (!run) return;
    setDownloading(format);
    setProblem(null);
    try {
      await downloadDebrief(run.id, format, locale);
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setDownloading(null);
    }
  };

  /** D76: the term report for this company, in the class language. */
  const developmentReport = async () => {
    if (!run || !team) return;
    setDownloadingReport(true);
    setProblem(null);
    try {
      await downloadEvaluation(run.id, team.id, locale);
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setDownloadingReport(false);
    }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!run || !team || !context || !draft) return;
    setBusy(true);
    setProblem(null);
    setShortfall(null);
    setNotice(null);
    try {
      await api.submitDecision(run.id, team.id, toPayload(context, draft));
      setNotice(t("player.submitted", { round: context.round }));
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

  // Results are readable on the waiting screen as well: a class whose session has finished should
  // still see its own statements, and nothing here submits anything.
  const resultsSection =
    statements.length > 0 ? (
      <section className="card">
        {/* No round dropdown: the summary rows below are the selector (click a round to open it). */}
        <h2>{t("player.results")}</h2>
        <p className="muted">{t("player.resultsHint")}</p>
        {standings.length > 0 && (
          <>
            <h3>{t("player.standings")}</h3>
            <p className="muted">{t("player.standingsHint")}</p>
            <table>
              <thead>
                <tr>
                  <th className="num">{t("player.rank")}</th>
                  <th>{t("debrief.company")}</th>
                  <th className="num">{t("debrief.cash")}</th>
                  <th className="num">{t("debrief.profit")}</th>
                </tr>
              </thead>
              <tbody>
                {standings.map((row) => (
                  <tr key={row.teamId} className={row.own ? "own-row" : undefined}>
                    <td className="num">{row.rank}</td>
                    <td>{row.company}</td>
                    <td className="num">{money(Number(row.cash ?? 0))}</td>
                    <td className="num">{money(Number(row.profit ?? 0))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
        <div className="buttons">
          <button onClick={() => void download("pdf")} disabled={downloading !== null}>
            {downloading === "pdf" ? t("debrief.exporting") : t("player.downloadPdf")}
          </button>
          <button onClick={() => void download("xlsx")} disabled={downloading !== null}>
            {downloading === "xlsx" ? t("debrief.exporting") : t("player.downloadExcel")}
          </button>
          {/* D76: the development report answers "how did I run it", the downloads above answer
              "what happened each round". */}
          <button onClick={() => void developmentReport()} disabled={downloadingReport}>
            {downloadingReport ? t("debrief.exporting") : t("player.developmentReport")}
          </button>
        </div>
        <p className="muted">{t("player.downloadHint")}</p>

        {/* The facilitator's debrief, for one company: a summary row per computed round, then the
            chosen round's detail below. Competitors' figures and the market share stay on the
            facilitator's screen (D21, D37). */}
        <table>
          <thead>
            <tr>
              <th>{t("debrief.round")}</th>
              <th className="num">{t("debrief.revenue")}</th>
              <th className="num">{t("debrief.cost")}</th>
              <th className="num">{t("debrief.profit")}</th>
              <th className="num">{t("debrief.cash")}</th>
              <th className="num">{t("debrief.lostSales")}</th>
              <th className="num">{t("debrief.capacity")}</th>
            </tr>
          </thead>
          <tbody>
            {statements.map((row) => {
              const chosen = row.round === (resultRound ?? statements[statements.length - 1].round);
              return (
                <tr
                  key={row.round}
                  className={chosen ? "active" : ""}
                  style={{ cursor: "pointer" }}
                  onClick={() => setResultRound(row.round)}
                >
                  <td>{row.round}</td>
                  <td className="num">{money(Number(row.revenue ?? 0))}</td>
                  <td className="num">{money(Number(row.revenue ?? 0) - Number(row.profit ?? 0))}</td>
                  <td className="num">
                    <strong>{money(Number(row.profit ?? 0))}</strong>
                  </td>
                  <td className="num">{money(Number(row.cash_after ?? 0))}</td>
                  <td className="num">{plain(Number(row.lost_sales ?? 0), 0)}</td>
                  <td className="num">
                    {plain(Number(row.capacity_used ?? 0), 0)} /{" "}
                    {plain(Number(row.capacity_available ?? 0), 0)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>

        {(() => {
          const row =
            statements.find((entry) => entry.round === (resultRound ?? statements[statements.length - 1].round)) ??
            statements[statements.length - 1];
          return (
            <>
              <h3 className="pick-head">{t("player.resultsRound", { round: row.round })}</h3>
              <StatementDetail row={row} />
            </>
          );
        })()}
      </section>
    ) : null;

  if (!context || !draft) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>{team.companyName}</h2>
          <ClockBadge run={run} />
          <p className="muted">
            {run.currentRound === 0 ? t("player.waitingRound1") : t("player.loadingForm")}
          </p>
          {problem && <p className="error">{problem}</p>}
        </div>
      </div>
    );
  }

  // Nothing is open to decide: the trainee gets the waiting card, not a dashboard with a
  // "Submit round 0" button that the server would refuse with round.not_open.
  if (!context.roundOpen) {
    const finished = run.status !== "OPEN";
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>{team.companyName}</h2>
          <ClockBadge run={run} />
          <p className="muted">
            {finished
              ? t("player.sessionFinished")
              : run.currentRound === 0
                ? t("player.waitingRound1")
                : t("player.waitingNext", { round: run.currentRound + 1 })}
          </p>
          {!finished && (
            <p className="muted" style={{ marginBottom: 0 }}>
              <small>{t("player.waitingHint")}</small>
            </p>
          )}
          {problem && <p className="error">{problem}</p>}
        </div>
        {resultsSection}
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

      {guidedRound === context.round && (
        <div className="card guided">
          <p style={{ margin: 0 }}>
            {t("player.guided", { round: context.round })}
          </p>
          <div className="buttons">
            <button
              type="button"
              className="ghost"
              onClick={() => {
                setDraft(draftFrom(context));
                setGuidedRound(null);
              }}
            >
              {t("player.guidedBlank")}
            </button>
          </div>
        </div>
      )}

      <div className="card">
        <div className="run-head">
          <div>
            <h2 style={{ marginBottom: ".2rem" }}>{team.companyName}</h2>
            <p className="muted" style={{ margin: 0 }}>
              {t("player.round", { round: context.round, total: run.rounds })} · {context.market.outlook} ·{" "}
              {context.market.trendLabel}
            </p>
            <ClockBadge run={run} />
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
          // D68: the message names the round it is about. Without that it sat above a header showing a
          // later round — or a form the trainee had not sent — and the two read as contradictory.
          <p className="banner good">{t("player.submittedPersistent", { round: context.round })}</p>
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
                  <NumberInput
                    className="num"
                    decimals={0}
                    min={0}
                    value={draft.production[product.code] ?? 0}
                    onChange={(units) => setIn("production", product.code, units)}
                  />
                </td>
                <td className="num">{plain(workers.perProduct[product.code] ?? 0)}</td>
                <td className="num">
                  <NumberInput
                    className="num"
                    decimals={2}
                    min={0}
                    value={draft.price[product.code] ?? product.price}
                    onChange={(price) => setIn("price", product.code, price)}
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

      {context.qualityUpgrade && (
        <section className="card">
          <h2>{t("player.quality")}</h2>
          <p className="muted">{t("player.qualityHint", { cost: money(context.qualityCostPerPoint) })}</p>
          <table>
            <thead>
              <tr>
                <th>{t("player.product")}</th>
                <th className="num">{t("player.qualityLevel")}</th>
                <th className="num">{t("player.qualityInvest")}</th>
                <th className="num">{t("player.qualityGain")}</th>
              </tr>
            </thead>
            <tbody>
              {context.products.map((product) => {
                const invest = draft.quality[product.code] ?? 0;
                const gain =
                  context.qualityCostPerPoint > 0 ? invest / context.qualityCostPerPoint : 0;
                return (
                  <tr key={product.code}>
                    <td>
                      {product.name} <small className="muted">{product.code}</small>
                    </td>
                    <td className="num">{plain(product.quality)}</td>
                    <td className="num">
                      <NumberInput
                        className="num"
                        decimals={2}
                        min={0}
                        value={invest}
                        onChange={(value) => setIn("quality", product.code, value)}
                      />
                    </td>
                    <td className="num">+{gain.toFixed(2)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      )}

      <section className="card">
        <h2>{t("player.marketing")}</h2>
        <div className="row">
          {context.channels.map((channel) => (
            <label key={channel.code}>
              {channel.name}
              <NumberInput
                className="num"
                decimals={2}
                min={0}
                value={draft.marketing[channel.code] ?? 0}
                onChange={(spend) => setIn("marketing", channel.code, spend)}
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
            <NumberInput
              className="num"
              decimals={0}
              min={0}
              value={draft.manpower}
              onChange={(staff) => set("manpower", staff)}
            />
          </label>
          <label>
            {t("player.buyMachines", { cost: money(context.machineCostPerWorker) })}
            <NumberInput
              className="num"
              decimals={0}
              min={0}
              value={draft.capacity}
              onChange={(machines) => set("capacity", machines)}
            />
          </label>
        </div>
        <p className="muted" style={{ marginBottom: 0 }}>
          {t("player.fixedStaff")}{" "}
          {Object.entries(context.staff)
            .filter(([role]) => role !== "PRODUCTION")
            .map(([role, count]) => `${role.toLowerCase()} ${count}`)
            .join(" · ")}{" "}
          · {t("player.fixedCostMonth", { cost: money(context.fixedCostPerMonth) })}
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
                {t("player.investedSoFar", {
                  invested: money(project.invested),
                  months: project.monthsInvested,
                  status: project.status.toLowerCase(),
                })}
                {project.overfunding === "REFUND" ? " · over-funding refunded" : ""}
              </p>
            </div>
            <label className="project-input">
              {t("player.rndThisMonth")}
              {/* COMPLETED or FAILED: nothing more can be invested, so the field is closed rather than
                  letting a trainee type a number the server will refuse (D35). */}
              <NumberInput
                className="num"
                decimals={2}
                min={0}
                disabled={project.status !== "ACTIVE"}
                value={project.status === "ACTIVE" ? (draft.rnd[project.code] ?? 0) : 0}
                onChange={(invest) => setIn("rnd", project.code, invest)}
              />
              {project.status !== "ACTIVE" && (
                <small className="muted">{t("player.rndFinished")}</small>
              )}
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
          {t("player.buyReview", { cost: money(context.marketReportCost) })}
        </label>
        <p className="muted">
          {t("player.marketIndices", {
            demand: plain(context.market.demandIndex),
            cost: plain(context.market.costIndex),
            wage: plain(context.market.wageIndex),
          })}
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
            {t("player.committedCash")}: <strong className={overCash ? "negative" : ""}>{money(committed)}</strong>{" "}
            of {money(context.cash)} · payroll, materials and holding cost are charged when the round is computed.
          </p>
        </div>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? t("player.submitting") : context.submitted ? t("player.replace") : t("player.submit", { round: context.round })}
        </button>
      </section>

      {resultsSection}
    </form>
  );
}
