# Design: Extending the UC Delta API to External Delta Tables

- **Status:** Draft (in progress — being validated against an end-to-end prototype)
- **Author:** tdas@databricks.com
- **Created:** 2026-07-02
- **Related:** `spec/protocols/ManagedTablesSpec.md` (UC Delta API v1.0, normative for managed tables), `api/delta.yaml`

> This is a living design. A companion prototype (branch
> `uc-external-delta/prototype`) is being built to surface issues that change
> the design. **Every design decision below is stated with its tradeoffs** —
> the options considered, the pros/cons of each, and why the recommendation
> wins. Recommendations and their rationale are revised in place as the
> prototype (or review) invalidates an assumption; the **Changelog** tracks what
> moved and why.

---

## 1. Summary

The UC Delta API (`/api/2.1/unity-catalog/delta/v1`, implemented by
`DeltaApiService`) already lets Delta clients create, load, and fetch
credentials for **both** managed and external Delta tables. The one thing it
does **not** do for external tables is **commit coordination** — the CCv2
"catalog-managed commits" machinery is deliberately fenced to `MANAGED` tables.

This project opens that gate **safely**. The work is small in code surface and
large in policy: most of the commit-coordination machinery is already
type-agnostic and reusable; the hard part is choosing an **ownership model**
that lets UC become the commit coordinator for a storage path it does not
exclusively own.

## 2. Background and current state

### 2.1 What the UC Delta API is today

- Spec: `api/delta.yaml`, title *"UC Delta API"*, mounted at
  `/api/2.1/unity-catalog/delta/v1`; the normative protocol is
  `spec/protocols/ManagedTablesSpec.md` (v1.0), *"normative for managed tables
  only"* even though *"the same API family also serves external Delta tables"*
  (`ManagedTablesSpec.md:84`).
- Primary handler `DeltaApiService` implements 8 routes today; `/config`
  advertises 12 (`DeltaApiService.java:60-73`) — `deleteTable`, `renameTable`,
  `reportMetrics`, and the `/delta/v1` `temporary-path-credentials` are
  advertised but not implemented here.
- A legacy commit API (`DeltaCommitsService`, `/delta/preview/commits`) still
  exists and shares the same persistence.

### 2.2 What external Delta tables can and cannot do today

| Capability | External support | Evidence |
|---|---|---|
| `createTable` (external supplies `storageLocation`) | Yes | `DeltaApiService.java:202`, `DeltaCreateTableMapper.java:70`, `TableRepository.java:657` |
| `loadTable` / `tableExists` | Yes, but **UC commits not surfaced for external** | `TableRepository.java:389-416`, gate `:406`, doc `:261` |
| `getTableCredentials` | Yes (type-agnostic) | `DeltaApiService.java:257` |
| `updateTable` → `update-metadata-snapshot-version` | **External-only** | `DeltaUpdateTableMapper.java:524` |
| `updateTable` → `add-commit` / `set-latest-backfilled-version` | **Managed-only** (blocked) | `DeltaUpdateTableMapper.java:624`; `DeltaCommitRepository.validateTable:980` |

External tables today are **snapshot-registered**; managed tables are
**coordinated** — after the first commit *"Unity Catalog becomes the commit
coordinator"* (`DeltaCommitRepository.java:323-336`).

### 2.3 Machinery we can reuse

`SELECT ... FOR UPDATE` + `assert-etag`/`assert-table-uuid`
(`TableRepository.java:363-378`); `postCommitCore` with onboarding/normal/
backfill handlers (`DeltaCommitRepository.java:270-514`); the
`uc_delta_commits` staged-commit-pointer table (`DeltaCommitDAO`) — UC stores
commit **pointers, not contents** (`ManagedTablesSpec.md:82`); backfill
lifecycle.

## 3. Problem statement

Let the UC Delta API **coordinate commits** for external Delta tables — UC as
the authoritative CCv2 coordinator for a table whose data lives at a user-owned
external path — without corrupting tables that have writers UC does not control.

## 4. Goals / non-goals

**Goals:** external tables can opt into coordinated commits (onboarding, normal,
conflict, backfill); `loadTable` surfaces UC commit state for them; read/write
credential vending works for external locations; an enforceable ownership model
that prevents split-brain.

**Non-goals (v1):** implementing the missing endpoints (`deleteTable`,
`renameTable`, `reportMetrics`); automatic dual-writer tables; a vacuum/GC
redesign beyond defining ownership boundaries.

