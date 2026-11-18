import { useI18n } from "./i18n";

/**
 * D98/D101: where each company sits, on the two axes a strategy is chosen on — price and quality —
 * with the bubble sized by market share. It answers in one glance the question a table cannot: are we
 * the cheap supplier, the premium one, or stuck between them?
 *
 * <p>D101 fixes two things measured on a live class. A company's price index is a **revenue-weighted
 * mean of its products' own price indices against that product's class average**, not the mean of its
 * price list: a mixed line averaged raw looked cheaper than a single-product rival that sold at the
 * same catalogue prices, so the map was drawing product-line breadth and calling it price posture.
 * And when every quality index is flat — nobody has bought quality this round — the vertical axis
 * becomes brand, which is the same strategic axis and actually has something to say.
 */

/** One product line as the statement stores it, with what it sold. */
export interface PositionLine {
  product: string;
  price: number;
  quality: number;
  sold: number;
  revenue: number;
}

export interface Position {
  company: string;
  /** Share-weighted price of what this company sold, over the class average **for the same product**. */
  priceIndex: number;
  /** Weighted quality of what it sold, over the class average for the same product. */
  qualityIndex: number;
  /** Market share, in percent — the bubble's area. */
  share: number;
  /** Brand points at the end of the round; the vertical axis when quality is flat. */
  brand: number;
  own: boolean;
  /** Previous rounds' positions, oldest first — the trail D101 adds so movement is visible. */
  trail: TrailPoint[];
}

/** One earlier round's position, kept so the map can draw the path a company took. */
export interface TrailPoint {
  priceIndex: number;
  qualityIndex: number;
}

/** How flat the quality column has to be before it is replaced by brand. */
const FLAT = 0.02;

/** The axis a map is drawn on: quality when it varies, brand when nothing has been bought yet. */
export type VerticalAxis = "quality" | "brand";

const SIZE = 340;
const PAD = 44;
const LOW = 0.6;
const HIGH = 1.4;

/** Class averages per product, so an index means the same thing for a one-product and a three-product line. */
function classAverages(byCompany: Map<number, PositionLine[]>): Map<string, { price: number; quality: number }> {
  const totals = new Map<string, { price: number; quality: number; weight: number }>();
  for (const lines of byCompany.values()) {
    for (const line of lines) {
      const weight = Math.max(line.revenue, 0) || Math.max(line.sold, 0) || 1;
      const entry = totals.get(line.product) ?? { price: 0, quality: 0, weight: 0 };
      entry.price += line.price * weight;
      entry.quality += line.quality * weight;
      entry.weight += weight;
      totals.set(line.product, entry);
    }
  }
  const averages = new Map<string, { price: number; quality: number }>();
  for (const [product, entry] of totals) {
    averages.set(product, {
      price: entry.weight > 0 ? entry.price / entry.weight : 1,
      quality: entry.weight > 0 ? entry.quality / entry.weight : 1,
    });
  }
  return averages;
}

/**
 * Turns the stored product lines of one round into positions.
 *
 * <p>Price and quality are weighted by **revenue actually booked**, because a company that sells
 * almost nothing of a product should not be placed by it; a company with no sales at all is placed on
 * its price list instead. Each product is indexed against its own class average first, so breadth of
 * line is not mistaken for a posture.
 */
export function positionsFrom(
  companies: { teamId: number; company: string; share: number; brand: number; own: boolean }[],
  products: Map<number, PositionLine[]>,
): Position[] {
  const averages = classAverages(products);

  const weighted = (teamId: number, pick: (line: PositionLine, average: { price: number; quality: number }) => number) => {
    const lines = products.get(teamId) ?? [];
    let total = 0;
    let weight = 0;
    for (const line of lines) {
      const average = averages.get(line.product);
      if (!average) {
        continue;
      }
      const w = Math.max(line.revenue, 0) || Math.max(line.sold, 0);
      total += pick(line, average) * w;
      weight += w;
    }
    if (weight > 0) {
      return total / weight;
    }
    // No sales: fall back to the plain mean of the same indices, so the bubble is still placed.
    const indices = lines
      .map((line) => ({ line, average: averages.get(line.product) }))
      .filter((entry) => entry.average);
    return indices.length === 0
      ? 1
      : indices.reduce((sum, entry) => sum + pick(entry.line, entry.average!), 0) / indices.length;
  };

  return companies.map((company) => ({
    company: company.company,
    priceIndex: weighted(company.teamId, (line, average) => line.price / average.price),
    qualityIndex: weighted(company.teamId, (line, average) => line.quality / average.quality),
    share: company.share,
    brand: company.brand,
    own: company.own,
    trail: [],
  }));
}

