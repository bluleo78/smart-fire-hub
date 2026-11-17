# WD-784 게이트 불안정 시험 두 개 — 원인과 계획

## 원인

### 1. `DatasetReferenceRepositoryContractTest.findDatasetChangeTriggers_arrayContains` (line 333)
- 데이터셋 변경 폴러 `TriggerEventService.pollDatasetChanges`(30초 주기)는 **ACTIVE 테넌트**의 켜진 `DATASET_CHANGE` 트리거를 돌며,
  감시 목록에 있는데 데이터셋이 없는 id 를 「지워진 데이터셋」으로 보고 `removeWatchedDataset` 으로 뺀다.
- 이 시험은 가짜 id `7`·`8`(그리고 `findDatasetChangeTriggers_longArray`·`_huge…` 는 `9_000_000_000+i`)을 감시 목록에 넣는다.
  전체 실행에서 시험 도중 폴러가 돌면 t1 의 `7` 이 지워져 `findDatasetChangeTriggers(7L)` 이 빈 결과가 된다.
- 이슈 본문의 의심(만든 데이터셋 id 가 7·8 이 되는 순서)은 아니다 — 테넌트·데이터셋 id 는 전체 실행에서 7·8 보다 훨씬 크다.
- WD-769 는 같은 원인을 `TriggerRepositoryPollSnapshotContractTest` 에서만 고쳤다(테넌트 정지).

### 2. `DocumentChunkVectorStoreTest.iterativeScanFillsTopKWhenModelFilterRejectsNearestCandidates` (line 292 `with == 5`)
- `document_chunk_vec_1024` 의 HNSW 색인은 포크(JVM 하나 + 컨테이너 하나) 안의 모든 시험이 함께 쓴다. 앞서 돈 시험의 벡터와,
  `@AfterEach` 가 지웠지만 VACUUM 전이라 그래프에 남은 죽은 튜플이 그래프 모양을 바꾼다.
- 시험은 `ef_search = 10` 근사 검색 결과 수를 단언한다. 25개만 있는 그래프라면(0층 이웃 상한 32) 사실상 완전 그래프라 결정적이지만,
  공유 색인에서는 어느 시험이 같은 포크에서 먼저 돌았는지에 따라 iterative scan 이 목표 5건에 닿지 못하기도 한다 — 부하가 아니라 실행 순서에 달렸다.

## 계획

1. `DatasetReferenceRepositoryContractTest` 에 `@BeforeEach` 를 더해 테넌트 A·B 를 `SUSPENDED` 로 둔다(WD-769 와 같은 방법,
   `fx.setTenantStatus`). 상위 `TenantPairContractBase` 의 `@BeforeEach`(테넌트 생성)가 먼저 돈다. 저장소 호출은 테넌트 범위(GUC)로
   실행되고 테넌트 상태는 보지 않으므로 계약의 의미는 바뀌지 않는다. jOOQ·MyBatis·Tibero 세 하위 클래스 모두에 적용된다.
2. 벡터 시험의 「iterative scan 이 k 를 채운다」 시연을 공유 색인 대신 **트랜잭션 안 임시 테이블**(`ON COMMIT DROP`, 같은
   `vector_cosine_ops` HNSW 색인)에서 한다 — 다른 모델 20개 + 목표 5개만 든 그래프라 결정적이다. `without`(iterative 없음) < 5,
   `with`(`HnswSearch.relaxIterativeScan` 뒤 `ef_search = 10`) == 5. 뮤테이션 보호(relax 의 iterative_scan 줄을 지우면 빨개짐)는 그대로.
   실제 경로(`chunkIndex.semantic`, ef 200 + iterative scan)가 공유 색인에서 5건을 채우는 단언은 남긴다.

## Review Focus
- 정지 테넌트에서 픽스처 생성(대시보드·파이프라인·트리거)이 막히지 않는지 — 세 하위 클래스 모두.
- 임시 테이블 시험이 앱 DB 역할에서 `CREATE TEMP TABLE`·`CREATE INDEX … USING hnsw` 권한이 있는지, `vector` 타입이 search_path 에 있는지.
- 임시 테이블 시험이 정말 결정적인지 — 단독 반복 실행(여러 번)으로 확인.
