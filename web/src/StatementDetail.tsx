import { useI18n } from "./i18n";
import type { StatementRow } from "./api";

/**
 * One company's round, grouped as Revenue · Cost · Result.
 *
 * Shared by the facilitator's debrief and the trainee's own results, so a trainee reading their round
 * sees exactly the figures the class debrief shows for that company — same source (`round_statement`),
 * same layout. The only thing the caller may add is the market share, which is competition data and
 * belongs to the facilitator's screen.
 */
export default function StatementDetail({ row, share }: { row: StatementRow; share?: number }) {
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

  return (
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
  );
}
