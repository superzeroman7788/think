import type { RoutineDraft } from "./types.ts";

const DEFAULT_TIME = "09:00";
const DEFAULT_REPEAT = [1, 2, 3, 4, 5];
const ALL_DAYS = [0, 1, 2, 3, 4, 5, 6];
const WEEKDAYS = [1, 2, 3, 4, 5];
const WEEKEND = [0, 6];

const WEEKDAY_CHAR: Record<string, number> = {
  "日": 0,
  "天": 0,
  "七": 0,
  "一": 1,
  "二": 2,
  "三": 3,
  "四": 4,
  "五": 5,
  "六": 6,
};

const CN_DIGIT: Record<string, number> = {
  "零": 0,
  "一": 1,
  "二": 2,
  "两": 2,
  "三": 3,
  "四": 4,
  "五": 5,
  "六": 6,
  "七": 7,
  "八": 8,
  "九": 9,
};

function parseChineseHour(token: string): number | null {
  const t = token.trim();
  if (!t) return null;
  if (/^\d{1,2}$/.test(t)) {
    const n = Number.parseInt(t, 10);
    return n >= 0 && n <= 24 ? n : null;
  }
  if (t === "十二") return 12;
  if (t === "十一") return 11;
  if (t === "十") return 10;
  if (t.startsWith("二十")) {
    const rest = t.slice(2);
    if (!rest) return 20;
    const tail = CN_DIGIT[rest];
    return tail === undefined ? null : 20 + tail;
  }
  if (t.length === 2 && t[0] === "十") {
    const d = CN_DIGIT[t[1]];
    return d === undefined ? null : 10 + d;
  }
  const d = CN_DIGIT[t];
  return d === undefined ? null : d;
}

