# ADR 0002 — 벡터 저장소를 설치 옵션으로 분리

- 상태: **제안** (2026-11-12 — 1~5번 방향은 사용자가 정함, 세부 설계는 검토 대기. 2026-11-14 — WD-720 구현에 맞춰 결정 3·4③·5·6·7 과 열린 질문을 고침. 같은 날 WD-721 구현에 맞춰 결정 6 을 고침)
- 날짜: 2026-11-12
- 이슈: WD-715 (에픽 WD-714, 하위 WD-716~724)
- 관련: ADR 0001(플랫폼 DB Tibero), WD-495(검색 기능 상태 API), WD-80(행 검색)
- **ADR 0001 결정 3 을 보완한다.** "Tibero 빌드에서 벡터·키워드 검색을 끈다" 는 `vector-store=platform` 일 때만 해당하고, `qdrant` 를 고르면 Tibero 설치본에서도 켜진다.

## 배경

벡터 검색은 지금 PostgreSQL 확장(pgvector·pg_trgm)에 묶여 있다.

- Tibero 설치본은 벡터·키워드 검색이 꺼져 있다(ADR 0001 결정 3, "새 벡터 DB 를 도입하기 전까지").
- 데이터 저장소가 Tibero 인 테넌트는 행 검색을 못 쓴다(`DataStoreBackend.supportsRowSearch()` 가 false).
- 행 검색은 행 수만큼 벡터가 쌓여 PG 한 대로 버티기 어려운 규모가 될 수 있다.

그래서 플랫폼 DB 처럼 **벡터 저장소도 설치할 때 고르게** 한다.

### 사용자가 정한 것(2026-11-12)

1. 벡터 저장소를 **인터페이스 뒤로** 뺀다. 첫 구현은 pgvector(기존 코드)와 Qdrant 이고, 다른 엔진(OpenSearch 등)을 나중에 구현체로 붙일 수 있어야 한다.
2. **PG 설치본도** 별도 벡터 DB 를 고를 수 있다(4가지 조합 모두 지원).
3. **행 검색도 범위에 넣는다.**
4. **새 설치만** 지원한다. 데이터가 든 설치본을 다른 벡터 저장소로 옮기는 도구는 만들지 않는다.
5. 키워드 검색은 **Qdrant 안에서 sparse 벡터**로 해결한다. 처음에는 Nori 형태소 + 음절 바이그램으로 정했다. PoC(WD-716) 결과를 보고 **바이그램 위주**로 바꿨다(2026-11-12, 결정 8).

### Qdrant 를 고른 이유(요약)

| 기준 | Qdrant | OpenSearch | Milvus |
|---|---|---|---|
| 필터 결합 k-NN(tenant·보안 등급·dataset 필터가 매번 붙음) | 강함(필터 인지 HNSW) | 보통 | 보통 |
| 한국어 키워드 | 앱에서 음절 바이그램 sparse 로 보완(결정 8) | nori+BM25 기본 제공 | 약함 |
| 운영 무게(고객 클러스터에 Helm 설치) | 단일 바이너리, 가벼움 | JVM, 무거움 | etcd·MinIO·MQ, 매우 무거움 |
| 라이선스 | Apache 2.0 | Apache 2.0 | Apache 2.0 |
| 확장 | 단일 노드로 시작, 분산 모드로 수평 확장(샤드·복제) | 가능 | 수십억 건 |

OpenSearch 는 한국어 키워드(특히 오타 — 퍼지 질의)가 문제가 될 때의 대안으로 남긴다. 결정 2 의 포트 구조라 구현체만 바꾸면 된다. PoC(WD-716)에서 필터 k-NN·지연·클러스터·폐쇄망은 Qdrant 로 기준을 통과했다. 키워드는 바이그램이 pg_trgm 보다 전체로 앞섰지만, 오타·정확 낱말 질의에서 근소하게 뒤졌다(결정 8).

## 현재 구조

벡터를 **저장하는** 곳은 firehub-api 뿐이다. ai-agent 의 graphrag 는 임베딩을 api(`apiClient.embed`)에서 받아 메모리에서 코사인 유사도로 이름을 묶을 뿐(`semantic-resolver.ts`) 벡터를 저장하지 않는다. 지식그래프(Neo4j)는 벡터 색인이 아니라서 범위 밖이다.

| 용도 | 벡터·키워드 위치 | 접근 경로 | 같은 DB 라서 되는 것 |
|---|---|---|---|
| 데이터셋 의미·키워드 검색 | 플랫폼 DB `dataset_embedding_vec_{1024,1536}`, `dataset_embedding.source_text`(pg_trgm) | `DatasetEmbeddingRepository`, `DatasetSearchRepository` | 가시성 SQL 조인, 복합 FK CASCADE, 백로그 anti-join(`NOT EXISTS`) |
| 문서 청크 검색(RAG) | 플랫폼 DB `document_chunk_vec_{1024,1536}`, `document_chunk.content`(pg_trgm) | `DocumentChunkRepository` | 위와 같음. 청크와 벡터를 한 트랜잭션에서 저장(`insertBatch`) |
| 행 검색 | 데이터 저장소(테넌트 PG) `fh_search_{datasetId}`(+`_prev`) | `RowSearchIndex` → `PgRowSearchIndex`(`@DataStoreDb`) | 원본 테이블과 조인한 구조화 필터, 원본 대조 삭제(`deleteMissing`) |

알아 둘 사실:

- **삭제는 FK CASCADE 에 기댄다.** 데이터셋 삭제(`DatasetService` 의 `deleteById`), 문서 삭제·재적재 정리(`DocumentIngestionService`)는 벡터를 지우는 명시 호출이 없다. 명시 호출은 행 색인 `rowSearchIndex.drop` 뿐이다.
- **원문이 바뀌어도 벡터를 바로 무효화하지 않는다.** `upsertSourceText` 는 원문만 고치고(내용이 같아도 늘 갱신), 비동기 재임베딩이 덮어쓸 때까지 옛 벡터가 남는다.
- **하이브리드 융합은 서비스가 Java 로 한다.** `DatasetSearchService`·`DocumentSearchService`·`RowSearchService` 가 의미 목록과 키워드 목록을 따로 받아 `RankFusion`(RRF)으로 합친다. 행 검색 응답의 `matchedBy`, 단계별 기능 가드, 임베딩 실패 시 키워드만으로 답하기(행 검색만), 모델·차원이 맞을 때만 의미 검색하기가 이 구조에 기댄다.
- **MyBatis(Tibero) 구현에는 벡터 코드가 없다.** 벡터·트라이그램 메서드는 모두 `PlatformFeatureOff` 를 던지는 빈 메서드다.
- **기능 판정은 두 군데다.** 카탈로그·청크 검색은 `PlatformDbFeatures`(→ `/platform-db/capabilities` 의 `vectorSearch`·`trigramSearch`)로, 행 검색은 `DataStoreBackend.supportsRowSearch()`(→ 관문 `RowSearchBackendGuard`, 배경 `RowSearchSweepScheduler`, 응답 `DataStoreCapabilitiesController` 의 `rowSearch` → 웹 검색 탭)로 판정한다.
- **`RowSearchIndex` 는 반환값만 엔진 중립이다.** row_id·점수만 돌려주지만, 입력 `CompiledFilter` 는 별칭 `t` 를 참조하는 PG SQL 조각이고, `rebuildReusing`·`hasPrev`·`dropPrev` 는 `_prev` 테이블을, `deleteMissing` 은 원본과의 anti-join 을 전제로 한다. 반면 `RowFilter` 는 구조화 조건이라 엔진 중립이다.
- `DatasetVisibility` 는 SQL 이 아니라 판정 입력(자격·AI 문맥)을 담는 값 타입이다(WD-485). 다만 "볼 수 있는 dataset_id 목록" 을 돌려주는 메서드는 아직 없다(SQL 렌더만 있다).

