import { useEffect, useState } from "react";
import { api, type FinancialRow, type Financials } from "./api";
import { useI18n } from "./i18n";

/** Which of the three reports a caller wants. */
export type StatementKind = "pnl" | "cash" | "balance";

/**
 * D88: the three reports a review page shows for one round.
 *
 * The arithmetic lives on the server (`FinancialStatementsService`), which reads the same statements
 * the round detail is built from, so the trainee's console and the facilitator's debrief show
 * identical figures by construction.
 *
 * The facilitator reads them inline under a company's detail; a trainee opens them one at a time from
 * the buttons beside "My results" (D91). Both draw the same card, so the two views cannot drift.
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
  const { rounds, failed } = useBooks(runId, teamId);
  const entry = rounds.find((row) => row.round === round);

  if (failed) {
    return <p className="muted">{t("financials.unavailable")}</p>;
  }
  if (!entry) {
    // Nothing computed yet, or the call is still in flight: the round detail above already explains
    // the round, so an empty block is better than a spinner that comes and goes.
    return null;
  }

  return (
    <div className="financials">
      <p className="muted financials-hint">{t("financials.hint")}</p>
      <ReportCard entry={entry} statement="pnl" showTitle />
      <ReportCard entry={entry} statement="cash" showTitle />
      <ReportCard entry={entry} statement="balance" showTitle />
    </div>
  );
}

/**
 * One report in a pop-up, opened from the trainee's results card (D91). The panel carries the report
 * name and the round it covers; the body is the same card the facilitator sees inline.
 *
 * Escape and a click on the backdrop close it — a trainee reading a report should not have to find a
 * small x to get back to their results.
 */
export function StatementDialog({
  runId,
  teamId,
  round,
  statement,
  onClose,
}: {
  runId: number;
  teamId: number;
  round: number;
  statement: StatementKind;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const { rounds, failed } = useBooks(runId, teamId);
  const entry = rounds.find((row) => row.round === round);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const title = t(titleKey(statement));

  return (
    <div
      className="report-modal-overlay"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="report-modal" role="dialog" aria-modal="true" aria-label={title}>
        <header className="report-modal-head">
          <h2>{title}</h2>
          <div className="report-modal-tools">
            <span className="report-round">{t("financials.round", { round })}</span>
            <button className="report-modal-close" onClick={onClose} autoFocus>
              {t("report.close")}
            </button>
          </div>
        </header>
        <div className="report-modal-body">
          {failed ? (
            <p className="muted">{t("financials.unavailable")}</p>
          ) : entry ? (
            <ReportCard entry={entry} statement={statement} showTitle={false} />
          ) : (
            <p className="muted">{t("financials.loading")}</p>
          )}
        </div>
      </div>
    </div>
  );
}

/** One of the three cards: title, the round it covers, the ruled table and its note. */
function ReportCard({
  entry,
  statement,
  showTitle,
}: {
  entry: Financials["rounds"][number];
  statement: StatementKind;
  showTitle: boolean;
}) {
  const { t } = useI18n();

  if (statement === "balance") {
    const assets = entry.balance.filter((row) => row.section === "assets");
    const liabilitiesAndEquity = [
      ...entry.balance.filter((row) => row.section === "liabilities"),
      ...entry.balance.filter((row) => row.section === "equity"),
    ];
    // Normally absent (the server only emits the row when it is not zero); filtered again so a stray
    // rounding line never appears as noise.
    const difference = entry.balance.filter(
      (row) => row.section === "check" && Math.abs(row.amount) >= 1,
    );
    return (
      <article className={showTitle ? "report-card" : "report-card in-modal"}>
        {showTitle && <CardHead title={t("balance.title")} round={entry.round} />}
        <p className="report-scope">{t("balance.scope")}</p>
        <div className="balance-grid">
          <Report rows={assets} groups={[{ name: "assets", label: t("balance.groupAssets") }]} />
          <Report
            rows={liabilitiesAndEquity}
            groups={[
              { name: "liabilities", label: t("balance.groupLiabilities") },
              { name: "equity", label: t("balance.groupEquity") },
            ]}
          />
        </div>
        {difference.length > 0 && <Report rows={difference} groups={[{ name: "check" }]} />}
        <p className="muted note">{t("balance.note")}</p>
      </article>
    );
  }

  if (statement === "cash") {
    return (
      <article className={showTitle ? "report-card" : "report-card in-modal"}>
        {showTitle && <CardHead title={t("cash.title")} round={entry.round} />}
        <p className="report-scope">{t("cash.scope")}</p>
        <Report
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
    );
  }

  return (
    <article className={showTitle ? "report-card" : "report-card in-modal"}>
      {showTitle && <CardHead title={t("pnl.title")} round={entry.round} />}
      <p className="report-scope">{t("pnl.scope")}</p>
      <Report
        rows={entry.pnl}
        groups={[
          { name: "revenue", label: t("pnl.groupIncome") },
          { name: "expenses", label: t("pnl.groupExpenses") },
          { name: "total" },
        ]}
      />
      <p className="muted note">{t("pnl.note")}</p>
    </article>
  );
}

function CardHead({ title, round }: { title: string; round: number }) {
  const { t } = useI18n();
  return (
    <header className="report-head">
      <h3>{title}</h3>
      <span className="report-round">{t("financials.round", { round })}</span>
    </header>
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
function Report({ rows, groups }: { rows: FinancialRow[]; groups: Group[] }) {
  const blocks = groups
    .map((group) => ({ group, rows: rows.filter((row) => row.section === group.name) }))
    .filter((block) => block.rows.length > 0);
  if (blocks.length === 0) return null;

  return (
    <table className="report">
      <tbody>
        {blocks.map((block) => (
          <Block key={block.group.name} label={block.group.label} rows={block.rows} />
        ))}
      </tbody>
    </table>
  );
}

/** A section of a report: its subheading, then its lines, each ruled by its level. */
function Block({ label, rows }: { label?: string; rows: FinancialRow[] }) {
  const { t, money } = useI18n();
  return (
    <>
      {label && (
        <tr className="group">
          <th scope="colgroup" colSpan={2}>
            {label}
          </th>
        </tr>
      )}
      {rows.map((row) => (
        <tr
          key={row.key}
          className={row.level === 2 ? "total" : row.level === 1 ? "subtotal" : undefined}
        >
          <th scope="row">{t(row.key)}</th>
          <td className={row.amount < 0 ? "num negative" : "num"}>{money(row.amount)}</td>
        </tr>
      ))}
    </>
  );
}

/** The reports for one company, fetched once per company and looked up by round. */
function useBooks(runId: number, teamId: number) {
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
        // The reports are an addition to the round detail: if they cannot be read, the debrief still
        // works and says so in one muted line.
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [runId, teamId]);

  return { rounds: books?.rounds ?? [], failed };
}

function titleKey(statement: StatementKind): string {
  return statement === "pnl" ? "pnl.title" : statement === "cash" ? "cash.title" : "balance.title";
}
