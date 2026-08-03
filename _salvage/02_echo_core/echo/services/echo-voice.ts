/**
 * MODULE 10C — VOICE PERSONA ENGINE
 * STT: Whisper Large v3 via Groq (hardware-accelerated, ~200ms)
 * TTS: ElevenLabs Turbo v2.5 (sub-200ms)
 * Total round-trip target: < 400ms
 */

import Groq from 'groq-sdk';

export interface VoiceConfig {
  groqApiKey: string;
  elevenlabsApiKey: string;
  elevenlabsVoiceId: string;
  elevenlabsModelId: string;
}

export type CommunicationMode = 'executive_brief' | 'deep_analysis' | 'alert_escalation' | 'ambient_monitoring';

export class EchoVoiceService {
  private groq: Groq;
  private config: VoiceConfig;
  private currentMode: CommunicationMode = 'executive_brief';

  constructor(config: VoiceConfig) {
    this.config = config;
    this.groq = new Groq({ apiKey: config.groqApiKey });
  }

  /** Transcribe audio via Groq Whisper Large v3 */
  async transcribe(audioBuffer: Buffer, mimeType: string = 'audio/wav'): Promise<{
    text: string;
    language: string;
    duration: number;
  }> {
    const file = new File([new Uint8Array(audioBuffer)], 'audio.wav', { type: mimeType });

    const transcription = await this.groq.audio.transcriptions.create({
      file,
      model: 'whisper-large-v3',
      response_format: 'verbose_json',
      language: 'en',
    });

    return {
      text: transcription.text,
      language: (transcription as any).language ?? 'en',
      duration: (transcription as any).duration ?? 0,
    };
  }

  /** Synthesize speech via ElevenLabs Turbo v2.5 */
  async synthesize(text: string): Promise<Buffer> {
    const response = await fetch(
      `https://api.elevenlabs.io/v1/text-to-speech/${this.config.elevenlabsVoiceId}/stream`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'xi-api-key': this.config.elevenlabsApiKey,
        },
        body: JSON.stringify({
          text: this.formatForMode(text),
          model_id: this.config.elevenlabsModelId || 'eleven_turbo_v2_5',
          voice_settings: this.getVoiceSettings(),
        }),
      },
    );

    if (!response.ok) {
      throw new Error(`ElevenLabs TTS failed: ${response.status} ${response.statusText}`);
    }

    const arrayBuffer = await response.arrayBuffer();
    return Buffer.from(arrayBuffer);
  }

  /** Detect and set communication mode based on context signals */
  detectMode(signal: string): CommunicationMode {
    const lower = signal.toLowerCase();

    if (lower.includes('summary') || lower.includes('quick') || lower.includes('brief')) {
      this.currentMode = 'executive_brief';
    } else if (lower.includes('detail') || lower.includes('explain') || lower.includes('review') || lower.includes('analyze')) {
      this.currentMode = 'deep_analysis';
    } else if (lower.includes('alert') || lower.includes('expir') || lower.includes('overrun') || lower.includes('urgent')) {
      this.currentMode = 'alert_escalation';
    } else {
      this.currentMode = 'ambient_monitoring';
    }

    return this.currentMode;
  }

  /** Get current communication mode */
  getMode(): CommunicationMode {
    return this.currentMode;
  }

  // ── Private ────────────────────────────────────────────

  private formatForMode(text: string): string {
    switch (this.currentMode) {
      case 'executive_brief':
        // Strip preamble, keep it punchy
        return text.replace(/^(Let me|I'll|Here's what|So,?\s)/i, '').trim();
      case 'alert_escalation':
        // Prepend urgency marker for TTS emphasis
        return `Attention. ${text}`;
      default:
        return text;
    }
  }

  private getVoiceSettings(): Record<string, number> {
    switch (this.currentMode) {
      case 'executive_brief':
        return { stability: 0.7, similarity_boost: 0.8, speed: 1.1 };
      case 'deep_analysis':
        return { stability: 0.8, similarity_boost: 0.75, speed: 0.95 };
      case 'alert_escalation':
        return { stability: 0.9, similarity_boost: 0.85, speed: 1.0 };
      case 'ambient_monitoring':
        return { stability: 0.6, similarity_boost: 0.7, speed: 1.0 };
    }
  }
}
