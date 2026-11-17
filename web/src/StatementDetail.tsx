import { useI18n } from "./i18n";
import type { DecisionInput, StatementRow } from "./api";
import DecisionSummary from "./DecisionSummary";

/**
 * One company's round, grouped as Submission · Revenue · Cost · Result.
 *
 * Shared by the facilitator's debrief and the trainee's own results, so a trainee reading their round
 * sees exactly the figures the class debrief shows for that company — same source (`round_statement`),
 * same layout. The only thing the caller may add is the market share, which is competition data and
 * belongs to the facilitator's screen, and the stored decision, which the facilitator's screen also
 * carries (D42): what the company sent is the first half of "why did this happen".
 */
export default function StatementDetail({
  row,
  share,
  decision,
}: {
  row: StatementRow;
  share?: number;
  /** The month the company sent, when the caller has it. Omitted by the trainee's own view. */
  decision?: DecisionInput | null;
}) {
  const { t, money, number } = useI18n();

  const lines = [
    ["cogs", "debrief.cogs"],
    ["payroll", "debrief.payroll"],
    ["marketing", "debrief.marketing"],
    ["rnd_spend", "debrief.rnd"],
    ["fixed_cost", "debrief.fixed"],
    ["holding_cost", "debrief.holding"],
    ["hiring_cost", "debrief.hiring"],
    ["capacity_cost", "debrief.capacityCost"],
    ["quality_cost", "debrief.qualityCost"],
    ["report_cost", "debrief.report"],
  ] as const;

  const lineSum = lines.reduce((sum, [key]) => sum + Number(row[key] ?? 0), 0);
  const revenue = Number(row.revenue ?? 0);
  const profit = Number(row.profit ?? 0);
  // Whatever the engine charged that is not one of the named lines. Normally 0; shown rather than
  // hidden so revenue - cost - profit always adds up on screen.
  const other = revenue - profit - lineSum;

  const cashAfter = Number(row.cash_after ?? 0);
  const cashOpening = Number(row.opening_cash ?? 0);
  const cashMove = cashAfter - cashOpening;

  // The engine writes one line per product into the statement detail (D34). They are the same
  // numbers that produced revenue and lost sales above, so the table is a breakdown, never a
  // second calculation.
  const products = row.detail?.products ?? [];
  // D79: the terms inside 库存与产能变动, so the trainee reads why cash and profit differ instead of
  // being handed one unexplained number. Absent on statements computed before D79.
  const stockChange = row.detail?.stockValueChange;
  const refund = row.detail?.refund;
  const sumOf = (pick: (line: (typeof products)[number]) => number) =>
    products.reduce((sum, line) => sum + Number(pick(line) ?? 0), 0);

  return (
    <>
      {decision !== undefined && (
        <section className="detail-section">
          <h4>{t("submission.title", { round: row.round })}</h4>
          <DecisionSummary decision={decision} />
        </section>
      )}

      <section className="detail-section">
        <h4>{t("submission.resultsTitle", { round: row.round })}</h4>
      <div className="detail-grid">
      <div>
        <h4>{t("debrief.groupRevenue")}</h4>
        <dl className="lines">
          <div>
            <dt>{t("debrief.revenue")}</dt>
            <dd>{money(revenue)}</dd>
          </div>
          <div>
            <dt>{t("debrief.lostSales")}</dt>
            <dd>
              {number(Number(row.lost_sales ?? 0), 0)} {t("debrief.units")}
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
              <dd>{money(Number(row[key] ?? 0))}</dd>
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
            <dd>{money(revenue - profit)}</dd>
          </div>
        </dl>
      </div>

      <div>
        <h4>{t("debrief.groupResult")}</h4>
        <dl className="lines">
          <div>
            <dt>{t("debrief.openingCash")}</dt>
            <dd>{money(cashOpening)}</dd>
          </div>
          <div>
            <dt>{t("debrief.profit")}</dt>
            <dd>
              <strong>{money(profit)}</strong>
            </dd>
          </div>
          {/* Profit is not cash: stock built and not sold, and capacity bought, leave the bank
              without touching profit. Shown so the column adds up. */}
          {Math.abs(cashMove - profit) >= 1 && (
            <div>
              <dt>{t("debrief.stockAndCapacity")}</dt>
              <dd>{money(cashMove - profit)}</dd>
            </div>
          )}
          {stockChange !== undefined && Math.abs(Number(stockChange ?? 0)) >= 1 && (
            <div className="muted sub">
              <dt>· {t("debrief.stockValueChange")}</dt>
              <dd>{money(Number(stockChange))}</dd>
            </div>
          )}
          {refund !== undefined && Math.abs(Number(refund ?? 0)) >= 1 && (
            <div className="muted sub">
              <dt>· {t("debrief.rndRefund")}</dt>
              <dd>{money(Number(refund))}</dd>
            </div>
          )}
          <div className="total">
            <dt>{t("debrief.cashAfter")}</dt>
            <dd>{money(cashAfter)}</dd>
          </div>
          <div>
            <dt>{t("debrief.brandAfter")}</dt>
            <dd>{number(Number(row.brand_after ?? 0), 1)}</dd>
          </div>
          <div>
            <dt>{t("debrief.capacity")}</dt>
            <dd>
              {number(Number(row.capacity_used ?? 0), 0)} /{" "}
              {number(Number(row.capacity_available ?? 0), 0)}
            </dd>
          </div>
          {share !== undefined && (
            <div>
              <dt>{t("debrief.share")}</dt>
              <dd>{number(share, 1)}%</dd>
            </div>
          )}
        </dl>
      </div>
    </div>

      {products.length > 0 && (
        <div className="product-lines">
        <h4>{t("debrief.products")}</h4>
        <table>
          <thead>
            <tr>
              <th>{t("debrief.product")}</th>
              <th className="num">{t("debrief.opening")}</th>
              <th className="num">{t("debrief.produced")}</th>
              <th className="num">{t("debrief.demand")}</th>
              <th className="num">{t("debrief.sold")}</th>
              <th className="num">{t("debrief.lost")}</th>
              <th className="num">{t("debrief.closing")}</th>
              <th className="num">{t("debrief.revenue")}</th>
              <th className="num">{t("debrief.cogs")}</th>
            </tr>
          </thead>
          <tbody>
            {products.map((line) => (
              <tr key={line.product}>
                <td>{line.product}</td>
                <td className="num">{number(Number(line.openingStock ?? 0), 0)}</td>
                <td className="num">{number(Number(line.produced ?? 0), 0)}</td>
                <td className="num">{number(Number(line.demand ?? 0), 0)}</td>
                <td className="num">{number(Number(line.sold ?? 0), 0)}</td>
                <td className={Number(line.lost ?? 0) > 0 ? "num negative" : "num"}>
                  {number(Number(line.lost ?? 0), 0)}
                </td>
                <td className="num">{number(Number(line.closingStock ?? 0), 0)}</td>
                <td className="num">{money(Number(line.revenue ?? 0))}</td>
                <td className="num">{money(Number(line.cogs ?? 0))}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td>
                {/* This company's own total for the round, not the class (the trainee sees this too). */}
                <strong>{t("debrief.total")}</strong>
              </td>
              <td className="num" colSpan={3} />
              <td className="num">{number(sumOf((line) => line.sold), 0)}</td>
              <td className="num">{number(sumOf((line) => line.lost), 0)}</td>
              <td className="num">{number(sumOf((line) => line.closingStock), 0)}</td>
              <td className="num">{money(sumOf((line) => line.revenue))}</td>
              <td className="num">{money(sumOf((line) => line.cogs))}</td>
            </tr>
          </tfoot>
          </table>
        </div>
      )}
      </section>
    </>
  );
}
