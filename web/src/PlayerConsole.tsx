import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useI18n, enumLabel } from "./i18n";
import { describeError } from "./errors";
import StatementDetail from "./StatementDetail";
import { StatementDialog, type StatementKind } from "./FinancialStatements";
import NumberInput from "./NumberInput";
import ClockBadge from "./ClockBadge";
import ClassShowPopup from "./ClassShowPopup";
import {
  api,
  ApiError,
  downloadDebrief,
  eventStreamUrl,
  downloadEvaluation,
  type DecisionFormContext,
  type DecisionInput,
  type ClassShow,
  type MarketForecast,
  type MarketPie,
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
    // D126: a read round is locked ticked — the draft itself carries the purchase, so every payload this
    // form can build includes it. A plan merely submitted with the tick restores it from `saved` and may
    // still be unticked, because nothing has been read yet.
    marketReport: context.reportRead ? true : saved?.marketReport ?? false,
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

/**
 * D127: which sentence explains this month. Derived from the numbers the report actually shows, so the words
 * cannot describe a movement the figures do not have — the defect this replaced was a bare list of indices.
 */
function impactKey(row: { demandIndex: number; costIndex: number }): string {
  const demand = Number(row.demandIndex);
  const cost = Number(row.costIndex);
  if (cost >= 1.02) return "player.impactCostUp";
  if (cost <= 0.98) return "player.impactCostDown";
  if (demand >= 1.02) return "player.impactDemandUp";
  if (demand <= 0.98) return "player.impactDemandDown";
  return "player.impactSteady";
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
  /** D117: the chart the facilitator has pushed to this screen, and the pies it draws. */
  const [show, setShow] = useState<ClassShow>({ open: false });
  const [showPies, setShowPies] = useState<MarketPie[]>([]);
  /** A round the trainee has closed. Re-opening the show clears it; a new round opens fresh. */
  const [showDismissed, setShowDismissed] = useState<number | null>(null);
  /** D61: the round whose form arrived pre-filled by the teaching guide, if any. */
  const [guidedRound, setGuidedRound] = useState<number | null>(null);
  const [downloading, setDownloading] = useState<"pdf" | "xlsx" | null>(null);
  /** D76: their own development report, separate from the round-by-round debrief. */
  const [downloadingReport, setDownloadingReport] = useState(false);
  const [resultRound, setResultRound] = useState<number | null>(null);
  /** D91: which of the three reports is open in its pop-up, if any. */
  const [openReport, setOpenReport] = useState<StatementKind | null>(null);
  const roundRef = useRef<number | null>(null);
  /** D96: the newest round already put on screen, so a freshly computed one opens itself. */
  const latestSeen = useRef<number | null>(null);

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
        const latest = history.length > 0 ? history[history.length - 1].round : null;
        if (latest !== latestSeen.current) {
          // A round has just been computed: open its detail without waiting to be asked. Reading an
          // older round is still possible — clicking a row selects it until the next result lands.
          latestSeen.current = latest;
          setResultRound(latest);
        } else {
          setResultRound((current) => {
            const stillThere = current !== null && history.some((row) => row.round === current);
            return stillThere ? current : latest;
          });
        }
      } catch {
        // Results are a convenience: a trainee must still be able to decide if this call fails.
        setStatements([]);
      }
      try {
        setStandings(await api.standings(active.id));
      } catch {
        setStandings([]);
      }
      // D117: is the facilitator showing a chart? The pies are refused by the server unless it is, so
      // this state and that call are one permission — never a chart nobody opened.
      try {
        const pushed = await api.show(active.id);
        setShow(pushed);
        if (pushed.open && pushed.round !== undefined) {
          setShowPies(await api.marketPies(active.id, pushed.round));
        } else {
          setShowPies([]);
        }
      } catch {
        setShow({ open: false });
        setShowPies([]);
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
  // reload. The chart push is the exception — a facilitator pressing "show" and waiting ten seconds is
  // a facilitator talking to a room that is not looking at their screen yet (D117).
  useEffect(() => {
    const timer = window.setInterval(() => void load(), 10_000);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => {
    const runId = run?.id;
    if (!runId) return;
    let source: EventSource | null = null;
    let retry: number | undefined;
    let stopped = false;
    const connect = async () => {
      try {
        const { ticket } = await api.streamTicket(runId);
        if (stopped) return;
        source = new EventSource(eventStreamUrl(runId, ticket));
        for (const name of ["show.opened", "show.closed"]) {
          source.addEventListener(name, () => void load());
        }
        source.onerror = () => {
          source?.close();
          if (!stopped) retry = window.setTimeout(connect, 2000);
        };
      } catch {
        if (!stopped) retry = window.setTimeout(connect, 2000);
      }
    };
    void connect();
    return () => {
      stopped = true;
      window.clearTimeout(retry);
      source?.close();
    };
  }, [run?.id, load]);

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
      // D123: the report has been *read*. The charge is already committed — the purchase travelled with
      // the plan that was submitted — so the box must not stay ticked: a ticked box means "charge this
      // month with the next submission", and leaving it on bills the same report twice.
      setDraft((current) => (current ? { ...current, marketReport: false } : current));
      setProblem(null);
    } catch (error) {
      setProblem(errorText(error, t));
    }
  };

  const signOut = (
    <div className="brand">
      <h1>bizsim · {team?.companyName ?? t("player.traineeFallback")}</h1>
      <button className="link muted" onClick={onSignOut}>
        {t("app.signOut")}
      </button>
    </div>
  );

  // D117: the push is an overlay, so it has to ride on every screen this console can be showing. A class
  // whose round is closed reads results while the facilitator talks over the pies — that is the state it
  // matters in most, and a pop-up wired only into the decision form would never appear there.
  const showBar = show.open ? (
    <div className="card show-bar">
      <strong>{t("player.showUp", { round: show.round ?? 0 })}</strong>
      <div className="buttons">
        <button type="button" className="link" onClick={() => setShowDismissed(null)}>
          {t("player.showOpen")}
        </button>
      </div>
    </div>
  ) : null;

  const showPopup =
    show.open && show.round !== undefined && showPies.length > 0 && showDismissed !== show.round ? (
      <ClassShowPopup
        pies={showPies}
        round={show.round}
        onClose={() => setShowDismissed(show.round ?? null)}
      />
    ) : null;

  if (!run) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>{t("player.noRunTitle")}</h2>
          <p className="muted">{t("player.noRunBody")}</p>
        </div>
      </div>
    );
  }

  if (!team) {
    return (
      <div className="player">
        {signOut}
        <div className="card">
          <h2>{t("player.unassignedTitle")}</h2>
          <p className="muted">{t("player.unassignedBody")}</p>
        </div>
      </div>
    );
  }

  // Results are readable on the waiting screen as well: a class whose session has finished should
  // still see its own statements, and nothing here submits anything.
  const shownRound =
    resultRound ?? (statements.length > 0 ? statements[statements.length - 1].round : 0);
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
                  <th className="num">{t("debrief.cumulative")}</th>
                  <th className="num">{t("debrief.roce")}</th>
                  <th className="num">{t("debrief.cash")}</th>
                  <th className="num">{t("debrief.profit")}</th>
                </tr>
              </thead>
              <tbody>
                {standings.map((row) => (
                  <tr key={row.teamId} className={row.own ? "own-row" : undefined}>
                    <td className="num">{row.rank}</td>
                    <td>{row.company}</td>
                    <td className="num">{money(Number(row.cumulativeProfit ?? 0))}</td>
                    <td className="num">{plain(Number(row.roce ?? 0), 1)}%</td>
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
          {/* D91: the three reports open in their own pop-up, for the round selected below. */}
          <button onClick={() => setOpenReport("pnl")}>{t("pnl.title")}</button>
          <button onClick={() => setOpenReport("cash")}>{t("cash.title")}</button>
          <button onClick={() => setOpenReport("balance")}>{t("balance.title")}</button>
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
            statements.find((entry) => entry.round === shownRound) ??
            statements[statements.length - 1];
          return (
            <>
              <h3 className="pick-head">{t("player.resultsRound", { round: row.round })}</h3>
              <StatementDetail row={row} />
            </>
          );
        })()}

        {openReport && run && team && (
          <StatementDialog
            runId={run.id}
            teamId={team.id}
            round={shownRound}
            statement={openReport}
            onClose={() => setOpenReport(null)}
          />
        )}
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
        {showBar}
        {resultsSection}
        {showPopup}
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

      {/* D117: the chart the facilitator pushed, and the way back to it while the show is up. */}
      {showBar}

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
              {/* D116/D120: the outlook's label only — the server's code (STEADY) is an identifier, not a
                  word a trainee reads, and it was printing beside its own translation. */}
              {t("player.round", { round: context.round, total: run.rounds })} · {context.market.trendLabel}
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
              <strong>{plain(context.machineWorkers ?? 0, 0)}</strong>
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

      {/* D83: the market environment, in plain words. The economy is not a mystery dial — the
          player is told which customers exist, what this round did to them, and why. */}
      <section className="card market-environment">
        <div className="run-head">
          <h2>{t("player.marketEnvironment")}</h2>
          <span className="muted">
            {context.market.headline ?? context.market.trendLabel} ·{" "}
            {t(`market.outlook.${context.market.outlook}`)}
          </span>
        </div>
        {context.market.description && <p>{context.market.description}</p>}
        <table>
          <thead>
            <tr>
              <th>{t("player.customerClass")}</th>
              <th>{t("player.product")}</th>
              <th className="num">{t("player.classSize")}</th>
              <th className="num">{t("player.purchasingPower")}</th>
            </tr>
          </thead>
          <tbody>
            {(context.market.segments ?? []).map((segment) => (
              <tr key={segment.code}>
                <td>
                  {segment.name}
                  {segment.description && (
                    <div className="muted">
                      <small>{segment.description}</small>
                    </div>
                  )}
                </td>
                <td>{segment.product ?? "—"}</td>
                <td className="num">
                  {plain(Math.round(segment.baseSize * segment.sizeIndex))}
                  <div className="muted">
                    <small>×{plain(segment.sizeIndex)}</small>
                  </div>
                </td>
                <td className="num">
                  ×{plain(segment.purchasingPower)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {context.market.events.length > 0 && (
          <ul className="forecast">
            {context.market.events.map((event) => (
              <li key={event.code}>
                {event.category && event.category !== "OTHER" && (
                  <span className="muted">{t(`category.${event.category}`)} · </span>
                )}
                <strong>{event.name}</strong>
                {event.description ? ` — ${event.description}` : ""}
              </li>
            ))}
          </ul>
        )}
      </section>

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
                {/* D104/D105: stock is a count of items. `plain` defaults to two decimals, so a
                    whole stock of 48 printed as "48.00" — and a leftover fractional row from before the
                    fix printed "48.67", which is what a trainee reported. Zero decimals, always. */}
                <td className="num">{plain(product.stock, 0)}</td>
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
                {/* D105: workforce need is measured in people, and half a person is not a plan. */}
                <td className="num">{plain(workers.perProduct[product.code] ?? 0, 0)}</td>
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
          {t("player.workforce", { used: plain(workers.needed, 0), available: plain(workers.available, 0) })}
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
        {/* The engine's marketing is one company-wide reach number: it lifts your share in every
            product and every customer class alike. Saying so here stops a trainee (and a trainer)
            reading the three channels as three audiences. */}
        <p className="muted">{t("player.marketingScope")}</p>
        <div className="row">
          {/* D127: what each channel's money buys — a trainee betting on a return needs to know whether it is
              bought media, a distributor's shelf, or sales visits. Templates without a description show none. */}
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
              {channel.description && (
                <small className="muted channel-what">{channel.description}</small>
              )}
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
                {t("player.projectTerms", {
                  effect: `${enumLabel(t, "effect", project.advantageType)} ${plain(project.advantageValue)}`,
                  months: project.durationMonths,
                  low: money(project.hintLow),
                  high: money(project.hintHigh),
                })}
              </p>
              <p className="muted">
                {t("player.investedSoFar", {
                  invested: money(project.invested),
                  months: project.monthsInvested,
                  status: enumLabel(t, "rndStatus", project.status),
                })}
                {project.overfunding === "REFUND" ? t("player.overfundingRefunded") : ""}
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
          {/* D126: the box locks once the review has been READ — disabled and ticked, so no submission this
              form can send omits it. Before that it is an ordinary choice: tick, submit, untick, submit
              again, and the charge follows whatever the last plan said. The server stores what is sent. */}
          <input
            type="checkbox"
            checked={context.reportRead ? true : draft.marketReport}
            disabled={Boolean(context.reportRead)}
            onChange={(e) => {
              if (context.reportRead) return;
              set("marketReport", e.target.checked);
            }}
            style={{ width: "auto" }}
          />
          {t("player.buyReview", { cost: money(context.marketReportCost) })}
        </label>
        {context.reportRead && (
          <p className="muted">{t("player.reviewBought")}</p>
        )}
        <p className="muted">
          {t("player.marketIndices", {
            demand: plain(context.market.demandIndex),
            cost: plain(context.market.costIndex),
            wage: plain(context.market.wageIndex),
          })}
        </p>
        {/* D123: saying why the button is dark. The old screen just left it disabled with no reason,
            which reads as a broken control when the plan was submitted seconds ago. */}
        {!context.submitted && <p className="muted">{t("player.reviewNeedsPlan")}</p>}
        {/* D123: the report is bought with the submitted plan, so the button follows the plan's state —
            the same `submitted` the context reports, read where it is used. */}
        <button type="button" onClick={buyReport} disabled={!context.submitted}>
          {t("player.showReview")}
        </button>
        {report && (
          <>
            {/* D127: the indices alone were three numbers with no reading. Each month now says what the
                numbers mean for the decision in front of the trainee, and the months already scheduled
                name the events the frozen deck will start. */}
            <ul className="forecast forecast-months">
              {report.forecast.map((row) => (
                <li key={row.round}>
                  <span className="muted">{t("player.forecastRound", { round: row.round })}</span>{" "}
                  {t("player.forecastRow", {
                    outlook: enumLabel(t, "outlook", row.outlook),
                    trend: row.trendLabel,
                    demand: plain(row.demandIndex),
                    costs: plain(row.costIndex),
                  })}
                  <p className="muted forecast-impact">
                    {t("player.forecastSummary", {
                      pct: plain(row.demandIndex * 100, 0),
                      cost: plain(row.costIndex * 100, 0),
                      impact: t(impactKey(row)),
                    })}
                  </p>
                  {row.events && row.events.length > 0 && (
                    <ul className="forecast-events">
                      {row.events.map((event) => (
                        <li key={event.code}>
                          <strong>{event.name}</strong>
                          {event.description ? ` — ${event.description}` : ""}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
            {/* D127: what the purchase is worth in decision terms, not in abstract value. */}
            <p className="muted forecast-worth">
              {t("player.forecastHours", { cost: money(context.marketReportCost) })}
            </p>
          </>
        )}
      </section>

      <section className="card submit-bar">
        <div>
          <p className="muted" style={{ margin: 0 }}>
            {t("player.committedCash")}: <strong className={overCash ? "negative" : ""}>{money(committed)}</strong>{" "}
            {t("player.committedOf", { cash: money(context.cash) })}
          </p>
        </div>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? t("player.submitting") : context.submitted ? t("player.replace") : t("player.submit", { round: context.round })}
        </button>
      </section>

      {resultsSection}

      {showPopup}
    </form>
  );
}
