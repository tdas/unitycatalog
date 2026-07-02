# Design: Extending the UC Delta API to External Delta Tables

- **Status:** Draft (in progress — being validated against an end-to-end prototype)
- **Author:** tdas@databricks.com
- **Created:** 2026-07-02
- **Related:** `spec/protocols/ManagedTablesSpec.md` (UC Delta API v1.0, normative for managed tables), `api/delta.yaml`

> This document is a living design. A companion prototype (branch
> `uc-external-delta/prototype`) is being built specifically to surface issues
> that change the design. The **Prototype Findings** section at the bottom is
> updated as the prototype teaches us things; earlier sections are revised in
> place when a finding invalidates an assumption. Look at the **Changelog** to
> see what the prototype has moved.

---

## 1. Summary

The UC Delta API (`/api/2.1/unity-catalog/delta/v1`, implemented by
`DeltaApiService`) already lets Delta clients create, load, and fetch
credentials for **both** managed and external Delta tables. The one thing it
does **not** do for external tables is **commit coordination** — the CCv2
"catalog-managed commits" machinery is deliberately fenced to `MANAGED` tables.

This project opens that gate **safely**. The work is small in code surface and
large in policy: most of the commit-coordination machinery is already
type-agnostic and reusable; the hard part is defining an **ownership model**
that lets UC become the commit coordinator for a storage path it does not
exclusively own.

## 2. Background and current state

### 2.1 What the UC Delta API is today

- Spec: `api/delta.yaml`, title *"UC Delta API"*, mounted at
  `/api/2.1/unity-catalog/delta/v1`; the normative protocol is
  `spec/protocols/ManagedTablesSpec.md` (v1.0), which states it is
  *"normative for managed tables only"* even though *"the same API family also
  serves external Delta tables"* (`ManagedTablesSpec.md:84`).
- Primary handler: `DeltaApiService`. It implements 8 routes today
  (`getConfig`, `createStagingTable`, `createTable`, `loadTable`, `tableExists`,
  `updateTable`, `getTableCredentials`, `getStagingTableCredentials`), while
  `/config` advertises 12 endpoints (`DeltaApiService.java:60-73`) — so
  `deleteTable`, `renameTable`, `reportMetrics`, and the `/delta/v1` form of
  `temporary-path-credentials` are advertised but not yet implemented here.
- A legacy commit API (`DeltaCommitsService`, `/delta/preview/commits`) still
  exists and shares the same persistence.

### 2.2 What external Delta tables can and cannot do today

| Capability | External support | Evidence |
|---|---|---|
| `createTable` (external supplies `storageLocation`) | Yes | `DeltaApiService.java:202`, `DeltaCreateTableMapper.java:70`, external branch `TableRepository.java:657` |
| `loadTable` / `tableExists` | Yes, but **UC commits are not surfaced for external** | `TableRepository.java:389-416`, gate `:406`, doc `:261` |
| `getTableCredentials` | Yes (type-agnostic) | `DeltaApiService.java:257` |
| `updateTable` → `update-metadata-snapshot-version` | **External-only** action | `DeltaUpdateTableMapper.java:524` |
| `updateTable` → `add-commit` / `set-latest-backfilled-version` | **Managed-only** (blocked) | `DeltaUpdateTableMapper.java:624`; `DeltaCommitRepository.validateTable:980` ("Only managed tables are supported for Delta commits") |

So external tables today are **snapshot-registered**: UC tracks a metadata
snapshot version (`update-metadata-snapshot-version`) but is not the commit
coordinator. Managed tables are **coordinated**: after the first (onboarding)
commit, *"Unity Catalog becomes the commit coordinator for the table"*
(`DeltaCommitRepository.java:323-336`).

### 2.3 The machinery we get to reuse

The commit path is nearly type-agnostic once the gate opens:

- Pessimistic `SELECT ... FOR UPDATE` serialization + optimistic
  `assert-etag` / `assert-table-uuid` checks (`TableRepository.java:363-378`).
