import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { isCommittedTask, isSoftSuggestion } from "./task_semantics.ts";

Deno.test("suggested is not committed", () => {
  assertEquals(isCommittedTask({ status: "suggested" }), false);
});

Deno.test("planned is committed", () => {
  assertEquals(isCommittedTask({ status: "planned" }), true);
});

Deno.test("legacy ai_suggestion+planned treated as soft", () => {
  assertEquals(isSoftSuggestion({ status: "planned", source: "ai_suggestion" }), true);
});

Deno.test("accepted ai_suggestion with planned status is committed", () => {
  assertEquals(isCommittedTask({ status: "planned" }), true);
});
