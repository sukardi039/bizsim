/**
 * D112/D121: per-product market share, as a **pie** the facilitator can show the class.
 *
 * <p>**What the slices mean.** The circle is the *served* market for that product — the pieces the
 * class actually contested (`pool × (1 − baselineShare)`) — so the slices add to the whole. Whatever the
 * class failed to supply is drawn as its own slice rather than dropped: a stockout is the most useful
 * thing a facilitator can point at, and a pie that silently omits it is a pie that flatters the class.
 *
 * <p>**Auto players.** A company played by the system is drawn in a muted colour but is labelled by its
 * **company name only** — never "auto", never the bot's persona (owner's ruling, 21 Nov 2026). The
 * colour tells the facilitator a seat was machine-played; the label tells the class nothing about it.
 */
import { useI18n } from "./i18n";
import type { MarketPie } from "./api";

const SIZE = 190;
const OUTER = 88;
/**
 * D121: a solid pie — no hole in the middle. The inner radius is 0, so each slice is a wedge from the
 * centre. The coverage figure that used to sit in the hole is now stated in the legend, where it can be
 * read without measuring an area; a solid centre also makes the slices bigger at the same diameter,
 * which is what the review asked for.
 */

/** Distinct hues that survive a projector; the auto seat uses a single muted grey instead. */
const PALETTE = [
  "#4f8ef7", "#f2994a", "#27ae60", "#eb5757", "#9b51e0",
  "#2d9cdb", "#f2c94c", "#56ccf2", "#bb6bd9", "#6fcf97",
  // D118: a class may seat up to thirty companies, so the palette grows with it. Hues are spaced and
  // alternate brightness so neighbours in the legend do not blur together on a projector.
  "#e07a5f", "#3d5a80", "#98c1d9", "#ee6c4d", "#6a994e",
  "#bc4749", "#a7c957", "#4361ee", "#f4a261", "#7209b7",
  "#0081a7", "#d62828", "#2a9d8f", "#e9c46a", "#8d99ae",
  "#c77dff", "#00b4d8", "#ff7b00", "#5f0f40", "#84a98c",
];
/**
 * D139: an auto player is coloured by **the Porter position it plays**, so a debrief can read which slice is the
 * cost leader without consulting a table. The four hues sit in a cool, desaturated family — deliberately different
 * in character from the warm/strong trainee palette — so the mark that a seat was machine-played survives, which
 * was the earlier ruling this preserves. A bot whose position is unknown keeps the neutral grey.
 */
const AUTO_BY_POSITION: Record<string, string> = {
  COST: "#3b82a0",
  DIFFERENTIATION: "#5b6fa8",
  COST_FOCUS: "#4a9d8f",
  DIFFERENTIATION_FOCUS: "#7d6aa8",
};
const AUTO = "#6b7280";

function autoColour(position?: string | null): string {
  return (position && AUTO_BY_POSITION[position]) || AUTO;
}
/**
 * D123: the **base** of the pie — a full disc drawn under the slices. Slices sweep on the served market,
 * so a class that under-serves leaves a wedge nobody drew: without a base that wedge is page background
 * and reads as a rendering hole rather than as the demand the class failed to supply. The base is the
 * colour of that gap (the same one the legend's "unserved" swatch uses), so the two agree.
 */
const BASE = "#2f3947";

/**
 * One slice: a wedge from the centre to `outer`, `from` in degrees clockwise from twelve o'clock.
 * D121: the same path with inner radius 0 is a pie slice; a single 100 % slice is a full circle.
 */
