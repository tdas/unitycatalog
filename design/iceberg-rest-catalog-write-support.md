# Design: Iceberg REST Catalog write support (create / load / update) in Unity Catalog

**Status:** Draft (living document) · **Owner:** tdas · **Last updated:** 2026-07-02

> Living document. §5 gives every design decision an explicit **tradeoff
> analysis**; §7 pins the design to UC's existing **authorization + API
> conventions** (grounded); §9.1 is the **PR execution plan + LOC estimates**;
> §10 is the **prototype findings** stream (now populated — the end-to-end spike
> validated the core architecture and amended D1/D2/D3/D5 and added D11).

---

## 1. Summary

Unity Catalog (OSS) today serves a **read-only** Iceberg REST Catalog that is a
thin shim over Delta UniForm tables. This proposes adding the **core write
operations** — `createTable`, `loadTable` (with read‑write credentials), and
`updateTable`/commit — so standard Iceberg REST clients (Spark, pyiceberg,
Trino, …) can create and mutate **native Iceberg tables** managed by UC.

Central bet, from Apache Polaris, **now validated by the prototype (§10)**:

> **UC owns the pointer store, authorization, and credential vending; Apache
> Iceberg core (`CatalogHandlers` + `BaseMetastoreCatalog`) owns all
> table‑metadata mechanics** (requirement validation, applying metadata updates,
> writing metadata files).

Two principles run through every decision:
1. **Reuse over reinvention** — Iceberg core for metadata mechanics; UC's
   existing persistence / credential / **authorization** machinery.
2. **Consistency with the rest of UC** — a new endpoint must authorize, error,
   paginate, and resolve names the same way the control‑plane and Delta REST
   catalog already do (§7).

## 2. Goals / Non‑goals

### Goals (MVP)
`GET /v1/config`; `createTable` (incl. `stage-create`); `loadTable` with
**read‑write** creds; `updateTable`/commit with correct OCC; `createNamespace`
(optional); Iceberg error model; **authorization consistent with UC** (§7);
real‑client interop proven by tests.

### Non‑goals (fast‑follow)
`dropTable`/`renameTable`/`registerTable`; views write API; multi‑table
(transaction) commits; scan planning; metrics ingestion. Full Azure/GCP
external‑location credential closure (D4) — MVP may be AWS‑first / local‑FS‑first.

## 3. Current state (baseline)

- **Server:** Armeria; Iceberg surface registered in
  `UnityCatalogServer.addIcebergApiServices(...)` under
  `/api/2.1/unity-catalog/iceberg` with `IcebergObjectMapper`. Security
  decorators attached by path prefix in `addSecurityDecorators(...)` (only when
  `isAuthorizationEnabled()`); authn before authz.
- **Read path (implemented):** `service/IcebergRestCatalogService.java` — config,
  namespaces list/load, tableExists, loadTable, listTables; loadView 404 stub;
  reportMetrics no‑op. Metadata read via `service/iceberg/MetadataService.java`.
  Errors via `exception/IcebergRestExceptionHandler.java`.
- **Not implemented:** `createTable`, `updateTable`/commit, `createNamespace`,
  drop/rename service methods (though `ENDPOINTS` already over‑advertises
  `V1_CREATE_TABLE`/`V1_UPDATE_TABLE` — §7.6).
- **Persistence:** Hibernate/JPA, default H2, `hbm2ddl.auto=update` (no migration
  files). `persist/dao/TableInfoDAO.java` → `uc_tables` has
  `uniform_iceberg_metadata_location` + `uniform_iceberg_converted_delta_*`;
  writer `updateUniformIcebergMetadata(...)`. **No `ICEBERG`** in `TableType` /
  `DataSourceFormat`.
- **Pointer writer today:** only the Delta path
  (`service/delta/DeltaUniformUtils.applyToDao`). The read‑write Delta REST
  catalog (`service/delta/DeltaApiService.java`) is the closest write template.
- **Authorization:** annotation‑driven SpEL evaluated by `UnityAccessDecorator`
  over a hierarchical `Privileges` model on `JCasbinAuthorizer` (fully grounded
  in §7).
