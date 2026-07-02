# Design: Extending the UC Delta API to External Delta Tables

- **Status:** Draft (prototype run + independently cross-reviewed by codex; decisions confirmed/refined — see §13)
- **Author:** tdas@databricks.com
- **Created:** 2026-07-02
- **Related:** `spec/protocols/ManagedTablesSpec.md` (UC Delta API v1.0, normative for managed tables), `api/delta.yaml`

> This is a living design. A companion prototype (branch
> `uc-external-delta/prototype`) was built to surface issues that change the
> design; **it has run** (green end-to-end) and been **independently
> cross-reviewed** (codex) — see **§13** and the **Changelog**. **Every design
> decision is stated with its tradeoffs** — options, pros/cons, recommendation,
> status. The **§7 PR execution plan** LOC/risk numbers are calibrated from the
> prototype.

---

## 1. Summary

The UC Delta API (`/api/2.1/unity-catalog/delta/v1`, `DeltaApiService`) already
lets Delta clients create, load, and fetch credentials for **both** managed and
external Delta tables. The one thing it does **not** do for external tables is
**commit coordination** — the CCv2 "catalog-managed commits" machinery is fenced
to `MANAGED` tables.

This project opens that gate **safely**. The prototype confirmed the code
surface is small and the machinery is largely reusable; the hard parts are all
**policy/correctness**: an **ownership model**, an **authorized onboarding
transition that reconciles with the physical `_delta_log`**, and **fencing** so
UC can be the coordinator for a path it does not exclusively own.

## 2. Background and current state

### 2.1 What the UC Delta API is today

