/**
 * D115: the debrief's per-class table — what each company won in each customer class, product by
 * product.
 *
 * <p>**Why a table and not a chart.** Three products × three classes × up to ten companies is a matrix,
 * and a matrix is read cell by cell ("we won a third of the mass market on P1 and almost nothing on
 * P3"). The pies beside it already answer "who holds this product"; this answers "who holds this
 * *class*", which is the question segment-weighted marketing makes askable.
 *
 * <p>**The number is demand won, not units sold**, and it is labelled that way. Stock is a
 * company-level pool, so a company that stocked out still won its share of the class and sold less;
 * its shortfall is on the product line as lost sales. Calling this "sales" would contradict the
 * company row above the table, which is drawn on units sold.
 */
import { useI18n } from "./i18n";
import type { ClassShareRow } from "./api";

export default function ClassShares({ weighted, rows }: { weighted: boolean; rows: ClassShareRow[] }) {
  const { t, number } = useI18n();
  if (!weighted) {
    // A class-blind template has one pool: there is no breakdown to show. Say so rather than leaving a
    // facilitator to wonder where the table went — D101's rule for the position map, applied here: a chart
    // that has nothing to say must admit it, and so must this one.
    return (
      <section className="class-shares">
        <h3>{t("debrief.classTitle")}</h3>
        <p className="muted">{t("debrief.classNotWeighted")}</p>
      </section>
    );
  }
  if (rows.length === 0) {
    // Weighted, but this round carries no class rows: it was computed before the breakdown was recorded,
    // and the allocation was never stored. Saying so is the honest alternative to an empty panel a
    // facilitator would read as "the feature is broken" — or to reconstructing numbers nobody measured.
    return (
      <section className="class-shares">
        <h3>{t("debrief.classTitle")}</h3>
        <p className="muted">{t("debrief.classOldRound")}</p>
      </section>
    );
  }

  // One table per product, in the order the server sent them (template order), so a facilitator reads
  // the same layout every round.
  const byProduct = new Map<string, ClassShareRow[]>();
  for (const row of rows) {
    const list = byProduct.get(row.product) ?? [];
    list.push(row);
    byProduct.set(row.product, list);
  }

  return (
    <section className="class-shares">
      <h3>{t("debrief.classTitle")}</h3>
      <p className="muted">{t("debrief.classHint")}</p>
      {[...byProduct.entries()].map(([product, cells]) => {
        // The classes of this product, in template order, and every company that appears in any of them.
        const classes = cells.map((cell) => ({
          code: cell.segment,
          name: cell.segmentName,
          market: Number(cell.market ?? 0),
        }));
        const companies = new Map<number, string>();
        for (const cell of cells) {
          for (const company of cell.companies) {
            companies.set(company.teamId, company.company);
          }
        }
        const award = (teamId: number, segment: string) =>
          cells
            .find((cell) => cell.segment === segment)
            ?.companies.find((company) => company.teamId === teamId) ?? null;

        return (
          <div key={product} className="class-share-block">
            <h4>{cells[0]?.productName || product}</h4>
            <table>
              <thead>
                <tr>
                  <th>{t("debrief.company")}</th>
                  {classes.map((row) => (
                    <th key={row.code} className="num">
                      {row.name}
                      <small className="muted" style={{ display: "block", fontWeight: 400 }}>
                        {t("debrief.classMarket")} {number(row.market, 0)}
                      </small>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[...companies.entries()].map(([teamId, name]) => (
                  <tr key={teamId}>
                    <td>{name}</td>
                    {classes.map((row) => {
                      const cell = award(teamId, row.code);
                      return (
                        <td key={row.code} className="num">
                          {cell === null ? (
                            <span className="muted">—</span>
                          ) : (
                            <>
                              <strong>{number(cell.share, 1)}%</strong>
                              <small className="muted" style={{ display: "block", fontWeight: 400 }}>
                                {number(cell.demand, 0)}
                              </small>
                            </>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
      })}
    </section>
  );
}
