# Smart Fire Hub — 실시간 데이터 처리 및 분석 플랫폼 연구 보고서

> 작성일: 2026-02-28
> 대상 플랫폼: Smart Fire Hub (Spring Boot + PostgreSQL + React + SSE)
> 목표: 소방/응급 관리 데이터 허브에 적합한 실시간 처리, 분석, 관측성 아키텍처 도출

---

## 목차

1. [실시간 데이터 처리 아키텍처](#1-실시간-데이터-처리-아키텍처)
2. [분석 및 BI 도구](#2-분석-및-bi-도구)
3. [데이터 변환과 dbt](#3-데이터-변환과-dbt)
4. [데이터 관측성 및 모니터링](#4-데이터-관측성-및-모니터링)
5. [아키텍처 권고안](#5-아키텍처-권고안)

---

## 1. 실시간 데이터 처리 아키텍처

### 1.1 스트림 처리 프레임워크 비교

Smart Fire Hub는 현재 Spring Boot(MVC) + SseEmitter 기반의 SSE 스트리밍을 사용 중이다. 아래에서 각 기술을 **복잡도, 규모 적합성, Spring Boot 통합, 운영 부담, 지연시간** 관점에서 평가한다.

#### 종합 비교표

| 기술 | 복잡도 | 소~중규모 적합성 | Spring Boot 통합 | 운영 부담 | 지연시간 | 처리량 |
|------|--------|-----------------|-----------------|----------|---------|-------|
| **SSE (현행)** | ★☆☆☆☆ | ★★★★★ | 네이티브 | 없음 | 매우 낮음 | 낮음 |
| **PostgreSQL LISTEN/NOTIFY** | ★☆☆☆☆ | ★★★★★ | 직접 통합 | 없음 (DB 내장) | <50ms | 중간 (수백/초) |
| **Spring WebFlux + Reactor** | ★★★☆☆ | ★★★★☆ | 네이티브 | 낮음 | 매우 낮음 | 높음 |
| **WebSocket** | ★★☆☆☆ | ★★★★☆ | 네이티브 | 낮음 | 매우 낮음 | 중간 |
| **Redis Streams** | ★★☆☆☆ | ★★★★☆ | Spring Data Redis | 낮음 | <1ms | 높음 |
| **NATS / JetStream** | ★★☆☆☆ | ★★★★☆ | 커뮤니티 클라이언트 | 매우 낮음 | <1ms | 매우 높음 |
| **RabbitMQ Streams** | ★★★☆☆ | ★★★☆☆ | Spring AMQP | 중간 | 낮음 | 높음 |
| **Apache Kafka + Kafka Streams** | ★★★★☆ | ★★☆☆☆ | Spring Kafka | 높음 | 중간 | 매우 높음 |
| **Apache Flink** | ★★★★★ | ★☆☆☆☆ | 커넥터 필요 | 매우 높음 | 매우 낮음 | 매우 높음 |
| **Apache Pulsar** | ★★★★★ | ★☆☆☆☆ | 커뮤니티 클라이언트 | 매우 높음 | 낮음 | 매우 높음 |

#### 기술별 상세 평가

**SSE (Server-Sent Events) — 현행 기술**

현재 Smart Fire Hub가 AI 채팅, 작업 진행 상태 스트리밍에 사용 중이다. `SseEmitter` 기반으로 동작하며, 단방향(서버→클라이언트) 통신이 핵심이다.

- **장점**: 별도 인프라 불필요, HTTP 기반으로 프록시/방화벽 친화적, 자동 재연결, 구현 극도로 단순
- **단점**: 단방향 전용, `SseEmitter`는 연결당 스레드 점유(스레드 모델 한계), 동시 연결 수백 개 이상이면 스레드 풀 고갈 위험
- **적합 사용처**: 대시보드 업데이트, 알림 스트리밍, 작업 진행 표시 (현재 용도에 최적)
- **현행 한계**: `AsyncConfig`에서 pipelineExecutor가 core=5, max=10, queue=25로 설정되어 있어 동시 연결 확장에 제한이 있음

**PostgreSQL LISTEN/NOTIFY — 제로 의존성 Pub/Sub**

PostgreSQL 내장 메시징 시스템이다. 별도 메시지 브로커 없이 DB에서 직접 이벤트를 발행/구독할 수 있다.

- **장점**: 추가 인프라 제로, 기존 PostgreSQL에서 바로 사용 가능, 트랜잭션과 연동(커밋 시에만 NOTIFY 발행), 구현 단순
- **단점**: 인메모리 전용으로 리스너 없으면 메시지 유실, 페이로드 8KB 제한, 고처리량(초당 수천 건 이상) 부적합, 메시지 재생/영속성 없음
- **적합 사용처**: 데이터 변경 알림, 파이프라인 상태 변경 이벤트, 데이터셋 변경 트리거 (현행 30초 폴링 대체 가능)
- **Spring Boot 통합**: `spring-jdbc`의 `SimpleDataSourceConnectionProvider` 또는 직접 `PGNotificationListener` 등록

**Spring WebFlux + Project Reactor — 리액티브 프로그래밍**

Spring의 리액티브 스택으로, 비동기/논블로킹 방식의 데이터 스트리밍을 지원한다.

- **장점**: 연결당 스레드 불필요(이벤트 루프 모델), 수만 동시 연결 처리 가능, SSE/WebSocket 모두 `Flux` 기반으로 통합, Spring 생태계 네이티브
- **단점**: 기존 Spring MVC 코드베이스와 혼용 시 복잡도 증가, 리액티브 프로그래밍 학습곡선, 블로킹 API(jOOQ, JDBC)와 함께 사용 시 별도 스케줄러 필요, 디버깅 어려움
- **현행 시스템과의 갭**: Smart Fire Hub는 Spring MVC + jOOQ(블로킹 JDBC) 기반이므로 WebFlux로의 전환은 대규모 리팩터링을 의미. 부분 도입(WebFlux 모듈 별도 생성)은 가능하나 복잡도가 크게 증가
- **권고**: 현 규모에서는 과잉. 동시 연결이 수천 이상으로 증가하는 시점에 검토

**WebSocket — 양방향 실시간 통신**

서버-클라이언트 간 양방향 전이중(Full-duplex) 통신을 지원한다.

- **장점**: 양방향 통신(클라이언트→서버 메시지도 가능), 매우 낮은 지연시간, Spring WebSocket 모듈 네이티브 지원
- **단점**: 연결 관리 복잡(하트비트, 재연결 로직 직접 구현), HTTP와 다른 프로토콜이라 프록시 설정 필요, 상태 관리 복잡
- **적합 사용처**: 양방향 상호작용이 필요한 경우 (예: 실시간 협업, 차량 위치 추적에서 클라이언트가 필터를 동적 변경)
- **Smart Fire Hub 맥락**: 현재 SSE가 충분히 커버하는 단방향 스트리밍이 주 용도이므로 WebSocket 도입의 실질적 이점이 제한적. GPS 추적 등 양방향 필터링이 필요해지면 검토

**Redis Streams — 경량 스트리밍**

Redis 5.0에서 도입된 로그형 데이터 구조로, Consumer Group을 지원하며 Kafka와 유사한 스트리밍 패턴을 경량으로 구현한다.

- **장점**: 인메모리라 초저지연(<1ms), Consumer Group으로 수평 확장 가능, 메시지 영속성(RDB/AOF), 기존 Redis 인프라 활용 가능, 학습곡선 완만
- **단점**: 메모리 기반이므로 대용량 데이터 보관에 비용 부담, Redis 클러스터 운영 필요 시 복잡도 증가
- **적합 사용처**: 이벤트 버퍼링, 실시간 메트릭 수집, 마이크로서비스 간 이벤트 전달
- **Spring Boot 통합**: `spring-data-redis`의 `StreamOperations`로 직접 지원, Pub/Sub도 함께 사용 가능
- **Smart Fire Hub 맥락**: Redis를 캐시로 이미 사용 중이거나 향후 도입 계획이 있다면 가장 자연스러운 스트리밍 레이어

**NATS / JetStream — 초경량 메시징**

단일 바이너리로 배포 가능한 경량 메시지 시스템이다. JetStream은 NATS의 영속성 레이어로, 메시지 재생과 정확히 한 번 전달을 지원한다.

- **장점**: 바이너리 하나로 설치(Raspberry Pi에서도 동작), 초저지연(200~400μs), JetStream으로 메시지 영속성/재생 지원, 운영 부담 최소, IoT/엣지 컴퓨팅에 강점
- **단점**: Java/Spring 생태계에서 커뮤니티 크기가 Kafka 대비 작음, 공식 Spring Integration 부재(jnats 라이브러리 직접 사용), 복잡한 스트림 처리(조인, 윈도윙 등) 미지원
- **적합 사용처**: IoT 센서 데이터 수집, 경량 마이크로서비스 메시징, 엣지 배포
- **Smart Fire Hub 맥락**: 센서 데이터(연기/화재 감지기) 수집이나 차량 GPS 추적에 적합. 단, Spring 통합 편의성은 Redis Streams 대비 떨어짐

**RabbitMQ Streams — 메시지 큐 + 스트리밍**

전통적 메시지 큐인 RabbitMQ에 스트리밍 기능을 추가한 것이다.

- **장점**: AMQP 프로토콜의 강력한 라우팅 기능, 메시지 우선순위/TTL/Dead Letter 등 엔터프라이즈 기능, Spring AMQP의 성숙한 통합
- **단점**: Kafka/Redis Streams 대비 처리량 낮음, Erlang 런타임 의존, 스트리밍은 비교적 새로운 기능
- **적합 사용처**: 복잡한 라우팅이 필요한 워크플로, 기존 RabbitMQ 인프라가 있는 환경
- **Smart Fire Hub 맥락**: 파이프라인 실행 큐잉에 적합하나, 현행 Jobrunr + 인메모리 큐로 충분히 동작 중이므로 도입 필요성 낮음

**Apache Kafka + Kafka Streams — 엔터프라이즈 이벤트 스트리밍**

대규모 이벤트 스트리밍의 사실상 표준이다.

- **장점**: 무한에 가까운 수평 확장, 디스크 기반 메시지 영속성/재생, Kafka Streams로 조인/윈도윙/집계 가능, 풍부한 생태계(Connect, Schema Registry 등), `spring-kafka` 네이티브 지원
- **단점**: ZooKeeper/KRaft 등 클러스터 운영 복잡, 최소 3-5 노드 권장(소규모에 과잉), 초기 설정과 튜닝에 전문성 필요, 리소스 소비 큼
- **적합 사용처**: 대규모 이벤트 소싱, 다수 컨슈머가 동일 이벤트를 소비, 감사 로그 스트림
- **Smart Fire Hub 맥락**: 단일 소방서 또는 지역 단위에서는 **확실히 과잉**. 광역/전국 규모로 확장하거나 수십 개 시스템 간 이벤트 허브로 사용할 때 비로소 가치가 있음

**Apache Flink — 상태 기반 스트림 처리**

복잡한 이벤트 처리(CEP), 윈도우 집계, 정확히 한 번 처리를 지원하는 분산 스트림 처리 엔진이다.

- **장점**: 가장 강력한 스트림 처리 기능(CEP, 윈도윙, watermark), 밀리초 단위 지연시간, exactly-once 보장
- **단점**: 배포/운영 복잡도 극도로 높음(YARN, Kubernetes, Mesos 등 필요), 학습곡선 가파름, 소규모 팀에게 운영 부담 과중
- **Smart Fire Hub 맥락**: **부적합**. 소방 데이터 허브 규모에서 Flink를 정당화할 처리 요구사항이 없음

**Apache Pulsar — 메시징 + 스트리밍 통합**

Kafka의 대안으로, 저장(BookKeeper)과 서빙(Broker)을 분리한 아키텍처이다.

- **장점**: 멀티테넌시 네이티브 지원, 저장/서빙 독립 확장, Geo-replication 내장
- **단점**: 3개 분산 시스템(ZooKeeper/etcd, BookKeeper, Broker) 운영 필요, Kafka보다 복잡한 운영, 커뮤니티/생태계 Kafka 대비 작음
- **Smart Fire Hub 맥락**: **부적합**. Kafka보다도 운영이 복잡하며 소규모에서 이점이 없음

---

### 1.2 소방 서비스 실시간 유스케이스 분석

소방/응급 데이터 허브의 핵심 실시간 유스케이스를 분석하고, 각각에 적합한 기술을 매핑한다.

| 유스케이스 | 데이터 특성 | 지연 요구 | 볼륨 | 권장 기술 |
|-----------|-----------|---------|------|----------|
| **실시간 사건 피드** (119 신고, 출동 현황) | 이벤트 기반, 불규칙 | <2초 | 분당 수 건~수십 건 | SSE + PostgreSQL LISTEN/NOTIFY |
| **차량 위치 추적** (AVL GPS) | 시계열, 1~10초 간격 | <5초 | 차량수 x 6~60/분 | Redis Streams (또는 NATS) → SSE |
| **센서 데이터** (IoT 화재/연기 감지기) | 시계열, 주기적 | <10초 | 센서수 x 1~6/분 | Redis Streams (또는 NATS) |
| **실시간 대시보드** (활성 사건, 차량 가용률) | 집계 데이터, 주기적 갱신 | <30초 | 낮음 | SSE + 주기적 폴링 |
| **알림/경보 스트리밍** (파이프라인 실패, 임계치 초과) | 이벤트 기반, 비정기 | <5초 | 매우 낮음 | PostgreSQL LISTEN/NOTIFY → SSE |
| **이벤트 기반 ETL** (데이터 변경 → 파이프라인 트리거) | 이벤트 기반 | <30초 | 낮음 | PostgreSQL LISTEN/NOTIFY (현행 30초 폴링 대체) |

#### 유스케이스별 상세 분석

**실시간 사건 피드 (Live Incident Feed)**

119 신고 접수, 출동 지령, 부대 상태 변경 등을 실시간으로 전파한다. 전형적인 CAD(Computer-Aided Dispatch) 시스템 연동이다.

- 데이터 흐름: CAD API/Webhook → Smart Fire Hub API → DB 저장 + NOTIFY → SSE → 대시보드
- 볼륨: 일반적인 소방서 기준 일 50~200건, 피크 시 분당 수 건
- 현행 시스템 대응: 이미 Webhook 트리거(`WEBHOOK` 타입)가 구현되어 있으므로, CAD 시스템이 Webhook으로 사건 데이터를 푸시하면 `POST /api/v1/triggers/webhook/{webhookId}`로 수신 가능
- 개선 방향: 수신된 이벤트를 PostgreSQL `LISTEN/NOTIFY`로 즉시 전파하여 SSE 연결된 대시보드에 실시간 반영

**차량 위치 추적 (Apparatus Tracking / AVL)**

소방차, 구급차 등의 GPS 좌표를 1~10초 간격으로 수집하여 지도에 표시한다.

- 데이터 흐름: AVL 장치 → GPS 게이트웨이 → Redis Streams(버퍼) → 배치/개별 DB 저장 → SSE → 지도 UI
- 볼륨: 차량 50대 기준, 5초 간격이면 초당 10건 (부담 없는 수준)
- Redis Streams 권장 이유: GPS 데이터는 고빈도/저지연이 필요하지만 개별 건의 유실이 치명적이지 않음. Redis의 인메모리 속도로 버퍼링 후 배치로 DB에 저장하면 DB 부하를 줄일 수 있음
- 대안: 차량 수가 적다면(20대 미만) PostgreSQL 직접 INSERT + SSE 폴링으로도 충분

**센서 데이터 (IoT 화재/연기 감지기, 건물 센서)**

IoT 센서에서 온도, 연기 농도, CO2 등을 주기적으로 수집한다.

- 데이터 흐름: 센서 → IoT 게이트웨이(MQTT) → NATS 또는 Redis Streams → 이상 감지 → 알림/DB 저장
- 볼륨: 센서 수에 따라 매우 가변적. 건물 100개 x 센서 10개 = 1,000 센서, 분당 1회면 초당 ~17건
- NATS 권장 이유: MQTT 브릿지 내장, 초경량(Raspberry Pi에서도 동작), IoT 엣지 환경에 최적
- 대안: Redis Streams도 충분하며, 센서 수가 적으면 HTTP API로 직접 수신도 가능

**실시간 대시보드 (Active Incidents, Unit Availability)**

활성 사건 수, 출동 가능 차량, 평균 대응 시간 등의 집계 메트릭을 표시한다.

- 데이터 흐름: DB 변경 → 집계 쿼리 실행 → SSE로 전파 (또는 주기적 폴링)
- 지연 허용: 10~30초 (실시간이지만 초단위 정확성은 불필요)
- 현행 대응: `dashboard` 모듈이 이미 존재. TanStack Query의 `refetchInterval`로 주기적 폴링 중
- 개선 방향: PostgreSQL LISTEN/NOTIFY로 데이터 변경 시에만 갱신 신호를 보내고, 클라이언트가 SSE로 수신하여 불필요한 폴링 제거

**알림/경보 스트리밍**

파이프라인 실패, 데이터 품질 임계치 초과, 시스템 이상 등을 즉시 통보한다.

- 데이터 흐름: 이벤트 발생 → PostgreSQL LISTEN/NOTIFY → SSE → UI 토스트/알림센터
- 볼륨: 매우 낮음 (일 수십 건)
- 현행 대응: `AsyncJobService`의 `SseEmitter` 기반 작업 상태 스트리밍이 이미 동작 중
- 개선 방향: 통합 알림 채널로 확장 (Slack/Teams 웹훅, 이메일 등)

---

### 1.3 현행 SSE 아키텍처의 한계와 개선 방향

**현재 구현 분석**

Smart Fire Hub는 두 가지 SSE 패턴을 사용 중이다:

1. **AsyncJobService**: `ConcurrentHashMap<UUID, List<SseEmitter>>` 기반. 파이프라인 실행, 데이터 임포트 진행 상태를 실시간 스트리밍
2. **AiAgentProxyService**: 외부 AI Agent(localhost:3001)로의 SSE 프록시. AI 채팅 응답 스트리밍

두 방식 모두 `SseEmitter`(Spring MVC)를 사용하며, 연결당 스레드를 점유하는 제한이 있다.

**확장 단계별 로드맵**

```
[현재] SseEmitter 기반 SSE
  │
  ├─ [단기] PostgreSQL LISTEN/NOTIFY 추가
  │    → DATASET_CHANGE 트리거의 30초 폴링을 즉시 이벤트로 대체
  │    → 파이프라인 상태 변경을 실시간 전파
  │    → 추가 인프라 없이 기존 DB만으로 구현
  │
  ├─ [중기] Redis Streams 도입 (선택적)
  │    → 차량 GPS 데이터 고빈도 수집/버퍼링
  │    → 센서 데이터 수집 레이어
  │    → 이벤트 영속성/재생이 필요한 경우
  │
  └─ [장기] WebFlux 부분 도입 (선택적)
       → 동시 SSE 연결이 수천 이상으로 증가 시
       → 별도 리액티브 모듈로 구성 (기존 MVC와 분리)
```

---

## 2. 분석 및 BI 도구

### 2.1 오픈소스 BI 도구 비교

| 항목 | Metabase | Apache Superset | Redash | Grafana | Evidence | Lightdash |
|------|----------|----------------|--------|---------|----------|-----------|
| **라이선스** | AGPL (OSS) / 상용 Pro/Enterprise | Apache 2.0 | BSD-2-Clause | AGPL (OSS) / 상용 Enterprise | MIT | MIT |
| **주요 대상** | 비기술 사용자 | 데이터 엔지니어/분석가 | SQL 능숙한 분석가 | DevOps/인프라 엔지니어 | 개발자 (코드형 BI) | dbt 사용팀 |
| **셀프호스팅 난이도** | ★☆☆☆☆ (JAR 또는 Docker) | ★★★☆☆ (Python/Docker) | ★★☆☆☆ (Docker) | ★☆☆☆☆ (단일 바이너리) | ★★☆☆☆ (Node.js) | ★★★☆☆ (Docker Compose) |
| **SQL 직접 작성** | 지원 (+ 비주얼 쿼리빌더) | 지원 (SQL Lab IDE) | 핵심 기능 | 지원 | 핵심 기능 (마크다운 내 SQL) | 지원 (dbt 모델 기반) |
| **차트 종류** | 15+ | 40+ (ECharts 기반) | 10+ | 20+ (패널 플러그인) | 10+ | 15+ |
| **임베딩 지원** | iframe + React SDK (Pro) | iframe + Embedded SDK (OSS) | iframe (제한적) | iframe + Panel Plugin | 정적 사이트 배포 | iframe |
| **PostgreSQL 연동** | 네이티브 | 네이티브 (SQLAlchemy) | 네이티브 | 네이티브 데이터소스 | 네이티브 | dbt 경유 |
| **AI/NL 쿼리** | MetaBot (2025~) | 제한적 | 없음 | AI 플러그인 | AI 에이전트 (2025~) | 없음 |
| **커뮤니티** | GitHub 40K+ stars | GitHub 65K+ stars | GitHub 26K+ stars | GitHub 65K+ stars | GitHub 5K+ stars | GitHub 6K+ stars |
| **장점** | 직관적 UI, "Ask a Question" 기능, 빠른 시작 | 엔터프라이즈 규모, 풍부한 시각화, RBAC | SQL 중심 간결함, 빠른 쿼리 공유 | 시계열 강점, 풍부한 데이터소스, 알림 | 코드형 리포트, Git 연동, CI/CD | dbt 메트릭 네이티브, 시맨틱 레이어 |
| **단점** | 고급 기능은 유료, 대규모 성능 제한 | 설치/운영 복잡, 학습곡선 | 유지보수 느림, 2024년 후 활동 감소 | 전통적 BI에 부적합, 피벗/크로스탭 약함 | 커뮤니티 작음, 상호작용 제한적 | dbt 필수, 비dbt 환경 부적합 |

#### 상세 평가

**Metabase — 비기술 사용자를 위한 최적의 선택**

비기술 직원(소방관, 행정직)도 데이터를 직접 탐색할 수 있는 가장 직관적인 BI 도구이다.

- React SDK로 네이티브 임베딩 가능: `@metabase/embedding-sdk-react` 패키지 제공
- JWT SSO 연동으로 Smart Fire Hub의 인증 체계와 통합 가능
- 질문(Question) → 대시보드 → 공유 워크플로가 매우 자연스러움
- 단점: React SDK의 Pro/Enterprise 라이선스 요구 (iframe 임베딩은 OSS에서도 가능)

**Apache Superset — 엔터프라이즈급 분석**

40개 이상의 시각화 타입과 SQL Lab IDE를 갖춘 강력한 분석 플랫폼이다.

- `@superset-ui/embedded-sdk`로 React 임베딩 지원 (OSS에서도 가능)
- Guest Token 기반 인증으로 외부 앱에서 대시보드 안전하게 노출
- RBAC, Row-Level Security 등 세밀한 접근 제어
- 단점: Python(Flask) 기반이라 Java/Spring 생태계와 이질적, 셀프호스팅 운영 복잡도 높음

**Grafana — 운영 모니터링에 강점**

시계열 데이터와 운영 메트릭 시각화에 특화된 플랫폼이다.

- PostgreSQL 데이터소스 네이티브 지원, 실시간 대시보드(자동 새로고침) 강점
- 알림(Alerting) 기능 내장 — 임계치 초과 시 Slack, 이메일 등으로 자동 알림
- 소방 운영 메트릭(대응 시간, 차량 가용률, 사건 추이)에 적합
- 단점: 피벗 테이블, 크로스탭 등 전통적 BI 분석에 약함. "데이터 탐색" 보다는 "대시보드 모니터링" 용도

**Redash — SQL 중심 간결함**

SQL 쿼리를 작성하고 바로 시각화/공유하는 데 특화된 경량 도구이다.

- 장점: SQL만 알면 바로 사용 가능, 쿼리 스케줄링 내장
- 단점: 2024년 이후 개발 활동 둔화, 차트 종류 제한적, 임베딩 기능 약함
- Smart Fire Hub 맥락: 이미 AI 에이전트로 SQL 쿼리 실행이 가능하므로 Redash의 핵심 가치가 겹침

**Evidence — 코드형 BI**

마크다운 + SQL로 데이터 리포트를 작성하는 코드 퍼스트 BI 도구이다.

- Git으로 버전 관리, CI/CD로 자동 배포, PR 리뷰 가능
- AI 에이전트가 쿼리 작성을 돕는 기능 (Evidence Studio, 2025~)
- 정적 사이트로 빌드되어 임베딩은 iframe으로만 가능
- Smart Fire Hub 맥락: 정기 리포트(월별 대응 통계, 연간 보고서)에 적합하나, 인터랙티브 대시보드에는 부족

**Lightdash — dbt 네이티브 BI**

dbt 프로젝트의 메트릭 정의를 바로 시각화하는 도구이다.

- dbt YAML에 메트릭을 정의하면 자동으로 탐색/시각화 가능
- Smart Fire Hub 맥락: dbt를 사용하지 않으면 가치가 없음. dbt 도입 시 함께 검토

---

### 2.2 임베디드 분석 접근법

Smart Fire Hub의 React 프론트엔드에 분석 기능을 통합하는 세 가지 접근법을 비교한다.

#### 접근법 A: 외부 BI 도구 임베딩

기존 BI 도구(Metabase/Superset/Grafana)를 iframe 또는 SDK로 React 앱 안에 삽입한다.

| 항목 | 설명 |
|------|------|
| **장점** | 빠른 구축, 풍부한 기능(드릴다운, 필터, 다운로드), 비개발자도 대시보드 편집 가능 |
| **단점** | 별도 서비스 운영, UX 이질감(iframe 테두리, 로딩), 스타일 커스터마이징 제한 |
| **권장 도구** | Metabase (React SDK, 가장 자연스러운 임베딩) 또는 Grafana (운영 대시보드) |
| **비용** | Metabase Pro 임베딩 SDK: $500/월~ / Superset 임베딩: 무료(OSS) / Grafana: 무료(OSS) |

#### 접근법 B: 커스텀 차트 라이브러리로 직접 구축

React 차트 라이브러리를 사용하여 대시보드를 직접 개발한다.

**차트 라이브러리 비교표**

| 라이브러리 | 유형 | 학습곡선 | 차트 종류 | 성능 | shadcn/ui 호환 | 특징 |
|-----------|------|---------|---------|------|---------------|------|
| **Recharts** | React 네이티브 | ★☆☆☆☆ | 15+ | 중간 (SVG) | **shadcn/ui 내장** | 컴포저블 API, 가장 인기 |
| **Apache ECharts** | 캔버스/WebGL | ★★★☆☆ | 50+ | **매우 높음** (GPU 가속) | echarts-for-react 래퍼 | 대용량 데이터, 3D, 지도 |
| **Nivo** | React + D3 | ★★☆☆☆ | 25+ | 높음 (SVG/Canvas) | CSS 커스텀 | SSR 지원, 풍부한 애니메이션 |
| **Victory** | React + D3 | ★★☆☆☆ | 15+ | 중간 | CSS 커스텀 | React Native 호환 |
| **Tremor** | React + Tailwind | ★☆☆☆☆ | 10+ | 중간 | **Tailwind 네이티브** | 대시보드 특화, 고수준 API |
| **Observable Plot** | D3 계열 | ★★★☆☆ | 분석 특화 | 높음 | 래퍼 필요 | 탐색적 데이터 분석 |

**Smart Fire Hub 맥락에서의 최적 선택**:

- **1순위: Recharts (shadcn/ui Charts)** — Smart Fire Hub가 이미 shadcn/ui를 사용 중이므로, `npx shadcn add chart`로 Recharts 기반 차트 컴포넌트를 바로 추가 가능. Tailwind CSS v4, 다크모드, 테마 시스템과 자동 통합. 일반적인 대시보드(막대, 선, 파이, 영역 차트)에 충분
- **2순위: Tremor** — Tailwind CSS 기반이라 shadcn/ui와 스타일 조화. 대시보드 특화 컴포넌트(KPI 카드, 스파크라인 등)가 내장. Recharts 위에 구축되어 있어 학습 전이 용이
- **3순위: Apache ECharts** — 지도 시각화(사건 위치, 차량 위치), 대용량 시계열(센서 데이터), 3D 차트가 필요한 경우에만 도입. `echarts-for-react` 래퍼로 React 통합

#### 접근법 C: 하이브리드 (권장)

커스텀 차트와 외부 BI 도구를 병행한다.

```
┌─────────────────────────────────────────────────┐
│              Smart Fire Hub 프론트엔드            │
│                                                  │
│  ┌──────────────────────┐  ┌──────────────────┐  │
│  │   커스텀 대시보드      │  │  Grafana 임베딩   │  │
│  │   (Recharts/shadcn)  │  │  (운영 모니터링)  │  │
│  │                      │  │                  │  │
│  │  - KPI 카드           │  │  - 대응시간 추이  │  │
│  │  - 사건 통계 차트      │  │  - 시스템 메트릭  │  │
│  │  - 파이프라인 현황     │  │  - 데이터 품질    │  │
│  └──────────────────────┘  └──────────────────┘  │
│                                                  │
│  ┌──────────────────────────────────────────────┐ │
│  │         AI 에이전트 기반 애드혹 분석           │ │
│  │         (기존 Claude SDK + MCP 도구)          │ │
│  │  - 자연어 → SQL 실행 → 결과 시각화            │ │
│  └──────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

| 구성 요소 | 역할 | 기술 |
|----------|------|------|
| 메인 대시보드 | 일상 운영 현황 | Recharts (shadcn/ui Charts) |
| 운영 모니터링 | 시계열 메트릭, 알림 | Grafana (iframe 임베딩) |
| 애드혹 분석 | 자유 질의, 탐색 | AI 에이전트 (현행) |
| 정기 리포트 | 월/분기/연간 통계 | 서버사이드 PDF/CSV 생성 |

---

## 3. 데이터 변환과 dbt

### 3.1 dbt Core vs dbt Cloud

| 항목 | dbt Core (OSS) | dbt Cloud |
|------|---------------|-----------|
| **라이선스** | Apache 2.0 (무료) | SaaS (유료, $100/사용자/월~) |
| **실행 환경** | CLI, 로컬/CI 직접 관리 | 웹 IDE, 클라우드 호스팅 |
| **스케줄링** | 없음 (Airflow, Dagster 등 별도 필요) | 내장 (Job Scheduler) |
| **PostgreSQL 지원** | `dbt-postgres` 어댑터 (2025년 12월 공식 지원 확인) | 동일 |
| **협업 기능** | Git 기반 (PR 리뷰) | Git + 웹 IDE + 문서 자동 생성 |
| **적합 대상** | DevOps 역량 있는 소규모 팀 | 빠른 시작, 비개발 분석가 포함 팀 |

### 3.2 dbt와 Smart Fire Hub 파이프라인 엔진의 비교

Smart Fire Hub는 이미 자체 파이프라인 엔진을 갖추고 있다. dbt 도입이 기존 엔진과 어떻게 비교되는지 분석한다.

| 기능 | Smart Fire Hub 파이프라인 엔진 | dbt Core |
|------|-------------------------------|----------|
| **SQL 변환** | `SqlScriptExecutor` — `data` 스키마에서 SQL 실행 | SQL SELECT 기반 모델 정의, 자동 CREATE/INSERT |
| **Python 처리** | `PythonScriptExecutor` — subprocess로 Python 실행 | dbt Python 모델 (제한적) |
| **API 호출** | `ApiCallExecutor` — WebClient, 페이지네이션, 인증 | **미지원** (별도 도구 필요) |
| **DAG 실행** | Kahn's algorithm 토폴로지 정렬, 비동기 실행 | 내장 DAG (ref() 함수로 의존성 정의) |
| **트리거** | Schedule, API, Webhook, Pipeline Chain, Dataset Change | 없음 (외부 오케스트레이터 필요) |
| **데이터 테스트** | 없음 (향후 계획) | **강력** — unique, not_null, relationships, 커스텀 테스트 |
| **문서화** | 없음 | **자동** — 모델 설명, 칼럼 설명, 리니지 그래프 |
| **버전 관리** | DB에 파이프라인 정의 저장 | Git 기반 (SQL 파일) |
| **UI** | 웹 UI에서 파이프라인 편집 (DAG 캔버스) | CLI 또는 dbt Cloud IDE |
| **증분 처리** | APPEND/REPLACE 전략 | incremental 모델 (매우 강력) |

### 3.3 소방 서비스 분석에서의 dbt 활용 시나리오

dbt가 가치를 제공할 수 있는 구체적 변환 사례:

```sql
-- models/staging/stg_incidents.sql
-- 원시 사건 데이터를 정제
SELECT
    incident_id,
    reported_at,
    dispatched_at,
    arrived_at,
    cleared_at,
    EXTRACT(EPOCH FROM (dispatched_at - reported_at)) AS dispatch_seconds,
    EXTRACT(EPOCH FROM (arrived_at - dispatched_at)) AS travel_seconds,
    EXTRACT(EPOCH FROM (arrived_at - reported_at)) AS response_seconds,
    incident_type,
    priority_level,
    station_id
FROM {{ source('raw', 'incidents') }}
WHERE reported_at IS NOT NULL

-- models/marts/fct_response_metrics.sql
-- 대응 시간 집계 메트릭
SELECT
    DATE_TRUNC('month', reported_at) AS month,
    station_id,
    incident_type,
    COUNT(*) AS incident_count,
    AVG(response_seconds) AS avg_response_seconds,
    PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY response_seconds) AS p90_response_seconds,
    COUNT(CASE WHEN response_seconds <= 360 THEN 1 END)::FLOAT / COUNT(*) AS within_6min_rate
FROM {{ ref('stg_incidents') }}
GROUP BY 1, 2, 3
```

```yaml
# models/staging/stg_incidents.yml
models:
  - name: stg_incidents
    description: "정제된 사건 데이터 — 대응 시간 계산 포함"
    columns:
      - name: incident_id
        tests: [unique, not_null]
      - name: response_seconds
        tests:
          - not_null
          - dbt_utils.accepted_range:
              min_value: 0
              max_value: 7200  # 2시간 이상이면 이상치
```

### 3.4 dbt 도입 판단: Smart Fire Hub에 적합한가?

**dbt가 가치를 제공하는 영역:**
- 데이터 품질 테스트 (unique, not_null, relationships, 커스텀 테스트)
- 변환 로직의 버전 관리와 코드 리뷰
- 자동 문서화와 데이터 리니지
- 증분 처리(incremental) 모델

**dbt가 과잉인 이유:**
- Smart Fire Hub는 이미 완전한 파이프라인 엔진을 보유 (SQL + Python + API 단계)
- 웹 UI 기반 파이프라인 편집기(DAG 캔버스)가 존재하여 비개발자도 사용 가능
- API 호출, Webhook 트리거 등 dbt가 지원하지 않는 기능이 핵심
- dbt 도입 시 두 개의 파이프라인 시스템을 운영해야 하는 복잡도 증가
- 소규모 팀에서 dbt 전문성 확보 부담

**권고: dbt를 도입하지 않고, 기존 파이프라인 엔진에 dbt의 핵심 가치를 점진적으로 흡수한다.**

구체적으로:

| dbt 기능 | 기존 엔진에 구현하는 방법 |
|----------|------------------------|
| 데이터 테스트 | 파이프라인 단계에 "검증(Validation)" 스텝 타입 추가 — SQL 기반 assertion |
| 증분 처리 | `SqlScriptExecutor`에 incremental 모드 추가 (마지막 실행 타임스탬프 기반) |
| 문서화 | 파이프라인/데이터셋 메타데이터에 설명 필드 추가 (이미 부분 구현) |
| 리니지 | 파이프라인 DAG + 데이터셋 의존성으로 자동 추적 (이미 구현) |

---

## 4. 데이터 관측성 및 모니터링

### 4.1 데이터 품질 도구 비교

| 항목 | Great Expectations | Soda Core | Elementary | 커스텀 구축 |
|------|-------------------|-----------|------------|-----------|
| **접근 방식** | 검증 코드(Expectation) | SodaCL (선언적 체크) | dbt 테스트 확장 | SQL 기반 assertion |
| **라이선스** | Apache 2.0 | Apache 2.0 | Apache 2.0 | 자체 |
| **PostgreSQL 지원** | 네이티브 | 네이티브 | dbt 경유 | 네이티브 |
| **학습곡선** | ★★★☆☆ (Python) | ★★☆☆☆ (YAML) | ★★☆☆☆ (dbt 필요) | ★☆☆☆☆ (SQL) |
| **이상 감지** | 규칙 기반 | 시계열 이상 감지 내장 | 이상 감지 내장 | 직접 구현 필요 |
| **알림** | 없음 (직접 연동) | Slack, PagerDuty 등 | Slack | 직접 연동 |
| **문서 생성** | Data Docs (HTML 리포트) | 없음 | 관측성 리포트 | 직접 구현 |
| **CI/CD 통합** | Checkpoint → Pass/Fail | CLI → Pass/Fail | dbt test → Pass/Fail | 직접 구현 |
| **Spring/Java 통합** | Python 서브프로세스 | Python 서브프로세스 | dbt 필요 | **네이티브** |
| **도입 비용** | 중간 (Python 환경 필요) | 낮음 | 중간 (dbt 필수) | 낮음 (점진 구축) |

#### 상세 평가

**Great Expectations — 코드형 데이터 검증의 표준**

Python 기반으로 "Expectation"(기대값)을 정의하여 데이터를 검증한다.

- 장점: 가장 표현력 있는 검증 규칙, 자동 Data Docs(HTML 리포트) 생성, 150+ 내장 Expectation
- 단점: Python 환경 필요(Smart Fire Hub 백엔드는 Java), 학습곡선 있음, 운영 설정 복잡
- Smart Fire Hub 맥락: `PythonScriptExecutor`를 통해 GE를 파이프라인 단계로 실행 가능하나, Java 백엔드와의 이질성이 단점

**Soda Core — 선언적 데이터 모니터링**

SodaCL이라는 YAML 기반 DSL로 데이터 체크를 선언한다.

```yaml
# checks for incidents
checks for data.incidents:
  - row_count > 0
  - missing_count(incident_id) = 0
  - duplicate_count(incident_id) = 0
  - avg(response_seconds) between 60 and 600
  - anomaly detection for row_count
  - freshness(reported_at) < 1h
```

- 장점: YAML로 간결하게 체크 정의, 이상 감지 내장, 데이터 신선도(freshness) 추적, Slack/PagerDuty 알림
- 단점: Python 환경 필요, 고급 기능은 Soda Cloud (유료)
- Smart Fire Hub 맥락: 파이프라인 후처리 단계로 Soda 체크 실행 가능. 문법이 간결하여 학습 부담 적음

**Elementary — dbt 네이티브 관측성**

dbt 프로젝트 위에 구축되는 데이터 관측성 도구이다.

- 장점: dbt 테스트 결과를 자동 수집/시각화, 이상 감지, 리니지 시각화
- 단점: **dbt 필수** — dbt 없이는 사용 불가
- Smart Fire Hub 맥락: dbt를 도입하지 않는다면 **적용 불가**

**커스텀 데이터 품질 프레임워크 — 자체 구축**

기존 파이프라인 엔진에 데이터 품질 검증을 내장한다.

```
파이프라인 단계: [데이터 수집] → [변환] → [검증] → [적재]
                                        ↓
                                  검증 실패 시 알림 + 실행 중단/경고
```

구현 방식:

```sql
-- 검증 스텝 예시: data.incidents 테이블 품질 체크
SELECT
    'row_count' AS check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    COUNT(*) AS actual_value
FROM data.incidents
WHERE reported_at >= CURRENT_DATE - INTERVAL '1 day'

UNION ALL

SELECT
    'null_incident_id',
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END,
    COUNT(*)
FROM data.incidents
WHERE incident_id IS NULL

UNION ALL

SELECT
    'response_time_outlier',
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'WARN' END,
    COUNT(*)
FROM data.incidents
WHERE response_seconds > 3600  -- 1시간 초과
```

### 4.2 파이프라인 모니터링 체계

Smart Fire Hub에 적합한 파이프라인 모니터링 프레임워크를 설계한다.

#### 4계층 모니터링 모델

```
┌──────────────────────────────────────────────┐
│  Layer 4: 비즈니스 메트릭 모니터링             │
│  - 대응시간 SLA 충족률                         │
│  - 데이터 활용도 (쿼리 빈도, 사용자 수)         │
│  - AI 에이전트 활용 지표                       │
├──────────────────────────────────────────────┤
│  Layer 3: 데이터 품질 모니터링                  │
│  - 완전성 (null/missing 비율)                  │
│  - 정확성 (범위 체크, 형식 검증)                │
│  - 일관성 (참조 무결성, 중복 검사)              │
│  - 적시성 (데이터 신선도, 지연 시간)            │
├──────────────────────────────────────────────┤
│  Layer 2: 파이프라인 실행 모니터링              │
│  - 실행 상태 (성공/실패/진행중)                 │
│  - 실행 시간 추이                              │
│  - 단계별 소요 시간                            │
│  - 재시도 횟수/패턴                            │
├──────────────────────────────────────────────┤
│  Layer 1: 인프라 모니터링                      │
│  - DB 연결 풀 사용률                           │
│  - 디스크/메모리 사용량                        │
│  - API 응답 시간/오류율                        │
│  - Jobrunr 작업 큐 상태                        │
└──────────────────────────────────────────────┘
```

#### 구현 우선순위

| 우선순위 | 기능 | 구현 방법 | 현행 상태 |
|---------|------|----------|----------|
| **P0** | 파이프라인 실행 실패 알림 | 실행 완료 시 상태 체크 → 알림 (이메일/Slack) | 미구현 |
| **P0** | 데이터 신선도 추적 | 테이블별 마지막 업데이트 시간 기록/표시 | 미구현 |
| **P1** | 기본 데이터 품질 체크 | 파이프라인에 검증 스텝 추가 (null, unique, range) | 미구현 |
| **P1** | 실행 시간 추이 | 실행 이력에서 소요시간 집계/차트 | 부분 구현 (이력 존재) |
| **P2** | 스키마 변경 감지 | DDL 변경 시 audit_log 기록 + 알림 | 미구현 |
| **P2** | 이상 감지 | 행 수, 실행시간 등의 이동평균 기반 이상 탐지 | 미구현 |
| **P3** | 데이터 리니지 시각화 | 파이프라인 DAG + 데이터셋 의존성 그래프 | 부분 구현 (DAG 존재) |

### 4.3 데이터 신선도(Freshness) 추적 방안

```sql
-- 데이터 신선도 메타데이터 테이블
CREATE TABLE public.data_freshness_log (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT REFERENCES datasets(id),
    table_name VARCHAR(255) NOT NULL,
    last_row_timestamp TIMESTAMP,   -- 데이터 내 최신 타임스탬프 칼럼 값
    last_updated_at TIMESTAMP,       -- 마지막 INSERT/UPDATE 시간
    row_count BIGINT,
    checked_at TIMESTAMP DEFAULT NOW(),
    staleness_minutes DOUBLE PRECISION GENERATED ALWAYS AS (
        EXTRACT(EPOCH FROM (checked_at - last_updated_at)) / 60
    ) STORED
);
```

### 4.4 스키마 변경 감지

PostgreSQL의 Event Trigger 또는 애플리케이션 레벨에서 구현할 수 있다.

```sql
-- PostgreSQL Event Trigger로 DDL 변경 감지
CREATE OR REPLACE FUNCTION log_ddl_change()
RETURNS event_trigger AS $$
DECLARE
    obj record;
BEGIN
    FOR obj IN SELECT * FROM pg_event_trigger_ddl_commands()
    LOOP
        IF obj.schema_name = 'data' THEN
            INSERT INTO public.schema_change_log (
                event_type, schema_name, object_name, object_type, changed_at
            ) VALUES (
                TG_EVENT, obj.schema_name, obj.object_identity,
                obj.object_type, NOW()
            );
            PERFORM pg_notify('schema_change', json_build_object(
                'schema', obj.schema_name,
                'object', obj.object_identity,
                'type', obj.object_type
            )::text);
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE EVENT TRIGGER detect_schema_changes
ON ddl_command_end
EXECUTE FUNCTION log_ddl_change();
```

---

## 5. 아키텍처 권고안

### 5.1 제약 조건 요약

| 항목 | 현황 |
|------|------|
| **규모** | 단일 소방서 또는 지역 단위 (사용자 수십~수백 명) |
| **기존 스택** | Spring Boot 3.4 + Java 21, jOOQ, PostgreSQL 16, React 19, SSE |
| **파이프라인** | 자체 엔진 (SQL/Python/API 단계, DAG, 트리거 시스템) |
| **AI** | Claude Agent SDK + MCP 도구 36종 통합 완료 |
| **팀 규모** | 소규모 (전문 인프라 운영 인력 제한) |
| **UI 프레임워크** | shadcn/ui + Tailwind CSS v4 |

### 5.2 핵심 질문별 권고

---

#### Q1. 어떤 수준의 실시간 처리가 적절한가?

**권고: "PostgreSQL 네이티브 + SSE" 조합으로 시작하고, 필요 시 Redis Streams를 선택적으로 추가한다.**

```
┌─────────────────────────────────────────────────────────┐
│                  권장 실시간 아키텍처                      │
│                                                         │
│  ┌─────────┐    LISTEN/     ┌──────────┐    SSE     ┌─────────┐
│  │PostgreSQL│───NOTIFY────→│Spring Boot│─────────→│  React   │
│  │  (DB)    │              │  (API)    │          │  (Web)   │
│  └─────────┘              └──────────┘          └─────────┘
│       │                        │                            │
│  트랜잭션 커밋 시            이벤트 라우터               TanStack Query
│  자동 NOTIFY 발행          (채널별 SSE 분배)          + EventSource API
│                                                         │
│  [선택적 확장]                                           │
│  ┌──────────┐                                           │
│  │  Redis    │  ← GPS/센서 데이터 고빈도 수집 시에만 추가   │
│  │ Streams   │                                           │
│  └──────────┘                                           │
└─────────────────────────────────────────────────────────┘
```

**구체적 행동 항목:**

1. **즉시 적용**: PostgreSQL `LISTEN/NOTIFY`를 도입하여 `DATASET_CHANGE` 트리거의 30초 폴링을 이벤트 기반으로 전환
2. **단기**: 파이프라인 상태 변경, 데이터 임포트 완료 등의 이벤트를 `NOTIFY`로 전파하여 대시보드 실시간 갱신
3. **중기 (필요 시)**: GPS/센서 데이터 수집이 실제로 요구되면 Redis Streams 추가
4. **도입 금지**: Kafka, Flink, Pulsar — 현 규모에서 운영 복잡도 대비 이점이 없음

**Kafka가 아닌 PostgreSQL LISTEN/NOTIFY를 권장하는 이유:**
- 추가 인프라 제로 (이미 PostgreSQL 운영 중)
- 트랜잭션 일관성 (커밋 시에만 이벤트 발행)
- 소방서 규모의 이벤트 볼륨(초당 수 건~수십 건)에 완벽히 적합
- 대부분의 내부 도구/대시보드에서는 "1000+ 동시 연결, 50ms 미만 지연"으로 충분하며 이는 LISTEN/NOTIFY의 스펙 안에 있음

---

#### Q2. 기존 BI 도구를 임베딩할 것인가, 커스텀 구축할 것인가?

**권고: 커스텀 차트(Recharts/shadcn) 중심 + Grafana 운영 대시보드 보조 (하이브리드)**

| 영역 | 접근법 | 이유 |
|------|--------|------|
| **메인 대시보드** | Recharts (shadcn/ui Charts) | 이미 shadcn/ui 사용 중, 스타일 통합 완벽, 학습 비용 최소 |
| **운영 모니터링** | Grafana (iframe 임베딩) | 시계열 강점, 알림 내장, 무료 OSS, 별도 서비스로 분리 |
| **애드혹 분석** | AI 에이전트 (현행) | 자연어 → SQL → 결과. 이미 구현 완료 |
| **지도 시각화** | Apache ECharts (필요 시) | 사건/차량 위치 표시, GPU 가속 대용량 렌더링 |

**Metabase/Superset을 권장하지 않는 이유:**
- 별도 서버 운영 부담 (Metabase는 Java, Superset은 Python)
- iframe 임베딩의 UX 이질감 (shadcn/ui의 세련된 UI와 충돌)
- React SDK(Metabase Pro)는 월 $500+ 비용
- AI 에이전트가 이미 애드혹 분석 기능을 제공하므로 BI 도구의 핵심 가치가 감소

**Grafana를 보조 도구로 추천하는 이유:**
- 설치가 극도로 간단 (단일 바이너리 또는 Docker)
- PostgreSQL 네이티브 데이터소스로 추가 ETL 불필요
- 자동 새로고침, 알림(Alerting)이 운영 모니터링에 최적
- iframe 임베딩이 무료이며, 운영 대시보드는 별도 탭/페이지로 분리 가능

**즉시 행동 항목:**
1. `npx shadcn add chart`로 Recharts 차트 컴포넌트 추가
2. `dashboard` 모듈의 통계를 Recharts 기반 시각화로 구현
3. 향후 운영 모니터링 필요 시 Grafana Docker 컨테이너 추가

---

#### Q3. dbt를 도입할 것인가, 기존 파이프라인 엔진을 강화할 것인가?

**권고: dbt를 도입하지 않고, 기존 파이프라인 엔진에 dbt의 핵심 가치를 점진적으로 내재화한다.**

dbt 도입이 부적합한 핵심 이유:
1. **파이프라인 엔진 중복**: 이미 완전한 DAG 실행 엔진이 존재하며, SQL/Python/API 단계를 지원
2. **UI 편집기 손실**: dbt는 CLI/코드 기반이므로 웹 UI DAG 캔버스와 양립 불가
3. **API/Webhook 미지원**: 소방 데이터의 핵심인 외부 시스템 연동(CAD, AVL)이 dbt에서 불가
4. **운영 복잡도**: 두 개의 변환 시스템 관리는 소규모 팀에게 과도한 부담

**대신 기존 엔진에 추가할 기능:**

```
┌─────────────────────────────────────────────────────┐
│            파이프라인 엔진 강화 로드맵                  │
│                                                     │
│  [P0] 검증(Validation) 스텝 타입 추가                 │
│    → SQL assertion 기반 데이터 품질 체크               │
│    → 실패 시 알림 + 실행 중단/경고 옵션               │
│                                                     │
│  [P1] 증분 처리(Incremental) 모드                    │
│    → SqlScriptExecutor에 WHERE updated_at > :last_run│
│    → 전체 재처리 vs 증분 처리 선택 옵션               │
│                                                     │
│  [P2] 메타데이터 강화                                │
│    → 단계별 설명, 칼럼 문서화                        │
│    → 자동 리니지 추적 (SELECT 파싱 → 소스 테이블 추출) │
│                                                     │
│  [P3] 변환 템플릿                                    │
│    → 대응시간 계산, 사건 집계 등 소방 도메인 템플릿     │
│    → 재사용 가능한 SQL 조각(snippet) 라이브러리        │
└─────────────────────────────────────────────────────┘
```

---

#### Q4. 데이터 관측성은 어떻게 접근할 것인가?

**권고: 커스텀 경량 프레임워크를 기존 파이프라인 엔진 내에 구축한다.**

외부 도구(Great Expectations, Soda) 대신 자체 구축을 권장하는 이유:
- Python 의존성 최소화 (Java/Spring 기반 백엔드와의 일관성)
- 파이프라인 UI와의 자연스러운 통합
- 소방 도메인 특화 검증 규칙 직접 구현
- 점진적 확장 가능 (복잡도를 필요에 따라 조절)

**구현 아키텍처:**

```
파이프라인 실행 흐름:

  [데이터 수집] → [변환] → [검증 스텝] → [적재] → [신선도 기록]
                            │                      │
                            ↓                      ↓
                     검증 결과 저장          data_freshness_log
                     (PASS/WARN/FAIL)       테이블 갱신
                            │
                            ↓
                     FAIL 시: NOTIFY → SSE → UI 알림
                     WARN 시: 로그 기록 + 대시보드 표시
```

**검증 규칙 타입:**

| 규칙 | SQL 패턴 | 용도 |
|------|---------|------|
| `not_null` | `COUNT(*) WHERE col IS NULL = 0` | 필수 칼럼 검증 |
| `unique` | `COUNT(*) = COUNT(DISTINCT col)` | 키 중복 검사 |
| `range` | `MIN(col) >= X AND MAX(col) <= Y` | 값 범위 검증 |
| `freshness` | `MAX(timestamp_col) >= NOW() - INTERVAL` | 데이터 신선도 |
| `row_count` | `COUNT(*) > 0` (또는 이전 대비 변화율) | 데이터 존재 확인 |
| `referential` | `LEFT JOIN parent WHERE child.fk IS NULL` | 참조 무결성 |
| `custom_sql` | 사용자 정의 SQL 반환 PASS/FAIL | 도메인 특화 규칙 |

---

#### Q5. 최소 실행 가능 실시간 아키텍처(MVA)는 무엇인가?

**Phase 0 (즉시 적용 — 추가 인프라 없음)**

현행 시스템만으로 구현 가능한 개선사항:

```
┌──────────────────────────────────────────────────────┐
│                   Phase 0 아키텍처                    │
│                                                      │
│  PostgreSQL                Spring Boot         React  │
│  ┌──────────┐            ┌──────────┐      ┌───────┐ │
│  │ LISTEN/  │──이벤트──→│ 이벤트   │─SSE─→│ 실시간 │ │
│  │ NOTIFY   │           │ 라우터   │      │ UI    │ │
│  └──────────┘           └──────────┘      └───────┘ │
│                                                      │
│  기존 SseEmitter 활용, 추가 인프라 없음               │
│  폴링 → 이벤트 기반으로 전환                          │
└──────────────────────────────────────────────────────┘

구현 내용:
1. PostgreSQL LISTEN/NOTIFY 리스너 (Spring @PostConstruct)
2. 통합 SSE 엔드포인트 (/api/v1/events/stream)
3. 채널별 이벤트 타입:
   - pipeline.status   — 파이프라인 실행 상태 변경
   - dataset.changed   — 데이터셋 데이터 변경
   - import.progress   — 데이터 임포트 진행
   - alert.triggered   — 알림/경보 발생
4. 프론트엔드 EventSource 훅 (useRealtimeEvents)
```

**Phase 1 (단기 — 1~2개월)**

대시보드 시각화와 기본 데이터 품질:

```
구현 내용:
1. shadcn/ui Charts (Recharts) 대시보드 구축
   - 데이터셋 현황 차트
   - 파이프라인 실행 추이
   - KPI 카드 (사건 수, 대응 시간 등)
2. 파이프라인 검증 스텝 추가
   - ValidationStepExecutor (SQL assertion 기반)
   - 검증 결과 저장/표시
3. 데이터 신선도 추적
   - data_freshness_log 테이블
   - 대시보드에 신선도 표시기
```

**Phase 2 (중기 — 3~6개월)**

운영 모니터링과 알림:

```
구현 내용:
1. Grafana 도입 (Docker)
   - PostgreSQL 데이터소스 연결
   - 파이프라인 메트릭 대시보드
   - 데이터 품질 대시보드
   - 알림 규칙 설정 (Slack/이메일)
2. 스키마 변경 감지
   - PostgreSQL Event Trigger
   - 변경 이력 로깅
3. 알림 시스템 확장
   - 통합 알림 채널 (Slack, 이메일, 인앱)
   - 알림 규칙 관리 UI
```

**Phase 3 (장기 — 필요 시)**

고빈도 데이터 수집:

```
구현 내용 (필요한 경우에만):
1. Redis Streams (차량 GPS, IoT 센서)
2. 지도 시각화 (ECharts/Leaflet)
3. 시계열 데이터 최적화 (TimescaleDB 또는 파티셔닝)
```

---

### 5.3 종합 기술 선택 매트릭스

| 영역 | 선택 | 대안 | 도입하지 않는 것 |
|------|------|------|----------------|
| **실시간 이벤트** | PostgreSQL LISTEN/NOTIFY | — | Kafka, Flink, Pulsar |
| **이벤트 스트리밍** | SSE (SseEmitter, 현행 유지) | WebFlux (동시 연결 증가 시) | WebSocket (불필요) |
| **고빈도 데이터 수집** | Redis Streams (필요 시) | NATS (IoT 특화) | RabbitMQ (불필요) |
| **메인 대시보드** | Recharts (shadcn/ui Charts) | Tremor (대시보드 특화) | — |
| **지도 시각화** | Apache ECharts (필요 시) | Leaflet/Mapbox | — |
| **운영 모니터링** | Grafana (Phase 2) | — | Metabase, Superset |
| **애드혹 분석** | AI 에이전트 (현행) | — | Redash (중복) |
| **데이터 변환** | 파이프라인 엔진 강화 | — | dbt (중복, 과잉) |
| **데이터 품질** | 커스텀 검증 스텝 | Soda Core (Python 허용 시) | GE (복잡), Elementary (dbt 필수) |
| **데이터 신선도** | 커스텀 (freshness_log 테이블) | — | — |
| **스키마 감지** | PostgreSQL Event Trigger | — | — |
| **알림** | 커스텀 + Grafana Alerting | — | — |

---

### 5.4 비용-효과 분석

| 접근법 | 추가 인프라 비용 | 운영 부담 | 개발 비용 | 기능 커버리지 |
|--------|---------------|----------|----------|-------------|
| **권고안 (Phase 0~2)** | Docker 컨테이너 1개 (Grafana) | 매우 낮음 | 중간 | 90% |
| Kafka + Superset + dbt | 서버 3~5대 (Kafka 클러스터 + Superset + dbt) | 매우 높음 | 높음 | 100% |
| 엔터프라이즈 (Flink + 상용 BI) | $10,000+/월 | 전담 인력 필요 | 높음 | 100% |

**권고안의 핵심 원칙**: 기존 PostgreSQL과 Spring Boot 생태계를 최대한 활용하여 추가 인프라를 최소화하고, 소방 도메인에 필요한 기능만을 정확히 구현한다. "지금 필요하지 않은 것"은 도입하지 않되, 향후 확장이 가능한 구조로 설계한다.

---

## 참고 자료

### 실시간 데이터 처리
- [Apache Kafka vs Redis Streams](https://betterstack.com/community/comparisons/redis-vs-kafka/)
- [Redis Streams vs Apache Kafka vs NATS](https://salfarisi25.wordpress.com/2024/06/07/redis-streams-vs-apache-kafka-vs-nats/)
- [NATS vs Redis vs Kafka: Message Broker Comparison 2026](https://www.index.dev/skill-vs-skill/nats-vs-redis-vs-kafka)
- [Best Apache Kafka Alternatives in 2026](https://brndle.com/apache-kafka-alternatives-real-time-data-streaming-event-processing/)
- [Compare NATS](https://docs.nats.io/nats-concepts/overview/compare-nats)
- [Real-Time Stream Processing: Kafka vs Pulsar vs Flink](https://www.suryasys.com/post/real-time-stream-processing-kafka-vs-pulsar-vs-flink)
- [Comparing Stream Processing Engines](https://www.onehouse.ai/blog/apache-spark-structured-streaming-vs-apache-flink-vs-apache-kafka-streams-comparing-stream-processing-engines)

### SSE / WebFlux / WebSocket
- [Reactive Real-Time Notifications with SSE, Spring Boot, and Redis Pub/Sub](https://www.infoq.com/articles/reactive-notification-system-server-sent-events/)
- [Real-Time Data Streaming with Spring WebFlux and SSE](https://dev.to/guneet_08/real-time-data-streaming-with-spring-webflux-and-sse-1obk)
- [Server-Sent Events in Spring](https://www.baeldung.com/spring-server-sent-events)
- [Spring WebFlux and Server-Sent Events](https://blog.stackademic.com/spring-webflux-and-server-sent-events-a-match-made-in-heaven-89e96e912ea0)

### PostgreSQL LISTEN/NOTIFY
- [PostgreSQL LISTEN/NOTIFY for Pub/Sub](https://neon.com/guides/pub-sub-listen-notify)
- [Real-Time Log Streaming with PostgreSQL LISTEN/NOTIFY](https://dev.to/polliog/building-real-time-log-streaming-with-postgresql-listennotify-4cbj)
- [Postgres as a Message Bus](https://thinhdanggroup.github.io/postgres-as-a-message-bus/)
- [How to Use Listen/Notify for Real-Time Updates in PostgreSQL](https://oneuptime.com/blog/post/2026-01-25-use-listen-notify-real-time-postgresql/view)
- [Scaling Postgres LISTEN/NOTIFY](https://pgdog.dev/blog/scaling-postgres-listen-notify)

### BI 도구
- [Apache Superset vs Metabase 2026 Guide](https://bix-tech.com/apache-superset-vs-metabase-the-nononsense-guide-to-choosing-the-right-opensource-bi-platform-in-2026/)
- [Top Open Source BI Tools in 2025](https://www.getgalaxy.io/learn/data-tools/open-source-bi-tools-2025)
- [Superset vs Metabase vs Redash](https://hevodata.com/blog/superset-vs-metabase-vs-redash/)
- [Metabase Embedded Analytics SDK for React](https://www.metabase.com/product/embedded-analytics-sdk)
- [Embedding Apache Superset in React](https://www.tetranyde.com/blog/embedding-superset/)
- [Superset Embedded SDK (npm)](https://www.npmjs.com/package/@superset-ui/embedded-sdk)

### React 차트 라이브러리
- [8 Best React Chart Libraries 2025](https://embeddable.com/blog/react-chart-libraries)
- [Best React Chart Libraries 2025 Update](https://blog.logrocket.com/best-react-chart-libraries-2025/)
- [shadcn/ui Charts](https://ui.shadcn.com/charts/area)
- [shadcn Charts & Graphs](https://allshadcn.com/components/category/charts-graphs/)

### dbt
- [Is dbt Core Still the Gold Standard? 2025 Review](https://sider.ai/blog/ai-tools/is-dbt-core-still-the-gold-standard-a-2025-review)
- [How dbt uses PostgreSQL for Data Transformation](https://community.getorchestra.io/dbt/how-dbt-uses-postgresql-for-data-transformation/)
- [dbt + PostgreSQL + Metabase Workshop](https://dbt-postgresql-metabase-workshop.pages.dev/part2dbt/)
- [dbt Pricing Guide 2026](https://mammoth.io/blog/dbt-pricing/)

### 데이터 관측성
- [2026 Open-Source Data Quality and Observability Landscape](https://datakitchen.io/the-2026-open-source-data-quality-and-data-observability-landscape/)
- [Data Observability: Soda vs Great Expectations](https://www.castordoc.com/tool-comparison/data-observability-tool-comparison-soda-vs-great-expectations)
- [dbt vs Great Expectations vs Soda](https://cybersierra.co/blog/best-data-quality-tools/)
- [Data Quality Framework 2025](https://www.ewsolutions.com/data-quality-framework/)
- [Data Pipeline Monitoring Best Practices](https://www.rudderstack.com/blog/data-pipeline-monitoring/)

### 소방 CAD 시스템
- [Computer Aided Dispatch for Fire and Rescue](https://www.getac.com/us/industries/public-safety/fire-and-rescue-computer-aided-dispatch/)
- [Smart CAD Fire Service Software](https://www.ginasoftware.com/blog/fire-service-software/)
- [Interfacing with CAD Systems for Fire Departments](https://www.emergent.tech/blog/cad-systems-technology-integration)
