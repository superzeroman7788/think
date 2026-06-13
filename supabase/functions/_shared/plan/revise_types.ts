export type DeferredReviseItem = {
  title: string;
  due_date: string;
  due_part?: "morning" | "afternoon" | "evening" | null;
};

export type ReviseTaskInput = {
  id: string;
  title: string;
  planned_start: string | null;
  planned_duration: number | null;
  important: boolean;
  status: string;
  actual_start: string | null;
  kind?: "block" | "point";
};

export type RevisionState = {
  planned_start: string | null;
  planned_duration: number | null;
  status: string;
  actual_start: string | null;
};

export type RevisionItem = {
  task_id: string;
  title: string;
  change: "moved" | "skip" | "delete" | "unchanged";
  started: boolean;
  before: RevisionState;
  after: RevisionState;
};

export type PlanReviseRequest = {
  date: string;
  timezone: string;
  now: string;
  instruction: string;
  tasks: ReviseTaskInput[];
};

export type AddedReviseTask = {
  client_key: string;
  title: string;
  planned_start: string | null;
  planned_duration: number | null;
  important: boolean;
  kind?: "block" | "point";
};

export type ReviseIntentMode = "revise" | "bootstrap" | "clarify";

export type PlanReviseIntent = {
  mode: ReviseIntentMode;
};

export type PlanReviseResponse = {
  revision_id: string;
  provider: "deepseek" | "qwen" | "kimi";
  /** true ⟺ 存在 moved/skip/delete 或 added（v1.3+） */
  applicable: boolean;
  /** applicable=false 时必填；禁止静默拒 */
  reject_reason: string | null;
  /** v2: 意图路由（bootstrap=空计划新建 / revise=改已有 / clarify=需追问） */
  intent: PlanReviseIntent;
  summary: string;
  revisions: RevisionItem[];
  added: AddedReviseTask[];
  deferred: DeferredReviseItem[];
  warnings: string[];
};

export type ApplyRevisionInput = {
  task_id: string;
  change: "moved" | "skip" | "delete";
  after: {
    planned_start?: string;
    planned_duration?: number;
    status: string;
  };
};

export type PlanReviseApplyRequest = {
  date: string;
  revision_id: string;
  revisions: ApplyRevisionInput[];
  added: Array<{
    client_key: string;
    title: string;
    planned_start?: string | null;
    planned_duration?: number | null;
    important?: boolean;
  }>;
  deferred?: Array<{
    title: string;
    due_date: string;
    due_part?: "morning" | "afternoon" | "evening" | null;
  }>;
};

export type PlanReviseApplyResponse = {
  ok: boolean;
  applied_count: number;
  tasks: TaskRow[];
};

export type TaskRow = {
  id: string;
  title: string;
  note: string | null;
  planned_start: string | null;
  planned_duration: number | null;
  important: boolean;
  status: string;
  actual_start: string | null;
  actual_end: string | null;
  task_type: string | null;
  time_of_day: string | null;
  kind?: string | null;
  anchor_task_id?: string | null;
};

export type AiReviseOutput = {
  summary: string;
  warnings: string[];
  revisions: Array<{
    task_id: string;
    change: string;
    after?: Record<string, unknown>;
  }>;
  added: Array<{
    title: string;
    planned_start?: string;
    planned_duration?: number;
    important?: boolean;
    kind?: string;
  }>;
  deferred?: Array<{
    title: string;
    due_date: string;
    due_part?: string;
  }>;
};
