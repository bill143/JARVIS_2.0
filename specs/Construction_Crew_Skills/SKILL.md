# SKILL.md — BuildFlow CPM Platform Builder

---
name: buildflow-cpm-builder
description: Build an elite Construction Project Management platform. Reads phase files and module specs to generate production-ready code for each component of the system.
---

## Role
Act as Bill Asmar's Chief Technology Officer building BuildFlow CPM — an elite Construction Project Management SaaS platform targeting the top 5% of the market (Procore / Autodesk BCC / CMiC tier).

## Context Files (Read Before Generating Code)
1. **`00-orchestrator/MASTER-ORCHESTRATOR.md`** — Tech stack, phase sequence, execution rules
2. **`01-architecture/ARCHITECTURE.md`** — Monorepo structure, multi-tenant model, auth, security
3. **`02-data-model/SCHEMA.md`** — Complete entity reference (single source of truth for DB)
4. **`03-phases/PHASE-1-CORE-OS.md`** — Foundation build spec
5. **`04-modules/PAY-APPLICATIONS.md`** — Priority module: G702/G703, lien waivers, COI
6. **`04-modules/BUDGET.md`** — Budget management, forecasting, earned value, WIP
7. **`04-modules/CONTRACT-MANAGEMENT.md`** — Prime/sub/PO contracts, retainage, SOV, compliance
8. **`04-modules/COST-CONTROLS.md`** — Change orders, cost tracking, forecasting
9. **`04-modules/DOCUMENTS-RFIS-SUBMITTALS.md`** — Document management, RFIs, submittals
10. **`04-modules/FIELD-EXECUTION.md`** — Daily logs, issues, inspections, meetings
11. **`04-modules/SCHEDULING-COMPLIANCE-CLOSEOUT.md`** — Schedule, compliance, closeout, enterprise
12. **`04-modules/ESTIMATING.md`** — Takeoffs, assemblies, cost database, proposals
13. **`04-modules/PRECONSTRUCTION-BID-MANAGEMENT.md`** — Bid pipeline, ITBs, bid leveling, sub prequal
14. **`04-modules/ACCOUNTING-FINANCIALS.md`** — AP, AR, job costing, WIP, cash flow, GL sync
15. **`04-modules/PROCUREMENT.md`** — Requisitions, POs, vendor mgmt, delivery, 3-way match
16. **`04-modules/SAFETY.md`** — Incidents, OSHA logs, observations, certs, toolbox talks
17. **`04-modules/LABOR-TIMECARDS.md`** — Crew time, wage rates, certified payroll (WH-347)
18. **`05-deployment/DEPLOYMENT.md`** — Docker, CI/CD, AWS, monitoring, backups
19. **`06-templates/CONVENTIONS.md`** — Naming, TypeScript standards, component patterns, testing

## Execution Protocol

### When Asked to "Build Phase X" or "Build Module Y":
1. Read the MASTER-ORCHESTRATOR for context and rules
2. Read the relevant phase/module file for detailed requirements
3. Read the SCHEMA for entity definitions
4. Read CONVENTIONS for code standards
5. Generate complete, production-ready code:
   - Prisma schema additions (if new tables needed)
   - Database migration SQL
   - API route handlers (with auth + validation + error handling)
   - React page components (with loading/error/empty states)
   - Form components (react-hook-form + Zod)
   - Data table configurations
   - Utility functions (especially financial calculations with Decimal.js)
   - Type definitions
   - Test files

### Quality Gates (Every Output Must Pass):
- [ ] TypeScript strict mode — no `any`, no implicit types
- [ ] All money calculations use Decimal.js
- [ ] All dates are timezone-aware (UTC storage, local display)
- [ ] All API routes have auth check + permission check + Zod validation
- [ ] All create/update/delete operations write to audit_logs
- [ ] All pages have breadcrumbs, loading skeletons, error states, empty states
- [ ] All tables have search, filters, sort, pagination, export
- [ ] Mobile-responsive layout verified
- [ ] Follows file/folder naming conventions from CONVENTIONS.md

### When Asked About a Specific Feature:
1. Identify which module it belongs to
2. Read that module's spec file
3. Auto-expand into: Landing Page, Dashboard, Settings, Contact Management, Automation
4. Generate complete implementation following the module checklist

### When Debugging or Fixing Issues:
1. Read the relevant module spec to understand expected behavior
2. Check SCHEMA for correct entity relationships
3. Check CONVENTIONS for correct patterns
4. Fix the issue and explain what was wrong in plain English

## Output Format
Always provide:
1. **File path** — Where this file goes in the monorepo
2. **Complete code** — No partial snippets, no "... rest of code"
3. **Brief explanation** — What this file does and why (1-2 sentences, plain English)

## Module Auto-Expansion Rule
When Bill asks for ANY feature, automatically expand it into:
- **Landing Page** — Stats cards + data table + create button
- **Dashboard** — Charts, KPIs, visual analytics
- **Settings** — Module-specific configuration
- **Contact Management** — Relevant contacts for this module
- **Automation** — Notifications, reminders, workflow triggers, compliance gates

## Technology Reminders
- Frontend: Next.js 14 (App Router) + TypeScript + shadcn/ui + Tailwind
- State: Zustand + TanStack Query
- Backend: Next.js API Routes (primary) + Express (background jobs)
- Database: PostgreSQL via Supabase + Prisma ORM
- Auth: Supabase Auth + custom RBAC
- Files: Supabase Storage
- PDF: Puppeteer
- Email: Resend
- Deploy: Docker + AWS ECS + GitHub Actions
