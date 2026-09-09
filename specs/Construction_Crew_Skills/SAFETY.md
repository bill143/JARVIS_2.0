# Safety Module — Enterprise Grade

## Module Priority: Build Phase 12 (Weeks 64–68)

## Overview
Comprehensive safety management: incident tracking, safety observations, toolbox talks, site-specific safety plans, OSHA recordkeeping (300/300A/301 logs), EMR tracking, drug testing, certifications management, and safety analytics. Supports federal compliance requirements including EM 385-1-1 (USACE), OSHA 29 CFR 1926, and owner-specific safety programs. Every incident, near-miss, and safety observation creates an auditable record. This module protects people and protects the company from liability.

---

## Pages & Routes

### Landing Page
**Route:** `/safety` (org-wide) | `/projects/[projectId]/safety`
**Purpose:** Safety overview with key metrics and incident log

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Safety Management                        [+ Report Incident] │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ 847      │ │ 0        │ │ 3        │ │ 0.82     │        │
│  │ Days     │ │ Lost Time│ │ Near     │ │ EMR      │        │
│  │ No LTI   │ │ Incidents│ │ Misses   │ │ Current  │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ Tabs: Dashboard | Incidents | Observations | Certs ─────┐ │
│  │                                                           │ │
│  │  Recent Incidents & Observations                          │ │
│  │  Date    | Type         | Severity | Project     | Status │ │
│  │  2/10/26 | Near Miss    | Medium   | GSA Chicago | Open   │ │
│  │  2/08/26 | Observation  | Low      | VA Hines    | Closed │ │
│  │  2/01/26 | First Aid    | Low      | GSA Chicago | Closed │ │
│  │  1/28/26 | Near Miss    | High     | USCIS Cool  | Open   │ │
│  │                                                           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  Quick Links: [OSHA Logs] [Toolbox Talks] [Safety Plan] [Certs]│
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/safety/dashboard` | `/projects/[projectId]/safety/dashboard`
**Purpose:** Safety analytics and trend analysis

**Widgets:**
1. **TRIR (Total Recordable Incident Rate)** — Gauge with industry benchmark comparison
2. **DART Rate** — Days Away, Restricted, or Transferred rate
3. **Incident Trend** — Monthly incidents by type over 12 months
4. **Severity Distribution** — Pie chart: near miss, first aid, recordable, lost time
5. **Incidents by Trade** — Bar chart showing which trades have most incidents
6. **Safety Observations** — Monthly safe vs unsafe observations
7. **Training Compliance** — % of workers with current safety certs
8. **Leading Indicators** — Toolbox talks completed, observations submitted, inspections done
9. **EMR History** — 3-year EMR trend for the organization
10. **Drug Test Status** — Pre-employment, random, post-incident completion rates

---

### Incident Report
**Route:** `/safety/incidents/new` | `/safety/incidents/[id]`
**Purpose:** Record and investigate safety incidents

**Form:**
```
┌─────────────────────────────────────────────────────────────┐
│  Incident Report                                              │
│                                                               │
│  Project:          [GSA Chicago                     ▼]       │
│  Date/Time:        [02/10/2026]  [2:30 PM]                  │
│  Location:         [Building: Main | Floor: 3 | Area: Mech] │
│                                                               │
│  Incident Type:    [● Near Miss  ○ First Aid  ○ Recordable  │
│                     ○ Lost Time  ○ Property Damage  ○ Env]   │
│  Severity:         [○ Low  ● Medium  ○ High  ○ Critical]    │
│                                                               │
│  ┌─ What Happened ─────────────────────────────────────────┐ │
│  │  Description: [Pipe fitting fell from scaffold at 3rd   │ │
│  │  floor. No one below. Hard hat area — barricade in      │ │
│  │  place but fitting bounced outside zone.               ] │ │
│  └──────────────────────────────────────────────────────────┘│
│                                                               │
│  Injured Person:   [○ None  ● Employee  ○ Sub  ○ Visitor]   │
│  Name:             [________________________]                │
│  Company:          [F.E. Moran              ▼]              │
│  Trade:            [Pipefitter                ]               │
│  Body Part:        [N/A — near miss           ]               │
│  Treatment:        [○ None  ○ First Aid  ○ Clinic  ○ ER]    │
│                                                               │
│  Witnesses:        [+ Add Witness]                           │
│  Photos:           [📷 Upload Photos]                        │
│                                                               │
│  ┌─ Root Cause Analysis ───────────────────────────────────┐ │
│  │  Immediate Cause:  [Improper securing of material      ]│ │
│  │  Root Cause:       [No toe boards on scaffold          ]│ │
│  │  Contributing:     [Rushing to meet schedule           ]│ │
│  └──────────────────────────────────────────────────────────┘│
│                                                               │
│  ┌─ Corrective Actions ────────────────────────────────────┐ │
│  │  Action                    | Assigned To | Due Date     │ │
│  │  Install toe boards        | F.E. Moran  | 2/11/26     │ │
│  │  Toolbox talk on overhead  | Site Safety  | 2/12/26     │ │
│  │  Expand barricade zone     | Superintendent| 2/10/26    │ │
│  │  [+ Add Corrective Action]                              │ │
│  └──────────────────────────────────────────────────────────┘│
│                                                               │
│                              [Save Draft] [Submit Report]    │
└─────────────────────────────────────────────────────────────┘
```

---

### Safety Observations
**Route:** `/projects/[projectId]/safety/observations`
**Purpose:** Proactive safety observation program (leading indicator)

**Features:**
- Quick-entry observation form (mobile-optimized)
- Safe vs unsafe behavior classification
- Photo required for unsafe observations
- Category tags (PPE, housekeeping, fall protection, scaffolding, electrical, excavation, etc.)
- Assign corrective actions for unsafe observations
- Track observation rate per worker-hour
- Gamification: observation leaderboard by project/team

**Quick Entry (Mobile):**
```
┌─────────────────────────────────┐
│  Safety Observation              │
│                                  │
│  [● Safe  ○ Unsafe]             │
│                                  │
│  Category: [Fall Protection ▼]   │
│  Trade:    [Iron Worker     ▼]   │
│  Location: [Floor 4, East   ]    │
│                                  │
│  [📷 Take Photo]                │
│                                  │
│  Notes: [_____________________]  │
│                                  │
│  [Submit Observation]            │
└─────────────────────────────────┘
```

---

### OSHA Recordkeeping
**Route:** `/safety/osha-logs`
**Purpose:** OSHA 300, 300A, and 301 log maintenance

**Features:**
- Auto-populate OSHA 300 log from recordable incidents
- Track: injury/illness type, days away, days restricted, job transfer
- Auto-calculate TRIR, DART, severity rate
- Generate OSHA 300A annual summary
- Generate OSHA 301 individual incident reports
- Year-end posting reminder (February 1 – April 30)
- Multi-year log retention (5+ years)
- Export for submission

**OSHA 300 Log:**
```
┌─────────────────────────────────────────────────────────────────┐
│  OSHA 300 Log — Calendar Year 2026                               │
│  Establishment: O'Neill Contractors                              │
│                                                                   │
│  Case | Date  | Employee | Job     | Description        | Type  │
│  1    | 3/15  | J. Smith | Laborer | Laceration - hand  | Other │
│  2    | 5/22  | M. Davis | Elec    | Back strain        | DART  │
│  ...                                                              │
│                                                                   │
│  Summary:                                                        │
│  Total Cases: 4  |  Deaths: 0  |  DART: 1  |  Other: 3          │
│  Total Hours Worked: 485,000  |  TRIR: 1.65  |  DART Rate: 0.41 │
│                                                                   │
│  [Generate 300A Summary] [Generate 301 Forms] [Export]           │
└─────────────────────────────────────────────────────────────────┘
```

---

### Toolbox Talks
**Route:** `/projects/[projectId]/safety/toolbox-talks`
**Purpose:** Track daily/weekly safety meetings

**Features:**
- Toolbox talk template library (200+ topics)
- Custom talk creation
- Record attendance (digital sign-in, or photo of sign-in sheet)
- Link to project-specific hazards
- Schedule recurring talks
- Track completion rate per project/trade
- Auto-suggest topics based on upcoming work activities
- Attach photos and handouts

---

### Certifications & Training
**Route:** `/safety/certifications`
**Purpose:** Track worker safety certifications and training

**Features:**
- OSHA 10 / OSHA 30 tracking
- CPR/First Aid certification
- Confined space entry
- Fall protection
- Scaffold competent person
- Crane operator (NCCCO)
- Rigging and signaling
- Hot work permit
- Silica competent person
- Drug testing records (pre-employment, random, post-incident)
- Expiration tracking with auto-alerts
- Site access gating (block site access if required cert expired)

**Certification Matrix:**
```
┌─────────────────────────────────────────────────────────────┐
│  Certification Matrix — GSA Chicago                          │
│                                                              │
│  Worker        | OSHA 10| OSHA 30| CPR   | Fall  | Drug    │
│  J. Smith      | ✅ 2027| —      | ✅ 2027| ✅ 2026| ✅ 1/26│
│  M. Davis      | ✅ 2026| ✅ 2028| ⚠️ 3/26| ✅ 2027| ✅ 1/26│
│  R. Johnson    | ✅ 2027| —      | ✅ 2027| ❌ EXP | ✅ 12/25│
│  T. Williams   | ❌ NONE| —      | ✅ 2026| ✅ 2026| ⚠️ Due │
│                                                              │
│  Overall Compliance: 87%                                     │
│  ⚠️ 3 workers with expiring certs in next 30 days           │
│  ❌ 2 workers with missing/expired required certs            │
│                                                              │
│  [Send Renewal Reminders] [Export Matrix] [Print]            │
└─────────────────────────────────────────────────────────────┘
```

---

### Site-Specific Safety Plan (SSSP)
**Route:** `/projects/[projectId]/safety/sssp`
**Purpose:** Project safety plan document management

**Features:**
- SSSP template (customizable per organization)
- Activity Hazard Analysis (AHA) library
- Risk assessment matrix
- Emergency action plan
- Site safety map (evacuation routes, assembly points, first aid, AED locations)
- Competent person assignments
- PPE requirements by area
- Crane and rigging plans
- Version control and approval workflow

---

### Settings
**Route:** `/safety/settings`

**Configurable Options:**
- Incident classification criteria
- OSHA recordability decision tree
- Required certifications by project type
- Observation categories and severity levels
- Corrective action due date defaults
- Toolbox talk frequency requirements
- Drug testing program rules (random pool %, post-incident triggers)
- EMR calculation parameters
- Safety alert distribution lists
- Report templates
- Auto-suggest toolbox talk topics based on schedule
- Site access requirements by certification

---

## Automations

### Workflow Automations
| Trigger | Action |
|---|---|
| Incident reported | Notify safety director + PM + superintendent immediately |
| Recordable incident | Auto-add to OSHA 300 log, notify executive team |
| Lost-time incident | Escalate to VP, start return-to-work tracking |
| Corrective action assigned | Notify assignee, set due date |
| Corrective action overdue | Escalate to PM + safety director |
| Unsafe observation submitted | Create corrective action, notify foreman |
| Certification expiring (30 days) | Alert worker + PM + safety |
| Certification expired | Block site access, alert PM |
| Drug test due (random) | Notify HR + worker |
| Toolbox talk not completed (weekly) | Alert superintendent + safety |
| OSHA 300A posting period start (Feb 1) | Remind safety director |
| New worker added to project | Check certification compliance |
| High-risk activity scheduled | Auto-suggest relevant AHA and toolbox talk |

### Auto-Calculations
- TRIR = (recordable incidents × 200,000) ÷ total hours worked
- DART Rate = (DART cases × 200,000) ÷ total hours worked
- Severity Rate = (days away × 200,000) ÷ total hours worked
- EMR = based on 3-year loss history (links to insurance module)
- Observation rate = observations per 1,000 worker-hours
- Training compliance % = workers with current certs ÷ total workers

### PDF Generation
- **Incident Report** — Full incident report with photos and corrective actions
- **OSHA 300 Log** — Formatted OSHA 300 log
- **OSHA 300A Summary** — Annual summary for posting
- **OSHA 301 Form** — Individual injury/illness report
- **Toolbox Talk Record** — Talk content with attendance sign-in
- **Certification Matrix** — Worker certification status grid
- **Safety Dashboard Report** — Monthly safety metrics summary

---

## API Endpoints

```
GET    /api/safety/incidents                           — List incidents (org-wide)
GET    /api/projects/[id]/safety/incidents             — List incidents (project)
POST   /api/projects/[id]/safety/incidents             — Create incident
GET    /api/safety/incidents/[incId]                   — Get incident detail
PUT    /api/safety/incidents/[incId]                   — Update incident
POST   /api/safety/incidents/[incId]/close             — Close incident

