# Procurement Module — Enterprise Grade

## Module Priority: Build Phase 11 (Weeks 59–63)

## Overview
End-to-end procurement lifecycle: purchase requisitions → approval → purchase orders → vendor management → delivery tracking → receiving → three-way match (PO ↔ receipt ↔ invoice). Manages all material and equipment purchases outside of subcontracts. Tracks commitments against budget, monitors delivery schedules, and ensures materials arrive when the schedule needs them. Integrates with Budget (committed costs), Accounting (AP invoices), and Field Execution (delivery logs).

---

## Pages & Routes

### Landing Page
**Route:** `/projects/[projectId]/procurement`
**Purpose:** All procurement activity for a project

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  Procurement                               [+ New Requisition] │
│                                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐         │
│  │ 28       │ │ $340K    │ │ 6        │ │ 3        │         │
│  │ Active   │ │ Total PO │ │ Pending  │ │ Deliveries│         │
│  │ POs      │ │ Value    │ │ Approval │ │ This Week│         │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘         │
│                                                                 │
│  ┌─ Tabs: All | Requisitions | POs | Deliveries | Vendors ──┐ │
│  │                                                            │ │
│  │  PO#     | Vendor         | Description      | Amount     │ │
│  │  PO-012  | Chicago Supply | Mechanical Pipe   | $45,000   │ │
│  │  PO-013  | ABC Materials  | Ceiling Tile      | $18,200   │ │
│  │  PO-014  | Steel Inc      | Structural Steel  | $124,000  │ │
│  │                                                            │ │
│  │  Status  | Ordered | Received | Remaining | Delivery Date │ │
│  │  Open    | 2/01    | 75%      | $11,250   | 2/28          │ │
│  │  Open    | 2/05    | 0%       | $18,200   | 3/15          │ │
│  │  Partial | 1/20    | 60%      | $49,600   | 3/01          │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Quick Links: [Vendor Directory] [Delivery Calendar] [Budget]   │
└─────────────────────────────────────────────────────────────┘
```

---

### Dashboard
**Route:** `/projects/[projectId]/procurement/dashboard`
**Purpose:** Procurement analytics and delivery tracking

**Widgets:**
1. **Procurement Pipeline** — Requisitions → approved → ordered → received → closed
2. **Spend by Vendor** — Pie chart of PO value by vendor
3. **Delivery Calendar** — Upcoming deliveries this week/month
4. **Budget Impact** — PO committed vs budget by cost code
5. **On-Time Delivery Rate** — % of deliveries on time (last 90 days)
6. **Open PO Aging** — POs open longer than expected
7. **Spend by Cost Code** — PO value by division/cost code
8. **Pending Approvals** — Requisitions awaiting PM approval

---

### Purchase Requisition
**Route:** `/projects/[projectId]/procurement/requisitions/new`
**Purpose:** Request materials or equipment for purchase

**Form:**
```
┌─────────────────────────────────────────────────────────────┐
│  Purchase Requisition                                         │
│                                                               │
│  Requested By:     [Current User - Auto]                     │
│  Date Needed:      [__/__/____]                               │
│  Priority:         [○ Low  ● Normal  ○ High  ○ Urgent]       │
│  Cost Code:        [23-00 HVAC General            ▼]         │
│                                                               │
│  ┌─ Line Items ──────────────────────────────────────────┐   │
│  │  # | Description          | Qty | Unit | Est. Cost   │   │
│  │  1 | 4" Copper Pipe Type L| 500 | LF   | $12,500     │   │
│  │  2 | 4" 90° Elbow Copper  | 24  | EA   | $960        │   │
│  │  3 | Pipe Hangers 4"      | 120 | EA   | $480        │   │
│  │  [+ Add Line Item]                                    │   │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  Subtotal: $13,940                                           │
│  Preferred Vendor: [Chicago Supply           ▼] (optional)   │
│  Justification: [_________________________________]          │
│  Attachments: [Upload specs/catalog pages]                   │
│                                                               │
│                              [Save Draft] [Submit for Approval]│
└─────────────────────────────────────────────────────────────┘
```

---

### Purchase Order Detail
**Route:** `/projects/[projectId]/procurement/po/[poId]`
**Purpose:** Full PO detail with receiving and invoice matching

**Tabs:**
1. **Summary** — PO info, vendor, amount, dates, status
2. **Line Items** — Ordered items with quantity, unit cost, extended cost
3. **Deliveries** — Delivery schedule, actual receipts, partial shipments
4. **Receiving** — Record materials received (qty, date, condition, photos)
5. **Invoices** — Matched vendor invoices (three-way match: PO ↔ receipt ↔ invoice)
6. **Documents** — PO document, quotes, submittals, delivery tickets
7. **History** — Full audit trail

**Three-Way Match:**
```
┌─────────────────────────────────────────────────────────────┐
│  Three-Way Match — PO-012                                     │
│                                                               │
│  Item                | PO Qty | Received | Invoiced | Match  │
│  4" Copper Pipe      | 500 LF | 375 LF   | 375 LF   | ✅    │
│  4" 90° Elbow        | 24 EA  | 24 EA    | 24 EA    | ✅    │
│  Pipe Hangers        | 120 EA | 120 EA   | 100 EA   | ⚠️    │
│                                                               │
│  PO Amount: $45,000  |  Received: $33,750  |  Invoiced: $32K│
│  Status: Partial Match — 20 hangers invoiced short           │
│                                                               │
│  [Approve Match] [Flag Discrepancy] [Contact Vendor]         │
└─────────────────────────────────────────────────────────────┘
```

---

### Vendor Management
**Route:** `/procurement/vendors`
**Purpose:** Organization-wide vendor directory and performance

**Features:**
- Vendor profiles with contact info, payment terms, trade categories
- Performance scoring (on-time delivery, quality, pricing, responsiveness)
- Price history by material type
- Preferred vendor designation
- Insurance and compliance tracking (links to COI module)
- Payment history and outstanding balances
- Notes and communication log

---

### Delivery Calendar
**Route:** `/projects/[projectId]/procurement/deliveries`
**Purpose:** Visual delivery schedule with project timeline

**Features:**
- Calendar view of expected deliveries
- Color-coded by status: scheduled (blue), confirmed (green), delayed (red)
- Link to daily log entries (delivery recorded in field)
- Delivery notification to superintendent
- Site logistics coordination (crane availability, staging area)
- Drag-and-drop reschedule (updates PO delivery date)

---

### Settings
**Route:** `/projects/[projectId]/procurement/settings`

**Configurable Options:**
- PO numbering format (prefix, auto-increment)
- Requisition approval thresholds (auto-approve below $X)
- Approval routing (requestor → PM → Director for above $X)
- Default payment terms by vendor
- Three-way match tolerance (% variance allowed)
- Required fields on requisitions
- Delivery notification recipients
- Preferred vendor list management
- Tax rates by jurisdiction
- Shipping/handling defaults

---

## Automations

### Workflow Automations
| Trigger | Action |
|---|---|
| Requisition submitted | Route to PM for approval |
| Requisition approved | Convert to PO draft, notify procurement |
| PO issued to vendor | Email PO PDF to vendor, update committed costs |
| Delivery date approaching (3 days) | Notify superintendent + PM |
| Delivery received | Update PO received quantity, log in daily report |
| Delivery late | Alert PM, flag PO |
| Invoice received | Attempt three-way match against PO and receipts |
| Three-way match successful | Auto-approve for payment (if below threshold) |
| Three-way match discrepancy | Flag for PM review |
| PO fully received and invoiced | Close PO |
| Budget overrun from PO | Alert PM before PO approval |
| Vendor performance below threshold | Alert procurement manager |

### Auto-Calculations
- PO total = sum of (line qty × unit cost) + tax + shipping
- Received value = sum of received qty × unit cost
- Remaining = PO total − received value
- Committed cost auto-feeds to budget module
- Three-way match: PO amount vs received amount vs invoice amount (within tolerance)

### PDF Generation
- **Purchase Order** — Formatted PO with company letterhead, vendor info, line items, terms
- **Requisition Form** — Internal request form
- **Delivery Report** — Receiving log with dates and quantities
- **Vendor Summary** — Vendor performance and payment history
- **Open PO Report** — All open POs with remaining values

---

## API Endpoints

```
GET    /api/projects/[id]/procurement/requisitions              — List requisitions
POST   /api/projects/[id]/procurement/requisitions              — Create requisition
GET    /api/projects/[id]/procurement/requisitions/[reqId]      — Get requisition
PUT    /api/projects/[id]/procurement/requisitions/[reqId]      — Update requisition
POST   /api/projects/[id]/procurement/requisitions/[reqId]/approve  — Approve
POST   /api/projects/[id]/procurement/requisitions/[reqId]/reject   — Reject
POST   /api/projects/[id]/procurement/requisitions/[reqId]/convert  — Convert to PO

