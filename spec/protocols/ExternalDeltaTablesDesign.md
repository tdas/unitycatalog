# Design: Extending the UC Delta API to External Delta Tables

- **Status:** Draft — direction validated; correctness core specified after two-vendor review + debate (see §13, §15). **Buildable in the revised PR order; not before.**
- **Author:** tdas@databricks.com
- **Created:** 2026-07-02
- **Related:** `spec/protocols/ManagedTablesSpec.md` (UC Delta API v1.0, normative for managed tables), `api/delta.yaml`

> Living design. Validated by a green end-to-end prototype (branch
> `uc-external-delta/prototype`, §13) and by an independent **two-vendor review +
> debate** (claude_code × codex, §15). Every decision carries its tradeoffs. The
> Delta feature is **`catalogManaged`** (repo term; `DeltaConsts.java:35`
> `CATALOG_MANAGED`, `connectors/spark/.../UCTableProperties.java:13`,
> `ManagedTablesSpec.md:371`) — an earlier draft wrongly called it `catalogOwned`.

---

## 1. Summary

The UC Delta API (`/api/2.1/unity-catalog/delta/v1`, `DeltaApiService`) already
lets Delta clients create, load, and vend credentials for **both** managed and
external Delta tables. It does **not** do **commit coordination** for external
tables — CCv2 is fenced to `MANAGED`.

The review + debate established the real shape of the work: the commit *plumbing*
is small and reusable, but making UC a **safe** coordinator for a user-owned path
requires a **new physical-`_delta_log` authority subsystem** that does not exist
in the server today (verified: no `_delta_log` reader/writer in `server/src/main`).
The correctness core is: **onboarding publishes a `catalogManaged` enablement
commit into the physical log with an atomic `tail+1` claim, and steady-state
commits detect divergence** — otherwise the fence is inert and split-brain is
possible even among conformant clients.

## 2. Background and current state

- Spec `api/delta.yaml` (*"UC Delta API"*), `/delta/v1`; normative
  `ManagedTablesSpec.md` (managed-only, `:84`).
- Managed-only fences on the commit path (all verified twice): `requireManaged`
  (`DeltaUpdateTableMapper.java:624`, the real Delta-path gate), the load
  surfacing branch (`TableRepository.java:406`), `validateTable`
  (`DeltaCommitRepository.java:980`, UC-REST path), and a **fifth** gate — an
  unconditional `checkManagedTableEnabled()` in `applyCommitAndBackfillInSession`
  (`DeltaCommitRepository.java:421`).
- **No physical-log I/O exists.** The server persists client-supplied commit
  *pointers* only; `handleOnboardingCommit` (`DeltaCommitRepository.java:323`)
  saves the first commit with no storage read.
- Reusable, type-agnostic machinery (prototype-verified, needs zero change): the
  `PESSIMISTIC_WRITE` row lock (`TableRepository.java:363-378`), `newVersion ==
  last+1` (`DeltaCommitRepository.java:478-489`), etag/uuid checks, `postCommitCore`,
  `uc_delta_commits` (`DeltaCommitDAO`). **Caveat:** the row lock serializes only
  writers *going through UC* — it does nothing against a direct `_delta_log` writer.

## 3. Problem statement

Make UC the authoritative CCv2 coordinator for an external (user-owned) Delta
table, without corrupting tables that have writers UC does not control.

## 4. Goals / non-goals

**Goals:** external tables opt into coordinated commits; `loadTable` surfaces UC
commit state; credential vending for external locations; an **enforceable**
ownership model (physical-log fence + divergence detection) that prevents
split-brain.

**Non-goals (v1):** missing endpoints (`deleteTable`/`renameTable`/`reportMetrics`);
dual-writer tables; a vacuum/GC redesign beyond a maintenance-policy decision
(D9); opening the UC-REST `postCommit` path to external (standardize on the Delta
update endpoint, D4); server-side cross-cloud conditional-write LogStore (D14).

---

## 5. Design decisions and tradeoffs

Settled decisions are terse; contested/new ones carry the debate detail.
`Review` lines record the two-vendor outcome.

