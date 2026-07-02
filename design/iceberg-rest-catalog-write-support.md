# Design: Iceberg REST Catalog write support (create / load / update) in Unity Catalog

**Status:** Draft — **review-verified** (cross-vendor design review + debate complete) · **Owner:** tdas · **Last updated:** 2026-07-02

> Living document. §5 = per-decision **tradeoffs**; §7 = **authorization + API
> consistency** (grounded); §9.1 = **PR plan + LOC**; §10 = **prototype
> findings**; §11 = **design review outcomes** (two independent vendor reviews +
> a debate round — consensus, resolved disagreements, residual dissent). v2.4
> folds the review must-fixes into the decisions.

---

## 1. Summary

UC (OSS) today serves a **read-only** Iceberg REST Catalog shimmed over Delta
UniForm tables. This adds the **core write ops** — `createTable`, `loadTable`
(read‑write creds), `updateTable`/commit — so standard Iceberg REST clients
(Spark, pyiceberg, Trino) can create and mutate **native Iceberg tables** in UC.

Central bet, from Apache Polaris, **prototype‑validated (§10)** and
**review‑verified (§11)**:

> **UC owns the pointer store, authorization, and credential vending; Iceberg
> core (`CatalogHandlers` + `BaseMetastoreCatalog`) owns table‑metadata
> mechanics.** Caveat surfaced by review: this reuse **leaks at the commit path**
> (retry tuning + `CommitStateUnknown` handling need wrapping `CatalogHandlers`;
> see D1↔D3).

Principles: (1) **reuse over reinvention**; (2) **consistency with the rest of
UC** — authorize, error, paginate, resolve names exactly like the control plane
and Delta catalog (§7).

## 2. Goals / Non‑goals

**Goals (MVP):** `GET /v1/config` (with authz downgrade, §7); `createTable`;
`loadTable` with read‑write creds; `updateTable`/commit with a defined OCC
contract; Iceberg error model incl. **403 shape** (§7.5); **authorization
consistent with UC incl. creator‑OWNER wiring** (§7); real‑client interop proven
by tests.
- **`stage-create` — conditional goal:** design + test the `base==null`
  first‑commit / `assert-create` path in PR3; **demote to non‑goal** if that
  path proves too costly (review flagged it as claimed‑but‑unvalidated).

**Non‑goals (fast‑follow):** `dropTable`/`renameTable`/`registerTable`; views
write API; multi‑table transactions; scan planning; metrics ingestion; full
Azure/GCP external‑location credential closure (D4). MVP may be AWS‑first /
local‑FS‑first.

## 3. Current state (baseline)

- **Server:** Armeria; Iceberg surface in
  `UnityCatalogServer.addIcebergApiServices(...)` under
  `/api/2.1/unity-catalog/iceberg`. **Security is a routing decorator**
  (`routeDecorator().pathPrefix(BASE_PATH)...`) and errors are handled by a
  **server‑wide `GlobalExceptionHandler`** (`UnityCatalogServer.java:328,345`).
- **Read path (implemented):** `service/IcebergRestCatalogService.java` — config,
  namespaces list/load, tableExists, loadTable, listTables; loadView 404 stub;
  reportMetrics no‑op. **It is a plain class — does NOT extend
  `AuthorizedService`** (`:47`), so it currently cannot grant hierarchy privileges.
- **Not implemented:** createTable, updateTable/commit, createNamespace,
  drop/rename. (On `main`, `ENDPOINTS` does **not** advertise create/update and
  the constructor compiles — see §7.6.)
- **Persistence:** Hibernate/JPA, default H2, `hbm2ddl.auto=update`, **no
  migration framework**. `TableInfoDAO` → `uc_tables` has
  `uniform_iceberg_metadata_location` (+ `uniform_iceberg_converted_delta_*`);
  only a **non‑unique** `idx_name`. No `ICEBERG` in `TableType`/`DataSourceFormat`
  (`DataSourceFormat.valueOf` throws on unknown, `TableInfoDAO.java:124`).
