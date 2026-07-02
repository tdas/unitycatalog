# PROTOTYPE — Findings: Catalog-managed commit coordination for EXTERNAL Delta tables

> **THROWAWAY SPIKE. Not for merge.** This branch (`uc-external-delta/prototype`, based on
> `main@25a5669`) exists only to surface design implications for extending catalog-managed commit
> coordination (CCv2 `add-commit` / `set-latest-backfilled-version`) from MANAGED to EXTERNAL Delta
> tables. Everything is behind a default-OFF feature flag. Correctness of *learning* was prioritized
> over production polish. The primary deliverable is the "Design Implications" list below.

## What this spike actually did

- Added a default-OFF server flag `server.external-delta.commit-coordination.enabled`
  (`ServerProperties.EXTERNAL_DELTA_COMMIT_COORDINATION_ENABLED`).
- Chose the onboarding marker: a reserved UC table property
  `io.unitycatalog.externalCommitCoordination = "true"`
  (`DeltaConsts.TableProperties.EXTERNAL_COMMIT_COORDINATION_ENABLED`).
- Relaxed the four known managed-only gates so the **Delta update endpoint**
  (`POST /delta/v1/.../tables/{table}`) accepts `add-commit` / `set-latest-backfilled-version`, and
  `loadTable` surfaces commits, for an onboarded EXTERNAL Delta table — reusing `postCommitCore`,
  the `SELECT..FOR UPDATE` lock, the version-conflict rule, and the `uc_delta_commits` DAO
  **unchanged**.
- Wrote one end-to-end SDK integration test
  (`server/.../sdk/delta/SdkExternalCommitCoordinationTest.java`) run with the external flag ON and
  the MANAGED flag **OFF**.

### Worked end-to-end (real, not stubbed)
- Create EXTERNAL Delta table on a local-FS temp dir via the Delta REST create endpoint.
- Onboard via `set-properties` (reserved marker).
- Onboarding commit (v1) → normal (+1) commit (v2) → conflicting replay of v2 →
  `COMMIT_VERSION_CONFLICT`; and the `+1` gap rule (`v4` while on `v2`) → `INVALID_ARGUMENT`.
- `loadTable` surfaces both coordinated commits and `latestTableVersion=2` for the external table.
- `set-latest-backfilled-version` (v1) then re-read → only v2 unbackfilled.
- Temporary-credential vend for the external local-FS path via `getTableCredentials` → one
  storage-credential scoped to the `file:` prefix (empty creds for FILE scheme).
- **All of the above with `server.managed-table.enabled=false`**, proving the external path is
  independent of the MANAGED-table flag.