/** Adds each company's positions in the earlier rounds, so the map can show where it came from. */

export function withTrails(
  points: Position[],
  history: Map<number, TrailPoint[]>,
): Position[] {
  return points.map((point, index) => ({
    ...point,
    trail: (history.get(index) ?? []).map((past) => ({
      priceIndex: past.priceIndex,
      qualityIndex: past.qualityIndex,
    })),
  }));
}

/** Quality when it actually varies between companies; brand when nobody has bought any. */
export function verticalAxisFor(points: Position[]): VerticalAxis {
  if (points.length < 2) {
    return "quality";
  }
  const values = points.map((point) => point.qualityIndex);
  const spread = Math.max(...values) - Math.min(...values);
  return spread < FLAT ? "brand" : "quality";
}

/** Brand is indexed against the class average too, so 1.00 means "the field's brand". */
function brandIndices(points: Position[]): number[] {
  const average = points.reduce((sum, point) => sum + point.brand, 0) / Math.max(points.length, 1) || 1;
  return points.map((point) => point.brand / average);
}

export default function PositionMap({
  points,
  axis = "quality",
  standalone = false,
}: {
  points: Position[];
  axis?: VerticalAxis;
  /** D102: rendered on its own page — no in-page heading, and a larger box, because it is the subject. */
  standalone?: boolean;
}) {
  const { t } = useI18n();
  if (points.length < 2) {
    return null;
  }

  const scale = (value: number) => {
    const clamped = Math.min(Math.max(value, LOW), HIGH);
    return PAD + ((clamped - LOW) / (HIGH - LOW)) * (SIZE - PAD * 2);
  };
  const bubble = (share: number) => 5 + Math.sqrt(Math.max(share, 0)) * 3.2;
  const maxShare = Math.max(...points.map((point) => point.share), 1);
  const brands = brandIndices(points);
  const vertical = (index: number) => (axis === "brand" ? brands[index] : points[index].qualityIndex);

  // Bubbles within a few pixels of each other would hide one another; nudge each subsequent one
  // down-right by a fixed step so the pair reads as two companies, not one.
  // Bubbles are drawn at their true position; only the labels are moved, because displacing the dot
  // would lie about where the company sits. Two labels closer than the gap below are pushed apart
  // vertically, alternating up and down, until nothing collides.
  const anchor = points.map((point, index) => ({
    x: scale(point.priceIndex),
    y: SIZE - scale(vertical(index)),
    r: bubble(point.share),
  }));
  const labelGap = 10;
  const labelY = anchor.map((entry) => entry.y - entry.r - 5);
  for (let pass = 0; pass < 8; pass++) {
    let moved = false;
    for (let i = 0; i < anchor.length; i++) {
      for (let j = i + 1; j < anchor.length; j++) {
        if (Math.abs(anchor[i].x - anchor[j].x) > 60) {
          continue;
        }
        const gap = Math.abs(labelY[i] - labelY[j]);
        if (gap >= labelGap) {
          continue;
        }
        const push = (labelGap - gap) / 2 + 1;
        labelY[j] += labelY[j] <= labelY[i] ? -push : push;
        moved = true;
      }
    }
    if (!moved) {
      break;
    }
  }

  const ticks = [0.7, 0.85, 1, 1.15, 1.3];
  const quadrant = (x: number, y: number, key: string) => (
    <text x={x} y={y} className="position-quadrant" textAnchor="middle">
      {t(key)}
    </text>
  );

  return (
    <figure className={standalone ? "position-map standalone" : "position-map"}>
      <figcaption>
        {!standalone && <h3>{t("debrief.positionMap")}</h3>}
        <p className="muted">{t("debrief.positionMapHint")}</p>
        {axis === "brand" && <p className="muted">{t("debrief.positionFlatQuality")}</p>}
      </figcaption>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={t("debrief.positionMap")}>
        <rect x={PAD} y={PAD} width={SIZE - PAD * 2} height={SIZE - PAD * 2} className="position-frame" />
        {/* The four sectors, named. The top half is "more of the vertical thing" — quality when the
            axis is quality, brand when it is brand — so the words follow the axis, not a fixed guess. */}
        {/* Anchored in the middle of each quadrant, not at the frame edge: a label near the top edge
            sits above the average line even when its quadrant is the bottom one, and then the words
            contradict the bubbles (D101a).
            On the shared page (`standalone`) they are omitted: at 620 px in a crowded class the words
            run into the bubbles and the labels, and the caption underneath already names the four
            quadrants — a chart that cannot be read is worse than one without decorative text. */}
        {!standalone && (
          <>
            {quadrant(scale(0.8), SIZE - scale(1.2),
              axis === "brand" ? "debrief.quadrantCheapUnknown" : "debrief.quadrantCheap")}
            {quadrant(scale(1.2), SIZE - scale(1.2),
              axis === "brand" ? "debrief.quadrantPremiumStrong" : "debrief.quadrantPremium")}
            {quadrant(scale(0.8), SIZE - scale(0.8), "debrief.quadrantStuck")}
            {quadrant(scale(1.2), SIZE - scale(0.8), "debrief.quadrantOverpriced")}
          </>
        )}

        <line x1={scale(1)} y1={PAD} x2={scale(1)} y2={SIZE - PAD} className="position-axis" />
        <line x1={PAD} y1={scale(1)} x2={SIZE - PAD} y2={scale(1)} className="position-axis" />
        <line x1={scale(1)} y1={PAD} x2={scale(1)} y2={SIZE - PAD} className="position-average" />
        <line x1={PAD} y1={scale(1)} x2={SIZE - PAD} y2={scale(1)} className="position-average" />
        {ticks.map((tick) => (
          <g key={tick}>
            <text x={scale(tick)} y={SIZE - PAD + 12} className="position-tick" textAnchor="middle">
              {tick.toFixed(2)}
            </text>
            <text x={PAD - 6} y={SIZE - scale(tick) + 3} className="position-tick" textAnchor="end">
              {tick.toFixed(2)}
            </text>
          </g>
        ))}
        <text x={SIZE / 2} y={SIZE - 6} className="position-label" textAnchor="middle">
          {t("debrief.positionPrice")} →
        </text>
        <text x={12} y={SIZE / 2} className="position-label" transform={`rotate(-90 12 ${SIZE / 2})`} textAnchor="middle">
          {axis === "brand" ? `${t("debrief.positionBrand")} →` : `${t("debrief.positionQuality")} →`}
        </text>

        {points.map((point, index) => {
          const { x, y } = anchor[index];
          const nameY = labelY[index];
          const trail = point.trail;
          return (
            <g key={point.company}>
              {trail.length > 1 && (
                <polyline
                  className="position-trail"
                  points={[...trail, { priceIndex: point.priceIndex, qualityIndex: point.qualityIndex }]
                    .map((step) => `${scale(step.priceIndex)},${SIZE - scale(step.qualityIndex)}`)
                    .join(" ")}
                />
              )}
              {trail.length > 0 && (
                <circle
                  cx={scale(trail[0].priceIndex)}
                  cy={SIZE - scale(trail[0].qualityIndex)}
                  r={2.5}
                  className="position-trail-start"
                />
              )}
              <circle
                cx={x}
                cy={y}
                r={bubble(point.share)}
                className={point.own ? "position-dot own" : "position-dot"}
                opacity={0.55 + 0.45 * (point.share / maxShare)}
              />
              <text x={x} y={nameY} className="position-name" textAnchor="middle">
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
