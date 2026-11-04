import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type RunPlayer, type ScoreRow, type StatementRow, type Team } from "./api";
import { describeError } from "./errors";
import StatementDetail from "./StatementDetail";
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
  rounds,
  currentRound,
  players,
}: {
  runId: number;
  teams: Team[];
  rounds: number;
  currentRound: number;
  /** Who is seated where: performance is per company, but the class asks "whose company?". */
  players: RunPlayer[];
}) {
  const { t, money, number } = useI18n();
  const [round, setRound] = useState(Math.max(1, currentRound || 1));
  const [board, setBoard] = useState<ScoreRow[]>([]);
  const [statements, setStatements] = useState<Record<number, StatementRow[]>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  const load = useCallback(async () => {
    setProblem(null);
    try {
      const rows = await api.scoreboard(runId, round);
      setBoard(rows);
      const perTeam = await Promise.all(
        rows.map(async (row) => [row.team_id, await api.statements(runId, row.team_id)] as const),
      );
      setStatements(Object.fromEntries(perTeam));
    } catch (error) {
      setProblem(describeError(error, t));
      setBoard([]);
    }
  }, [runId, round, t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (currentRound > 0) setRound(currentRound);
  }, [currentRound]);

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
      </section>
    );
  }

  return (
    <section className="card">
      <div className="row">
        <h2>{t("debrief.title")}</h2>
        <label className="inline">
          {t("debrief.round")}
          <select value={round} onChange={(e) => setRound(Number(e.target.value))}>
            {Array.from({ length: Math.max(rounds, currentRound) }, (_, i) => i + 1).map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
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
                        return <StatementDetail row={statement} share={row.market_share} />;
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
    </section>
  );
}
