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
 * Rows arrive as i18n keys with a level (0 a line, 1 a subtotal, 2 a total) and a section, so the
 * page can rule them the way a printed statement is ruled: subtotals underlined, the result
 * double-ruled, the balance sheet in two columns, and the cash flow split into operating, investing
 * and financing.
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
  const { t } = useI18n();
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

  const section = (rows: FinancialRow[], name: string) =>
    rows.filter((row) => row.section === name);

  const cashGroups = ["operating", "investing", "financing", "movement"].map((name) => ({
    name,
    label: t(`cash.section.${name}`),
    rows: section(entry.cashFlow, name),
  }));
  const assets = section(entry.balance, "assets");
  const liabilitiesAndEquity = [
    ...section(entry.balance, "liabilities"),
    ...section(entry.balance, "equity"),
  ];
  // Normally absent (the server only emits the row when it is not zero); filtered again so a stray
  // rounding line never appears as noise.
  const difference = section(entry.balance, "check").filter((row) => Math.abs(row.amount) >= 1);

  return (
    <section className="detail-section financials">
      <h4>{t("financials.title", { round })}</h4>
      <p className="muted">{t("financials.hint")}</p>

      <div className="statement">
        <h5>{t("pnl.title")}</h5>
        <Report rows={entry.pnl} />
        <p className="muted note">{t("pnl.note")}</p>
      </div>

      <div className="statement">
        <h5>{t("cash.title")}</h5>
        {cashGroups.map((group) =>
          group.rows.length > 0 ? (
            <Report key={group.name} label={group.label} rows={group.rows} />
          ) : null,
        )}
        <p className="muted note">{t("cash.note")}</p>
      </div>

      <div className="statement">
        <h5>{t("balance.title")}</h5>
        <div className="balance-grid">
          <Report label={t("balance.groupAssets")} rows={assets} />
          <Report label={t("balance.groupLiabilitiesEquity")} rows={liabilitiesAndEquity} />
        </div>
        {difference.length > 0 && <Report rows={difference} />}
        <p className="muted note">{t("balance.note")}</p>
      </div>
    </section>
  );
}

/**
 * One ruled table. A subtotal is separated from the lines above it by a rule; a total closes the
 * block with a double rule, which is how a printed statement says "this is the result".
 */
function Report({ label, rows }: { label?: string; rows: FinancialRow[] }) {
  const { t, money } = useI18n();
  if (rows.length === 0) return null;
  return (
    <table className="report">
      {label && <caption>{label}</caption>}
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.key}
            className={row.level === 2 ? "total" : row.level === 1 ? "subtotal" : undefined}
          >
            <th scope="row">{t(row.key)}</th>
            <td className={row.amount < 0 ? "num negative" : "num"}>{money(row.amount)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
