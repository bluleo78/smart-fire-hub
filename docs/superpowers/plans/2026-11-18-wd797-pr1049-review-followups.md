# WD-797 통합 PR #1049 리뷰 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API_CALL 미리보기가 전역 reactor-netty 자원 교체 뒤에도 SSRF 접속 검사 해석기로 동작하게 한다. 팩토리를 락 없이 교체를 따라가게 하고, buildscript 의존성을 런타임과 맞춘다.

**Architecture:** 다음 둘을 함께 한다.
- 미리보기는 요청마다 클라이언트를 얻는다(A).
- 팩토리는 위임 DNS 를 실행기별 해석기를 만들 때 공급자로 찾는 고정 그룹 하나를 둔다(B).

**Tech Stack:** Java 21, Spring Boot 3.5.16, reactor-netty 1.2.18, JUnit5, WireMock, Mockito

**Spec:** `docs/superpowers/specs/2026-11-18-wd797-pr1049-review-followups-design.md` (조사 원본: `.superpowers/recon-wd797.md`)

## Global Constraints

- 한국어 Javadoc/주석 필수(무엇을·왜, WD-797 표기). C 를 하지 않은 이유 1~3 을 `SsrfSafeHttpClientFactory` 클래스 Javadoc 에 남긴다.
- 셸 명령은 단순하게 쓴다: 한 줄 셸 함수 정의, `${…:-{…}}`, `;`·`&&` 로 길게 이은 한 줄은 쓰지 않는다.
- **gradle 시험·generateJooq 는 돌리지 않는다**(메모리 공유 게이트 진행 중). 컴파일만 한다: `cd apps/firehub-api` 뒤 `./gradlew compileJava compileTestJava -x generateJooq`. 시험은 컨트롤러가 뒤에 돌린다.
- spotless 를 쓰면 바꾼 파일만 남기고 다른 파일 변경은 되돌린다.
- 커밋은 `git commit --no-verify`, 과제마다 하나다.

## Review Focus

1. 미리보기가 IP 리터럴로 루프백을 부를 때도(사전 검사가 놓친 경우) 접속 검사가 그대로 막아야 한다 — 그룹 구조를 바꿔도 `doIsResolved=false` 동작은 유지한다.
2. 시험용 고정 DNS(`fixedDns`) 생성자로 만든 팩토리는 위임이 늘 그 가짜 DNS 여야 한다 — 기존 `ApiCallExecutorTest` 재바인딩 시험·`dnsDelegate()` 시험이 그대로 통과한다.
3. 팩토리의 고정 그룹을 `close()` 하는 곳이 남아 공유 위임(정적 DefaultDns)을 닫지 않아야 한다 — 위임의 수명은 DefaultDns 가 쥔다.
4. 여러 스레드가 동시에 `httpClient()` 를 불러도 락·경합 없이 같은 그룹을 쓴다.
5. 미리보기 요청마다 만드는 클라이언트는 공유 풀·루프를 쓰므로, 요청 수만큼 커넥션 풀이 늘지 않는다(`ApiConnectionService.healthCheckClient()` 와 같은 수준).

---

### Task 1: 팩토리 고정 그룹(지연 위임) + 미리보기 요청마다 클라이언트 + 회귀 시험

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/SsrfSafeHttpClientFactory.java` (필드 L39–44, 생성자 L100–121, `resolverGroup()` L127–139, `dnsDelegate()` L142–144, `httpClient()` L150–152, 클래스 Javadoc)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/SsrfGuardedAddressResolverGroup.java` (생성자·`delegate()`·`newResolver` L60–62)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/ApiCallPreviewService.java` (필드 L43, Javadoc L35–42, 생성자 L45–56, `executeRequest` L209–227)
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/pipeline/service/ApiCallPreviewServiceTest.java`

**Interfaces:**
- `SsrfGuardedAddressResolverGroup(Supplier<AddressResolverGroup<InetSocketAddress>> delegate, AddressPolicy policy)`
  - `newResolver(executor)` 가 `delegate.get().getResolver(executor)` 를 쓴다.
  - 시험이 쓰는 기존 생성자 `(AddressResolverGroup, AddressPolicy)` 가 있으면 `() -> group` 으로 위임해 남긴다.
