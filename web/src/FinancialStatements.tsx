import { Fragment, useEffect, useState } from "react";
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
 * Each report is its own panel with its own heading and the round it covers, followed by one column
 * of figures: subtotals are ruled, the result is double-ruled, and each block of lines carries a
 * subheading (Income, Operating expenses, Operating activities, Assets…). Rows arrive as i18n keys
 * with a level (0 a line, 1 a subtotal, 2 a total) and a section, so the grouping is the server's
 * accounting decision, not a guess made here.
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

  const assets = section(entry.balance, "assets");
  const liabilitiesAndEquity = [
    ...section(entry.balance, "liabilities"),
    ...section(entry.balance, "equity"),
  ];
  // Normally absent (the server only emits the row when it is not zero); filtered again so a stray
  // rounding line never appears as noise.
  const difference = section(entry.balance, "check").filter((row) => Math.abs(row.amount) >= 1);

  const badge = t("financials.round", { round });

  return (
    <div className="financials">
      <p className="muted financials-hint">{t("financials.hint")}</p>

      <article className="report-card">
        <header className="report-head">
          <h3>{t("pnl.title")}</h3>
          <span className="report-round">{badge}</span>
        </header>
        <p className="report-scope">{t("pnl.scope")}</p>
        <Statement
          rows={entry.pnl}
          groups={[
            { name: "revenue", label: t("pnl.groupIncome") },
            { name: "expenses", label: t("pnl.groupExpenses") },
            { name: "total" },
          ]}
        />
        <p className="muted note">{t("pnl.note")}</p>
      </article>

      <article className="report-card">
        <header className="report-head">
          <h3>{t("cash.title")}</h3>
          <span className="report-round">{badge}</span>
        </header>
        <p className="report-scope">{t("cash.scope")}</p>
        <Statement
          rows={entry.cashFlow}
          groups={[
            { name: "operating", label: t("cash.section.operating") },
            { name: "investing", label: t("cash.section.investing") },
            { name: "financing", label: t("cash.section.financing") },
            { name: "movement", label: t("cash.section.movement") },
          ]}
        />
        <p className="muted note">{t("cash.note")}</p>
      </article>

      <article className="report-card">
        <header className="report-head">
          <h3>{t("balance.title")}</h3>
          <span className="report-round">{badge}</span>
        </header>
        <p className="report-scope">{t("balance.scope")}</p>
        <div className="balance-grid">
          <Statement rows={assets} groups={[{ name: "assets", label: t("balance.groupAssets") }]} />
          <Statement
            rows={liabilitiesAndEquity}
            groups={[
              { name: "liabilities", label: t("balance.groupLiabilities") },
              { name: "equity", label: t("balance.groupEquity") },
            ]}
          />
        </div>
        {difference.length > 0 && <Statement rows={difference} groups={[{ name: "check" }]} />}
        <p className="muted note">{t("balance.note")}</p>
      </article>
    </div>
  );
}

interface Group {
  name: string;
  label?: string;
}

/**
 * One ruled table. A block of lines opens with its subheading; a subtotal is separated from the lines
 * above it by a rule, and a total closes the block with a double rule — how a printed statement says
 * "this is the result".
 */
function Statement({ rows, groups }: { rows: FinancialRow[]; groups: Group[] }) {
  const { t, money } = useI18n();
  const blocks = groups
    .map((group) => ({ group, rows: rows.filter((row) => row.section === group.name) }))
    .filter((block) => block.rows.length > 0);
  if (blocks.length === 0) return null;

  return (
    <table className="report">
      <tbody>
        {blocks.map((block) => (
          <Fragment key={block.group.name}>
            {block.group.label && (
              <tr className="group">
                <th scope="colgroup" colSpan={2}>
                  {block.group.label}
                </th>
              </tr>
            )}
            {block.rows.map((row) => (
              <tr
                key={row.key}
                className={row.level === 2 ? "total" : row.level === 1 ? "subtotal" : undefined}
              >
                <th scope="row">{t(row.key)}</th>
                <td className={row.amount < 0 ? "num negative" : "num"}>{money(row.amount)}</td>
              </tr>
            ))}
          </Fragment>
        ))}
      </tbody>
    </table>
  );
}