---

## 5. Design decisions and tradeoffs

Each decision lists the realistic options with pros/cons, the recommendation,
and a status. `Pending prototype` means the prototype is expected to move or
confirm the recommendation.

### D1 — Ownership / coordination model  *(the crux)*
**Context:** UC's DB commit log is authoritative only if UC is the sole writer
of `_delta_log`. External paths may have other writers
(`DeltaCommitRepository.java:307`).

| Option | Pros | Cons |
|---|---|---|
| **A. Status quo — snapshot tracking only** | Zero new risk; already shipped | Fails the goal; external tables never get coordinated commits / catalog-as-source-of-truth |
| **B. Opt-in, one-way onboarding to catalog-managed commits, enforced by the Delta `catalogOwned` table feature** *(recommended)* | UC becomes authoritative for conformant clients; reuses managed machinery; clear user contract; incremental | One-way (no easy exit, see D7); protection is client-enforced (non-conformant writers can still corrupt); needs onboarding validation (D6); requires cross-engine client conformance |
| **C. UC proxies commits for any external path, no fencing** | Smallest server change; works with any client that calls the API | **Unsafe** — DB log cannot be authoritative; silent divergence from out-of-band writers; no split-brain detection |
| **D. Advisory / reconciling coordination** (UC coordinates but treats `_delta_log` as source of truth, reconciles on read) | Tolerates external writers; no lock-in | UC not authoritative → weaker guarantees; complex, race-prone reconciliation; loses the fast catalog-side commit-listing that motivates CCv2 |

**Recommendation:** **B.** It is the only option that is both safe and meets the
goal. **C** is rejected as unsafe. **D** is the right shape for a *future* true
multi-writer story but is out of scope for v1. **Residual risk accepted:** a
non-conformant writer can still corrupt an onboarded table — inherent to
external storage; our guarantee is scoped to conformant clients and must be a
documented contract.  **Status:** Proposed; core assumption under prototype test.

### D2 — Fencing / enforcement mechanism
**Context:** once onboarded, conformant clients must not write `_delta_log`
directly.

| Option | Pros | Cons |
|---|---|---|
| **A. Delta `catalogOwned` table feature** (client-side protocol enforcement) *(recommended v1)* | Standard Delta mechanism; cross-engine; no new UC infra | Only conformant clients honor it; old clients ignore it |
| **B. Server-side fencing tokens / commit leases tied to vended write creds** | Server-enforced; revocable; defense-in-depth | Real infra; credential-model change; **cannot** stop a client using its *own* bucket credentials |
| **C. Storage-level exclusive locking** | Strongest | Not portable across clouds; not how Delta works; heavy |

