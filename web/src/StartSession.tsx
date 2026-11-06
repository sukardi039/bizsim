import { useCallback, useEffect, useState } from "react";
import { api, type CompanyProfile, type Run, type SimulationPackage } from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/**
 * Execution: start a session from a published package.
 *
 * The facilitator chooses *which prepared package* and *who is in the room* — nothing else. The
 * template, the companies and their product lines were decided by a builder, earlier, and are not
 * editable from here (ruling of 3 Nov 2026). That is the whole point of splitting the two jobs: the
 * person running the class cannot quietly change the economy the class is being graded on.
 */
export default function StartSession({ onStarted }: { onStarted: (run: Run) => void }) {
  const { t } = useI18n();
  const [packages, setPackages] = useState<SimulationPackage[]>([]);
  const [companies, setCompanies] = useState<CompanyProfile[]>([]);
  const [name, setName] = useState("");
  const [packageId, setPackageId] = useState<number | "">("");
  /** D62: which part of the programme to start; 1 when it has a single part. */
  const [part, setPart] = useState<number | "">(1);

  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [packageList, companyList] = await Promise.all([api.packages(), api.companies()]);
      const published = packageList.filter((entry) => entry.status === "PUBLISHED");
      setPackages(published);
      setCompanies(companyList);
      setPackageId((current) => current || published[0]?.id || "");
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const chosen = packages.find((entry) => entry.id === packageId);
  /**
   * A part is started once. The parts that already have a session are not offered — a second
   * session of the same part would duplicate a class that exists — and a package with nothing left
   * to start cannot be started at all.
   */
  const parts = chosen?.parts ?? [];
  const startedParts = new Set(chosen?.startedParts ?? []);
  const openParts = parts.filter((entry) => !startedParts.has(entry.position));
  const alreadyStarted = parts.filter((entry) => startedParts.has(entry.position));
  const allStarted = chosen != null && openParts.length === 0;

  // Keep the chosen part inside what is on offer: a package with part 1 begun opens on part 2,
  // and switching packages never carries a part that the new package does not have.
  useEffect(() => {
    const first: number | "" = openParts.length > 0 ? openParts[0].position : "";
    setPart((current) =>
      openParts.some((entry) => entry.position === current) ? current : first,
    );
  }, [packageId, chosen]);
  const companyNames = (entry: SimulationPackage) =>
    entry.companyProfileIds
      .map((id) => companies.find((company) => company.id === id)?.name ?? `#${id}`)
      .join(" · ");

  const start = async (event: React.FormEvent) => {
    event.preventDefault();
    if (packageId === "" || part === "") return;
    setBusy(true);
    setProblem(null);
    try {
      // The class list travels with the package; the facilitator does not re-seat anyone here.
      const run = await api.startSession(packageId, {
        name: name.trim() || chosen?.name || t("start.defaultName"),
        traineeIds: [],
        part,
      });
      setName("");
      onStarted(run);
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  if (packages.length === 0) {
    return (
      <section className="card">
        <h2>{t("start.title")}</h2>
        <p className="muted">{t("start.noPackages")}</p>
        {problem && <p className="error">{problem}</p>}
      </section>
    );
  }

  return (
    <section className="card">
      <h2>{t("start.title")}</h2>
      <p className="muted">{t("start.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <form onSubmit={start}>
        <div className="row">
          <label>
            {t("start.name")}
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={chosen?.name ?? t("start.defaultName")}
            />
          </label>
          <label>
            {t("start.package")}
            <select
              value={packageId}
              onChange={(e) => setPackageId(Number(e.target.value))}
            >
              {packages.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {parts.length > 1 && openParts.length > 0 && (
          <label>
            {t("start.part")}
            <select value={part} onChange={(e) => setPart(Number(e.target.value))}>
              {openParts.map((entry) => (
                <option key={entry.position} value={entry.position}>
                  {entry.position}. {entry.name}
                </option>
              ))}
            </select>
          </label>
        )}

        {alreadyStarted.length > 0 && (
          <p className="muted">
            {t("start.partsStarted", {
              parts: alreadyStarted
                .map((entry) => `${entry.position}. ${entry.name}`)
                .join(" · "),
            })}
          </p>
        )}
        {allStarted && <p className="error">{t("start.allPartsStarted")}</p>}

        {chosen && (
          <p className="muted">
            {t("start.competing")}: <strong>{companyNames(chosen)}</strong>
            {" · "}
            {chosen.sourceTemplateKey} v{chosen.templateVersion}
          </p>
        )}

        {chosen && <h3 className="pick-head">{t("start.classList")}</h3>}
        {chosen && <p className="muted">{t("start.classListHint")}</p>}
        <ul className="picks">
          {chosen?.companyProfileIds.map((id, index) => {
            const seat = chosen.seats?.find((entry) => entry.position === index);
            return (
              <li key={id}>
                <strong>{companies.find((company) => company.id === id)?.name ?? `#${id}`}</strong>
                {" → "}
                {seat ? `${seat.displayName}` : <span className="muted">{t("start.emptySeat")}</span>}
              </li>
            );
          })}
        </ul>

        <div className="buttons">
          <button className="primary" type="submit" disabled={busy || packageId === "" || part === ""}>
            {busy ? t("start.starting") : t("start.start")}
          </button>
        </div>
      </form>
    </section>
  );
}
