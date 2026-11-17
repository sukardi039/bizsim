import { useEffect, useState } from "react";
import { api, type FinancialRow, type Financials } from "./api";
import { useI18n } from "./i18n";

/**
 * D88: the three reports a review page shows for one round — profit and loss, cash flow statement,
 * and assets and liabilities as at the end of that round.
 *
 * The arithmetic lives on the server (`FinancialStatementsService`), which reads the same statements
 * the round detail above is built from. This component only labels and lays them out, so the trainee's
 * console and the facilitator's debrief show identical figures by construction.
 *
 * Rows arrive as i18n keys with a level: 0 a line, 1 a subtotal, 2 a total. A row the report does not
 * carry on this round (an R&D refund of nothing, a zero difference) is simply absent.
 */
export default function FinancialStatements({
  runId,
  teamId,
  round,
}: {
  runId: number;
  teamId: number;
  round: number;
}) {
  const { t, money } = useI18n();
  const [books, setBooks] = useState<Financials | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    setFailed(false);
    api
      .financials(runId, teamId)
      .then((result) => {
        if (live) setBooks(result);
      })
      .catch(() => {
        // The three reports are an addition to the round detail: if they cannot be read, the debrief
        // still works and says so in one muted line.
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [runId, teamId]);

  const entry = books?.rounds.find((row) => row.round === round);
  if (failed) {
    return <p className="muted">{t("financials.unavailable")}</p>;
  }
  if (!entry) {
    // Nothing computed yet, or the call is still in flight: the round detail above already explains
    // the round, so an empty block is better than a spinner that comes and goes.
    return null;
  }

  const lines = (rows: FinancialRow[]) => (
    <dl className="lines">
      {rows
        .filter((row) => !(row.key === "balance.difference" && Math.abs(Number(row.amount)) < 1))
        .map((row) => (
          <div
            key={row.key}
            className={row.level === 2 ? "total grand" : row.level === 1 ? "total" : undefined}
          >
            <dt>{t(row.key)}</dt>
            <dd className={Number(row.amount) < 0 ? "negative" : undefined}>{money(Number(row.amount))}</dd>
          </div>
        ))}
    </dl>
  );

  return (
    <section className="detail-section financials">
      <h4>{t("financials.title", { round })}</h4>
      <p className="muted">{t("financials.hint")}</p>

      <div className="statement">
        <h5>{t("pnl.title")}</h5>
        {lines(entry.pnl)}
        <p className="muted note">{t("pnl.note")}</p>
      </div>

      <div className="statement">
        <h5>{t("cash.title")}</h5>
        {lines(entry.cashFlow)}
        <p className="muted note">{t("cash.note")}</p>
      </div>

      <div className="statement">
        <h5>{t("balance.title")}</h5>
        {lines(entry.balance)}
        <p className="muted note">{t("balance.note")}</p>
      </div>
    </section>
  );
}
