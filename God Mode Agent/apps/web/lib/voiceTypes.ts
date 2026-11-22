// Shared types for the ECHO voice route (JARVIS_UI_LOCKED_SPEC.md §3).

export type OrbState = "standby" | "listening" | "thinking" | "speaking";

export type Turn = { role: "you" | "echo"; text: string; at: number };
