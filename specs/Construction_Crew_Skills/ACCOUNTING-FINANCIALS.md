# Accounting & Financials Module — Enterprise Grade

## Module Priority: Build Phase 10 (Weeks 53–58)

## Overview
Construction-specific accounting engine: job costing, accounts payable (sub invoices & vendor bills), accounts receivable (owner billing & collections), WIP (work-in-progress) reporting, cash flow management, and general ledger integration. This is NOT a full ERP replacement — it's the project-level financial layer that syncs with external accounting systems (Sage 300, Viewpoint Vista, QuickBooks, Xero). Every financial transaction across BuildFlow rolls up here for reporting, compliance, and executive visibility.

---

## Pages & Routes

### Landing Page — Financial Overview
**Route:** `/financials`
**Purpose:** Organization-wide financial health across all projects

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Financials                                   [Reports ▼]     │
│                                                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │ $42.6M   │ │ $38.2M   │ │ $18.4M   │ │ $3.8M    │        │
│  │ Total    │ │ Revenue  │ │ Total AP │ │ Cash     │        │
│  │ Contract │ │ Earned   │ │ Outstand │ │ Flow Gap │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│                                                                │
│  ┌─ Tabs: Overview | AP | AR | Job Cost | WIP | Cash Flow ─┐ │
│  │                                                           │ │
│  │  Project Financial Summary (sortable)                     │ │
│  │  Project      | Contract  | Billed   | Paid    | Margin  │ │
│  │  GSA Chicago  | $4.35M    | $1.9M    | $1.6M   | 8.2%   │ │
│  │  VA Hines     | $1.85M    | $420K    | $380K   | 6.8%   │ │
│  │  USCIS Cool   | $2.1M     | $1.4M    | $1.2M   | 9.1%   │ │
│  │  Fed Lobby    | $3.5M     | $3.2M    | $3.0M   | 7.4%   │ │
│  │                                                           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  Quick Links: [Invoices] [Payments] [WIP Report] [GL Sync]    │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/financials/dashboard`
**Purpose:** Executive financial analytics

**Widgets:**
1. **Revenue vs Cost** — Stacked area chart over time (revenue earned vs costs incurred)
2. **Profit Margin by Project** — Horizontal bars, color-coded by health
3. **Accounts Payable Aging** — Buckets: current, 30, 60, 90, 120+ days
4. **Accounts Receivable Aging** — Same buckets for owner payments
5. **Cash Flow Forecast** — 90-day projection: inflows vs outflows
6. **WIP Summary** — Over-billed vs under-billed across all projects
7. **Monthly Revenue** — Bar chart with trend line
8. **Top 10 Outstanding Invoices** — Sorted by amount, with aging

---

### Accounts Payable
**Route:** `/financials/ap`
**Purpose:** Track sub invoices, vendor bills, payment approval and processing

**Features:**
- Record invoices from subs and vendors
- Match invoices to contracts and POs
- Approval workflow (PM → AP → Director for above threshold)
- Track payment status (pending, approved, scheduled, paid, held, disputed)
- Link to pay applications (sub invoices → pay app line items)
- Lien waiver gating (require waiver before payment release)
- Partial payment support
- Payment scheduling and batch processing
- Check/EFT number tracking
- 1099 tracking for year-end

**AP Invoice Detail:**
```
┌─────────────────────────────────────────────────────────────┐
│  Invoice: INV-2026-0088 — F.E. Moran                        │
│  Status: Approved — Scheduled for Payment 2/28/26            │
│                                                              │
│  Invoice Date: 2/10/2026    |  Due Date: 3/12/2026          │
│  Invoice Amount: $220,000   |  Retainage: $11,000            │
│  Net Payment: $209,000                                       │
│                                                              │
│  Contract: SC-001           |  Pay App: PA #2                │
│  Cost Code: 23-00 HVAC      |  PO: —                        │
│                                                              │
│  Compliance Check:                                           │
│  ☑ COI Current              ☑ Lien Waiver Received          │
│  ☑ Certified Payroll Filed   ☑ Safety Docs Current          │
│                                                              │
│  [Approve] [Hold] [Dispute] [Schedule Payment]              │
└─────────────────────────────────────────────────────────────┘
```

---

### Accounts Receivable
**Route:** `/financials/ar`
**Purpose:** Track owner billing, collections, and payment receipt

**Features:**
- Track owner invoices generated from pay applications
- Record payments received from owner
- Aging analysis (current, 30, 60, 90, 120+ days)
- Payment reminders (automated email to owner contact)
- Retainage receivable tracking
- Apply payments to specific invoices
- Write-off and adjustment capability
- Collection notes and follow-up tracking

---

### Job Cost Report
**Route:** `/financials/job-cost` | `/projects/[projectId]/financials/job-cost`
**Purpose:** Detailed cost analysis by project and cost code

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│  Job Cost Report — GSA Chicago                                    │
│  Period: Through February 2026                                    │
│                                                                   │
│  Code | Description  | Revenue  | Costs    | Margin  | Margin % │
│  01   | General Cond | $355,000 | $340,000 | $15,000 | 4.2%     │
│  03   | Concrete     | $420,000 | $418,000 | $2,000  | 0.5%     │
│  07   | T&M          | $207,000 | $210,000 | -$3,000 | -1.4%    │
│  23   | HVAC         | $1,515K  | $1,490K  | $25,000 | 1.7%     │
│  26   | Electrical   | $86,000  | $88,000  | -$2,000 | -2.3%    │
│  ...  | ...          | ...      | ...      | ...     | ...      │
│  ────────────────────────────────────────────────────────────────│
│  TOTAL              | $4,350K  | $4,278K  | $72,000 | 1.7%     │
│                                                                   │
│  [Filter by Division] [Filter by Period] [Export] [Print]        │
└─────────────────────────────────────────────────────────────────┘
```