- **Pointer writer today:** only the Delta path; the read‑write Delta REST
  catalog (`service/delta/DeltaApiService.java`) is the closest write template
  (incl. `initializeHierarchicalAuthorization` on create, `:216`).
- **Authorization:** SpEL `@AuthorizeExpression` via `UnityAccessDecorator` over
  a hierarchical `Privileges` model on `JCasbinAuthorizer` (fully grounded, §7).
- **Credential vending:** `StorageCredentialVendor` is multi‑cloud, write‑capable
  (`{SELECT, UPDATE}`), used by `temporary-table-credentials` and Delta. **Iceberg
  path pinned read‑only** (`TableConfigService`/`FileIOFactory` hardcode
  `Set.of(SELECT)` `// FIXME`); `SimpleLocalFileIO` cannot write on `main`.
  Azure/GCP external‑location vending `throw UNIMPLEMENTED`; AWS works.

## 4. Reference architecture (Apache Polaris)

Polaris routes Iceberg REST to a vendored fork of `CatalogHandlers` + a
`BaseMetastoreCatalog`/`TableOperations`: `CatalogHandlers.commit()` runs the
canonical loop (`UpdateRequirement.validate` → `MetadataUpdate.applyTo` → build
`TableMetadata` → `TableOperations.commit`). `doRefresh` reads the pointer;
`doCommit` writes new metadata then a two‑layer CAS. Persistence = pointer +
scalar summary. Credential vending = cached per‑cloud SPI gated by
`X-Iceberg-Access-Delegation`. (Note: Polaris **forked** `CatalogHandlers` — a
hint that the stock helper is not customizable enough; see D3.)

---

## 5. Design decisions & tradeoffs

Each: **Options → Tradeoffs → Recommendation → Consistency note.** Prototype
(§10) and review (§11) outcomes are folded in.

### D1 — Reuse Iceberg `CatalogHandlers` + `BaseMetastoreCatalog` vs. hand‑write
- **A (recommended):** `UCIcebergCatalog extends BaseMetastoreCatalog` +
  `UCTableOperations`; route handlers through `CatalogHandlers`.
- **B:** hand‑write create/commit.

| Dim | A: reuse core | B: hand‑write |
|---|---|---|
| Spec fidelity | High | Drift risk |
| Maintenance vs bumps | Low code; version coupling (D8) | Own it |
| Commit‑semantics effort | Low (proven loop) | High |

- **Recommendation:** **A** — prototype‑validated end‑to‑end (real `RESTCatalog`,
  no fallback).
- **Implementation contract (prototype gotcha):** Iceberg 1.9.2
  `BaseMetastoreTableOperations.refresh()` **re‑throws** `NoSuchTableException`
  from `doRefresh()`; the create path must `disableRefresh(); return;` when the
  pointer is absent **and** `currentMetadataLocation()==null` (mirror
  `JdbcTableOperations`); only throw when a known table's pointer vanished.
- **⚠ D1↔D3 tension (from review):** "reuse, write no bespoke commit logic" is
  optimistic — a configurable server‑side retry count **and** proper
  `CommitStateUnknown` handling both require **wrapping / re‑driving**
  `CatalogHandlers.commit` (which hardcodes `retry(4)`; see D3). Budget for a thin
  UC commit wrapper.

### D2 — Native‑Iceberg persistence model
- **A (recommended):** add `ICEBERG` to `DataSourceFormat` (+ a generation
  marker) + a **general `metadata_location` column** decoupled from
  `uniform_iceberg_*`. **B:** overload the uniform column. **C:** new entity.
- **Recommendation:** **A** (prototype confirmed B's hazard: `valueOf` throws;
  overloading makes native vs UniForm indistinguishable with two writers).