GET    /api/projects/[id]/procurement/po                        — List POs
POST   /api/projects/[id]/procurement/po                        — Create PO
GET    /api/projects/[id]/procurement/po/[poId]                 — Get PO detail
PUT    /api/projects/[id]/procurement/po/[poId]                 — Update PO
POST   /api/projects/[id]/procurement/po/[poId]/issue           — Issue to vendor
POST   /api/projects/[id]/procurement/po/[poId]/receive         — Record delivery
POST   /api/projects/[id]/procurement/po/[poId]/close           — Close PO
GET    /api/projects/[id]/procurement/po/[poId]/match           — Three-way match

GET    /api/projects/[id]/procurement/deliveries                — Delivery calendar
GET    /api/procurement/vendors                                 — List vendors
GET    /api/procurement/vendors/[id]                            — Vendor detail
PUT    /api/procurement/vendors/[id]                            — Update vendor
GET    /api/procurement/vendors/[id]/performance                — Performance scores
```

---

## Validation Rules (Zod Schemas)

```typescript
// Purchase Requisition
const requisitionSchema = z.object({
  date_needed: z.string().date(),
  priority: z.enum(['low', 'normal', 'high', 'urgent']).default('normal'),
  cost_code_id: z.string().uuid(),
  preferred_vendor_id: z.string().uuid().optional(),
  justification: z.string().max(2000).optional(),
  line_items: z.array(z.object({
    description: z.string().min(1).max(500),
    quantity: z.number().positive(),
    unit: z.string().min(1).max(20),
    estimated_unit_cost: z.number().min(0).refine(
      v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
    ),
  })).min(1),
});

