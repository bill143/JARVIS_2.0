# Labor & Timecards Module — Enterprise Grade

## Module Priority: Build Phase 13 (Weeks 69–74)

## Overview
Construction labor management: crew time tracking, cost code allocation, foreman daily time entry, overtime/prevailing wage calculations, certified payroll generation (WH-347), and payroll system export. Tracks labor hours at the worker × cost code × day level for accurate job costing. Supports both self-perform labor and subcontractor workforce tracking. Feeds into Budget (actual labor costs), Safety (worker-hours for TRIR), and Accounting (payroll liability). Federal project compliance requires Davis-Bacon prevailing wage tracking and certified payroll submissions.

---

## Pages & Routes

### Landing Page
**Route:** `/projects/[projectId]/labor`
**Purpose:** Weekly timecard overview and labor metrics

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Labor & Timecards              [+ New Timecard] [Week ◀ ▶]  │
│                                                                │
│  Week of: February 10–14, 2026                                │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ 42       │ │ 1,680    │ │ 128      │ │ $84,200  │        │
│  │ Workers  │ │ Regular  │ │ Overtime │ │ Labor    │        │
│  │ On Site  │ │ Hours    │ │ Hours    │ │ Cost     │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ Tabs: Timecards | Crew View | Cost Code | Cert Payroll ─┐│
│  │                                                            ││
│  │  Foreman Timecards (this week)                            ││
│  │  Foreman      | Crew | Mon | Tue | Wed | Thu | Fri | Total││
│  │  J. Martinez  | 8    | 72  | 72  | 80  | 72  | 64  | 360 ││
│  │  R. Thompson  | 6    | 48  | 48  | 54  | 48  | 48  | 246 ││
│  │  D. Wilson    | 5    | 40  | 40  | 45  | 40  | 40  | 205 ││
│  │                                                            ││
│  │  Status  | Submitted | Approved  | Exported                ││
│  │  ✅ Appr | 2/14      | 2/15      | 2/16                   ││
│  │  ⏳ Pend | 2/14      | —         | —                      ││
│  │  ✅ Appr | 2/14      | 2/15      | 2/16                   ││
│  └────────────────────────────────────────────────────────────┘│
│                                                                │
│  Quick Links: [Crew Roster] [Wage Rates] [Certified Payroll]  │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/projects/[projectId]/labor/dashboard`
**Purpose:** Labor analytics and productivity tracking

**Widgets:**
1. **Daily Headcount** — Line chart: workers on site by day (last 30 days)
2. **Hours by Trade** — Stacked bar chart: hours by trade per week
3. **Overtime Ratio** — OT hours ÷ total hours (target vs actual)
4. **Labor Cost by Cost Code** — Budget vs actual labor cost by division
5. **Productivity Rate** — Units installed per labor hour (by trade)
6. **Crew Utilization** — % of available hours actually worked
7. **Weekly Trend** — Total hours per week over project duration
8. **Cost per Hour** — Average burdened rate by trade (actual vs budgeted)

---

### Foreman Timecard Entry
**Route:** `/projects/[projectId]/labor/timecards/new`
**Purpose:** Foreman enters time for their crew (mobile-optimized)

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Daily Timecard — February 12, 2026 (Wednesday)              │
│  Foreman: J. Martinez  |  Project: GSA Chicago               │
│                                                               │
│  Worker         | Trade     | ST  | OT  | DT  | Cost Code   │
│  A. Rodriguez   | Pipefitter| 8   | 2   | —   | 23-20       │
│  B. Chen        | Pipefitter| 8   | 2   | —   | 23-20       │
│  C. Williams    | Laborer   | 8   | 1   | —   | 23-20       │
│  D. Jackson     | Laborer   | 8   | 1   | —   | 23-30       │
│  E. Smith       | Sheet Met | 8   | —   | —   | 23-30       │
│  F. Garcia      | Sheet Met | 8   | —   | —   | 23-30       │
│  G. Lee         | Apprentice| 8   | —   | —   | 23-20       │
│  H. Brown       | Foreman   | 8   | 2   | —   | 23-00       │
│  [+ Add Worker]                                              │
│                                                               │
│  ┌─ Time Split (if worker works multiple codes) ──────────┐  │
│  │  A. Rodriguez: 23-20 (6 hrs) + 23-30 (2 hrs) + OT 2hr│  │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  Work Performed: [Installed 4" copper risers floors 3-4,    │
│                   started duct connections on 4th floor     ]│
│                                                               │
│  Notes: [Weather delay — 30 min AM. Full crew after 7:30   ]│
│                                                               │
│                              [Save Draft] [Submit Timecard]  │
└─────────────────────────────────────────────────────────────┘
```

