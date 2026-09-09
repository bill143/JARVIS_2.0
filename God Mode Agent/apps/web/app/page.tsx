"use client";

// `/` is the ECHO voice presence (JARVIS_UI_LOCKED_SPEC.md).
// The admin console lives at /console. Two routes — never blended.

import VoiceRoute from "@/components/voice/VoiceRoute";

export default function Home() {
  return <VoiceRoute />;
}
