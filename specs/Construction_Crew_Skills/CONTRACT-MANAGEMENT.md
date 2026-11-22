# Contract Management Module — Enterprise Grade

## Module Priority: Build Phase 3 (Weeks 13–15) — Extracted from Cost Controls

## Overview
Full contract lifecycle management for all contract types: prime contracts (owner ↔ GC), subcontracts (GC ↔ sub), purchase orders (GC ↔ vendor), and professional services agreements. Tracks contract amounts, change orders, payment status, compliance requirements, insurance, retainage, and key dates. Every dollar on the project is governed by a contract — this module is the legal and financial backbone.

---

## Pages & Routes

### Landing Page
**Route:** `/projects/[projectId]/contracts`
**Purpose:** All contracts on a project with financial summary

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Contracts                               [+ New Contract]     │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ $4.2M    │ │ $3.8M    │ │ $1.9M    │ │ $1.2M    │        │
│  │ Prime    │ │ Sub      │ │ Billed   │ │ Remaining│        │
│  │ Contract │ │ Committed│ │ To Date  │ │ To Bill  │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ Tabs: All | Prime | Subcontracts | Purchase Orders ────┐ │
│  │                                                           │ │
│  │  Contract Table                                           │ │
│  │  #       | Company        | Type | Original  | Current   │ │
│  │  C-001   | GSA (Owner)    | Prime| $4,200,000| $4,350,000│ │
│  │  SC-001  | F.E. Moran     | Sub  | $1,470,000| $1,515,000│ │
│  │  SC-002  | Pace Systems   | Sub  | $86,000   | $86,000   │ │
│  │  SC-003  | Smith Concrete | Sub  | $420,000  | $420,000  │ │
│  │  PO-001  | Chicago Supply | PO   | $45,000   | $45,000   │ │
│  │                                                           │ │
│  │  Status | Paid     | Retainage | Compliance | Actions    │ │
│  │  Active | $1,200K  | $60K      | ✅         | ⋯         │ │
│  │  Active | $890K    | $44.5K    | ✅         | ⋯         │ │
│  │  Active | $32K     | $1.6K     | ⚠️ COI     | ⋯         │ │
│  │  Active | $315K    | $15.75K   | ✅         | ⋯         │ │
│  │  Closed | $45K     | —         | ✅         | ⋯         │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  Quick Links: [Change Orders] [Pay Apps] [Insurance] [SOV]     │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/projects/[projectId]/contracts/dashboard`
**Purpose:** Contract financial analytics and compliance overview

**Widgets:**
1. **Contract Value Breakdown** — Stacked bar: original + COs = current by contract
2. **Payment Progress** — % paid per contract (horizontal bars)
3. **Retainage Summary** — Total held by contract, projected release dates
4. **Compliance Status** — COI/lien waiver/safety compliance per sub
5. **Uncommitted Budget** — Budget amount not yet under contract
6. **Change Order Impact** — CO value by contract over time
7. **Payment Aging** — Days since last payment per contract
8. **Contract Expiration** — Contracts expiring within 30/60/90 days

---

### Contract Detail View
**Route:** `/projects/[projectId]/contracts/[contractId]`
**Purpose:** Everything about a single contract

**Tabs:**
1. **Summary** — Contract info, amounts, key dates, status, contacts
2. **Schedule of Values** — SOV line items for this contract
3. **Change Orders** — All COs against this contract (approved, pending, rejected)
4. **Pay Applications** — Billing history for this contract
5. **Compliance** — COI status, lien waivers, safety docs, licenses
6. **Documents** — Contract documents, executed agreements, amendments, correspondence
7. **Retainage** — Retainage schedule, amounts held, release tracking
8. **History** — Full audit trail

**Summary Tab:**
```
┌─────────────────────────────────────────────────────────────┐
│  SC-001 — F.E. Moran Mechanical                              │
│  Status: Active  |  Type: Subcontract  |  Trade: Div 23 HVAC│
│                                                               │
│  ┌─ Financial Summary ─────────────────────────────────────┐ │
│  │  Original Contract:        $1,470,000                   │ │
│  │  Approved Change Orders:   +$45,000 (3 COs)            │ │
│  │  Current Contract Value:   $1,515,000                   │ │
│  │                                                         │ │
│  │  Billed to Date:           $890,000 (58.7%)            │ │
│  │  Paid to Date:             $845,500                     │ │
│  │  Retainage Held:           $44,500 (5%)                │ │
│  │  Remaining to Bill:        $625,000                     │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─ Key Dates ─────────────────────────────────────────────┐ │
│  │  Executed:     11/15/2025                               │ │
│  │  Notice to Proceed: 12/01/2025                          │ │
│  │  Substantial Completion: 06/30/2026                     │ │
│  │  Final Completion: 07/31/2026                           │ │
│  │  Warranty Expires: 07/31/2027                           │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─ Contacts ─────────────────────────────────────────────┐  │
│  │  PM: John Smith (jsmith@femoran.com)                   │  │
│  │  Superintendent: Mike Davis (mdavis@femoran.com)       │  │
│  │  Billing: Lisa Johnson (ljohnson@femoran.com)          │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  [Edit Contract] [Create CO] [Create Pay App] [Print]        │
└─────────────────────────────────────────────────────────────┘
```

---

### Create/Edit Contract
**Route:** `/projects/[projectId]/contracts/new` | `/contracts/[contractId]/edit`
**Purpose:** Create or modify a contract

**Wizard Steps:**

**Step 1 — Contract Header**
```
┌─────────────────────────────────────────────────────────────┐
│  New Contract                                                 │
│                                                               │
│  Contract Type:    [● Prime  ○ Subcontract  ○ PO  ○ PSA]    │
│  Company:          [Select from Directory           ▼]       │
│  Trade/Division:   [Div 23 — HVAC                  ▼]       │
│  Contract Number:  [SC-001] (auto-generated)                 │
│  Contract Name:    [HVAC — Supply & Install_________]        │
│                                                               │
│  Original Amount:  [$1,470,000.00]                           │
│  Retainage %:      [5.0%]                                   │
│  Retainage Cap:    [☑ Reduce to 0% at 50% complete]         │
│                                                               │
│  Payment Terms:    [Net 30 ▼]                                │
│  Delivery Method:  [Lump Sum ▼]                              │
│                                                               │
│  Cost Code Allocation:                                       │
│  [23-00 HVAC General    ] [$1,200,000]                      │
│  [23-20 HVAC Piping     ] [$150,000  ]                      │
│  [23-30 HVAC Air Dist   ] [$120,000  ]                      │
│  [+ Add Cost Code]                                           │
│                                                               │
│                                    [Cancel] [Save & Continue] │
└─────────────────────────────────────────────────────────────┘
```

**Step 2 — Key Dates**
- Execution date, NTP date, start date, substantial completion, final completion
- Warranty start/end dates
- Liquidated damages terms ($/day)

**Step 3 — Compliance Requirements**
- Required insurance types and minimum limits
- Lien waiver requirements
- Safety requirements (EMR threshold, OSHA certs)
- Licensing requirements
- Bonding requirements

**Step 4 — Schedule of Values**
- Enter SOV line items (description, scheduled value)
- Import from CSV/Excel
- Or import from estimate line items

**Step 5 — Documents**
- Upload executed contract, exhibits, insurance certificates
- Link to related documents in document management

---

### Retainage Manager
**Route:** `/projects/[projectId]/contracts/retainage`
**Purpose:** Track retainage across all contracts

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Retainage Summary                                            │
│                                                               │
│  Total Retainage Held: $121,850                              │
│  Projected Release: $45,000 at substantial, $76,850 at final │
│                                                               │
│  Contract      | Current Value | Billed  | Ret %  | Held    │
│  SC-001 Moran  | $1,515,000   | $890K   | 5.0%   | $44,500 │
│  SC-002 Pace   | $86,000      | $32K    | 5.0%   | $1,600  │
│  SC-003 Smith  | $420,000     | $315K   | 5.0%   | $15,750 │
│  SC-004 ABC    | $380,000     | $240K   | 5.0%   | $12,000 │
│  (12 more...)  | ...          | ...     | ...    | ...     │
│  ──────────────────────────────────────────────────────────  │
│  Prime Contract| $4,350,000   | $1.9M   | 5.0%   | $95,000 │
│                                                               │
│  [Release Retainage] [Generate Report] [Export]              │
└─────────────────────────────────────────────────────────────┘
```