- **Credential vending:** `StorageCredentialVendor` is multi‑cloud and
  write‑capable (`{SELECT, UPDATE}`), used by `temporary-table-credentials` and
  the Delta catalog. **Iceberg path pinned read‑only** —
  `iceberg/TableConfigService.java` and `FileIOFactory.java` hardcode
  `Set.of(SELECT)` with `// FIXME`. Azure/GCP vendors `throw UNIMPLEMENTED` for
  external‑location `CredentialDAO`; AWS works.

## 4. Reference architecture (Apache Polaris)

Polaris wires Iceberg REST resources to a vendored fork of
`CatalogHandlers` + a `BaseMetastoreCatalog` subclass with `TableOperations`:
`CatalogHandlers.commit()` runs the canonical loop (`UpdateRequirement.validate`
→ `MetadataUpdate.applyTo` → build new `TableMetadata` → `TableOperations.commit`),
so Polaris writes **no** bespoke commit logic. `doRefresh()` reads the pointer +
parses metadata; `doCommit()` writes a new `metadata.json` then a **two‑layer
CAS** (app pointer check + persistence `entityVersion` CAS). Persistence stores a
pointer + scalar summary. Credential vending is a cached per‑cloud SPI gated by
`X-Iceberg-Access-Delegation`.

---

## 5. Design decisions & tradeoffs

Each: **Options → Tradeoffs → Recommendation → Consistency note.** Prototype
outcomes (§10) are folded into D1/D2/D3/D5 and the new D11.

### D1 — Reuse Iceberg `CatalogHandlers` + `BaseMetastoreCatalog` vs. hand‑write
- **A (recommended):** `UCIcebergCatalog extends BaseMetastoreCatalog` +
  `UCTableOperations`; route handlers through `CatalogHandlers`.
- **B:** hand‑write create/commit in the service.

| Dimension | A: reuse core | B: hand‑write |
|---|---|---|
| Spec fidelity | High (canonical) | Drift risk |
| Maintenance vs Iceberg bumps | Low code; version coupling (D8) | Own it forever |
| Control | Slightly indirect | Full |
| Commit‑semantics effort | Low (proven loop) | High |

- **Recommendation:** **A** — **validated by the prototype**: full round trip via
  a real `RESTCatalog` client on the SPI path, with only 2 small SPI classes +
  ~3‑line endpoint delegations; no fallback needed.
- **Implementation contract (prototype gotcha):** in Iceberg **1.9.2**,
  `BaseMetastoreTableOperations.refresh()` **re‑throws** `NoSuchTableException`
  from `doRefresh()`. The create path (`buildTable().create()` expects
  `ops.current()==null`) breaks unless `doRefresh()` does
  **`disableRefresh(); return;`** when the pointer is absent **and**
  `currentMetadataLocation()==null` (mirror `JdbcTableOperations`); only throw
  when a *previously existing* table's pointer vanished. Do **not** copy the read
  path's "throw NoSuchTable" habit into the write path.

### D2 — Native‑Iceberg table persistence model
- **A (recommended):** add `ICEBERG` to `DataSourceFormat` (+ a table generation
  marker) + a **general `metadata_location` column** decoupled from
  `uniform_iceberg_*`.
- **B:** overload `uniform_iceberg_metadata_location`.
- **C:** dedicated new entity/table.

| Dimension | A | B | C |
|---|---|---|---|
| Semantic clarity | Clean | Poor (Delta‑conversion baggage) | Clean/heavy |
| Migration churn | 1 col + enum (reviewed) | none | largest |
| Coexistence (D5) | easy discriminator | ambiguous | easy |

- **Recommendation:** **A** — **prototype confirms the hazard of B**:
  `DataSourceFormat.valueOf(...)` throws on unknown values
  (`TableInfoDAO.java:125`), so native rows had to use `data_source_format=null`
  / `table_type=EXTERNAL`; and overloading the uniform pointer makes native vs.
  UniForm tables **indistinguishable** with **two writers** of one column.
  Production needs the discriminator **and** a rule that enabling UniForm on a
  native Iceberg table is rejected.
- **Migration caution:** `hbm2ddl.auto=update` is fine for H2 dev, risky in prod
  — treat the new column/enum as a migration‑reviewed change.

### D3 — Commit atomicity / OCC
- **A:** JPA `@Version` optimistic lock. **B (recommended):** conditional
  `UPDATE ... WHERE metadata_location=:expected` (CAS). **C:** serializable txn.
