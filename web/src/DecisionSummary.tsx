import { useI18n } from "./i18n";
import type { DecisionInput } from "./api";

/**
 * What one company asked for in one month, in the order the form asks it.
 *
 * Used in two places with the same shape: the facilitator's submission list while the round is open
 * (D12/Q-G) and the per-company detail of a computed round, where it sits above the result so
 * "what we sent" and "what we got" read as one story. When no decision was sent the block says so —
 * an empty month is a business event, not a missing screen.
 */
export default function DecisionSummary({
  decision,
  compact = false,
}: {
  decision: DecisionInput | null | undefined;
  /** True inside the submissions table, where the rows are already tight. */
  compact?: boolean;
}) {
  const { t, money, number } = useI18n();

  if (!decision) {
    return <p className="muted">{t("submission.none")}</p>;
  }

  const marketing = (decision.marketing ?? []).filter((row) => Number(row.spend) > 0);
  const rnd = (decision.rnd ?? []).filter((row) => Number(row.invest) > 0);
  const quality = (decision.quality ?? []).filter((row) => Number(row.invest) > 0);

  return (
    <div className={compact ? "submission compact" : "submission"}>
      <table>
        <thead>
          <tr>
            <th>{t("player.product")}</th>
            <th className="num">{t("submission.units")}</th>
            <th className="num">{t("submission.price")}</th>
            <th className="num">{t("submission.qualitySpend")}</th>
          </tr>
        </thead>
        <tbody>
          {(decision.production ?? []).map((row) => {
            const price = (decision.price ?? []).find((p) => p.product === row.product)?.unitPrice;
            const q = quality.find((entry) => entry.product === row.product);
            return (
              <tr key={row.product}>
                <td>{row.product}</td>
                <td className="num">{number(Number(row.units ?? 0), 0)}</td>
                <td className="num">{price === undefined ? "—" : money(Number(price))}</td>
                <td className="num">{q ? money(Number(q.invest)) : "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <dl className="lines">
        <div>
          <dt>{t("submission.marketing")}</dt>
          <dd>
            {marketing.length === 0
              ? t("submission.nothing")
              : marketing
                  .map((row) => `${row.channel} ${money(Number(row.spend))}`)
                  .join(" · ")}
          </dd>
        </div>
        <div>
          <dt>{t("submission.rnd")}</dt>
          <dd>
            {rnd.length === 0
              ? t("submission.nothing")
              : rnd
                  .map((row) => `${row.project} ${money(Number(row.invest))}`)
                  .join(" · ")}
          </dd>
        </div>
        <div>
          <dt>{t("submission.workforce")}</dt>
          <dd>
            {t("submission.workforceValue", {
              staff: number(Number(decision.manpower?.production ?? 0), 0),
              machines: money(Number(decision.capacityPurchase ?? 0)),
            })}
          </dd>
        </div>
        <div>
          <dt>{t("submission.report")}</dt>
          <dd>{decision.marketReport ? t("submission.bought") : t("submission.notBought")}</dd>
        </div>
      </dl>
    </div>
  );
}