- `SsrfSafeHttpClientFactory`: `private final SsrfGuardedAddressResolverGroup resolverGroup` 를 생성자에서 한 번 만든다.
  - 고정 DNS 면 `() -> fixedDns`, 기본이면 `DefaultDns::current` 를 넘긴다.
  - `httpClient()` 는 `HttpClient.create().resolver(resolverGroup)` 이다(락 없음).
  - `dnsDelegate()` 는 공급자의 지금 값을 돌려준다.
  - `synchronized`·인스턴스 캐시·교체 때 `close()` 는 지운다.
- `ApiCallPreviewService`: 필드 `webClientBuilder`·`ssrfSafeHttpClientFactory` 를 두고 `private WebClient client()` 로 요청마다 `RedirectFollower.withoutAutoRedirect(webClientBuilder, ssrfSafeHttpClientFactory).build()` 를 쓴다.

- [ ] **Step 1: 회귀 시험을 쓴다** — `preview_keepsSsrfConnectCheckAfterGlobalReactorResourcesAreReplaced()` (`ApiCallPreviewServiceTest`)
  - 반복 밖에서 두 서비스를 만든다.
    - `blocking`: 사전 검사를 끈 `noOpSsrf`(파일의 기존 픽스처) + `new SsrfSafeHttpClientFactory(new SsrfProtectionService())`
    - `allowing`: 같은 구성에 `new SsrfSafeHttpClientFactory(address -> {})`
  - `for (cycle 0..2)`: `ReactorResourceFactory` 를 `afterPropertiesSet()`·`start()` 한 뒤 아래를 단언하고 `stop()`·`destroy()` 한다.
    - `blocking.preview(http://localhost:<wiremock 포트>/wd797, timeoutMs 3000)` → `success()==false`, `errorMessage()` 가 `"내부망 주소는 호출할 수 없습니다(루프백): "` 로 시작
    - `allowing.preview(같은 URL)` → `success()==true` (WireMock 에 `/wd797` 200 JSON 스텁)
  - 반복 뒤 확인한다: WireMock 의 `/wd797` 요청 수 == 3(허용 쪽만), 막힌 요청은 0.
  - 시험 Javadoc 에 왜 반복 밖에서 한 번 만드는지(오래 사는 클라이언트 재현), 왜 호스트 이름인지 적는다.
- [ ] **Step 2: 구현한다** — 위 Interfaces 대로. 클래스 Javadoc 에 B 의 원리(실행기별 캐시·교체 뒤 새 실행기)와 C 를 하지 않은 이유 1~3 을 적는다.
- [ ] **Step 3: 컴파일한다** — `cd apps/firehub-api` 뒤 `./gradlew compileJava compileTestJava -x generateJooq`. 시험 실행은 하지 않는다(컨트롤러가 수정 전 실패·수정 후 통과를 확인한다).
- [ ] **Step 4: 커밋한다**

### Task 2: buildscript 의존성을 런타임과 맞춘다

**Files:**
- Modify: `apps/firehub-api/build.gradle.kts` (buildscript L4–15, 주석 L169 근처)

- [ ] **Step 1**: buildscript 의 `org.postgresql:postgresql` 를 `42.7.11`, testcontainers 를 `1.21.4` 로 바꾼다.
  - 바꾸기 전에 Boot 3.5.16 BOM 값과 같은지 한 번 확인한다: Gradle 캐시의 `spring-boot-dependencies-3.5.16.pom`, 또는 `./gradlew -q dependencyInsight --dependency org.postgresql:postgresql --configuration runtimeClasspath -x generateJooq` — 이것은 가벼우니 돌려도 된다.
- [ ] **Step 2**: 주석을 실제에 맞춘다 — 「buildscript 클래스패스는 Boot BOM 밖이라 generateJooq 용으로 Boot BOM 값을 손으로 따라 맞춘다 — Boot 를 올릴 때 함께 올린다(WD-797)」. L169 의 testcontainers 주석도 같다.
- [ ] **Step 3**: `./gradlew help -x generateJooq` 로 스크립트가 해석되는지만 본다. generateJooq 실행은 컨트롤러가 뒤에 한다.
- [ ] **Step 4: 커밋한다**