### Stubbed / faked / deliberately not done
- **Onboarding is a bare, unauthenticated, reversible property write** — no reconciliation with the
  physical `_delta_log`, no one-way transition, no `catalogOwned` writer-feature check. (See #4, #5.)
- **No physical filesystem I/O / no real Delta log.** UC only records commit *pointers*; the spike
  never writes or reads a `_delta_log`. The onboarding commit's version is taken on faith.
- **Local FS only.** Cloud credential vending for external locations (Azure/GCP) remains
  unimplemented upstream, so real-cloud external coordination is out of scope here (see #7).
- **UC REST `add-commit`/`getCommits` paths left MANAGED-only on purpose** (see #6). Only the Delta
  update endpoint was opened up.
- Authorization is disabled in the test harness (as in all existing SDK tests).

## Test result

**GREEN.** `sbt "server/testOnly io.unitycatalog.server.sdk.delta.SdkExternalCommitCoordinationTest"`
→ `Passed: Total 1, Failed 0, Errors 0, Passed 1` (5.96s), `[success]`. The whole end-to-end flow
(create external → onboard → onboarding commit v1 → normal commit v2 → conflict → loadTable surfaces
[v1,v2] with `latestTableVersion=2` → backfill v1 → creds vend) ran with the external flag ON and the
MANAGED-table flag **OFF**.

**No-behavior-change-when-OFF** verified: the pre-existing `SdkUpdateTableTest` (whose
`add-commit on EXTERNAL rejected` case runs with the external flag at its default OFF) and
`ServerPropertiesTest` (default-property validation) both still pass — the relaxed gate's rejection
message still contains `"MANAGED"`, so the existing assertion holds.

---

## Design Implications

Each item: **(a)** what I tried · **(b)** what happened · **(c)** file:line evidence · **(d)** how
it constrains the design.

> Line numbers for the three gates this spike *relaxed* (#1 `requireManaged`, #2 the
> `checkManagedTableEnabled()` call, #3 the `loadTable` surfacing branch) refer to their **pre-diff**
> positions on `main@25a5669`; `git diff` shows the exact edits. All other citations are to
> unmodified code and are current.

### 1. Gate #1 — the mapper's `requireManaged` is the real commit fence on the Delta path
- **(a)** Send `add-commit` for an EXTERNAL table through `POST /delta/v1/.../tables/{table}`.
- **(b)** Rejected with `"...require a MANAGED Delta table."` before any commit logic runs. This is
  the gate that actually blocks external CCv2 commits on the Delta update endpoint (not
  `validateTable`).
- **(c)** `server/.../service/delta/DeltaUpdateTableMapper.java:624` (orig `requireManaged`), called
  from `prepareCommitAndBackfill` at `:570`.
- **(d)** Relaxing must happen here, and this method sees the DAO **and** the post-apply
  `MutablePropertyMap`, so it's the natural place to enforce "type == EXTERNAL AND flag on AND
  onboarding marker present". Because it reads the *post-apply* property map, onboarding + first
  commit can legally ride in a single request. The design should keep the commit-eligibility
  decision here (one place, has all the state), not scatter it.

### 2. Gate #2 — external commits are silently coupled to the MANAGED-table flag (hidden gate beyond the 4 known)
- **(a)** Reach the commit executor for an external table with `server.managed-table.enabled=false`.
- **(b)** `applyCommitAndBackfillInSession` calls `serverProperties.checkManagedTableEnabled()`
  unconditionally, which throws `"MANAGED table ... currently disabled"` — an external commit fails
  purely because MANAGED tables are off, which is nonsensical for an external-only deployment.
- **(c)** `server/.../persist/DeltaCommitRepository.java:421`.
- **(d)** This is a coupling the "4 known gates" list did not call out as a *flag* dependency.
  External coordination needs its **own** flag, independent of `MANAGED_TABLE_ENABLED`. The spike
  proves the independence by running green with MANAGED off. Design: any shared commit-executor
  entrypoint must branch its feature-gate by table type, not assume the managed flag.

### 3. Gate #3 — `loadTable` silently omits commits for external tables
- **(a)** After committing v1/v2 to an onboarded external table, call `loadTable`.
- **(b)** Without relaxation, the response carries **no** `commits` and a null `latestTableVersion`
  because the surfacing branch is `type == MANAGED` only — the client would never learn the
  catalog-tracked version and CCv2 reads would break.
- **(c)** `server/.../persist/TableRepository.java:406-411` (`buildLoadTableResponse`).
- **(d)** The read path must mirror the write path's eligibility exactly (type + flag + onboarding
  marker), or onboarded external tables become write-only. The doc at `TableRepository.java:261`
  ("empty for external tables") also becomes stale and must be updated.

### 4. Onboarding representation — a property is the cheapest thing that works, and that's the problem
- **(a)** Represent "catalog-coordinated" as a reserved table property set via `set-properties`.
- **(b)** Works end-to-end with zero schema change, but the marker is a **free-form, mutable,
  client-writable** property: a client can set it, unset it (`remove-properties`), or spoof it in
  the same request as the first commit. Nothing makes onboarding a guarded, one-way transition.
- **(c)** Marker constant `DeltaConsts.java` (`EXTERNAL_COMMIT_COORDINATION_ENABLED`); it flows
  through the ordinary property store (`MutablePropertyMap`) and the mutable `set-properties` /
  `remove-properties` actions (`DeltaUpdateTableMapper.java:394-406`). No privileged check anywhere.
- **(d)** For the real design, onboarding must be an explicit, authorized, ideally **one-way** state
  transition — a dedicated endpoint or a protected DAO column — not a property any writer can toggle.
  It should also bind to the Delta `catalogOwned` writer feature in the physical log (see #5) so the
  catalog and the table agree on who coordinates. A property marker cannot express "you may not
  un-onboard while there are unbackfilled commits", which is a split-brain trap.

### 5. No reconciliation with the physical `_delta_log` — onboarding-version is taken on faith (correctness/split-brain hole)
- **(a)** Onboard, then send an onboarding commit at version **1** (arbitrary).
- **(b)** Accepted unconditionally. `handleOnboardingCommit` just persists whatever version the
  client sends; UC never reads the real `_delta_log`, so it cannot tell that the physical table may
  actually be at v50. A misbehaving/racing client can seed UC's version counter at any value.
- **(c)** `server/.../persist/DeltaCommitRepository.java:323-336` (`handleOnboardingCommit`) — saves
  `commitInfo` with no storage cross-check. Contrast MANAGED, where UC is source-of-truth from
  create (`TableRepository.java:661-681`, staging → single owner).
- **(d)** The design must reconcile onboarding with the real log tail: read the latest published
  version from storage at onboarding time and require the onboarding commit to match (or fence
  further filesystem-direct writes). For MANAGED there are no prior non-catalog writers; for EXTERNAL
  a pre-existing log with concurrent non-catalog writers is the normal case. This is the single
  biggest correctness gap.

### 6. Two commit endpoints; only the Delta update path is safe for external. `validateTableForCommit` URI-equality is the reason the *other* one is hard
- **(a)** Trace both server entrypoints that write `uc_delta_commits`.
- **(b)** The **UC REST** `postCommit` path enforces `validateTable` (MANAGED-only) **and**
  `validateTableForCommit`, which requires the client-supplied `table_uri` to `.equals()` the stored
  URL after `NormalizedURL` normalization. The **Delta update** path
  (`applyCommitAndBackfillInSession`) deliberately does **not** call `validateTableForCommit` — it
  resolves the table by name+id and has no client `table_uri` to compare — so it sidesteps the
  URI-equality problem entirely.
- **(c)** UC REST: `DeltaCommitRepository.java:255` → `validateTableForCommit` at `:1010-1022`
  (`commitTableUri.equals(tableUri)`), gated by `validateTable` at `:980`. Delta path skips it:
  `:408` comment + `:428` `postCommitCore` call with no URI check.
- **(d)** **Standardize external coordination on the Delta update endpoint** (it's the CCv2 path and
  avoids URI equality). If external support were ever added to the UC REST `postCommit`, the
  URI-equality check is a landmine for external `storageLocation`: external URIs are arbitrary,
  client-supplied at create, and may not round-trip identically through `NormalizedURL` across
  schemes (`s3://`, `abfss://`, trailing-slash, case). The spike avoids this by construction and
  recommends the design do the same. `getCommits` (UC REST) is likewise MANAGED-only
  (`DeltaCommitRepository.java:173`), so the Delta `loadTable` is the external read path.

### 7. Credential scoping — trivial for local FS, a real gap for cloud external paths
- **(a)** Vend temp credentials for the external table via the Delta credentials endpoint.
- **(b)** Works with no type gate: `getTableCredentials` resolves the storage location by name and
  vends; for the `FILE` scheme `CloudCredentialVendor` returns **empty** credentials, so local FS
  "just works". The credential path is already type-agnostic.
- **(c)** `DeltaApiService.java:254-266` (no managed gate) → `StorageCredentialVendor.java:41-54`
  (looks up an external-location credential for the path) → `CloudCredentialVendor.java:40-42`
  (`FILE, NULL -> new TemporaryCredentials()`).
- **(d)** For MANAGED tables the storage root is UC-owned and per-metastore; for EXTERNAL the path is
  arbitrary and **must map to a registered ExternalLocation + credential**
  (`externalLocationUtils.getExternalLocationCredentialDaoForPath`). Azure/GCP external-location
  vending is unimplemented upstream, so real-cloud external commit coordination is blocked on that
  work, not on the commit machinery. Credential scoping is the same on the commit *pointer* path
  (none needed) but becomes load-bearing for actual data reads/writes on cloud.

### 8. Concurrency, locking, etag/uuid requirements — reused cleanly, but only fence *catalog* writers
- **(a)** Rely on the existing `SELECT..FOR UPDATE` + version-conflict rule for two concurrent
  external commits; use `assert-table-uuid` / `assert-etag` unchanged.
- **(b)** All type-agnostic and correct for external: the row lock serializes updates; the
  `newVersion == last+1` rule and `COMMIT_VERSION_CONFLICT` fire identically; the etag is derived
  from `updatedAt` and data-only commits don't advance it (so the test can reuse one etag across
  data commits, exactly like MANAGED).
- **(c)** Lock: `TableRepository.java:363-378` (`PESSIMISTIC_WRITE`). Conflict rule:
  `DeltaCommitRepository.java:478-489`. Requirements: `DeltaUpdateTableMapper.java:297-330`
  (`checkAssertTableUuid`, `checkAssertEtag`, `computeEtag`).
- **(d)** These need **no change** for external — a genuine reuse win. **Caveat:** they fence only
  writers that go *through UC*. A non-catalog writer appending directly to the external `_delta_log`
  is not fenced, so UC's version counter can diverge from the physical log (split-brain). The Delta
  `catalogOwned` feature is what fences direct writers in the real protocol; the property marker
  does not. The documented ms-precision `assert-etag` weakness
  (`DeltaUpdateTableMapper.java:314-325`) applies equally and is acceptable (etag is an optional
  optimization; the version check is authoritative).

### 9. Table deletion does not purge commit pointers for external tables (orphaned-rows / re-create hazard)
- **(a)** Consider deleting an onboarded external table with unbackfilled commits.
- **(b)** `deleteTable` only calls `permanentlyDeleteTableCommits` (and directory delete) for
  MANAGED. An onboarded external table's `uc_delta_commits` rows would be **orphaned** on delete.
- **(c)** `server/.../persist/TableRepository.java:879-888` (the `if MANAGED` block).
- **(d)** The delete/cleanup path must purge `uc_delta_commits` for onboarded external tables too
  (but must **not** delete the external directory — that's user-owned data). Otherwise orphaned
  pointers accumulate and a table re-created at the same UUID could inherit stale commits.

### 10. `delta.lastUpdateVersion` / `delta.lastCommitTimestamp` now have two writers for external tables
- **(a)** A metadata-changing `add-commit` on an onboarded external table.
- **(b)** `prepareCommitAndBackfill` stamps `delta.lastUpdateVersion` / `delta.lastCommitTimestamp`
  from the commit for **any** metadata-changing commit regardless of type — so external tables now
  get these stamped by the commit path, **and** the EXTERNAL-only
  `update-metadata-snapshot-version` action writes the *same two properties*.
- **(c)** Commit-path stamp: `DeltaUpdateTableMapper.java:586-590`. Snapshot action:
  `DeltaUpdateTableMapper.java:536-539` (`applyUpdateSnapshotVersion`, EXTERNAL-only).
- **(d)** Once external tables can both `add-commit` and `update-metadata-snapshot-version`, two
  mechanisms write the same snapshot properties. The design must define which is authoritative and
  prevent the snapshot version from regressing (e.g. monotonicity guard), or the two actions will
  fight. Also note `hasManagedTableMetadataChange()` is named "Managed" but is used
  type-agnostically (`DeltaUpdateTableMapper.java:197`) — rename/reframe to avoid implying
  managed-only.

### 11. The wire protocol and generated clients need no change — external coordination is a server-gating change
- **(a)** Build an SDK test that creates EXTERNAL via Delta REST, commits, loads, vends creds.
- **(b)** Compiled and ran against the **unchanged** generated client:
  `DeltaCreateTableRequest` already supports `tableType=EXTERNAL`; the update request already carries
  `add-commit` / `set-latest-backfilled-version`; `loadTable` already returns `commits` /
  `latestTableVersion`. No `.yaml` or client edits were needed.
- **(c)** Test uses stock client models under `io.unitycatalog.client.delta.model.*`; the only
  client wrinkle was that credentials live on a **separate** generated class
  `DeltaTemporaryCredentialsApi` with `operation` as the **first** parameter
  (`clients/.../delta/api/DeltaTemporaryCredentialsApi.java:170`), not on `DeltaTablesApi`.
- **(d)** External commit coordination is purely a **server-side authorization/gating** feature; the
  Delta REST surface is already type-agnostic. This lowers the blast radius: no wire model changes,
  no client regen coordination. The design can focus on server semantics (onboarding, reconciliation,
  fencing) rather than protocol shape.
