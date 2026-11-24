/**
 * Validate ISO calendar dates before sending an audit query.
 * Empty boundaries are allowed; an inclusive start date must not follow
 * the inclusive end date. Date.UTC avoids browser-local timezone parsing.
 */
function isCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day;
}

export function getAuditDateRangeError(from: string, to: string): string | null {
  if (from && !isCalendarDate(from)) return "Enter a valid start date.";
  if (to && !isCalendarDate(to)) return "Enter a valid end date.";
  if (from && to && from > to) return "The start date must be on or before the end date.";
  return null;
}
