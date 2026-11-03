import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, getToken } from "./api";

/**
 * Language of the chrome, not of the content.
 *
 * Ruling: template content — product names, project names, event text, trend labels — is authored
 * and is never translated. Only interface labels and server *codes* live here, so adding a language
 * is this file plus a catalog, not a migration.
 */
export type Locale = "en" | "zh";

export const LOCALES: { code: Locale; label: string }[] = [
  { code: "en", label: "English" },
  { code: "zh", label: "中文" },
];

const STORAGE = "bizsim.locale";

type Dict = Record<string, string>;

const en: Dict = {
  "nav.sessions": "Sessions",
  "nav.templates": "Templates",
  "nav.people": "People",
  "app.signOut": "sign out",

  "login.title": "bizsim",
  "login.subtitle": "Facilitator console",
  "login.tenant": "Tenant",
  "login.email": "Email",
  "login.password": "Password",
  "login.signIn": "Sign in",
  "login.signingIn": "Signing in…",
  "login.again": "Sign in again",

  "run.openNext": "Open next round",
  "run.compute": "Compute round",
  "run.delete": "Delete run",
  "run.deleteConfirm":
    "Delete run {id} and everything played in it?\n\nRounds, decisions, results and the seats go with it. Accounts stay. This cannot be undone.",
  "run.select": "Select a run, or create one.",

  "score.title": "Scoreboard",
  "score.round": "round {round}",
  "score.rank": "#",
  "score.company": "Company",
  "score.profit": "Profit",
  "score.cash": "Cash",
  "score.share": "Share",
  "score.stock": "Stock",
  "score.empty": "No round computed yet.",

  "trainees.title": "Trainees",
  "trainees.hint":
    "A trainee is a person, not a seat: prepare accounts in People, then seat them here. Each company needs one seat.",
  "trainees.none": "no trainee yet",
  "trainees.seatExisting": "Seat an existing trainee",
  "trainees.pick": "— pick an account —",
  "trainees.seat": "Seat",
  "trainees.seating": "Seating…",
  "trainees.new": "…or create a new one — name",
  "trainees.namePlaceholder": "Walk-in trainee",
  "trainees.email": "Email",
  "trainees.password": "Initial password (8+)",
  "trainees.passwordPlaceholder": "hand this over",
  "trainees.createSeat": "Create and seat",
  "trainees.seated": "Seated. They can sign in and see this company.",

  "people.title": "People",
  "people.hint":
    "Trainee accounts belong to the person and outlive a session. A trainee who has already played is deactivated, not deleted — their results stay readable.",
  "people.name": "Name",
  "people.email": "Email",
  "people.role": "Role",
  "people.status": "Status",
  "people.active": "active",
  "people.deactivated": "deactivated",
  "people.deactivate": "Deactivate",
  "people.activate": "Activate",
  "people.delete": "Delete",
  "people.add": "Add a trainee",
  "people.create": "Create account",
  "people.creating": "Creating…",

  "templates.title": "Templates",
  "templates.hint":
    "A template is the economy a session is played on. The shipped standard template is global and read-only — copy it to get a draft of your own.",
  "templates.owner": "Owner",
  "templates.status": "Status",
  "templates.products": "Products",
  "templates.rounds": "Rounds",
  "templates.basedOn": "Based on",
  "templates.global": "global",
  "templates.yours": "yours",
  "templates.duplicate": "Duplicate",
  "templates.copying": "Copying…",

  "player.round": "Round {round} of {total}",
  "player.cash": "Cash",
  "player.brand": "Brand",
  "player.machines": "Machines",
  "player.production": "Production and price",
  "player.product": "Product",
  "player.stock": "Stock",
  "player.unitCost": "Unit cost",
  "player.units": "Units to produce",
  "player.workers": "Workers",
  "player.price": "Price",
  "player.workforce": "Workforce: {used} of {available} workers used",
  "player.marketing": "Marketing",
  "player.capacity": "Workforce and capacity",
  "player.staff": "Production staff",
  "player.rnd": "Research and development",
  "player.rndThisMonth": "This month",
  "player.review": "Market review",
  "player.showReview": "Show the purchased review",
  "player.submit": "Submit round {round}",
  "player.replace": "Replace decision",
  "player.submitting": "Submitting…",

  // ---- server codes (D23): the interface owns the words, the server owns the code ----
  "error.decision.required": "The decision is empty.",
  "error.decision.cash_shortfall": "Not enough cash for this plan.",
  "error.production.required": "Every product needs a volume, even 0.",
  "error.production.unknown_product": "Unknown product {product}.",
  "error.production.duplicate_product": "Product {product} appears more than once.",
  "error.production.units_negative": "{product}: units must be 0 or more.",
  "error.production.missing_product": "{product} has no production volume.",
  "error.production.workforce_short":
    "The plan needs {needed} workers; this company has {available}. Hire staff or buy capacity.",
  "error.price.required": "Every product needs a selling price.",
  "error.price.unknown_product": "Unknown product {product}.",
  "error.price.duplicate_product": "Product {product} appears more than once.",
  "error.price.not_positive": "{product}: the price must be above zero.",
  "error.price.missing_product": "{product} has no price.",
  "error.marketing.unknown_channel": "Unknown channel {channel}.",
  "error.marketing.duplicate_channel": "Channel {channel} appears more than once.",
  "error.marketing.spend_negative": "{channel}: spend must be 0 or more.",
  "error.manpower.out_of_range": "Production staff must be between 0 and {max}.",
  "error.capacity.negative": "Capacity purchase must be 0 or more.",
  "error.rnd.unknown_project": "Unknown project {project}.",
  "error.rnd.duplicate_project": "Project {project} appears more than once.",
  "error.rnd.invest_negative": "{project}: investment must be 0 or more.",
  "error.rnd.not_active": "{project} is already {status}.",
  "error.run.not_found": "That simulation does not exist.",
  "error.run.not_open": "The simulation is {status}, so it cannot do that.",
  "error.run.not_yours": "That simulation belongs to another facilitator.",
  "error.run.already_in_progress":
    "You already have a simulation in progress. Finish it, or sign in as another facilitator.",
  "error.run.teams_required": "At least one company is required.",
  "error.run.too_many_teams": "At most {max} companies per simulation.",
  "error.run.rounds_out_of_range": "Rounds must be 1-48 and cycles at least 1.",
  "error.round.not_open": "No round is open.",
  "error.round.closed": "Round {round} is closed.",
  "error.team.not_found": "That company is not in this simulation.",
  "error.team.not_yours": "You do not run this company.",
  "error.user.not_found": "No such trainee in this tenant.",
  "error.user.self_delete": "You cannot delete your own account.",
  "error.user.has_played": "This trainee has already played. Deactivate the account instead.",
  "error.user.duplicate_email": "An account with that email already exists.",
  "error.user.password_too_short": "The password must be at least {min} characters.",
  "error.user.role_invalid": "The role must be PLAYER or FACILITATOR.",
  "error.user.facilitator_needs_admin": "Only an admin can create a facilitator.",
  "error.template.unknown": "Unknown template {template}.",
  "error.template.not_found": "That template does not exist.",
  "error.template.rounds_exceeded": "This template has only {rounds} rounds.",
  "error.template.invalid_rounds": "Template {template} has no valid round count.",
  "error.auth.facilitator_only": "This action is for facilitators.",
  "error.auth.player_only": "This action is for trainees.",
  "error.locale.unsupported": "That language is not available.",
  "error.stream.ticket_invalid": "The live connection could not be opened. Reload the page.",
  "error.report.not_purchased": "The market review was not purchased for this round.",
  "error.db.conflict": "That value is already taken.",
  "error.request.invalid": "Please check the form.",
  "error.request.field": "{field} is not valid.",
  "error.request.field_required": "{field} is required.",

  "shortfall.title": "Not enough cash: the plan commits {required} against {available} available.",
  "line.materials": "materials",
  "line.marketing": "marketing",
  "line.rnd": "R&D",
  "line.capacity": "capacity",
  "line.hiring": "hiring",
};

