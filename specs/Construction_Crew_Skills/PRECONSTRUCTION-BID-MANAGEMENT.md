# Preconstruction & Bid Management Module — Enterprise Grade

## Module Priority: Build Phase 9 (Weeks 47–52)

## Overview
End-to-end preconstruction workflow: opportunity tracking → bid invitations → scope distribution → sub bid collection → bid leveling → scope gap analysis → GC bid assembly → proposal submission → award tracking. Manages the full subcontractor prequalification lifecycle and bid pipeline. Feeds into Estimating (for pricing) and Contract Management (on award). This is where projects are won — accuracy here drives profitability for the life of the job.

---

## Pages & Routes

### Landing Page — Bid Pipeline
**Route:** `/preconstruction`
**Purpose:** All active opportunities and bid pipeline

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Preconstruction & Bids                    [+ New Opportunity] │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ 12       │ │ 5        │ │ $86M     │ │ 38%      │        │
│  │ Active   │ │ Due This │ │ Pipeline │ │ Win Rate │        │
│  │ Pursuits │ │ Month    │ │ Value    │ │ (12 mo)  │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ View: [Kanban ▼] | Table | Calendar ───────────────────┐ │
│  │                                                           │ │
│  │  TRACKING    │  BIDDING     │  SUBMITTED   │  AWARDED    │ │
│  │  ┌────────┐  │  ┌────────┐  │  ┌────────┐  │  ┌────────┐│ │
│  │  │GSA     │  │  │VA Hines│  │  │USCIS   │  │  │Fed Bldg││ │
│  │  │Chicago │  │  │Abate   │  │  │Cooling │  │  │Lobby   ││ │
│  │  │$4.2M   │  │  │$1.8M   │  │  │$2.1M   │  │  │$3.5M   ││ │
│  │  │Due 3/1 │  │  │Due 2/28│  │  │Sub 2/10│  │  │Won!    ││ │
│  │  └────────┘  │  └────────┘  │  └────────┘  │  └────────┘│ │
│  │  ┌────────┐  │  ┌────────┐  │              │            │ │
│  │  │Corps   │  │  │DOD Ft  │  │              │            │ │
│  │  │Eng Lev │  │  │Riley   │  │              │            │ │
│  │  │$8.5M   │  │  │$12M    │  │              │            │ │
│  │  └────────┘  │  └────────┘  │              │            │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  Quick Links: [Sub Prequal] [Bid Calendar] [ITB Templates]     │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/preconstruction/dashboard`
**Purpose:** Win/loss analytics, pipeline health, team performance

**Widgets:**
1. **Pipeline Funnel** — Opportunities → bids → submittals → awards (value + count at each stage)
2. **Win/Loss Rate** — By client, by project type, by estimator, by contract size
3. **Bid Calendar** — Upcoming due dates in calendar view
4. **Sub Response Rate** — % of invited subs who respond, by trade
5. **Average Markup** — Winning bids vs losing bids OH&P comparison
6. **Go/No-Go Score Distribution** — Risk scores of pursued vs declined opportunities
7. **Revenue Forecast** — Expected value = pipeline × probability at each stage
8. **Time to Bid** — Average days from opportunity identification to submission

---

### Opportunity Detail
**Route:** `/preconstruction/[opportunityId]`
**Purpose:** Full detail for a single bid opportunity

**Tabs:**
1. **Overview** — Project info, owner, delivery method, key dates, go/no-go score
2. **Documents** — Plans, specs, addenda, pre-bid meeting notes, RFIs
3. **Scope Breakdown** — Divisions/trades required, self-perform vs sub
4. **Bid Invitations** — Subs invited by trade, response status, bid amounts
5. **Bid Leveling** — Side-by-side comparison of sub bids per trade
6. **Estimate** — Link to estimate (or create new from here)
7. **Proposal** — Generated bid proposal, cover letter, qualifications
8. **Team** — Assigned PM, estimator, superintendent, marketing contact
9. **Activity** — Full audit trail: invitations sent, bids received, meetings, notes

---

### Go / No-Go Analysis
**Route:** `/preconstruction/[opportunityId]/go-no-go`
**Purpose:** Structured decision framework for bid pursuit

**Scorecard:**
```
┌─────────────────────────────────────────────────────────────┐
│  Go / No-Go Analysis — GSA Chicago Federal Building          │
│                                                              │
│  Category                    Weight  Score   Weighted        │
│  ──────────────────────────────────────────────────────────  │
│  Client Relationship          20%     8/10    1.6            │
│  Project Type Experience      15%     9/10    1.35           │
│  Geographic Location          10%     10/10   1.0            │
│  Competition Level            15%     6/10    0.9            │
│  Resource Availability        15%     7/10    1.05           │
│  Profitability Potential      15%     7/10    1.05           │
│  Schedule Alignment           10%     8/10    0.8            │
│  ──────────────────────────────────────────────────────────  │
│  Total Score:                                7.75 / 10       │
│  Recommendation:              ✅ GO                          │
│                                                              │
│  Decision: [● Go  ○ No-Go  ○ Deferred]                      │
│  Decided By: [____________]  Date: [__/__/____]              │
│  Notes: [_____________________________________________]      │
│                                                              │
│                               [Save Decision]                │
└─────────────────────────────────────────────────────────────┘
```

---

### Bid Invitation Manager
**Route:** `/preconstruction/[opportunityId]/invitations`
**Purpose:** Invite subcontractors by trade and track responses

**Features:**
- Select trades/divisions needed for this project
- Browse qualified subs by trade from company directory
- Bulk invite subs via email (ITB — Invitation to Bid)
- Track: invited → viewed → bidding → submitted → declined
- Attach scope documents per trade
- Set trade-specific bid due dates
- Send reminders to non-responsive subs
- Mark preferred/recommended subs

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Bid Invitations — GSA Chicago                               │
│                                                              │
│  Trade: Div 23 — HVAC                     [+ Invite Subs]   │
│                                                              │
│  Subcontractor       | Status    | Bid Amount | Notes       │
│  F.E. Moran          | Submitted | $1,470,000 | Complete    │
│  Midwest Mechanical   | Submitted | $1,520,000 | Excl ctrl  │
│  ARC Comfort          | Bidding   | —          | Due 2/28   │
│  Chicago HVAC Inc     | Declined  | —          | Too busy   │
│  Temperature Corp     | Invited   | —          | No response│
│                                                              │
│  Trade: Div 26 — Electrical               [+ Invite Subs]   │
│                                                              │
│  Subcontractor       | Status    | Bid Amount | Notes       │
│  Pace Systems         | Submitted | $86,000    | Complete    │
│  Premier Electric     | Submitted | $94,200    | w/ alt     │
│  Kelso-Burnett        | Bidding   | —          | Due 2/28   │
│                                                              │
│                              [Send All Reminders] [Export]   │
└─────────────────────────────────────────────────────────────┘
```

