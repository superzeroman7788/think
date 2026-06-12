const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

let cachedTemplate: string | null = null;

export function setPlanGenerateTemplate(template: string): void {
  cachedTemplate = template;
}

/** loads plan_generate_v1.md at runtime; prefer setPlanGenerateTemplate() from entrypoint. */
export async function loadPlanGenerateTemplate(): Promise<string> {
  if (cachedTemplate) return cachedTemplate;

  const candidates = [
    new URL("../../plan-generate/plan_generate_v1.md", import.meta.url),
    new URL("./plan_generate_v1.md", import.meta.url),
    new URL("../../../prompts/plan_generate_v1.md", import.meta.url),
  ];

  for (const path of candidates) {
    try {
      cachedTemplate = await Deno.readTextFile(path);
      console.log(`[plan/generate] prompt loaded from ${path.pathname}`);
      return cachedTemplate;
    } catch {
      // try next path
    }
  }

  throw new Error("plan_generate_v1.md not found");
}

export const OUTPUT_SCHEMA_HINT = `{
  "type": "object",
  "required": ["tasks", "ai_comment"],
  "additionalProperties": false,
  "properties": {
    "tasks": {
      "type": "array",
      "minItems": 1,
      "maxItems": 10,
      "items": {
        "type": "object",
        "required": ["title", "task_type", "time_of_day"],
        "additionalProperties": false,
        "properties": {
          "title": { "type": "string", "maxLength": 60 },
          "note": { "type": "string", "maxLength": 100 },
          "planned_start": { "type": "string", "pattern": "^[0-9]{2}:[0-9]{2}$" },
          "planned_duration": { "type": "integer", "minimum": 0, "maximum": 480 },
          "important": { "type": "boolean" },
          "kind": { "enum": ["block", "point"] },
          "anchor_block_start": { "type": "string", "pattern": "^[0-9]{2}:[0-9]{2}$" },
          "task_type": { "enum": ["deep_work","admin","social","health","errand","recovery"] },
          "time_of_day": { "enum": ["morning","midday","afternoon","evening"] }
        }
      }
    },
    "suggestion_tasks": {
      "type": "array",
      "maxItems": 3,
      "items": { "$ref": "#/properties/tasks/items" }
    },
    "ai_comment": { "type": "string", "maxLength": 200, "description": "1-3 lines separated by \\n, length matches user input" }
  }
}`;

export type PromptVars = {
  date: string;
  weekday: string;
  memoriesBullets: string | null;
  hardConstraints: string;
  rawInput: string;
};

export function weekdayZh(isoDate: string): string {
  const d = new Date(`${isoDate}T12:00:00`);
  return WEEKDAYS[d.getDay()];
}

export function formatHardConstraints(
  constraints: { start: string; end: string; title: string }[] | undefined,
): string {
  if (!constraints?.length) return "(无)";
  return constraints
    .map((c) => `- ${c.start}-${c.end} ${c.title}`)
    .join("\n");
}

export function formatMemoriesBullets(memories: string[]): string | null {
  if (!memories.length) return null;
  return memories.map((m) => `- ${m}`).join("\n");
}

export async function renderPlanPrompt(vars: PromptVars): Promise<{ system: string; user: string }> {
  const planGenerateTemplate = await loadPlanGenerateTemplate();
  let userBody = planGenerateTemplate;

  if (vars.memoriesBullets) {
    userBody = userBody.replace(/\{\{#if memories\}\}([\s\S]*?)\{\{\/if\}\}/, "$1");
    userBody = userBody.replace("{{memories_bullets}}", vars.memoriesBullets);
  } else {
    userBody = userBody.replace(/\{\{#if memories\}\}[\s\S]*?\{\{\/if\}\}\n?/g, "");
  }

  userBody = userBody
    .replace("{{date}}", vars.date)
    .replace("{{weekday}}", vars.weekday)
    .replace("{{hard_constraints}}", vars.hardConstraints)
    .replace("{{raw_input}}", vars.rawInput);

  const systemMatch = userBody.match(/\[SYSTEM\]\s*([\s\S]*?)\s*\[USER\]/);
  const userMatch = userBody.match(/\[USER\]\s*([\s\S]*)$/);
  const system = (systemMatch?.[1] ?? "").trim();
  const user = (userMatch?.[1] ?? "").trim();

  const userWithSchema = `${user}\n\nJSON schema:\n${OUTPUT_SCHEMA_HINT}`;

  return { system, user: userWithSchema };
}
