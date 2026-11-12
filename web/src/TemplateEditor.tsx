import { useCallback, useEffect, useState } from "react";
import {
  api,
  type TemplateChannel,
  type TemplateDetail,
  type TemplateEvent,
  type TemplateProduct,
  type TemplateProject,
  type TemplateRound,
  type TemplateSegment,
} from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

const LANGUAGE_OPTIONS = ["en", "zh"];

/** The language of a set row (D86): two lowercase letters, with the shipped ones offered. */
function LanguageCell({ value, disabled, onChange }: {
  value?: string | null;
  disabled: boolean;
  onChange: (lang: string) => void;
}) {
  return (
    <input
      value={value ?? "en"}
      maxLength={2}
      size={3}
      disabled={disabled}
      list="bizsim-languages"
      onChange={(e) => onChange(e.target.value.toLowerCase().replace(/[^a-z]/g, ""))}
    />
  );
}

/**
 * The words of one object in each language (D87). A product's identity and numbers stay single; only
 * its name changes with the reader, so the editor edits a small list rather than duplicating the row.
 */
function TextsCell({ texts, disabled, onChange, withDescription = true }: {
  texts?: { lang: string; name: string; description?: string | null }[];
  disabled: boolean;
  onChange: (texts: { lang: string; name: string; description?: string | null }[]) => void;
  withDescription?: boolean;
}) {
  const { t } = useI18n();
  const rows = texts ?? [];
  const update = (at: number, patch: { lang?: string; name?: string; description?: string }) =>
    onChange(rows.map((row, i) => (i === at ? { ...row, ...patch } : row)));
  return (
    <div className="texts-cell">
      {rows.map((row, at) => (
        <div key={at} className="text-row">
          <LanguageCell
            value={row.lang}
            disabled={disabled}
            onChange={(lang) => update(at, { lang })}
          />
          <input
            value={row.name}
            placeholder={t("field.name")}
            disabled={disabled}
            onChange={(e) => update(at, { name: e.target.value })}
          />
          {withDescription && (
            <input
              value={row.description ?? ""}
              placeholder={t("field.description")}
              disabled={disabled}
              onChange={(e) => update(at, { description: e.target.value })}
            />
          )}
          <button
            type="button"
            className="ghost"
            disabled={disabled}
            title={t("editor.removeThisRow")}
            aria-label={t("editor.removeThisRow")}
            onClick={() => onChange(rows.filter((_, i) => i !== at))}
          >
            ✕
          </button>
        </div>
      ))}
      <button
        type="button"
        className="ghost"
        disabled={disabled}
        onClick={() => onChange([...rows, { lang: "", name: "", description: "" }])}
      >
        {t("editor.addLanguage")}
      </button>
    </div>
  );
}