GET    /api/projects/[id]/safety/observations          — List observations
POST   /api/projects/[id]/safety/observations          — Create observation
GET    /api/projects/[id]/safety/observations/[obsId]  — Get observation

GET    /api/safety/osha-logs/[year]                    — Get OSHA 300 log
GET    /api/safety/osha-logs/[year]/300a               — Generate 300A
GET    /api/safety/osha-logs/[year]/301/[caseId]       — Generate 301

GET    /api/projects/[id]/safety/toolbox-talks         — List toolbox talks
POST   /api/projects/[id]/safety/toolbox-talks         — Record toolbox talk
GET    /api/safety/toolbox-talk-templates              — List templates

GET    /api/safety/certifications                      — List all certifications
GET    /api/safety/certifications/worker/[workerId]    — Worker certs
POST   /api/safety/certifications                      — Add certification
PUT    /api/safety/certifications/[certId]             — Update certification
GET    /api/projects/[id]/safety/cert-matrix           — Project cert matrix
GET    /api/safety/certifications/expiring             — Expiring certs report

GET    /api/projects/[id]/safety/sssp                  — Get SSSP
PUT    /api/projects/[id]/safety/sssp                  — Update SSSP
GET    /api/projects/[id]/safety/aha                   — List AHAs
POST   /api/projects/[id]/safety/aha                   — Create AHA