- `postCommitCore` with onboarding / normal / backfill handlers
  (`DeltaCommitRepository.java:270-514`); version-conflict enforcement
  `newVersion == last + 1`.
- Staged-commit-pointer table `uc_delta_commits` (`DeltaCommitDAO`) — UC stores
  commit **metadata/pointers**, not commit contents (`ManagedTablesSpec.md:82`).
- Backfill lifecycle and `latestBackfilledVersion` bookkeeping.

## 3. Problem statement

Make the UC Delta API able to **coordinate commits** for external Delta tables
— i.e., let UC be the authoritative commit coordinator (CCv2) for a table whose
data lives at a user-owned external path — without corrupting tables that have
writers UC does not control.

## 4. Goals / non-goals

**Goals**
- External Delta tables can opt into UC commit coordination and perform
  coordinated commits (onboarding, normal, conflict, backfill) through the
  Delta API.
- `loadTable` surfaces UC commit state for coordinated external tables.
- Read/write credential vending works for external locations across clouds.
- A clear, enforceable ownership model that prevents split-brain.

**Non-goals (initially)**
- Implementing the still-missing Delta API endpoints (`deleteTable`,
  `renameTable`, `reportMetrics`) — tracked separately.
- Automatic bidirectional (dual-writer) tables. We target a one-way transition
  into catalog coordination.
- Vacuum/GC redesign beyond defining ownership boundaries.

## 5. Key design decision — the ownership model

**This is the crux.** UC's DB commit log is authoritative only if UC is the
sole writer of `_delta_log`. For external paths that is not guaranteed
(`DeltaCommitRepository.java:307`, codex risk notes).

**Decision (proposed): opt-in, one-way onboarding to catalog-managed commits,
enforced by the Delta `catalogOwned` table feature.**

