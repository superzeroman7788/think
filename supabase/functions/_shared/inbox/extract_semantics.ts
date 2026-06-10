import { stripJsonFences } from "../plan/schema.ts";
import { plannedStartToIso } from "../plan/timezone.ts";
import type { InboxDuePart, InboxExtractResult } from "./types.ts";

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const DUE_PARTS = new Set<InboxDuePart>(["morning", "afternoon", "evening"]);

function normalizeTitle(raw: string, fallback: string): string {
  const t = raw.trim().slice(0, 80);
  return t.length >= 1 ? t : fallback.trim().slice(0, 80);
}

function normalizeDueDate(value: unknown, anchorDate: string): string | null {
  if (value === null || value === undefined || value === "") return null;
  const s = String(value).trim();
  if (!DATE_RE.test(s)) return null;
  const anchor = Temporal.PlainDate.from(anchorDate);
  const due = Temporal.PlainDate.from(s);
  const diff = due.since(anchor).days;
  if (diff < -7 || diff > 400) return null;
  return s;
}

function normalizeDuePart(value: unknown): InboxDuePart | null {
  if (value === null || value === undefined || value === "") return null;
  const s = String(value).trim().toLowerCase();
  return DUE_PARTS.has(s as InboxDuePart) ? (s as InboxDuePart) : null;
}

export function parseExtractJson(
  raw: string,
  rawText: string,
  anchorDate: string,
): Omit<InboxExtractResult, "provider" | "extract_failed"> {
  const parsed = JSON.parse(stripJsonFences(raw)) as Record<string, unknown>;
  return {
    title: normalizeTitle(String(parsed.title ?? ""), rawText),
    due_date: normalizeDueDate(parsed.due_date, anchorDate),
    due_part: normalizeDuePart(parsed.due_part),
  };
}

export function fallbackExtract(rawText: string): InboxExtractResult {
  return {
    title: normalizeTitle(rawText, rawText),
    due_date: null,
    due_part: null,
    extract_failed: true,
  };
}

export function plannedStartForAddToday(
  taskDate: string,
  duePart: InboxDuePart | null,
  tz: string,
): { plannedStart: string | null; timeOfDay: InboxDuePart | null } {
  if (!duePart) return { plannedStart: null, timeOfDay: null };
  const hhmm = duePart === "morning" ? "09:00" : duePart === "afternoon" ? "14:00" : "18:00";
  return {
    plannedStart: plannedStartToIso(taskDate, hhmm, tz),
    timeOfDay: duePart,
  };
}

export function isValidClientDate(value: string): boolean {
  return DATE_RE.test(value);
}