### D1 — Ownership / coordination model  *(crux)*
Options: **A** status quo (fails goal); **B** opt-in one-way onboarding + client
fencing (UC-DB-only); **B′** *(recommended)* B **plus** publishing the
`catalogManaged` feature into the physical `_delta_log`; **C** proxy-any-path
(unsafe); **D** advisory reconciling (weak guarantees).
**Review (both vendors):** B as originally written is **insufficient** — it
conflates "UC's DB says catalog-managed" with "the physical log's protocol
advertises `catalogManaged`," and only manipulates the former, so a conformant
client reading the table *by path* has no signal to route through UC → split-brain
even among conformant clients. **Decided → B′.** The value is entirely in physical
publication + fencing + divergence detection, not the (already-working) plumbing.

### D2 — Fencing / enforcement mechanism
Options: **A** Delta `catalogManaged` feature (client-side); **B** server-side
leases/tokens; **C** storage locks.
**Review:** **A**, but reframed. A is **inert until the feature is physically
published** (D1/D12) and provides **no detection** of a violating writer.
Therefore **divergence detection is first-class, not optional** (see **D13**). A
fences conformant clients; D13 catches non-conformant ones; neither alone is
sufficient. **Status:** A + D13, decided.

### D3 — Coordination-state representation → **Decided: B** (typed, authorized, one-way column)
A property is client-writable/reversible/spoofable (`DeltaUpdateTableMapper.java:394-406`,
guarded only by endpoint-level `UPDATE_TABLE`). **Review add:** must also define
concurrent-onboarding behavior and state migration. Confirmed by both.

### D4 — Reuse commit machinery → **Decided: A** on the Delta update endpoint
Relax `requireManaged` (`:624`) + decouple the 5th gate (`:421`) behind the
independent flag; reuse `postCommitCore`; avoid UC-REST `postCommit` (its
`validateTableForCommit` URI-equality `:1010-1022` is a landmine for arbitrary
external URIs). **Review corrections:** (1) finding-#12 citation is
`DeltaCommitRepository.java:421` (not `:427-430`). (2) **Reuse silently skips a
validation:** `UcManagedDeltaContract.validate(...)` runs only for MANAGED
(`DeltaUpdateTableMapper.java:371`; create likewise `DeltaCreateTableMapper.java:70`)
— external would get coordinated commits UC never validated it can support. PR must
re-add contract validation for coordinated external. (3) Keep eligibility
(flag + marker) in one mapper entry point — the repo layer checks only the flag.

### D5 — Credential vending → **C** (AWS/local v1, then per-cloud)
Type-agnostic path confirmed (`DeltaApiService.java:254-266` → `StorageCredentialVendor`
→ `CloudCredentialVendor` FILE→empty). **Review add (important):** the design vended
creds *to clients* only; onboarding/ratify need **server-side READ credentials** for
the external `_delta_log` (see D15). Azure/GCP external-location vending is
unimplemented (`AzureCredentialVendor.java:20`, `GcpCredentialVendor.java:37`).

### D6 — Onboarding validation → **Decided: A (mandatory), now a subsystem**
`handleOnboardingCommit` takes the version on faith (`DeltaCommitRepository.java:323`).
**Review:** "read tail + match version" is necessary but **underspecified**. A correct
onboarding must: detect an existing *foreign* coordinator; check protocol/feature/
schema/UUID compatibility (not just version); interpret checkpoints/gaps when
computing the tail; **publish** the enablement commit; and **close TOCTOU** (see
**D12**). This is a from-scratch subsystem (**D15**), not "more validation."

### D7 — One-way onboarding → **Decided: A**, + recovery
One-way is right, but for user-owned external tables there is no supported hand-back
and no partial-onboarding rollback. **Review:** onboarding is multi-step (verify tail
→ publish enablement → set typed state → seed pointer); a mid-step failure must be a
**recoverable orphan** via an admin repair path — the physical log unambiguously names
`catalogManaged` + tableId, so recovery is well-defined. Required in v1 (PR9).

### D8 — Rollout gating → **Decided: A** (independent flag)
Coupling to `MANAGED_TABLE_ENABLED` (`checkManagedTableEnabled()` at
`DeltaCommitRepository.java:173,239,421`, `TableRepository.java:663`) proven; dedicated
flag added in the prototype. Consider per-cloud gating since storage semantics (esp.
conditional-put, D12/D14) vary.