## 결정

### 1. 설정 축 — `firehub.vector-store.type`

| 값 | 데이터셋·문서 벡터·키워드 | 행 검색 |
|---|---|---|
| `platform` (기본) | 플랫폼 DB 가 PG 면 pgvector·pg_trgm, Tibero 면 꺼짐 | 데이터 저장소가 PG 면 pgvector, 아니면 꺼짐 |
| `qdrant` | Qdrant | Qdrant(데이터 저장소 종류와 무관) |

- 기본값은 지금 동작과 같다. 설정을 바꾸지 않으면 아무것도 달라지지 않는다.
- 플랫폼 DB 축(`firehub.platform-db.type`)과 독립이다. 조합 4가지(PG/Tibero × platform/qdrant)를 모두 지원한다.
- 빈 조건은 값마다 메타 애노테이션을 둔다(`@ConditionalOnPlatformVectorStore`, `@ConditionalOnQdrantVectorStore` — `@ConditionalOnTiberoPlatformDb` 와 같은 `@ConditionalOnProperty` 방식). 설정값 검사는 `PlatformDbStartupGuard.resolve` 처럼 모르는 값이면 기동을 멈춘다.

### 2. 포트(인터페이스)와 구현체

서비스는 구현체를 모른다. 구현체는 **pgvector·Qdrant·꺼짐** 세 가지다.

| 포트 | 하는 일 | 구현체 |
|---|---|---|
| `DatasetVectorIndex` | 데이터셋 dense·키워드 쓰기, dense 만 지우기, 전부 지우기, **의미 목록·키워드 목록을 따로** 검색, 차원 정리 | `PgDatasetVectorIndex`(기존 jOOQ 코드 이동), `QdrantDatasetVectorIndex`, `OffDatasetVectorIndex`(Tibero+platform, 지금 `PlatformFeatureOff` 역할) |
| `ChunkVectorIndex` | 문서 청크 같은 일 | `Pg…`, `Qdrant…`, `Off…` |
| `RowSearchIndex`(서명 변경) | 행 검색 색인 생명주기·쓰기·의미/키워드 목록 검색 | `PgRowSearchIndex`(기존), `QdrantRowSearchIndex`, (꺼짐은 지금처럼 기능 판정으로 막음) |
| `VectorStoreFeatures` | 카탈로그·청크 검색과 행 검색이 각각 되는지, 저장소가 지금 응답하는지 | 설정 축 + 플랫폼 DB + 데이터 저장소 + 상태 점검 |
| `VectorStoreLifecycle` | 기동 때 컬렉션·색인 준비, 스키마 버전 확인, 상태 점검 | `Pg…`(no-op, Flyway 가 맡음), `Qdrant…` |

- **융합은 서비스에 남긴다.** 포트는 의미 목록과 키워드 목록을 따로 돌려주고, 서비스가 지금처럼 `RankFusion` 으로 합친다. 그래야 `matchedBy`·단계별 가드·키워드 폴백·`vectorsUsable` 판정과 pgvector 설치본의 순위 규칙이 그대로다. Qdrant 어댑터는 키워드 쪽을 **키워드 목록 하나로** 돌려준다(pg_trgm 한 목록과 같은 자리 — 의미:키워드 비중 1:1 유지). 1차는 바이그램 sparse 하나라 합칠 것이 없다. 나중에 Nori 를 더하면 어댑터 안에서 바이그램과 합친다(결정 8).
- **`RowSearchIndex` 서명을 바꾼다.**
  - 검색 입력을 `CompiledFilter`(PG SQL) 대신 `RowFilter`(구조화)로 받는다. Pg 구현은 안에서 지금처럼 SQL 로 컴파일해 조인하고, Qdrant 구현은 결정 9 의 2단계로 처리한다.
  - `rebuildReusing`·`hasPrev`·`dropPrev` 를 테이블 중립 이름(세대 시작·이전 세대 유무·이전 세대 정리)으로 바꾼다. Pg 구현은 `_prev` 테이블, Qdrant 구현은 `generation` payload 로 구현한다.
  - `deleteMissing`(원본 대조 삭제)은 결정 9 에서 다룬다.
- 기존 리포지토리에서 **벡터·트라이그램 연산을 떼어** 위 포트로 옮긴다. 기반 테이블 연산(원문·청크 저장, 전체 수, 원문 조회, 표시 열)은 플랫폼 리포지토리에 남는다. 그러면 `MyBatisDatasetSearchRepository` 는 통째로 사라지고, MyBatis 쪽 빈 메서드는 `Off…` 구현으로 옮겨진다.
- **새 플랫폼 리포지토리 메서드:** "이 가시성으로 볼 수 있는 dataset_id 목록"(결정 5)과 "id 목록으로 결과 표시 필드 읽기"(데이터셋 이름·설명·카테고리, 청크 본문·파일명). jOOQ·MyBatis 두 벌과 두 계정 RLS 계약 테스트가 필요하다.
- 새 엔진을 붙이려면 포트 3개 + `VectorStoreLifecycle` 구현과 메타 애노테이션 하나만 추가한다. 서비스·컨트롤러는 바꾸지 않는다.
- 포트마다 **추상 계약 테스트**를 두고 구현체별 하위 클래스로 같은 시나리오를 돌린다(ADR 0001 의 계약 테스트 방식). 테넌트 교차 차단 시나리오를 반드시 넣는다.

빈 조합:

| 플랫폼 DB | vector-store | 카탈로그·청크 포트 | 행 검색 포트 |
|---|---|---|---|
| PG | platform | Pg | Pg(데이터 저장소가 PG 인 테넌트만) |
| Tibero | platform | Off | Pg(데이터 저장소가 PG 인 테넌트만) |
| PG | qdrant | Qdrant | Qdrant(모든 테넌트, 단 Tibero 데이터 저장소는 단계 8 이후) |
| Tibero | qdrant | Qdrant | 위와 같음 |

### 3. 기능 판정 — 한 곳으로 모은다

