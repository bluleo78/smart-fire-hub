# 온톨로지 기반 GraphRAG — 얇은 수직 슬라이스(Walking Skeleton) 설계

- 작성일: 2026-07-20
- 상태: 설계 승인 대기
- 범위: 전체 GraphRAG 시스템의 **1차 하위 프로젝트** (walking skeleton)

## 배경

smart-fire-hub는 이미 pgvector(1024차원, bge-m3) 기반 RRF 하이브리드 검색과
`find_datasets → 스키마 → SQL/문서검색 → 답변` 파이프라인을 프로덕션에서 운영 중이다.
이 위에 **온톨로지 기반 GraphRAG**(엔티티/관계 그래프에 근거한 설명가능 답변)를 얹는다.

전체 시스템은 한 번의 spec으로 짓기엔 크다. 최소 3개의 독립 하위 프로젝트로 나뉜다:

1. **① 인프라 + 그래프 데이터 모델**
2. **② 추출 + 적재 파이프라인 (build)**
3. **③ GraphRAG 질의 + 답변 (query)**

각 하위 프로젝트는 자기만의 spec → plan → 구현 사이클을 갖는다.
**이 문서는 ①②③을 아주 좁게 관통하는 첫 수직 슬라이스**를 정의한다.

## 확정된 아키텍처 결정 (전체 시스템 공통)

| 축 | 결정 | 근거 |
|---|---|---|
| 원천 데이터 | TABLE + DOCUMENT 통합 | 문서 엔티티와 테이블 엔티티를 같은 온톨로지로 연결 |
| 온톨로지 | 하이브리드 — 코어 타입은 사람 정의, 인스턴스는 LLM 추출 | 실무 GraphRAG 정석 절충 |
| 그래프 저장소 | Neo4j | 탐색·경로쿼리·시각화 생태계 |
| 답변 표면 | 기존 AI 챗 (firehub-ai-agent, MCP 도구/subagent) | 기존 UX 재사용, 최소 침습 |
| 엔진 소유 | ai-agent 중심 (A안) | api에서 원천 당김 → LLM 추출 → Neo4j 적재·질의 모두 ai-agent |

> 슬라이스에서는 원천을 **DOCUMENT 1종으로 좁힌다**. TABLE 통합은 후속 하위 프로젝트.

## 성공 기준 (가장 중요)

**이 슬라이스가 증명하는 것**
전 루프가 end-to-end로 배선되어, 챗에서 **그래프에 근거한(노드·엣지 + 출처 청크) 인용 답변**이 나온다.

**증명하지 *않는* 것**
"GraphRAG가 기존 벡터검색보다 낫다"는 품질 우위. 합성 샘플은 샘플·온톨로지·추출 프롬프트가
서로에게 튜닝되므로 품질 판정에 쓸 수 없다.

**품질 검증 게이트 (후속 조건)**
실제 문서로 GraphRAG의 답변 품질 우위를 검증하는 것은 **②③ 본격 확장 전 별도 게이트**로 둔다.
이 게이트를 통과하기 전에는 TABLE 통합·온톨로지 확장·자동 재적재를 진행하지 않는다.

## 샘플 데이터 (합성, 의도적으로 지저분하게)

화재조사 보고서 **5~6건** (한국어 합성). 아래 성질을 반드시 갖춘다:

- 여러 문서가 **같은 건물/원인/규정을 공유** — 겹쳐야 그래프가 의미를 가짐
  (예: 같은 건물에서 재발한 화재, 반복되는 발화원인, 반복 위반 규정)
- **같은 엔티티를 문서마다 다른 이름으로** 표기
  (예: "중앙로 상가" / "중앙로 3가 상가건물") — 엔티티 해소(MERGE)가 실제로 일하게 함
- 약간의 모호성 포함

> 너무 깨끗한 샘플은 아무것도 검증하지 못한다. "지저분함"은 설계 요구사항이다.

## 코어 온톨로지 (소형 — ai-agent 내 설정 파일)

엔티티 타입 (6):
`사건(Incident)`, `건물(Building)`, `발화원인(Cause)`, `피해(Damage)`,
`소방장비/설비(Equipment)`, `규정(Regulation)`

관계 타입 (6):
`발생장소(OCCURRED_AT)`, `원인(CAUSED_BY)`, `피해발생(RESULTED_IN)`,
`설비보유(HAS_EQUIPMENT)`, `위반(VIOLATED)`, `관련규정(GOVERNED_BY)`

### 대표 질문 (온톨로지를 역규정 — 이 3개가 챗에서 그래프 근거로 답해지면 성공)

1. "중앙로 상가건물에서 발생한 화재들의 공통 발화원인은?" → Building→Incident→Cause 다홉
2. "전기적 요인으로 발생한 화재에서 반복 위반된 규정은?" → Cause→Incident→Regulation
3. "스프링클러 미작동이 피해 규모와 어떻게 연관됐나?" → Equipment→Incident→Damage

## 아키텍처 & 컴포넌트