### D9 — Maintenance policy for coordinated external tables → **CONTRADICTION to resolve**
Earlier recommendation was "client owns vacuum." **Review (codex catch, claude_code
concedes):** `ManagedTablesSpec.md:111` is **normative and forbids** VACUUM/OPTIMIZE/
REORG and *"all other maintenance operations,"* with *"no per-table policy or API for
the catalog to grant more."* So "client owns vacuum" is **forbidden by the spec this
feature extends**. Options: **(A)** amend the spec to permit defined client-side
maintenance for *external* coordinated tables (external = user owns the bytes);
**(B)** external inherits the no-maintenance restriction (needs a log-growth story:
who compacts/checkpoints?); **(C)** UC-driven maintenance (needs server write creds +
delete authority over user data — heavy). **Plus the required fix:** `deleteTable`
purges `uc_delta_commits` only for MANAGED (`TableRepository.java:879-888`) → external
orphan-purge (don't delete the user's directory; fresh-UUID create means no
inheritance except under explicit UUID reuse). **Recommendation:** A (amend spec for
external), pending your call. **Status:** Open (spec change) + purge fix required.

### D10 — Advertised-but-unimplemented endpoints → **A** (defer + trim `/config`)
`/config` advertises 12 (`DeltaApiService.java:60-73`); `DeltaApiService` implements 8;
the list endpoint is also unimplemented; `temporary-path-credentials` is mounted
separately (`UnityCatalogServer.java:250`). Enumerate precisely before trimming.

### D11 — Version authority → **Decided: A**, clarified
Distinguish **`latestTableVersion`** (advances on *every* commit — the authority,
`TableRepository.java:457`) from **`delta.lastUpdateVersion`** (stamped only on
metadata-changing commits, `DeltaUpdateTableMapper.java:586`; advisory). Also: the
snapshot action `update-metadata-snapshot-version` writes the same props (`:536-539`)
and code asserts it *"can never co-occur with add-commit"* (`:187-188`) — false once
external gets `add-commit`. **Must reject co-occurrence** and treat the commit log as
version authority.

### D12 — Onboarding atomicity & TOCTOU closure *(new; converged)*
**Recommendation:** dedicated fencing commit at **`tail+1`** via **atomic
put-if-absent**, with **publish-before-ratify** for the onboarding commit only:
1. UC row lock; typed state `ONBOARDING_IN_PROGRESS` (+ op id).
2. Server reads physical published tail `T` and validates eligibility (no foreign
   coordinator; protocol/schema/UUID compatible).