const zh: Dict = {
  "nav.sessions": "模拟",
  "nav.templates": "模板",
  "nav.people": "人员",
  "app.signOut": "退出登录",

  "login.title": "bizsim",
  "login.subtitle": "教师控制台",
  "login.tenant": "机构代码",
  "login.email": "邮箱",
  "login.password": "密码",
  "login.signIn": "登录",
  "login.signingIn": "正在登录…",
  "login.again": "重新登录",

  "run.openNext": "开启下一轮",
  "run.compute": "结算本轮",
  "run.delete": "删除模拟",
  "run.deleteConfirm":
    "删除模拟 {id} 及其全部数据进行记录？\n\n轮次、决策、结果与席位将一并删除，账号保留。此操作无法撤销。",
  "run.select": "选择一个模拟，或新建一个。",

  "score.title": "排行榜",
  "score.round": "第 {round} 轮",
  "score.rank": "排名",
  "score.company": "公司",
  "score.profit": "利润",
  "score.cash": "现金",
  "score.share": "市场份额",
  "score.stock": "库存",
  "score.empty": "本轮尚未结算。",

  "trainees.title": "学员",
  "trainees.hint":
    "学员是个人，而非席位：先在“人员”中建立账号，再在这里分配席位。每家公司需要一个席位。",
  "trainees.none": "尚无学员",
  "trainees.seatExisting": "分配已有学员",
  "trainees.pick": "— 选择账号 —",
  "trainees.seat": "分配",
  "trainees.seating": "正在分配…",
  "trainees.new": "…或新建账号 — 姓名",
  "trainees.namePlaceholder": "临时学员",
  "trainees.email": "邮箱",
  "trainees.password": "初始密码（8 位以上）",
  "trainees.passwordPlaceholder": "交给学员",
  "trainees.createSeat": "创建并分配",
  "trainees.seated": "已分配。该学员可以登录并查看此公司。",

  "people.title": "人员",
  "people.hint":
    "学员账号属于个人，可跨模拟长期使用。已经参与过的学员只能停用，不能删除——其成绩需要保留以供复盘。",
  "people.name": "姓名",
  "people.email": "邮箱",
  "people.role": "角色",
  "people.status": "状态",
  "people.active": "启用",
  "people.deactivated": "已停用",
  "people.deactivate": "停用",
  "people.activate": "启用",
  "people.delete": "删除",
  "people.add": "添加学员",
  "people.create": "创建账号",
  "people.creating": "正在创建…",

  "templates.title": "模板",
  "templates.hint":
    "模板决定模拟所运行的经济环境。内置的标准模板为全局只读——复制一份即可得到属于你的草稿。",
  "templates.owner": "归属",
  "templates.status": "状态",
  "templates.products": "产品数",
  "templates.rounds": "轮次",
  "templates.basedOn": "复制自",
  "templates.global": "全局",
  "templates.yours": "本机构",
  "templates.duplicate": "复制",
  "templates.copying": "正在复制…",

  "player.round": "第 {round} 轮，共 {total} 轮",
  "player.cash": "现金",
  "player.brand": "品牌",
  "player.machines": "设备产能",
  "player.production": "生产与定价",
  "player.product": "产品",
  "player.stock": "库存",
  "player.unitCost": "单位成本",
  "player.units": "计划产量",
  "player.workers": "用工",
  "player.price": "售价",
  "player.workforce": "用工：已使用 {used} / 可用 {available}",
  "player.marketing": "市场营销",
  "player.capacity": "人员与产能",
  "player.staff": "生产人员",
  "player.rnd": "研发",
  "player.rndThisMonth": "本月投入",
  "player.review": "市场报告",
  "player.showReview": "查看已购买的报告",
  "player.submit": "提交第 {round} 轮决策",
  "player.replace": "更新决策",
  "player.submitting": "正在提交…",

  "error.decision.required": "决策内容为空。",
  "error.decision.cash_shortfall": "本方案的现金不足。",
  "error.production.required": "每个产品都需要产量，即使为 0。",
  "error.production.unknown_product": "未知产品 {product}。",
  "error.production.duplicate_product": "产品 {product} 重复出现。",
  "error.production.units_negative": "{product}：产量不能为负。",
  "error.production.missing_product": "{product} 未填写产量。",
  "error.production.workforce_short": "方案需要 {needed} 名工人，本公司仅有 {available} 名。请增加用工或购买产能。",
  "error.price.required": "每个产品都需要定价。",
  "error.price.unknown_product": "未知产品 {product}。",
  "error.price.duplicate_product": "产品 {product} 重复出现。",
  "error.price.not_positive": "{product}：售价必须大于零。",
  "error.price.missing_product": "{product} 未填写售价。",
  "error.marketing.unknown_channel": "未知渠道 {channel}。",
  "error.marketing.duplicate_channel": "渠道 {channel} 重复出现。",
  "error.marketing.spend_negative": "{channel}：投入不能为负。",
  "error.manpower.out_of_range": "生产人员数量应在 0 到 {max} 之间。",
  "error.capacity.negative": "产能购买金额不能为负。",
  "error.rnd.unknown_project": "未知项目 {project}。",
  "error.rnd.duplicate_project": "项目 {project} 重复出现。",
  "error.rnd.invest_negative": "{project}：投入不能为负。",
  "error.rnd.not_active": "{project} 当前状态为 {status}。",
  "error.run.not_found": "该模拟不存在。",
  "error.run.not_open": "模拟当前状态为 {status}，无法执行该操作。",
  "error.run.not_yours": "该模拟属于其他教师。",
  "error.run.already_in_progress": "你已有一个进行中的模拟。请先结束它，或以另一位教师身份登录。",
  "error.run.teams_required": "至少需要一家公司。",
  "error.run.too_many_teams": "每个模拟最多 {max} 家公司。",
  "error.run.rounds_out_of_range": "轮次应为 1-48，周期至少为 1。",
  "error.round.not_open": "当前没有开启的轮次。",
  "error.round.closed": "第 {round} 轮已结束。",
  "error.team.not_found": "该模拟中没有这家公司。",
  "error.team.not_yours": "你不负责这家公司。",
  "error.user.not_found": "本机构中没有该学员。",
  "error.user.self_delete": "不能删除自己的账号。",
  "error.user.has_played": "该学员已参与过模拟，请改为停用账号。",
  "error.user.duplicate_email": "该邮箱已被使用。",
  "error.user.password_too_short": "密码至少需要 {min} 位。",
  "error.user.role_invalid": "角色只能是 PLAYER 或 FACILITATOR。",
  "error.user.facilitator_needs_admin": "只有管理员可以创建教师账号。",
  "error.template.unknown": "未知模板 {template}。",
  "error.template.not_found": "该模板不存在。",
  "error.template.rounds_exceeded": "该模板仅有 {rounds} 轮。",
  "error.template.invalid_rounds": "模板 {template} 的轮次设置无效。",
  "error.auth.facilitator_only": "该操作仅限教师。",
  "error.auth.player_only": "该操作仅限学员。",
  "error.locale.unsupported": "暂不支持该语言。",
  "error.stream.ticket_invalid": "实时连接无法建立，请刷新页面。",
  "error.report.not_purchased": "本轮未购买市场报告。",
  "error.db.conflict": "该值已被占用。",
  "error.request.invalid": "请检查表单内容。",
  "error.request.field": "{field} 不合法。",
  "error.request.field_required": "请填写 {field}。",

  "shortfall.title": "现金不足：方案需占用 {required}，可用现金 {available}。",
  "line.materials": "原材料",
  "line.marketing": "市场营销",
  "line.rnd": "研发",
  "line.capacity": "产能",
  "line.hiring": "招聘",
};