- An external table is **onboarded** to UC coordination via an explicit
  transition (a UC-side flag + writing the `catalogOwned` table feature into the
  table's protocol).
- After onboarding, **conformant clients must commit through UC**; direct
  `_delta_log` writes by conformant clients are refused by the protocol feature.
- The transition is **one-way** for v1 (no supported "un-onboard" that keeps
  coordination guarantees).
- Onboarding **validates the existing `_delta_log`** (protocol/version) before
  UC takes over — closing today's gap where external registration ignores a
  pre-existing log (`TableCli.java:173` `TODO confirm the schema`,
  `CliTableCreationTest.java:94`).

**Why not "UC proxies commits for any external path":** it provides no
protection against non-conformant / out-of-band writers and cannot make the DB
log authoritative. Rejected as unsafe.

**Residual risk we accept:** a *non-conformant* writer that ignores the
`catalogOwned` feature can still corrupt the table. That is inherent to
external storage; our guarantee is scoped to conformant clients. This must be
documented as a user-facing contract.

## 6. Detailed design (phased)

Each phase is independently shippable and testable, and lands as its own PR.

### Phase 0 — This design + protocol addendum
Agree the ownership model; extend `ManagedTablesSpec.md` (currently managed-only,
`:84`) with a normative "catalog-managed external tables" section.

### Phase 1 — State: "external, catalog-coordinated"
- Introduce a per-table state distinguishing external-snapshot-only (today)
  from external-catalog-coordinated. Candidate representation: a reserved UC
  property (e.g. `delta.catalogOwned` / a UC-internal column) — final choice
  pending prototype.
- Add the onboarding transition (create/alter path sets the flag).
- Relax `loadTable` to surface commits based on a **"coordination-enabled"**
  predicate rather than `type == MANAGED` (`TableRepository.java:406`, `:261`).

### Phase 2 — Open the commit gate (core)
- Replace `requireManaged` gates with a "commit-coordination enabled" predicate:
  `DeltaCommitRepository.validateTable:980`, `DeltaUpdateTableMapper.java:564`
  and `:624`.
- Reuse `postCommitCore`, the row lock, version/backfill logic unchanged.
- Resolve staging-URI assumptions on the legacy path: `validateTableForCommit`
  enforces table-URI equality against a managed staging URI
  (`DeltaCommitRepository.java:1016-1022`); external tables use the registered
  `storageLocation`. (Prototype to confirm exact break.)

### Phase 3 — Credential vending for external write
- Implement the missing external-location credential vending for Azure and GCP
  (`AzureCredentialVendor.java:20`, `GcpCredentialVendor.java:37` are
  unimplemented today); AWS falls back to per-bucket config
  (`AwsCredentialVendor.java:127`).
- Ensure `READ_WRITE` scoping resolves per external location
  (`ExternalLocationUtils.java:267`) and wire external-location privilege to
  write-credential issuance.

### Phase 4 — Safety, fencing, lifecycle
- Onboarding-time `_delta_log` validation (protocol/version compatibility).
- Write + enforce the `catalogOwned` protocol feature so conformant clients
  cannot bypass the coordinator post-onboarding.
- Define cleanup/vacuum ownership for coordinated external tables (external drop
  is metadata-only today, `TableRepository.java:879`; cloud delete is a no-op
  for S3/GCS/ABFS, `FileOperations.java:26`).

### Phase 5 — Client wiring
- Route external catalog-managed create/commit through the Delta API instead of
  `buildForPath(PATH_CREATE_TABLE)` (`UCSingleCatalog.scala:339`; managed path
  reference `:121`); credential-fetcher plumbing
  (`UCDeltaGenericCredentialFetcher.java:37`).

### Phase 6 — Test matrix & rollout
- Parameterize Delta-API tests on table type; today external coverage stops at
  create/load/snapshot-update and asserting `add-commit` is rejected
  (`SdkUpdateTableTest.java:768`).
- Add external coordinated-commit tests: onboarding, normal, conflict, backfill,
  cross-cloud credentials.
- Gate behind a feature flag reusing the `checkManagedTableEnabled` /
  `checkDeltaApiOnlyEnabled` pattern; incremental rollout.

## 7. Authorization

External create today proves authorization (no data-securable overlap + external
-location privilege), not exclusivity (`AuthorizeExpressions.java:65`,
`TemporaryPathCredentialsService.java:101`). Onboarding to coordination should
require at least the external-location privilege that write-credential vending
requires; exact privilege TBD with the prototype.

## 8. Risks

- **Split-brain (top risk):** a non-conformant writer mutates `_delta_log`
  after onboarding → DB log diverges from storage. Mitigated (not eliminated) by
  the protocol feature + client conformance; documented as a contract.
- **Cross-cloud commit-file atomicity** for arbitrary external paths.
- **Credential blast radius** for external write.
- **Hidden managed-only coupling** beyond the four known gates — the prototype
  exists to find these.

## 9. Open questions

1. Is one-way `catalogOwned` onboarding acceptable, or must external tables stay
   dual-writable?
2. v1 cloud scope — AWS-only first (Azure/GCP external vending is unimplemented)?
3. Do the advertised-but-unimplemented endpoints come along or stay out of scope?
4. Representation of the coordination flag — reserved property vs. schema column?
5. Onboarding privilege model — reuse external-location write privilege?

## 10. Testing strategy

- SDK-level end-to-end tests mirroring `SdkUpdateTableTest`, parameterized on
  table type.
- Coordinated-commit scenarios: onboarding, normal (+1), conflict (`<= last` →
  `COMMIT_VERSION_CONFLICT`; gap → `INVALID_ARGUMENT`), backfill, credential
  vending per cloud.
- Concurrency: two writers racing the `FOR UPDATE` lock.
- Negative: pre-existing incompatible `_delta_log` at onboarding.

## 11. Rollout

Feature-flagged, off by default. AWS/local first. Promote per-cloud as vending
lands. No change to existing managed or external-snapshot behavior when the flag
is off.

---

## 12. Prototype Findings (living — updated from `uc-external-delta/prototype`)

> Populated as the prototype runs. Each entry: what we tried, what happened,
> file:line evidence, and the design change it forces.

_(Pending first prototype run.)_

## 13. Changelog

- 2026-07-02: Initial draft from investigation (claude_code + codex read-only
  explores). Prototype not yet run.
