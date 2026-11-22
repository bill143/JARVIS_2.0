# Estimating Module — Enterprise Grade

## Module Priority: Build Phase 8 (Weeks 41–46)

## Overview
Full-lifecycle estimating engine: conceptual budgets → detailed takeoffs → assembly-based pricing → bid assembly → proposal generation. Integrates historical cost database, CSI MasterFormat cost code structure, and real-time unit pricing. Supports multiple estimate types (conceptual, schematic, design development, construction documents, change order pricing). Feeds directly into Budget and Preconstruction/Bid Management modules.

---

## Pages & Routes

### Landing Page
**Route:** `/estimating`
**Purpose:** All estimates across the organization with quick stats

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Estimating                                  [+ New Estimate] │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ 24       │ │ 8        │ │ $42.6M   │ │ 3        │        │
│  │ Total    │ │ Active   │ │ Total    │ │ Due This │        │
│  │ Estimates│ │ In Prog  │ │ Est Value│ │ Week     │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ Tabs: All | Draft | In Progress | Final | Awarded ─────┐ │
│  │                                                           │ │
│  │  Estimate Table (sortable, filterable)                    │ │
│  │  #   | Project      | Type     | Value       | Status    │ │
│  │  E-24| GSA Chicago  | CD       | $4,200,000  | Final     │ │
│  │  E-23| VA Hines     | SD       | $1,850,000  | Draft     │ │
│  │  E-22| USCIS Cool   | CO Price | $186,400    | Submitted │ │
│  │                                                           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  Quick Links: [Cost Database] [Assembly Library] [Templates]   │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/estimating/dashboard`
**Purpose:** Analytics and performance tracking

**Widgets:**
1. **Estimating Pipeline** — Funnel chart: estimates created → submitted → awarded (win rate %)
2. **Monthly Volume** — Bar chart of estimate value submitted per month
3. **Cost Code Breakdown** — Pie chart showing typical cost distribution by division
4. **Accuracy Tracker** — Scatter plot: estimated vs actual on awarded projects (variance %)
5. **Team Workload** — Estimates assigned per estimator with due dates
6. **Historical Trends** — $/SF by building type over time
7. **Win Rate by Type** — Bar chart showing win rate by project type (federal, commercial, healthcare)
8. **Average Turnaround** — Days from estimate start to submission

---

### Create/Edit Estimate
**Route:** `/estimating/new` | `/estimating/[estimateId]/edit`
**Purpose:** Build an estimate from scratch or modify existing

**Wizard Steps:**

**Step 1 — Estimate Header**
```
┌─────────────────────────────────────────────────────────────┐
│  New Estimate                                                 │
│                                                               │
│  Project:          [Select or Create Project        ▼]       │
│  Estimate Type:    [○ Conceptual  ○ SD  ○ DD  ● CD  ○ CO]   │
│  Estimate Name:    [________________________________]         │
│  Due Date:         [__/__/____]                               │
│  Assigned To:      [Select Estimator               ▼]        │
│  Template:         [Blank | Previous Estimate | Template ▼]   │
│                                                               │
│  Building Info:                                               │
│  Gross SF:         [________]    Floors: [___]               │
│  Building Type:    [Commercial | Healthcare | Federal  ▼]     │
│  Construction Type:[New | Renovation | Addition | TI   ▼]     │
│  Location:         [City, State                      ]        │
│  Location Factor:  [1.02] (auto from RS Means data)          │
│                                                               │
│                                    [Cancel] [Save & Continue] │
└─────────────────────────────────────────────────────────────┘
```

**Step 2 — Cost Code Structure**
```
┌─────────────────────────────────────────────────────────────┐
│  Cost Code Setup                                              │
│                                                               │
│  Template: [CSI MasterFormat 2020           ▼] [Apply]       │
│                                                               │
│  ☑ Div 01 — General Requirements                             │
│  ☑ Div 02 — Existing Conditions                              │
│  ☑ Div 03 — Concrete                                         │
│  ☑ Div 04 — Masonry                                          │
│  ☐ Div 05 — Metals                                           │
│  ☑ Div 06 — Wood, Plastics, Composites                       │
│  ☑ Div 07 — Thermal & Moisture Protection                    │
│  ☑ Div 08 — Openings                                         │
│  ☑ Div 09 — Finishes                                         │
│  ☐ Div 10 — Specialties                                      │
│  ...                                                          │
│  ☑ Div 22 — Plumbing                                         │
│  ☑ Div 23 — HVAC                                             │
│  ☑ Div 26 — Electrical                                       │
│                                                               │
│                              [Back] [Save & Continue]         │
└─────────────────────────────────────────────────────────────┘
```

**Step 3 — Takeoff & Pricing (Main Work Area)**
```
┌─────────────────────────────────────────────────────────────────┐
│  GSA Chicago — CD Estimate                    [Save] [Submit]   │
│                                                                  │
│  ┌─ Cost Code Tree ─┐  ┌─ Line Items ─────────────────────────┐│
│  │ 📁 01-General     │  │                                      ││
│  │ 📁 03-Concrete    │  │  Div 03 — Concrete         [$342,000]││
│  │  ├ 03 10 Forming  │  │                                      ││
│  │  ├ 03 20 Rebar    │  │  Code  | Description    | Qty  | Unit││
│  │  ├ 03 30 CIP      │  │  03 30 | 4000psi CIP    | 450  | CY ││
│  │  └ 03 40 Precast  │  │        | Material       |      |    ││
│  │ 📁 07-T&M         │  │        |  Ready Mix     | 450  | CY ││
│  │ 📁 09-Finishes    │  │        |  Pump/Place    | 450  | CY ││
│  │ 📁 22-Plumbing    │  │        |  Finish        | 4200 | SF ││
│  │ 📁 23-HVAC        │  │        | Labor          |      |    ││
│  │ 📁 26-Electrical  │  │        |  Forming       | 4200 | SF ││
│  │                    │  │        |  Rebar         | 22   | TN ││
│  │  ──────────────── │  │        |  Pour Crew     | 180  | HR ││
│  │  Summary:         │  │                                      ││
│  │  Direct: $3.2M    │  │  Unit  | Material | Labor  | Total  ││
│  │  OH&P:   $480K    │  │  CY    | $145.00  | $85.00 | $230.00││
│  │  Total:  $4.2M    │  │  Total | $65,250  | $38,250| $103,500│
│  │  $/SF:   $168     │  │                                      ││
│  └────────────────────┘  │  [+ Add Line] [+ Add Assembly]     ││
│                           └──────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

