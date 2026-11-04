import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type ScoreRow, type StatementRow, type Team } from "./api";
import { describeError } from "./errors";
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
}: {
  runId: number;
  teams: Team[];
  rounds: number;
  currentRound: number;
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
                        const lines = [
                          ["cogs", "debrief.cogs"],
                          ["payroll", "debrief.payroll"],
                          ["marketing", "debrief.marketing"],
                          ["rnd_spend", "debrief.rnd"],
                          ["fixed_cost", "debrief.fixed"],
                          ["holding_cost", "debrief.holding"],
                          ["hiring_cost", "debrief.hiring"],
                          ["capacity_cost", "debrief.capacityCost"],
                          ["report_cost", "debrief.report"],
                        ] as const;
                        const lineSum = lines.reduce(
                          (sum, [key]) => sum + (forRound(row.team_id, key) ?? 0),
                          0,
                        );
                        const revenueAt = forRound(row.team_id, "revenue") ?? 0;
                        const profitAt = forRound(row.team_id, "profit") ?? 0;
                        // Whatever the engine charged that is not one of the named lines. Normally 0;
                        // shown rather than hidden so revenue - cost - profit always adds up on screen.
                        const other = revenueAt - profitAt - lineSum;
                        const cashAfterAt = forRound(row.team_id, "cash_after") ?? 0;
                        const cashOpening = forRound(row.team_id, "opening_cash") ?? 0;
                        const cashMove = cashAfterAt - cashOpening;
                        const used = forRound(row.team_id, "capacity_used") ?? 0;
                        const available = forRound(row.team_id, "capacity_available") ?? 0;
                        return (
                          <div className="detail-grid">
                            <div>
                              <h4>{t("debrief.groupRevenue")}</h4>
                              <dl className="lines">
                                <div>
                                  <dt>{t("debrief.revenue")}</dt>
                                  <dd>{money(revenueAt)}</dd>
                                </div>
                                <div>
                                  <dt>{t("debrief.lostSales")}</dt>
                                  <dd>
                                    {number(forRound(row.team_id, "lost_sales") ?? 0, 0)}{" "}
                                    {t("debrief.units")}
                                  </dd>
                                </div>
                              </dl>
                            </div>

                            <div>
                              <h4>{t("debrief.groupCost")}</h4>
                              <dl className="lines">
                                {lines.map(([key, label]) => (
                                  <div key={key}>
                                    <dt>{t(label)}</dt>
                                    <dd>{money(forRound(row.team_id, key) ?? 0)}</dd>
                                  </div>
                                ))}
                                {Math.abs(other) >= 1 && (
                                  <div>
                                    <dt>{t("debrief.other")}</dt>
                                    <dd>{money(other)}</dd>
                                  </div>
                                )}
                                <div className="total">
                                  <dt>{t("debrief.totalCost")}</dt>
                                  <dd>{money(revenueAt - profitAt)}</dd>
                                </div>
                              </dl>
                            </div>

                            <div>
                              <h4>{t("debrief.groupResult")}</h4>
                              <dl className="lines">
                                <div>
                                  <dt>{t("debrief.openingCash")}</dt>
                                  <dd>{money(forRound(row.team_id, "opening_cash") ?? 0)}</dd>
                                </div>
                                <div>
                                  <dt>{t("debrief.profit")}</dt>
                                  <dd>
                                    <strong>{money(profitAt)}</strong>
                                  </dd>
                                </div>
                                {/* Cash does not move by profit alone: stock and capacity are cash
                                    out but not profit. Show the gap rather than let the two rows
                                    fail to add up. */}
                                {Math.abs(cashMove - profitAt) >= 1 && (
                                  <div>
                                    <dt>{t("debrief.stockAndCapacity")}</dt>
                                    <dd>{money(cashMove - profitAt)}</dd>
                                  </div>
                                )}
                                <div className="total">
                                  <dt>{t("debrief.cashAfter")}</dt>
                                  <dd>{money(cashAfterAt)}</dd>
                                </div>
                                <div>
                                  <dt>{t("debrief.brandAfter")}</dt>
                                  <dd>{number(forRound(row.team_id, "brand_after") ?? 0, 1)}</dd>
                                </div>
                                <div>
                                  <dt>{t("debrief.capacity")}</dt>
                                  <dd>
                                    {number(used, 0)} / {number(available, 0)}
                                  </dd>
                                </div>
                                <div>
                                  <dt>{t("debrief.share")}</dt>
                                  <dd>{number(row.market_share, 1)}%</dd>
                                </div>
                              </dl>
                            </div>
                          </div>
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
    </section>
  );
}
