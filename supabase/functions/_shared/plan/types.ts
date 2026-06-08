export type HardConstraint = {
  start: string;
  end: string;
  title: string;
};

export type PlanGenerateRequest = {
  date: string;
  raw_input: string;
  hard_constraints?: HardConstraint[];
  tone?: "quiet" | "friendly" | "reflective";
};

export type TaskType =
  | "deep_work"
  | "admin"
  | "social"
  | "health"
  | "errand"
  | "recovery";

export type TimeOfDay = "morning" | "midday" | "afternoon" | "evening";

export type TaskSource = "user_voice" | "user_text" | "wechat" | "ai_suggestion" | "routine";

export type TaskStatus = "planned" | "done" | "skipped" | "dropped" | "suggested";

export type AiTaskItem = {
  title: string;
  note?: string;
  planned_start?: string;
  planned_duration?: number;
  important?: boolean;
  task_type: TaskType;
  time_of_day: TimeOfDay;
};

export type AiPlanOutput = {
  tasks: AiTaskItem[];
  suggestion_tasks?: AiTaskItem[];
  ai_comment: string;
};

export type PlanTaskResponse = AiTaskItem & {
  source: TaskSource;
  /** 真任务默认 planned；suggestion_tasks 固定 suggested（未接受）。 */
  status: TaskStatus;
};

export type PlanGenerateResponse = {
  proposal_id: string;
  provider: "deepseek" | "qwen" | "kimi";
  tasks: PlanTaskResponse[];
  suggestion_tasks: PlanTaskResponse[];
  ai_comment: string;
};