**Recommendation:** **A** for v1 (matches Delta's own model). Note **B** as
defense-in-depth *only where UC vends the write credentials* — since external
writers often bring their own storage credentials, server-side fencing can never
be complete, which is exactly why A (a protocol-level contract) is the realistic
guarantee.  **Status:** Proposed.

### D3 — Coordination-state representation
**Context:** need to mark an external table as catalog-coordinated (onboarded),
possibly before its first commit.

| Option | Pros | Cons |
|---|---|---|
| **A. Reserved table property** (e.g. `delta.catalogOwned=true` in `uc_properties`) | No schema migration; property plumbing exists; visible to clients | Free-form → weak invariant; easy to set/unset by accident; couples behavior to a magic string |
| **B. Typed column on `TableInfoDAO`** (e.g. `coordination_mode` enum) *(recommended for production)* | Strong typed invariant; queryable; explicit migration | Schema migration; touches DAO/model; more code |
| **C. Infer from presence of rows in `uc_delta_commits`** | No new state | Conflates "has commits" with "is coordinated"; no representation for onboarded-but-not-yet-committed; brittle |

**Recommendation:** **B** for production; **A** is acceptable for the prototype
to move fast. Final call pending the prototype's read on which is least
invasive.  **Status:** Pending prototype.

### D4 — Reuse commit machinery vs. a separate external path
**Context:** Phase 2 opens the gate; how much of `postCommitCore` do we reuse?

| Option | Pros | Cons |
|---|---|---|
| **A. Relax the managed-only gates; reuse `postCommitCore`/lock/backfill unchanged** *(recommended)* | Minimal code; single proven path; least drift | Risk that hidden managed-only assumptions leak (staging-URI equality `DeltaCommitRepository.java:1016-1022`, etag/uuid, location derivation) |
| **B. Fork a parallel external commit path** | Isolates external logic; zero risk to managed path | Duplicated logic; two paths to maintain; divergence bugs |

**Recommendation:** **A**, contingent on the prototype showing the hidden
assumptions are few and localized. If it finds deep coupling, move toward
*shared core with small explicit branches* — not a full fork.  **Status:**
Pending prototype (this is a primary thing the prototype is hunting).

### D5 — Credential vending for external write
**Context:** Azure/GCP external-location vending is unimplemented
(`AzureCredentialVendor.java:20`, `GcpCredentialVendor.java:37`); AWS has a
per-bucket fallback (`AwsCredentialVendor.java:127`).

| Option | Pros | Cons |
|---|---|---|
| **A. Implement per-cloud external-location vending (Azure/GCP) + reuse AWS** | Correct per-location least-privilege scoping; consistent model | Real per-cloud work |
| **B. Reuse path-credentials / per-bucket config** | Less new code; AWS already has it | Coarser (bucket vs. location) scoping; inconsistent across clouds; weaker least-privilege |
| **C. v1 AWS/local only; defer Azure/GCP** *(recommended for scope)* | Unblocks end-to-end where vending already exists; smaller v1 | Partial cloud coverage until A lands |

**Recommendation:** **C** for v1 rollout scope, then **A** per cloud; **B** only
as an interim on AWS. Ties to open question Q2.  **Status:** Proposed.

### D6 — Onboarding validation strictness
**Context:** the external path may already contain a `_delta_log`; today
registration ignores it (`TableCli.java:173` `TODO confirm the schema`).

| Option | Pros | Cons |
|---|---|---|
| **A. Strict — validate protocol/version/schema before UC takes over; reject incompatible** *(recommended)* | Prevents adopting a table UC can't safely coordinate; closes the known TODO | More validation code; may reject edge tables |
| **B. Lenient — adopt whatever is there** | Easy | Risks adopting unknown table features → later corruption; this *is* today's unsafe behavior |

**Recommendation:** **A.** This is a safety gate, not a convenience.  **Status:**
Proposed.

### D7 — One-way vs. reversible onboarding
| Option | Pros | Cons |
|---|---|---|
| **A. One-way (v1)** *(recommended)* | Simple invariants; no ambiguous "half-managed" state | No exit; user is committed once onboarded |
| **B. Reversible (un-onboard)** | Flexibility | Must drain in-flight coordinated commits and safely remove the `catalogOwned` feature; race-prone |

**Recommendation:** **A** for v1; design **B** later if there's real demand.
**Status:** Proposed.

### D8 — Rollout gating
| Option | Pros | Cons |
|---|---|---|
| **A. Global server feature flag** (reuse `checkManagedTableEnabled` pattern), off by default *(recommended)* | Matches existing pattern; simple kill-switch | Coarse (all-or-nothing per server) |
| **B. Per-table / per-catalog opt-in on top of the flag** | Gradual canarying | More config surface |

**Recommendation:** **A** as the kill-switch; the per-table onboarding opt-in
from D1 already provides natural granularity, so B is largely redundant for v1.
**Status:** Proposed.

### D9 — Vacuum / cleanup ownership
**Context:** external drop is metadata-only today (`TableRepository.java:879`);
cloud delete is a no-op for S3/GCS/ABFS (`FileOperations.java:26`).

| Option | Pros | Cons |
|---|---|---|
| **A. UC owns vacuum for coordinated external tables** | Consistent lifecycle; UC knows commit history | UC needs delete creds to a user-owned path; deleting user data is sensitive; cloud delete is currently a no-op |
| **B. Client owns vacuum** *(recommended v1)* | User keeps control of their storage; matches "external = user owns the path"; matches today's metadata-only drop | UC commit log and storage can drift on cleanup |
| **C. No vacuum in v1; document** | Avoids the hard part | Unbounded commit/log growth |

**Recommendation:** **B** for v1 — UC coordinates commits but does not delete the
user's data — with the drift documented; revisit **A** if drift becomes a real
problem.  **Status:** Proposed.

### D10 — Scope of the advertised-but-unimplemented endpoints
**Context:** `/config` advertises `deleteTable`/`renameTable`/`reportMetrics`
that `DeltaApiService` does not implement (`DeltaApiService.java:60-73`).

| Option | Pros | Cons |
|---|---|---|
| **A. Defer (non-goal), track separately** *(recommended)* | Keeps this project focused on coordination | `/config` keeps advertising endpoints that 404 → misleads clients |
| **B. Implement them as part of this work** | Completes the surface | Scope creep unrelated to external coordination |

**Recommendation:** **A**, but *stop advertising what isn't implemented* (trim
the `/config` `ENDPOINTS` list) so clients aren't misled — a cheap correctness
fix independent of the rest.  **Status:** Proposed.