function formatHhmm(hour: number, minute: number): string {
  const h = Math.max(0, Math.min(23, hour));
  const m = Math.max(0, Math.min(59, minute));
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function applyDayPeriod(period: string | undefined, hour: number): number {
  const p = (period ?? "").trim();
  if (/中午|午间|正午/.test(p)) return 12;
  if (/下午/.test(p)) return hour >= 1 && hour <= 11 ? hour + 12 : hour;
  if (/晚上|傍晚|夜晚|夜间/.test(p)) {
    if (hour >= 1 && hour <= 11) return hour + 12;
    return hour;
  }
  if (/凌晨/.test(p)) return hour;
  return hour;
}

function parseMinuteSuffix(suffix: string | undefined): number {
  if (!suffix) return 0;
  const s = suffix.trim();
  if (s === "半") return 30;
  if (s === "一刻") return 15;
  if (s === "三刻") return 45;
  const m = s.match(/^(\d{1,2}|[一二三四五六七八九十]+)\s*分$/);
  if (!m) return 0;
  const n = parseChineseHour(m[1]);
  return n ?? 0;
}

function extractTime(seg: string): { time: string | null; cleaned: string } {
  const noonOnly = /(?:中午|午间|正午)(?:十?二?点?(?:半|一刻|三刻)?)?/;
  const mNoon = seg.match(noonOnly);
  if (mNoon) {
    let minute = 0;
    if (/半/.test(mNoon[0])) minute = 30;
    else if (/一刻/.test(mNoon[0])) minute = 15;
    else if (/三刻/.test(mNoon[0])) minute = 45;
    const cleaned = seg.replace(mNoon[0], " ");
    return { time: formatHhmm(12, minute), cleaned };
  }

  const clock = new RegExp(
    "(凌晨|早上|上午|下午|傍晚|晚上|夜晚|夜间)?\\s*" +
      "(\\d{1,2}|[一二三四五六七八九十两]{1,3})\\s*" +
      "(?:点|:|：)\\s*" +
      "(半|一刻|三刻|(\\d{1,2}|[一二三四五六七八九十]+)\\s*分)?",
    "u",
  );
  const mClock = seg.match(clock);
  if (mClock) {
    const period = mClock[1];
    const hourRaw = parseChineseHour(mClock[2]);
    if (hourRaw !== null) {
      const hour = applyDayPeriod(period, hourRaw);
      const minute = parseMinuteSuffix(mClock[3] ?? mClock[4]);
      const cleaned = seg.replace(mClock[0], " ");
      return { time: formatHhmm(hour, minute), cleaned };
    }
  }

  const halfPast = new RegExp(
    "(凌晨|早上|上午|下午|傍晚|晚上|夜晚|夜间)?\\s*" +
      "(\\d{1,2}|[一二三四五六七八九十两]{1,3})\\s*点半",
    "u",
  );
  const mHalf = seg.match(halfPast);
  if (mHalf) {
    const hourRaw = parseChineseHour(mHalf[2]);
    if (hourRaw !== null) {
      const hour = applyDayPeriod(mHalf[1], hourRaw);
      const cleaned = seg.replace(mHalf[0], " ");
      return { time: formatHhmm(hour, 30), cleaned };
    }
  }

  const colon = /(\d{1,2})[:：](\d{2})/;
  const mColon = seg.match(colon);
  if (mColon) {
    const hour = Number.parseInt(mColon[1], 10);
    const minute = Number.parseInt(mColon[2], 10);
    if (!Number.isNaN(hour) && !Number.isNaN(minute)) {
      const cleaned = seg.replace(mColon[0], " ");
      return { time: formatHhmm(hour, minute), cleaned };
    }
  }

  return { time: null, cleaned: seg };
}

function weekdayIndexesFromChars(chars: string): number[] {
  const out: number[] = [];
  for (const ch of chars) {
    const d = WEEKDAY_CHAR[ch];
    if (d !== undefined) out.push(d);
  }
  return [...new Set(out)].sort((a, b) => a - b);
}

function extractRepeat(seg: string): { days: number[] | null; cleaned: string } {
  if (/每天|每一天|天天/.test(seg)) {
    return { days: [...ALL_DAYS], cleaned: seg.replace(/每天|每一天|天天/g, " ") };
  }
  if (/每个工作日|工作日/.test(seg)) {
    return { days: [...WEEKDAYS], cleaned: seg.replace(/每个工作日|工作日/g, " ") };
  }
  if (/周末/.test(seg)) {
    return { days: [...WEEKEND], cleaned: seg.replace(/周末/g, " ") };
  }

  const range = seg.match(
    /(?:每)?(?:周|星期|礼拜)?([一二三四五六])\s*至\s*(?:周|星期|礼拜)?([一二三四五六])/u,
  );
  if (range) {
    const start = WEEKDAY_CHAR[range[1]];
    const end = WEEKDAY_CHAR[range[2]];
    if (start !== undefined && end !== undefined && start <= end) {
      const days: number[] = [];
      for (let d = start; d <= end; d++) days.push(d);
      const cleaned = seg.replace(range[0], " ");
      return { days, cleaned };
    }
  }

  const single = seg.match(/(?:每)?(?:周|星期|礼拜)([一二三四五六日天七])/u);
  if (single) {
    const d = WEEKDAY_CHAR[single[1]];
    if (d !== undefined) {
      return { days: [d], cleaned: seg.replace(single[0], " ") };
    }
  }

  const shorthand = seg.match(
    /(?:每)?(?:周|星期)?([一二三四五六日天七]{2,})/u,
  );
  if (shorthand) {
    const days = weekdayIndexesFromChars(shorthand[1]);
    if (days.length >= 2) {
      return { days, cleaned: seg.replace(shorthand[0], " ") };
    }
  }

  const bare = seg.match(/^([一二三四五六])([三五]|[二四])$/u);
  if (bare) {
    const days = weekdayIndexesFromChars(bare[0]);
    if (days.length >= 2) {
      return { days, cleaned: seg.replace(bare[0], " ") };
    }
  }

  const inline = seg.match(/([一二三四五六日天七]{2,})(?=[早晚上下])/u);
  if (inline) {
    const days = weekdayIndexesFromChars(inline[1]);
    if (days.length >= 2) {
      return { days, cleaned: seg.replace(inline[0], " ") };
    }
  }

  return { days: null, cleaned: seg };
}

function cleanTitle(raw: string): string {
  let s = raw
    .replace(/[,，、;；]/g, "")
    .replace(/(?:提醒我|记得|要|帮我|请|我|需要|一下)/g, "")
    .replace(/(?:每个|每周)/g, "")
    .replace(/\s+/g, "")
    .trim();
  s = s.replace(/^[的了呢吧啊呀]+/, "").replace(/[的了呢吧啊呀]+$/, "");
  return s;
}

function parseSegment(seg: string): RoutineDraft | null {
  const trimmed = seg.trim();
  if (!trimmed) return null;

  const repeat = extractRepeat(trimmed);
  const time = extractTime(repeat.cleaned);
  let title = cleanTitle(time.cleaned);

  if (!title) {
    title = cleanTitle(trimmed);
  }
  if (!title) return null;

  return {
    title,
    default_time: time.time ?? DEFAULT_TIME,
    repeat_days: repeat.days ?? [...DEFAULT_REPEAT],
    note: null,
  };
}

function splitSegments(text: string): string[] {
  const parts = text
    .split(/[,，、;；]|(?:然后|再|还有|以及)(?=\s*[\u4e00-\u9fff])/u)
    .map((s) => s.trim())
    .filter(Boolean);
  return parts.length ? parts : [text.trim()];
}

export function parseRoutinesFromText(text: string): RoutineDraft[] {
  const normalized = text.trim();
  if (!normalized) return [];

  const segments = splitSegments(normalized);
  const routines: RoutineDraft[] = [];

  for (const seg of segments) {
    const draft = parseSegment(seg);
    if (draft) routines.push(draft);
  }

  return routines;
}

export function todayInTimezone(timeZone: string): string {
  const tz = timeZone.trim() || "Asia/Shanghai";
  try {
    return Temporal.Now.zonedDateTimeISO(tz).toPlainDate().toString();
  } catch {
    return Temporal.Now.zonedDateTimeISO("Asia/Shanghai").toPlainDate().toString();
  }
}