function wedge(from: number, sweep: number, outer: number): string {
  const clamp = Math.min(sweep, 359.999);
  if (sweep >= 359.999) {
    return `M ${SIZE / 2} ${SIZE / 2 - outer} A ${outer} ${outer} 0 1 1 ${SIZE / 2 - 0.01} ${SIZE / 2 - outer} Z`;
  }
  const a0 = ((from - 90) * Math.PI) / 180;
  const a1 = ((from + clamp - 90) * Math.PI) / 180;
  const large = clamp > 180 ? 1 : 0;
  const x = (r: number, a: number) => SIZE / 2 + r * Math.cos(a);
  const y = (r: number, a: number) => SIZE / 2 + r * Math.sin(a);
  return (
    `M ${SIZE / 2} ${SIZE / 2} ` +
    `L ${x(outer, a0)} ${y(outer, a0)} ` +
    `A ${outer} ${outer} 0 ${large} 1 ${x(outer, a1)} ${y(outer, a1)} Z`
  );
}

function SolidPie({ pie }: { pie: MarketPie }) {
  const { t, number } = useI18n();
  const totalSold = pie.slices.reduce((sum, slice) => sum + Number(slice.sold), 0);
  const coverage =
    Number(pie.servedMarket) > 0 ? Math.min(100, (totalSold / Number(pie.servedMarket)) * 100) : 0;

  // The ring is swept on the **served market**, not on units sold. Swept on units sold it always closes
  // the circle, so demand the class could not supply disappears from the drawing while the legend
  // reports it — a chart contradicting itself (reported 20 Nov 2026).
  const served = Number(pie.servedMarket) || 0;
  let cursor = -90;
  let colour = 0;
  const arcs = pie.slices.map((slice) => {
    const mine = slice.autoPlayer ? autoColour(slice.position) : PALETTE[colour % PALETTE.length];
    colour += 1;
    const sweep = served > 0 ? (Number(slice.sold) / served) * 360 : 0;
    const path = sweep > 0 ? wedge(cursor, sweep, OUTER) : null;
    cursor += sweep;
    return { slice, mine, sweep, path };
  });

  return (
    <figure className="market-pie">
      <figcaption>{pie.productName || pie.product}</figcaption>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={pie.productName || pie.product}>
        {/* The base disc, first so every slice sits on it. */}
        <circle cx={SIZE / 2} cy={SIZE / 2} r={OUTER} fill={BASE} />
        {arcs.map(({ slice, mine, path }, index) =>
          path ? (
            <path key={`${slice.label}-${index}`} d={path} fill={mine} data-sweep={arcs[index].sweep} />
          ) : null,
        )}
      </svg>
      {/* D121: the coverage reading moved here from the hole in the middle. The hole was where it used to
          live, and a solid pie has nowhere to put it — the legend is also where a reader looks for it. */}
      <p className="market-pie-coverage">
        {t("debrief.coverage")}: <strong>{number(coverage, 0)}%</strong>
      </p>
      <table className="market-pie-legend">
        <tbody>
          {/* One number per company: its share of the served market — the figure the ring is drawn
              from. There used to be a second column (share of what was actually sold), which is the same
              figure renormalised and identical whenever nothing is unserved. */}
          {arcs.map(({ slice, mine }, index) => (
            <tr key={`${slice.label}-${index}`}>
              <td>
                <span className="swatch" style={{ background: mine }} />
              </td>
              {/* Company name only. Nothing here says "auto". */}
              <td>{slice.label}</td>
              <td className="num">{number(Number(slice.sharePercent), 1)}</td>
            </tr>
          ))}
          {Number(pie.unserved) > 0 && (
            <tr className="muted">
              <td>
                <span className="swatch" style={{ background: "#2f3947" }} />
              </td>
              <td>{t("debrief.unserved")}</td>
              <td className="num">{number(Number(pie.unservedPercent), 1)}</td>
            </tr>
          )}
        </tbody>
      </table>
    </figure>
  );
}

export function MarketSharePies({ pies }: { pies: MarketPie[] }) {
  const { t } = useI18n();
  if (!pies.length) {
    return <p className="muted">{t("debrief.noMarket")}</p>;
  }
  return (
    <div className="market-pies">
      {pies.map((pie) => (
        <SolidPie key={pie.product} pie={pie} />
      ))}
    </div>
  );
}
