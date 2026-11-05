import { useCallback, useEffect, useState } from "react";
import {
  api,
  type TemplateChannel,
  type TemplateDetail,
  type TemplateEvent,
  type TemplateProduct,
  type TemplateProject,
  type TemplateRound,
} from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/** Settings the facilitator may change. Anything else in the document is engine calibration. */
const SETTINGS: { field: string; kind: "int" | "money" | "decimal" }[] = [
  { field: "rounds", kind: "int" },
  { field: "roundMinutes", kind: "int" },
  { field: "cycles", kind: "int" },
  { field: "cycleLength", kind: "int" },
  { field: "startingCash", kind: "money" },
  { field: "startingBrand", kind: "decimal" },
  { field: "fixedCostPerMonth", kind: "money" },
  { field: "brandDecay", kind: "decimal" },
  { field: "holdingCostPerUnit", kind: "decimal" },
  { field: "referenceQuality", kind: "decimal" },
  { field: "machineCostPerWorker", kind: "money" },
  { field: "hiringCostMonths", kind: "decimal" },
  { field: "severanceMonths", kind: "decimal" },
  { field: "marketReportCost", kind: "money" },
  { field: "forecastHorizon", kind: "int" },
  { field: "forecastBand", kind: "decimal" },
  { field: "minRndBenefitRatio", kind: "decimal" },
  { field: "cycleDemandDrift", kind: "decimal" },
  { field: "cycleCostDrift", kind: "decimal" },
  // D15: the price of one quality point, when the switch below is on.
  { field: "qualityCostPerPoint", kind: "money" },
];

/** Per-function numbers: salaries and starting headcount. Four functions, same keys in every template. */
const FUNCTIONS = ["PRODUCTION", "SALES", "RND", "ADMIN"] as const;

/**
 * Setup → Templates → editor (S6c).
 *
 * Two tabs, because they are two different things: **settings** are a handful of scalars merged into
 * the definition document, while the **environment** is a table of positions that is replaced whole.
 * A read-only template shows the values greyed out with the reason — the shipped economy is a
 * reference every class plays on.
 */
