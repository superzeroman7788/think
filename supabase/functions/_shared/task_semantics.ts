/** 已接受任务集：用户承诺跟踪、参与计数/执行/复盘/推送的项。 */
export function isCommittedTask(task: { status: string }): boolean {
  return task.status !== "dropped" && task.status !== "suggested";
}

/** 软建议 = AI 礼物，未点「加入」前不进已接受任务集。 */
export function isSoftSuggestion(task: { status: string; source?: string | null }): boolean {
  return task.status === "suggested" ||
    (task.source === "ai_suggestion" && task.status === "planned");
}

/** 历史视图「过去 7 天」单日计数：做成/共（跳过不进分子分母；软建议整条排除）。 */
export type HistoryDayStats = { done: number; total: number };

export function historyDayStats(
  tasks: { status: string; source?: string | null }[],
): HistoryDayStats {
  const committed = tasks.filter((t) => isCommittedTask(t));
  const done = committed.filter((t) => t.status === "done").length;
  const undone = committed.filter((t) => t.status === "planned").length;
  return { done, total: done + undone };
}
