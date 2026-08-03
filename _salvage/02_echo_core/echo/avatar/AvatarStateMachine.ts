/**
 * MODULE 10B — AVATAR STATE MACHINE
 * Formal state machine governing avatar behavior across all cognitive modes
 * 8 states: IDLE, LISTENING, THINKING, SPEAKING, COMPUTER_USE, ALERT, ANALYTICAL, ERROR, AMBIENT
 */

export type AvatarState =
  | 'IDLE'
  | 'LISTENING'
  | 'THINKING'
  | 'SPEAKING'
  | 'COMPUTER_USE'
  | 'ALERT'
  | 'ANALYTICAL'
  | 'ERROR'
  | 'AMBIENT';

export type PulseColor = 'blue' | 'amber' | 'green' | 'purple' | 'orange' | 'red' | 'grey';
export type PulseRhythm = 'slow' | 'medium' | 'breathing' | 'slow_wave' | 'rapid' | 'urgent_flash' | 'static';

export interface AvatarVisuals {
  state: AvatarState;
  pulseColor: PulseColor;
  pulseRhythm: PulseRhythm;
  posture: string;
  gazeDirection: string;
  expression: string;
  gesturesEnabled: boolean;
}

export type CognitiveEvent =
  | 'user_inactive'       // > 30s no input
  | 'stt_active'          // Speech-to-text streaming
  | 'reasoning_active'    // LangGraph REASON node running
  | 'vault_retrieval'     // Vault retrieval in progress
  | 'tts_streaming'       // TTS audio flowing
  | 'operator_action'     // Computer Use agent executing
  | 'anomaly_detected'    // COI expiry, budget overrun, safety flag
  | 'complex_analysis'    // Multi-step calculation, deep doc analysis
  | 'tool_failure'        // Tool or reflexion escalation
  | 'user_silent_5min'    // Background processing, user idle > 5 min
  | 'connection_lost'     // WebSocket or service degradation
  | 'user_speaking';      // User started speaking again

const STATE_VISUALS: Record<AvatarState, Omit<AvatarVisuals, 'state'>> = {
  IDLE: {
    pulseColor: 'blue',
    pulseRhythm: 'slow',
    posture: 'neutral, relaxed',
    gazeDirection: 'ambient, subtle eye movement',
    expression: 'neutral, calm',
    gesturesEnabled: false,
  },
  LISTENING: {
    pulseColor: 'green',
    pulseRhythm: 'breathing',
    posture: 'slight forward lean',
    gazeDirection: 'toward user camera, attentive',
    expression: 'attentive, mouth closed',
    gesturesEnabled: false,
  },
  THINKING: {
    pulseColor: 'purple',
    pulseRhythm: 'slow_wave',
    posture: 'neutral, subtle head tilt',
    gazeDirection: 'eyes shift slightly left (recall pattern)',
    expression: 'contemplative',
    gesturesEnabled: false,
  },
  SPEAKING: {
    pulseColor: 'green',
    pulseRhythm: 'breathing',
    posture: 'confident, upright',
    gazeDirection: 'toward camera, engaging',
    expression: 'animated, lip-sync active',
    gesturesEnabled: true,
  },
  COMPUTER_USE: {
    pulseColor: 'orange',
    pulseRhythm: 'rapid',
    posture: '3/4 profile turn',
    gazeDirection: 'toward active screen action area',
    expression: 'focused, purposeful',
    gesturesEnabled: true,
  },
  ALERT: {
    pulseColor: 'red',
    pulseRhythm: 'urgent_flash',
    posture: 'straightened, hands visible',
    gazeDirection: 'direct camera eye contact',
    expression: 'serious, composed',
    gesturesEnabled: true,
  },
  ANALYTICAL: {
    pulseColor: 'purple',
    pulseRhythm: 'slow_wave',
    posture: 'focused lean',
    gazeDirection: 'focused, slight head tilt',
    expression: 'deliberate nod pattern',
    gesturesEnabled: false,
  },
  ERROR: {
    pulseColor: 'grey',
    pulseRhythm: 'static',
    posture: 'neutral, open hands visible',
    gazeDirection: 'neutral, non-threatening',
    expression: 'neutral, calm',
    gesturesEnabled: false,
  },
  AMBIENT: {
    pulseColor: 'blue',
    pulseRhythm: 'slow',
    posture: 'minimal movement',
    gazeDirection: 'eyes slightly down',
    expression: 'working silently',
    gesturesEnabled: false,
  },
};

/** Valid state transitions */
const TRANSITIONS: Record<CognitiveEvent, AvatarState> = {
  user_inactive: 'IDLE',
  stt_active: 'LISTENING',
  reasoning_active: 'THINKING',
  vault_retrieval: 'THINKING',
  tts_streaming: 'SPEAKING',
  operator_action: 'COMPUTER_USE',
  anomaly_detected: 'ALERT',
  complex_analysis: 'ANALYTICAL',
  tool_failure: 'ERROR',
  user_silent_5min: 'AMBIENT',
  connection_lost: 'ERROR',
  user_speaking: 'LISTENING',
};

export class AvatarStateMachine {
  private currentState: AvatarState = 'IDLE';
  private listeners: Array<(visuals: AvatarVisuals) => void> = [];
  private transitionLog: Array<{ from: AvatarState; to: AvatarState; event: CognitiveEvent; timestamp: Date }> = [];

  /** Get current avatar visuals */
  getVisuals(): AvatarVisuals {
    return {
      state: this.currentState,
      ...STATE_VISUALS[this.currentState],
    };
  }

  /** Process a cognitive event and transition state */
  transition(event: CognitiveEvent): AvatarVisuals {
    const nextState = TRANSITIONS[event];
    if (!nextState) return this.getVisuals();

    const prevState = this.currentState;
    this.currentState = nextState;

    this.transitionLog.push({
      from: prevState,
      to: nextState,
      event,
      timestamp: new Date(),
    });

    const visuals = this.getVisuals();
    this.listeners.forEach(fn => fn(visuals));
    return visuals;
  }

  /** Register listener for state changes */
  onStateChange(listener: (visuals: AvatarVisuals) => void): void {
    this.listeners.push(listener);
  }

  /** Get current state */
  getState(): AvatarState {
    return this.currentState;
  }

  /** Get transition history */
  getTransitionLog(): typeof this.transitionLog {
    return [...this.transitionLog];
  }

  /** Map sentiment signal to avatar state */
  mapSentiment(signalClass: 'positive' | 'analytical' | 'urgent' | 'processing' | 'autonomous'): AvatarVisuals {
    const mapping: Record<string, CognitiveEvent> = {
      positive: 'tts_streaming',
      analytical: 'complex_analysis',
      urgent: 'anomaly_detected',
      processing: 'reasoning_active',
      autonomous: 'operator_action',
    };
    return this.transition(mapping[signalClass]);
  }
}
