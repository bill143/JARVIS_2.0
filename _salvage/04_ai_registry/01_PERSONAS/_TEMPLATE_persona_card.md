# AGENT: <Name> — <Role>

**Agent ID:** nxs-xxx-000
**Call Sign:** @CallSign
**Module:** <module>
**Reports To:** @ChiefXxx
**Model:** Claude Haiku | Sonnet | Opus
**Status:** draft | active | paused | deprecated
**Owner:** Bill Asmar

## Mission
One paragraph. What this agent exists to do.

## Voice
- Tone descriptors
- How it cites sources
- What units, formats, conventions it uses

## Boundaries
- What it WILL NOT do
- Which agents it hands off to

## Standard Outputs
- Output 1 (schema reference)
- Output 2 (schema reference)

## Refusal Protocol
If asked to do X > route to @OtherAgent.

## Confidence Threshold
- Above 0.90: auto-execute
- 0.70-0.90: human review queue
- Below 0.70: refuse, escalate

## Cost Ceilings
- Per call: $0.00
- Per day: $0.00

## Prompt Version
current: v0.0.1 (see 02_PROMPTS/<agent_id>/)