GET    /api/safety/metrics                             — Safety metrics (TRIR, DART, etc.)
GET    /api/projects/[id]/safety/metrics               — Project safety metrics
```

---

## Validation Rules (Zod Schemas)

```typescript
// Incident Report
const incidentSchema = z.object({
  project_id: z.string().uuid(),
  incident_date: z.string().datetime(),
  location_building: z.string().max(100).optional(),
  location_floor: z.string().max(20).optional(),
  location_area: z.string().max(100).optional(),
  incident_type: z.enum(['near_miss', 'first_aid', 'recordable', 'lost_time', 'property_damage', 'environmental']),
  severity: z.enum(['low', 'medium', 'high', 'critical']),
  description: z.string().min(10).max(5000),
  injured_party_type: z.enum(['none', 'employee', 'subcontractor', 'visitor']).default('none'),
  injured_person_name: z.string().max(200).optional(),
  injured_company_id: z.string().uuid().optional(),
  injured_trade: z.string().max(100).optional(),
  body_part: z.string().max(100).optional(),
  treatment: z.enum(['none', 'first_aid', 'clinic', 'emergency_room', 'hospitalization']).default('none'),
  days_away: z.number().int().min(0).default(0),
  days_restricted: z.number().int().min(0).default(0),
  root_cause_immediate: z.string().max(2000).optional(),
  root_cause_underlying: z.string().max(2000).optional(),
  contributing_factors: z.string().max(2000).optional(),
  witnesses: z.array(z.object({
    name: z.string(),
    company: z.string().optional(),
    phone: z.string().optional(),
  })).optional(),
  corrective_actions: z.array(z.object({
    description: z.string().min(1).max(1000),
    assigned_to: z.string().uuid(),
    due_date: z.string().date(),
  })).optional(),
  photos: z.array(z.string().url()).optional(),
});