- `VECTOR_SEARCH`·`TRIGRAM_SEARCH` 판정을 `PlatformDbFeatures` 에서 떼어 `VectorStoreFeatures` 로 옮긴다. 근거가 "플랫폼 DB 종류" 에서 "벡터 저장소 설정" 으로 바뀐다. 서비스의 건너뛰기 분기는 그대로 두고 물어보는 대상만 바꾼다.
- `supportsRowSearch()` 를 쓰는 **세 곳**(`RowSearchBackendGuard`, `RowSearchSweepScheduler`, `DataStoreCapabilitiesController`)도 `VectorStoreFeatures` 에 묻게 한다.
- **카탈로그·청크 판정과 행 판정을 나눈다.** 지금 `AiVectorPurgeService` 는 플랫폼 DB 기능이 꺼지면 행 검색 정리까지 건너뛴다. 그래서 Tibero 플랫폼 + PG 데이터 저장소 조합에서는 행 색인 외부 벡터가 즉시 정리되지 않고 다음 스윕을 기다린다(기존 결함 — 단계 3 에서 함께 고친다).
- **qdrant 조합의 행 검색은 WD-722 전까지 끈다**(`QdrantVectorStoreFeatures.supportsRowSearch` 가 늘 거짓, WD-720). 기본 판정(데이터 저장소가 PG 면 켜짐)을 쓰면 행 색인이 데이터 저장소 pgvector 에 쌓이기 시작하고, 새 설치만 지원하므로(사용자 결정 4) 나중에 옮길 길이 없다. 행 데이터의 Qdrant 테넌트 격리 수준(공유 컬렉션·전용 샤드·테넌트별 컬렉션·인스턴스)도 WD-722 에서 정한다.
- **기능 응답에 "저장소 일시 중단" 을 더한다.** 지금 응답(`vectorSearch`·`trigramSearch`, `rowSearch`)은 "설치본이 지원하는가" 만 나타낸다. Qdrant 가 내려가 있을 때 안내하려면 필드가 하나 더 필요하다(예: `vectorStoreAvailable`). 웹(검색 화면·검색 탭)과 ai-agent 도구 안내를 같이 고친다. 기존 필드는 유지해 하위 호환을 지킨다.

### 4. 백로그 판정 — 플랫폼 DB 에 "임베딩한 원문" 표시

지금 `countMissing`/`findMissing` 은 기반 테이블과 벡터 테이블을 anti-join 한다(모델 일치 포함). 벡터가 다른 저장소로 가면 이 조인을 할 수 없다.

이 결정은 동작을 바꾸는지에 따라 세 이슈로 나눠 넣는다.

**① 공간 표시 — WD-717 (pgvector 동작 변경 없음)**
- `dataset_embedding`·`document_chunk` 에 공간 표시 열 `embedded_dim`·`embedded_model` 을 둔다(V160, PG·Tibero 두 벌). **벡터 행의 거울**이다. 벡터 행이 있으면 그 차원·모델이 들어 있고, 없으면 NULL 이다. PG 는 지금 벡터 행으로 백필하고(`<unknown>` 모델도 그대로 옮겨 계속 백로그로 남는다), Tibero 는 벡터가 없어 열만 더한다.
- **채우고 비우는 규칙:** 벡터를 쓰거나 지우는 경로(`upsertEmbeddings`·`insertBatch`·`deleteVectors`·`deleteVectorsOf`·`deleteOtherDimensions`)가 벡터 SQL 과 같은 트랜잭션에서 표시 열을 채우거나 비운다.
- 백로그(`countMissing`·`findMissing`)와 진행률 분자(`countEmbedded`)는 이 열로 판정한다. 판정식은 `embedded_dim`·`embedded_model` 이 NULL 이거나 지금 공간과 다르면 백로그다. Tibero 로 옮길 수 있게 NULL 을 명시적으로 비교한다(`IS DISTINCT FROM` 을 쓰지 않는다). 거울이라 pgvector 설치본에서는 예전 anti-join 과 결과가 같다(동등성 테스트로 확인).
- 표시 열 쓰기는 벡터 SQL 과 나눠 둔다. 포트를 추출할 때 벡터 SQL 은 Pg 구현으로 옮기고, 표시 열 쓰기는 플랫폼 리포지토리에 남긴다.

**② MyBatis 실제 SQL — WD-718 (포트 추출)**
- Tibero 설치본에서는 Qdrant 가 들어오기 전까지 표시 열을 채우는 쪽이 없다. 그래서 MyBatis `countMissing`·`findMissing`·`countEmbedded` 를 빈 메서드에서 실제 SQL 로 바꾸는 일은 Off 구현과 4조합 빈 조건을 정하는 포트 추출에서 함께 한다. 계약 테스트(`supportsVectorSearch()` 훅 조정)와 구현 짝 허용 목록 갱신도 같이 한다.

**③ 원문 해시 — WD-720 (Qdrant 검색, 새 동작)**
- **임베딩한 원문의 해시**를 표시에 더한다. 원문이 바뀌면 백로그로 잡히므로 백로그·진행률이 달라지는 **새 동작**이다. 그래서 ①과 떼어 키워드 표시가 필요한 WD-720 에서 다룬다.
- **구현(V161, WD-720):** `dataset_embedding` 에 `source_hash`(지금 원문 해시)·`embedded_hash`(벡터를 만든 원문 해시)·`keyword_marker`, `document_chunk` 에 `content_hash`·`keyword_marker` 를 둔다. 표시를 비우는 대신 **두 해시를 비교**한다 — `upsertSourceText` 가 원문과 같은 문장에서 `source_hash` 를 쓰고, 판정식은 `source_hash` 가 있고 `embedded_hash` 가 그와 다르거나 없으면 백로그로 본다. 그래서 내용이 같은 편집(태그 순서만 바뀜 등)은 재임베딩을 부르지 않는다. 진행률 분자(`countEmbedded`)도 낡은 행을 세지 않는다. 청크는 본문이 바뀌지 않아 dense 쪽 해시가 없다.
- **채우는 규칙:** 벡터 쓰기와 같은 트랜잭션에서, 벡터를 만든 원문 해시를 넘겨 "그 해시 = 지금 원문 해시" 일 때만 채운다(조건부 UPDATE). 옛 원문으로 돌던 늦은 재임베딩(재색인 리스너·재임베딩 잡)이 표시를 잘못 채우지 않는다 — 벡터는 써지지만 그 행은 백로그에 남아 다음 실행이 새 원문으로 다시 만든다(`TenantReembedJobTest` 로 고정).
- **해시가 없는 기존 행(V161 이전):** 백필하지 않는다. 해시는 Java 한 곳(`SourceHash`, UTF-8 SHA-256 hex)에서만 계산한다 — SQL 로 계산하면 Tibero 와 계산 위치가 갈라진다. 해시가 NULL 이면 예전처럼 공간만으로 판정하고(업그레이드 직후 전체 재임베딩을 부르지 않는다), 다음 벡터 쓰기가 넘긴 해시로 `source_hash` 를 함께 채운다. 기존 벡터가 저장된 원문으로 만든 것이라는 V160 백필과 같은 전제다.
- **5분 스윕 중복:** 편집 직후 재색인 리스너가 지금 원문으로 끝나면 표시가 차서 스윕은 할 일이 없다. 리스너가 도는 몇 초 사이에 스윕이 겹치면 그 데이터셋이 한 번 더 임베딩될 수 있다 — 결과는 같고 비용만 한 건이라 감수한다.
- **키워드 쪽:** pgvector 구현은 원문 열 자체가 키워드 색인이라 표시가 필요 없다(포트 `separateKeywordIndex()` 가 거짓 — 키워드 경로가 바로 끝난다). Qdrant 구현은 sparse 벡터를 따로 쓰므로 **키워드 표시** `keyword_marker = "{규칙}:{원문 해시}"`(규칙은 `bigram-v1`)를 둔다. 표시가 없거나, 원문 해시가 없거나, 지금 규칙·해시와 어긋나면 키워드 백로그 스윕(`KeywordBacklogSweepScheduler`, 5분, 활성 테넌트 순회)이 다시 쓴다. 규칙 버전을 올리면 모든 표시가 어긋나 다시 쓴다. 키워드 sparse 는 임베딩 API 없이 만들어 싸고, 임베딩 미설정 테넌트에도 쓴다.
- 마이그레이션은 PG(Flyway)와 Tibero 기준선 **두 벌**이 필요하다(ADR 0001 결정 2 규칙).
- 행 검색은 색인 자체가 `source_hash` 를 들고 있어(`existingHashes`) 표시 열이 필요 없다.

