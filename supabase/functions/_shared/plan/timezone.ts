const DEFAULT_TIMEZONE = "Asia/Shanghai";

/** combine date (YYYY-MM-DD) + HH:MM + IANA timezone → ISO 8601 with offset */
export function plannedStartToIso(
  date: string,
  hhmm: string,
  timeZone = DEFAULT_TIMEZONE,
): string {
  const tz = timeZone.trim() || DEFAULT_TIMEZONE;
  try {
    const zdt = Temporal.ZonedDateTime.from(`${date}T${hhmm}:00[${tz}]`);
    return zdt.toString().replace(/\[.*\]$/, "");
  } catch {
    const fallback = Temporal.ZonedDateTime.from(`${date}T${hhmm}:00[${DEFAULT_TIMEZONE}]`);
    return fallback.toString().replace(/\[.*\]$/, "");
  }
}
