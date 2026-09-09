# Budget Module — Enterprise Grade

## Module Priority: Build Phase 3 (Weeks 11–13) — Extracted from Cost Controls

## Overview
Project-level budget management built on CSI MasterFormat cost codes. Tracks original budget, approved changes, revised budget, committed costs, actual costs, forecast to complete, and estimated final cost. Every financial transaction across the platform (pay apps, change orders, purchase orders, invoices) flows through the budget as the single source of financial truth. Supports both owner-side and GC-side budgeting with earned value analysis.

---

## Pages & Routes

### Landing Page
**Route:** `/projects/[projectId]/budget`
**Purpose:** Budget overview with variance indicators

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Project Budget                          [Import] [+ Add Line] │
│                                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐         │
│  │ $4.2M    │ │ $4.35M   │ │ $3.8M    │ │ $4.28M   │         │
│  │ Original │ │ Revised  │ │ Committed│ │ Forecast │         │
│  │ Budget   │ │ Budget   │ │ Costs    │ │ Final    │         │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘         │
│                                                                 │
│  ┌─ Variance Alert ─────────────────────────────────────────┐  │
│  │  ⚠️ 3 cost codes over budget  |  📈 Forecast: -$70K      │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ Budget Grid ─────────────────────────────────────────────┐ │
│  │  Code  | Description      | Original | Changes | Revised  │ │
│  │        |                  | Budget   | (+/-)   | Budget   │ │
│  │  01    | General Cond     | $340,000 | +$15K   | $355,000 │ │
│  │  03    | Concrete         | $420,000 | —       | $420,000 │ │
│  │  07    | Thermal & Moist  | $185,000 | +$22K   | $207,000 │ │
│  │  09    | Finishes         | $310,000 | -$8K    | $302,000 │ │
│  │  22    | Plumbing         | $280,000 | —       | $280,000 │ │
│  │  23    | HVAC             | $1,470K  | +$45K   | $1,515K  │ │
│  │  26    | Electrical       | $86,000  | —       | $86,000  │ │
│  │                                                            │ │
│  │  (continued...)                                            │ │
│  │                                                            │ │
│  │  Code  | Committed | Actual  | Forecast | Variance        │ │
│  │  01    | $340,000  | $180K   | $355,000 | ✅ $0           │ │
│  │  03    | $420,000  | $315K   | $418,000 | ✅ +$2K         │ │
│  │  07    | $185,000  | $92K    | $210,000 | ❌ -$3K         │ │
│  │  23    | $1,470K   | $890K   | $1,490K  | ⚠️ +$25K        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Quick Links: [Change Orders] [Contracts] [Pay Apps] [Reports]  │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/projects/[projectId]/budget/dashboard`
**Purpose:** Visual budget health and financial analytics

**Widgets:**
1. **Budget Health Gauge** — Overall budget vs forecast (green/yellow/red zones)
2. **Cash Flow Curve** — S-curve: planned vs actual spend over time
3. **Cost Code Variance Chart** — Bar chart: budget vs forecast by division (color-coded)
4. **Commitment Coverage** — % of budget committed (contracted vs uncommitted)
5. **Change Order Impact** — Cumulative CO value over time
6. **Contingency Burn** — Remaining contingency vs project % complete
7. **Earned Value Metrics** — CPI (Cost Performance Index), SPI (Schedule Performance Index)
8. **Monthly Spend** — Actual cost by month with trend line

---

### Budget Line Detail
**Route:** `/projects/[projectId]/budget/[costCodeId]`
**Purpose:** Drill into a single cost code's full financial picture

**Tabs:**
1. **Summary** — Original, changes, revised, committed, actual, forecast, variance
2. **Transactions** — All financial transactions hitting this cost code (pay apps, invoices, POs, COs)
3. **Contracts** — Contracts and POs allocated to this cost code
4. **Change Orders** — COs impacting this cost code
5. **Forecast** — Cost-to-complete detail, estimator notes, forecast history
6. **History** — Audit trail of all budget modifications

**Transaction Ledger:**
```
┌─────────────────────────────────────────────────────────────┐
│  Cost Code 23-00 — HVAC        Revised Budget: $1,515,000   │
│                                                              │
│  Date     | Type    | Reference    | Amount    | Running    │
│  1/15/26  | PO      | PO-2026-012  | $45,000   | $45,000   │
│  1/20/26  | PayApp  | PA #1 - Moran| $185,000  | $230,000  │
│  2/01/26  | CO      | CO-003       | +$45,000  | (budget)  │
│  2/10/26  | Invoice | INV-8824     | $12,400   | $242,400  │
│  2/15/26  | PayApp  | PA #2 - Moran| $220,000  | $462,400  │
│                                                              │
│  Committed: $1,470,000  |  Actual: $462,400  |  Remaining: │
│  Forecast:  $1,490,000  |  Variance: -$25,000 (over)       │
└─────────────────────────────────────────────────────────────┘
```

---

### Forecast Manager
**Route:** `/projects/[projectId]/budget/forecast`
**Purpose:** Update cost-to-complete estimates and project final cost

**Features:**
- PM/estimator enters forecast-to-complete per cost code
- System calculates EAC (Estimate at Completion) = actuals + forecast-to-complete
- Snapshot forecast monthly (creates audit trail)
- Forecast methods: manual, trending (% complete extrapolation), earned value
- Forecast notes per cost code (explain variances)
- Lock previous months' forecasts
- Compare current forecast to previous month and original budget

**Forecast Grid:**
```
┌─────────────────────────────────────────────────────────────────┐
│  Forecast Update — Period: February 2026                         │
│                                                                  │
│  Code | Description  | Budget  | Actual | FTC     | EAC     |Var│
│  01   | General Cond | $355K   | $180K  | $172K   | $352K   | ✅│
│  03   | Concrete     | $420K   | $315K  | $103K   | $418K   | ✅│
│  07   | T&M          | $207K   | $92K   | $118K   | $210K   | ⚠️│
│  23   | HVAC         | $1,515K | $890K  | $600K   | $1,490K | ✅│
│  26   | Electrical   | $86K    | $32K   | $56K    | $88K    | ⚠️│
│  ─────────────────────────────────────────────────────────────── │
│  TOTAL              | $4,350K | $2,180K| $2,098K | $4,278K | ✅ │
│                                                                  │
│  Contingency Remaining: $72,000 (32% of original)               │
│                                                                  │
│                              [Save Draft] [Submit Forecast]      │
└─────────────────────────────────────────────────────────────────┘
```

---

### Budget Reports
**Route:** `/projects/[projectId]/budget/reports`
**Purpose:** Generate standard financial reports

**Report Types:**
- **Budget Summary** — One-page project financial summary
- **Cost Code Detail** — Full breakdown by division/cost code
- **Variance Report** — Over/under budget items with explanations
- **Cash Flow Projection** — Monthly projected spend vs actual
- **Earned Value Report** — CPI, SPI, BCWP, BCWS, ACWP
- **Contingency Status** — Original, used, remaining, projected
- **WIP (Work in Progress)** — Under/over billing analysis
- **Job Cost Report** — Revenue, costs, profit, margin by cost code

---

### Settings
**Route:** `/projects/[projectId]/budget/settings`

**Configurable Options:**
- Cost code template (CSI MasterFormat divisions to include)
- Budget columns to display (toggle original, changes, revised, committed, actual, forecast, variance)
- Variance threshold alerts (% and $ amounts)
- Contingency tracking method (single line vs distributed)
- Forecast update frequency (monthly, bi-weekly)
- Forecast lock date (prevent edits to past periods)
- Auto-populate budget from estimate on project creation
- Budget approval workflow (PM → Director for budget modifications)
- Report templates and default formatting
- Currency and rounding settings

---

## Automations

### Auto-Calculations
- Revised Budget = Original Budget + Approved Change Orders
- Committed = Sum of all contract amounts + PO amounts allocated to cost code
- Actual = Sum of all approved pay app line items + invoices for cost code
- Forecast = Actual + Forecast-to-Complete (or auto-calculated from % complete)
- Variance = Revised Budget − Forecast (positive = under budget)
- CPI = Earned Value ÷ Actual Cost
- SPI = Earned Value ÷ Planned Value
- Contingency Remaining = Original Contingency − Applied COs

### Workflow Automations
| Trigger | Action |
|---|---|
| Change order approved | Auto-update revised budget for affected cost codes |
| Contract executed | Add to committed costs for allocated cost codes |
| Pay app approved | Update actual costs for all line item cost codes |
| Purchase order approved | Add to committed costs |
| Invoice approved | Update actual costs |
| Cost code exceeds budget | Alert PM + project director |
| Cost code forecast exceeds revised budget | Alert PM |
| Contingency below 25% | Alert PM + project director |
| Monthly forecast due | Remind PM to update forecast |
| Forecast submitted | Snapshot current forecast, notify leadership |
| Estimate awarded | Auto-create budget from estimate line items |

### PDF Generation
- **Budget Summary** — One-page executive summary
- **Detailed Budget** — Full cost code breakdown with all columns
- **Variance Report** — Flagged items with explanations
- **Cash Flow Report** — Monthly projection chart
- **WIP Report** — Under/over billing by cost code

### Export Options
- PDF (formatted for print/email)
- Excel (editable with formulas, pivot-ready)
- CSV (flat data export for accounting system import)

---

## API Endpoints

```
GET    /api/projects/[id]/budget                     — Get full budget grid
POST   /api/projects/[id]/budget                     — Initialize budget
PUT    /api/projects/[id]/budget                     — Update budget
POST   /api/projects/[id]/budget/import              — Import budget from estimate or CSV
GET    /api/projects/[id]/budget/export              — Export budget