function CopyLanguageSet({ languages, disabled, onCopy }: {
  languages: string[];
  disabled: boolean;
  onCopy: (from: string, to: string) => void;
}) {
  const { t } = useI18n();
  const [from, setFrom] = useState(languages[0] ?? "en");
  const [to, setTo] = useState("");
  const known = Array.from(new Set([...languages, ...LANGUAGE_OPTIONS])).sort();
  return (
    <span className="copy-set">
      <label>
        {t("editor.copyFrom")}
        <select value={from} disabled={disabled} onChange={(e) => setFrom(e.target.value)}>
          {known.map((lang) => <option key={lang} value={lang}>{lang}</option>)}
        </select>
      </label>
      <label>
        {t("editor.copyTo")}
        <input
          value={to}
          maxLength={2}
          size={3}
          placeholder="ms"
          disabled={disabled}
          onChange={(e) => setTo(e.target.value.toLowerCase().replace(/[^a-z]/g, ""))}
        />
      </label>
      <button
        type="button"
        disabled={disabled || to.length !== 2}
        onClick={() => onCopy(from, to)}
      >
        {t("editor.copySet")}
      </button>
    </span>
  );
}


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
  { field: "guidedRounds", kind: "int" },
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
    "settings" | "environment" | "segments" | "events" | "products" | "channels" | "projects"
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
  /** D81: the customer classes the economy moves; each product serves one. */
  const [segments, setSegments] = useState<TemplateSegment[]>([]);
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
    setSegments(next.segments ?? []);
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

  /** D86: duplicate one language's cycle into another, keeping the indices and adding the words. */
  const copyRounds = (from: string, to: string) => {
    setRounds((current) => {
      const source = current.filter((row) => row.lang === from);
      if (source.length === 0) {
        return current;
      }
      return [...current.filter((row) => row.lang !== to),
        ...source.map((row) => ({ ...row, lang: to, description: row.description ?? "" }))];
    });
  };

  /** D86: duplicate one language's deck into another; codes take the target suffix, effects copy. */
  const copyEvents = (from: string, to: string) => {
    setEvents((current) => {
      const source = current.filter((row) => row.lang === from);
      if (source.length === 0) {
        return current;
      }
      return [...current.filter((row) => row.lang !== to),
        ...source.map((row) => ({
          ...row,
          lang: to,
          code: `${row.code.replace(/-[A-Z]{2}$/, "")}-${to.toUpperCase()}`,
          effects: (row.effects ?? []).map((effect) => ({ ...effect })),
        }))];
    });
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

  /**
   * D86: rows belong to a language set, so a round is added to the language of the last row (the
   * set a builder is currently working on), keeping every set the same length.
   */
  const addRound = () => {
    const last = rounds[rounds.length - 1];
    const lang = last?.lang ?? "en";
    setRounds((current) => [
      ...current,
      {
        lang,
        round: current.filter((row) => row.lang === lang).length + 1,
        demandIndex: last?.demandIndex ?? 1,
        costIndex: last?.costIndex ?? 1,
        wageIndex: last?.wageIndex ?? 1,
        trendStrength: 0,
        trendLabel: "",
        note: "",
        description: "",
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
      <datalist id="bizsim-languages">
        {LANGUAGE_OPTIONS.map((lang) => <option key={lang} value={lang} />)}
      </datalist>
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
          <button className={tab === "segments" ? "active" : ""} onClick={() => setTab("segments")}>
            {t("editor.segments")}
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
                  <th>{t("field.language")}</th>
                  <th>{t("editor.round")}</th>
                  <th className="num">{t("field.demandIndex")}</th>
                  <th className="num">{t("field.costIndex")}</th>
                  <th className="num">{t("field.wageIndex")}</th>
                  <th className="num">{t("field.trendStrength")}</th>
                  <th>{t("field.trendLabel")}</th>
                  <th>{t("field.note")}</th>
                  <th>{t("field.description")}</th>
                </tr>
              </thead>
              <tbody>
                {rounds.map((row, index) => (
                  <tr key={`${row.lang}-${row.round}`}>
                    <td>
                      <LanguageCell
                        value={row.lang}
                        disabled={locked}
                        onChange={(lang) => editRound(index, { lang })}
                      />
                    </td>
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
                    <td>
                      <input
                        value={row.description ?? ""}
                        placeholder={t("editor.marketDescriptionPlaceholder")}
                        onChange={(e) => editRound(index, { description: e.target.value })}
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
              <CopyLanguageSet
                languages={Array.from(new Set(rounds.map((row) => row.lang)))}
                disabled={locked}
                onCopy={copyRounds}
              />
              <button className="primary" type="submit" disabled={locked || busy}>
                {busy ? t("editor.saving") : t("editor.save")}
              </button>
            </div>
          </form>
        ) : tab === "segments" ? (
          <form onSubmit={(event) => saveCatalogue(event, () => api.saveTemplateSegments(templateKey, segments))}>
            <p className="muted">{t("editor.segmentsHint")}</p>
            <table>
              <thead>
                <tr>
                  <th>{t("field.code")}</th>
                  <th>{t("field.name")}</th>
                  <th>{t("field.description")}</th>
                  <th>{t("editor.translations")}</th>
                  <th className="num">{t("field.baseSize")}</th>
                  <th className="num">{t("field.purchasingPower")}</th>
                  <th className="num">{t("field.incomeElasticity")}</th>
                  <th className="num">{t("field.trendAffinity")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {segments.map((row, index) => {
                  const edit = (patch: Partial<TemplateSegment>) =>
                    setSegments((c) => c.map((r, i) => (i === index ? { ...r, ...patch } : r)));
                  return (
                    <tr key={index}>
                      <td><input value={row.code} disabled={locked} onChange={(e) => edit({ code: e.target.value })} /></td>
                      <td><input value={row.name} disabled={locked} onChange={(e) => edit({ name: e.target.value })} /></td>
                      <td><input value={row.description ?? ""} disabled={locked} onChange={(e) => edit({ description: e.target.value })} /></td>
                      <td>
                        <TextsCell
                          texts={row.texts}
                          disabled={locked}
                          onChange={(texts) => edit({ texts })}
                        />
                      </td>
                      <td><input className="num" value={String(row.baseSize)} disabled={locked} onChange={(e) => edit({ baseSize: Number(e.target.value) })} /></td>
                      <td><input className="num" value={String(row.basePurchasingPower)} disabled={locked} onChange={(e) => edit({ basePurchasingPower: Number(e.target.value) })} /></td>
                      <td><input className="num" value={String(row.incomeElasticity)} disabled={locked} onChange={(e) => edit({ incomeElasticity: Number(e.target.value) })} /></td>
                      <td><input className="num" value={String(row.trendAffinity)} disabled={locked} onChange={(e) => edit({ trendAffinity: Number(e.target.value) })} /></td>
                      <td>
                        <button type="button" className="ghost" disabled={locked || segments.length <= 1}
                          title={t("editor.removeThisRow")}
                          aria-label={t("editor.removeThisRow")}
                          onClick={() => setSegments((c) => c.filter((_, at) => at !== index))}>
                          ✕
                        </button>
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
                  setSegments((c) => [
                    ...c,
                    {
                      code: "",
                      name: "",
                      description: "",
                      texts: [],
                      baseSize: 1000,
                      basePurchasingPower: 1,
                      incomeElasticity: 1,
                      trendAffinity: 0,
                      sortOrder: c.length + 1,
                    },
                  ])
                }
              >
                {t("editor.addRow")}
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
                  <th>{t("field.language")}</th>
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
                  <th>{t("field.category")}</th>
                  {segments.map((segment) => (
                    <th key={segment.code} className="num">
                      {segment.code}
                      <div className="muted"><small>{t("field.eventEffect")}</small></div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {events.map((row, index) => {
                  const edit = (patch: Partial<TemplateEvent>) =>
                    setEvents((c) => c.map((r, i) => (i === index ? { ...r, ...patch } : r)));
                  return (
                    <tr key={index}>
                      <td>
                        <LanguageCell
                          value={row.lang}
                          disabled={locked}
                          onChange={(lang) => edit({ lang })}
                        />
                      </td>
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
                      <td>
                        <select
                          value={row.category ?? "OTHER"}
                          disabled={locked}
                          onChange={(e) => edit({ category: e.target.value })}
                        >
                          {["SUPPLY_SHOCK", "FINANCIAL_CRISIS", "INFLATION", "TECHNOLOGY",
                            "DEMAND_SHOCK", "POLICY", "MARKETS", "OTHER"].map((category) => (
                            <option key={category} value={category}>{t(`category.${category}`)}</option>
                          ))}
                        </select>
                      </td>
                      {segments.map((segment) => {
                        const effect = (row.effects ?? []).find((x) => x.segmentCode === segment.code);
                        const change = (patch: { sizeMultiplier?: number; purchasingPowerMultiplier?: number }) => {
                          const rest = (row.effects ?? []).filter((x) => x.segmentCode !== segment.code);
                          const next = {
                            segmentCode: segment.code,
                            sizeMultiplier: effect?.sizeMultiplier ?? 1,
                            purchasingPowerMultiplier: effect?.purchasingPowerMultiplier ?? 1,
                            ...patch,
                          };
                          edit({ effects: [...rest, next] });
                        };
                        return (
                          <td key={segment.code} className="num">
                            <input
                              className="num"
                              title={t("field.sizeMultiplier")}
                              value={String(effect?.sizeMultiplier ?? 1)}
                              disabled={locked}
                              onChange={(e) => change({ sizeMultiplier: Number(e.target.value) })}
                            />
                            <input
                              className="num"
                              title={t("field.purchasingPowerMultiplier")}
                              value={String(effect?.purchasingPowerMultiplier ?? 1)}
                              disabled={locked}
                              onChange={(e) => change({ purchasingPowerMultiplier: Number(e.target.value) })}
                            />
                          </td>
                        );
                      })}
                      <td>
                        <button type="button" className="ghost" disabled={locked || events.length <= 1}
                          title={t("editor.removeThisRow")}
                          aria-label={t("editor.removeThisRow")}
                          onClick={() => setEvents((c) => c.filter((_, at) => at !== index))}>
                          ✕
                        </button>
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
                      lang: events[events.length - 1]?.lang ?? "en",
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
                      category: "OTHER",
                      effects: [],
                    },
                  ])
                }
              >
                {t("editor.addRow")}
              </button>
              <CopyLanguageSet
                languages={Array.from(new Set(events.map((row) => row.lang)))}
                disabled={locked}
                onCopy={copyEvents}
              />
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
                  <th>{t("editor.translations")}</th>
                  <th className="num">{t("field.basePrice")}</th>
                  <th className="num">{t("field.baseUnitCost")}</th>
                  <th className="num">{t("field.baseQuality")}</th>
                  <th className="num">{t("field.baseDemand")}</th>
                  <th className="num">{t("field.unitsPerWorker")}</th>
                  <th className="num">{t("field.incomeElasticity")}</th>
                  <th className="num">{t("field.trendAffinity")}</th>
                  <th>{t("field.targetSegment")}</th>
                  <th className="num">{t("field.crossSegmentPct")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {products.map((row, index) => (
                  <tr key={index}>
                    <td><input value={row.code} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, code: e.target.value } : r))} /></td>
                    <td><input value={row.name} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, name: e.target.value } : r))} /></td>
                    <td>
                      <TextsCell
                        texts={row.texts}
                        disabled={locked}
                        withDescription={false}
                        onChange={(texts) => setProducts((c) => c.map((r, i) => i === index ? { ...r, texts } : r))}
                      />
                    </td>
                    <td><input value={String(row.basePrice)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, basePrice: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseUnitCost)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseUnitCost: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseQuality)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseQuality: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.baseDemand)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, baseDemand: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.unitsPerWorker)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, unitsPerWorker: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.incomeElasticity)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, incomeElasticity: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.trendAffinity)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, trendAffinity: Number(e.target.value) } : r))} /></td>
                    <td>
                      <select
                        value={row.targetSegment ?? ""}
                        disabled={locked}
                        onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, targetSegment: e.target.value || null } : r))}
                      >
                        <option value="">{t("editor.legacyPool")}</option>
                        {segments.map((segment) => (
                          <option key={segment.code} value={segment.code}>{segment.code}</option>
                        ))}
                      </select>
                    </td>
                    <td><input className="num" value={String(row.crossSegmentPct ?? 0)} disabled={locked} onChange={(e) => setProducts((c) => c.map((r, i) => i === index ? { ...r, crossSegmentPct: Number(e.target.value) } : r))} /></td>
                                        <td>
                        <button type="button" className="ghost" disabled={locked || products.length <= 1}
                          title={t("editor.removeThisRow")}
                          aria-label={t("editor.removeThisRow")}
                          onClick={() => setProducts((c) => c.filter((_, at) => at !== index))}>
                          ✕
                        </button>
                      </td>
</tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setProducts((c) => [...c, { code: "", name: "", basePrice: 1, baseUnitCost: 1, baseQuality: 50, baseDemand: 100, unitsPerWorker: 40, incomeElasticity: 1, trendAffinity: 0, targetSegment: null, crossSegmentPct: 0, texts: [] }])}>
                {t("editor.addRow")}
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
                  <th />
                </tr>
              </thead>
              <tbody>
                {channels.map((row, index) => (
                  <tr key={index}>
                    <td><input value={row.code} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, code: e.target.value } : r))} /></td>
                    <td><input value={row.name} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, name: e.target.value } : r))} /></td>
                    <td><input value={String(row.reachWeight)} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, reachWeight: Number(e.target.value) } : r))} /></td>
                    <td><input value={String(row.brandAlpha)} disabled={locked} onChange={(e) => setChannels((c) => c.map((r, i) => i === index ? { ...r, brandAlpha: Number(e.target.value) } : r))} /></td>
                                        <td>
                        <button type="button" className="ghost" disabled={locked || channels.length <= 1}
                          title={t("editor.removeThisRow")}
                          aria-label={t("editor.removeThisRow")}
                          onClick={() => setChannels((c) => c.filter((_, at) => at !== index))}>
                          ✕
                        </button>
                      </td>
</tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setChannels((c) => [...c, { code: "", name: "", reachWeight: 1, brandAlpha: 1 }])}>
                {t("editor.addRow")}
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
                  <th>{t("editor.projectLang")}</th>
                  <th>{t("field.overfunding")}</th>
                  <th />
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
                      <select value={row.lang ?? "en"} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, lang: e.target.value as "en" | "zh" } : r))}>
                        <option value="en">{t("editor.catalogEnglish")}</option>
                        <option value="zh">{t("editor.catalogChinese")}</option>
                      </select>
                    </td>
                    <td>
                      <select value={row.overfunding} disabled={locked} onChange={(e) => setProjects((c) => c.map((r, i) => i === index ? { ...r, overfunding: e.target.value } : r))}>
                        {["REFUND", "LOST"].map((mode) => (
                          <option key={mode} value={mode}>{mode}</option>
                        ))}
                      </select>
                    </td>
                                        <td>
                        <button type="button" className="ghost" disabled={locked || projects.length <= 1}
                          title={t("editor.removeThisRow")}
                          aria-label={t("editor.removeThisRow")}
                          onClick={() => setProjects((c) => c.filter((_, at) => at !== index))}>
                          ✕
                        </button>
                      </td>
</tr>
                ))}
              </tbody>
            </table>
            <div className="buttons">
              <button type="button" disabled={locked} onClick={() => setProjects((c) => [...c, { code: "", name: "", targetProduct: null, advantageType: "UNIT_COST_DOWN", advantageValue: 0.1, hiddenCost: 0, hintBand: 0.2, durationMonths: 3, partialBenefit: true, benefitCurve: "LINEAR", benefitExponent: 1, overfunding: "LOST" }])}>
                {t("editor.addRow")}
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
