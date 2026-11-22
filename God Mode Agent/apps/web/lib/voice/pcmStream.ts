export function floatToPcm16(samples: Float32Array): Int16Array {
  const output = new Int16Array(samples.length);
  for (let i = 0; i < samples.length; i += 1) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    output[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return output;
}

export function downmixToMono(buffer: Float32Array): Float32Array {
  if (buffer.length === 0) return new Float32Array(0);
  const mono = new Float32Array(buffer.length / 2);
  for (let i = 0; i < mono.length; i += 1) {
    const left = buffer[i * 2] ?? 0;
    const right = buffer[i * 2 + 1] ?? 0;
    mono[i] = (left + right) / 2;
  }
  return mono;
}

export function startPcmStream(wsUrl: string): { stop: () => void } {
  const stopHandles: Array<() => void> = [];

  const notify = (message: string) => {
    if (typeof window !== "undefined") {
      const banner = document.getElementById("pcm-warning");
      if (banner) {
        banner.textContent = message;
      }
    }
  };

  if (typeof window === "undefined") {
    return { stop: () => undefined };
  }

  if (!window.isSecureContext) {
    notify("Microphone access requires a secure context (HTTPS or localhost). This page is not in a secure context.");
    return { stop: () => undefined };
  }

  let ws: WebSocket | null = null;
  try {
    ws = new WebSocket(wsUrl);
  } catch {
    notify("Microphone stream could not open a websocket connection.");
    return { stop: () => undefined };
  }

  const stop = () => {
    stopHandles.forEach((handler) => handler());
    ws?.close();
  };

  const start = async () => {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      notify("Microphone input is not available in this browser.");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const context = new AudioContext();
      const source = context.createMediaStreamSource(stream);
      if (typeof AudioWorklet !== "undefined" && context.audioWorklet) {
        const workletUrl = URL.createObjectURL(
          new Blob(
            [
              `class PcmProcessor extends AudioWorkletProcessor { process(inputs) { const input = inputs[0]; if (!input || input.length === 0) return true; const mono = input[0]; const pcm = new Int16Array(mono.length); for (let i = 0; i < mono.length; i += 1) { const s = Math.max(-1, Math.min(1, mono[i] ?? 0)); pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff; } this.port.postMessage(Array.from(pcm)); return true; } } registerProcessor('pcm-processor', PcmProcessor);`,
            ],
            { type: "application/javascript" },
          ),
        );

        try {
          await context.audioWorklet.addModule(workletUrl);
          const node = new AudioWorkletNode(context, "pcm-processor");
          node.port.onmessage = (event) => {
            if (ws && ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: "pcm", samples: Array.from(event.data) }));
            }
          };
          source.connect(node);
          node.connect(context.destination);
          stopHandles.push(() => {
            node.disconnect();
            stream.getTracks().forEach((track) => track.stop());
          });
          return;
        } catch {
          // fallback to ScriptProcessor below
        }
      }

      const bufferSize = 4096;
      const processor = context.createScriptProcessor(bufferSize, 1, 1);
      processor.onaudioprocess = (event) => {
        const input = event.inputBuffer.getChannelData(0);
        const mono = downmixToMono(input);
        const pcm = floatToPcm16(mono);
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "pcm", samples: Array.from(pcm) }));
        }
      };
      source.connect(processor);
      processor.connect(context.destination);
      stopHandles.push(() => {
        processor.disconnect();
        stream.getTracks().forEach((track) => track.stop());
        context.close();
      });
    } catch (error) {
      notify(`Microphone access failed: ${error instanceof Error ? error.message : "unknown error"}`);
    }
  };

  void start();

  return { stop };
}