- **⚠ Read‑path blast radius (review MUST‑FIX):** `loadTable`, `listTables`,
  `tableExists` all read the uniform pointer
  (`IcebergRestCatalogService.java:157/178/232`). Decoupling into a new column
  **404s native tables** unless **every reader is updated to coalesce** the two
  columns. Assign this to PR2/PR3 with a test.
- **Migration caution:** `hbm2ddl.auto=update` won't reliably add columns/enums
  in prod — see D11 for the migration reality.

### D3 — Commit atomicity / OCC
- **A:** JPA `@Version`. **B (recommended):** conditional
  `UPDATE ... WHERE metadata_location=:expected` (CAS). **C:** serializable txn.
- **Recommendation:** **B** (+ optionally A). CAS works on JPA+H2
  (`affectedRows==1`). **Two‑layer OCC (prototype):** (1) Iceberg requirement
  validation in `CatalogHandlers` (server re‑loads current metadata, validates
  the client's `requirements` — this rejects a stale client); (2) the
  metadata‑location CAS guards the tiny load→swap window. Property‑only concurrent
  commits **merge** (last‑writer‑wins per key); data‑commit conflicts come from
  `assert-ref-snapshot-id`.
- **⚠ Commit‑outcome state machine (review MUST‑FIX):** the CAS returns a clean
  boolean, but if the DB write **throws after issuing** (connection dropped),
  the outcome is **ambiguous**. Design must classify: DB exception → definite
  `CommitFailedException` (safe to retry / delete the freshly‑written metadata)
  vs. `CommitStateUnknownException` (ambiguous → **do not** delete; client
  reconciles). Today the error handler maps only `CommitFailedException`
  (`IcebergRestExceptionHandler.java:44`).
- **⚠ Retry reality (review, decompiled evidence):** stock
  `CatalogHandlers.commit` hardcodes `Tasks.foreach(...).retry(4)
  .exponentialBackoff(100,60000,1800000,2.0)` and does **not** read
  `commit.retry.num-retries` (that property is honored only by *client‑side*
  commit ops, not this server path). The server‑side DB read‑modify‑write
  amplification is therefore **not tunable** without wrapping `CatalogHandlers`.
  **Decide up front:** accept the fixed `retry(4)`, or wrap/re‑drive the commit
  loop (owning the D1↔D3 tension).
- **Orphan metadata:** every lost CAS race / retry writes a `metadata.json`;
  define a cleanup contract (delete only on **definite** `CommitFailed`).

### D4 — Credential vending scope for writes
- **A (recommended):** AWS‑first — reuse `StorageCredentialVendor`
  `{SELECT, UPDATE}`, honor `X-Iceberg-Access-Delegation`; **B:** close Azure/GCP
  now; **C:** local‑FS (spike). **Recommendation:** A for MVP, B fast‑follow.
- **Server‑side write FileIO (real obligation):** add a write‑capable metadata
  writer (`OutputFile` / `TableMetadataParser.overwrite`); make
  `SimpleLocalFileIO` writable; build FileIO from the **table's location** (not
  the warehouse root); flip the SELECT‑only FIXME to vend `{SELECT, UPDATE}` when
  authorized (via `VEND_TABLE_CREDENTIAL`, §7).

### D5 — Client FileIO responsibility (reframed by review)
- **Server MUST guarantee:** write `metadata.json` via a write‑scoped server‑side
  FileIO; return creds + `io-impl`/region in `LoadTableResponse.config`
  (`TableConfigService.java:71-81` already does S3/ADLS/GCS).
- **Client owns:** a FileIO on its own classpath — **Spark** ships
  `iceberg-spark-runtime` (shaded S3FileIO + XML parser); **pyiceberg** uses
  pyarrow/fsspec. Neither hits the prototype's `ResolvingFileIO`/Woodstox
  `NoClassDefFoundError`.
