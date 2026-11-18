# WD-797 — 통합 PR #1049(WD-791) 리뷰 후속 설계 (짧은 설계)

자율 work-loop 세션이라 설계 승인은 스스로 했다. 이슈 본문이 「근본 쪽은 가능하면 검토, 바꾸지 않으면 이유」라 접근 선택을 맡겼다.
조사 근거는 `.superpowers/recon-wd797.md` 에 있다(로컬 — 아래에 요지를 옮김).

## 1. API_CALL 미리보기의 해석기 고정

원인:
- `ApiCallPreviewService` 가 생성자에서 WebClient 를 한 번 만든다.
- 그때 `SsrfSafeHttpClientFactory.httpClient()` 가 넣은 `.resolver(group)` 하나만 낡는다. reactor-netty 1.2.18 의 `HttpClient` 는 풀·루프·기본 해석기를 접속 때 `HttpResources.get()` 으로 찾지만, 직접 넣은 resolver 는 그렇지 않다.

결정 — A + B:
- **A**: 미리보기는 요청마다 클라이언트를 얻는다(`ApiConnectionService.healthCheckClient()` 와 같은 모양).
- **B**: 팩토리는 인스턴스마다 바뀌지 않는 `SsrfGuardedAddressResolverGroup` 하나를 둔다. 그 그룹은 위임 DNS 를 `newResolver(executor)` 때 공급자(`Supplier`)로 찾는다.
  - `AddressResolverGroup` 은 실행기마다 해석기를 캐시하고, 실행기가 끝나면 지운다. 전역 자원이 바뀌면 새 실행기에서 `newResolver` 가 다시 불려 지금 위임을 쓴다.
  - 그래서 인스턴스 캐시·`synchronized resolverGroup()`·교체 때 닫기가 없어지고, `httpClient()` 는 락 없이 지나간다.
  - 오래 사는 클라이언트(앞으로 생길 것 포함)도 교체를 따라간다.
  - `DefaultDns` 의 정적 캐시(전역 자원에 묶인 위임 그룹을 만들고 닫는 곳)는 그대로 둔다.

> **구현 중 바뀜(최종 리뷰 뒤) — B 는 되돌렸다. A 만 남는다.** 최종 리뷰에서 B 의 위험 두 가지가 나왔다.
> - 같은 실행기를 둔 채 `DefaultDns` 가 위임을 바꾸면(교체 중 graceful 종료로 옛 실행기가 살아 있는 동안 등) 실행기별로 캐시된 해석기가 닫힌 위임을 영영 가리킨다. 예전 방식은 `httpClient()` 마다 위임을 비교해 회복했다.
> - `DefaultDns.current()` 가 `newResolver` 안, 곧 이벤트 루프 스레드에서 불려 컨텍스트 종료 뒤 전역 자원을 되살리거나 다른 실행기가 쓰는 위임을 닫을 수 있다.
>
> 오래 사는 호출부는 미리보기 하나였고 A 가 그것을 고친다. 그래서 팩토리는 main 그대로 두고 `httpClient()` Javadoc 에 「돌려준 클라이언트를 필드에 오래 두지 않는다」는 약속만 적었다.

버린 대안 — C: 기본 해석기 + `resolvedAddressesSelector` 로 정책 검사. 지금은 하지 않는다. 이유:
1. reactor-netty 1.2.18 에서, 이미 풀린 주소(IP 리터럴) 경로에 selector 가 예외를 던지면 채널을 닫지 않고 `Mono.error` 로 끝난다(바이트코드 확인).
   - 막힌 요청마다 소켓이 하나씩 남을 수 있다. 지금 해석기는 해석 안에서 실패해 채널이 닫힌다.
2. SSRF 검사 자리가 옮겨져, SSRF 시험 여러 벌(느린 DNS 비차단·가짜 DNS 재바인딩)을 다시 짜야 한다.
3. Boot 4(reactor-netty 1.3) 전환(WD-792)이 다가와, 그때 selector 동작을 다시 확인하는 편이 맞다.

이 이유는 팩토리 Javadoc 에 남긴다.

회귀 시험:
- `ApiCallPreviewServiceTest` 에 둔다. 서비스를 반복 **밖에서 한 번** 만든 뒤, `ReactorResourceFactory` 시작·종료를 3회 반복한다.
- 반복마다 확인한다:
  - 사전 검사를 끈 서비스가 `localhost`(호스트 이름 — IP 리터럴은 DNS 를 타지 않음)를 루프백으로 막는다.
  - 허용 정책 서비스가 정상 응답을 받는다.
- 막힌 경로는 WireMock 이 요청을 받지 않는다.
- 수정 전 실패·수정 후 통과를 확인한다.

## 2. PG 57014 번역

조사 결과 다른 소비처가 없다. 그래서 코드를 바꾸지 않고 근거를 이슈·PR 에 남긴다. 근거:
- `PreCommitDeadline.exceededBy` 는 옛 타입·새 타입·57014 를 모두 받는다.
- `GlobalExceptionHandler` 는 두 타입 모두 500 으로 처리한다.
- Spring Retry·RetryTemplate 은 없고, JobRunr 재시도는 예외 타입과 무관하다.

PG 번역기 하나로 맞추려면 번역 지점 여섯 곳에 엮어야 하는데, 쓰는 곳이 없어 하지 않는다.

## 3. buildscript 의존성

- buildscript 의 postgresql 42.7.4·testcontainers 1.20.4 는 Boot 3.4.1 BOM 값이다. Boot 3.5.16 BOM(런타임·시험)은 42.7.11·1.21.4 다.
- buildscript 는 `generateJooq` 만 쓴다(Testcontainers PG → Flyway → jOOQ codegen).
- 결정: 두 값을 런타임과 같게 올리고, 주석(L169 포함)을 「Boot BOM 값을 손으로 따라 맞춘다 — Boot 를 올릴 때 함께 올린다」로 실제와 맞춘다.
- 확인: `generateJooq` 를 실제로 돌려 생성 소스가 바뀌지 않는다(diff 없음).

## 제약

- 컨테이너 메모리 16GB 를 두 세션이 나눠 쓴다. 기차 33 게이트(gradle)가 도는 동안에는 gradle 시험·generateJooq 를 돌리지 않는다. 게이트가 끝난 뒤 컨트롤러가 돌린다.
