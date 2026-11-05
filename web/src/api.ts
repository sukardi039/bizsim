/** Thin typed wrapper over the REST API. The token lives in localStorage, not in a URL. */

// Empty by default: the console calls its own origin and nginx forwards /api to the backend.
// Set VITE_API_BASE_URL at build time only when the API lives on another origin.
const ORIGIN = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
const BASE = ORIGIN + "/api";
const STORAGE = "bizsim.session";

export interface ApiProblemBody {
  code: string;
  params?: Record<string, string | number>;
  message: string;
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: any) {
    super(body?.error ?? `HTTP ${status}`);
  }

  /** The translatable reason (D23): the interface owns the wording, not the server. */
  get code(): string | undefined {
    return typeof this.body?.code === "string" ? this.body.code : undefined;
  }

  get params(): Record<string, string> {
    const raw = this.body?.params;
    if (!raw || typeof raw !== "object") return {};
    const params: Record<string, string> = {};
    for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
      params[key] = String(value);
    }
    return params;
  }

  /** Every rule a submission broke, as codes with parameters. */
  get problems(): ApiProblemBody[] {
    const list = this.body?.problems;
    if (!Array.isArray(list)) return [];
    return list.filter(
      (entry): entry is ApiProblemBody =>
        !!entry && typeof entry === "object" && typeof (entry as ApiProblemBody).code === "string",
    );
  }

  /** The server's English messages — a fallback for codes this client does not know yet. */
  get details(): string[] {
    if (this.problems.length > 0) return this.problems.map((problem) => problem.message);
    if (Array.isArray(this.body?.details)) return this.body.details.filter((s: unknown) => typeof s === "string");
    return [];
  }
}

export function eventStreamUrl(runId: number, ticket: string): string {
  return `${BASE}/runs/${runId}/events?ticket=${encodeURIComponent(ticket)}`;
}

/**
 * Fetches the debrief file and hands it to the browser as a download. The token travels in the
 * header, so a plain anchor would not be authenticated; the blob URL is revoked immediately after.
 */