export default function TemplateEditor({ templateKey, onLeave }: { templateKey: string; onLeave: () => void }) {
  const { t, number: fmt, money } = useI18n();
  const [detail, setDetail] = useState<TemplateDetail | null>(null);
  const [tab, setTab] = useState<
    "settings" | "environment" | "events" | "products" | "channels" | "projects"
  >("settings");
  const [settings, setSettings] = useState<Record<string, string>>({});
  /** D15: a mechanic is a switch on the template, not a code path every class must live with. */
  const [qualityOn, setQualityOn] = useState(false);
  /** D48: opponents are a mechanic too — a template that never turns this on seats no auto worker. */
  const [autoWorkersOn, setAutoWorkersOn] = useState(false);
  const [rounds, setRounds] = useState<TemplateRound[]>([]);
  const [products, setProducts] = useState<TemplateProduct[]>([]);
  const [channels, setChannels] = useState<TemplateChannel[]>([]);
  const [projects, setProjects] = useState<TemplateProject[]>([]);
  const [events, setEvents] = useState<TemplateEvent[]>([]);
  const [salaries, setSalaries] = useState<Record<string, string>>({});
  const [staff, setStaff] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const adopt = useCallback((next: TemplateDetail) => {
    setDetail(next);
    const flat: Record<string, string> = {};
    for (const { field } of SETTINGS) {
      const value = next.settings[field];
      if (value !== undefined && value !== null) flat[field] = String(value);
    }
    // Show the loader's default rate so the builder sees what "on" would cost per point.
    if (flat.qualityCostPerPoint === undefined) flat.qualityCostPerPoint = "60000000";
    setSettings(flat);
    setQualityOn(Boolean(next.settings.qualityUpgrade));
    setAutoWorkersOn(Boolean(next.settings.autoWorkers));
    const pay = (next.settings.salaries ?? {}) as Record<string, number>;
    const headcount = (next.settings.startingStaff ?? {}) as Record<string, number>;
    setSalaries(Object.fromEntries(FUNCTIONS.map((f) => [f, String(pay[f] ?? "")])));
    setStaff(Object.fromEntries(FUNCTIONS.map((f) => [f, String(headcount[f] ?? "")])));
    setEvents(next.events ?? []);
    setRounds(next.environment);
    setProducts(next.products ?? []);
    setChannels(next.channels ?? []);
    setProjects(next.projects ?? []);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .templateDetail(templateKey)
      .then((next) => {
        if (!cancelled) adopt(next);
      })
      .catch((error) => {
        if (!cancelled) setProblem(describeError(error, t));
      });
    return () => {
      cancelled = true;
    };
  }, [templateKey, adopt, t]);

  const saveSettings = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      const payload: Record<string, unknown> = {};
      for (const { field, kind } of SETTINGS) {
        const raw = settings[field];
        if (raw === undefined || raw === "") continue;
        payload[field] = kind === "int" ? Number.parseInt(raw, 10) : Number(raw);
      }
      payload.qualityUpgrade = qualityOn;
      payload.autoWorkers = autoWorkersOn;
      // The per-function maps go in as objects; a blank cell is simply not sent, so the stored value
      // survives rather than being zeroed by a form the builder never touched.
      const payMap: Record<string, number> = {};
      for (const f of FUNCTIONS) if (salaries[f]) payMap[f] = Number(salaries[f]);
      if (Object.keys(payMap).length > 0) payload.salaries = payMap;
      const staffMap: Record<string, number> = {};
      for (const f of FUNCTIONS) if (staff[f]) staffMap[f] = Number.parseInt(staff[f], 10);
      if (Object.keys(staffMap).length > 0) payload.startingStaff = staffMap;
      adopt(await api.saveTemplateSettings(templateKey, payload));
      setNotice(t("editor.saved"));
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  const saveEnvironment = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      adopt(await api.saveTemplateEnvironment(templateKey, rounds));
      setNotice(t("editor.saved"));
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  const saveCatalogue = async (
    event: React.FormEvent,
    call: () => Promise<TemplateDetail>,
  ) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      adopt(await call());
      setNotice(t("editor.saved"));
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  const editRound = (index: number, patch: Partial<TemplateRound>) => {
    setRounds((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  };

  const addRound = () => {
    const last = rounds[rounds.length - 1];
    setRounds((current) => [
      ...current,
      {
        round: current.length + 1,
        demandIndex: last?.demandIndex ?? 1,
        costIndex: last?.costIndex ?? 1,
        wageIndex: last?.wageIndex ?? 1,
        trendStrength: 0,
        trendLabel: "",
        note: "",
      },
    ]);
  };

  const removeRound = () => {
    setRounds((current) =>
      current.slice(0, -1).map((row, i) => ({ ...row, round: i + 1 })),
    );
  };

  if (!detail) {
    return (
      <section className="card">
        <p className="muted">{problem ?? t("editor.loading")}</p>
        <button onClick={onLeave}>{t("editor.back")}</button>
      </section>
    );
  }

  const locked = !detail.editable;

  return (
    <>
      <section className="card">
        <header className="run-head">
          <div>
            <h2>{detail.name}</h2>
            <p className="muted">
              {detail.key} · {detail.tenantId == null ? t("templates.global") : t("templates.yours")} ·{" "}
              {detail.status.toLowerCase()}
              {detail.basedOn ? ` · ${t("templates.basedOn")} ${detail.basedOn}` : ""}
            </p>
          </div>
          <div className="buttons">
            <button onClick={onLeave}>{t("editor.back")}</button>
          </div>
        </header>

        {locked && <p className="banner">{t("editor.readOnly")}</p>}
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}

        <div className="nav">
          <button className={tab === "settings" ? "active" : ""} onClick={() => setTab("settings")}>
            {t("editor.settings")}
          </button>
          <button
            className={tab === "environment" ? "active" : ""}
            onClick={() => setTab("environment")}
          >
            {t("editor.environment")}
          </button>
          <button className={tab === "events" ? "active" : ""} onClick={() => setTab("events")}>
            {t("editor.events")}
          </button>
          <button className={tab === "products" ? "active" : ""} onClick={() => setTab("products")}>
            {t("editor.products")}
          </button>
          <button className={tab === "channels" ? "active" : ""} onClick={() => setTab("channels")}>
            {t("editor.channels")}
          </button>
          <button className={tab === "projects" ? "active" : ""} onClick={() => setTab("projects")}>
            {t("editor.projects")}
          </button>
        </div>

        {tab === "settings" ? (
          <form onSubmit={saveSettings}>
            <div className="settings-grid">
              {SETTINGS.map(({ field, kind }) => (
                <label key={field}>
                  {t(`field.${field}`)}
                  <input
                    value={settings[field] ?? ""}
                    onChange={(e) =>
                      setSettings((current) => ({ ...current, [field]: e.target.value }))
                    }
                    disabled={locked}
                    inputMode={kind === "int" ? "numeric" : "decimal"}
                  />
                  {kind === "money" && settings[field] ? (
                    <small className="muted">{money(Number(settings[field]))}</small>
                  ) : null}
                </label>
              ))}
            </div>
            <label className="check">
              <input
                type="checkbox"
                checked={qualityOn}
                disabled={locked}
                onChange={(e) => setQualityOn(e.target.checked)}
              />
              {t("editor.qualityUpgrade")}
            </label>
            <p className="muted">{t("editor.qualityHint")}</p>
            <label className="check">
              <input
                type="checkbox"
                checked={autoWorkersOn}
                disabled={locked}
                onChange={(e) => setAutoWorkersOn(e.target.checked)}
              />
              {t("editor.autoWorkers")}
            </label>
            <p className="muted">{t("editor.autoWorkersHint")}</p>
            <h3 className="pick-head">{t("editor.perFunction")}</h3>
            <table>
              <thead>
                <tr>
                  <th>{t("editor.function")}</th>
                  <th className="num">{t("field.salaries")}</th>
                  <th className="num">{t("field.startingStaff")}</th>
                </tr>
              </thead>
              <tbody>
                {FUNCTIONS.map((f) => (
                  <tr key={f}>
                    <td>{t(`fn.${f}`)}</td>
                    <td>
                      <input
                        value={salaries[f] ?? ""}
                        disabled={locked}
                        onChange={(e) => setSalaries((c) => ({ ...c, [f]: e.target.value }))}
                      />
                      {salaries[f] ? <small className="muted">{money(Number(salaries[f]))}</small> : null}
                    </td>
                    <td>
                      <input
                        value={staff[f] ?? ""}
                        disabled={locked}
                        onChange={(e) => setStaff((c) => ({ ...c, [f]: e.target.value }))}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : tab === "environment" ? (
          <form onSubmit={saveEnvironment}>
            <p className="muted">{t("editor.environmentHint")}</p>
            <table>
              <thead>
                <tr>
                  <th>{t("editor.round")}</th>
                  <th className="num">{t("field.demandIndex")}</th>
                  <th className="num">{t("field.costIndex")}</th>
                  <th className="num">{t("field.wageIndex")}</th>
                  <th className="num">{t("field.trendStrength")}</th>
                  <th>{t("field.trendLabel")}</th>
                  <th>{t("field.note")}</th>
                </tr>
              </thead>
              <tbody>
                {rounds.map((row, index) => (
                  <tr key={row.round}>
                    <td>{row.round}</td>
                    <td>
                      <input
                        value={String(row.demandIndex)}
                        onChange={(e) => editRound(index, { demandIndex: Number(e.target.value) })}
                        disabled={locked}
                      />
                    </td>
                    <td>
                      <input
                        value={String(row.costIndex)}
                        onChange={(e) => editRound(index, { costIndex: Number(e.target.value) })}
                        disabled={locked}
                      />
                    </td>
                    <td>
                      <input
                        value={String(row.wageIndex)}
                        onChange={(e) => editRound(index, { wageIndex: Number(e.target.value) })}
                        disabled={locked}
                      />
                    </td>
                    <td>
                      <input
                        value={String(row.trendStrength)}
                        onChange={(e) => editRound(index, { trendStrength: Number(e.target.value) })}
                        disabled={locked}
                      />
                    </td>
                    <td>
                      <input
                        value={row.trendLabel}
                        onChange={(e) => editRound(index, { trendLabel: e.target.value })}
                        disabled={locked}
                      />
                    </td>
                    <td>
                      <input
                        value={row.note}
                        onChange={(e) => editRound(index, { note: e.target.value })}
                        disabled={locked}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="muted">
              {t("editor.rounds", { count: rounds.length, formatted: fmt(rounds.length, 0) })}
            </p>
            <div className="buttons">
              <button type="button" onClick={addRound} disabled={locked || rounds.length >= 48}>
                {t("editor.addRound")}
              </button>
              <button type="button" onClick={removeRound} disabled={locked || rounds.length <= 1}>
                {t("editor.removeRound")}
              </button>
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : tab === "events" ? (
          <form onSubmit={(event) => saveCatalogue(event, () => api.saveTemplateEvents(templateKey, events))}>
            <p className="muted">{t("editor.eventsHint")}</p>
            <table>
              <thead>
                <tr>
                  <th>{t("field.code")}</th>
                  <th>{t("field.name")}</th>
                  <th>{t("field.description")}</th>
                  <th className="num">{t("field.earliestRound")}</th>
                  <th className="num">{t("field.latestRound")}</th>
                  <th className="num">{t("field.probability")}</th>
                  <th className="num">{t("field.durationMonths")}</th>
                  <th className="num">{t("field.demandMultiplier")}</th>
                  <th className="num">{t("field.costMultiplier")}</th>
                  <th className="num">{t("field.wageMultiplier")}</th>
                  <th>{t("field.targetProduct")}</th>
                </tr>
              </thead>
              <tbody>
                {events.map((row, index) => {
                  const edit = (patch: Partial<TemplateEvent>) =>
                    setEvents((c) => c.map((r, i) => (i === index ? { ...r, ...patch } : r)));
                  return (
                    <tr key={index}>
                      <td><input value={row.code} disabled={locked} onChange={(e) => edit({ code: e.target.value })} /></td>
                      <td><input value={row.name} disabled={locked} onChange={(e) => edit({ name: e.target.value })} /></td>
                      <td><input value={row.description} disabled={locked} onChange={(e) => edit({ description: e.target.value })} /></td>
                      <td><input value={String(row.earliestRound)} disabled={locked} onChange={(e) => edit({ earliestRound: Number(e.target.value) })} /></td>
                      <td><input value={String(row.latestRound)} disabled={locked} onChange={(e) => edit({ latestRound: Number(e.target.value) })} /></td>
                      <td><input value={String(row.probability)} disabled={locked} onChange={(e) => edit({ probability: Number(e.target.value) })} /></td>
                      <td><input value={String(row.durationMonths)} disabled={locked} onChange={(e) => edit({ durationMonths: Number(e.target.value) })} /></td>
                      <td><input value={String(row.demandMultiplier)} disabled={locked} onChange={(e) => edit({ demandMultiplier: Number(e.target.value) })} /></td>
                      <td><input value={String(row.costMultiplier)} disabled={locked} onChange={(e) => edit({ costMultiplier: Number(e.target.value) })} /></td>
                      <td><input value={String(row.wageMultiplier)} disabled={locked} onChange={(e) => edit({ wageMultiplier: Number(e.target.value) })} /></td>
                      <td>
                        <select
                          value={row.targetProduct ?? ""}
                          disabled={locked}
                          onChange={(e) => edit({ targetProduct: e.target.value || null })}
                        >
                          <option value="">{t("editor.allProducts")}</option>
                          {products.map((product) => (
                            <option key={product.code} value={product.code}>{product.code}</option>
                          ))}
                        </select>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="buttons">
              <button
                type="button"
                disabled={locked}
                onClick={() =>
                  setEvents((c) => [
                    ...c,
                    {
                      code: "",
                      name: "",
                      description: "",
                      earliestRound: 2,
                      latestRound: 6,
                      probability: 0.4,
                      durationMonths: 2,
                      demandMultiplier: 1,
                      costMultiplier: 1,
                      wageMultiplier: 1,
                      targetProduct: null,
                    },
                  ])
                }
              >
                {t("editor.addRow")}
              </button>
              <button type="button" disabled={locked || events.length === 0} onClick={() => setEvents((c) => c.slice(0, -1))}>
                {t("editor.removeRow")}
              </button>
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : tab === "products" ? (
          <form onSubmit={(event) => saveCatalogue(event, () => api.saveTemplateProducts(templateKey, products))}>
            <p className="muted">{t("editor.catalogueHint")}</p>
            <table>
              <thead>
                <tr>
                  <th>{t("field.code")}</th>
                  <th>{t("field.name")}</th>
                  <th className="num">{t("field.basePrice")}</th>
                  <th className="num">{t("field.baseUnitCost")}</th>
                  <th className="num">{t("field.baseQuality")}</th>
                  <th className="num">{t("field.baseDemand")}</th>
                  <th className="num">{t("field.unitsPerWorker")}</th>
                  <th className="num">{t("field.incomeElasticity")}</th>
                  <th className="num">{t("field.trendAffinity")}</th>
                </tr>
              </thead>
              <tbody>
                {products.map((row, index) => (
                  <tr key={index}>
                    <td><input value={row.code} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, code: e.target.value } : r))} /></td>
                    <td><input value={row.name} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, name: e.target.value } : r))} /></td>
                    <td><input value={String(row.basePrice)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, basePrice: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseUnitCost)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseUnitCost: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseQuality)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseQuality: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseDemand)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseDemand: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.unitsPerWorker)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, unitsPerWorker: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.incomeElasticity)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, incomeElasticity: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.trendAffinity)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, trendAffinity: Number(e.target.value) } : r))} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setProducts((c) => [...c, { code: "", name: "", basePrice: 1, baseUnitCost: 1, baseQuality: 50, baseDemand: 100, unitsPerWorker: 40, incomeElasticity: 1, trendAffinity: 0 }])}>
                {t("editor.addRow")}
              </button>
              <button type="button" disabled={locked || products.length <= 1} onClick={() => setProducts((c) => c.slice(0, -1))}>
                {t("editor.removeRow")}
              </button>
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : tab === "channels" ? (
          <form onSubmit={(event) => saveCatalogue(event, () => api.saveTemplateChannels(templateKey, channels))}>
            <table>
              <thead>
                <tr>
                  <th>{t("field.code")}</th>
                  <th>{t("field.name")}</th>
                  <th className="num">{t("field.reachWeight")}</th>
                  <th className="num">{t("field.brandAlpha")}</th>
                </tr>
              </thead>
              <tbody>
                {channels.map((row, index) => (
                  <tr key={index}>
                    <td><input value={row.code} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, code: e.target.value } : r))} /></td>
                    <td><input value={row.name} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, name: e.target.value } : r))} /></td>
                    <td><input value={String(row.reachWeight)} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, reachWeight: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.brandAlpha)} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, brandAlpha: Number(e.target.value) } : r))} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setChannels((c) => [...c, { code: "", name: "", reachWeight: 1, brandAlpha: 1 }])}>
                {t("editor.addRow")}
              </button>
              <button type="button" disabled={locked || channels.length <= 1} onClick={() => setChannels((c) => c.slice(0, -1))}>
                {t("editor.removeRow")}
              </button>
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : (
          <form onSubmit={(event) => saveCatalogue(event, () => api.saveTemplateProjects(templateKey, projects))}>
            <table>
              <thead>
                <tr>
                  <th>{t("field.code")}</th>
                  <th>{t("field.name")}</th>
                  <th>{t("field.targetProduct")}</th>
                  <th>{t("field.advantageType")}</th>
                  <th className="num">{t("field.advantageValue")}</th>
                  <th className="num">{t("field.hiddenCost")}</th>
                  <th className="num">{t("field.durationMonths")}</th>
                  <th>{t("field.benefitCurve")}</th>
                  <th>{t("field.overfunding")}</th>
                </tr>
              </thead>
              <tbody>
                {projects.map((row, index) => (
                  <tr key={index}>
                    <td><input value={row.code} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, code: e.target.value } : r))} /></td>
                    <td><input value={row.name} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, name: e.target.value } : r))} /></td>
                    <td>
                      <select value={row.targetProduct ?? ""} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, targetProduct: e.target.value || null } : r))}>
                        <option value="">{t("editor.allProducts")}</option>
                        {products.map((product) => (
                          <option key={product.code} value={product.code}>{product.code}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <select value={row.advantageType} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, advantageType: e.target.value } : r))}>
                        {["UNIT_COST_DOWN", "CAPACITY_UP", "QUALITY_UP", "ATTRACTIVENESS_UP"].map((type) => (
                          <option key={type} value={type}>{type}</option>
                        ))}
                      </select>
                    </td>
                    <td><input value={String(row.advantageValue)} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, advantageValue: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.hiddenCost)} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, hiddenCost: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.durationMonths)} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, durationMonths: Number(e.target.value) } : r))} /></td>
                    <td>
                      <select value={row.benefitCurve} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, benefitCurve: e.target.value } : r))}>
                        {["LINEAR", "POWER", "CHANCE"].map((curve) => (
                          <option key={curve} value={curve}>{curve}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <select value={row.overfunding} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, overfunding: e.target.value } : r))}>
                        {["REFUND", "LOST"].map((mode) => (
                          <option key={mode} value={mode}>{mode}</option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setProjects((c) => [...c, { code: "", name: "", targetProduct: null, advantageType: "UNIT_COST_DOWN", advantageValue: 0.1, hiddenCost: 0, hintBand: 0.2, durationMonths: 3, partialBenefit: true, benefitCurve: "LINEAR", benefitExponent: 1, overfunding: "LOST" }])}>
                {t("editor.addRow")}
              </button>
              <button type="button" disabled={locked || projects.length <= 1} onClick={() => setProjects((c) => c.slice(0, -1))}>
                {t("editor.removeRow")}
              </button>
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        )}
      </section>
    </>
  );
}