- Spec `api/delta.yaml` (*"UC Delta API"*), mounted at `/delta/v1`; normative
  protocol `spec/protocols/ManagedTablesSpec.md` (*"normative for managed tables
  only"*, `:84`).
- `DeltaApiService` implements 8 routes; `/config` advertises 12
  (`DeltaApiService.java:60-73`) — `deleteTable`/`renameTable`/`reportMetrics`
  advertised but not implemented.
- Legacy commit API `DeltaCommitsService` (`/delta/preview/commits`) shares the
  same persistence.

### 2.2 What external Delta tables can/can't do today

External tables are **snapshot-registered** (`update-metadata-snapshot-version`,
`DeltaUpdateTableMapper.java:524`); managed tables are **coordinated**
(`add-commit`). The managed-only fences: `DeltaUpdateTableMapper` `requireManaged`
(`:624`, the real gate on the Delta path), the load-surfacing branch
(`TableRepository.java:406`), `DeltaCommitRepository.validateTable:980` (UC REST
path), **and a fifth, previously-unlisted gate the prototype found:** an
unconditional `checkManagedTableEnabled()` in `applyCommitAndBackfillInSession`
(`DeltaCommitRepository.java:421`) — see §13/D8.

### 2.3 Machinery we can reuse *(prototype-validated + cross-reviewed)*

The `SELECT ... FOR UPDATE` lock (`TableRepository.java:363-378`), the
`newVersion == last+1` conflict rule (`DeltaCommitRepository.java:478-489`),
`assert-etag`/`assert-table-uuid` (`DeltaUpdateTableMapper.java:297-330`),
`postCommitCore` + onboarding/normal/backfill handlers, and the
`uc_delta_commits` pointer table (`DeltaCommitDAO`) are **already type-agnostic**
and needed **zero** changes in the prototype.

## 3. Problem statement

Let the UC Delta API **coordinate commits** for external Delta tables — UC as the
authoritative CCv2 coordinator for a table whose data lives at a user-owned
external path — without corrupting tables that have writers UC does not control.

## 4. Goals / non-goals

**Goals:** external tables opt into coordinated commits (onboarding, normal,
conflict, backfill); `loadTable` surfaces UC commit state; read/write credential
vending for external locations; an enforceable ownership model that prevents
split-brain.

**Non-goals (v1):** the missing endpoints (`deleteTable`/`renameTable`/
`reportMetrics`); dual-writer tables; a vacuum/GC redesign beyond ownership
boundaries; opening the **UC REST `postCommit`** path to external (we standardize
on the Delta update endpoint — see D4/§13 finding #6).

---

## 5. Design decisions and tradeoffs

`Prototype update` / `Cross-review` lines record what the spike and the
independent review showed.

### D1 — Ownership / coordination model  *(the crux)*
**Context:** UC's DB commit log is authoritative only if UC is the sole writer of
`_delta_log`. External paths may have other writers.

| Option | Pros | Cons |
|---|---|---|
| **A. Status quo — snapshot tracking only** | Zero new risk; shipped | Fails the goal |
| **B. Opt-in, one-way onboarding to catalog-managed commits, enforced by the Delta `catalogOwned` table feature** *(recommended)* | UC authoritative for conformant clients; reuses managed machinery; clear contract; incremental | One-way (D7); client-enforced (non-conformant writers can still corrupt); needs onboarding validation (D6); cross-engine conformance |
| **C. UC proxies commits for any external path, no fencing** | Smallest change | **Unsafe** — DB log can't be authoritative; silent divergence |
| **D. Advisory / reconciling coordination** | Tolerates external writers | UC not authoritative; complex reconciliation; loses CCv2's fast commit-listing |

**Recommendation:** **B.** **Prototype update:** the spike proved the mechanics
work but has *no fencing at all* (findings #5/#8) — reinforcing that B's value is
entirely in the `catalogOwned` fencing + onboarding reconciliation, not the
commit plumbing (which already works). **Status:** Proposed; fencing is the open
build item, not the plumbing.

### D2 — Fencing / enforcement mechanism
| Option | Pros | Cons |
|---|---|---|
| **A. Delta `catalogOwned` table feature** (client-side protocol enforcement) *(recommended v1)* | Standard Delta mechanism; cross-engine; no new infra | Only conformant clients honor it |
| **B. Server-side fencing tokens / commit leases** | Server-enforced; revocable | Real infra; can't stop a client using its own bucket creds |
| **C. Storage-level exclusive locking** | Strongest | Not portable; not how Delta works |

**Recommendation:** **A** for v1. **Prototype update:** finding #8 (confirmed by
cross-review) shows the existing lock/conflict machinery fences only writers
*going through UC*; a direct `_delta_log` writer is unfenced. So `catalogOwned`
(A) is not optional polish — it is the *only* thing that fences direct writers.
**Status:** Proposed.

### D3 — Coordination-state representation
| Option | Pros | Cons |
|---|---|---|
| **A. Reserved table property** (`io.unitycatalog.externalCommitCoordination=true`) | No migration; plumbing exists; visible | Free-form; **client-writable & reversible**; spoofable |
| **B. Typed column / authorized one-way transition** *(recommended)* | Strong invariant; enforceable; queryable | Schema migration; more code |
| **C. Infer from `uc_delta_commits` rows** | No new state | Conflates concerns; no pre-first-commit state |

**Prototype update (decided → B):** the spike used option A and proved it
*functional but unsafe* — the marker rides the ordinary property store and the
mutable `set-properties`/`remove-properties` actions with **no privileged check**
(`DeltaUpdateTableMapper.java:394-406`), so any writer can set, unset, or spoof
it in the same request as the first commit (finding #4). A property cannot
express "you may not un-onboard while unbackfilled commits exist." Onboarding
**must** be an authorized, one-way transition (dedicated action + protected
column), not a free-form property. **Status:** Decided → B.

### D4 — Reuse commit machinery vs. a separate external path
| Option | Pros | Cons |
|---|---|---|
| **A. Relax the gates; reuse `postCommitCore`/lock/backfill unchanged** *(recommended)* | Minimal code; single proven path | Risk of hidden managed-only assumptions |
| **B. Fork a parallel external path** | Isolation | Duplication; drift |

**Prototype update (A confirmed, strengthened):** reuse works — lock, conflict
rule, and etag/uuid are type-agnostic and changed **zero** lines (finding #8).
Two refinements: (1) the real gate to relax is `requireManaged` in
`DeltaUpdateTableMapper` (`:624`, finding #1), **plus a fifth gate** the "4 known"
list missed — an unconditional `checkManagedTableEnabled()` at
`DeltaCommitRepository.java:421` (finding #2). (2) **Standardize external
coordination on the Delta update endpoint**, not the UC REST `postCommit` path,
because the latter enforces `validateTableForCommit` URI-equality
(`DeltaCommitRepository.java:1010-1022`) which is a landmine for arbitrary
external `storageLocation` URIs; the Delta path resolves by name+id and skips it
(finding #6). **Cross-review caveat (finding #12):** keep the commit-eligibility
check (flag **+** onboarding marker) in a *single* mapper entry point — the
prototype's repository layer checks only the flag
(`DeltaCommitRepository.java:427-430`), so production must ensure no internal
call site can bypass the mapper's marker check. **Status:** Decided → A on the
Delta update endpoint.

### D5 — Credential vending for external write
| Option | Pros | Cons |
|---|---|---|
| **A. Per-cloud external-location vending (Azure/GCP) + reuse AWS** | Correct scoping; consistent | Real per-cloud work |
| **B. Reuse path-credentials / per-bucket config** | Less code | Coarser; inconsistent |
| **C. v1 AWS/local only; defer Azure/GCP** *(recommended for scope)* | Unblocks e2e where vending exists | Partial coverage |

**Prototype update:** the credential path is already type-agnostic — local FS
vended fine with **no** type gate (`DeltaApiService.java:254-266` →
`StorageCredentialVendor` → `CloudCredentialVendor` FILE→empty creds, finding
#7). Confirms cloud external coordination is blocked on the **unimplemented
Azure/GCP external-location vending**, not on the commit machinery — so **C** for
v1 is well-founded. **Status:** Proposed (C for v1, then A).

### D6 — Onboarding validation strictness
| Option | Pros | Cons |
|---|---|---|
| **A. Strict — reconcile with the physical `_delta_log`; reject incompatible** *(recommended, now mandatory)* | Prevents adopting a table UC can't safely coordinate | More validation code |
| **B. Lenient — adopt whatever is there** | Easy | Corruption risk (today's behavior) |

**Prototype update (decided → A, mandatory; confirmed by cross-review):** finding
#5 is the **single biggest correctness gap**. `handleOnboardingCommit`
(`DeltaCommitRepository.java:323-336`) persists the client-supplied onboarding
version **on faith** with no storage cross-check — a client can seed UC at v1
while the physical table is at v50. Onboarding **must** read the real published
log tail and require the onboarding version to match (and fence subsequent direct
writers, D2). **Status:** Decided → A (mandatory).

### D7 — One-way vs. reversible onboarding
**Recommendation:** **A. One-way (v1).** **Prototype update:** finding #4 shows a
property marker makes onboarding trivially reversible (`remove-properties`),
which is a split-brain trap; the authorized transition from D3 must enforce
one-way. **Status:** Decided → A.

### D8 — Rollout gating
| Option | Pros | Cons |
|---|---|---|
| **A. Dedicated server feature flag, off by default** *(recommended)* | Simple kill-switch; matches pattern | Coarse |
| **B. Per-table/per-catalog opt-in on top** | Gradual | More config |

**Prototype update (decided → A, independent flag; confirmed by cross-review):**
external coordination was silently coupled to `MANAGED_TABLE_ENABLED` via gate #5
(`DeltaCommitRepository.java:421`). The spike added a **dedicated, independent**
flag `server.external-delta.commit-coordination.enabled` (default OFF) and ran
green with `managed-table.enabled=false`, proving independence. The per-table
onboarding marker (D3) already gives granularity, so B is redundant for v1.
**Status:** Decided → A.

### D9 — Vacuum / cleanup ownership
| Option | Pros | Cons |
|---|---|---|
| **A. UC owns vacuum for coordinated external tables** | Consistent lifecycle | UC deleting user data; cloud delete is a no-op today |
| **B. Client owns vacuum** *(recommended v1)* | User owns their storage | Possible drift |
| **C. No vacuum in v1; document** | Avoids the hard part | Unbounded growth |

**Recommendation:** **B** for v1 (UC coordinates commits, never deletes user
data). **Prototype update — new required cleanup task (finding #9):** independent
of vacuum, `deleteTable` purges `uc_delta_commits` only for MANAGED
(`TableRepository.java:879-888`); onboarded external tables would **orphan**
commit-pointer rows. Cleanup must purge external commit pointers on drop — but
must **not** delete the external directory. **Cross-review refinement:** the
orphan-row leak is confirmed, but the "re-created table at the same UUID inherits
stale commits" concern is overstated — normal external create allocates a *fresh*
UUID (`TableRepository.java:657`), so inheritance only arises under explicit UUID
reuse. **Status:** Proposed (B) + required orphan-purge fix.

### D10 — Scope of advertised-but-unimplemented endpoints
**Recommendation:** **A. Defer**, but trim the `/config` `ENDPOINTS` list so
clients aren't misled by advertised-yet-404 endpoints
(`DeltaApiService.java:60-73`). **Status:** Proposed.

### D11 — Snapshot-version authority *(from prototype finding #10; confirmed by cross-review)*
**Context:** once external tables can do both `add-commit` (stamps
`delta.lastUpdateVersion`/`delta.lastCommitTimestamp`,
`DeltaUpdateTableMapper.java:586-590`) **and** the EXTERNAL-only
`update-metadata-snapshot-version` (writes the same two properties, `:536-539`),
two mechanisms write the same snapshot state.

| Option | Pros | Cons |
|---|---|---|
| **A. Commit path authoritative + monotonicity guard on the snapshot action** *(recommended)* | Single source of truth; no regressions | Small guard logic |
| **B. Snapshot action authoritative** | — | Commits (the real coordination) become secondary |
| **C. Keep both, last-writer-wins** | No work | Version can regress; the two actions fight |

**Recommendation:** **A** — coordinated commit is source of truth; the snapshot
action must not regress the version (monotonicity guard). Also rename
`hasManagedTableMetadataChange()` (`DeltaUpdateTableMapper.java:197`), which is
already used type-agnostically, to avoid implying managed-only. **Status:** Proposed.

---

## 6. Detailed design (phased)

Self-contained, flag-gated PRs against the fork until upstream PRs are approved.

- **Phase 0 — This design + protocol addendum** (D1/D2 normative in
  `ManagedTablesSpec.md`, managed-only today `:84`), and update the Delta API
  descriptions that still say managed-only for `add-commit`/commits
  (`api/delta.yaml:791`, `:1035`) — no structural wire change (finding #11).
- **Phase 1 — Authorized onboarding transition + typed state (D3, D7) + surface
  commits on load** (mirror write eligibility exactly; relax
  `TableRepository.java:406`, fix stale doc `:261`). Include the D11 monotonicity
  guard for snapshot vs commit.
- **Phase 2 — Open the commit gate (D4).** Relax `requireManaged`
  (`DeltaUpdateTableMapper.java:624`, the real gate) **and** decouple the fifth
  gate `checkManagedTableEnabled()` (`DeltaCommitRepository.java:421`) by table
  type behind the independent flag (D8); standardize on the Delta update endpoint
  (leave UC REST `postCommit` MANAGED-only); keep the eligibility check
  centralized (finding #12). Reuse `postCommitCore` unchanged.
- **Phase 3 — Credential vending (D5)** — AWS/local first; Azure/GCP later.
- **Phase 4 — Safety/fencing/lifecycle (D2, D6, D9).** Onboarding `_delta_log`
  reconciliation (the crux correctness item), `catalogOwned` fencing, and
  external commit-pointer purge on drop.
- **Phase 5 — Client wiring.** *Minimal — no structural wire/client changes
  (finding #11).* Route external catalog-managed create/commit through the Delta
  API (`UCSingleCatalog.scala:339`) and enable the connector path.
- **Phase 6 — Test matrix + rollout** (type-parameterized; today external
  coverage stops at asserting `add-commit` is rejected, `SdkUpdateTableTest.java:768`).

## 7. PR execution plan (stacking + LOC estimates)

Git stack of self-contained, **flag-gated-OFF** PRs, each branched from master
with its own tests, rebased in order; branches `uc-external-delta/<step>`; PRs
target the fork until upstream is approved.

**LOC calibrated from the prototype** (prod + test, excl. generated models &
BUILD). The spike's raw gating diff was **~90 lines across 4 source files** — so
the gate-opening (PR4) is small and low-risk; the weight is in **onboarding
reconciliation + fencing (PR6)**, and **client wiring (PR7) is small** because no
structural wire/client changes are needed (finding #11).

| # | Branch (stack step) | Scope (decisions) | Depends on | Est. LOC (prod + test) | Risk |
|---|---|---|---|---|---|
| PR1 | `…/01-design-spec` | This doc + `ManagedTablesSpec.md` addendum + API-description text updates (`delta.yaml:791,1035`) (D1,D2) | master | ~0 code / ~450 doc | Low |
| PR2 | `…/02-flag-and-state` | Independent flag (D8) + **authorized one-way onboarding transition & typed state** (D3,D7); default OFF | master | ~350–550 (250–400 + 100–150) | Med |
| PR3 | `…/03-load-and-snapshot` | `loadTable` surfaces commits for onboarded external (relax `:406`, fix `:261`); D11 monotonicity guard | PR2 | ~300–450 (150–250 + 150–200) | Med |
| PR4 | `…/04-commit-gate` | Relax `requireManaged` (`:624`) + decouple 5th gate (`:421`); centralized eligibility (#12); reuse `postCommitCore`; Delta-update-endpoint only (D4) | PR3 | ~350–600 (150–300 + 200–300) | Med |
| PR5 | `…/05-cred-vending` | External write creds (D5); AWS/local low end, +Azure+GCP high end | PR2 (parallel) | AWS/local ~150–250; +Azure+GCP ~500–800 | Med |
| PR6 | `…/06-onboarding-fencing` | **Onboarding `_delta_log` reconciliation (crux) + `catalogOwned` fencing (D2,D6) + orphan commit-pointer purge (D9)** | PR3, PR4 | ~600–900 (400–600 + 200–300) | **High** |
| PR7 | `…/07-client-wiring` | Route connectors through the Delta API (`UCSingleCatalog:339`); **no structural wire/client changes (finding #11)** | PR4, PR5, PR6 | ~150–300 (100–200 + 50–100) | Low–Med |
| PR8 | `…/08-test-matrix-rollout` | Type-parameterized e2e matrix + rollout | all | ~250–450 (mostly test) | Low–Med |

**Totals (PR2–PR8, excludes docs):** ≈ **2,150–4,050 LOC** (prod + test);
production-only ≈ **1,150–2,450**. Vs. the pre-prototype estimate, PR4 and PR7
came **down** (reuse confirmed; no structural wire changes) and PR6 went **up**
(onboarding reconciliation + fencing is the real work). The remaining wide band
is PR5 cloud scope (D5).

**Stacking DAG** (mostly linear; PR5 parallel):

```
PR1  design + spec        (independent)

master
 └─ PR2  flag + authorized onboarding state (D3,D7,D8)
      ├─ PR3  load-surfacing + snapshot authority (D11)
      │     └─ PR4  commit gate (D4)            [reuse; med risk]
      │           └─ PR6  onboarding reconciliation + fencing + purge (D2,D6,D9)  [crux, high risk]
      │                 └─ PR7  client wiring (minimal; no wire changes)
      │                       └─ PR8  test matrix + rollout
      └─ PR5  external write credential vending (D5)  ──feeds──▶ PR7
```

**Notes.** PR1 merges independently. PR5 is orthogonal, parallel off PR2. The
prototype on `uc-external-delta/prototype` is a throwaway spike, **not** a stack
PR. Don't touch generated BUILD files; each PR self-contained with tests; rebase
the stack in order after any change.

## 8. Authorization

External create proves authorization, not exclusivity
(`AuthorizeExpressions.java:65`, `TemporaryPathCredentialsService.java:101`).
Onboarding (D3) should require at least the external-location privilege that
write-credential vending requires. **Tradeoff:** reuse is simpler/consistent but
coarser than a dedicated "onboard" privilege. Recommendation: reuse for v1.

## 9. Risks

- **Split-brain (top):** non-conformant writer mutates `_delta_log`
  post-onboarding. Mitigated (not eliminated) by D2 fencing + D6 reconciliation +
  documented contract. The prototype has **no** fencing yet (finding #8,
  cross-review confirmed), so this is the primary build risk (PR6).
- **Onboarding version seeding** (finding #5) — mitigated by D6.
- **Cross-cloud commit-file atomicity**; **credential blast radius** (D5).

## 10. Open questions

1. One-way onboarding (D7-A) acceptable? *(Prototype + review support it.)*
2. v1 cloud scope — AWS/local first (D5-C)? *(Prototype supports it.)*
3. Trim `/config` advertised endpoints now (D10)?
4. Onboarding privilege (§8) — reuse external-location write privilege?
5. D11 — confirm commit path is the snapshot-version authority?

## 11. Testing strategy

SDK-level e2e mirroring the prototype's `SdkExternalCommitCoordinationTest`,
parameterized on table type: onboarding, normal (+1), conflict
(`COMMIT_VERSION_CONFLICT`; gap → `INVALID_ARGUMENT`), backfill, per-cloud
credentials; two-writer race on the lock; **negative: onboarding against a
pre-existing/incompatible `_delta_log`** (the D6 reconciliation test);
flag-OFF-no-behavior-change (the prototype already demonstrates this).

## 12. Rollout

Independent flag (D8-A), off by default; AWS/local first (D5-C); promote per
cloud as vending lands; no change to managed or external-snapshot behavior when off.

---

## 13. Prototype Findings (from `uc-external-delta/prototype` @ `9334840`)

Green end-to-end spike: create EXTERNAL Delta table (local FS) → onboard → commit
v1 → v2 → conflict → `loadTable` surfaces `[v1,v2]` → backfill → credential vend,
**all with `managed-table.enabled=false`**. Diff ≈ 90 lines / 4 source files, all
`[PROTOTYPE]`. Full report: `uc-external-delta/prototype:spec/protocols/prototype-findings.md`.

**Cross-review (codex, independent, from a clean `main` worktree): all primary
findings CONFIRMED.** Flag-OFF safety confirmed (rejection still contains
"MANAGED", so `SdkUpdateTableTest.java:768` holds); findings #2/#5/#6/#9/#10
verified against `main`; gating logic sound (onboard-plus-first-commit in one
request works via the post-apply property map). Refinements folded in: #9
(UUID-reuse nuance), #11 (structural-only), and a new #12 (centralize
eligibility).

**Findings → design impact:**

1. **#1** `requireManaged` (`DeltaUpdateTableMapper.java:624`) is the real
   commit gate on the Delta path → relax here (D4).
2. **#2 (new gate; confirmed)** `applyCommitAndBackfillInSession` calls
   `checkManagedTableEnabled()` unconditionally (`DeltaCommitRepository.java:421`)
   → external was coupled to the MANAGED flag; needs an independent flag (D8).
3. **#3** `loadTable` surfaces commits for MANAGED only
   (`TableRepository.java:406-411`; stale doc `:261`) → read path must mirror
   write eligibility (D3/Phase 1).
4. **#4** Property marker is client-writable/reversible/spoofable
   (`DeltaUpdateTableMapper.java:394-406`) → onboarding must be an authorized,
   one-way transition (D3, D7).
5. **#5 (biggest correctness gap; confirmed)** `handleOnboardingCommit`
   (`DeltaCommitRepository.java:323-336`) takes the onboarding version on faith,
   no `_delta_log` cross-check → mandatory reconciliation (D6).
6. **#6 (confirmed)** `validateTableForCommit` URI-equality (`:1010-1022`) lives
   only on the UC REST `postCommit` path; the Delta update path skips it →
   standardize on the Delta update endpoint (D4).
7. **#7** Credential path is type-agnostic; local FS vends with no gate; cloud is
   blocked on unimplemented Azure/GCP external-location vending (D5).
8. **#8 (confirmed)** Lock / conflict / etag-uuid reused unchanged, but fence
   only writers *through UC* → `catalogOwned` fencing required (D2).
9. **#9 (confirmed; refined)** `deleteTable` purges commits for MANAGED only
   (`TableRepository.java:879-888`) → external orphan-purge fix (D9). Cross-review:
   orphan leak real; same-UUID stale-commit *inheritance* only under UUID reuse
   (normal create uses a fresh UUID, `TableRepository.java:657`).
10. **#10 (confirmed)** Two writers to `delta.lastUpdateVersion`/`lastCommitTimestamp`
    (commit path `:586-590` vs snapshot action `:536-539`) → authority +
    monotonicity (D11).
11. **#11 (refined → PARTIAL)** No *structural* wire/client changes — Delta REST
    surface already type-agnostic — but the API descriptions still say
    managed-only (`api/delta.yaml:791`, `:1035`) and must be updated for
    production (Phase 0). Shrinks client wiring (PR7).
12. **#12 (cross-review caveat)** The prototype's repository-layer gate checks
    only the flag, not the onboarding marker (`DeltaCommitRepository.java:427-430`);
    eligibility (flag **+** marker) is enforced only at the mapper. Production
    must centralize the eligibility check so no internal call site can bypass it (D4).

## 14. Changelog

- 2026-07-02: **Cross-review (codex) folded in** — all primary findings CONFIRMED;
  refined #9 (UUID-reuse nuance) and #11 (structural-only wire; API descriptions
  need updating), added #12 (centralize eligibility check); added the caveat to D4.
- 2026-07-02: **Prototype run folded in.** Added §13; revised D3/D6/D7/D8 to
  *Decided*, refined D4/D5/D9, added **D11**; recalibrated §7 (PR4/PR7 down, PR6
  up; total ≈ 2.15k–4.05k).
- 2026-07-02: Added §7 PR execution plan (stacking + LOC).
- 2026-07-02: Added §5 Design Decisions & Tradeoffs (D1–D10).
- 2026-07-02: Initial draft from investigation.
