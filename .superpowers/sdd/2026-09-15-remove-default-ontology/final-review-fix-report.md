# Final-review fix pass — #678 remove-default-ontology

Base: 426f44f7 on feat/678-remove-default-ontology.

## C-1: GraphMutationClient missing datasetId — ADDRESSED

Files:
- `apps/firehub-api/src/main/java/com/smartfirehub/graphreview/service/GraphMutationClient.java`
- `apps/firehub-api/src/main/java/com/smartfirehub/graphreview/service/ReviewItemService.java`
- `apps/firehub-api/src/test/java/com/smartfirehub/graphreview/GraphMutationClientTest.java`
- `apps/firehub-api/src/test/java/com/smartfirehub/graphreview/ReviewItemServiceTest.java`

Changes:
- `mergeEntities(entityType, nameA, nameB, Long datasetId)`, `addEntity(..., Long datasetId)`,
  `addRelation(..., Long datasetId)` now build the request body as `Map<String, Object>` (not the
  `Map<String,String>` used previously) and send `datasetId` — Jackson serializes a `Long` as a JSON
  number, matching ai-agent's `z.number()` schema (verified against `apps/firehub-ai-agent/src/routes/graph.ts`).
  `setProperty()` untouched (its route never required datasetId).
- `ReviewItemService.approve()`: added `requireDatasetId(row)` guard, called at the top of the
  `SYNONYM`, `ENTITY`, and `RELATION` branches (not `PROPERTY`). Throws
  `IllegalArgumentException("이 검수 항목에는 데이터셋 정보가 없어 승인할 수 없습니다.")` **before** calling
  `mutationClient`, so a null-datasetId legacy item never reaches ai-agent and stays `pending` (approve is
  not transactional — the guard has to be pre-call, not a catch).
- All three call sites now pass `row.datasetId()` through.

Tests:
- `GraphMutationClientTest`: updated the 4 pre-existing call sites for the new signatures, and added 3
  new tests (`mergeEntities_sendsDatasetIdAsNumber`, `addEntity_sendsDatasetIdAsNumber`,
  `addRelation_sendsDatasetIdAsNumber`) that inspect the actual WireMock request body with
  `matchingJsonPath("$[?(@.datasetId == 42)]")` — this fails if datasetId were ever sent as a string
  (e.g. via `String.valueOf`), unlike a plain `matchingJsonPath("$.datasetId")` presence check.
- `ReviewItemServiceTest`: updated `approve_synonym_callsMergeThenUpdatesStatus` (now datasetId=99L),
  `approve_synonym_targetMissing_keepsPending` (datasetId null → 99L so the mutationClient-failure path
  is still actually exercised instead of being pre-empted by the new guard), `approve_entity_callsAddEntity`,
  `approve_relation_callsAddRelation`, `approve_relation_endpointMissing_keepsPending` (mock signatures).
  Added 3 new tests covering the null-datasetId rejection path for all three item types:
  `approve_synonym_withoutDatasetId_throwsBeforeCallingMutationClient`,
  `approve_entity_withoutDatasetId_throwsBeforeCallingMutationClient`,
  `approve_relation_withoutDatasetId_throwsBeforeCallingMutationClient` — each asserts the
  `IllegalArgumentException`, that `mutationClient` was never called, and that `repo.updateStatus` was
  never called (item stays pending).

Result: 177/177 Java tests green (see I-1).

## C-2: id=1-special-case tests — ADDRESSED (rewritten per corrected understanding, see note below)

**Important correction vs. the brief**: the brief's proposed replacement for the archive-side test
("참조 중인 온톨로지는 은퇴시킬 수 없다") describes a rule that does not exist in this codebase.
`OntologyService.changeStatus`/`assertTransitionAllowed` has no reference-count gate at all — archiving
only checks transition legality (`active→archived` allowed unconditionally). `deleteOntology`'s own
rejection message literally says *"...은퇴(archived)를 사용하세요"* — archiving a referenced ontology is
the deliberate escape hatch, not something rejected. Writing the brief's proposed assertion would have
reintroduced a guard the plan deliberately deleted. Consulted the advisor before writing and got this
confirmed against the actual `OntologyService` source; adjusted accordingly.

Files:
- `apps/firehub-api/src/test/java/com/smartfirehub/ontology/OntologyStatusTransitionTest.java`
- `apps/firehub-api/src/test/java/com/smartfirehub/ontology/OntologyDeleteTest.java`