---

### Timecard Detail / Approval
**Route:** `/projects/[projectId]/labor/timecards/[timecardId]`
**Purpose:** Review and approve submitted timecards

**Features:**
- View all workers, hours, cost codes for the day/week
- PM reviews and approves/rejects
- Flag discrepancies (hours don't match daily log, cost code wrong)
- Revision workflow (return to foreman with notes)
- Batch approval for multiple timecards
- Approval locks the timecard from further edits

---

### Crew Roster
**Route:** `/projects/[projectId]/labor/crew`
**Purpose:** Manage workers assigned to the project

**Features:**
- Worker profiles: name, trade, classification, hourly rate, employer
- Active/inactive status on project
- Certification compliance (links to Safety module)
- Onboarding checklist (safety orientation, drug test, OSHA card)
- Emergency contact info
- Photo for ID badge
- Site access permissions
- Assignment history (which projects, which dates)

**Crew List:**
```
┌─────────────────────────────────────────────────────────────┐
│  Project Crew Roster — GSA Chicago                           │
│  Active Workers: 42  |  Trades: 8  |  Compliance: 94%       │
│                                                              │
│  Worker        | Trade      | Class    | Rate   | Status    │
│  A. Rodriguez  | Pipefitter | Journey  | $72.50 | Active    │
│  B. Chen       | Pipefitter | Journey  | $72.50 | Active    │
│  C. Williams   | Laborer    | General  | $48.00 | Active    │
│  G. Lee        | Pipefitter | 4th Year | $58.00 | Active    │
│  ...                                                         │
│                                                              │
│  Employer | Certs      | Onboarding | Hours (YTD)           │
│  Moran    | ✅ All     | ✅ Complete | 320                   │
│  Moran    | ✅ All     | ✅ Complete | 312                   │
│  O'Neill  | ⚠️ CPR exp | ✅ Complete | 280                   │
│  Moran    | ✅ All     | ✅ Complete | 240                   │
│                                                              │
│  [+ Add Worker] [Import Roster] [Export] [Print Badges]      │
└─────────────────────────────────────────────────────────────┘
```

---

### Wage Rate Management
**Route:** `/labor/wage-rates`
**Purpose:** Manage prevailing wage rates and labor classifications

**Features:**
- Prevailing wage rate tables by trade and locality
- Import Davis-Bacon wage determinations (from SAM.gov)
- Base rate, fringe benefits, total rate breakdown
- Overtime calculation rules (1.5x ST, 2x after X hours, etc.)
- Shift differentials
- Rate effective dates (track changes over project duration)
- Multi-classification support (worker can work multiple trades at different rates)

**Wage Table:**
```
┌─────────────────────────────────────────────────────────────┐
│  Prevailing Wage Rates — Cook County, IL                     │
│  Wage Determination: IL20260001  |  Effective: 1/1/2026     │
│                                                              │
│  Trade           | Class    | Base    | Fringe  | Total     │
│  Pipefitter      | Journey  | $52.50  | $20.00  | $72.50    │
│  Pipefitter      | Appr 4yr | $42.00  | $16.00  | $58.00    │
│  Sheet Metal     | Journey  | $48.75  | $18.50  | $67.25    │
│  Electrician     | Journey  | $50.00  | $19.25  | $69.25    │
│  Laborer         | General  | $32.00  | $16.00  | $48.00    │
│  Operator        | Group 1  | $55.00  | $21.50  | $76.50    │
│  Carpenter       | Journey  | $46.50  | $17.80  | $64.30    │
│  Iron Worker     | Structural| $51.00 | $19.75  | $70.75    │
│                                                              │
│  OT Rule: 1.5x base after 8 hrs/day, 2x on Sunday/Holiday  │
│  [Import from SAM.gov] [Edit Rates] [Export]                 │
└─────────────────────────────────────────────────────────────┘
```

---

### Certified Payroll (WH-347)
**Route:** `/projects/[projectId]/labor/certified-payroll`
**Purpose:** Generate WH-347 certified payroll reports for federal projects

**Features:**
- Auto-generate WH-347 from approved timecards
- Worker info auto-populated from crew roster
- Hours and wage rates auto-calculated from timecards + wage table
- Deductions tracking
- Digital signature for certification statement
- Weekly report (one per week per project)
- Batch generation (all weeks for a month)
- Submission tracking (submitted to owner/agency)
- Compliance check (verify all workers paid at or above prevailing wage)

**WH-347 Preview:**
```
┌─────────────────────────────────────────────────────────────────┐
│  WH-347 Certified Payroll — Week Ending 2/14/2026               │
│  Project: GSA Chicago  |  Contractor: O'Neill Contractors       │
│  Contract: GS-05P-LIL-123456                                    │
│                                                                  │
│  # | Name          | Trade      | Day Hours (M-Su)  | Total    │
│  1 | Rodriguez, A  | Pipefitter | 8 8 10 8 8 0 0    | 42      │
│  2 | Chen, B       | Pipefitter | 8 8 10 8 8 0 0    | 42      │
│  3 | Williams, C   | Laborer    | 8 8 9 8 8 0 0     | 41      │
│  ...                                                             │
│                                                                  │
│  # | Class   | Rate   | Gross     | Deductions | Net Pay       │
│  1 | Journey | $72.50 | $3,117.50 | $467.63    | $2,649.87     │
│  2 | Journey | $72.50 | $3,117.50 | $467.63    | $2,649.87     │
│  3 | General | $48.00 | $1,992.00 | $298.80    | $1,693.20     │
│                                                                  │
│  Certification: I certify that the above payroll is correct...  │
│  Signed: [Digital Signature]  Date: [2/16/2026]                 │
│                                                                  │
│  [Download PDF] [Submit to Owner] [Mark as Filed]               │
└─────────────────────────────────────────────────────────────────┘
```

---

### Payroll Export
**Route:** `/labor/payroll-export`
**Purpose:** Export timecard data to external payroll systems

**Supported Formats:**
- **ADP** — CSV format matching ADP import template
- **Paychex** — CSV format matching Paychex import
- **QuickBooks Payroll** — IIF or CSV format
- **Generic CSV** — Customizable column mapping
- **Custom XML** — Configurable XML export

**Features:**
- Map BuildFlow fields to payroll system fields
- Preview before export
- Export by date range, project, or crew
- Export history and audit trail
- Scheduled exports (weekly on Monday)

---

### Settings
**Route:** `/labor/settings`

**Configurable Options:**
- Timecard entry method (daily or weekly)
- Overtime rules (after 8 hrs/day, after 40 hrs/week, Saturday/Sunday rates)
- Double-time rules
- Shift differential rates and schedules
- Approval workflow (foreman → PM → payroll)
- Cost code allocation rules (split time allowed, minimum increment)
- Prevailing wage enforcement (warn or block if below rate)
- Certified payroll template (WH-347 or state-specific)
- Payroll export format and schedule
- Timecard submission deadline (e.g., end of day Friday)
- Late timecard notifications
- Worker onboarding checklist template
- Badge printing configuration

---

## Automations

### Workflow Automations
| Trigger | Action |
|---|---|
| Timecard submitted by foreman | Notify PM for approval |
| Timecard approved by PM | Lock timecard, update labor costs in budget |
| Timecard rejected | Return to foreman with notes |
| Timecard not submitted by deadline | Alert foreman + PM |
| All timecards approved for week | Auto-generate certified payroll (if federal) |
| Certified payroll generated | Notify PM for signature |
| Certified payroll signed | Mark for submission, notify admin |
| Worker added to project | Check certs, create onboarding checklist |
| Worker cert expired | Alert PM + safety, restrict site access |
| Payroll export scheduled | Auto-export, confirm success, alert on error |
| Prevailing wage violation detected | Block timecard approval, alert PM |
| Overtime threshold reached (daily) | Alert foreman + PM |
| Weekly hours exceed limit | Alert PM + labor manager |

### Auto-Calculations
- Regular hours = min(daily_hours, 8) (or per OT rule config)
- Overtime hours = hours above 8/day or 40/week (1.5x rate)
- Double-time hours = per project rules (Sunday, holiday, >12 hrs)
- Labor cost = (ST hours × rate) + (OT hours × 1.5 × rate) + (DT hours × 2 × rate)
- Burdened rate = base + fringe + taxes + insurance
- Worker-hours (for safety) = sum of all hours for TRIR calculation
- Cost code labor allocation = hours × burdened rate per cost code

### PDF Generation
- **WH-347 Certified Payroll** — Federal format, one per week per project
- **Weekly Timesheet** — Per worker or per crew summary
- **Labor Cost Report** — Hours and costs by cost code, trade, worker
- **Crew Roster** — Worker list with classifications and rates
- **Overtime Report** — OT hours by worker and project
- **Payroll Summary** — Weekly/monthly payroll totals

---

## API Endpoints

```
GET    /api/projects/[id]/labor/timecards              — List timecards
POST   /api/projects/[id]/labor/timecards              — Create timecard
GET    /api/projects/[id]/labor/timecards/[tcId]       — Get timecard detail
PUT    /api/projects/[id]/labor/timecards/[tcId]       — Update timecard
POST   /api/projects/[id]/labor/timecards/[tcId]/submit — Submit for approval
POST   /api/projects/[id]/labor/timecards/[tcId]/approve — Approve
POST   /api/projects/[id]/labor/timecards/[tcId]/reject  — Reject with notes
GET    /api/projects/[id]/labor/timecards/weekly/[date]  — Weekly summary

GET    /api/projects/[id]/labor/crew                   — Crew roster
POST   /api/projects/[id]/labor/crew                   — Add worker to project
PUT    /api/projects/[id]/labor/crew/[workerId]        — Update worker assignment
DELETE /api/projects/[id]/labor/crew/[workerId]        — Remove from project
GET    /api/projects/[id]/labor/crew/[workerId]/hours  — Worker hours history

GET    /api/labor/wage-rates                           — List wage rate tables
POST   /api/labor/wage-rates                           — Create wage table
PUT    /api/labor/wage-rates/[id]                      — Update wage table
POST   /api/labor/wage-rates/import                    — Import from SAM.gov/CSV

GET    /api/projects/[id]/labor/certified-payroll              — List cert payrolls
POST   /api/projects/[id]/labor/certified-payroll/generate     — Generate WH-347
GET    /api/projects/[id]/labor/certified-payroll/[cpId]       — Get cert payroll
POST   /api/projects/[id]/labor/certified-payroll/[cpId]/sign  — Digital signature
POST   /api/projects/[id]/labor/certified-payroll/[cpId]/submit — Mark as submitted
GET    /api/projects/[id]/labor/certified-payroll/[cpId]/pdf   — Download PDF

POST   /api/labor/payroll-export                       — Export to payroll system
GET    /api/labor/payroll-export/history               — Export history
PUT    /api/labor/payroll-export/mapping               — Configure field mapping

GET    /api/projects/[id]/labor/reports/cost           — Labor cost report
GET    /api/projects/[id]/labor/reports/overtime        — Overtime report
GET    /api/projects/[id]/labor/reports/productivity    — Productivity report
GET    /api/projects/[id]/labor/reports/headcount       — Daily headcount
```

---

## Validation Rules (Zod Schemas)

```typescript
// Daily Timecard Entry
const timecardEntrySchema = z.object({
  project_id: z.string().uuid(),
  date: z.string().date(),
  foreman_id: z.string().uuid(),
  work_performed: z.string().max(2000).optional(),
  notes: z.string().max(1000).optional(),
  entries: z.array(z.object({
    worker_id: z.string().uuid(),
    trade: z.string().min(1).max(100),
    classification: z.string().min(1).max(100),
    time_allocations: z.array(z.object({
      cost_code_id: z.string().uuid(),
      straight_time: z.number().min(0).max(24).refine(
        v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
      ),
      overtime: z.number().min(0).max(24).default(0).refine(
        v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
      ),
      double_time: z.number().min(0).max(24).default(0).refine(
        v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
      ),
    })).min(1),
  })).min(1),
}).refine(data => {
  // Validate no worker exceeds 24 hours in a day
  for (const entry of data.entries) {
    const totalHours = entry.time_allocations.reduce(
      (sum, a) => sum + a.straight_time + a.overtime + a.double_time, 0
    );
    if (totalHours > 24) return false;
  }
  return true;
}, "No worker can exceed 24 hours in a day");

// Wage Rate
const wageRateSchema = z.object({
  trade: z.string().min(1).max(100),
  classification: z.string().min(1).max(100),
  locality: z.string().max(200),
  wage_determination: z.string().max(50).optional(),
  base_rate: z.number().positive().refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  fringe_rate: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  effective_date: z.string().date(),
  expiration_date: z.string().date().optional(),
});

// Certified Payroll
const certifiedPayrollSchema = z.object({
  project_id: z.string().uuid(),
  week_ending: z.string().date(),
  contractor_name: z.string(),
  contract_number: z.string().optional(),
  payroll_number: z.number().int().positive(),
  certification_signer: z.string().uuid(),
  certification_date: z.string().date(),
});
```

---

## Calculation Library

```typescript
import Decimal from 'decimal.js';

// Calculate labor cost for a single time entry
function calculateLaborCost(
  straightTime: Decimal,
  overtime: Decimal,
  doubleTime: Decimal,
  baseRate: Decimal,
  fringeRate: Decimal,
): LaborCost {
  const totalRate = baseRate.plus(fringeRate);
  const stCost = straightTime.times(totalRate);
  const otCost = overtime.times(baseRate.times(new Decimal(1.5))).plus(overtime.times(fringeRate));
  const dtCost = doubleTime.times(baseRate.times(new Decimal(2))).plus(doubleTime.times(fringeRate));
  const totalCost = stCost.plus(otCost).plus(dtCost);
  const totalHours = straightTime.plus(overtime).plus(doubleTime);
  const effectiveRate = totalHours.isZero() ? new Decimal(0) : totalCost.div(totalHours);

  return { stCost, otCost, dtCost, totalCost, totalHours, effectiveRate };
}

// Prevailing wage compliance check
function checkPrevailingWageCompliance(
  actualRate: Decimal,
  requiredRate: Decimal,
): { compliant: boolean; shortfall: Decimal } {
  const shortfall = requiredRate.minus(actualRate);
  return {
    compliant: shortfall.lte(0),
    shortfall: shortfall.gt(0) ? shortfall : new Decimal(0),
  };
}

// Weekly hours aggregation
function aggregateWeeklyHours(
  dailyEntries: DailyEntry[],
  weeklyOTThreshold: number = 40,
): WeeklyAggregation {
  let totalST = new Decimal(0);
  let totalOT = new Decimal(0);
  let totalDT = new Decimal(0);

  for (const day of dailyEntries) {
    totalST = totalST.plus(new Decimal(day.straight_time));
    totalOT = totalOT.plus(new Decimal(day.overtime));
    totalDT = totalDT.plus(new Decimal(day.double_time));
  }

  // Check weekly OT threshold
  const weeklyRegular = totalST;
  const weeklyOTFromThreshold = weeklyRegular.gt(weeklyOTThreshold)
    ? weeklyRegular.minus(weeklyOTThreshold)
    : new Decimal(0);

  return {
    straightTime: totalST.minus(weeklyOTFromThreshold),
    overtime: totalOT.plus(weeklyOTFromThreshold),
    doubleTime: totalDT,
    totalHours: totalST.plus(totalOT).plus(totalDT),
  };
}
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation)
- Company Directory for worker employer tracking
- Budget module for labor cost allocation to cost codes
- Safety module for certification compliance checks
- Accounting module for payroll liability tracking
- Cost code structure for time allocation
- Email service (Resend) for notifications
- PDF generation for WH-347 and reports
