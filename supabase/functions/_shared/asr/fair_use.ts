import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

export type AsrQuotaSnapshot = {
  allowed: boolean;
  dailyAsrSessions: number;
  limit: number;
  alreadyCounted?: boolean;
};

export async function getDailyAsrQuota(
  supabase: SupabaseClient,
  today: string,
  dailyLimit: number,
): Promise<AsrQuotaSnapshot> {
  const { data, error } = await supabase.rpc("get_daily_asr_quota", {
    p_today: today,
    p_limit: dailyLimit,
  });
  if (error) throw error;

  const row = data as {
    allowed: boolean;
    daily_asr_sessions: number;
    limit: number;
  };

  return {
    allowed: row.allowed,
    dailyAsrSessions: row.daily_asr_sessions,
    limit: row.limit ?? dailyLimit,
  };
}

/** 实际连上/出字时调用；同一 session_id 幂等。 */
export async function consumeDailyAsrUsage(
  supabase: SupabaseClient,
  today: string,
  dailyLimit: number,
  sessionId: string,
): Promise<AsrQuotaSnapshot> {
  const { data, error } = await supabase.rpc("try_consume_daily_asr_usage", {
    p_today: today,
    p_session_id: sessionId,
    p_limit: dailyLimit,
  });
  if (error) throw error;

  const row = data as {
    allowed: boolean;
    already_counted?: boolean;
    daily_asr_sessions: number;
    limit: number;
  };

  return {
    allowed: row.allowed,
    dailyAsrSessions: row.daily_asr_sessions,
    limit: row.limit ?? dailyLimit,
    alreadyCounted: row.already_counted ?? false,
  };
}

/** @deprecated 预取时代扣配额；现等同 getDailyAsrQuota，保留兼容旧 import。 */
export async function consumeDailyAsrSession(
  supabase: SupabaseClient,
  today: string,
  dailyLimit: number,
): Promise<AsrQuotaSnapshot> {
  return getDailyAsrQuota(supabase, today, dailyLimit);
}