---

### Bid Leveling / Scope Analysis
**Route:** `/preconstruction/[opportunityId]/leveling`
**Purpose:** Compare sub bids side-by-side and identify scope gaps

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│  Bid Leveling — Div 23 HVAC                                      │
│                                                                   │
│  Scope Item               | F.E. Moran | Midwest  | ARC Comfort │
│  ──────────────────────────────────────────────────────────────── │
│  Base Bid                 | $1,470,000 |$1,520,000| $1,380,000  │
│  ───────────────────────────────────────────────────────────────  │
│  RTU Supply & Install     | ✅ Incl    | ✅ Incl  | ✅ Incl     │
│  Ductwork                 | ✅ Incl    | ✅ Incl  | ✅ Incl     │
│  Controls (DDC/BAS)       | ✅ Incl    | ❌ EXCL  | ✅ Incl     │
│  TAB                      | ✅ Incl    | ✅ Incl  | ⚠️ Allowance│
│  Insulation               | ✅ Incl    | ✅ Incl  | ✅ Incl     │
│  Permits & Fees           | ✅ Incl    | ✅ Incl  | ❌ EXCL     │
│  Startup & Commissioning  | ✅ Incl    | ⚠️ Extra | ✅ Incl     │
│  Winter Protection        | ❌ EXCL    | ❌ EXCL  | ❌ EXCL     │
│  ───────────────────────────────────────────────────────────────  │
│  Adjusted (scope-level)   | $1,470,000 |$1,585,000| $1,425,000  │
│  ───────────────────────────────────────────────────────────────  │
│  Bond                     | 1.2%       | 1.5%     | Included    │
│  Payment Terms            | Net 30     | Net 30   | Net 45      │
│  Duration                 | 16 wks     | 18 wks   | 14 wks     │
│  ───────────────────────────────────────────────────────────────  │
│  Recommendation:          | ⭐ BEST     |          |             │
│                                                                   │
│  [Mark Winner] [Export Comparison PDF] [Add Notes]                │
└─────────────────────────────────────────────────────────────────┘
```

---

### Subcontractor Prequalification
**Route:** `/preconstruction/prequalification`
**Purpose:** Vet and qualify subcontractors before inviting to bid

**Features:**
- Prequalification questionnaire (customizable per org)
- Financial statements review (bonding capacity, credit rating)
- Safety record (EMR, OSHA citations, DART rate)
- References and past project performance
- Insurance verification (COI status from COI module)
- Licensing and certifications
- Minority/DBE/MBE/WBE certifications
- Scoring algorithm (weighted criteria → qualification tier)
- Expiration/renewal tracking (annual re-qualification)

**Prequal Record:**
```
┌─────────────────────────────────────────────────────────────┐
│  Subcontractor Prequalification — F.E. Moran                 │
│                                                              │
│  Overall Score: 92/100          Tier: ⭐ Preferred           │
│  Status: Approved               Expires: Dec 2026           │
│                                                              │
│  Category              | Score | Max  | Notes               │
│  Financial Stability   | 18    | 20   | Bonding: $15M       │
│  Safety Record         | 17    | 20   | EMR: 0.82           │
│  Project Experience    | 19    | 20   | 12 federal projects │
│  References            | 18    | 20   | 4/4 positive        │
│  Insurance/Legal       | 20    | 20   | All current          │
│                                                              │
│  Trades: HVAC, Plumbing, Fire Protection                    │
│  Regions: IL, IN, WI, MI                                    │
│  Bonding Capacity: $15M aggregate / $8M single              │
│                                                              │
│  [View Full Application] [Request Update] [Print Report]     │
└─────────────────────────────────────────────────────────────┘
```

---

### ITB (Invitation to Bid) Templates
**Route:** `/preconstruction/itb-templates`
**Purpose:** Email templates for bid invitations

**Features:**
- Create branded ITB email templates
- Auto-populate project info, scope, dates, documents
- Template variables: {{project_name}}, {{bid_due_date}}, {{trade}}, etc.
- Include document links (plans, specs, addenda)
- Preview before sending
- Track open/click/response rates

---

### Settings
**Route:** `/preconstruction/settings`

**Configurable Options:**
- Opportunity stages (tracking, go/no-go, bidding, submitted, awarded, lost)
- Go/no-go criteria and weights
- Default ITB email template
- Bid due date reminders (3 day, 1 day, day-of)
- Sub response deadline defaults
- Prequalification questionnaire template
- Prequal scoring weights and tier thresholds
- Required prequal fields before invitation
- Auto-invite preferred subs by trade
- Pipeline probability % by stage
- Opportunity numbering format

---

## Automations

### Workflow Automations
| Trigger | Action |
|---|---|
| New opportunity created | Assign number, notify preconstruction team |
| Go decision made | Move to bidding stage, create estimate shell |
| No-Go decision made | Move to declined, log reason, notify team |
| ITB sent to subs | Track delivery, set response deadline timer |
| Sub bid received | Notify estimator, update invitation status |
| Bid due date − 3 days | Remind non-responsive subs |
| Bid due date − 1 day | Alert estimator of missing bids |
| All bids received for trade | Notify estimator, flag for leveling |
| Bid submitted to owner | Move to submitted stage, log date |
| Project awarded | Create project, create contracts from winning subs, notify team |
| Project lost | Log loss reason, update win/loss stats |
| Prequal expiring (30 days) | Alert procurement + sub to renew |
| Addendum issued | Notify all invited subs, require acknowledgment |

### Auto-Population
- ITB emails auto-populate with project info, scope, dates
- Bid leveling sheet auto-generates from received bids
- Go/no-go score auto-calculates from weighted criteria
- Pipeline value auto-calculates: opportunity value × stage probability
- Win rate auto-updates on award/loss

### PDF Generation
- **ITB Package** — Invitation letter + scope + bid form + instructions
- **Bid Leveling Report** — Side-by-side comparison with scope analysis
- **Go/No-Go Report** — Scorecard with rationale
- **Proposal Package** — (links to Estimating module proposal generator)
- **Prequal Report** — Subcontractor qualification summary

---

## API Endpoints

```
GET    /api/preconstruction/opportunities              — List all opportunities
POST   /api/preconstruction/opportunities              — Create opportunity
GET    /api/preconstruction/opportunities/[id]          — Get opportunity detail
PUT    /api/preconstruction/opportunities/[id]          — Update opportunity
DELETE /api/preconstruction/opportunities/[id]          — Delete (tracking stage only)
PUT    /api/preconstruction/opportunities/[id]/stage    — Update pipeline stage
POST   /api/preconstruction/opportunities/[id]/award    — Mark as awarded → create project
POST   /api/preconstruction/opportunities/[id]/loss     — Mark as lost with reason