- **Resolution (review consensus):** the `NoClassDefFoundError` is a
  **test‑harness classpath gap**, **not** a production data‑plane blocker — but it
  **gates the MVP's interop‑proof deliverable**, so fix it cheaply in **PR1**
  (`hadoop-client-runtime % Test`, or point the test client at
  `io-impl=org.apache.iceberg.aws.s3.S3FileIO` to bypass `ResolvingFileIO`).
  *(Residual dissent is only the label: "interop‑proof/test concern" vs "interop
  blocker" — both agree it belongs in PR1.)*

### D6 — Namespace ↔ schema mapping
- **A (recommended):** single‑level namespace = one UC schema (current). Reject
  nested namespaces explicitly; a missing schema currently surfaces as a UC authz
  error, not a clean Iceberg `NoSuchNamespace` — map it.

### D7 — `createNamespace`
- (A) map → `createSchema` (reuse control‑plane authz/location); (B) defer.
  **Recommendation:** decide in MVP scoping; **do not advertise it unless
  implemented** with SchemaService‑equivalent semantics.

### D8 — Metadata persistence granularity + version coupling
- Start **pointer‑only**; adopt pointer+summary if listing/cred latency demands.
- **Version coupling:** reliance on internal `BaseMetastore*` contracts (D1's
  `refresh()` re‑throw; D3's hardcoded `retry(4)`) is Iceberg‑version‑specific.
  Pin the version (`build.sbt:401`) and add SPI‑compatibility tests re‑run on
  every bump.

### D9 — Hand‑written Iceberg types vs generated
- **A (recommended):** hand‑write against `org.apache.iceberg` types (current;
  Polaris‑style). Add **golden REST serialization tests** since it diverges from
  UC's OpenAPI codegen.

### D10 — Authorization (grounded in §7; expanded by review)
- **A (recommended):** reuse UC's `@AuthorizeExpression` constants
  (`CREATE_TABLE`/`GET_TABLE`/`UPDATE_TABLE`/`VEND_TABLE_CREDENTIAL`) + Sub‑decision
  **S** (replace the read gate's metastore‑OWNER with the hierarchical model).
- **⚠ Create‑path authz is TWO complementary gaps (review MUST‑FIX), one op,
  template `DeltaApiService.java:202-219`:**
  1. **Input mapping (read‑before):** annotate only `CATALOG` + `SCHEMA` (+
     external location) — **never `TABLE`**, because `KeyMapper` resolves a table
     by DB lookup that **throws pre‑create** (`KeyMapper.java:145-155`).
  2. **Grant (write‑after):** call `initializeHierarchicalAuthorization(tableUuid,
     schemaId)` after insert so the creator becomes table **OWNER** — else the
     next `UPDATE_TABLE` / delegated `loadTable` **denies the creator**.
  3. **Service capability:** `IcebergRestCatalogService` must **extend
     `AuthorizedService`** (or inject the authorizer) — it's a plain class today.
- **⚠ 403 error shape (review MUST‑FIX):** authz is a **routing decorator**;
  errors go through the **server‑wide `GlobalExceptionHandler`**, so a denial
  returns UC `error_code` JSON, **not** Iceberg `ErrorResponse`. Needs a
  `/iceberg`‑prefix error decorator or a path‑aware handler.
- **⚠ Config‑endpoint authz downgrade:** if Sub‑decision S opens read gates but
  `/v1/config` stays metastore‑OWNER, a SELECT user can't bootstrap a client.
  Mirror Delta `getConfig` (`DeltaApiService.java:98-102`).
- **Tradeoff on S:** read‑gate widening is intentional (SELECT user can read) and
  security‑relevant — call it out and cover with an authz test matrix.

### D11 — Create atomicity + migration reality
- **Problem:** create is check‑then‑insert with **no unique constraint** on
  `(schema_id, name)` (only non‑unique `idx_name`) — racing creates both insert.
  (This is true of UC's *existing* create path too, `TableRepository.java:644`.)
- **Recommendation:** add a DB **unique constraint** on `(schema_id, name)`.
- **⚠ Migration is metastore‑wide, not cheap (review):** it changes create
  semantics for **all** table types, needs a **dedup precondition** (existing
  duplicates would fail the constraint), and `hbm2ddl.auto=update` will **not**
  reliably add it — requires an **explicit DDL migration** (and UC has no
  migration framework today). Scope accordingly.

---

## 6. Per‑operation design (condensed)

- **`loadTable` (→ read‑write):** honor delegation header; pick `{SELECT}` vs
  `{SELECT, UPDATE}` from grants (§7); vend via `StorageCredentialVendor`; return
  metadata‑location/metadata/config/storage‑credentials; **coalesce** the pointer
  column (D2).
- **`createTable`:** authorize `CREATE_TABLE` on CATALOG+SCHEMA (D10.1); build
  initial metadata (Iceberg core); write `v1.metadata.json` via write FileIO
  (D4); **atomic** insert (D11) with `disableRefresh()` on `doRefresh` (D1);
  **grant creator OWNER** (D10.2); managed/staging location allocation; optional
  `stage-create`.
- **`updateTable`/commit:** validate requirements + apply updates via
  `CatalogHandlers` (D1); write new metadata; **CAS** pointer (D3) with the
  `CommitFailed`/`CommitStateUnknown` classification + orphan cleanup; authorize
  `UPDATE_TABLE`.

## 7. Authorization & UC API consistency (grounded)

**Principle:** indistinguishable from the rest of UC in authorize/error/
paginate/resolve.

### 7.1 Framework
`@AuthorizeExpression` (SpEL; default `#deny`; vars `#authorize/#authorizeAny/All`,
`#principal`, `#metastore`/`#catalog`/`#schema`/`#table`/`#external_location`),
`@AuthorizeResourceKey` (params/body→securables→UUIDs), `@AuthorizeKey`
(non‑resource vars). Evaluated by `UnityAccessDecorator`/`UnityAccessEvaluator`;
`KeyMapper` resolves names→UUIDs and auto‑fills table→schema→catalog **by DB
lookup (throws for a non‑existent table — see D10.1)**. LIST filters via
`@ResponseAuthorizeFilter`/`ResultFilter`. Privileges in
`persist/model/Privileges.java`; `JCasbinAuthorizer` hierarchy `g2(parent,child)`
— non‑OWNER matches through the hierarchy, OWNER only direct.
`initializeHierarchicalAuthorization` (in `AuthorizedService`) grants creator
OWNER + links child→parent. Authn: internal JWT bearer / `UC_TOKEN` cookie;
external tokens exchanged at `/api/1.0/unity-control/auth/tokens`.

### 7.2 Existing per‑op privileges (parity targets)
| Op | Expr | Rule |
|---|---|---|
| createTable | `CREATE_TABLE` | catalog use AND (`schema OWNER` OR `USE_SCHEMA + CREATE_TABLE`) [+ external‑location checks] |
| getTable / Delta loadTable | `GET_TABLE` | metastore/catalog OWNER OR `schema OWNER + USE_CATALOG` OR `USE_SCHEMA + USE_CATALOG + table OWNER/SELECT/MODIFY` |
| Delta updateTable/commit | `UPDATE_TABLE` | catalog use AND schema use AND `table OWNER/MODIFY` |
| getTableCredentials / temp‑creds | `VEND_TABLE_CREDENTIAL` | catalog+schema use AND READ→`table OWNER/SELECT`; RW→`OWNER` OR `SELECT+MODIFY`; vend READ→`{SELECT}`, RW→`{SELECT,UPDATE}` |
| config (Delta getConfig) | metastore OWNER OR `catalog USE_CATALOG/OWNER` | bootstrap for non‑owners |

### 7.3 KEY FINDING — current `/iceberg` gate diverges
Every read endpoint gates on `#authorize(#principal, #metastore, OWNER)` — too
strict (a SELECT user can't read) and off‑model; LIST doesn't per‑object filter.
Fix for new endpoints **and** the read endpoints (Sub‑decision S).

### 7.4 Target mapping for new ops
| Iceberg op | Reuse | Privileges / notes |
|---|---|---|
| config | Delta‑style | downgrade from metastore‑OWNER so SELECT users bootstrap |
| createTable | `CREATE_TABLE` | authorize CATALOG+SCHEMA (never TABLE pre‑create, D10.1); **then grant creator OWNER (D10.2)** |
| loadTable (read) | `GET_TABLE` | hierarchical read; coalesce pointer (D2) |
| loadTable + delegation | `VEND_TABLE_CREDENTIAL` (RW) | `table OWNER or (SELECT+MODIFY)`; vend `{SELECT,UPDATE}` |
| updateTable/commit | `UPDATE_TABLE` | `table OWNER/MODIFY` |

### 7.5 Error/JSON/pagination conventions
Iceberg JSON via `IcebergObjectMapper`. **403/authz denials must be routed to an
Iceberg `ErrorResponse` handler** — today decorator‑thrown errors hit the
server‑wide `GlobalExceptionHandler` and return UC `error_code` JSON (MUST‑FIX).
UC pagination is `max_results`/`page_token`; Iceberg uses `pageToken`/`pageSize`
— bridge. Resolve names via `KeyMapper`.

### 7.6 ~~Current‑state code discrepancies~~ (WITHDRAWN — factual error)
The earlier claim (constructor/`ENDPOINTS` mismatch) described the **prototype**
branch, **not `main`**. Verified by both reviewers (`git diff main` empty): on
`main`, `ENDPOINTS` has no create/update and the constructor compiles. **No PR1
build fix is needed; removed from Phase 0.**

## 8. Error model
`400` (malformed / unknown update/requirement), `404`
(`NoSuchNamespace`/`NoSuchTable`), `409` (`AlreadyExists`, `CommitFailed`),
`5xx` `CommitStateUnknown`, `403` authz — **all in Iceberg `ErrorResponse`
shape**, which requires the §7.5 error‑routing fix for decorator‑thrown 403s.

## 9. Phasing, risks, testing, execution

**Phasing:** 0 design/scaffolding (S) → 1 write‑FileIO + config/credential
contract + **JVM‑client interop proof** (M, blocks 2&3) → 2 persistence model +
**read‑path coalescing** + D11 migration (L) → 3 createTable + **creator‑OWNER
wiring** + stage‑create (L) → 4 updateTable/commit + **CommitStateUnknown/retry**
(L, critical path) → 5 read‑gate authz alignment + config downgrade + **403
plumbing** → 6 hardening/interop/docs.

**Risks:** (1) commit OCC contract + D1↔D3 wrapping; (2) metastore‑wide D11
migration (no migration framework); (3) write‑FileIO/creds incl. cloud; (4)
version coupling (D1/D3/D8); (5) UniForm coexistence; (6) authz parity, creator‑
OWNER wiring, read‑gate widening, and 403 shape.

**Testing:** unit (metadata build/apply, requirement validation); integration
(Armeria+H2 create→load→commit); **JVM `RESTCatalog` interop** (fix test
classpath); **real commit contention + `CommitStateUnknown`**; **authz matrix**
(per‑op privileges incl. creator‑OWNER + read‑gate change); **403 error‑shape
conformance**; golden REST serialization. Extend `IcebergRestCatalogTest.java`.

**Execution:** stacked PRs via fanout + mandatory cross‑vendor review; branch
`tdas_data/stack/uc-iceberg-rest/<step>`. **Git policy: NO PRs to upstream until
the human says so; fork (`origin`) branch pushes OK.** Prototype at
`origin/prototype/iceberg-rest-write` (`30724a0`, no PR). Design branch
`tdas_data/stack/uc-iceberg-rest/design`.

### 9.1 PR execution plan — stacking & LOC estimates

Each PR self‑contained with tests, rebased in order. LOC = net added hand‑written
lines, excluding generated code. **Anchors (measured): `DeltaApiService` ~296;
`IcebergRestCatalogService` on `main` ~247** (the earlier "~327" was the
post‑prototype size). Reusing `CatalogHandlers` keeps core logic small; **tests +
migration + authz wiring dominate**.

```
master
  |- 01-foundation-write-io+interop  (PR1)
  |    \- 02-persistence+coalesce+migrate (PR2)
  |         \- 03-create+owner-wiring  (PR3)
  |              \- 04-commit+occ       (PR4)   <-- critical path
  |                   \- 05-authz-align+403 (PR5)  <-- touches PR3's file; sequence AFTER 03/04, not parallel
  \- 06-hardening-interop             (PR6; last)
```

| PR | Scope (decisions) | Prod LOC | Test LOC | Risk |
|----|----|----|----|----|
| 1 Foundation: write FileIO + metadata writer; `{SELECT,UPDATE}` delegation; config/cred contract; **JVM‑client interop test fix** (D4/D5) | ~200-320 | ~180-280 | Med |
| 2 Persistence: `ICEBERG` format + `metadata_location` col; **reader coalescing**; **unique‑constraint DDL migration + dedup** (D2/D11) | ~180-300 | ~150-250 | Med‑High (migration) |
| 3 createTable: `UCIcebergCatalog`/ops (`disableRefresh`) via `CatalogHandlers`; **creator‑OWNER wiring + extend `AuthorizedService`**; authz input mapping; staging; stage‑create (D1/D10) | ~350-550 | ~200-320 | High |
| 4 updateTable/commit: `doCommit` write+CAS; **`CommitStateUnknown` + orphan cleanup + retry decision (wrap `CatalogHandlers`?)** (D3) | ~350-550 | ~250-380 | High |
| 5 Authz alignment: read‑gate → hierarchical + list filter; config downgrade; **Iceberg 403 error routing** (§7.3/7.5) | ~120-200 | ~150-250 | Med (behavior change) |
| 6 Hardening: Spark/pyiceberg interop, error/serialization conformance, metadata retention, docs | ~50-120 | ~300-450 | Low |

**Totals (calibrated):** ~1,250-2,050 prod LOC + ~1,230-1,930 test/doc LOC ≈
**~2,500-3,900 LOC across 6 PRs**. Critical path PR1→PR2→PR3→PR4→PR5 is now
**mostly linear** (PR5 touches PR3's file, so it's sequenced after, not
parallel). PR6 last.

## 10. Prototype findings & design deltas

From `prototype-iceberg-rest-write-v2` (local‑FS spike;
`origin/prototype/iceberg-rest-write` @ `30724a0`; **no PR**). Full round trip
via a real `RESTCatalog` (create→load→2 commits→reload) on the Polaris‑style
path; `IcebergRestCatalogWriteTest` 6/6 green; valid version‑chained on‑disk
metadata.

| # | Finding | Affects | Resolution |
|---|---------|---------|-----------|
| F1 | SPI works; `refresh()` re‑throws `NoSuchTableException` (1.9.2) | D1 | `disableRefresh(); return;` when pointer absent & `currentMetadataLocation()==null` |
| F2 | JPA+H2 CAS works; OCC is two‑layer; property commits merge; retry amplification | D3 | keep CAS for load→swap; Iceberg `requirements` for stale‑client; classify `CommitStateUnknown`; retry not tunable w/o wrapping |
| F3 | No `ICEBERG` format; `valueOf` throws | D2 | first‑class `ICEBERG` format + marker |
| F4 | Pointer overloaded onto Delta column → coexistence hazard | D2/D5 | general `metadata_location` + discriminator + **reader coalescing** |
| F5 | Write FileIO/creds is the biggest gap; client `NoClassDefFoundError` (test‑harness) | D4/D5 | server write FileIO + `{UPDATE}`; per‑table‑location FileIO; fix test classpath in PR1; not a prod client blocker |
| F6 | Single‑level ns; create needs schema pre‑exist | D6/D7 | decide `createNamespace`; reject nested |
| F7 | Create not atomic (no unique constraint) | D11 | unique `(schema_id,name)` via real DDL migration |
| F8 | Version coupling to internal `BaseMetastore*` contracts | D8/D9 | pin + SPI compat tests |

## 11. Design review outcomes

Two independent, different‑vendor adversarial reviews of v2.3 + a debate round.
Both **endorse the architecture** and converged after debate.

**Per‑decision verdicts (both reviewers, post‑debate):** D1 AGREE(amend, + D1↔D3
tension); D2 AMEND (read‑path coalescing); D3 AMEND‑significant
(`CommitStateUnknown` + retry not tunable w/o wrapping); D4 AGREE(amend); D5
reframed (client FileIO = test/client concern, → PR1 for interop proof); D6
AGREE; D7 AGREE(amend); D8 AGREE; D9 AGREE; **D10/§7 strong AMEND
(creator‑OWNER wiring + input mapping + service base class + 403 shape + config
downgrade)**; D11 AGREE (migration is metastore‑wide); §9.1 AMEND (LOC anchor,
PR1 reframe, PR5 not parallel).

**Debates resolved with evidence:**
- *Retry knob:* decompiled `iceberg-core-1.9.2` — `CatalogHandlers.commit`
  hardcodes `retry(4)`; ignores `commit.retry.num-retries`. → not tunable without
  wrapping (D1↔D3).
- *Client FileIO:* the `ResolvingFileIO`/Woodstox error is a **test‑harness
  classpath gap**, not a Spark/pyiceberg production blocker → fixed cheaply in
  PR1 as part of the interop proof.

**Consensus MUST‑FIX (folded into §5/§7):** (1) create‑path authz (input mapping
+ creator‑OWNER grant + extend `AuthorizedService`); (2) withdraw §7.6; (3) D3
commit‑outcome state machine + retry decision + orphan cleanup; (4) D2 reader
coalescing; (5) Iceberg 403 error routing; (6) PR1 reframe (FileIO + contract +
interop proof); (7) stage‑create design‑or‑demote; (8) D11 real DDL migration; (9)
config‑endpoint authz downgrade.

**Residual dissent:** only the *label* for client FileIO (test/interop‑proof
concern vs. interop blocker); both agree it belongs in PR1 — no impact on plan.

**Readiness call:** architecture is ready; **start PR1** (reframed). Resolve the
D3 commit‑outcome contract and the create‑path authz design before PR3/PR4.

## 12. Changelog
- **2026‑07‑02 (v2.4):** Folded cross‑vendor review + debate outcomes (new §11);
  corrected §7.6 (withdrawn); added creator‑OWNER wiring + input mapping + service
  base class + 403 routing + config downgrade to D10/§7; added
  `CommitStateUnknown`/retry‑reality/orphan cleanup to D3 + D1↔D3 tension; D2
  read‑path coalescing; reframed D5 (client FileIO); D11 migration reality;
  corrected LOC anchor (main ~247) and PR plan (PR1 reframe, PR5 sequenced).
- **2026‑07‑02 (v2.3):** Populated §10 prototype findings (F1–F8); added D11.
- **2026‑07‑02 (v2.2):** Added §9.1 PR execution plan + LOC.
- **2026‑07‑02 (v2.1):** Grounded §7 authorization; finalized D10.
- **2026‑07‑02 (v2):** Per‑decision tradeoffs (D1–D10); git policy.
- **2026‑07‑02 (v1):** Initial draft from delegated investigation.