- **Recommendation:** **B**, optionally + **A**. **Prototype refinement (key):**
  a real CAS **works** on JPA+H2 (`createMutationQuery(...).executeUpdate()`,
  `affectedRows==1`=success; stale expected → 0 rows → false). **But OCC has two
  layers and the CAS is necessary‑not‑sufficient:**
  1. **Iceberg requirement validation** (in `CatalogHandlers`): server re‑loads
     current metadata and validates the client's `requirements`
     (`assert-table-uuid`, `assert-ref-snapshot-id`, …). This is what rejects a
     stale client.
  2. **The metadata‑location CAS** guards only the tiny **load→swap** window.
  - Consequence: because the server re‑loads and re‑applies, **property‑only
    concurrent commits MERGE** (last‑writer‑wins per key) rather than conflict;
    real data‑commit conflict detection comes from `assert-ref-snapshot-id`, not
    the CAS. **Design must state the OCC contract explicitly** and keep the CAS
    for the load→swap race.
  - `CatalogHandlers.commit` wraps in `Tasks.foreach(ops).retry(N)
    .onlyRetryOn(CommitFailedException)` → **retry amplification** under
    contention (several DB read‑modify‑write cycles per client commit) → expose
    a **retry‑count config knob**.

### D4 — Credential vending scope for writes
- **A:** AWS‑first (reuse `StorageCredentialVendor` `{SELECT, UPDATE}`, honor
  `X-Iceberg-Access-Delegation`); **B:** close Azure/GCP now; **C:** local‑FS
  (prototype). **Recommendation:** **A** for MVP, **C** for spike, **B**
  fast‑follow. Add a metadata **writer** (Iceberg `OutputFile` /
  `TableMetadataParser.overwrite`). Vend via the **same** vendor + privilege set
  as `VEND_TABLE_CREDENTIAL` (§7). See D5 for the prototype's write‑FileIO gap.

### D5 — UniForm coexistence (two pointer producers)
- **Decision:** a table is **either** native Iceberg **or** Delta‑UniForm‑as‑Iceberg,
  never both writing the pointer. Requires the D2 discriminator so Iceberg
  commits reject UniForm tables and vice versa. Allowing both risks two writers
  racing one pointer with no shared OCC — rejected. **Prototype confirms** the
  indistinguishability hazard.

### D6 — Namespace ↔ schema mapping
- **A (recommended):** single‑level Iceberg namespace = one UC schema (current);
  catalog from `{prefix}`/warehouse. **B:** multi‑level. **Recommendation:** A —
  **prototype confirms** single‑level only; multi‑level (`a.b.c`) has no UC
  representation. Note: create currently **requires the UC schema to pre‑exist**
  (no `createNamespace`).

### D7 — `createNamespace`
- (A) map → UC `createSchema` (reuse control‑plane authz/creation); (B) out of
  scope. **Recommendation:** A if low‑cost; else defer.

### D8 — Metadata persistence granularity
- **A:** pointer only. **B (Polaris):** pointer + scalar summary. Start **A**;
  adopt **B** if listing/credential latency demands.
- **Version‑coupling note (from prototype D8):** relying on Iceberg internal
  `BaseMetastore*` contracts (e.g. the `refresh()` re‑throw in D1) is
  **version‑specific**. Pin the Iceberg version and re‑verify these contracts on
  every bump (informs D9 too).