// Purchase Order
const purchaseOrderSchema = z.object({
  vendor_company_id: z.string().uuid(),
  cost_code_id: z.string().uuid(),
  requisition_id: z.string().uuid().optional(),
  delivery_date: z.string().date(),
  delivery_location: z.string().max(500).optional(),
  payment_terms: z.enum(['net_15', 'net_30', 'net_45', 'net_60']).default('net_30'),
  tax_rate: z.number().min(0).max(100).default(0),
  shipping_cost: z.number().min(0).default(0),
  line_items: z.array(z.object({
    description: z.string().min(1).max(500),
    quantity: z.number().positive(),
    unit: z.string().min(1).max(20),
    unit_cost: z.number().min(0).refine(
      v => Number(v.toFixed(2)) === v, "Max 2 decimal places"
    ),
  })).min(1),
  notes: z.string().max(2000).optional(),
});

// Receiving
const receivingSchema = z.object({
  po_id: z.string().uuid(),
  received_date: z.string().date(),
  received_by: z.string().uuid(),
  delivery_ticket_number: z.string().max(50).optional(),
  line_items: z.array(z.object({
    po_line_id: z.string().uuid(),
    quantity_received: z.number().min(0),
    condition: z.enum(['good', 'damaged', 'partial_damage', 'rejected']),
    notes: z.string().max(500).optional(),
  })).min(1),
  photos: z.array(z.string().url()).optional(),
  notes: z.string().max(1000).optional(),
});
```

---

## Dependencies
- Phase 1 Core OS must be complete
- Budget module for committed cost tracking
- Company Directory for vendor information
- Accounting module for AP invoice matching
- Field Execution (daily logs) for delivery recording
- COI Tracking for vendor compliance
- Email service (Resend) for PO delivery and notifications
- PDF generation for PO documents