---

### Settings
**Route:** `/projects/[projectId]/contracts/settings`

**Configurable Options:**
- Contract numbering format (prefix by type: C-, SC-, PO-)
- Default retainage percentage
- Retainage reduction rules (at % complete or $ threshold)
- Default payment terms
- Required compliance items per contract type
- Minimum insurance limits by trade
- Contract approval workflow (PM → Director → VP for above $X)
- SOV import format preferences
- Contract template library
- Auto-generate contract from bid award
- Liquidated damages tracking

---

## Automations

### Auto-Calculations
- Current Contract Value = Original + Approved COs
- Billed to Date = Sum of approved pay app amounts
- Paid to Date = Sum of marked-as-paid pay app amounts
- Retainage Held = Billed to Date × Retainage % (with cap logic)
- Remaining to Bill = Current Contract Value − Billed to Date
- % Complete = Billed to Date ÷ Current Contract Value × 100

### Workflow Automations
| Trigger | Action |
|---|---|
| Contract created | Assign number, create budget allocation, notify PM |
| Contract executed | Record execution date, start compliance tracking |
| NTP issued | Notify sub, update project schedule link |
| Change order approved | Update current contract value, update budget |
| Pay app submitted against contract | Validate against SOV, check compliance |
| Pay app approved | Update billed-to-date, calculate retainage |
| Payment made | Update paid-to-date, generate payment record |
| COI expiring for contract | Alert PM + sub, flag contract compliance |
| Lien waiver missing | Block next pay app approval |
| Contract substantial completion | Trigger punch list, reduce retainage |
| Contract final completion | Trigger final lien waiver, release retainage |
| Warranty expiring (30 days) | Alert PM and facilities team |
| Bid awarded in Preconstruction | Auto-create contract from winning bid |