모두 **firehub-ai-agent** 안에 신설. 새 모듈 `src/graphrag/`:

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `ontology.ts` | 코어 온톨로지(6+6 타입) 정의 + 검증 스키마 | — |
| `neo4j-client.ts` | Neo4j 드라이버 연결·세션·제약/인덱스 부트스트랩 | Neo4j |
| `extractor.ts` | 청크 → LLM 호출 → 온톨로지 준수 엔티티/관계 JSON. 온톨로지 밖 타입 폐기 | Claude(Agent SDK `query`) |
| `resolver.ts` | 추출 엔티티 정규화·중복 병합 키 생성 (엔티티 해소) | — |
| `loader.ts` | 해소된 엔티티/관계 → Neo4j `MERGE` 멱등 적재, 출처(chunkId·datasetId) 부착 | neo4j-client |
| `ingest.ts` | 배치 오케스트레이션: api에서 청크 bulk read → extractor → resolver → loader | api client |
| `retriever.ts` | NL 질문 → 시드 엔티티(기존 벡터검색) → 1~2홉 탐색 → 서브그래프+연결 청크 조립 | neo4j, api |
| MCP 도구 `graphrag_query` | retriever 결과를 컨텍스트로 답변 근거 반환 | firehub-mcp-server |
| MCP 도구/CLI `graphrag_ingest(datasetId)` | 빌드 배치 수동 트리거 | ingest |

### 신규 인프라

- `docker-compose`(dev)에 Neo4j 컨테이너 — 인증·볼륨·헬스체크. prod compose는 별도 후속.
- ai-agent 환경변수: `NEO4J_URI` / `NEO4J_USER` / `NEO4J_PASSWORD`.

### 설계 중 검증 필요 (선행 확인 항목)

- ai-agent가 특정 데이터셋의 **청크 전체를 bulk read**할 api 엔드포인트가 있는지 확인.
  `search_documents`는 검색용이라 부적합. 없으면 **api에 bulk-chunk 조회 엔드포인트 신설**(소규모 추가 작업).

### 온톨로지 위치

슬라이스에서는 ai-agent 내 설정 파일(`ontology.ts`). 후속에 DB/관리 UI로 승격.

## 데이터 흐름 (리스크 순 — 계층 순 아님)

Neo4j(신규 인프라)는 저리스크(저장은 되거나 안 되거나). **추출 품질이 진짜 리스크**이므로 먼저 깬다.

### 빌드 (오프라인 배치, 수동 트리거)

```
[1단계: 추출 검증 먼저]
 api chunks(bulk) → extractor(LLM) → resolver → *JSON 덤프 파일*
   → 사람이 검수 (엔티티 해소·타입 준수 눈으로 확인)   ← 여기서 실패하면 멈춘다

[2단계: 배선]
 검증된 JSON → loader(MERGE) → Neo4j   (제약: 엔티티 타입별 unique key)
```

빌드 트리거는 슬라이스에서 **CLI 스크립트/수동 MCP 도구** `graphrag_ingest(datasetId)`.
변경 감지 자동 재적재 리스너는 후속(②)으로 미룬다.

### 질의 (온라인, 챗)

```
챗 질문 → graphrag_query
  → 시드: 기존 벡터/키워드 검색으로 관련 엔티티명 후보 확보
  → Neo4j 1~2홉 탐색 (서브그래프)
  → 서브그래프 노드 + 연결된 출처 청크 텍스트를 컨텍스트로 조립
  → 에이전트가 인용 답변 (노드/관계 + fileName 근거)
```

`retriever`는 **의도적으로 얇게**: 시드 → 1~2홉 → 서브그래프+청크 덤프.
그 이상의 영리한 랭킹·요약은 하지 않는다.

## 도구 라우팅

챗에 `find_datasets`·`search_documents`·`graphrag_query`가 공존하여 검색 도구가 겹친다.
슬라이스에서는 **명시적 규칙**을 system-prompt에 문서화:

- 엔티티 간 **관계/연결/공통점/경로**를 묻는 질문 → `graphrag_query`
- 단일 데이터셋 조회·단순 문서검색 → 기존 도구(`find_datasets`/`search_documents`)

지능적 자동 라우팅은 후속으로 미룬다. (지금 명시하지 않으면 조용히 엉킨다)

## 에러 처리

- 추출 LLM이 온톨로지 밖 타입/깨진 JSON 반환 → 해당 항목 폐기 + 로그, 배치 계속
- Neo4j 연결 실패 → `graphrag_query`는 명확한 폴백 메시지("그래프 미구축/연결불가") 반환, 기존 도구 우회 안내
- bulk read 실패 → 배치 중단 + 리포트

## 테스트 (CLAUDE.md: ai-agent는 TC 필수)

- `resolver`·`ontology`: 순수 함수 유닛 TC (중복 병합, 온톨로지 타입 검증)
- `loader`: Neo4j MERGE 멱등성 TC (2회 적재 = 노드 수 불변)
- `retriever`: 시드 서브그래프에서 대표 질문 3개가 기대 노드/관계를 회수하는지 통합 TC
- end-to-end: `graphrag_ingest` → `graphrag_query`로 대표 질문 3개 답변에 근거 노드·출처 포함 확인

## 범위 밖 (후속 하위 프로젝트)

- TABLE 데이터셋 → 그래프 매핑
- 온톨로지 관리 UI / DB 승격
- 변경 감지 자동 재적재 리스너
- 전용 Q&A 화면 + 그래프 시각화
- 지능적 도구 자동 라우팅
- 실제 문서 기반 품질 우위 검증 게이트 (②③ 확장의 선행 조건)
- prod 배포(Neo4j prod compose·백업·인증 강화)
