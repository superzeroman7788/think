export type InboxDuePart = "morning" | "afternoon" | "evening";
export type InboxStatus = "pending" | "added" | "dismissed" | "deleted";
export type InboxSource = "voice" | "text" | "widget" | "shortcut" | "plan_defer";

export type InboxItemRow = {
  id: string;
  user_id: string;
  text: string;
  raw_text: string;
  due_date: string | null;
  due_part: InboxDuePart | null;
  status: InboxStatus;
  source: InboxSource;
  added_task_id: string | null;
  created_at: string;
  updated_at: string;
  due_handled_at: string | null;
};

export type InboxCaptureRequest = {
  raw_text?: string;
  structured?: {
    title: string;
    due_date: string;
    due_part?: InboxDuePart | null;
  };
  client_local_date: string;
  client_tz: string;
  source?: InboxSource;
};

export type InboxCaptureResponse = {
  id: string;
  title: string;
  due_date: string | null;
  due_part: InboxDuePart | null;
  extract_failed?: boolean;
  provider?: "deepseek" | "qwen" | "kimi";
};

export type InboxUpdateAction = "add_today" | "dismiss" | "delete" | "set_due";

export type InboxUpdateRequest = {
  id: string;
  action: InboxUpdateAction;
  client_local_date?: string;
  client_tz?: string;
  due_date?: string | null;
  due_part?: InboxDuePart | null;
};

export type InboxBadgeResponse = {
  count: number;
};

export type InboxExtractResult = {
  title: string;
  due_date: string | null;
  due_part: InboxDuePart | null;
  provider?: "deepseek" | "qwen" | "kimi";
  extract_failed: boolean;
};