// Safety Observation
const observationSchema = z.object({
  project_id: z.string().uuid(),
  type: z.enum(['safe', 'unsafe']),
  category: z.enum([
    'ppe', 'housekeeping', 'fall_protection', 'scaffolding', 'electrical',
    'excavation', 'confined_space', 'crane_rigging', 'fire_prevention',
    'hazcom', 'tool_equipment', 'traffic_control', 'other'
  ]),
  trade: z.string().max(100).optional(),
  location: z.string().max(200).optional(),
  description: z.string().max(2000).optional(),
  photo_url: z.string().url().optional(),
});

// Certification
const certificationSchema = z.object({
  worker_id: z.string().uuid(),
  cert_type: z.enum([
    'osha_10', 'osha_30', 'cpr_first_aid', 'confined_space',
    'fall_protection', 'scaffold_competent', 'crane_operator_nccco',
    'rigging_signaling', 'hot_work', 'silica_competent',
    'forklift', 'aerial_lift', 'hazwoper', 'other'
  ]),
  cert_number: z.string().max(100).optional(),
  issue_date: z.string().date(),
  expiration_date: z.string().date().optional(),
  issuing_organization: z.string().max(200).optional(),
  document_url: z.string().url().optional(),
});
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation)
- Company Directory for sub/vendor worker tracking
- Field Execution (daily logs) for worker hour tracking (feeds TRIR calculations)
- COI module for EMR data
- Labor/Timecards for worker-hour data
- Email service (Resend) for alerts and notifications
- PDF generation for reports and OSHA forms
- File storage for photos and documents
