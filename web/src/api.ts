/** Thin typed wrapper over the REST API. The token lives in localStorage, not in a URL. */

// Empty by default: the console calls its own origin and nginx forwards /api to the backend.
// Set VITE_API_BASE_URL at build time only when the API lives on another origin.
const ORIGIN = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
const BASE = ORIGIN + "/api";
const STORAGE = "bizsim.session";

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: any) {
    super(body?.error ?? `HTTP ${status}`);
  }

  /** The server reports validation problems as a list; the form shows them verbatim. */
  get details(): string[] {
    if (Array.isArray(this.body?.details)) return this.body.details;
    if (Array.isArray(this.body?.problems)) return this.body.problems;
    return [];
  }
}

export function eventStreamUrl(runId: number, ticket: string): string {
  return `${BASE}/runs/${runId}/events?ticket=${encodeURIComponent(ticket)}`;
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
  id: number;
  status: string;
  currentRound: number;
  scenarioKey: string;
  rounds: number;
  cycles: number;
  teams: Team[];
  players: RunPlayer[];
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

export interface DecisionFormContext {
  runId: number;
  round: number;
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
  /** True when this company already has a decision stored for the open round. */
  submitted: boolean;
  /** The stored decision, so the form can reopen on it instead of an empty month. */
  decision?: DecisionInput | null;
}

export const api = {
  login: (tenantCode: string, email: string, password: string) =>
    request<{ token: string }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ tenantCode, email, password }),
    }),
  me: () => request<{ userId: number; tenantId: number; tenantCode: string; role: string }>("/me"),
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
  addPlayer: (runId: number, teamId: number, userId: number, roleKey = "OWNER") =>
    request<RunPlayer>(`/runs/${runId}/players`, {
      method: "POST",
      body: JSON.stringify({ teamId, userId, roleKey }),
    }),
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
