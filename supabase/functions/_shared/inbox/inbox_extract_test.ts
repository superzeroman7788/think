import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  fallbackExtract,
  parseExtractJson,
  plannedStartForAddToday,
} from "./extract_semantics.ts";

Deno.test("parse: relative thursday afternoon", () => {
  const anchor = "2026-06-08"; // Sunday
  const out = parseExtractJson(
    '{"title":"发报告","due_date":"2026-06-12","due_part":"afternoon"}',
    "周四下午发报告",
    anchor,
  );
  assertEquals(out.title, "发报告");
  assertEquals(out.due_date, "2026-06-12");
  assertEquals(out.due_part, "afternoon");
});

Deno.test("parse: no date → null", () => {
  const out = parseExtractJson(
    '{"title":"买咖啡豆","due_date":null,"due_part":null}',
    "买咖啡豆",
    "2026-06-08",
  );
  assertEquals(out.due_date, null);
  assertEquals(out.due_part, null);
});

Deno.test("fallback keeps raw text", () => {
  const out = fallbackExtract("周四下午发报告");
  assertEquals(out.extract_failed, true);
  assertEquals(out.due_date, null);
  assertEquals(out.title.includes("发报告"), true);
});

Deno.test("plannedStartForAddToday afternoon", () => {
  const { plannedStart, timeOfDay } = plannedStartForAddToday(
    "2026-06-08",
    "afternoon",
    "Asia/Shanghai",
  );
  assertEquals(timeOfDay, "afternoon");
  assertEquals(plannedStart?.includes("T14:00:00"), true);
});

Deno.test("plannedStartForAddToday no part", () => {
  const { plannedStart, timeOfDay } = plannedStartForAddToday("2026-06-08", null, "Asia/Shanghai");
  assertEquals(plannedStart, null);
  assertEquals(timeOfDay, null);
});
