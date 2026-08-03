/**
 * MODULE 1 — THE ROAMING BRAIN
 * Real-Time Perceptual Intelligence Engine
 *
 * Primary Model: Gemini 2.5 Pro via Multimodal Live API
 * Transport: Persistent bidirectional WebSocket (REST prohibited)
 * Latency ceiling: < 800ms round-trip for audio response initiation
 */

import { GoogleGenerativeAI } from '@google/generative-ai';
import WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';

export interface EchoLiveConfig {
  apiKey: string;
  modelId: string;
  visionFps: number;
  reconnectMaxRetries: number;
}

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'degraded';

interface LiveSession {
  sessionId: string;
  ws: WebSocket | null;
  state: ConnectionState;
  reconnectAttempts: number;
}

const DEFAULT_CONFIG: EchoLiveConfig = {
  apiKey: '',
  modelId: 'gemini-2.5-pro',
  visionFps: 1,
  reconnectMaxRetries: 10,
};

export class EchoLiveService {
  private config: EchoLiveConfig;
  private session: LiveSession;
  private genAI: GoogleGenerativeAI;
  private frameInterval: ReturnType<typeof setInterval> | null = null;
  private onStateChange?: (state: ConnectionState) => void;
  private onResponseDelta?: (delta: { type: 'audio' | 'text'; data: string }) => void;

  constructor(config: Partial<EchoLiveConfig> & { apiKey: string }) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.genAI = new GoogleGenerativeAI(this.config.apiKey);
    this.session = {
      sessionId: uuidv4(),
      ws: null,
      state: 'disconnected',
      reconnectAttempts: 0,
    };
  }

  /** Opens Gemini Live WebSocket session */
  async connect(): Promise<void> {
    this.setState('connecting');
    try {
      // Gemini Multimodal Live API WebSocket endpoint
      const wsUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${this.config.apiKey}`;
      this.session.ws = new WebSocket(wsUrl);

      this.session.ws.on('open', () => {
        this.setState('connected');
        this.session.reconnectAttempts = 0;
        // Send initial setup message
        this.sendSetup();
      });

      this.session.ws.on('message', (data: WebSocket.Data) => {
        this.handleMessage(data);
      });

      this.session.ws.on('close', () => {
        this.setState('disconnected');
        this.attemptReconnect();
      });

      this.session.ws.on('error', (err: Error) => {
        console.error('[ECHO-LIVE] WebSocket error:', err.message);
        this.setState('degraded');
      });
    } catch (err) {
      this.setState('degraded');
      throw err;
    }
  }

  /** Encodes mic input and sends PCM chunks */
  streamAudio(pcmChunk: Buffer): void {
    if (!this.session.ws || this.session.state !== 'connected') return;
    const message = {
      realtimeInput: {
        mediaChunks: [{
          mimeType: 'audio/pcm;rate=16000',
          data: pcmChunk.toString('base64'),
        }],
      },
    };
    this.session.ws.send(JSON.stringify(message));
  }

  /** Grabs 1FPS canvas snapshot, encodes to base64 for vision tokens */
  captureFrame(frameBuffer: Buffer): void {
    if (!this.session.ws || this.session.state !== 'connected') return;
    const message = {
      realtimeInput: {
        mediaChunks: [{
          mimeType: 'image/jpeg',
          data: frameBuffer.toString('base64'),
        }],
      },
    };
    this.session.ws.send(JSON.stringify(message));
  }

  /** Starts automatic frame capture at configured FPS */
  startFrameCapture(captureProvider: () => Buffer): void {
    const intervalMs = 1000 / this.config.visionFps;
    this.frameInterval = setInterval(() => {
      const frame = captureProvider();
      this.captureFrame(frame);
    }, intervalMs);
  }

  /** Handles streamed audio/text delta events */
  onResponse(handler: (delta: { type: 'audio' | 'text'; data: string }) => void): void {
    this.onResponseDelta = handler;
  }

  /** Registers state change listener (for UI pulse ring) */
  onConnectionStateChange(handler: (state: ConnectionState) => void): void {
    this.onStateChange = handler;
  }

  /** Graceful teardown with session state flush */
  async disconnect(): Promise<void> {
    if (this.frameInterval) {
      clearInterval(this.frameInterval);
      this.frameInterval = null;
    }
    if (this.session.ws) {
      this.session.ws.close(1000, 'ECHO session teardown');
      this.session.ws = null;
    }
    this.setState('disconnected');
  }

  getSessionId(): string {
    return this.session.sessionId;
  }

  getState(): ConnectionState {
    return this.session.state;
  }

  // ── Private ────────────────────────────────────────────

  private sendSetup(): void {
    if (!this.session.ws) return;
    const setup = {
      setup: {
        model: `models/${this.config.modelId}`,
        generationConfig: {
          responseModalities: ['AUDIO', 'TEXT'],
        },
      },
    };
    this.session.ws.send(JSON.stringify(setup));
  }

  private handleMessage(data: WebSocket.Data): void {
    try {
      const msg = JSON.parse(data.toString());
      if (msg.serverContent?.modelTurn?.parts) {
        for (const part of msg.serverContent.modelTurn.parts) {
          if (part.inlineData?.mimeType?.startsWith('audio/')) {
            this.onResponseDelta?.({ type: 'audio', data: part.inlineData.data });
          }
          if (part.text) {
            this.onResponseDelta?.({ type: 'text', data: part.text });
          }
        }
      }
    } catch {
      // Non-JSON or malformed — skip
    }
  }

  private setState(state: ConnectionState): void {
    this.session.state = state;
    this.onStateChange?.(state);
  }

  /** Exponential backoff reconnect with degradation indicator */
  private attemptReconnect(): void {
    if (this.session.reconnectAttempts >= this.config.reconnectMaxRetries) {
      this.setState('degraded');
      return;
    }
    const delay = Math.min(1000 * Math.pow(2, this.session.reconnectAttempts), 30000);
    this.session.reconnectAttempts++;
    setTimeout(() => this.connect(), delay);
  }
}
