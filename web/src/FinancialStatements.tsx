import { useEffect, useState } from "react";
import { api, type FinancialRow, type Financials } from "./api";
import { useI18n } from "./i18n";

/** Which of the three reports a caller wants. */
export type StatementKind = "pnl" | "cash" | "balance";

/** The three reports, as the API returns them for one round. */
type Entry = Financials["rounds"][number];

/**
 * D88: the three reports, one column per round.
 *
 * The arithmetic lives on the server (`FinancialStatementsService`), which reads the same statements
 * the round detail is built from, so the trainee's console and the facilitator's debrief show
 * identical figures by construction.
 *
 * D92: a report is read as a comparison — the round in front of the class, and the two before it,
 * where they exist. The facilitator gets that inline under a company's detail; a trainee opens one
 * report at a time from the buttons beside "My results" (D91). Both draw the same card.
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
  const entries = comparison(rounds, round);

  if (failed) {
    return <p className="muted">{t("financials.unavailable")}</p>;
  }
  if (entries.length === 0) {
    // Nothing computed yet, or the call is still in flight: the round detail above already explains
    // the round, so an empty block is better than a spinner that comes and goes.
    return null;
  }

  return (
    <div className="financials">
      <p className="muted financials-hint">{t("financials.hint")}</p>
      <ReportCard entries={entries} statement="pnl" showTitle />
      <ReportCard entries={entries} statement="cash" showTitle />
      <ReportCard entries={entries} statement="balance" showTitle />
    </div>
  );
}

/**
 * One report in a pop-up, opened from the trainee's results card (D91). The panel carries the report
 * name and the round it is opened on; the body is the same card the facilitator sees inline.
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
  const entries = comparison(rounds, round);

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
          ) : entries.length > 0 ? (
            <ReportCard entries={entries} statement={statement} showTitle={false} />
          ) : (
            <p className="muted">{t("financials.loading")}</p>
          )}
        </div>
      </div>
    </div>
  );
}

/** One of the three cards: title, the rounds compared, the ruled table and its note. */
function ReportCard({
  entries,
  statement,
  showTitle,
}: {
  entries: Entry[];
  statement: StatementKind;
  showTitle: boolean;
}) {
  const { t } = useI18n();

  if (statement === "balance") {
    const assets = pick(entries, "balance", "assets");
    const liabilitiesAndEquity = [
      ...pick(entries, "balance", "liabilities"),
      ...pick(entries, "balance", "equity"),
    ];
    // Normally absent (the server only emits the row when it is not zero); filtered again so a stray
    // rounding line never appears as noise.
    const difference = pick(entries, "balance", "check").filter((row) =>
      entries.some((entry) => Math.abs(value(entry, "balance", row.key)) >= 1),
    );
    return (
      <article className={showTitle ? "report-card" : "report-card in-modal"}>
        {showTitle && <CardHead title={t("balance.title")} round={entries[0].round} />}
        <p className="report-scope">{t("balance.scope")}</p>
        <div className="balance-grid">
          <Report
            entries={entries}
            rows={assets}
            groups={[{ name: "assets", label: t("balance.groupAssets") }]}
            source="balance"
          />
          <Report
            entries={entries}
            rows={liabilitiesAndEquity}
            groups={[
              { name: "liabilities", label: t("balance.groupLiabilities") },
              { name: "equity", label: t("balance.groupEquity") },
            ]}
            source="balance"
          />
        </div>
        {difference.length > 0 && (
          <Report entries={entries} rows={difference} groups={[{ name: "check" }]} source="balance" />
        )}
        <p className="muted note">{t("balance.note")}</p>
      </article>
    );
  }

  if (statement === "cash") {
    return (
      <article className={showTitle ? "report-card" : "report-card in-modal"}>
        {showTitle && <CardHead title={t("cash.title")} round={entries[0].round} />}
        <p className="report-scope">{t("cash.scope")}</p>
        <Report
          entries={entries}
          rows={entries[0].cashFlow}
          groups={[
            { name: "operating", label: t("cash.section.operating") },
            { name: "investing", label: t("cash.section.investing") },
            { name: "financing", label: t("cash.section.financing") },
            { name: "movement", label: t("cash.section.movement") },
          ]}
          source="cashFlow"
        />
        <p className="muted note">{t("cash.note")}</p>
      </article>
    );
  }

  return (
    <article className={showTitle ? "report-card" : "report-card in-modal"}>
      {showTitle && <CardHead title={t("pnl.title")} round={entries[0].round} />}
      <p className="report-scope">{t("pnl.scope")}</p>
      <Report
        entries={entries}
        rows={entries[0].pnl}
        groups={[
          { name: "revenue", label: t("pnl.groupIncome") },
          { name: "expenses", label: t("pnl.groupExpenses") },
          { name: "total" },
        ]}
        source="pnl"
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

type Source = "pnl" | "cashFlow" | "balance";

/** The rows of one block, taken from the newest round in the comparison. */
function pick(entries: Entry[], source: Source, section: string): FinancialRow[] {
  return entries[0][source].filter((row) => row.section === section);
}

/** One line's amount in one round; zero when that round's statement does not carry the line. */
function value(entry: Entry, source: Source, key: string): number {
  return entry[source].find((row) => row.key === key)?.amount ?? 0;
}

/**
 * One ruled table, a column per round. A block of lines opens with its subheading; a subtotal is
 * separated from the lines above it by a rule, and a total closes the block with a double rule —
 * how a printed statement says "this is the result".
 */
function Report({
  entries,
  rows,
  groups,
  source,
}: {
  entries: Entry[];
  rows: FinancialRow[];
  groups: Group[];
  source: Source;
}) {
  const blocks = groups
    .map((group) => ({ group, rows: rows.filter((row) => row.section === group.name) }))
    .filter((block) => block.rows.length > 0);
  if (blocks.length === 0) return null;

  return (
    <table className="report multi">
      <thead>
        <tr>
          <th scope="col" />
          {entries.map((entry) => (
            <th scope="col" className="num" key={entry.round}>
              <RoundLabel round={entry.round} />
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {blocks.map((block) => (
          <Block
            key={block.group.name}
            label={block.group.label}
            rows={block.rows}
            entries={entries}
            source={source}
          />
        ))}
      </tbody>
    </table>
  );
}

function RoundLabel({ round }: { round: number }) {
  const { t } = useI18n();
  return <>{t("financials.round", { round })}</>;
}

/** A section of a report: its subheading, then its lines, one amount per round. */
function Block({
  label,
  rows,
  entries,
  source,
}: {
  label?: string;
  rows: FinancialRow[];
  entries: Entry[];
  source: Source;
}) {
  const { t, money } = useI18n();
  return (
    <>
      {label && (
        <tr className="group">
          <th scope="colgroup" colSpan={entries.length + 1}>
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
          {entries.map((entry) => {
            const amount = value(entry, source, row.key);
            return (
              <td className={amount < 0 ? "num negative" : "num"} key={entry.round}>
                {money(amount)}
              </td>
            );
          })}
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

/**
 * D92: the round asked for and the two before it, where they exist — current on the left, the older
 * rounds to its right, the way a comparison is read. A class in round 2 gets two columns, not three
 * with an empty one.
 */
function comparison(rounds: Entry[], current: number): Entry[] {
  const out: Entry[] = [];
  for (const number of [current, current - 1, current - 2]) {
    const entry = rounds.find((row) => row.round === number);
    if (entry) out.push(entry);
  }
  return out;
}

function titleKey(statement: StatementKind): string {
  return statement === "pnl" ? "pnl.title" : statement === "cash" ? "cash.title" : "balance.title";
}