### 5. 보안 — 가시성 2단계, 테넌트 강제, 원문 텍스트 미보관

- **가시성:** 검색 포트는 지금처럼 `DatasetVisibility` 를 받는다. pgvector 구현은 지금처럼 SQL 로 렌더한다. Qdrant 구현은
  1. 플랫폼 DB 에서 볼 수 있는 dataset_id 를 구해(결정 2 의 새 메서드) payload 필터(`dataset_id in …`)로 넘기고,
  2. topK 의 몇 배(기본 3배)를 받아 플랫폼 DB 에서 한 번 더 확인한 뒤 자른다. 확인 항목은 지금 SQL 의 조건 전부다: 가시성, 모델 일치, 데이터셋 `storage_type`, 문서 청크는 `document_file.status = 'COMPLETED'` 와 요청한 `datasetIds`. 이게 빠지면 적재 중이거나 실패한 문서의 청크가 결과에 섞인다.
  2단계 전에는 결과를 밖으로 내보내지 않는다. 결과에 붙일 표시 필드도 이때 플랫폼 DB 에서 읽는다.
  - **구현(WD-720):** 두 단계의 SQL 은 `SearchCandidateRepository`(jOOQ·MyBatis 두 벌, 두 계정 RLS 계약 테스트)에 모았다. 1단계는 요청한 `datasetIds`·`storageType` 과 미리 교집합을 구해 빈 집합이면 Qdrant 를 부르지 않는다. 2단계는 의미 검색이면 표시 열의 공간(차원·모델) 일치도 본다 — 공간이 바뀐 뒤 아직 지우지 못한 옛 점이 섞이지 않게. 확인을 통과한 것만 Qdrant 점수 순서로 topK 까지 자른다.
- **호스팅 경계:** `AiHostingResolver`·`EmbeddingAiGate` 는 "등급 AI 정책 × 임베딩 공급자 호스팅" 으로 허용을 판정한다. 벡터 저장소가 원격(예: Qdrant Cloud)에 있으면 판정 밖의 반출 경로가 생긴다. 그래서 **Qdrant 는 api 와 같은 신뢰 경계 안의 직접 설치만 허용**한다. 원격 저장소를 허용해야 하면 등급 게이트가 벡터 저장소 위치까지 보도록 따로 설계한다.
- **테넌트:** 모든 포인트에 `tenant_id` payload(`is_tenant` 색인)를 둔다. 어댑터는 `TenantContext` 없이 호출되면 예외를 던진다(`IndexRef` 가 테넌트를 타입에 싣고 `PgRowSearchIndex` 가 테넌트 불일치를 검사하는 것과 같은 원칙). 계약 테스트가 교차 테넌트 0건을 고정한다.
- **원문 텍스트는 Qdrant 에 넣지 않는다.** id·벡터·필터용 payload(`tenant_id`·`dataset_id`·`model`, 청크는 `document_id`·`chunk_id`)만 둔다. 원문 해시는 플랫폼 DB 에만 있다. 시연·고객 데이터(매출·고객 정보)가 읽을 수 있는 형태로 한 곳 더 복제되는 것을 막는다. 다만 키워드 sparse 벡터에는 **토큰 해시와 빈도**가 들어간다. 원문으로 되돌릴 수는 없지만 흔한 단어는 사전 대입으로 추정될 수 있다는 점을 감수한다.
- **접속:** API 키(읽기 전용 키 분리 가능), TLS, 네트워크 정책으로 api 파드만 접근하게 한다.

### 6. 정합성 — 명시 삭제, 순번, 아웃박스, 대조 스윕

같은 DB 의 CASCADE·트랜잭션 삭제를 외부 저장소에서는 쓸 수 없다.

- **명시 삭제 호출을 새로 넣는다.** 지금은 CASCADE 에 기대 호출이 없는 경로에 포트 삭제 호출을 더한다: 데이터셋 삭제, 문서 삭제, 문서 재적재 정리, 재구축 임시 백업 폐기. pgvector 구현은 이 호출을 no-op 또는 같은 트랜잭션 삭제로 처리해 지금 동작을 지킨다.
- **dense 만 지우는 경로를 구분한다.** 외부 벡터 정리(`deleteVectors`·`deleteVectorsOf`)는 키워드는 남겨야 한다. Qdrant 구현은 의미 컬렉션의 점만 지우고 키워드 컬렉션은 그대로 둔다(결정 7 — dense 와 키워드는 다른 컬렉션).
- **Qdrant 구현의 삭제는 아웃박스로 보낸다.** 같은 트랜잭션에서 **벡터 아웃박스 테이블**에 행을 쓰고, 커밋 뒤 워커가 Qdrant 에 반영한다. 서비스 코드는 구현을 모른다.
- **삭제와 다시 쓰기의 순서 경합을 순번으로 막는다.** 예: 등급이 불허로 바뀌어 삭제가 쌓임 → 처리 전에 허용으로 돌아옴 → 재임베딩이 Qdrant 에 바로 씀 → 뒤늦게 워커가 새 벡터를 지움.
  1. 플랫폼 DB **시퀀스**에서 순번을 받아 포인트를 쓸 때 payload `seq` 에 싣고, 아웃박스 삭제 행에도 싣는다. 워커는 "그 순번보다 작은 포인트만" 지운다. 시각을 쓰지 않는 이유: api 파드 시계와 DB 시계를 비교하게 되고, Tibero 는 TSTZ 열을 금지한다(WD-260).
  2. 삭제를 쌓는 트랜잭션에서 표시 열을 비운다(결정 4). 다시 쓰기가 끝나야 표시 열이 다시 찬다.
