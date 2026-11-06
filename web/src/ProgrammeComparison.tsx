import { useEffect, useState } from "react";
import { api, type Run } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

/**
 * How the class moved between the parts of a programme (D62) — facilitator only.
 *
 * <p>No state carries between parts, so every part is a fresh race from the same opening condition.
 * That is what makes the interesting number the **delta**: what a company did this part compared with
 * the last, and whether that beat the class's own move.
 */
type Comparison = Awaited<ReturnType<typeof api.packageComparison>>;

export default function ProgrammeComparison({ run }: { run: Run }) {
  const { t, money } = useI18n();
  const [data, setData] = useState<Comparison | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!run.packageId) {
      setData(null);
      return;
    }
    let live = true;
    void (async () => {
      try {
        const next = await api.packageComparison(run.packageId as number);
        if (live) {
          setData(next);
          setProblem(null);
        }
      } catch (error) {
        if (live) setProblem(describeError(error, t));
      }
    })();
    return () => {
      live = false;
    };
  }, [run.packageId, run.currentRound, run.roundOpen, t]);

  // A single-part package is not a programme: nothing to compare.
  if (!data || data.parts.length < 2) {
    return null;
  }

  const positions = data.parts.map((part) => part.position);

  return (
    <section className="card">
      <h2>
        {t("programme.title")}
        {run.partPosition ? ` · ${t("programme.partOf", { part: run.partPosition, total: data.parts.length })}` : ""}
      </h2>
      <p className="muted">{t("programme.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <table>
        <thead>
          <tr>
            <th>{t("debrief.company")}</th>
            {positions.map((position) => (
              <th key={position} className="num">
                {t("programme.part", { position })}
              </th>
            ))}
            <th className="num">{t("programme.move")}</th>
            <th className="num">{t("programme.vsClass")}</th>
          </tr>
        </thead>
        <tbody>
          {data.companies.map((company) => {
            const last = company.parts[company.parts.length - 1];
            return (
              <tr key={company.teamId}>
                <td>{company.company}</td>
                {company.parts.map((cell) => (
                  <td key={cell.position} className="num">
                    {money(Number(cell.profit ?? 0))}
                  </td>
                ))}
                <td className={`num ${(last.delta ?? 0) >= 0 ? "" : "negative"}`}>
                  {last.delta == null ? "—" : money(Number(last.delta))}
                </td>
                <td className={`num ${(last.deltaToClass ?? 0) >= 0 ? "" : "negative"}`}>
                  {last.deltaToClass == null ? "—" : money(Number(last.deltaToClass))}
                </td>
              </tr>
            );
          })}
        </tbody>
        <tfoot>
          <tr>
            <td>{t("programme.classAverage")}</td>
            {data.parts.map((part) => (
              <td key={part.position} className="num">
                {money(Number(part.averageProfit ?? 0))}
              </td>
            ))}
            <td />
            <td />
          </tr>
        </tfoot>
      </table>

      <p className="muted">
        {data.parts
          .map((part) => `${part.position}. ${part.name}: ${part.status}${part.leader ? ` · ${part.leader}` : ""}`)
          .join(" · ")}
      </p>
    </section>
  );
}
