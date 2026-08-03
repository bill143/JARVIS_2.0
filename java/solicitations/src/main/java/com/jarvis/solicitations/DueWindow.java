package com.jarvis.solicitations;

/**
 * Closing-date buckets an operator filters by. Boundaries are inclusive on the low end: {@code <28d}
 * is 0–27 days out, {@code 28–45d} is 28–45, {@code 45+d} is 46+. Anything already past due (negative
 * days) is in no window except {@link #ANY}.
 */
public enum DueWindow {
    ANY,
    LT_28,
    D28_45,
    GT_45;

    /** Whether {@code daysUntilDue} falls in this window. {@link #ANY} always matches. */
    public boolean contains(long daysUntilDue) {
        return switch (this) {
            case ANY -> true;
            case LT_28 -> daysUntilDue >= 0 && daysUntilDue < 28;
            case D28_45 -> daysUntilDue >= 28 && daysUntilDue <= 45;
            case GT_45 -> daysUntilDue > 45;
        };
    }

    /** Lenient parse: unknown/blank → {@link #ANY}. Accepts {@code <28d}, {@code 28-45d}, {@code 45+d}. */
    public static DueWindow parse(String value) {
        if (value == null) {
            return ANY;
        }
        return switch (value.strip().toLowerCase()) {
            case "lt_28", "<28d", "<28", "28-" -> LT_28;
            case "d28_45", "28-45d", "28-45" -> D28_45;
            case "gt_45", "45+d", "45+", ">45" -> GT_45;
            default -> ANY;
        };
    }
}
