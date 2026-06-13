export type ReviewTaskInput = {
  id: string;
  title: string;
  status: string;
  planned_start?: string | null;
  planned_duration?: number | null;
  important?: boolean;
  actual_start?: string | null;
  actual_end?: string | null;
  was_rescheduled?: boolean;
  reschedule_count?: number;
  source?: string | null;
  task_type?: string | null;
  time_of_day?: string | null;
  note?: string | null;
};

export type ProposedStatusChange = {
  task_id: string;
  title: string;
  from_status: string;
  to_status: "done" | "skipped";
};

/** 计划外、复盘语音补记的新活动（apply 时 INSERT）。 */
export type AddedTaskProposal = {
  client_key: string;
  title: string;
  planned_start: string | null;
  planned_duration: number | null;
  to_status: "done";
};

export type ReviewParseResponse = {
  review_id: string;
  provider: "deepseek" | "qwen" | "kimi";
  summary: string;
  proposed: ProposedStatusChange[];
  added: AddedTaskProposal[];
  warnings: string[];
  unclear: boolean;
};

export type ReviewApplyRequest = {
  date: string;
  review_id: string;
  proposed: Array<{ task_id: string; to_status: "done" | "skipped" }>;
  added: Array<{
    client_key: string;
    title: string;
    planned_start?: string | null;
    planned_duration?: number | null;
  }>;
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
  was_rescheduled: boolean;
  reschedule_count: number;
  source: string | null;
  task_type: string | null;
  time_of_day: string | null;
};