const catalogs: Record<Locale, Dict> = { en, zh };

function isLocale(value: string | null | undefined): value is Locale {
  return value === "en" || value === "zh";
}

function initial(): Locale {
  const stored = localStorage.getItem(STORAGE);
  if (isLocale(stored)) return stored;
  // Browser preference, so a Chinese browser starts in Chinese without a setting.
  return navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en";
}

interface I18n {
  locale: Locale;
  /** Translates a key, filling {placeholders}. Falls back to English, then to the key itself. */
  t: (key: string, params?: Record<string, string | number>) => string;
  money: (value: number) => string;
  number: (value: number, decimals?: number) => string;
  /** Stores the choice locally and, when signed in, on the person's account. */
  setLocale: (locale: Locale) => void;
  /** Adopts the language the server resolved (user choice, then tenant default). */
  adopt: (locale: string | undefined) => void;
}

const Context = createContext<I18n | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(initial);

  useEffect(() => {
    localStorage.setItem(STORAGE, locale);
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    // Best effort: the choice is already stored locally, so a failed call only affects other devices.
    if (getToken()) void api.setMyLocale(next).catch(() => undefined);
  }, []);

  const adopt = useCallback((fromServer: string | undefined) => {
    if (isLocale(fromServer) && localStorage.getItem(STORAGE) !== fromServer) {
      setLocaleState(fromServer);
    }
  }, []);

  const value = useMemo<I18n>(() => {
    const dict = catalogs[locale];
    const localeTag = locale === "zh" ? "zh-CN" : "en-US";
    const t = (key: string, params?: Record<string, string | number>) => {
      let text = dict[key] ?? en[key] ?? key;
      if (params) {
        for (const [name, replacement] of Object.entries(params)) {
          text = text.split(`{${name}}`).join(String(replacement));
        }
      }
      return text;
    };
    return {
      locale,
      t,
      money: (v: number) => new Intl.NumberFormat(localeTag, { maximumFractionDigits: 0 }).format(v),
      number: (v: number, decimals = 2) =>
        new Intl.NumberFormat(localeTag, { maximumFractionDigits: decimals }).format(v),
      setLocale,
      adopt,
    };
  }, [locale, setLocale, adopt]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useI18n(): I18n {
  const value = useContext(Context);
  if (!value) throw new Error("useI18n outside I18nProvider");
  return value;
}