- **청크 적재 순서:** 청크 저장(id 획득, `insertChunks`) → 커밋 → 키워드 쓰기 → 벡터 쓰기(`upsertEmbeddings`, 표시 열을 같은 트랜잭션에서) → 문서 완료 순서로 나눈다. **pgvector 설치본도 같은 순서**다(2026-11-14 사용자 결정 — 코드 경로 하나). 예전 `insertBatch`(청크와 벡터 한 트랜잭션)는 없앴다. 벡터 쓰기가 실패해도 문서는 "완료" 로 둔다 — 표시 열이 비어 재임베딩 백로그가 채우고, 그동안 청크는 키워드에는 보이고 의미 검색에만 빠진다. 예전처럼 실패로 돌리면 커밋된 청크와 상태가 어긋난다.
- **부모 없는 id:** 벡터를 쓰기 전에 리포지토리가 지금 있는 행만 골라 포트에 넘긴다(청크는 데이터셋·문서 id 도 함께 읽어 `ChunkRef` 로). pgvector 의 `INSERT…SELECT` 0행과 같은 뜻이고, 별도 저장소에 고아 점이 생기지 않는다.
- **구현(WD-721):**
  - 테이블 `vector_outbox`(V162, PG·Tibero 두 벌)는 `seq`(기본 키, 시퀀스 `vector_seq`)·`kind`·`target_id`·`keep_dim`·`attempts`·`last_error` 열을 둔다. 부모(데이터셋·문서)에 FK 를 두지 않는다 — 부모 삭제와 같은 트랜잭션에 쌓는 행이라 CASCADE 가 걸리면 함께 지워진다. 테넌트 FK 만 `ON DELETE CASCADE` 다. 종류는 `DATASET_DENSE`·`DATASET_ALL`·`CHUNK_DENSE`·`CHUNK_ALL_OF_DATASET`·`CHUNK_ALL_OF_DOCUMENT` 다. 리포지토리는 `VectorOutboxRepository`(jOOQ·MyBatis, 두 계정 RLS 계약 테스트)다.
  - Qdrant 구현의 삭제 메서드는 저장소를 부르지 않고 호출한 트랜잭션에 행을 쌓는다. 데이터셋 삭제·문서 삭제(이번에 한 트랜잭션으로 묶었다)·외부 벡터 정리·차원 정리가 모두 이 길이다. "지운 수" 는 표시 열로 센다(`VectorMarkerRepository`) — 저장소에 묻지 않으므로 Qdrant 가 내려가 있어도 정리 경로가 실패하지 않는다. 쓰기 뒤 다른 차원 정리도 아웃박스로 간다(표시가 다른 차원을 가리키던 데이터셋만).
  - 점 쓰기(dense·키워드)는 호출마다 `vector_seq` 에서 순번을 받아 payload `seq` 에 싣는다. 워커와 스윕의 삭제 조건은 `seq < 행 순번 OR seq 없음` 이다 — 순번이 없는 점(순번을 싣기 전에 쓴 점)도 지워야 하기 때문이다. 문서 재적재는 같은 문서 id 로 새 청크를 쓰므로, 이 조건이 없으면 늦은 삭제가 새 청크를 지운다(`QdrantVectorConsistencyTest` 로 고정).
  - 워커 `VectorOutboxWorker` 는 5초마다 활성 테넌트의 행을 순번 순서로 반영하고, 반영한 행만 지운다. 삭제는 멱등이라 잠금 없이 돈다. 실패하면 시도 수·오류를 남기고 그 테넌트의 이번 회차를 멈춘다.
  - `AiVectorPurgeService` 는 WD-718 에서 이미 리포지토리 → 포트로 지우게 바뀌어 따로 고치지 않았다.
- **대조 스윕:** 테넌트별 주기 실행 틀(`TenantScopedRunner.forEachActiveTenant`)을 빌려 쓴다(`QdrantReconcileSweeper`, 30분). 두 방향을 본다.
  - Qdrant 에 있는데 표시가 그 점을 가리키지 않음 → 지운다(아웃박스 유실 대비). dense 는 표시가 그 차원·모델일 때만 남긴다 — 등급 불허로 표시를 비웠는데 아웃박스가 사라진 벡터도 여기서 지워진다. 키워드는 원문 행이 있으면 남긴다(정리 대상이 아니고, 표시만 빠진 점은 키워드 백로그가 다시 쓴다).
  - 표시 열은 찼는데 Qdrant 에 포인트가 없거나 모델이 다름 → 표시 열을 비워 백로그로 돌린다. 쓰기는 점을 쓴 뒤 같은 트랜잭션에서 표시를 채우므로 이 방향은 경합이 없다.
  - **울타리:** 첫째 방향은 쓰기와 경합한다 — 점을 쓰고 표시를 채우는 트랜잭션이 커밋되기 전이면 "표시 없는 점" 으로 보인다. 그래서 지난 회차를 시작할 때 받은 순번보다 작은 점만 보고, 지울 때도 같은 조건을 붙인다. 울타리는 파드 메모리라 재기동 뒤 첫 회차는 첫째 방향을 건너뛴다.
- **워커와 테넌트:** 아웃박스는 테넌트 테이블이라 RLS 가 걸린다. 1차는 `forEachActiveTenant` 로 테넌트마다 처리한다. 비활성 테넌트의 행은 남는다(감수 — 테넌트를 다시 활성으로 돌리면 처리된다). 지금 앱에는 테넌트 행을 지우는 경로가 없다. 그런 기능이 생기면 그 테넌트 점을 `tenant_id` 필터로 Qdrant 에서 지우는 정리를 함께 넣어야 한다 — 아웃박스 행은 테넌트 FK `ON DELETE CASCADE` 로 함께 사라지고 대조 스윕도 활성 테넌트만 돌기 때문이다(후속). 테넌트를 넘어 한 번에 훑어야 하면 알림 아웃박스의 `outbox_tenant_ids` 같은 우회 루틴을 PG SECURITY DEFINER 함수와 Tibero `FH_TENANT` 패키지에 더해야 한다(ADR 0001 결정 7 — 우회는 소유자 패키지 안에서만).
- **깨우기:** 기존 `OutboxWakeupChannel` 은 알림 전용 고정 채널이라 쓰면 알림 워커가 깨어난다. 1차는 **폴링만** 한다.
- 반영 전의 틈은 결정 5 의 사후 확인이 막는다(지워졌거나 불허된 데이터셋은 결과에서 빠진다).

### 7. Qdrant 컬렉션 구성