GET    /api/preconstruction/opportunities/[id]/go-no-go         — Get go/no-go scorecard
PUT    /api/preconstruction/opportunities/[id]/go-no-go         — Update scores
POST   /api/preconstruction/opportunities/[id]/go-no-go/decide  — Record decision

GET    /api/preconstruction/opportunities/[id]/invitations           — List invitations
POST   /api/preconstruction/opportunities/[id]/invitations           — Send ITBs
PUT    /api/preconstruction/opportunities/[id]/invitations/[invId]   — Update status
POST   /api/preconstruction/opportunities/[id]/invitations/remind    — Send reminders

GET    /api/preconstruction/opportunities/[id]/bids                  — List received bids
POST   /api/preconstruction/opportunities/[id]/bids                  — Record sub bid
PUT    /api/preconstruction/opportunities/[id]/bids/[bidId]          — Update bid
GET    /api/preconstruction/opportunities/[id]/leveling              — Get leveling sheet
PUT    /api/preconstruction/opportunities/[id]/leveling/[trade]      — Update scope items
POST   /api/preconstruction/opportunities/[id]/leveling/winner       — Select winning bid

GET    /api/preconstruction/prequalification                         — List all prequals
POST   /api/preconstruction/prequalification                         — Start prequal
GET    /api/preconstruction/prequalification/[id]                    — Get prequal detail
PUT    /api/preconstruction/prequalification/[id]                    — Update prequal
POST   /api/preconstruction/prequalification/[id]/approve            — Approve sub
POST   /api/preconstruction/prequalification/[id]/reject             — Reject sub

