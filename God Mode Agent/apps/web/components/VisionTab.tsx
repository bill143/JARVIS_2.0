"use client";

import { useState } from "react";
import { fileToBase64, postJson } from "@/lib/api";

type VisionData = {
  ok: boolean;
  source: string;
  width: number | null;
  height: number | null;
  mean_brightness: number | null;
  ocr_text: string;
  ocr_engine: string;
  caption: string;
  note: string;
};

type SnapshotData = { status: string; output: { source: string; note: string; analysis: VisionData } };

export default function VisionTab() {
  const [preview, setPreview] = useState<string | null>(null);
  const [result, setResult] = useState<VisionData | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function analyzeFile(file: File) {
    setBusy(true);
    setError("");
    setResult(null);
    setPreview(URL.createObjectURL(file));
    const b64 = await fileToBase64(file);
    const resp = await postJson<VisionData>("/vision/analyze", {
      image_b64: b64,
      source: "web-upload",
      session_id: "web-vision",
    });
    if (resp.success) setResult(resp.data);
    else setError(`${resp.error.code}: ${resp.error.message}`);
    setBusy(false);
  }

  async function webcamSnapshot() {
    setBusy(true);
    setError("");
    const resp = await postJson<SnapshotData>("/tools/execute", {
      tool: "webcam_snapshot",
      arguments: {},
      session_id: "web-vision",
    });
    if (resp.success) {
      setResult(resp.data.output.analysis);
      setPreview(null);
      if (resp.data.output.note) setError(resp.data.output.note);
    } else setError(`${resp.error.code}: ${resp.error.message}`);
    setBusy(false);
  }

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <div className="space-y-3">
        <label className="block rounded-lg border-2 border-dashed border-zinc-700 p-6 text-center text-sm text-zinc-400 hover:border-emerald-500">
          <input
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => e.target.files?.[0] && analyzeFile(e.target.files[0])}
          />
          Click to upload an image for analysis + OCR
        </label>
        <button
          onClick={webcamSnapshot}
          disabled={busy}
          className="w-full rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          📷 Capture webcam snapshot (server-side)
        </button>
        {preview && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={preview} alt="uploaded preview" className="max-h-64 rounded-lg border border-zinc-800" />
        )}
        {busy && <p className="animate-pulse text-xs text-zinc-500">analyzing…</p>}
        {error && <p className="text-sm text-amber-400">{error}</p>}
      </div>

      <div className="rounded-lg border border-zinc-800 p-4 text-sm">
        <h2 className="mb-2 font-medium text-emerald-400">Analysis</h2>
        {!result && <p className="text-zinc-500">Upload an image or take a snapshot.</p>}
        {result && (
          <dl className="space-y-1">
            <Row k="Caption" v={result.caption} />
            <Row k="Source" v={result.source} />
            <Row k="Dimensions" v={result.width ? `${result.width} × ${result.height}` : "unknown"} />
            <Row k="Brightness" v={result.mean_brightness?.toString() ?? "n/a"} />
            <Row k="OCR engine" v={result.ocr_engine} />
            <Row k="OCR text" v={result.ocr_text || "(none detected)"} />
            {result.note && <Row k="Note" v={result.note} />}
          </dl>
        )}
      </div>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex gap-2">
      <dt className="w-28 shrink-0 text-zinc-500">{k}</dt>
      <dd className="whitespace-pre-wrap break-words">{v}</dd>
    </div>
  );
}
