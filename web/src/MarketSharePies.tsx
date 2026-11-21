/**
 * D112: per-product market share, as a donut the facilitator can show the class.
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
const INNER = 54;
const COVER_OUTER = 94;
const COVER_INNER = 92;

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
const AUTO = "#6b7280";

/** A ring segment between two radii, `from` in degrees clockwise from twelve o'clock. */
function ring(from: number, sweep: number, outer: number, inner: number): string {
  const clamp = Math.min(sweep, 359.999);
  const a0 = ((from - 90) * Math.PI) / 180;
  const a1 = ((from + clamp - 90) * Math.PI) / 180;
  const large = clamp > 180 ? 1 : 0;
  const x = (r: number, a: number) => SIZE / 2 + r * Math.cos(a);
  const y = (r: number, a: number) => SIZE / 2 + r * Math.sin(a);
  return (
    `M ${x(outer, a0)} ${y(outer, a0)} ` +
    `A ${outer} ${outer} 0 ${large} 1 ${x(outer, a1)} ${y(outer, a1)} ` +
    `L ${x(inner, a1)} ${y(inner, a1)} ` +
    `A ${inner} ${inner} 0 ${large} 0 ${x(inner, a0)} ${y(inner, a0)} Z`
  );
}

function Donut({ pie }: { pie: MarketPie }) {
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
    const mine = slice.autoPlayer ? AUTO : PALETTE[colour % PALETTE.length];
    colour += 1;
    const sweep = served > 0 ? (Number(slice.sold) / served) * 360 : 0;
    const path = sweep > 0 ? ring(cursor, sweep, OUTER, INNER) : null;
    cursor += sweep;
    return { slice, mine, sweep, path };
  });

  return (
    <figure className="market-pie">
      <figcaption>{pie.productName || pie.product}</figcaption>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={pie.productName || pie.product}>
        {coverage > 0 && (
          <path d={ring(-90, (coverage / 100) * 360, COVER_OUTER, COVER_INNER)} />
        )}
        {arcs.map(({ slice, mine, path }, index) =>
          path ? (
            <path key={`${slice.label}-${index}`} d={path} fill={mine} data-sweep={arcs[index].sweep} />
          ) : null,
        )}
        <text
          x={SIZE / 2}
          y={SIZE / 2 - 4}
          textAnchor="middle"
          className="market-pie-centre"
        >
          {number(coverage, 0)}%
        </text>
        <text x={SIZE / 2} y={SIZE / 2 + 12} textAnchor="middle" className="market-pie-centre-sub">
          {t("debrief.coverage")}
        </text>
      </svg>
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
        <Donut key={pie.product} pie={pie} />
      ))}
    </div>
  );
}