export async function downloadDebrief(
  runId: number,
  format: "pdf" | "xlsx",
  /** The language the reader is using: the document is written in it (D44). */
  locale?: string,
): Promise<void> {
  const token = getToken();
  const query = new URLSearchParams({ format });
  if (locale) query.set("locale", locale);
  const response = await fetch(`${BASE}/runs/${runId}/debrief/export?${query.toString()}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    throw new ApiError(response.status, await response.json().catch(() => null));
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  // The server names the file: the class debrief and a trainee's own report are different documents.
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const named = /filename="?([^";]+)"?/.exec(disposition);
  link.download = named ? named[1] : `bizsim-debrief-run${runId}.${format}`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function getToken(): string {
  return localStorage.getItem(STORAGE) ?? "";
}

export function setToken(token: string): void {
  localStorage.setItem(STORAGE, token);
}

export function clearToken(): void {
  localStorage.removeItem(STORAGE);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const response = await fetch(BASE + path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let body: any = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) throw new ApiError(response.status, body);
  return body as T;
}

export interface Team {
  id: number;
  name: string;
  companyName: string;
  cash: number;
  brandScore?: number;
  status?: string;
  machineWorkers?: number;
}

export interface RunPlayer {
  id: number;
  teamId: number;
  userId: number;
  roleKey: string;
  displayName: string;
}

export interface Run {
  /** Class language of the package this run came from; absent/blank = follow each person. */
  locale?: string | null;
  id: number;
  status: string;
  currentRound: number;
  /** True while a round is open to decide: the run page's single button computes, else opens (D38). */
  roundOpen: boolean;
  /** The open round's deadline and clock state (D12); all three are meaningless when nothing is open. */
  closesAt?: string | null;
  clockPaused?: boolean;
  /** Seconds left, already frozen while paused; -1 for an untimed round. */
  clockRemainingSeconds?: number;
  scenarioKey: string;
  rounds: number;
  cycles: number;
  teams: Team[];
  players: RunPlayer[];
}

/** One product's line in a computed round: what was available, what it sold, what it lost. */
export interface ProductStat {
  product: string;
  planned: number;
  produced: number;
  openingStock: number;
  demand: number;
  sold: number;
  closingStock: number;
  lost: number;
  price: number;
  unitCost: number;
  /** D15: the level the line carried through this round, after any purchase or R&D. */
  quality?: number;
  revenue: number;
  cogs: number;
}

export interface StatementRow {
  round: number;
  revenue: number;
  cogs: number;
  payroll: number;
  marketing: number;
  rnd_spend: number;
  fixed_cost: number;
  holding_cost: number;
  hiring_cost: number;
  capacity_cost: number;
  report_cost: number;
  /** D15: money spent raising product quality this month (0 on templates that do not offer it). */
  quality_cost: number;
  /** Cash held before the round was computed; cash_after - opening_cash is the movement. */
  opening_cash: number;
  profit: number;
  cash_after: number;
  brand_after: number;
  lost_sales: number;
  capacity_used: number;
  capacity_available: number;
  /** The per-product lines written by the engine at compute time (D34). */
  detail?: { products?: ProductStat[] } | null;
}

export interface ScoreRow {
  round: number;
  team_id: number;
  company_name: string;
  profit: number;
  cash: number;
  market_share: number;
  stock: number;
  brand?: number;
  /** The rest of the D13 KPI set; the endpoint returns them, the scoreboard table shows a subset. */
  revenue?: number;
  cumulative_profit?: number;
  lost_sales?: number;
  capacity_utilisation?: number;
  rnd_completed?: number;
  status?: string;
}

export interface ContextProduct {
  code: string;
  name: string;
  price: number;
  unitCost: number;
  quality: number;
  stock: number;
  unitsPerWorker: number;
}

export interface ContextChannel {
  code: string;
  name: string;
  reachWeight: number;
  brandAlpha: number;
}

export interface ContextProject {
  code: string;
  name: string;
  targetProduct: string;
  advantageType: string;
  advantageValue: number;
  durationMonths: number;
  partialBenefit: boolean;
  benefitCurve: string;
  benefitExponent: number;
  overfunding: string;
  hintLow: number;
  hintHigh: number;
  invested: number;
  monthsInvested: number;
  status: string;
}

/** One company's monthly submission (D1: one decision per company per month). */
export interface DecisionInput {
  production: { product: string; units: number }[];
  price: { product: string; unitPrice: number }[];
  marketing: { channel: string; spend: number }[];
  manpower: { production: number };
  rnd: { project: string; invest: number }[];
  /** D15: money per product for a quality upgrade; empty when the template does not offer it. */
  quality: { product: string; invest: number }[];
  capacityPurchase: number;
  marketReport: boolean;
}

export interface MarketForecast {
  round: number;
  demandIndex: number;
  costIndex: number;
  wageIndex: number;
  trendStrength: number;
  trendLabel: string;
  band: number;
  outlook: string;
}

export interface TemplateRound {
  round: number;
  demandIndex: number;
  costIndex: number;
  wageIndex: number;
  trendStrength: number;
  trendLabel: string;
  note: string;
}

export interface TemplateDetail {
  key: string;
  name: string;
  tenantId?: number | null;
  status: string;
  basedOn?: string | null;
  editable: boolean;
  settings: Record<string, unknown>;
  environment: TemplateRound[];
  products: TemplateProduct[];
  channels: TemplateChannel[];
  projects: TemplateProject[];
  events: TemplateEvent[];
}

export interface TemplateProduct {
  code: string;
  name: string;
  basePrice: number;
  baseUnitCost: number;
  baseQuality: number;
  baseDemand: number;
  unitsPerWorker: number;
  incomeElasticity: number;
  trendAffinity: number;
}

export interface TemplateChannel {
  code: string;
  name: string;
  reachWeight: number;
  brandAlpha: number;
}

export interface TemplateProject {
  code: string;
  name: string;
  targetProduct: string | null;
  advantageType: string;
  advantageValue: number;
  hiddenCost: number;
  hintBand: number;
  durationMonths: number;
  partialBenefit: boolean;
  benefitCurve: string;
  benefitExponent: number;
  overfunding: string;
}

export interface TemplateEvent {
  code: string;
  name: string;
  description: string;
  earliestRound: number;
  latestRound: number;
  probability: number;
  durationMonths: number;
  demandMultiplier: number;
  costMultiplier: number;
  wageMultiplier: number;
  targetProduct?: string | null;
}

export interface CompanyProfile {
  id: number;
  name: string;
  companyName: string;
  products: string[];
  notes?: string | null;
  tenantId?: number | null;
  basedOn?: number | null;
}

export interface SimulationPackage {
  id: number;
  name: string;
  /** The frozen copy sessions run on. */
  templateKey: string;
  /** The draft the builder published from, for display. */
  sourceTemplateKey: string;
  templateVersion: number;
  companyProfileIds: number[];
  /** The prepared class list, in company order. */
  seats: { position: number; userId: number; displayName: string; email: string }[];
  /** Class language forced on trainees; null = each person keeps their own. */
  locale?: string | null;
  status: string;
  publishedAt: string;
}

export interface UserRow {
  id: number;
  email: string;
  displayName: string;
  role: string;
  active: boolean;
}

export interface TemplateRow {
  key: string;
  name: string;
  /** Absent for the shipped global template: the API omits nulls. */
  tenantId?: number | null;
  status: string;
  basedOn?: string | null;
  version: number;
  products: number;
  rounds: number;
}

export interface DecisionFormContext {
  runId: number;
  /** The round the run has reached; 0 before the facilitator opens round 1. */
  round: number;
  /** False when nothing is open to decide — before round 1, or once the session has finished. */
  roundOpen: boolean;
  closesAt?: string | null;
  cash: number;
  brand: number;
  status: string;
  machineWorkers: number;
  products: ContextProduct[];
  staff: Record<string, number>;
  salaries: Record<string, number>;
  fixedCostPerMonth: number;
  holdingCostPerUnit: number;
  channels: ContextChannel[];
  machineCostPerWorker: number;
  rndProjects: ContextProject[];
  market: {
    round: number;
    demandIndex: number;
    costIndex: number;
    wageIndex: number;
    trendStrength: number;
    trendLabel: string;
    outlook: string;
    events: unknown[];
  };
  marketReportCost: number;
  /** D15: whether this template sells quality upgrades, and at what price per point. */
  qualityUpgrade: boolean;
  qualityCostPerPoint: number;
  /** True when this company already has a decision stored for the open round. */
  submitted: boolean;
  /** The stored decision, so the form can reopen on it instead of an empty month. */
  decision?: DecisionInput | null;
}

export interface ClockState {
  round: number;
  /** Null for an untimed round. */
  closesAt: string | null;
  paused: boolean;
  /** Frozen while paused; -1 when the round has no clock. */
  remainingSeconds: number;
}

/** One company's month as the facilitator sees it while the round is open (D12). */
export interface SubmissionRow {
  teamId: number;
  companyName: string;
  submitted: boolean;
  submittedAt: string | null;
  submittedBy: string | null;
  decision: DecisionInput | null;
}

/** One hand laid on the session, with the reason and the person (D12/Q-G). */
export interface AuditRow {
  id: number;
  round: number | null;
  teamId: number | null;
  companyName: string | null;
  action: string;
  amount: number | null;
  reason: string | null;
  actor: string;
  at: string;
}

/** A reusable brain: level (how well it plays) × personality (what it wants). */
export interface BotRow {
  id: number;
  key: string;
  name: string;
  level: "LOW" | "MID" | "HIGH";
  personality: "CAUTIOUS" | "BALANCED" | "AGGRESSIVE";
  version: number;
  active: boolean;
  /** The person that carries this brain; null when it has not been created yet. */
  userId: number | null;
}

export interface BotTraceRow {
  round: number;
  teamId: number;
  company: string;
  profileKey: string;
  profileVersion: number;
  level: string;
  personality: string;
  mode: "SCHEDULED" | "MANUAL" | "NONE";
  decidedAt: string | null;
  rationale: string | null;
}

export interface BotReplay {
  round: number;
  teamId: number;
  level: string;
  personality: string;
  contextDigest: string;
  rationale: string;
  storedPayload: string | null;
  replayedPayload: string;
  identical: boolean;
}

export const api = {
  login: (tenantCode: string, email: string, password: string) =>
    request<{ token: string }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ tenantCode, email, password }),
    }),
  me: () =>
    request<{
      userId: number;
      tenantId: number;
      tenantCode: string;
      role: string;
      /** "en" or "zh"; resolved server-side (person, else tenant, else English). */
      locale: string;
    }>("/me"),
  setMyLocale: (locale: string) =>
    request<{ locale: string }>("/me/locale", {
      method: "PUT",
      body: JSON.stringify({ locale }),
    }),
  runs: () => request<Run[]>("/runs"),
  run: (id: number) => request<Run>(`/runs/${id}`),
  createRun: (name: string, scenarioKey: string, teamNames: string[], cycles: number) =>
    request<Run>("/runs", {
      method: "POST",
      body: JSON.stringify({
        name,
        scenarioKey,
        cycles,
        teams: teamNames.map((teamName) => ({ name: teamName, companyName: teamName })),
      }),
    }),
  nextRound: (runId: number) => request<{ number: number }>(`/runs/${runId}/rounds/next`, { method: "POST" }),
  closeRound: (runId: number) => request<unknown>(`/runs/${runId}/rounds/close`, { method: "POST" }),
  /** D12: stop and restart the round clock, and give the class more time. */
  pauseClock: (runId: number) =>
    request<ClockState>(`/runs/${runId}/rounds/clock/pause`, { method: "POST" }),
  resumeClock: (runId: number) =>
    request<ClockState>(`/runs/${runId}/rounds/clock/resume`, { method: "POST" }),
  extendClock: (runId: number, minutes: number) =>
    request<ClockState>(`/runs/${runId}/rounds/clock/extend`, {
      method: "POST",
      body: JSON.stringify({ minutes }),
    }),
  /** D12: every company's submitted month, so the facilitator can see who is still deciding. */
  submissions: (runId: number, round?: number) =>
    request<{ round: number; companies: SubmissionRow[] }>(
      `/runs/${runId}/decisions${round ? `?round=${round}` : ""}`,
    ),
  /** D12/Q-G: cash moved by hand, always with a reason on the record. */
  adjustCash: (runId: number, teamId: number, delta: number, reason: string) =>
    request<{ teamId: number; cash: number; delta: number }>(`/runs/${runId}/teams/${teamId}/cash`, {
      method: "POST",
      body: JSON.stringify({ delta, reason }),
    }),
  /** Undo the last computed round: restored state, deleted results, the month open again. */
  voidLastRound: (runId: number, reason: string) =>
    request<{ round: number; status: string }>(`/runs/${runId}/rounds/void`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  audit: (runId: number) => request<AuditRow[]>(`/runs/${runId}/audit`),
  /** Deletes the simulation and everything played in it. Owner or admin only; not undoable. */
  deleteRun: (runId: number) => request<unknown>(`/runs/${runId}`, { method: "DELETE" }),
  statements: (runId: number, teamId: number) =>
    request<StatementRow[]>(`/runs/${runId}/teams/${teamId}/statements`),
  scoreboard: (runId: number, round?: number) =>
    request<ScoreRow[]>(`/runs/${runId}/scoreboard${round ? `?round=${round}` : ""}`),
  context: (runId: number, teamId: number) =>
    request<DecisionFormContext>(`/runs/${runId}/teams/${teamId}/context`),
  /** Facilitator/admin only: makes the account a trainee signs in with. */
  createUser: (email: string, displayName: string, password: string, role = "PLAYER") =>
    request<{ id: number; email: string; displayName: string; role: string }>("/users", {
      method: "POST",
      body: JSON.stringify({ email, displayName, password, role }),
    }),
  // ---------------------------------------------------------------- auto workers (D47–D50)
  bots: () => request<BotRow[]>("/bots"),
  createBot: (body: { name: string; level: string; personality: string }) =>
    request<BotRow>("/bots", { method: "POST", body: JSON.stringify(body) }),
  updateBot: (id: number, body: { name: string; level: string; personality: string }) =>
    request<BotRow>(`/bots/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  retireBot: (id: number) => request<unknown>(`/bots/${id}/retire`, { method: "POST" }),
  /** What the opponents did: profile version, how they were called, and the one-line reason. */
  botTrace: (runId: number) => request<BotTraceRow[]>(`/runs/${runId}/bots`),
  askBots: (runId: number) =>
    request<{ decided: number }>(`/runs/${runId}/bots/decide`, { method: "POST" }),
  replayBot: (runId: number, teamId: number, round: number) =>
    request<BotReplay>(`/runs/${runId}/bots/${teamId}/replay?round=${round}`, { method: "POST" }),
  templates: () => request<TemplateRow[]>("/templates"),
  copyTemplate: (key: string, name?: string) =>
    request<TemplateRow>(`/templates/${encodeURIComponent(key)}/copy`, {
      method: "POST",
      body: JSON.stringify({ name: name ?? "" }),
    }),
  templateDetail: (key: string) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}`),
  saveTemplateSettings: (key: string, settings: Record<string, unknown>) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/settings`, {
      method: "PUT",
      body: JSON.stringify(settings),
    }),
  saveTemplateEnvironment: (key: string, rounds: TemplateRound[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/environment`, {
      method: "PUT",
      body: JSON.stringify({ rounds }),
    }),
  saveTemplateProducts: (key: string, products: TemplateProduct[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/products`, {
      method: "PUT",
      body: JSON.stringify({ products }),
    }),
  saveTemplateChannels: (key: string, channels: TemplateChannel[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/channels`, {
      method: "PUT",
      body: JSON.stringify({ channels }),
    }),
  saveTemplateEvents: (key: string, events: TemplateEvent[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/events`, {
      method: "PUT",
      body: JSON.stringify({ events }),
    }),
  saveTemplateProjects: (key: string, projects: TemplateProject[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/projects`, {
      method: "PUT",
      body: JSON.stringify({ projects }),
    }),
  /**
   * The roster. `includeAssigned` is the People page switch: off (the default) leaves out trainees
   * who already hold a seat in a package or a session, so the list shows who is still free to put
   * in a company.
   */
  users: (includeAssigned = false) =>
    request<UserRow[]>(`/users${includeAssigned ? "?includeAssigned=true" : ""}`),
  /**
   * Active trainees only — the people a company can seat. Separate from `users()` on purpose: the
   * package form builds a class list, it has no business reading the staff roster.
   */
  trainees: () => request<UserRow[]>("/users/trainees"),
  companies: () => request<CompanyProfile[]>("/companies"),
  packages: () => request<SimulationPackage[]>("/packages"),
  tidyPreview: () =>
    request<{ id: number; name: string; frozenKey: string; publishedAt: string }[]>("/packages/tidy-up"),
  tidyUp: () =>
    request<{ removed: number; names: string[] }>("/packages/tidy-up", { method: "POST" }),
  publishPackage: (body: {
    name: string;
    templateKey: string;
    companyProfileIds: number[];
    traineeIds: (number | null)[];
    locale?: string | null;
  }) =>
    request<SimulationPackage>("/packages", { method: "POST", body: JSON.stringify(body) }),
  retirePackage: (id: number) =>
    request<unknown>(`/packages/${id}/retire`, { method: "POST" }),
  /** Execution: a facilitator starts a session from a published package. */
  startSession: (packageId: number, body: { name: string; traineeIds: number[] }) =>
    request<Run>(`/packages/${packageId}/sessions`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  copyCompany: (id: number) =>
    request<CompanyProfile>(`/companies/${id}/copy`, { method: "POST" }),
  saveCompany: (id: number, body: { name: string; companyName: string; products: string[]; notes?: string }) =>
    request<CompanyProfile>(`/companies/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  createCompany: (body: { name: string; companyName: string; products: string[]; notes?: string }) =>
    request<CompanyProfile>("/companies", { method: "POST", body: JSON.stringify(body) }),
  /** Starts a session from the prepared set: template + company profiles + trainees. */
  startPrepared: (body: {
    name: string;
    templateKey: string;
    companyProfileIds: number[];
    traineeIds: number[];
  }) =>
    request<Run>("/runs/prepared", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  resetUserPassword: (id: number, password: string) =>
    request<unknown>(`/users/${id}/password`, {
      method: "PUT",
      body: JSON.stringify({ password }),
    }),
  changeMyPassword: (currentPassword: string, newPassword: string) =>
    request<unknown>("/me/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }),
  setUserRole: (id: number, role: string) =>
    request<{ id: number; email: string; displayName: string; role: string; active: boolean }>(
      `/users/${id}/role`,
      { method: "PATCH", body: JSON.stringify({ role }) },
    ),
  deleteUser: (id: number) => request<unknown>(`/users/${id}`, { method: "DELETE" }),
  setUserActive: (id: number, active: boolean) =>
    request<unknown>(`/users/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" }),
  submitDecision: (runId: number, teamId: number, decision: DecisionInput) =>
    request<{ submitted: boolean }>(`/runs/${runId}/teams/${teamId}/decisions`, {
      method: "POST",
      body: JSON.stringify(decision),
    }),
  marketReport: (runId: number, teamId: number) =>
    request<{ purchasedForRound: number; cost: number; current: any; forecast: MarketForecast[] }>(
      `/runs/${runId}/teams/${teamId}/market-report`,
    ),
  streamTicket: (runId: number) =>
    request<{ ticket: string; expiresInSeconds: number }>(`/runs/${runId}/stream-ticket`, {
      method: "POST",
    }),
};