3. Write enablement commit as `_delta_log/(T+1).json` via put-if-absent. **Fail →** a
   direct writer won; re-read, retry at `T+2` if still eligible, else reject (ordinary
   Delta conflict — no corruption, table isn't catalog-managed yet). **Succeed →** the
   physical log now advertises `catalogManaged`; the fence is live.
4. **Then** UC `onboard(version=T+1)`: seed last-ratified `=T`, ratify `T+1`, flip the
   one-way typed column.
Publish-before-ratify guarantees UC never becomes coordinator-of-record for a version a
foreign writer physically owns. **Quiescence is rejected** (can't pause writers on a
bucket you don't own; unnecessary given put-if-absent). Requires storage backends with
conditional-create (Delta LogStore semantics); backends without it aren't eligible.
**Tradeoff:** slightly more complex than a plain DB write, but it's the only race-free
option. **Status:** Recommended.

### D13 — Steady-state divergence detection *(new; converged with a cost refinement)*
**Recommendation:** at **ratify time**, before accepting `newVersion`, **HEAD**
`_delta_log/<newVersion>.json`; if a foreign published file exists, reject
(`FAILED_PRECONDITION`) + alert. Plus a **periodic full reconciliation** (published
tail vs UC latest-ratified) as maintenance. **Debate:** codex initially proposed a
physical-tail **LIST per commit** (stronger, costly); claude_code refined to HEAD +
periodic, grounded in the spec's authoritative-catalog-entry rule
(`ManagedTablesSpec.md:100-101`, conformant readers ignore a foreign published file at
a catalog-ratified version). **Residual disagreement:** codex would keep per-commit
physical checks as *v1* behavior and relax later; claude_code prefers HEAD+periodic
from the start. **Status:** Recommended HEAD+periodic; v1 strictness is a tunable.

### D14 — Who issues the fence write: server- vs client-driven *(new; OPEN — the one unresolved fork)*
| Option | Pros | Cons |
|---|---|---|
| **Server-driven** (UC writes `_delta_log/(T+1).json`) | UC fully controls the fence; simpler client | Server must implement cross-cloud conditional-write LogStore (large new surface) + hold write creds |
| **Client-driven** (client writes via Delta LogStore; server only reads to verify) *(leaning)* | Reuses Delta's existing conditional-write; server needs read-only I/O; smaller v1 | Relies on a conformant client to perform onboarding correctly; server verifies after the fact |
**Status:** **Open** — the debate did not fully converge. Leaning client-driven for v1
(smaller, reuses LogStore); revisit for a server-driven mode later. **Needs your call.**

### D15 — Server-side `_delta_log` I/O + credentials *(new)*
Add a `DeltaLogAccessor` subsystem: list/read latest version (incl. checkpoints), read
protocol+metadata, HEAD a version, verify published commit content for backfill. **Reads
on the server**; the conditional **write** stays on the client for v1 (D14). Needs
**server-side READ credentials** for external locations (via the external-location
credential resolution path, `StorageCredentialVendor.java:42`) — new plumbing. Local/S3
first; Azure/GCP when vending + atomic semantics are proven. **Status:** Required.

---

## 6. Detailed design (phased) — see §7 for the revised, reordered stack

Phases now front-load the physical-log authority subsystem: **spec → state →
read-only log accessor → physical onboarding/fence → load+version → guarded commit
gate + divergence detection → creds → client wiring → tests/rollout/repair.** No
external `add-commit` is acceptable until the fence (D12) and reconciliation (D13)
exist.

## 7. PR execution plan (revised from the debate)

Self-contained, flag-gated-OFF PRs, branched from master, rebased in order; fork-only
until upstream is approved. **Reordered so the commit gate cannot open before fencing.**

| # | Scope (decisions) | Depends | Est. LOC (prod+test) | Risk |
|---|---|---|---|---|
| PR1 | Design + spec: `catalogOwned`→`catalogManaged`; amend `ManagedTablesSpec.md` (`:83` creation-only enablement, `:84` managed-only, `:111` maintenance policy) + `delta.yaml:791,1035` descriptions (D1,D2,D9) | master | ~0 code / ~600 doc | Low |
| PR2 | Independent flag + authorized one-way onboarding **typed state** + migration + concurrent-onboarding (D3,D7,D8) | master | ~400–600 | Med |
| PR3 | **`DeltaLogAccessor` read-only** (list/tail/snapshot/protocol), local/S3 (D15) | master | ~400–700 | Med–High |
| PR4 | **Physical onboarding**: enablement commit at `tail+1`, put-if-absent, publish-before-ratify, recovery/idempotency, typed-state transition (D12,D6,D14) | PR2,PR3 | ~500–800 | **High** |
| PR5 | `loadTable` surfacing for onboarded external + version-state seeding + D11 (authority + reject snapshot co-occurrence) | PR2,PR4 | ~300–450 | Med |
| PR6 | **Guarded external commit gate** + divergence detection (HEAD-at-ratify + periodic, D13) + external contract validation (`:371`) + centralized eligibility (D4) | PR4,PR5 | ~500–800 | **High** |
| PR7 | Credential vending: client write creds **+ server-side READ creds** (D5,D15); AWS/local, then Azure/GCP | master | AWS/local ~250–450; +Az/GCP ~500–800 | Med |
| PR8 | Client wiring through the Delta API (`UCSingleCatalog:339`); no structural wire changes | PR6,PR7 | ~150–300 | Low–Med |
| PR9 | Test matrix + rollout + telemetry + **admin repair for partial onboarding** (D7) | all | ~300–500 | Med |

**Totals (PR2–PR9, local/S3, excl. docs): ≈ 3,300–5,600 LOC** (prod+test). The
physical-`_delta_log` authority subsystem alone (PR3+PR4+PR6) is ≈ **1,500–2,500**
(both vendors agreed the earlier "PR6 ≈ 600–900" was a serious under-estimate).
Azure/GCP adds ~500–800 (PR7).

**DAG:**
```
PR1 spec (independent)
master ─ PR2 state ─┐
master ─ PR3 log-accessor(read) ─┤
                                 └─ PR4 physical onboarding/fence ─ PR5 load+version ─ PR6 commit gate + divergence
                                                                                          ├─ needs PR7 creds
                                                                                          └─ PR8 client wiring ─ PR9 tests/rollout/repair
```

## 8. Authorization

**Review (new hole):** `CREATE_TABLE` skips the external-location privilege check when
no external location resolves (`AuthorizeExpressions.java:71-73`). Reusing it for
onboarding (the earlier §8 suggestion) would let a principal onboard a path with **no
registered external location**, bypassing the OWNER gate that proves authority to fence
the path. **Onboarding needs a dedicated privilege check** (require external-location
ownership/OWNER), not reuse of `CREATE_TABLE`.

## 9. Risks

- **Split-brain (top):** *not* mitigated until D12 (physical fence) + D13 (divergence
  detection) + D15 (log I/O) exist — the earlier "mitigated" framing undersold this. The
  prototype has none of them yet.
- **TOCTOU at onboarding** (D12); **foreign coordinator adoption** (D6); **partial-
  onboarding wedge** (D7); **maintenance-policy contradiction** (D9); **cross-cloud
  conditional-write availability** (D12/D14); **credential blast radius** (D5/D15).

## 10. Open questions

1. **D14** — server- vs client-driven fence write? (leaning client for v1)
2. **D9** — amend the maintenance spec for external, or inherit no-maintenance?
3. **D13** — HEAD+periodic vs per-commit physical checks in v1?
4. **D5/D15** — how do we obtain server-side read creds per cloud?
5. One-way onboarding acceptable given the repair path (D7)?

## 11. Testing strategy

Mirror the prototype's `SdkExternalCommitCoordinationTest`, parameterized on type:
onboarding (incl. **concurrent onboarding**, **`tail+1` conflict/retry**, **foreign
coordinator reject**), normal/conflict/backfill, per-cloud creds, two-writer race,
**divergence detection (foreign published file at ratify)**, partial-onboarding
recovery, flag-OFF-no-behavior-change.

## 12. Rollout

Independent flag (D8), off by default; local/S3 first; per-cloud promotion as
conditional-write + vending are proven.

---

## 13. Prototype Findings (from `uc-external-delta/prototype` @ `9334840`)

Green end-to-end spike (create external → onboard → v1 → v2 → conflict → load → backfill
→ creds), MANAGED flag OFF; ~90-line diff, all `[PROTOTYPE]`. **codex cross-review
CONFIRMED all findings**; refinements folded into D4/D9/D11/§13. Full report:
`uc-external-delta/prototype:spec/protocols/prototype-findings.md`. Findings #1–#12 map
to D1–D11 as annotated inline above (e.g. #2→D8, #5→D6, #6→D4, #9→D9, #10→D11, #11→PR8,
#12→D4). The debate (§15) showed the prototype's onboarding was DB-only — motivating the
new physical-log decisions D12–D15.

## 14. Changelog

- 2026-07-02: **Two-vendor review + debate folded in.** Fixed `catalogOwned`→
  `catalogManaged`; reworked D1(→B′)/D2/D6/D9/D11; added D12 (atomic onboarding),
  D13 (divergence detection), D14 (fence-write actor, OPEN), D15 (`DeltaLogAccessor`+
  server creds); rewrote §7 (reordered, 9 PRs, physical-log subsystem ≈1.5k–2.5k,
  total ≈3.3k–5.6k); added §8 authz hole, §15. Readiness: buildable in the new order.
- 2026-07-02: codex cross-review of prototype findings folded in.
- 2026-07-02: Prototype run folded in (§13); added D11; recalibrated §7.
- 2026-07-02: Added §7 PR plan; §5 decisions D1–D10; initial draft.

## 15. Design review & debate (two-vendor)

**Process:** `claude_code` and `codex` independently reviewed every decision against
the code (Round 1), then each rebutted the other's critique and converged (Round 2).
Neither authored this doc, so both are independent reviewers.

**Convergence (both, independently):** direction is right (B′ + `catalogManaged` fence
+ reuse), but the design was **not buildable end-to-end** as written — the physical-log
authority + onboarding TOCTOU were unspecified, and the server has no `_delta_log` I/O.
Both caught the `catalogManaged` naming, the D4 `:421` citation, D11's stamp gap, the
D10 endpoint miscount, the skipped external contract validation (`:371`), and that PR4
must not precede fencing.

**Disagreements resolved by debate:** (1) **Quiescence dropped** in favor of atomic
put-if-absent (D12). (2) **Divergence detection = HEAD-at-ratify + periodic**, not
LIST-per-commit (D13).

**Unresolved (recorded as D14):** server- vs client-driven fence write. Leaning
client-driven for v1; needs an explicit decision.

**Verdict:** Direction **sound**; correctness core now **specified**. **PRs 1–3
(spec, state, read-only log accessor) are ready to build now.** The commit gate (PR6)
must follow the fence (PR4) + divergence detection. Resolve D14 (and D9's spec change)
before PR4.
