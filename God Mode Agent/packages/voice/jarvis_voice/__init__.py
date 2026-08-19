"""Voice: STT (Deepgram/local/mock), TTS (ElevenLabs/pyttsx3/mock), realtime loop."""

from jarvis_voice.loop import VoiceSession
from jarvis_voice.stt import transcribe_audio
from jarvis_voice.tts import synthesize_speech

__all__ = ["VoiceSession", "transcribe_audio", "synthesize_speech"]