### PDF Generation
- **Contract Summary** — One-page overview per contract
- **All Contracts Summary** — Project-wide contract list with financials
- **Retainage Report** — Retainage held/released by contract
- **Compliance Matrix** — COI/lien waiver/safety status per contract
- **SOV Report** — Schedule of values for a contract
- **Contract Comparison** — Original vs current with CO detail

---

## API Endpoints

```
GET    /api/projects/[id]/contracts                    — List all contracts
POST   /api/projects/[id]/contracts                    — Create contract
GET    /api/projects/[id]/contracts/[contractId]        — Get contract detail
PUT    /api/projects/[id]/contracts/[contractId]        — Update contract
DELETE /api/projects/[id]/contracts/[contractId]        — Delete (draft only)
POST   /api/projects/[id]/contracts/[contractId]/execute — Mark as executed
POST   /api/projects/[id]/contracts/[contractId]/ntp     — Issue NTP
POST   /api/projects/[id]/contracts/[contractId]/close   — Close contract

GET    /api/projects/[id]/contracts/[contractId]/sov              — Get SOV
POST   /api/projects/[id]/contracts/[contractId]/sov              — Create SOV lines
PUT    /api/projects/[id]/contracts/[contractId]/sov/[sovId]      — Update SOV line
POST   /api/projects/[id]/contracts/[contractId]/sov/import       — Import SOV
GET    /api/projects/[id]/contracts/[contractId]/sov/export       — Export SOV

GET    /api/projects/[id]/contracts/[contractId]/change-orders     — List COs
GET    /api/projects/[id]/contracts/[contractId]/pay-apps          — List pay apps
GET    /api/projects/[id]/contracts/[contractId]/compliance        — Compliance status
GET    /api/projects/[id]/contracts/[contractId]/documents         — Contract docs

GET    /api/projects/[id]/contracts/retainage                      — Retainage summary
POST   /api/projects/[id]/contracts/[contractId]/retainage/release — Release retainage
GET    /api/projects/[id]/contracts/[contractId]/retainage/history — Release history

GET    /api/projects/[id]/contracts/summary                        — Project-wide summary
GET    /api/projects/[id]/contracts/uncommitted                    — Uncommitted budget
```

---

## Validation Rules (Zod Schemas)

```typescript
// Contract Creation
const createContractSchema = z.object({
  contract_type: z.enum(['prime', 'subcontract', 'purchase_order', 'professional_services']),
  company_id: z.string().uuid(),
  name: z.string().min(1).max(300),
  trade_division: z.string().optional(),
  original_amount: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  retainage_percent: z.number().min(0).max(100).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ).default(5),
  retainage_cap_percent: z.number().min(0).max(100).optional(),
  retainage_cap_action: z.enum(['reduce_to_zero', 'reduce_to_half', 'custom']).optional(),
  payment_terms: z.enum(['net_15', 'net_30', 'net_45', 'net_60', 'net_90']).default('net_30'),
  delivery_method: z.enum(['lump_sum', 'gmp', 'cost_plus', 'unit_price', 'time_materials']),
  cost_code_allocations: z.array(z.object({
    cost_code_id: z.string().uuid(),
    amount: z.number().min(0).refine(v => Number(v.toFixed(2)) === v),
  })).min(1),
  execution_date: z.string().date().optional(),
  ntp_date: z.string().date().optional(),
  start_date: z.string().date().optional(),
  substantial_completion_date: z.string().date().optional(),
  final_completion_date: z.string().date().optional(),
  warranty_months: z.number().int().min(0).default(12),
  liquidated_damages_per_day: z.number().min(0).optional(),
  notes: z.string().max(5000).optional(),
}).refine(data => {
  const total = data.cost_code_allocations.reduce((sum, a) => sum + a.amount, 0);
  return Math.abs(total - data.original_amount) < 0.01;
}, "Cost code allocations must equal contract amount");

// SOV Line Item
const sovLineItemSchema = z.object({
  item_number: z.string().min(1).max(20),
  description: z.string().min(1).max(500),
  scheduled_value: z.number().min(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  cost_code_id: z.string().uuid().optional(),
  notes: z.string().max(500).optional(),
});

// Retainage Release
const retainageReleaseSchema = z.object({
  amount: z.number().positive().refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  reason: z.enum(['substantial_completion', 'final_completion', 'partial_release', 'other']),
  notes: z.string().max(1000).optional(),
  effective_date: z.string().date(),
});
```

---

## Dependencies
- Phase 1 Core OS must be complete (auth, projects, navigation, company directory)
- `cost_codes` table must exist for budget allocation
- Budget module for committed cost tracking
- Pay Applications module for billing against contracts
- Change Order module (in Cost Controls) for CO impact
- COI Tracking for compliance gating
- Lien Waiver management for payment compliance
- Preconstruction module for bid award → contract creation flow