GET    /api/projects/[id]/budget/[costCodeId]         — Get cost code detail
PUT    /api/projects/[id]/budget/[costCodeId]         — Update cost code budget
GET    /api/projects/[id]/budget/[costCodeId]/transactions — List transactions

GET    /api/projects/[id]/budget/forecast             — Get current forecast
PUT    /api/projects/[id]/budget/forecast             — Update forecast
POST   /api/projects/[id]/budget/forecast/submit      — Submit monthly forecast
POST   /api/projects/[id]/budget/forecast/snapshot    — Create forecast snapshot
GET    /api/projects/[id]/budget/forecast/history     — Forecast history

GET    /api/projects/[id]/budget/reports/summary       — Budget summary report
GET    /api/projects/[id]/budget/reports/variance       — Variance report
GET    /api/projects/[id]/budget/reports/cashflow       — Cash flow projection
GET    /api/projects/[id]/budget/reports/earned-value   — Earned value report
GET    /api/projects/[id]/budget/reports/wip            — WIP report
GET    /api/projects/[id]/budget/reports/job-cost       — Job cost report
```

---

## Validation Rules (Zod Schemas)

```typescript
// Budget Line
const budgetLineSchema = z.object({
  cost_code_id: z.string().uuid(),
  original_budget: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  notes: z.string().max(1000).optional(),
});