### Estimate Detail View
**Route:** `/estimating/[estimateId]`
**Purpose:** Read-only view with tabs for all estimate data

**Tabs:**
1. **Summary** — Total by division, $/SF, pie chart breakdown, key metrics
2. **Line Items** — Full line item detail (expandable by division → cost code → line)
3. **Assemblies** — Assembly instances used in this estimate
4. **Markups** — OH&P, contingency, escalation, bonds, insurance, fee
5. **Alternates** — Add/deduct alternates with separate pricing
6. **Qualifications** — Inclusions, exclusions, allowances, assumptions
7. **Comparison** — Side-by-side with other estimate versions or historical projects
8. **History** — Full audit trail of changes, who edited what and when

---

### Assembly Library
**Route:** `/estimating/assemblies`
**Purpose:** Reusable pre-built assemblies (groups of line items)

**Features:**
- Create assemblies from groups of line items (e.g., "Standard 8" CMU Wall" = block, mortar, rebar, labor)
- Assemblies have a unit of measure (per SF, per LF, per EA)
- Auto-price assemblies from unit costs
- Version control — update assembly pricing without affecting past estimates
- Import/export assemblies
- Categorize by CSI division and building type
- Clone and modify existing assemblies

**Assembly Detail:**
```
┌─────────────────────────────────────────────────────────────┐
│  Assembly: Standard 8" CMU Wall                              │
│  Unit: SF  |  Category: 04 - Masonry  |  Version: 3.2      │
│                                                              │
│  Component          | Qty/Unit | Unit | Material | Labor    │
│  8" CMU Block       | 1.125    | EA   | $2.85    | $3.20    │
│  Type S Mortar      | 0.008    | CY   | $165.00  | —        │
│  #5 Rebar Vertical  | 0.15     | LF   | $0.95    | $0.45    │
│  Joint Reinforcing  | 0.375    | LF   | $0.35    | $0.15    │
│  Scaffold           | 1.0      | SF   | —        | $1.20    │
│  ─────────────────────────────────────────────────────────── │
│  Total per SF:                          $6.49    | $5.87    │
│  Combined:                              $12.36 / SF         │
└─────────────────────────────────────────────────────────────┘
```

---

### Cost Database
**Route:** `/estimating/cost-database`
**Purpose:** Organization-wide unit cost library

**Features:**
- Maintain unit costs for materials, labor, equipment, subcontractors
- Costs linked to CSI cost codes
- Regional pricing adjustments (location factors)
- Historical pricing from awarded projects (auto-feeds from actuals)
- Crew rate builder (labor rate × productivity = unit cost)
- Import from RS Means or custom CSV
- Track pricing trends over time
- Effective date ranges (valid from/to)

**Cost Item Record:**
```
┌─────────────────────────────────────────────────────────────┐
│  Cost Item: Ready Mix Concrete 4000psi                       │
│  Code: 03 30 00  |  Unit: CY  |  Updated: Jan 2026          │
│                                                              │
│  ┌─ Pricing History ──────────────────────────────────────┐  │
│  │  Date      | Material | Labor  | Equip  | Total       │  │
│  │  Jan 2026  | $145.00  | $85.00 | $25.00 | $255.00     │  │
│  │  Jul 2025  | $138.00  | $82.00 | $25.00 | $245.00     │  │
│  │  Jan 2025  | $132.00  | $80.00 | $24.00 | $236.00     │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  Sources: [Chicago Ready Mix quote 1/10/26] [F.E. Moran bid]│
│  Location Factor: Chicago = 1.02                             │
└─────────────────────────────────────────────────────────────┘
```

---

### Markups & Fee Builder
**Route:** `/estimating/[estimateId]/markups`
**Purpose:** Apply OH&P, contingency, escalation, bonds, insurance, fee

**Markup Stack:**
```
┌─────────────────────────────────────────────────────────────┐
│  Markup Summary                                              │
│                                                              │
│  Direct Cost Subtotal:                      $3,200,000      │
│                                                              │
│  ┌─ Applied Markups ──────────────────────────────────────┐  │
│  │  ☑ General Conditions     8.0%          $256,000       │  │
│  │  ☑ Overhead               5.0%          $172,800       │  │
│  │  ☑ Profit                 4.0%          $145,152       │  │
│  │  ☑ Contingency            3.0%          $113,339       │  │
│  │  ☑ Escalation (18 mo)     2.5%          $97,218        │  │
│  │  ☑ Bond                   1.2%          $47,815        │  │
│  │  ☑ Builder's Risk Ins     0.4%          $16,050        │  │
│  │  ☐ Design Contingency     —             —              │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ─────────────────────────────────────────────────────────── │
│  Total Estimate:                            $4,048,374      │
│  $/SF (25,000 SF):                          $161.93         │
└─────────────────────────────────────────────────────────────┘
```

**Markup Types:**
- **Percentage** — Applied to subtotal or running total (compounding vs non-compounding)
- **Lump Sum** — Fixed dollar amount
- **Per Unit** — $/SF, $/LF, etc.
- **Custom Formula** — User-defined calculation

---

### Proposal Generator
**Route:** `/estimating/[estimateId]/proposal`
**Purpose:** Generate professional bid proposals from estimate data

**Features:**
- Auto-populate proposal template from estimate data
- Include/exclude sections: cover letter, summary, detailed breakdown, qualifications, alternates, schedule, team
- Company letterhead and branding
- Digital signature block
- Export as PDF
- Track proposal versions
- Attach supporting documents (plans, specs, schedules)

---

### Settings
**Route:** `/estimating/settings`

**Configurable Options:**
- Default markup stack (percentages for OH&P, contingency, etc.)
- Default cost code template (CSI MasterFormat divisions to include)
- Estimate numbering format (prefix, auto-increment)
- Location factors by city/region
- Labor rates by trade (journeyman, apprentice, foreman)
- Crew compositions and productivity rates
- Rounding rules (line item level vs total level)
- Required fields before submission
- Approval routing (estimator → chief estimator → PM)
- PDF proposal template selection
- Cost database update frequency reminders

---

## Automations

### Auto-Calculations
- Line item totals = qty × unit cost (material + labor + equipment)
- Division subtotals = sum of all line items in division
- Markup stack compounds or applies flat based on configuration
- $/SF auto-calculates from total ÷ gross area
- Assembly pricing auto-updates when component costs change (with confirmation)
- Location factor auto-applies to all unit costs

### Workflow Automations
| Trigger | Action |
|---|---|
| Estimate created | Assign estimate number, notify estimator |
| Estimate submitted for review | Notify chief estimator, start SLA timer |
| Estimate approved | Notify PM, unlock proposal generator |
| Estimate finalized | Lock line items, snapshot all unit costs |
| Estimate awarded | Create project + budget from estimate, notify team |
| Due date approaching (3 days) | Alert estimator + PM |
| Cost database item updated | Flag active estimates using that item |
| Historical project completed | Auto-feed actual costs to cost database |

### PDF Generation
- **Estimate Summary** — One-page overview: project info, division totals, markups, grand total, $/SF
- **Detailed Estimate** — Full line item breakdown by division/cost code
- **Proposal Package** — Cover letter + summary + breakdown + qualifications + alternates
- **Comparison Report** — Side-by-side estimate versions or vs historical projects

### Export Options
- PDF (formatted for print/email)
- Excel (editable with formulas, division subtotals)
- CSV (flat data export)

---

## API Endpoints

```
GET    /api/estimates                                — List all estimates (with filters)
POST   /api/estimates                                — Create estimate
GET    /api/estimates/[id]                           — Get estimate detail
PUT    /api/estimates/[id]                           — Update estimate
DELETE /api/estimates/[id]                           — Delete (draft only)
POST   /api/estimates/[id]/submit                    — Submit for review
POST   /api/estimates/[id]/approve                   — Approve estimate
POST   /api/estimates/[id]/finalize                  — Lock and finalize
POST   /api/estimates/[id]/clone                     — Clone estimate (new version)
POST   /api/estimates/[id]/award                     — Mark as awarded → create project
GET    /api/estimates/[id]/pdf                       — Generate PDF
GET    /api/estimates/[id]/comparison/[otherId]       — Compare two estimates

GET    /api/estimates/[id]/line-items                — List line items
POST   /api/estimates/[id]/line-items                — Create line item
PUT    /api/estimates/[id]/line-items/[lineId]        — Update line item
DELETE /api/estimates/[id]/line-items/[lineId]        — Delete line item
POST   /api/estimates/[id]/line-items/bulk            — Bulk create/update

GET    /api/estimates/[id]/markups                   — List markups
PUT    /api/estimates/[id]/markups                   — Update markup stack
GET    /api/estimates/[id]/alternates                — List alternates
POST   /api/estimates/[id]/alternates                — Create alternate

GET    /api/assemblies                               — List assemblies
POST   /api/assemblies                               — Create assembly
GET    /api/assemblies/[id]                          — Get assembly detail
PUT    /api/assemblies/[id]                          — Update assembly
DELETE /api/assemblies/[id]                          — Delete assembly
POST   /api/assemblies/[id]/clone                    — Clone assembly

GET    /api/cost-database                            — List cost items
POST   /api/cost-database                            — Create cost item
PUT    /api/cost-database/[id]                       — Update cost item
POST   /api/cost-database/import                     — Import from CSV/RS Means
GET    /api/cost-database/export                     — Export cost database
GET    /api/cost-database/history/[id]               — Price history for item
```

---

## Validation Rules (Zod Schemas)

```typescript
// Estimate Creation
const createEstimateSchema = z.object({
  project_id: z.string().uuid().optional(), // optional for preconstruction
  name: z.string().min(1).max(200),
  estimate_type: z.enum(['conceptual', 'schematic', 'design_development', 'construction_documents', 'change_order']),
  due_date: z.string().date().optional(),
  assigned_to: z.string().uuid(),
  gross_sf: z.number().positive().optional(),
  building_type: z.string().optional(),
  construction_type: z.enum(['new', 'renovation', 'addition', 'tenant_improvement']).optional(),
  location_city: z.string().optional(),
  location_state: z.string().optional(),
  location_factor: z.number().min(0.5).max(2.0).default(1.0),
});

// Line Item
const estimateLineItemSchema = z.object({
  cost_code_id: z.string().uuid(),
  description: z.string().min(1).max(500),
  quantity: z.number().min(0).refine(
    v => Number(v.toFixed(4)) === v, "Max 4 decimal places"
  ),
  unit: z.string().min(1).max(20),
  material_unit_cost: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  labor_unit_cost: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  equipment_unit_cost: z.number().min(0).default(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  sub_unit_cost: z.number().min(0).default(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  notes: z.string().max(1000).optional(),
});

// Markup
const markupSchema = z.object({
  name: z.string().min(1).max(100),
  type: z.enum(['percentage', 'lump_sum', 'per_unit']),
  value: z.number().min(0),
  applies_to: z.enum(['direct_cost', 'running_total']),
  is_compounding: z.boolean().default(false),
  sort_order: z.number().int().min(0),
});
```

---

## Calculation Library

```typescript
import Decimal from 'decimal.js';

// Line item total
function calculateLineItemTotal(item: EstimateLineItem): {
  material: Decimal; labor: Decimal; equipment: Decimal; sub: Decimal; total: Decimal;
} {
  const qty = new Decimal(item.quantity);
  const material = qty.times(new Decimal(item.material_unit_cost));
  const labor = qty.times(new Decimal(item.labor_unit_cost));
  const equipment = qty.times(new Decimal(item.equipment_unit_cost));
  const sub = qty.times(new Decimal(item.sub_unit_cost));
  const total = material.plus(labor).plus(equipment).plus(sub);
  return { material, labor, equipment, sub, total };
}

// Apply location factor
function applyLocationFactor(baseCost: Decimal, factor: Decimal): Decimal {
  return baseCost.times(factor);
}

// Markup stack calculation
function calculateMarkupStack(
  directCost: Decimal,
  markups: Markup[]
): { subtotals: Map<string, Decimal>; grandTotal: Decimal } {
  const subtotals = new Map<string, Decimal>();
  let runningTotal = directCost;

  for (const markup of markups.sort((a, b) => a.sort_order - b.sort_order)) {
    let markupAmount: Decimal;
    const base = markup.applies_to === 'running_total' ? runningTotal : directCost;

    switch (markup.type) {
      case 'percentage':
        markupAmount = base.times(new Decimal(markup.value).div(100));
        break;
      case 'lump_sum':
        markupAmount = new Decimal(markup.value);
        break;
      case 'per_unit':
        markupAmount = new Decimal(markup.value).times(new Decimal(markup.unit_qty || 0));
        break;
    }
    subtotals.set(markup.name, markupAmount);
    runningTotal = runningTotal.plus(markupAmount);
  }

  return { subtotals, grandTotal: runningTotal };
}

// Cost per SF
function calculateCostPerSF(total: Decimal, grossSF: number): Decimal {
  if (grossSF <= 0) return new Decimal(0);
  return total.div(new Decimal(grossSF));
}

// Estimate accuracy (actual vs estimated)
function calculateVariance(estimated: Decimal, actual: Decimal): Decimal {
  if (estimated.isZero()) return new Decimal(0);
  return actual.minus(estimated).div(estimated).times(100); // percentage
}
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation)
- `cost_codes` table must exist (CSI MasterFormat)
- `companies` and `contacts` tables must exist (for sub pricing)
- Budget module must exist for awarded estimate → budget flow
- PDF generation (Puppeteer) must be configured
