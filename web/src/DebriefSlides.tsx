import { useEffect, useMemo, useState } from "react";
import { useI18n } from "./i18n";
import type { RunPlayer, ScoreRow, StatementRow } from "./api";

/**
 * The debrief as the class sees it (D13): six measures on page 1, the two measures that explain them
 * on page 2. Built as a full-screen overlay rather than a route so the facilitator can open it on
 * the run page, walk the room with the arrow keys, and close it with Escape.
 */
export default function DebriefSlides({
  board,
  statements,
  players,
  round,
  rounds,
  onRoundChange,
  onClose,
}: {
  board: ScoreRow[];
  statements: Record<number, StatementRow[]>;
  players: RunPlayer[];
  round: number;
  rounds: number;
  onRoundChange: (round: number) => void;
  onClose: () => void;
}) {
  const { t, money, number } = useI18n();
  const [page, setPage] = useState<1 | 2>(1);

  // One handler for the whole overlay: the facilitator should not have to aim at a button mid-class.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
      if (event.key === "ArrowRight") setPage(2);
      if (event.key === "ArrowLeft") setPage(1);
      if (event.key === "ArrowDown" && round < rounds) onRoundChange(round + 1);
      if (event.key === "ArrowUp" && round > 1) onRoundChange(round - 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, onRoundChange, round, rounds]);

  const seats = (teamId: number) =>
    players
      .filter((player) => player.teamId === teamId)
      .map((player) => player.displayName)
      .join(" · ");

  const forRound = (teamId: number, key: keyof StatementRow) => {
    const row = (statements[teamId] ?? []).find((entry) => entry.round === round);
    return row ? Number(row[key]) : null;
  };

  const totals = useMemo(
    () => ({
      revenue: board.reduce((sum, row) => sum + (forRound(row.team_id, "revenue") ?? 0), 0),
      profit: board.reduce((sum, row) => sum + (forRound(row.team_id, "profit") ?? 0), 0),
    }),
    [board, statements, round],
  );

  return (
    <div className="slides-overlay" role="dialog" aria-modal="true">
      <header className="slides-head">
        <div>
          <span className="slide-kicker">{t("slides.debrief")}</span>
          <h1>{t("slides.round", { round })}</h1>
        </div>
        <div className="slides-nav">
          <button onClick={() => onRoundChange(Math.max(1, round - 1))} disabled={round <= 1}>
            ‹
          </button>
          <button onClick={() => onRoundChange(Math.min(rounds, round + 1))} disabled={round >= rounds}>
            ›
          </button>
          <button onClick={() => setPage(page === 1 ? 2 : 1)}>
            {page === 1 ? t("slides.next") : t("slides.back")}
          </button>
          <button onClick={onClose}>{t("slides.close")}</button>
        </div>
      </header>

      <div className="slide-body">
        {board.map((row) => {
          const revenue = forRound(row.team_id, "revenue");
          const cost = revenue === null ? null : revenue - (forRound(row.team_id, "profit") ?? 0);
          const used = forRound(row.team_id, "capacity_used");
          const available = forRound(row.team_id, "capacity_available");
          const utilisation =
            used === null || available === null || available === 0
              ? row.capacity_utilisation
              : (used / available) * 100;
          return (
            <article className="slide-card" key={row.team_id}>
              <h2>
                {row.company_name}
                {seats(row.team_id) && <small> · {seats(row.team_id)}</small>}
              </h2>
              {page === 1 ? (
                <dl className="kpi-grid">
                  <div>
                    <dt>{t("debrief.profit")}</dt>
                    <dd className={row.profit < 0 ? "negative" : "positive"}>
                      {money(row.profit)}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("debrief.cumulative")}</dt>
                    <dd>{money(row.cumulative_profit ?? 0)}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.share")}</dt>
                    <dd>{number(row.market_share, 1)}%</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.cash")}</dt>
                    <dd>{money(row.cash)}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.brand")}</dt>
                    <dd>{number(row.brand ?? 0, 0)}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.stock")}</dt>
                    <dd>{number(row.stock, 0)}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.revenue")}</dt>
                    <dd>{revenue === null ? "—" : money(revenue)}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.cost")}</dt>
                    <dd>{cost === null ? "—" : money(cost)}</dd>
                  </div>
                </dl>
              ) : (
                <dl className="kpi-grid">
                  <div>
                    <dt>{t("debrief.lostSales")}</dt>
                    <dd>{forRound(row.team_id, "lost_sales") ?? 0}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.capacity")}</dt>
                    <dd>{number(utilisation ?? 0, 0)}%</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.rnd")}</dt>
                    <dd>{row.rnd_completed}</dd>
                  </div>
                  <div>
                    <dt>{t("debrief.status")}</dt>
                    <dd>{row.status}</dd>
                  </div>
                </dl>
              )}
            </article>
          );
        })}
      </div>

      <footer className="slides-foot">
        <span>
          {t("debrief.class")}: {money(totals.profit)} {t("debrief.profit")}
        </span>
        <span className="muted">
          {t("slides.page", { page, of: 2 })} · ← → {t("slides.pagesHint")} · ↑ ↓{" "}
          {t("slides.roundsHint")} · Esc
        </span>
      </footer>
    </div>
  );
}