// Budget Import
const budgetImportSchema = z.object({
  source: z.enum(['estimate', 'csv', 'excel']),
  estimate_id: z.string().uuid().optional(),
  file_url: z.string().url().optional(),
  overwrite_existing: z.boolean().default(false),
}).refine(data => {
  if (data.source === 'estimate') return !!data.estimate_id;
  if (data.source === 'csv' || data.source === 'excel') return !!data.file_url;
  return false;
}, "Must provide estimate_id or file_url based on source");

// Forecast Update
const forecastUpdateSchema = z.object({
  period: z.string().regex(/^\d{4}-\d{2}$/, "Format: YYYY-MM"),
  lines: z.array(z.object({
    cost_code_id: z.string().uuid(),
    forecast_to_complete: z.number().min(0).refine(
      v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
    ),
    forecast_method: z.enum(['manual', 'trending', 'earned_value']).default('manual'),
    notes: z.string().max(500).optional(),
  })).min(1),
});

// Variance threshold must be positive
const varianceAlertSchema = z.object({
  cost_code_id: z.string().uuid().optional(), // null = project-level
  threshold_percent: z.number().min(0).max(100).optional(),
  threshold_amount: z.number().min(0).optional(),
}).refine(data => data.threshold_percent || data.threshold_amount,
  "Must set at least one threshold type"
);
```

---

## Calculation Library

```typescript
import Decimal from 'decimal.js';

// Budget line calculations
function calculateBudgetLine(line: BudgetLine): BudgetCalculations {
  const original = new Decimal(line.original_budget);
  const changes = new Decimal(line.approved_changes); // sum of COs
  const revised = original.plus(changes);
  const committed = new Decimal(line.committed_costs);  // contracts + POs
  const actual = new Decimal(line.actual_costs);         // pay apps + invoices
  const ftc = new Decimal(line.forecast_to_complete);
  const eac = actual.plus(ftc); // Estimate at Completion
  const variance = revised.minus(eac); // positive = under budget

  return {
    original, changes, revised, committed, actual,
    forecastToComplete: ftc,
    estimateAtCompletion: eac,
    variance,
    variancePercent: revised.isZero()
      ? new Decimal(0)
      : variance.div(revised).times(100),
    commitmentCoverage: revised.isZero()
      ? new Decimal(0)
      : committed.div(revised).times(100),
  };
}

// Earned Value calculations
function calculateEarnedValue(
  budgetAtCompletion: Decimal,
  percentComplete: Decimal, // 0-100
  actualCost: Decimal,
  plannedPercent: Decimal,  // 0-100, planned % complete at this point
): EarnedValueMetrics {
  const bac = budgetAtCompletion;
  const bcwp = bac.times(percentComplete.div(100));  // Earned Value
  const bcws = bac.times(plannedPercent.div(100));    // Planned Value
  const acwp = actualCost;

  const cpi = acwp.isZero() ? new Decimal(1) : bcwp.div(acwp);
  const spi = bcws.isZero() ? new Decimal(1) : bcwp.div(bcws);
  const eac = cpi.isZero() ? bac : bac.div(cpi);
  const etc = eac.minus(acwp);
  const vac = bac.minus(eac);

  return { bcwp, bcws, acwp, cpi, spi, eac, etc, vac };
}

// WIP calculation (over/under billing)
function calculateWIP(
  contractAmount: Decimal,
  percentComplete: Decimal,
  totalBilled: Decimal,
): { earnedRevenue: Decimal; overUnderBilling: Decimal; status: string } {
  const earnedRevenue = contractAmount.times(percentComplete.div(100));
  const overUnderBilling = totalBilled.minus(earnedRevenue);
  const status = overUnderBilling.isPositive() ? 'over_billed' :
                 overUnderBilling.isNegative() ? 'under_billed' : 'balanced';
  return { earnedRevenue, overUnderBilling, status };
}
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation)
- `cost_codes` table must exist (CSI MasterFormat)
- Change Order module for approved CO → budget impact
- Contract Management for committed cost tracking
- Pay Applications for actual cost tracking
- Estimating module for estimate → budget import flow
