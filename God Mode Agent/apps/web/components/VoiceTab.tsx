"use client";

import { useEffect, useRef, useState } from "react";
import { blobToBase64, wsUrl } from "@/lib/api";

type VoiceEvent = { type: string; text?: string; message?: string; audio_b64?: string; media_type?: string; engine?: string };

export default function VoiceTab() {
  const [connected, setConnected] = useState(false);
  const [recording, setRecording] = useState(false);
  const [partial, setPartial] = useState("");
  const [log, setLog] = useState<string[]>([]);
  const [simulated, setSimulated] = useState("");
  const wsRef = useRef<WebSocket | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const append = (line: string) => setLog((prev) => [...prev.slice(-40), line]);

  useEffect(() => {
    const ws = new WebSocket(wsUrl("/realtime/voice"));
    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => append("⚠ websocket error - is the API running?");
    ws.onmessage = (msg) => {
      const event: VoiceEvent = JSON.parse(msg.data);
      if (event.type === "transcript.partial") setPartial(event.text ?? "");
      else if (event.type === "transcript.final") {
        setPartial("");
        append(`🎤 You: ${event.text}`);
      } else if (event.type === "reply.text") append(`🤖 JARVIS: ${event.text}`);
      else if (event.type === "tts.audio" && event.audio_b64) {
        append(`🔊 TTS (${event.engine})`);
        const audio = new Audio(`data:${event.media_type};base64,${event.audio_b64}`);
        audioRef.current = audio;
        audio.play().catch(() => append("⚠ audio playback blocked - click the page first"));
      } else if (event.type === "interrupted") {
        audioRef.current?.pause();
        append("⏹ interrupted (barge-in)");
      } else if (event.type === "error") append(`⚠ ${event.message}`);
    };
    wsRef.current = ws;
    return () => ws.close();
  }, []);

  async function toggleMic() {
    if (recording) {
      recorderRef.current?.stop();
      setRecording(false);
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const recorder = new MediaRecorder(stream);
      recorder.ondataavailable = async (e) => {
        if (e.data.size > 0 && wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: "audio", data_b64: await blobToBase64(e.data) }));
        }
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        wsRef.current?.send(JSON.stringify({ type: "commit" }));
      };
      recorder.start(500);
      recorderRef.current = recorder;
      setRecording(true);
      append("🎙 recording… (real STT needs a Deepgram key; otherwise use simulated speech below)");
    } catch (err) {
      append(`⚠ microphone unavailable: ${err}`);
    }
  }

  function sendSimulated() {
    const text = simulated.trim();
    if (!text || wsRef.current?.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(JSON.stringify({ type: "text", text }));
    setSimulated("");
  }

  function bargeIn() {
    wsRef.current?.send(JSON.stringify({ type: "barge_in" }));
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <span className={`h-2 w-2 rounded-full ${connected ? "bg-emerald-400" : "bg-red-500"}`} />
        <span className="text-sm text-zinc-400">{connected ? "voice channel connected" : "disconnected"}</span>
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          onClick={toggleMic}
          className={`rounded-md px-4 py-2 text-sm font-medium ${recording ? "bg-red-600" : "bg-emerald-600"}`}
        >
          {recording ? "■ Stop & transcribe" : "🎙 Start mic"}
        </button>
        <button onClick={bargeIn} className="rounded-md bg-amber-600 px-4 py-2 text-sm font-medium">
          ✋ Barge in
        </button>
      </div>

      <div className="flex gap-2">
        <input
          value={simulated}
          onChange={(e) => setSimulated(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && sendSimulated()}
          placeholder="Simulate speech (works offline, e.g. 'compute 6*7')"
          aria-label="Simulated speech"
          className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
        <button onClick={sendSimulated} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">
          Speak
        </button>
      </div>

      {partial && <p className="text-sm italic text-zinc-400">…{partial}</p>}

      <div className="h-[45vh] overflow-y-auto rounded-lg border border-zinc-800 p-3 text-sm">
        {log.length === 0 && <p className="text-zinc-500">Transcripts and replies appear here.</p>}
        {log.map((line, i) => (
          <p key={i} className="mb-1 whitespace-pre-wrap">{line}</p>
        ))}
      </div>
    </div>
  );
}