---

### WIP (Work in Progress) Report
**Route:** `/financials/wip`
**Purpose:** Over/under billing analysis for financial reporting and bonding

**Layout:**
```
┌─────────────────────────────────────────────────────────────────┐
│  WIP Report — As of February 28, 2026                            │
│                                                                   │
│  Project      | Contract  | % Comp | Earned   | Billed   | O/U  │
│  GSA Chicago  | $4,350K   | 43.7%  | $1,901K  | $1,900K  | ($1K)│
│  VA Hines     | $1,850K   | 22.7%  | $420K    | $420K    | $0   │
│  USCIS Cool   | $2,100K   | 66.7%  | $1,400K  | $1,400K  | $0   │
│  Fed Lobby    | $3,500K   | 91.4%  | $3,200K  | $3,200K  | $0   │
│  ────────────────────────────────────────────────────────────────│
│  TOTAL        | $11,800K  |        | $6,921K  | $6,920K  | ($1K)│
│                                                                   │
│  Over-Billed Total: $0     Under-Billed Total: $1,000           │
│  Net Position: Under-billed by $1,000                            │
│                                                                   │
│  [Select Period] [Export for Auditor] [Print]                    │
└─────────────────────────────────────────────────────────────────┘
```

---

### Cash Flow Manager
**Route:** `/financials/cash-flow`
**Purpose:** Project and manage cash flow across the organization

**Features:**
- 90-day cash flow projection
- Planned inflows (owner payments expected based on pay app schedule)
- Planned outflows (sub payments scheduled, vendor bills due)
- Net cash position by week/month
- "What-if" scenarios (delay a payment, accelerate billing)
- Bank balance integration (manual or via Plaid in future)
- Cash flow alerts (negative cash position warning)

---

### GL Integration Hub
**Route:** `/financials/integrations`
**Purpose:** Sync financial data with external accounting systems

**Supported Systems:**
- **Sage 300 CRE** — Export journal entries, import GL balances
- **Viewpoint Vista** — Two-way sync: contracts, pay apps, invoices
- **QuickBooks Online** — Export invoices, import payments
- **Xero** — Export invoices, import payments
- **Custom CSV** — Flexible import/export for any system

**Features:**
- Map BuildFlow cost codes to GL account numbers
- Schedule automatic syncs (daily, weekly, on-demand)
- Transaction staging area (review before push to GL)
- Error handling and conflict resolution
- Sync history and audit log
- Field mapping configuration

---

### Settings
**Route:** `/financials/settings`

**Configurable Options:**
- GL account mapping (cost codes → GL accounts)
- AP approval thresholds (auto-approve below $X)
- AP approval workflow routing
- Payment terms defaults
- Payment method preferences (check, EFT, wire)
- 1099 threshold and tracking rules
- AR aging bucket definitions
- Cash flow projection parameters
- WIP calculation method (cost-to-cost, units of delivery, labor hours)
- External system integration credentials and schedule
- Fiscal year start month
- Report templates and formatting

---

## Automations

### Workflow Automations
| Trigger | Action |
|---|---|
| Pay app approved (sub) | Create AP invoice, route for payment approval |
| Pay app approved (owner) | Create AR invoice, track receivable |
| Owner payment received | Apply to AR invoice, update cash flow |
| Sub payment approved | Schedule payment, update AP |
| Sub payment released | Update paid amounts, record check/EFT |
| Invoice past due (30 days) | Alert PM + AP team |
| AR past due (30 days) | Send payment reminder to owner |
| AR past due (60 days) | Escalate to project director |
| Cash flow negative projected | Alert CFO + PM |
| Month end | Auto-generate WIP report, snapshot financials |
| GL sync scheduled | Run sync, report errors |
| 1099 threshold reached | Flag vendor for year-end reporting |