GET    /api/preconstruction/itb-templates                            — List ITB templates
POST   /api/preconstruction/itb-templates                            — Create template
PUT    /api/preconstruction/itb-templates/[id]                       — Update template
```

---

## Validation Rules (Zod Schemas)

```typescript
// Opportunity
const createOpportunitySchema = z.object({
  name: z.string().min(1).max(300),
  owner_company: z.string().min(1).max(200),
  delivery_method: z.enum(['design_bid_build', 'design_build', 'cm_at_risk', 'cm_agency', 'ipd', 'joc']),
  contract_type: z.enum(['lump_sum', 'gmp', 'cost_plus', 'unit_price', 'time_materials']),
  estimated_value: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  bid_due_date: z.string().datetime().optional(),
  pre_bid_date: z.string().datetime().optional(),
  project_type: z.enum(['federal', 'state', 'municipal', 'commercial', 'healthcare', 'education', 'industrial', 'residential']),
  location_city: z.string().optional(),
  location_state: z.string().optional(),
  stage: z.enum(['tracking', 'go_no_go', 'bidding', 'submitted', 'awarded', 'lost', 'declined']).default('tracking'),
  assigned_estimator: z.string().uuid().optional(),
  assigned_pm: z.string().uuid().optional(),
  notes: z.string().max(5000).optional(),
});

// Go/No-Go Score
const goNoGoScoreSchema = z.object({
  criteria: z.array(z.object({
    name: z.string(),
    weight: z.number().min(0).max(100), // percentage
    score: z.number().min(0).max(10),
  })),
  decision: z.enum(['go', 'no_go', 'deferred']),
  decided_by: z.string().uuid(),
  notes: z.string().max(2000).optional(),
}).refine(data => {
  const totalWeight = data.criteria.reduce((sum, c) => sum + c.weight, 0);
  return totalWeight === 100;
}, "Criteria weights must total 100%");

// Sub Bid
const subBidSchema = z.object({
  company_id: z.string().uuid(),
  trade_division: z.string().min(1),
  base_bid: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  alternates: z.array(z.object({
    description: z.string(),
    amount: z.number().refine(v => Number(v.toFixed(2)) === v),
    type: z.enum(['add', 'deduct']),
  })).optional(),
  inclusions: z.array(z.string()).optional(),
  exclusions: z.array(z.string()).optional(),
  duration_weeks: z.number().positive().optional(),
  bond_percentage: z.number().min(0).max(100).optional(),
  payment_terms: z.string().optional(),
  valid_until: z.string().date().optional(),
  notes: z.string().max(2000).optional(),
});

// Bid Leveling Scope Item
const scopeItemSchema = z.object({
  description: z.string().min(1).max(500),
  trade_division: z.string().min(1),
  is_required: z.boolean().default(true),
  bids: z.array(z.object({
    company_id: z.string().uuid(),
    status: z.enum(['included', 'excluded', 'allowance', 'extra', 'unknown']),
    amount: z.number().optional(),
    notes: z.string().max(500).optional(),
  })),
});
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation, company directory)
- Estimating module for estimate creation from opportunity
- Contract Management module for award → contract flow
- COI Tracking (from Pay Apps) for prequal insurance verification
- Email service (Resend) for ITB delivery and tracking
- PDF generation (Puppeteer) for ITB packages and reports
