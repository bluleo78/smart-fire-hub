# WD-803 Qdrant 시험 호출 대기 상한 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공유 머신 과부하 아래에서도 Qdrant 시험이 gRPC 응답 30초 대기 초과로 깨지지 않게, 시험 하네스의 호출 대기 상한을 한 곳(120초)으로 모은다.

**Architecture:** `QdrantTestContainer` 에 상수 `CALL_TIMEOUT` 을 두고, `properties()` 와 Qdrant 시험의 하네스 클라이언트 직접 호출(`.get(30, TimeUnit.SECONDS)`)이 그 상수를 쓴다. 운영 코드는 바꾸지 않는다.

**Tech Stack:** Java 21, JUnit5, Testcontainers(Qdrant), qdrant java client

**Spec:** `docs/superpowers/specs/2026-11-19-wd803-qdrant-test-timeout-design.md`

## Global Constraints

- 한국어 Javadoc/주석 필수(무엇을·왜, WD-803 표기).
- 셸 명령은 단순하게 쓴다: 한 줄 셸 함수 정의, `${…:-{…}}`, `;`·`&&` 로 길게 이은 한 줄은 쓰지 않는다.
- `src/main` 은 건드리지 않는다.
- 커밋은 `git commit --no-verify` 하나.

## Review Focus

1. 시간 초과를 일부러 검사하는 시험(`new QdrantProperties(..., Duration.ofSeconds(2|5|8|10))`, 꺼진 컨테이너·`127.0.0.1:1`)은 그대로여야 한다 — 그 짧은 상한이 시험 목적이다.
2. 바꾼 `.get(...)` 은 하네스 Qdrant 컨테이너를 부르는 것뿐이어야 한다(다른 비동기 호출의 30초는 건드리지 않는다).
3. 상수는 한 곳에만 정의되고, 값을 바꾸면 모든 Qdrant 시험의 대기가 함께 바뀌어야 한다.
4. 시험 하나가 실제로 120초 넘게 막히는 경우(진짜 결함)에도 전체 실행이 끝없이 걸리지 않는다 — 호출마다 상한이 있으므로 그렇다. 무한 대기(`get()` 인자 없음)를 만들지 않는다.

## Task 1: 하네스 상수와 사용처

**Files:**
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantTestContainer.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantVectorStoreLifecycleTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantVectorConsistencyTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantRowSearchIndexTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantDedicatedRowTargetTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/rowsearch/QdrantRowSearchServiceTest.java`

- [ ] **Step 1:** `QdrantTestContainer` 에 `public static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);` 를 두고 Javadoc 에 이유(WD-803 — load 128 에서 30초 초과, 운영 기본값과 무관)를 적는다. `properties()` 의 `Duration.ofSeconds(30)` 과 `deleteAllCollections` 의 `.get(30, TimeUnit.SECONDS)` 를 이 상수로 바꾼다(`.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)`).
- [ ] **Step 2:** 위 시험 파일들에서 하네스 Qdrant 클라이언트 호출의 `.get(30, TimeUnit.SECONDS)` 를 `.get(QdrantTestContainer.CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)` 로 바꾼다(같은 패키지면 클래스 이름 생략 가능 — 정적 import 를 써도 된다). 각 줄이 Qdrant 호출인지 확인하고 아니면 두지 않는다.
- [ ] **Step 3:** `cd apps/firehub-api` 뒤 `./gradlew compileTestJava -x generateJooq` 로 컴파일을 확인한다. spotless 를 돌리면(`./gradlew spotlessApply -x generateJooq`) 바꾼 파일 밖의 변경은 되돌린다.
- [ ] **Step 4:** `grep -rn "get(30, TimeUnit.SECONDS)"` 로 위 파일에 Qdrant 호출이 남지 않았는지 본다.
- [ ] **Step 5:** 커밋 `test(api): Qdrant 시험의 호출 대기 상한을 하네스 상수(120초)로 모은다 (WD-803)` — `--no-verify`.

시험 실행(Qdrant 패키지·부하 재현)은 컨트롤러가 한다.