### Auto-Calculations
- AP aging = days since invoice date
- AR aging = days since billing date
- WIP over/under = billed to date − (contract × % complete)
- Cash flow = sum of expected inflows − sum of expected outflows by period
- Profit margin = (revenue − costs) ÷ revenue × 100
- Gross margin = revenue − direct costs

### PDF Generation
- **AP Aging Report** — Accounts payable by vendor with aging buckets
- **AR Aging Report** — Accounts receivable by project/owner with aging
- **Job Cost Report** — Revenue, costs, margin by project and cost code
- **WIP Report** — Over/under billing formatted for bonding company/CPA
- **Cash Flow Projection** — Monthly cash flow chart and table
- **Payment Register** — All payments made in a period with details
- **1099 Summary** — Year-end vendor payment summary

---

## API Endpoints

```
GET    /api/financials/overview                        — Org-wide financial summary
GET    /api/financials/dashboard                       — Dashboard widget data

GET    /api/financials/ap                              — List AP invoices
POST   /api/financials/ap                              — Create AP invoice
GET    /api/financials/ap/[id]                         — Get invoice detail
PUT    /api/financials/ap/[id]                         — Update invoice
POST   /api/financials/ap/[id]/approve                 — Approve for payment
POST   /api/financials/ap/[id]/hold                    — Put on hold
POST   /api/financials/ap/[id]/schedule                — Schedule payment
POST   /api/financials/ap/[id]/pay                     — Record payment
GET    /api/financials/ap/aging                        — AP aging report

GET    /api/financials/ar                              — List AR invoices
POST   /api/financials/ar                              — Create AR invoice
GET    /api/financials/ar/[id]                         — Get AR detail
POST   /api/financials/ar/[id]/record-payment          — Record owner payment
POST   /api/financials/ar/[id]/send-reminder           — Send payment reminder
GET    /api/financials/ar/aging                        — AR aging report

GET    /api/financials/job-cost                        — Org-wide job cost
GET    /api/projects/[id]/financials/job-cost          — Project job cost
GET    /api/financials/wip                             — WIP report
GET    /api/financials/cash-flow                       — Cash flow projection

GET    /api/financials/integrations                    — List integrations
POST   /api/financials/integrations/[system]/sync      — Trigger sync
GET    /api/financials/integrations/[system]/history    — Sync history
PUT    /api/financials/integrations/[system]/mapping    — Update GL mapping

GET    /api/financials/reports/[type]                  — Generate report
```

---

## Validation Rules (Zod Schemas)

```typescript
// AP Invoice
const apInvoiceSchema = z.object({
  vendor_company_id: z.string().uuid(),
  contract_id: z.string().uuid().optional(),
  purchase_order_id: z.string().uuid().optional(),
  pay_app_id: z.string().uuid().optional(),
  invoice_number: z.string().min(1).max(50),
  invoice_date: z.string().date(),
  due_date: z.string().date(),
  amount: z.number().positive().refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  retainage_amount: z.number().min(0).default(0).refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  cost_code_allocations: z.array(z.object({
    cost_code_id: z.string().uuid(),
    amount: z.number().positive().refine(v => Number(v.toFixed(2)) === v),
  })).min(1),
  description: z.string().max(1000).optional(),
  attachments: z.array(z.string().url()).optional(),
}).refine(data => {
  const total = data.cost_code_allocations.reduce((sum, a) => sum + a.amount, 0);
  return Math.abs(total - data.amount) < 0.01;
}, "Cost code allocations must equal invoice amount");

// Payment Record
const paymentRecordSchema = z.object({
  invoice_id: z.string().uuid(),
  payment_date: z.string().date(),
  amount: z.number().positive().refine(
    v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
  ),
  payment_method: z.enum(['check', 'eft', 'wire', 'ach', 'credit_card']),
  reference_number: z.string().max(50).optional(), // check number, EFT reference
  notes: z.string().max(500).optional(),
});

// GL Mapping
const glMappingSchema = z.object({
  cost_code_id: z.string().uuid(),
  gl_account_number: z.string().min(1).max(50),
  gl_account_name: z.string().max(200).optional(),
  department_code: z.string().max(20).optional(),
});
```

---

## Dependencies
- Phase 1 Core OS must be complete
- Budget module for cost code structure
- Contract Management for contract financial data
- Pay Applications for billing data (both sub and owner)
- Change Order module for CO financial impacts
- Company Directory for vendor/sub information
- Email service (Resend) for payment reminders
- PDF generation (Puppeteer) for reports
