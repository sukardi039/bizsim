import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api,
  downloadDebrief,
  downloadEvaluation,
  type DecisionInput,
  type RunPlayer,
  type ScoreRow,
  type StatementRow,
  type Team,
} from "./api";
import { describeError } from "./errors";
import StatementDetail from "./StatementDetail";
import DebriefSlides from "./DebriefSlides";
import { useI18n } from "./i18n";

/**
 * The debrief (S5): what the facilitator puts on screen after a round.
 *
 * One row per company for the chosen round — revenue, cost, profit, cash, share, lost sales and how
 * hard the plant was pushed — plus the class totals. The numbers come from the round's own
 * statements, so what the class sees here is what the engine charged them, not a re-derivation.
 */
export default function DebriefPanel({
  runId,
  teams,
  currentRound,
  players,
}: {
  runId: number;
  teams: Team[];
  currentRound: number;
  /** Who is seated where: performance is per company, but the class asks "whose company?". */
  players: RunPlayer[];
}) {
  const { t, money, number, locale } = useI18n();
  const [round, setRound] = useState(Math.max(1, currentRound || 1));
  const [board, setBoard] = useState<ScoreRow[]>([]);
  const [statements, setStatements] = useState<Record<number, StatementRow[]>>({});
  /** What each company sent for the selected round, so the detail can show cause before result (D42). */
  const [decisions, setDecisions] = useState<Record<number, DecisionInput | null>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [slides, setSlides] = useState(false);
  const [downloading, setDownloading] = useState<"pdf" | "xlsx" | null>(null);
  /** D76: which company's development report is being fetched. */
  const [reportFor, setReportFor] = useState<number | null>(null);

  // D69: the statements are the record of which rounds have been computed, so they are read for the
  // whole run and not for the selected round. Reading them only after a scoreboard call made the
  // selector depend on the very round it was supposed to police.
  const loadStatements = useCallback(async () => {
    try {
      const perTeam = await Promise.all(
        teams.map(async (team) => [team.id, await api.statements(runId, team.id)] as const),
      );
      setStatements(Object.fromEntries(perTeam));
    } catch {
      setStatements({});
    }
  }, [runId, teams]);

  useEffect(() => {
    void loadStatements();
  }, [loadStatements]);

  /** Every round the class has actually computed, ascending. */
  const computedRounds = useMemo(() => {
    const seen = new Set<number>();
    for (const rows of Object.values(statements)) {
      for (const entry of rows) {
        if (entry.round > 0) seen.add(entry.round);
      }
    }
    return [...seen].sort((a, b) => a - b);
  }, [statements]);

  /**
   * The computed rounds as one string, used as a dependency: a stable value that changes only when
   * the set changes, which is the event the debrief table has to react to (see `load`).
   */
  const computedKey = computedRounds.join(",");

  const load = useCallback(async () => {
    setProblem(null);
    try {
      const rows = await api.scoreboard(runId, round);
      setBoard(rows);
      // A separate call on purpose: the statements are the result and must still render if the
      // decisions cannot be read.
      try {
        const submissions = await api.submissions(runId, round);
        setDecisions(
          Object.fromEntries(submissions.companies.map((entry) => [entry.teamId, entry.decision])),
        );
      } catch {
        setDecisions({});
      }
    } catch (error) {
      setProblem(describeError(error, t));
      setBoard([]);
    }
    // `computedKey` is what makes a computed round appear. Fetching on `round` alone left the panel
    // showing "no round has been computed yet" after the facilitator computed the round already on
    // screen: that round's number did not change, so nothing re-read its scoreboard until a reload.
    // The statements below are re-read when the run refreshes, so this key changes exactly when the
    // set of computed rounds does — including after a reset, which removes them.
  }, [runId, round, t, computedKey]);

  useEffect(() => {
    void load();
  }, [load]);

  // D69: the selector cannot offer a round that has not been computed — asking the backend for one is
  // what produced the error message. The selection follows the **newest** computed round when a new
  // one appears (that is the round the facilitator just pressed Compute on, so it is the one the class
  // should see), and falls back to the last remaining round when a reset removes them. A deliberate
  // choice is kept otherwise: reading round 2 while nothing new is computed must not move.
  const lastComputed = useRef<number | null>(null);
  useEffect(() => {
    if (computedRounds.length === 0) {
      lastComputed.current = null;
      return;
    }
    const newest = computedRounds[computedRounds.length - 1];
    const previous = lastComputed.current;
    lastComputed.current = newest;
    setRound((current) => {
      if (previous !== null && newest > previous) return newest;
      return computedRounds.includes(current) ? current : newest;
    });
  }, [computedRounds]);

  /** D76: the same report the trainee can download, generated for any company by staff. */
  const developmentReport = async (teamId: number) => {
    setProblem(null);
    setReportFor(teamId);
    try {
      await downloadEvaluation(runId, teamId, locale);
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setReportFor(null);
    }
  };

  /** The trainee(s) seated in a company, so a row reads as a person's performance (D36). */
  const seats = (teamId: number) =>
    players
      .filter((player) => player.teamId === teamId)
      .map((player) => player.displayName)
      .join(" · ");

  const forRound = (teamId: number, key: keyof StatementRow) => {
    const row = (statements[teamId] ?? []).find((entry) => entry.round === round);
    return row ? Number(row[key]) : null;
  };

  const totals = useMemo(() => {
    const sumOf = (key: keyof StatementRow) =>
      board.reduce((sum, row) => sum + (forRound(row.team_id, key) ?? 0), 0);
    return { revenue: sumOf("revenue"), profit: sumOf("profit") };
  }, [board, statements, round]);

  if (board.length === 0) {
    return (
      <section className="card">
        <h2>{t("debrief.title")}</h2>
        <p className="muted">{t("debrief.noRound")}</p>
        {problem && <p className="error">{problem}</p>}
        {slides && (
          <DebriefSlides
            board={board}
            statements={statements}
            players={players}
            round={round}
            lastComputed={Math.max(1, currentRound)}
            onRoundChange={setRound}
            onClose={() => setSlides(false)}
          />
        )}
      </section>
    );
  }

  return (
    <section className="card">
      <div className="row">
        <h2>{t("debrief.title")}</h2>
        <label className="inline">
          {t("debrief.round")}
          <select
            value={round}
            disabled={computedRounds.length === 0}
            onChange={(e) => setRound(Number(e.target.value))}
          >
            {computedRounds.length === 0 ? (
              <option value={0}>{t("debrief.noRounds")}</option>
            ) : (
              computedRounds.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))
            )}
          </select>
        </label>
        <div className="buttons">
          <button onClick={() => setSlides(true)}>{t("debrief.present")}</button>
          <button
            onClick={async () => {
              setProblem(null);
              setDownloading("pdf");
              try {
                await downloadDebrief(runId, "pdf", locale);
              } catch (error) {
                setProblem(describeError(error, t));
              } finally {
                setDownloading(null);
              }
            }}
            disabled={downloading !== null}
          >
            {downloading === "pdf" ? t("debrief.exporting") : t("debrief.pdf")}
          </button>
          <button
            onClick={async () => {
              setProblem(null);
              setDownloading("xlsx");
              try {
                await downloadDebrief(runId, "xlsx", locale);
              } catch (error) {
                setProblem(describeError(error, t));
              } finally {
                setDownloading(null);
              }
            }}
            disabled={downloading !== null}
          >
            {downloading === "xlsx" ? t("debrief.exporting") : t("debrief.excel")}
          </button>
        </div>
      </div>
      <p className="muted">{t("debrief.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <table>
        <thead>
          <tr>
            <th>{t("debrief.company")}</th>
            <th className="num">{t("debrief.revenue")}</th>
            <th className="num">{t("debrief.cost")}</th>
            <th className="num">{t("debrief.profit")}</th>
            <th className="num">{t("debrief.cash")}</th>
            <th className="num">{t("debrief.share")}</th>
            <th className="num">{t("debrief.lostSales")}</th>
            <th className="num">{t("debrief.capacity")}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {board.map((row) => {
            const revenue = forRound(row.team_id, "revenue");
            const cost =
              revenue === null
                ? null
                : revenue -
                  forRound(row.team_id, "profit")!;
            const used = forRound(row.team_id, "capacity_used");
            const available = forRound(row.team_id, "capacity_available");
            return (
              <>
                <tr key={row.team_id}>
                  <td>
                    <strong>{row.company_name}</strong>
                    {seats(row.team_id) && (
                      <small className="muted"> · {seats(row.team_id)}</small>
                    )}
                  </td>
                  <td className="num">{revenue === null ? "—" : money(revenue)}</td>
                  <td className="num">{cost === null ? "—" : money(cost)}</td>
                  <td className="num">
                    <strong>{money(row.profit)}</strong>
                  </td>
                  <td className="num">{money(row.cash)}</td>
                  <td className="num">{number(row.market_share, 1)}%</td>
                  <td className="num">
                    {forRound(row.team_id, "lost_sales") === null
                      ? "—"
                      : number(forRound(row.team_id, "lost_sales")!, 0)}
                  </td>
                  <td className="num">
                    {used === null || available === null
                      ? "—"
                      : `${number(used, 0)} / ${number(available, 0)}`}
                  </td>
                  <td className="num">
                    <button className="link" onClick={() => setOpen(open === row.team_id ? null : row.team_id)}>
                      {open === row.team_id ? t("debrief.hide") : t("debrief.detail")}
                    </button>
                    {" · "}
                    <button
                      className="link"
                      onClick={() => void developmentReport(row.team_id)}
                      disabled={reportFor !== null}
                    >
                      {reportFor === row.team_id ? t("debrief.exporting") : t("debrief.developmentReport")}
                    </button>
                  </td>
                </tr>
                {open === row.team_id && (
                  <tr key={`${row.team_id}-detail`}>
                    <td colSpan={9}>
                      {(() => {
                        const statement = (statements[row.team_id] ?? []).find(
                          (entry) => entry.round === round,
                        );
                        if (!statement) {
                          return <p className="muted">{t("debrief.noStatement")}</p>;
                        }
                        return (
                          <StatementDetail
                            row={statement}
                            share={row.market_share}
                            decision={decisions[row.team_id]}
                          />
                        );
                      })()}
                    </td>
                  </tr>
                )}
              </>
            );
          })}
        </tbody>
        <tfoot>
          <tr>
            <td>
              <strong>{t("debrief.class")}</strong>
            </td>
            <td className="num">{money(totals.revenue)}</td>
            <td className="num">{money(totals.revenue - totals.profit)}</td>
            <td className="num">
              <strong>{money(totals.profit)}</strong>
            </td>
            <td className="num" colSpan={5} />
          </tr>
        </tfoot>
      </table>
      <p className="muted">
        {t("debrief.companies", { count: teams.length, round })}
      </p>

      {slides && (
        <DebriefSlides
          board={board}
          statements={statements}
          players={players}
          round={round}
          lastComputed={Math.max(1, currentRound)}
          onRoundChange={setRound}
          onClose={() => setSlides(false)}
        />
      )}
    </section>
  );
}