---

## 6. Detailed design (phased)

Each phase is independently shippable and testable and lands as its own PR
(against your fork until upstream PRs are approved).

- **Phase 0 — This design + protocol addendum.** Agree D1/D2; extend
  `ManagedTablesSpec.md` (managed-only today, `:84`) with a normative
  catalog-managed-external section.
- **Phase 1 — State (D3) + onboarding transition + surface commits on load**
  (relax `TableRepository.java:406`, `:261`).
- **Phase 2 — Open the commit gate (D4).** Replace `requireManaged`
  (`DeltaCommitRepository.validateTable:980`, `DeltaUpdateTableMapper.java:564`,
  `:624`) with a "coordination-enabled" predicate; resolve the staging-URI
  assumption (`DeltaCommitRepository.java:1016-1022`).
- **Phase 3 — Credential vending (D5).**
- **Phase 4 — Safety/fencing/lifecycle (D2, D6, D9).**
- **Phase 5 — Client wiring.** Route external catalog-managed create/commit
  through the Delta API instead of `buildForPath(PATH_CREATE_TABLE)`
  (`UCSingleCatalog.scala:339`; managed ref `:121`;
  `UCDeltaGenericCredentialFetcher.java:37`).
- **Phase 6 — Test matrix (D8 flag) + rollout.** Parameterize on table type;
  external coverage today stops at create/load/snapshot-update and asserting
  `add-commit` is rejected (`SdkUpdateTableTest.java:768`).

## 7. Authorization

External create proves authorization, not exclusivity
(`AuthorizeExpressions.java:65`, `TemporaryPathCredentialsService.java:101`).
Onboarding should require at least the external-location privilege that
write-credential vending requires. **Tradeoff:** reusing that privilege keeps
the model simple and consistent, but is coarser than a dedicated
"onboard-to-coordination" privilege; a dedicated privilege is more precise but
adds surface. Recommendation: reuse for v1. Exact call pending prototype.

## 8. Risks

- **Split-brain (top):** non-conformant writer mutates `_delta_log` post-onboarding
  → DB log diverges. Mitigated (not eliminated) by D1/D2 + documented contract.
- **Cross-cloud commit-file atomicity** for arbitrary external paths.
- **Credential blast radius** for external write.
- **Hidden managed-only coupling** beyond the four known gates — what the
  prototype is hunting (feeds D4).

## 9. Open questions

1. Is one-way onboarding (D7-A) acceptable, or must external tables stay dual-writable?
2. v1 cloud scope — AWS/local first (D5-C)?
3. Endpoints (D10) — trim `/config` now, implement later?
4. State representation (D3) — property vs. column?
5. Onboarding privilege (§7) — reuse external-location write privilege?

## 10. Testing strategy

SDK-level end-to-end tests mirroring `SdkUpdateTableTest`, parameterized on table
type: onboarding, normal (+1), conflict (`<= last` → `COMMIT_VERSION_CONFLICT`;
gap → `INVALID_ARGUMENT`), backfill, per-cloud credentials; two-writer race on
the `FOR UPDATE` lock; negative test for an incompatible pre-existing
`_delta_log` at onboarding.

## 11. Rollout

Feature-flagged (D8-A), off by default; AWS/local first (D5-C); promote per
cloud as vending lands; no change to existing managed or external-snapshot
behavior when off.

---

## 12. Prototype Findings (living — updated from `uc-external-delta/prototype`)

> Each entry: what we tried, what happened, file:line evidence, and the design
> decision (D#) it moves.

_(Pending first prototype run.)_

## 13. Changelog

- 2026-07-02: Added **Design Decisions & Tradeoffs** (§5, D1–D10): every decision
  now carries options + pros/cons + recommendation + status. Reworked the phases
  to reference the decisions. Prototype not yet reported.
- 2026-07-02: Initial draft from investigation (claude_code + codex read-only
  explores).