### D9 — Hand‑written Iceberg types vs OpenAPI‑generated
- **A (recommended):** keep hand‑writing against `org.apache.iceberg` types
  (current; also Polaris's model approach). **B:** vendor Iceberg's OpenAPI +
  codegen. **Recommendation:** **A** — note the deliberate divergence from UC's
  spec‑driven codegen, plus the version‑pin discipline from D8.

### D10 — Authorization (grounded in §7)
- **A (recommended):** reuse UC's `@AuthorizeExpression` constants
  (`CREATE_TABLE` / `GET_TABLE` / `UPDATE_TABLE` / `VEND_TABLE_CREDENTIAL`)
  verbatim, with `@AuthorizeResourceKey`/`KeyMapper` mapping Iceberg path params
  to UUIDs. **B:** Iceberg‑specific gates. **Sub‑decision S:** replace the read
  path's `#authorize(#principal, #metastore, OWNER)` gate with the hierarchical
  model. **Tradeoff on S:** a **behavior change** (today needs metastore OWNER; a
  normal SELECT user can't even read) that makes Iceberg consistent with the
  control plane — more correct, but call it out and cover with tests.
  **Recommendation:** **A + S.**

### D11 — Create atomicity (NEW, from prototype)
- **Problem:** `createIfAbsent` is check‑then‑insert with **no unique
  constraint** on `(schema_id, name)` (only non‑unique `idx_name`,
  `TableInfoDAO.java:29`); two racing creates can both insert.
- **Options:** (A) add a DB **unique constraint** on `(schema_id, name)` (or an
  upsert) so `AlreadyExistsException` is DB‑enforced; (B) app‑level lock.
- **Tradeoff:** A is correct and cheap but is a schema change (migration
  discipline, D2); B is racy/portable‑but‑weaker. **Recommendation:** **A.**
  (Update/commit is already safe via the D3 CAS.)

---

## 6. Per‑operation design (condensed)

- **`loadTable` (→ read‑write):** honor `X-Iceberg-Access-Delegation`; pick
  `{SELECT}` vs `{SELECT, UPDATE}` from grants (§7); vend via
  `StorageCredentialVendor`; return metadata‑location, metadata, config,
  storage‑credentials.
- **`createTable`:** build initial `TableMetadata` (Iceberg core); write
  `v1.metadata.json`; persist native pointer (D2) via an **atomic** insert (D11);
  staging/managed location (Delta template); authorize `CREATE_TABLE` (§7);
  `stage-create`; `doRefresh` must use the `disableRefresh()` pattern (D1).
- **`updateTable`/commit:** validate `requirements` + apply `updates` via
  `CatalogHandlers` (D1) → new metadata; write file; **CAS** pointer (D3) →
  conflict = `CommitFailedException`; retain metadata‑log; authorize
  `UPDATE_TABLE`.

## 7. Authorization & UC API consistency (grounded)

**Principle:** the Iceberg endpoints must be indistinguishable from the rest of
UC in how they authorize, error, paginate, and resolve names.

### 7.1 Framework
`@AuthorizeExpression` (SpEL; default `#deny`; exposes `#authorize`,
`#authorizeAny/All`, `#principal`, `#metastore`/`#catalog`/`#schema`/`#table`/
`#external_location`), `@AuthorizeResourceKey` (params/body → securables →
UUIDs), `@AuthorizeKey` (non‑resource vars, e.g. `#operation`, `#table_type`).
Evaluated by `UnityAccessDecorator`/`UnityAccessEvaluator`; `KeyMapper`
resolves names→UUIDs and auto‑fills table→schema→catalog; LIST filters via
`@ResponseAuthorizeFilter`/`ResultFilter`. Attached in `addSecurityDecorators`
only when `isAuthorizationEnabled()`; `AuthDecorator` (authn) before authz.
Privileges: `persist/model/Privileges.java` (`OWNER`, `USE_CATALOG`,
`CREATE_SCHEMA`, `USE_SCHEMA`, `CREATE_TABLE`, `SELECT`, `MODIFY`,
`CREATE_EXTERNAL_TABLE`, …). `JCasbinAuthorizer` stores `(principal, resource,
action)` + hierarchy `g2(parent, child)` — **non‑OWNER matches through the
hierarchy; OWNER only direct**. `initializeHierarchicalAuthorization` grants
creator `OWNER` + links child→parent. Authn: internal JWT bearer (issuer
`internal`, JWKS) or `UC_TOKEN` cookie; external tokens exchanged at
`/api/1.0/unity-control/auth/tokens`; principal = UC user UUID.

### 7.2 Existing per‑op privileges (parity targets)
| Op | Expression | Rule |
|---|---|---|
| `TableService.createTable` | `CREATE_TABLE` | `catalog OWNER/USE_CATALOG` AND (`schema OWNER` OR `USE_SCHEMA + CREATE_TABLE`); external tables also `external_location OWNER/CREATE_EXTERNAL_TABLE` + no data‑securable overlap |
| `getTable` / Delta `loadTable` | `GET_TABLE` | metastore/catalog OWNER OR `schema OWNER + USE_CATALOG` OR `USE_SCHEMA + USE_CATALOG + table OWNER/SELECT/MODIFY` |
| Delta `updateTable`/commit, UC commit | `UPDATE_TABLE` | `catalog OWNER/USE_CATALOG` AND `schema OWNER/USE_SCHEMA` AND `table OWNER/MODIFY` |
| Delta `getTableCredentials`, `temporary-table-credentials` | `VEND_TABLE_CREDENTIAL` | catalog+schema use AND READ→`table OWNER/SELECT`; READ_WRITE→`table OWNER` OR `SELECT+MODIFY`; vend READ→`{SELECT}`, READ_WRITE→`{SELECT,UPDATE}` |

### 7.3 KEY FINDING — current `/iceberg` authz diverges
Every read endpoint is gated only by
`@AuthorizeExpression("#authorize(#principal, #metastore, OWNER)")` +
`@AuthorizeResourceKey(METASTORE)` (`IcebergRestCatalogService.java` ~:105–243) —
so it requires **metastore OWNER**: both too strict (a plain `SELECT` user can't
read) and off‑model; LIST doesn't per‑object filter. Fix for the new write
endpoints **and** (Sub‑decision S) the read endpoints.

### 7.4 Target mapping for new ops
| Iceberg op | Reuse | Privileges |
|---|---|---|
| `createTable` | `CREATE_TABLE` | catalog use AND (`schema OWNER` OR `USE_SCHEMA + CREATE_TABLE`) |
| `loadTable` (read) | `GET_TABLE` | hierarchical read |
| `loadTable` w/ delegation | `VEND_TABLE_CREDENTIAL` (RW) | catalog/schema use AND `table OWNER or (SELECT+MODIFY)`; vend `{SELECT,UPDATE}` |
| `updateTable`/commit | `UPDATE_TABLE` | catalog/schema use AND `table OWNER/MODIFY` |
| `createNamespace` (D7=A) | mirror `SchemaService` | `USE_CATALOG` + `CREATE_SCHEMA` |

`@AuthorizeResourceKey` maps `prefix`→catalog (via warehouse), `namespace`→schema,
`table`.

### 7.5 Other conventions
Iceberg JSON via `IcebergObjectMapper` (kebab‑case + REST serializers); authz
denials must map to the Iceberg `ErrorResponse` 403 shape (not UC's `error_code`
JSON); UC pagination is `max_results`/`page_token` (next = last name) while
Iceberg uses `pageToken`/`pageSize` — bridge consistently; resolve names via
`KeyMapper`.

### 7.6 Current‑state code discrepancies to fix
1. **`ENDPOINTS` over‑advertises** `V1_CREATE_TABLE`/`V1_UPDATE_TABLE` with no
   backing methods (~:57–68).
2. **Constructor/wiring mismatch** in this checkout (`UnityCatalogServer`
   builds the service with 4 args ~:292–297; constructor needs `FileIOFactory` +
   `Repositories` ~:88–100). Fold the fix into Phase 0/1 (likely blocks build).
   *(Prototype note: the spike compiled/ran on its branch, so verify whether this
   is checkout‑state drift vs. a real gap when starting Phase 1.)*

## 8. Error model
`400` (malformed / unknown update or requirement), `404`
(`NoSuchNamespace`/`NoSuchTable`), `409` (`AlreadyExists` on create,
`CommitFailed` on requirement failure), `5xx` `CommitStateUnknown`, `403` authz —
all as Iceberg `ErrorResponse` via `IcebergRestExceptionHandler`.

## 9. Phasing, risks, testing, execution

**Phasing:** 0 design/scaffolding + §7.6 fixes (S) → 1 write‑IO + creds (M,
blocks 2&3) → 2 createTable + persistence model + D11 unique constraint (L) →
3 updateTable/commit (L, critical path) → 4 hardening/interop/authz‑alignment
(M). 3 depends on the persistence model from 2.

**Risks:** (1) commit OCC contract (D3 two‑layer nuance) + retry amplification;
(2) migration outside H2; (3) write‑FileIO/creds gap incl. cloud + client
`NoClassDefFoundError` (D5/§10‑F5); (4) spec/version coupling (D1/D8/D9);
(5) UniForm coexistence (D2/D5); (6) authz parity + read‑gate behavior change
(§7.3).

**Testing:** unit (metadata build/apply, requirement validation); integration
(Armeria + H2, create→load→commit — proven by the prototype); Iceberg client
interop (`RESTCatalog`); **commit concurrency/conflict + the D3 OCC contract**;
authorization matrices (§7.4) incl. the read‑gate change; error‑model
conformance. Extend `service/IcebergRestCatalogTest.java` (+ a write test, as the
prototype did).

**Execution:** stacked PRs via fanout + mandatory cross‑vendor review; branch
convention `tdas_data/stack/uc-iceberg-rest/<step>`; each PR self‑contained with
tests. **Git policy: NO PRs to `unitycatalog/unitycatalog` (upstream) until the
human says so; pushing branches to the fork `origin` (`tdas/unitycatalog`) is
allowed.** The prototype branch lives at `origin/prototype/iceberg-rest-write`
(`30724a0`, no PR).

### 9.1 PR execution plan — stacking & LOC estimates

Stacked PRs, each self‑contained with tests; branch convention
`tdas_data/stack/uc-iceberg-rest/<step>`, rebased in order. LOC = net added
hand‑written lines, **excluding** code generated from `api/all.yaml`. Ranges are
preliminary; **the prototype (§10) confirmed the small‑SPI intuition** (write
engine = 2 small SPI classes + ~3‑line endpoint delegations), so PR3/PR4 core
logic trends to the low end, with tests dominating.

**Anchor (measured):** `DeltaApiService.java` ~296 LOC for the full
create+commit+load+creds surface; `IcebergRestCatalogService.java` ~327 LOC;
`IcebergRestCatalogTest.java` ~333 LOC.

```
master
  |- 01-foundation-write-io       (PR1)
  |    \- 02-persistence-model     (PR2)
  |         \- 03-create-table     (PR3)
  |              \- 04-update-commit (PR4)   <-- critical path
  |- 05-authz-alignment           (PR5; branches off 01, independent of 02-04)
  \- 06-hardening-interop         (PR6; last, depends on 03/04)
```

| PR | Scope (key decisions) | Key files | Prod LOC | Test LOC | Risk |
|----|----|----|----|----|----|
| 1 Foundation + write IO/creds | fix constructor/`ENDPOINTS` (§7.6); write‑capable FileIO + metadata writer; credential delegation via `StorageCredentialVendor` (D4/D5) | `iceberg/FileIOFactory`, `iceberg/TableConfigService`, metadata writer, `SimpleLocalFileIO`, `UnityCatalogServer` | ~250-350 | ~150-250 | Low-Med |
| 2 Persistence model | `ICEBERG` format + general `metadata_location` col; coexistence guard (D2/D5); unique constraint (D11) | `api/all.yaml`, `TableInfoDAO`, `TableRepository` | ~150-250 | ~120-200 | Med (migration) |
| 3 createTable | `UCIcebergCatalog`/`UCTableOperations` (+ `disableRefresh` pattern, D1) via `CatalogHandlers`; staging; `stage-create`; authz `CREATE_TABLE`; optional `createNamespace` | new `UCIcebergCatalog`, `IcebergRestCatalogService`, `TableRepository` | ~300-500 | ~200-300 | Med-High |
| 4 updateTable/commit | `doCommit` (write + CAS, D3) via `CatalogHandlers.updateTable`; OCC contract + retry knob; authz `UPDATE_TABLE` | `UCIcebergCatalog`/ops, repo CAS, service | ~300-450 | ~250-350 | High |
| 5 Read-path authz alignment | replace metastore‑OWNER gate w/ `GET_TABLE` + list filter (§7.3, S); write‑delegation load authz | `IcebergRestCatalogService`, `AuthorizeExpressions` | ~80-150 | ~150-250 | Med (behavior change) |
| 6 Hardening/interop/docs | Spark/pyiceberg interop, client‑FileIO packaging (F5), error‑model conformance, metadata retention, docs | tests, `docs/`, `roadmap.md` | ~50-120 | ~300-450 | Low |

**Totals (preliminary):** ~1,150-1,800 prod LOC + ~1,200-1,800 test/doc LOC ≈
**~2,400-3,400 LOC across 6 PRs** (excludes generated code). Critical path
PR1→PR2→PR3→PR4; PR5 parallel off PR1; PR6 last.

## 10. Prototype findings & design deltas (LIVING)

From `prototype-iceberg-rest-write-v2` (local‑FS spike; branch
`origin/prototype/iceberg-rest-write` @ `30724a0`; **no PR**). It got a **full
round trip working via a real `RESTCatalog` client** (create → load →
commit set‑properties → commit schema‑evolution → reload) on the **Polaris‑style
`CatalogHandlers` + `BaseMetastoreCatalog`** path — no hand‑wiring fallback —
proven by `IcebergRestCatalogWriteTest` (6/6 tests pass) with valid,
version‑chained on‑disk metadata.

| # | Finding | Evidence | Affects | Resolution |
|---|---------|----------|---------|-----------|
| F1 | SPI path works end‑to‑end; **but** `refresh()` re‑throws `NoSuchTableException` (Iceberg 1.9.2) | full round‑trip test green; bytecode of `BaseMetastoreTableOperations.refresh()` | D1 | Confirm A; create path uses `disableRefresh(); return;` when pointer absent & `currentMetadataLocation()==null` |
| F2 | JPA+H2 CAS works but OCC has **two layers**; property commits merge; retry amplification | `compareAndSwapMetadataLocation` (`affected==1`); `testConcurrentPropertyCommitsMerge`; `CatalogHandlers.commit` retry loop | D3 | Keep CAS for load→swap; rely on Iceberg `requirements` for stale‑client conflict; add retry‑count knob; state OCC contract |
| F3 | No first‑class `ICEBERG` format; `DataSourceFormat.valueOf` throws | `api/all.yaml:1686`; `TableInfoDAO.java:125`; spike used `data_source_format=null`/`EXTERNAL` | D2 | Add `ICEBERG` format + generation marker |
| F4 | Pointer overloaded onto Delta‑UniForm column → coexistence hazard | only `uniform_iceberg_metadata_location` exists; 2 writers; indistinguishable | D2/D5 | General `metadata_location` col + discriminator; reject UniForm on native |
| F5 | Write FileIO/creds is the biggest gap | `FileIOFactory`/`TableConfigService` `Set.of(SELECT)` `// FIXME`; `SimpleLocalFileIO` couldn't write (spike made local‑FS writable); client `ResolvingFileIO.setConf` → `NoClassDefFoundError` (shaded Woodstox) | D4/D5 | Widen to `UPDATE` gated by authz; per‑table‑location FileIO; resolve Azure/GCP UNIMPLEMENTED; ship a working **client** FileIO or vend a loadable `io-impl` |
| F6 | Single‑level namespaces only; create needs schema to pre‑exist | `UCIcebergCatalog`→`getSchemaIdOrThrow`; `listNamespaces` empty for `parent` | D6/D7 | Decide `createNamespace`→`createSchema`; reject/flatten nested |
| F7 | Create not atomic (no unique constraint) | `createIfAbsent` check‑then‑insert; only non‑unique `idx_name` (`TableInfoDAO.java:29`) | **D11 (new)** | Unique constraint on `(schema_id, name)` / upsert |
| F8 | Build/codegen friction + Iceberg **version coupling** | reliance on internal `BaseMetastore*` contracts (e.g. F1) is version‑specific | D8/D9 | Pin Iceberg version; re‑verify contracts on bumps |

### Resolved open questions
- D1 fits UC's Armeria/JPA stack (F1). D3 CAS works but is half the OCC story
  (F2). D2 persistence model is needed as designed (F3/F4). §7.6 build: the
  spike ran on its branch — re‑verify checkout drift at Phase 1.

## 11. Changelog
- **2026‑07‑02 (v2.3):** Populated §10 from the prototype (F1–F8); it validated
  D1 (SPI works; `disableRefresh` gotcha), refined D3 (two‑layer OCC + retry
  amplification), confirmed D2/D5, and added **D11 (create atomicity / unique
  constraint)**; updated §9.1 calibration and risks.
- **2026‑07‑02 (v2.2):** Added §9.1 PR execution plan (6‑PR stack + LOC
  estimates anchored on `DeltaApiService` ~296 LOC).
- **2026‑07‑02 (v2.1):** Grounded §7 (authz framework, per‑op expressions, the
  metastore‑OWNER divergence, target mapping, code discrepancies); finalized D10.
- **2026‑07‑02 (v2):** Restructured around per‑decision tradeoffs (D1–D10);
  recorded the git policy.
- **2026‑07‑02 (v1):** Initial draft from delegated investigation.