- **카탈로그·청크는 차원별 컬렉션:** `fh_datasets_{dim}`, `fh_chunks_{dim}`. 이름은 `EmbeddingDimension`(지금 1024·1536)에서 만들고(차원별 SQL 테이블 이름의 단일 출처와 같은 자리), 기동 때 모든 값의 컬렉션을 미리 만든다. 새 차원은 열거형에 값을 더하면 컬렉션이 생긴다.
- **행 검색 컬렉션은 차원이 정해져 있지 않다.** 행 검색은 테넌트 공급자의 차원을 그대로 쓰고(상한 `MAX_DIM = 2000`), 키워드 전용 색인은 `KEYWORD_ONLY_DIM = 1` 이다. 그래서 `fh_rows_{dim}` 을 **필요할 때 만들고**, 키워드 전용은 dense 없는 `fh_rows_kw` 에 둔다.
- **카탈로그·청크는 dense 와 키워드를 다른 컬렉션에 둔다**(2026-11-14 사용자 결정, WD-720 — 처음 설계는 한 포인트에 named vector 둘이었다). 의미 컬렉션 `fh_datasets_{dim}`·`fh_chunks_{dim}` 에는 `dense` 만, 키워드 컬렉션 `fh_datasets_kw`·`fh_chunks_kw` 에는 `kw_bigram`(음절 바이그램, sparse, `modifier: idf`)만 있다. 키워드는 임베딩 차원과 무관해서 한 포인트에 두면 ① 임베딩 미설정 테넌트의 키워드를 둘 차원이 없고 ② 차원을 바꿀 때 키워드도 옮겨야 하고 ③ dense 쓰기와 키워드 쓰기가 같은 포인트를 두고 경합한다(Qdrant upsert 는 포인트 전체를 갈아 끼운다). "dense 만 지우기" 는 의미 컬렉션에서 점을 지우는 것이 된다(키워드는 그대로). 대신 Qdrant 서버 측 하이브리드(prefetch 융합)는 쓰지 않는다 — 융합은 서비스 `RankFusion` 이 한다(결정 2). 행 검색 컬렉션 구성(지금은 한 포인트에 둘)은 WD-722 에서 다시 정한다. `kw_nori` 는 이름만 예약한다(결정 8).
- **payload:** `tenant_id`, `dataset_id`, `model`(공간 일치 검사 — #392 원칙), `seq`(결정 6), 행 검색은 `row_id`·`chunk_no`·`source_hash`·`generation`.
  - `tenant_id` 는 **키워드형**(값은 문자열 `"12"`)으로 색인하고 `is_tenant` 를 켠다. Qdrant 1.20 은 정수형 색인에 준 `is_tenant` 를 오류 없이 버린다(2026-11-13 실측, WD-719 — PoC `knn.py` 의 정수형 설정도 실제로는 적용되지 않았다). 청크는 문서 삭제용으로 `document_id` 도 색인하고, 사후 확인에서 점을 청크로 되짚으려고 `chunk_id` 를 싣는다(거르지 않아 색인하지 않는다).
- **포인트 id 는 결정적으로 만든다.** (종류, tenant, 대상 id[, chunk_no, generation])로 UUIDv5 를 만든다. 같은 대상을 다시 쓰면 덮어쓰게(upsert) 된다.
- **공유 컬렉션 안에서는 `tenant_id`+`model` 필터가 반드시 붙는다.** 테넌트마다 임베딩 공간이 다르기 때문이다.
- **데이터셋마다 컬렉션을 만들지 않는다.** 데이터셋 수만큼 컬렉션이 생기면 운영이 무너진다.
- **클러스터 모드로 시작하고 샤드를 넉넉히 잡는다**(노드 1대여도 `cluster.enabled: true`, 샤드 수는 설정값, 기본 6). 오픈소스판은 리샤딩·자동 리밸런싱이 없어서(Cloud 전용) 나중에 노드를 늘릴 때 샤드 이동만으로 끝나게 하려는 것이다. PoC 에서 확인한 것:
  - 새 노드는 비어서 들어온다. `move_shard`(`stream_records`)로 손으로 옮긴다. 샤드 3개(약 10만 점, 1024차원)에 39초가 걸렸고, 그동안 검색 실패는 0건이었다.
  - 단독 모드로 쓰던 저장소에 클러스터 모드를 켜면 데이터는 남지만 **샤드 수가 1로 고정**된다(공식 문서에도 없는 경로). 그래서 처음부터 클러스터 모드로 시작한다.
- **메모리:** scalar(int8) 양자화와 원본 벡터 디스크 저장을 기본으로 켠다. v1.19부터 설정 이름이 `memory`(원본 `cold`, 양자화 `pinned`)로 바뀌었다. 예전 `on_disk`/`always_ram` 도 아직 받지만 새 이름을 쓴다. 검색 때 `rescore` 를 켠다(int8 은 기본으로 rescore 하지 않는다). PoC 수치(20만 점, 단일 노드): 필터 선택도 0.5~45% 에서 recall@10 0.993~1.0, p95 20~44ms.
- **스키마 버전:** 컬렉션 구성(named vector·payload 색인·양자화)에 버전을 매기고 `VectorStoreLifecycle` 이 기동 때 만들거나 확인한다. 어긋나면 기동을 멈춘다(Flyway 의 validate 와 같은 역할).

### 8. 키워드 검색 — 앱에서 음절 바이그램 sparse 벡터 생성

- **1차는 음절 바이그램 sparse 하나로 간다**(2026-11-12 사용자 결정, PoC 근거). 텍스트 → 낱말(공백·기호로 자름) 안의 음절 바이그램(한 음절 낱말은 그대로, 소문자) → 해시(murmur3)로 정수 id → BM25 TF 포화 값 → sparse 벡터. 질의는 같은 바이그램에 값 1 이다.
- IDF 는 Qdrant 가 sparse 벡터의 `modifier: idf` 로 계산한다.
- **PoC 근거(WD-716, KorQuAD 문단 10,645개·질의 1,500개, success@10):**

  | | 자연어 질문 | 정확 낱말 | 부분 일치 | 띄어쓰기 변형 | 오타 | 전체 |
  |---|---|---|---|---|---|---|
  | pg_trgm(지금) | 0.930 | 1.000 | 0.660 | 0.433 | 0.587 | 0.722 |
  | 바이그램 | 0.990 | 0.970 | 0.807 | 0.937 | 0.530 | 0.847 |
  | Nori(사전 없음) | 0.987 | 0.690 | 0.283 | 0.477 | 0.210 | 0.529 |
  | Nori+바이그램 한 벡터 | 0.990 | 0.953 | 0.717 | 0.840 | 0.513 | 0.803 |

  - Nori 를 섞을수록 정확 낱말·부분 일치·띄어쓰기·오타가 떨어지고, 보태는 건 자연어 질문 MRR +0.01~0.02 뿐이었다.
  - 사용자 사전이 없으면 복합어를 잘못 쪼갠다(`소방안전관리자` → `소방/안전관/리자`). 사전을 운영하는 부담에 비해 얻는 것이 작다.
- **감수하는 것:** 오타(-5.7%p)와 정확 낱말(-3%p)에서 pg_trgm 보다 조금 약하다. Qdrant 에는 퍼지 질의가 없다. 대신 띄어쓰기 변형(+50%p)·부분 일치(+15%p)·자연어 질문에서 앞선다.
- **Nori 는 나중에 더할 수 있게만 둔다.** sparse 이름 `kw_nori` 를 예약하고 1차에는 만들지 않는다. 사용자 사전(소방·행정 용어)을 갖춘 뒤 더하려면 어댑터 안에서 바이그램과 낮은 비중(RRF 0.2 안팎)으로 합치고, PoC 질의 세트로 다시 재서 바이그램 단독보다 나을 때만 켠다.
- 바이그램은 사전이 없어 분석기 변경에 따른 재색인이 거의 없다. 생성 규칙(정규화·낱말 자르기)을 바꾸면 키워드 표시를 비워 백로그로 다시 만든다.
- **쓰는 때:** 원문이 생기거나 바뀌는 곳 — `syncSourceText`(데이터셋·태그 편집, 지금은 쓰기 트랜잭션 안에서 동기로 돌아 키워드 검색이 즉시 보인다), 청크 적재(`insertBatch`·AI 불허 문서의 `insertChunksOnly`), 키워드 전용 행 색인. Qdrant 쓰기는 트랜잭션에 묶이지 않으므로 커밋 뒤에 쓰고, 실패하면 결정 4 의 키워드 표시로 백로그가 다시 쓴다. 그래서 Qdrant 설치본에서는 편집 직후 키워드 검색 반영이 **조금 늦을 수 있다**.
- **회복력의 범위:** 키워드 sparse 는 임베딩 API 없이 만들 수 있다. KEYWORD 모드와 행 검색 HYBRID(임베딩 실패 시 키워드만으로 답함)는 지금처럼 임베딩 장애에도 동작한다. 데이터셋·문서 검색의 HYBRID 는 지금도 임베딩 예외를 잡지 않으며, 이 ADR 은 그 동작을 바꾸지 않는다.
- 점수 척도가 바뀐다(pg_trgm `word_similarity` 0~1 → sparse IDF 내적). 융합은 순위 기반(RRF)이라 영향이 없지만, 단일 KEYWORD 모드에서 점수를 임계값으로 쓰는 곳이 있으면 다시 맞춘다.

### 9. 행 검색을 Qdrant 로 옮길 때 바뀌는 것

| 지금(PG) | Qdrant |
|---|---|
| 데이터셋마다 `fh_search_{id}` 테이블 | `fh_rows_{dim}`·`fh_rows_kw` 공유 컬렉션 + `dataset_id` 필터 |
| 원본 교체 시 `_prev` 테이블로 보관, 같은 해시 벡터 재사용 | `generation` 을 올린다. 옛 generation 에서 같은 `source_hash` 벡터를 **읽어서 다시 쓴다**(서버 쪽 복사가 없다). 끝나면 옛 generation 을 지운다 |
| 현재 색인 = 테이블 이름 | **현재 generation 을 `dataset_search_index` 에 기록**하고 검색 때 그 값으로 거른다. swap 뒤에는 row_id 가 새로 매겨지므로, 거르지 않으면 옛 포인트가 엉뚱한 행을 가리킨다 |
| 구조화 필터를 원본 테이블과 SQL 조인 | 아래 2단계 |
| `deleteMissing`: 원본과 anti-join | Qdrant 의 데이터셋 row_id 를 scroll 로 읽어 데이터 저장소와 대조(비싸서 스윕에서만) |
| `existingHashes`·`count`·`hasPrev` | payload 필터 조회·count API·generation 비교 |
| 10만 행 이하는 정확 스캔으로 재현율 보장 | 같은 기준으로 Qdrant `exact` 검색을 쓴다 |
| `clearEmbeddings`(키워드만 남김) | `dense` named vector 만 지움 |

구조화 필터(`RowFilter`)는 원본 테이블에 있는 열이라 Qdrant 혼자 평가할 수 없다.

1. 필터가 있으면 먼저 데이터 저장소에서 필터를 평가해 row_id 를 구한다. 개수가 상한(기본 1만) 이하면 Qdrant 에 id 필터로 넘긴다.
2. 상한을 넘으면 topK 를 넉넉히 받아 데이터 저장소에서 `row_id IN (…) AND 필터` 로 사후 확인한다.

열 값을 payload 로 복제하는 방식은 원문 텍스트 미보관 원칙(결정 5)과 충돌해서 쓰지 않는다.

`dataset_search_index.generation` 열은 데이터 저장소가 아니라 플랫폼 DB 테이블이다. PG·Tibero 마이그레이션 두 벌과 `SearchIndexStateRepository` jOOQ·MyBatis 구현을 고친다.

**Tibero 데이터 저장소 테넌트도 행 검색이 되려면** 데이터 저장소 쪽에 Tibero 짝이 필요하다(단계 8).

- `SearchSourceReader`: 원문 읽기뿐 아니라 `currentOid`(`regclass`), `countRows`, `fetchRows`, `fetchChanged`(`timestamptz`)가 PG 전용이다.
- `RowFilterCompiler`: `= ANY(?)`, `t."col"` 등 PG SQL.
- 증분 책갈피: `RowSearchSyncService` 가 PG 함수를 부르는 `IncrementalCursorService` 를 직접 쓴다. 이미 있는 `TiberoIncrementalCursor` 로 갈아 끼운다.

### 10. 새 설치만 — 설치 후 바꾸기 금지

- 설치된 벡터 저장소 종류를 `system_settings` 에 키 하나로 기록한다(전역 테이블, 테넌트 열·RLS 없음, PG·Tibero 모두 있음).
- 기록이 없을 때:
  - `dataset_embedding`·`document_chunk` 에 행이 하나라도 있으면 **`platform` 으로 확정**한다. 업그레이드하면서 qdrant 설정을 같이 켜도 데이터가 든 설치본은 통과하지 못한다.
  - 행이 없으면 지금 설정값으로 기록한다.
- 기록과 설정이 다르면 기동을 멈추고 이유를 로그에 남긴다.
- 기존 PG 설치본이 Qdrant 로 옮기는 길은 없다(결정 4). 필요해지면 "재임베딩 전량" 으로 따로 설계한다.

### 11. 설치·배포

- 설정은 환경변수로 받는다: `FIREHUB_VECTOR_STORE_TYPE`, `QDRANT_URL`, `QDRANT_API_KEY`, 샤드 수·양자화 옵션.
- **버전은 v1.20 이상으로 고정한다**(PoC 기준 v1.20.0). v1.20 은 노드 간 인증이 기본으로 켜진다. 노드 간 호출은 읽기·쓰기 키만 받으므로(읽기 전용 키·JWT 거부) 모든 노드에 같은 `api_key` 를 준다. 앱에는 읽기·쓰기 키를 준다.
- **TLS:** REST·gRPC 는 `service.enable_tls`, 노드 간은 `cluster.p2p.enable_tls` 로 켠다. PoC 에서 키가 없으면 401, 읽기 전용 키로 쓰면 403 인 것을 확인했다.
- **저장소는 블록 스토리지 PV 로 둔다.** FUSE·NFS 위에서는 Qdrant 가 데이터 손상 경고를 낸다.
- **노드를 늘리는 절차**는 자동이 아니다. 새 노드를 붙인 뒤 `move_shard` 로 샤드를 옮기는 절차를 배포 문서에 넣는다(결정 7).
- 폐쇄망 반입: 이미지는 gzip 기준 약 61MB이고, 운영 아키텍처(amd64)로 받는다. 공식 Helm 차트는 `image.repository`·`imagePullSecrets` 로 사설 레지스트리를 지정할 수 있다.
- 로컬: docker-compose 에 `qdrant` 서비스(프로필로 켬).
- k8s: 리포 밖 `~/k8s/smart-fire-hub` 차트에 Qdrant(공식 차트 의존 또는 StatefulSet)와 API 키 시크릿을 넣는다. 이미지는 버전을 고정하고 폐쇄망 반입 목록에 넣는다.
- 백업: Qdrant 스냅샷을 주기로 뜬다. 다만 벡터는 파생 데이터라 최악의 경우 재임베딩으로 복구할 수 있다.
- 단일 노드는 HA 가 없다. 내려가 있는 동안 검색은 기능 응답의 "저장소 일시 중단"(결정 3)으로 안내한다(5xx 로 터뜨리지 않는다).
- `.claude/docs/deploy.md` 에 설치·점검 절차를 더한다.

## 단계와 규모

| # | 내용 | 규모 | 선행 |
|---|---|---|---|
| 1 | **PoC(끝남, WD-716)**: 한국어 키워드(Nori·바이그램 sparse 대 pg_trgm), 필터 k-NN 지연·재현율(tenant + dataset_id 수천 개), 키워드 두 sparse 합치기 비중, 단독→클러스터 모드 전환, 폐쇄망 설치 — 결과는 WD-714 코멘트 | M | — |
| 2 | 공간 표시 열(`embedded_dim`·`embedded_model`, PG·Tibero 두 벌, 벡터 행의 거울), 채움·비움 규칙, 백로그·진행률 판정 전환(jOOQ). 판정의 MyBatis 실제 SQL 은 3번, 원문 해시는 5번으로 넘긴다(결정 4) | M | — |
| 3 | 포트 추출: `DatasetVectorIndex`·`ChunkVectorIndex`(의미·키워드 목록 분리)·`VectorStoreFeatures`·`VectorStoreLifecycle`, Pg·Off 구현으로 기존 코드 이동, 4조합 빈 조건·설정 축, 기능 판정 통합(`supportsRowSearch` 세 곳 포함, `AiVectorPurgeService` 판정 분리), 설치 종류 기록·판별(결정 10), 명시 삭제 호출 추가, 백로그 판정 MyBatis 실제 SQL(결정 4 ②), 추상 계약 테스트 | L | 2 |
| 4 | Qdrant 기반: 클라이언트·설정·`QdrantVectorStoreLifecycle`(컬렉션·스키마 버전), 바이그램 sparse 생성기, Testcontainers 하네스 | M | 1 |
| 5 | `QdrantDatasetVectorIndex`·`QdrantChunkVectorIndex`: 가시성 2단계(볼 수 있는 dataset_id 조회·표시 필드 읽기 — 두 벌 + RLS 계약 테스트), 테넌트 강제, 키워드 쓰기 경로·키워드 표시, 원문 해시 표시(결정 4 ③, 두 벌), 청크 적재 순서 분리 | L | 3, 4 |
| 6 | 정합성: 벡터 아웃박스(PG·Tibero 두 벌, RLS 정책·tenant_id DEFAULT·카탈로그 시험 갱신)·순번 시퀀스·워커(폴링)·양방향 대조 스윕 | M | 5 |
| 7 | `QdrantRowSearchIndex`: `RowSearchIndex` 서명 변경(`RowFilter` 입력·세대 중립 메서드), `dataset_search_index.generation`(두 벌), 필터 2단계, `deleteMissing` 대체, 정확 스캔 기준 | L | 4, 6 |
| 8 | Tibero 데이터 저장소 행 검색: `SearchSourceReader`(OID·행 수·행 읽기·변경분)·`RowFilterCompiler` Tibero 짝, `TiberoIncrementalCursor` 연결 | M | 7 |
| 9 | 설치·배포·안내: 기능 응답 "저장소 일시 중단" 필드와 웹·ai-agent 반영, docker-compose, Helm 값, 시크릿, 스냅샷 백업, 배포 문서 | M | 5 |

- 2·3번은 Qdrant 없이도 가치가 있고 pgvector 설치본 동작을 바꾸지 않는다. 먼저 병합한다. 원문 해시는 백로그 결과를 바꾸는 새 동작이라 2번에서 빼 5번으로 넘겼다.
- 1번 PoC 결과 Qdrant 를 유지하고 키워드는 바이그램 위주로 정했다(결정 8). 2·3번은 엔진과 무관하다.

## 결과(감수하는 것)

- Qdrant 설치본에서 벡터는 **결과적 일관성**이다. 삭제·등급 변경이 반영되기 전 잠깐의 틈은 사후 확인으로 막지만, 벡터 자체는 잠시 남는다.
- Qdrant 설치본에서 편집 직후 키워드 검색 반영이 조금 늦을 수 있다(커밋 뒤 쓰기). 실패하면 키워드 스윕 주기(5분)만큼 늦는다.
- 원문 해시 판정으로 pgvector 설치본도 원문이 바뀐 데이터셋을 백로그로 잡는다(새 동작). 해시가 없던 기존 행은 처음 편집할 때 한 번 재임베딩될 수 있다.
- 검색 한 번에 플랫폼 DB 왕복이 늘어난다(볼 수 있는 id 조회 + 사후 확인·표시 필드 읽기).
- 행 검색의 선택도 높은 필터가 2단계 중 사후 확인 경로로 빠지면 결과가 topK 보다 적을 수 있다.
- Qdrant 설치본의 키워드 검색은 오타·정확 낱말 질의에서 pg_trgm 보다 조금 약하다(결정 8).
- 플랫폼 DB 마이그레이션(표시 열·아웃박스·`generation`)이 PG·Tibero 두 벌로 늘어난다.
- 단일 노드 Qdrant 는 HA 가 없다. 필요하면 3노드 분산 모드로 늘린다(앱 코드는 그대로).
- 설치 후 벡터 저장소 종류를 바꿀 수 없다.
- 비활성 테넌트의 벡터 아웃박스 행은 처리되지 않고 남을 수 있다.

## 열린 질문

- 테넌트를 넘는 아웃박스 처리(우회 루틴)가 필요한지 — 1차는 활성 테넌트 순회로 시작한다.
- **sparse IDF 가 샤드 단위다.** PoC 키워드 실험은 샤드 1개로 했다. 샤드 6에 테넌트가 섞이면 다른 테넌트 문서가 IDF 에 섞인다. WD-720 은 컬렉션 기본 IDF 로 간다 — 다른 테넌트 원문이 새는 것은 아니고(필터·사후 확인), 낱말 흔함의 추정만 섞인다. 하이브리드 융합은 순위 기반이라 영향이 작다. 테넌트가 늘어 키워드 순위가 눈에 띄게 흔들리면 PoC 질의 세트로 다시 재고 정한다.
- **지금 PG 설치본의 pg_trgm 이 느리다.** 임계값 0.1 이라 GIN 색인을 못 타고 seq scan 이 된다. PoC 의 문단 1만 건에서 질의당 평균 1.2초, 한 질의는 4초가 걸렸다. 이 ADR 범위 밖이라 따로 이슈로 본다.
- PoC 의 행 검색 규모(수백만 점)와 필터 HNSW 경로의 낮은 선택도는 재지 않았다. 선택도 1% 이하는 플래너가 전수 검사로 답했다. 7번에서 정확 스캔 기준과 함께 확인한다.
