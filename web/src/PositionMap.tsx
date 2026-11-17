import { useI18n } from "./i18n";

/**
 * D98: where each company sits, on the two axes a strategy is chosen on — price and quality — with
 * the bubble sized by market share. It answers in one glance the question a table cannot: are we
 * the cheap supplier, the premium one, or stuck between them?
 *
 * Indices are relative to the class average for the round (1.00 = average), so the map needs no
 * catalogue: a company at 0.80 is a fifth cheaper than the field, whatever the absolute price was.
 */
export interface Position {
  company: string;
  /** Weighted price of what this company actually sold, divided by the class average. */
  priceIndex: number;
  /** Weighted quality of what it sold, divided by the class average. */
  qualityIndex: number;
  /** Market share, in percent — the bubble's area. */
  share: number;
  own: boolean;
}

const SIZE = 340;
const PAD = 34;
const LOW = 0.6;
const HIGH = 1.4;

export default function PositionMap({ points }: { points: Position[] }) {
  const { t } = useI18n();
  if (points.length < 2) {
    return null;
  }

  const scale = (value: number) => {
    const clamped = Math.min(Math.max(value, LOW), HIGH);
    return PAD + ((clamped - LOW) / (HIGH - LOW)) * (SIZE - PAD * 2);
  };
  // Bubbles carry share through area, with a floor so a 2 % company is still visible and a ceiling
  // so a 50 % one does not swallow its neighbours.
  const bubble = (share: number) => 5 + Math.sqrt(Math.max(share, 0)) * 3.2;
  const maxShare = Math.max(...points.map((point) => point.share), 1);

  return (
    <figure className="position-map">
      <figcaption>
        <h3>{t("debrief.positionMap")}</h3>
        <p className="muted">{t("debrief.positionMapHint")}</p>
      </figcaption>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={t("debrief.positionMap")}>
        {/* The average lines: the cross every company is placed against. */}
        <line x1={scale(1)} y1={PAD} x2={scale(1)} y2={SIZE - PAD} className="position-axis" />
        <line x1={PAD} y1={scale(1)} x2={SIZE - PAD} y2={scale(1)} className="position-axis" />
        <rect
          x={PAD}
          y={PAD}
          width={SIZE - PAD * 2}
          height={SIZE - PAD * 2}
          fill="none"
          className="position-frame"
        />
        <text x={SIZE / 2} y={SIZE - 8} className="position-label" textAnchor="middle">
          {t("debrief.positionPrice")} →
        </text>
        <text x={12} y={SIZE / 2} className="position-label" transform={`rotate(-90 12 ${SIZE / 2})`} textAnchor="middle">
          {t("debrief.positionQuality")} →
        </text>
        {points.map((point) => {
          const x = scale(point.priceIndex);
          const y = SIZE - scale(point.qualityIndex);
          return (
            <g key={point.company}>
              <circle
                cx={x}
                cy={y}
                r={bubble(point.share)}
                className={point.own ? "position-dot own" : "position-dot"}
                opacity={0.55 + 0.45 * (point.share / maxShare)}
              />
              <text x={x} y={y - bubble(point.share) - 5} className="position-name" textAnchor="middle">
                {point.company}
              </text>
              <text x={x} y={y + 3} className="position-share" textAnchor="middle">
                {point.share.toFixed(1)}%
              </text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}

/**
 * Turns the stored product lines of one round into positions. Price and quality are weighted by the
 * units actually sold, because a company that sells almost nothing of a product should not be placed
 * by it; a company with no sales at all is placed on its own price list instead.
 */
export function positionsFrom(
  companies: { teamId: number; company: string; share: number; own: boolean }[],
  products: Map<number, { price: number; quality: number; sold: number }[]>,
): Position[] {
  const raw = companies.map((company) => {
    const lines = products.get(company.teamId) ?? [];
    const weight = lines.reduce((total, line) => total + Math.max(line.sold, 0), 0);
    const price =
      weight > 0
        ? lines.reduce((total, line) => total + line.price * Math.max(line.sold, 0), 0) / weight
        : lines.reduce((total, line) => total + line.price, 0) / Math.max(lines.length, 1);
    const quality =
      weight > 0
        ? lines.reduce((total, line) => total + line.quality * Math.max(line.sold, 0), 0) / weight
        : lines.reduce((total, line) => total + line.quality, 0) / Math.max(lines.length, 1);
    return { ...company, price, quality };
  });

  const mean = (values: number[]) =>
    values.length === 0 ? 1 : values.reduce((a, b) => a + b, 0) / values.length;
  const avgPrice = mean(raw.map((row) => row.price)) || 1;
  const avgQuality = mean(raw.map((row) => row.quality)) || 1;

  return raw.map((row) => ({
    company: row.company,
    priceIndex: row.price / avgPrice,
    qualityIndex: row.quality / avgQuality,
    share: row.share,
    own: row.own,
  }));
}
