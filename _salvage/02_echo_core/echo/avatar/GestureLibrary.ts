/**
 * MODULE 10B — GESTURE LIBRARY
 * Named gesture → animation ID mapping for Simli.ai avatar
 */

export interface Gesture {
  id: string;
  name: string;
  description: string;
  duration: number;       // ms
  applicableStates: string[];
}

export const GESTURE_LIBRARY: Record<string, Gesture> = {
  nod: {
    id: 'gesture_nod',
    name: 'Nod',
    description: 'Affirmative head nod — acknowledging user input',
    duration: 800,
    applicableStates: ['LISTENING', 'SPEAKING', 'ANALYTICAL'],
  },
  head_tilt: {
    id: 'gesture_head_tilt',
    name: 'Head Tilt',
    description: 'Curious/contemplative head tilt — processing complex input',
    duration: 600,
    applicableStates: ['THINKING', 'ANALYTICAL'],
  },
  point_to_screen: {
    id: 'gesture_point_screen',
    name: 'Point to Screen',
    description: 'Points toward the active screen area — during computer use',
    duration: 1000,
    applicableStates: ['COMPUTER_USE', 'SPEAKING'],
  },
  thumbs_up: {
    id: 'gesture_thumbs_up',
    name: 'Thumbs Up',
    description: 'Success confirmation — task completed successfully',
    duration: 1200,
    applicableStates: ['SPEAKING'],
  },
  open_hands: {
    id: 'gesture_open_hands',
    name: 'Open Hands',
    description: 'Open palms — presenting options or explaining',
    duration: 1000,
    applicableStates: ['SPEAKING', 'ALERT', 'ERROR'],
  },
  attention: {
    id: 'gesture_attention',
    name: 'Attention',
    description: 'Raised hand — urgent alert or important information',
    duration: 800,
    applicableStates: ['ALERT'],
  },
  thinking_touch: {
    id: 'gesture_thinking_touch',
    name: 'Thinking Touch',
    description: 'Chin touch — deep analysis in progress',
    duration: 1500,
    applicableStates: ['ANALYTICAL', 'THINKING'],
  },
  wave: {
    id: 'gesture_wave',
    name: 'Wave',
    description: 'Friendly wave — greeting on session start',
    duration: 1000,
    applicableStates: ['IDLE', 'SPEAKING'],
  },
};

export class GestureController {
  private activeGesture: string | null = null;

  /** Get appropriate gesture for current state and context */
  selectGesture(state: string, context: 'greeting' | 'success' | 'alert' | 'explanation' | 'thinking' | 'action'): Gesture | null {
    const mapping: Record<string, string> = {
      greeting: 'wave',
      success: 'thumbs_up',
      alert: 'attention',
      explanation: 'open_hands',
      thinking: 'thinking_touch',
      action: 'point_to_screen',
    };

    const gestureKey = mapping[context];
    const gesture = GESTURE_LIBRARY[gestureKey];

    if (!gesture) return null;
    if (!gesture.applicableStates.includes(state)) return null;

    this.activeGesture = gesture.id;
    return gesture;
  }

  /** Get all gestures valid for a given state */
  getAvailableGestures(state: string): Gesture[] {
    return Object.values(GESTURE_LIBRARY).filter(g => g.applicableStates.includes(state));
  }

  /** Clear active gesture */
  clearGesture(): void {
    this.activeGesture = null;
  }

  getActiveGesture(): string | null {
    return this.activeGesture;
  }
}
