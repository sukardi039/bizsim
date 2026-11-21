/**
 * D117: the pop-up the facilitator pushes onto the class's own screens.
 *
 * <p>It draws the **same** `MarketSharePies` component the facilitator's debrief draws, from the same
 * endpoint, so the class cannot be shown a chart that disagrees with the projector. What differs is the
 * frame: this is a modal over the trainee's console, because the point of a push is attention.
 *
 * <p>**It can be dismissed, and re-opened.** A trainee reading their own results should not be trapped
 * behind a chart, but closing it must not mean losing it while the facilitator still has it up — the
 * console keeps a button that brings it back until the facilitator takes the show down.
 */
import { useI18n } from "./i18n";
import { MarketSharePies } from "./MarketSharePies";
import type { MarketPie } from "./api";

export default function ClassShowPopup({
  pies,
  round,
  onClose,
}: {
  pies: MarketPie[];
  round: number;
  onClose: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="report-modal-overlay" role="presentation" onClick={onClose}>
      <div
        className="report-modal show-popup"
        role="dialog"
        aria-modal="true"
        aria-label={t("player.showTitle", { round })}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="row">
          <h2>{t("player.showTitle", { round })}</h2>
          <div className="buttons">
            <button type="button" className="link" onClick={onClose}>
              {t("player.showClose")}
            </button>
          </div>
        </div>
        <p className="muted">{t("player.showHint")}</p>
        <MarketSharePies pies={pies} />
      </div>
    </div>
  );
}
