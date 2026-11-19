# WD-804 통합 PR #1051 리뷰 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #1051 리뷰 지적 7개를 확인해 맞는 것은 고치고, 고치지 않는 것은 이유를 코드 주석으로 남긴다.

**조사 원본:** `.superpowers/recon-wd804.md`(지적별 file:line, 고칠 모양, 시험 위치) — 구현자는 먼저 이 파일을 읽는다.

**Tech Stack:** React 19 + TS + sonner 2.0.7 + Playwright E2E + vitest(web), Java 21 + JUnit5(api)

## 결정(내가 정한 것)

- 1: 저장 전에 보이게 — `extraHeaderRowErrors` 에 개수 초과 검사(보낼 행 기준, 11번째부터 이름 칸 오류). 만들기 대화상자도 같은 함수라 함께 막힌다.
- 2: 단순 대소문자 무시 비교는 기존 E2E(손대지 않은 X-Id 가 다른 곳에서 x-id 로 바뀌면 더해져야 함)를 깨므로, 「이전 판에 있던 헤더(대소문자 무시)인데 지금 사용자 행에 없으면 = 사용자가 지운 것 → 되살리지 않는다」로 판정한다.
- 3: 토스트 id 를 이름 목록으로 만든다(`api-connection-extra-headers-added:${names}`, 「빠짐」 토스트도 같게). 같은 안내는 하나로 모이고(StrictMode 중복 방지 유지) 다른 안내는 쌓인다.
- 4: 코드 변경 없음. 팩토리 Javadoc 에 C(`resolvedAddressesSelector`)를 WD-792 로 미룬 이유(WD-797 설계에 약속했으나 빠짐)와 지금 호출처가 모두 요청마다 만든다는 점을 적는다.
- 5: 코드 변경 없음. `resolverGroup()` 의 옛 그룹 `close()` 는 Netty 캐시만 비우고 `GuardedResolver.close()` 는 하는 일이 없으며, 실제 위임 닫기는 `DefaultDns.current()` 에서 옛 자원 폐기 뒤에만 일어난다는 것을 주석으로 남긴다.
- 6: 두 시험(`ApiCallPreviewServiceTest`·`SsrfSafeHttpClientFactoryTest`)에 정직한 주석 — 재현에 전역 폐기가 필요한 이유와, 같은 포크의 캐시된 컨텍스트(Boot 자동 구성 `WebClient.Builder` 커넥터)가 죽은 루프를 쥘 수 있는 잠재 위험. 별도 JVM 으로 옮기는 시험 인프라 변경은 이슈 범위 밖이라 하지 않는다.
- 7: `try` 를 목록 호출로 좁히고 null 판정은 try 밖에서 필드 이름을 적어 실패시킨다.

## Global Constraints

- 한국어 주석/Javadoc 필수(무엇을·왜, WD-804 표기).
- 셸 명령은 단순하게: 한 줄 셸 함수 정의, `${…:-{…}}`, `;`·`&&` 로 길게 이은 한 줄은 쓰지 않는다.
- **gradle 시험·Playwright E2E 는 돌리지 않는다**(다른 무거운 시험이 메모리를 함께 쓴다). 컴파일·타입검사·vitest 만: api 는 `./gradlew compileJava compileTestJava -x generateJooq`, web 은 `pnpm typecheck` 와 해당 vitest 파일.
- 커밋은 과제마다 하나, `git commit --no-verify`.

## Review Focus

1. 1번 개수 검사는 「보낼 행」만 센다 — 빈 이름·빈 값 새 행 등 저장에서 빠지는 행은 세지 않는다(기존 저장 직렬화 규칙과 같게).
2. 2번 판정이 기존 E2E 두 경우(손대지 않은 X-Id→x-id 는 더함, 사용자가 지운 헤더는 되살리지 않음)를 모두 지킨다.
3. 3번 id 에 이름 목록을 넣을 때 순서가 같으면 같은 id 가 되어야 한다(StrictMode 이중 실행에서 한 번만 보임).
4. 7번 변경 뒤에도 Qdrant 연결 실패는 여전히 「Qdrant 컬렉션 목록을 읽지 못했습니다」로 보인다.

## Task 1: api (7번 + 4·5·6 주석)

- [ ] `QdrantRowSearchServiceTest` 7번 수정.
- [ ] `SsrfSafeHttpClientFactory` Javadoc·주석(4·5).
- [ ] `ApiCallPreviewServiceTest`·`SsrfSafeHttpClientFactoryTest` 주석(6).
- [ ] 컴파일 확인 후 커밋 `fix(api): …(WD-804)` 형식이 아니라 과제 커밋 `test(api): WD-804 리뷰 후속 — Qdrant 시험 null 판정·SSRF 팩토리 수명 주석 (WD-804)`.

## Task 2: web (3 → 2 → 1)

- [ ] 3번 토스트 id.
- [ ] 2번 되살림 판정.
- [ ] 1번 개수 검사 + vitest(`api-connection-extra-headers` 단위 시험).
- [ ] E2E: `apps/firehub-web/e2e/pages/admin/api-connections.spec.ts` 의 `WD-796 재조회 병합 후속` describe 에 1·2·3 경우를 더한다(`saveInfoAndRefetch`·`openAuthEdit`·`savedExtraHeaders` 재사용). 돌리지는 않는다.
- [ ] `pnpm typecheck`·vitest 확인 후 커밋 `fix(web): WD-804 리뷰 후속 — 재조회 더한 헤더 개수 상한·지운 헤더 되살림·토스트 덮어씀 (WD-804)`.
