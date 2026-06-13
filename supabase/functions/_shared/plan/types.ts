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

export type TaskKind = "block" | "point";

export type AiTaskItem = {
  title: string;
  note?: string;
  planned_start?: string;
  planned_duration?: number;
  important?: boolean;
  task_type: TaskType;
  time_of_day: TimeOfDay;
  /** block=时间块(默认); point=时刻点钉子 */
  kind?: TaskKind;
  /** 提案阶段:锚定 block 的 planned_start(HH:MM); 落库后 FE 写 anchor_task_id */
  anchor_block_start?: string;
  anchor_task_id?: string | null;
};

export type DeferredItem = {
  title: string;
  due_date: string;
  due_part?: "morning" | "afternoon" | "evening" | null;
};

export type AiPlanOutput = {
  tasks: AiTaskItem[];
  suggestion_tasks?: AiTaskItem[];
  ai_comment: string;
  deferred?: DeferredItem[];
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
  deferred: DeferredItem[];
};