Changes:
- `기본_온톨로지는_은퇴시킬_수_없다()` → replaced with `참조_중인_온톨로지도_은퇴시킬_수_있다()`: creates a
  fixture ontology (`OntologyTestSupport.createWithStatus(..., "active")`), binds a `dataset_ontology`
  row to it, then asserts `transitionTo(id, "archived")` **succeeds** and the fixture ends up `archived`
  — encoding the actual rule (archiving is never blocked by references) and guarding against someone
  re-adding a reference gate on archive. Cleans up its own `dataset_ontology` row in a `finally`. Never
  touches id=1.
- `기본_온톨로지는_삭제할_수_없다()` in `OntologyDeleteTest` → removed outright (with an explanatory
  comment) rather than rewritten to a near-duplicate: the uniform "삭제는 참조 중이면만 거부, id 무관"
  rule is already fully covered by the existing `바인딩된_데이터셋이_있으면_삭제할_수_없다()` test (fixture
  ontology + `bindTo` + asserts `"사용 중"`), so re-asserting the same rule against id=1 would only add
  risk (touching the shared seed) without new coverage.
- Updated the stale class-level doc comments in both files (`"거부 사유는 참조 중과 기본 온톨로지뿐"` →
  now says the rule is uniform / id-independent, per #678).

Result: no test now touches ontology id=1's status/existence.

## I-1: verify Java suite + restore polluted seed — ADDRESSED

**Pre-check** (before any code changes): `SELECT id, domain, status FROM ontology WHERE id=1` against
`smartfirehub_test` returned **0 rows** — confirming the brief's hypothesis: a prior run of the broken
`OntologyDeleteTest.기본_온톨로지는_삭제할_수_없다()` had already actually deleted the seed ontology
(cascading its `ontology_entity_type`/`ontology_relation` rows) on this shared test DB, before I started.
The dev DB (`smartfirehub`) was unaffected (`id=1` still `화재조사 보고서`/active).

**Restore**: recreated the row set from `V71__create_ontology.sql` (ontology id=1 domain/status, 6 entity
types, 6 relations — note `ontology_relation` schema has since evolved to `subject_type_id`/
`object_type_id` FK columns instead of the original text columns, so the relation INSERT was rewritten
to join entity-type ids by name) plus `V72__create_ontology_entity_property.sql`'s Incident/피해액
property row (missed on the first restore pass — caught by `OntologyMigrationTest` failures on the first
`cleanTest` run: `incident_hasDamageAmountProperty()` and `읽기_응답은_관계와_속성의_안정_id를_함께_노출한다()`).
All inserts done inside a transaction with `SET app.tenant_id='1'` (RLS-gated tables).

**First full `cleanTest` run** also surfaced one more pre-existing pollution artifact unrelated to my
restore: `GraphIngestRepositoryTest.findStale_returnsDatasetsBelowBoundOntologyVersion_latestRowOnly()`
failed with `DuplicateKeyException` on `dataset_ontology_dataset_id_key` — two `dataset_ontology` rows for
fixture dataset ids 9101/9102 (bound to an orphaned probe ontology `V102_STALE_PROBE_...`) were already
present in the shared test DB from an earlier, previously-interrupted run of this same test elsewhere
(unrelated to any change in this fix pass — this test file wasn't touched). Deleted the two orphan
`dataset_ontology` rows and the two orphan probe `ontology` rows.

**Final verification** — `./gradlew cleanTest test --tests "com.smartfirehub.ontology.*" --tests "com.smartfirehub.graphingest.*" --tests "com.smartfirehub.graphreview.*"`:

```
BUILD SUCCESSFUL
177 tests completed, 0 failed
```

Post-run re-check of the seed (proof C-2 no longer mutates it):
```
 id |     domain      | status
----+-----------------+--------
  1 | 화재조사 보고서 | active
(6 entity types, 6 relations, 1 property — all present)
```

No other pre-existing failures were observed in this filtered scope (the known #394
`RefreshTokenCleanupServiceTest` issue is outside `ontology.*`/`graphingest.*`/`graphreview.*` and was not
run).

## M-1/M-2: ontologyId stamp on the three missed write paths — ADDRESSED

Files:
- `apps/firehub-ai-agent/src/graphrag/entity-add.ts`
- `apps/firehub-ai-agent/src/graphrag/relation-add.ts`
- `apps/firehub-ai-agent/src/graphrag/property-mutation.ts`
- `apps/firehub-api/src/main/java/com/smartfirehub/ontology/OntologyRules.java`
- `apps/firehub-ai-agent/src/routes/graph.ts` (threading fix — see correction below)

**Correction vs. the brief**: the brief assumed `addRelation`/`addEntity` "should already have
ontologyId in scope from `loadOntologyForMutation`". They didn't — `loadOntologyForMutation` in
`graph.ts` was calling `resolveDatasetOntology(...)` and returning only `.ontology`, discarding the
`ontologyId` half of the `{ontology, ontologyId}` pair it actually gets back. Also, `Ontology` (the
interface, `ontology.ts`) has no `id` field — it's a separate value from the start, not derivable from
the ontology object. Fixed by having `loadOntologyForMutation` return the whole
`{ontology, ontologyId}` pair and updating all three `graph.ts` route handlers to destructure and pass
`ontologyId` through.

- `entity-add.ts`: added `'ontologyId'` to `RESERVED_NODE_KEYS`; `addEntity` now takes an `ontologyId: number`
  parameter (`addEntity(ontology, ontologyId, input)`), binds it via `neo4j.int()` (same pattern as
  `loader.ts`), and stamps `n.ontologyId = $ontologyId` on the node MERGE and `x.ontologyId = $ontologyId`
  on both direction branches of the pending-relation MERGE.
- `relation-add.ts`: `addRelation` now takes `ontologyId: number` as its second parameter
  (`addRelation(ontology, ontologyId, subjectKey, relType, objectKey, sourceChunkIds)`), stamps
  `x.ontologyId = $ontologyId` alongside the existing `schemaVersion` SET, bound via `neo4j.int()`.
- `property-mutation.ts`: added `'ontologyId'` to `RESERVED_NODE_KEYS` only — confirmed by reading the
  file that it never creates/stamps nodes (`MATCH` + `SET n += $props` only), so per the brief it needed
  only the reserved-key defense, not a stamp.
- `OntologyRules.java`: added `"ontologyId"` to `RESERVED_PROPERTY_NAMES` (backend defense-in-depth at
  ontology-edit time, matching the ai-agent-side reserved set).
- `graph.ts`: `loadOntologyForMutation` now returns `resolveDatasetOntology(...)` directly (both fields);
  the `/graph/add-entity` and `/graph/add-relation` handlers destructure `{ ontology, ontologyId }` and
  pass `ontologyId` into `addEntity`/`addRelation`. `/graph/merge-entities` destructures `{ ontology }`
  only (unchanged — `mergeEntities` in `synonym-merge.ts` was out of scope for this finding).

Tests updated/added (all in `apps/firehub-ai-agent`):
- `relation-add.test.ts`: updated all 9 `addRelation(CORE_ONTOLOGY, ...)` call sites to
  `addRelation(CORE_ONTOLOGY, 9, ...)`; added
  `ontologyId를 loader.ts와 동일하게 INTEGER로 바인딩하고 엣지에 스탬프한다(#678)` asserting the Cypher
  contains `x.ontologyId = $ontologyId` and the bound param is a real `neo4j.int()` Integer (`neo4j.isInt`),
  not a plain number.
- `relation-add.integration.test.ts`, `schema-version-type.integration.test.ts`: updated call sites
  (ontologyId=9) — these are `VITEST_INTEGRATION=1`-only real-Neo4j tests, not run in this pass (no
  local Neo4j integration run was attempted; typecheck confirms signatures compile).
- `entity-add.integration.test.ts`: updated the 3 existing `addEntity(CORE_ONTOLOGY, ...)` call sites
  (ontologyId=9) and added a new test asserting the stamped `n.ontologyId` round-trips as `9` with
  `valueType` `INTEGER`. Also real-Neo4j/integration-only, not run here.
- `property-mutation.test.ts`: added
  `ontologyId 속성명도 예약어라 거부한다(#678 — 구버전 판정 스탬프 보호)`.
- `routes/graph.test.ts`: fixed the one test this threading change broke
  (`datasetId를 resolveDatasetOntology로 바인딩된 온톨로지 변환에 쓴다` — `addEntityMock` is now called with
  `(boundOntology, 42, {...})`, matching the mock's `ontologyId: 42`).

`entity-add.ts`'s reserved set still lacks `sourceDatasetIds` (present in `loader.ts`'s set but not here)
— out of scope for this finding per the brief ("ontologyId only"), noting it here rather than widening
the change.

Verification: `pnpm typecheck` clean, `pnpm test` → 1403/1404 passed, 1 pre-existing unrelated failure
(see below).

## M-3: system-prompt / data-analyst agent.md ontologyId note — ADDRESSED

Files:
- `apps/firehub-ai-agent/src/agent/system-prompt.ts` (both occurrences, near the original line numbers)
- `apps/firehub-ai-agent/src/agent/subagents/data-analyst/agent.md`

Added a one-line clarifying note at each `graphrag_describe_ontology` mention: it requires `ontologyId`
("기본 온톨로지" 없음) and should be obtained via `graphrag_list_ontologies` first — consistent with the
tool's own zod description in `graphrag-tools.ts` (already correct, only the prompt text was stale).
No prompt byte-identity/snapshot test broke (`system-prompt.test.ts` doesn't assert exact byte content
for this section — full suite run confirmed green apart from the pre-existing unrelated failure).

## M-4: stale comments — ADDRESSED (plus one extra: e2e file)

Files:
- `apps/firehub-api/src/main/java/com/smartfirehub/ontology/repository/OntologyRepository.java` — fixed
  the class doc comment: it referenced a "findOntology" no-arg overload that no longer exists (only
  `currentSchemaVersion()` still has a no-arg legacy overload, kept for `GraphIngestService`'s single-
  ontology path); reworded to describe only what's actually there.
- `apps/firehub-api/src/main/java/com/smartfirehub/ontology/controller/OntologyController.java:67` —
  `delete()` comment updated from "참조 중이거나 기본 온톨로지면 409" to describe the actual uniform
  countReferences-only rule.
- `apps/firehub-web/src/api/ontology.ts:29` — same fix on the `deleteOntology` API client comment.
- `apps/firehub-web/e2e/pages/admin/ontology.spec.ts` — the brief cited line 3727 for a stale bare
  `GET /api/v1/ontology` mock, but the file (as it exists on this branch) is 1011 lines; found and removed
  3 occurrences of `mockApi(page, 'GET', '/api/v1/ontology', ...)` (lines ~159, ~550, ~573 pre-edit) — dead
  mocks for an endpoint firehub-api no longer serves (confirmed via `graph.ts`'s own comment: "GET
  /api/v1/ontology 자체가 firehub-api 에서 제거되어"), each already shadowed by a correct sibling
  `mockApi(page, 'GET', '/api/v1/ontology/1', ...)` mock in the same test. Removed via a scoped
  line-match, verified no unintended matches (grep for the pattern post-edit returns nothing) and that
  the surrounding tests still read correctly.

Verification: `pnpm --filter firehub-web typecheck` clean (`tsc -b --noEmit && tsc --noEmit -p tsconfig.e2e.json`).
No Playwright run was executed for this e2e file (out of scope / no local run requested; typecheck is the
verification asked for touched web files).

## Full verification summary

- `apps/firehub-api`: `./gradlew cleanTest test --tests "com.smartfirehub.ontology.*" --tests "com.smartfirehub.graphingest.*" --tests "com.smartfirehub.graphreview.*"` → **177/177 passed**, run twice for confidence (once after C-1/C-2, once again after all changes including M-1/M-2's `OntologyRules.java` edit).
- `apps/firehub-ai-agent`: `pnpm typecheck` clean; `pnpm test` → **1403/1404 passed**. The 1 failure
  (`trigger-manager/prompt-safeguards.test.ts` — `agent.md Phase 1에 get_pipeline/get_dataset 존재 확인 규칙`)
  was verified pre-existing and unrelated by `git stash`-ing all changes from this pass and re-running
  just that file: it fails identically on unmodified HEAD. Not touched by this fix pass.
- `apps/firehub-web`: `pnpm typecheck` clean (only M-4 touched web files).

## Commits

Created as a small number of logical commits (see `git log`), following this branch's established
`--no-verify` precedent only if the pre-commit hook's failure is independently verified unrelated (not
expected to be needed here since backend/agent/web verification all ran clean).
