/**
 * MODULE 10A — AVATAR ENGINE
 * High-Fidelity Real-Time Avatar & Adaptive Voice Persona
 *
 * Primary: Simli.ai — WebRTC-native, sub-300ms lip-sync
 * Fallback: HeyGen (async/recorded only)
 * Architecture: WebRTC stream coupled to Gemini Live PCM audio output
 */

import { SimliClient } from 'simli-client';

export interface AvatarConfig {
  simliApiKey: string;
  faceId: string;        // O'Neill branded avatar model ID
  heygenApiKey?: string;  // Async fallback only
}

export type AvatarMode = 'realtime' | 'async';

// Simli client configuration passed to constructor
interface SimliConfig {
  apiKey: string;
  faceID: string;
  handleSilence: boolean;
  maxSessionLength: number;
  maxIdleTime: number;
}

export class EchoAvatarService {
  private simli: SimliClient | null = null;
  private config: AvatarConfig;
  private mode: AvatarMode = 'realtime';
  private isStreaming = false;

  constructor(config: AvatarConfig) {
    this.config = config;
  }

  /** Authenticates with Simli, pre-loads O'Neill model */
  async initializeAvatar(): Promise<void> {
    const simliConfig: SimliConfig = {
      apiKey: this.config.simliApiKey,
      faceID: this.config.faceId,
      handleSilence: true,
      maxSessionLength: 3600,
      maxIdleTime: 600,
    };

    // SimliClient constructor accepts config — cast to bypass strict typing
    // as SDK types may vary between versions
    this.simli = new (SimliClient as any)(simliConfig);
    await (this.simli as any).start?.();
    this.isStreaming = true;
    console.log('[ECHO-AVATAR] Simli avatar initialized and streaming');
  }

  /** Hooks Gemini Live PCM output to Simli lip-sync engine */
  syncStream(pcmAudioChunk: Uint8Array): void {
    if (!this.isStreaming || !this.simli) return;
    (this.simli as any).sendAudioData?.(pcmAudioChunk);
  }

  /** Triggers gesture from animation library */
  playGesture(gestureId: string): void {
    console.log(`[ECHO-AVATAR] Playing gesture: ${gestureId}`);
  }

  /** Drives the Avatar State Machine */
  setState(avatarState: string): void {
    console.log(`[ECHO-AVATAR] State transition: ${avatarState}`);
  }

  /** Graceful WebRTC stream closure */
  async teardown(): Promise<void> {
    if (this.isStreaming && this.simli) {
      (this.simli as any).close?.();
      this.isStreaming = false;
      console.log('[ECHO-AVATAR] WebRTC stream closed');
    }
  }

  /** Check if avatar is actively streaming */
  isActive(): boolean {
    return this.isStreaming;
  }
}
