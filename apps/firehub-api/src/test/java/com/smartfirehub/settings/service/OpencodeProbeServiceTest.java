package com.smartfirehub.settings.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult.Reason;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@link OpencodeProbeService} 테스트.
 *
 * <p><b>두 그룹으로 나눈다.</b> (1) 실제 SSRF 가드를 쓰는 그룹 — 사설 대역/스킴/포트/DNS
 * rebinding 을 검증한다. 네트워크를 전혀 타지 않는다(가드가 프리플라이트에서 막는다는 것 자체가
 * 요구사항이므로, 만약 가드가 뚫려 실제 연결을 시도해도 테스트가 "왜" 실패했는지 구분할 수 있도록
 * {@code ok()} 가 아니라 {@code message()} 를 단언한다 — {@code ok()==false} 만 보면 가드를
 * 지워도(연결이 실패해서) 여전히 초록불이 되는 공허한 테스트가 된다).
 * (2) {@code validateTarget()} 을 통째로 건너뛰는 서브클래스로 WireMock(루프백)을 상대하는 그룹 —
 * 리다이렉트 미추적/응답 파싱/비밀 비유출을 검증한다. 가드 자체의 정확성은 (1)번 그룹과
 * {@code SsrfProtectionTest} 가 이미 검증하므로 여기서는 재확인하지 않는다.
 */
class OpencodeProbeServiceTest {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetWireMock() {
    wireMock.resetAll();
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  // ---------------------------------------------------------------------
  // 헬퍼
  // ---------------------------------------------------------------------

  /** 실제 SSRF 가드가 살아 있는 인스턴스 — 사설 대역/스킴/포트/DNS 판정 테스트 전용. */
  private OpencodeProbeService guardedService() {
    return new OpencodeProbeService(
        mock(AiCredentialService.class), new SsrfProtectionService(), WebClient.builder(), Duration.ofSeconds(10));
  }

  /**
   * DNS 해석 결과를 강제로 바꿔치기한 가드 인스턴스. 호스트명 문자열은 그대로 두고 "이 이름이
   * 가리키는 IP"만 조작해, DNS rebinding(해석 시점엔 사설 IP)을 재현한다.
   */
  private OpencodeProbeService guardedServiceWithFakeDns(InetAddress fakeAddress) {
    return new OpencodeProbeService(
        mock(AiCredentialService.class), new SsrfProtectionService(), WebClient.builder(), Duration.ofSeconds(10)) {
      @Override
      protected InetAddress[] resolve(String host) {
        return new InetAddress[] {fakeAddress};
      }
    };
  }

  /**
   * WireMock(루프백)을 상대하는 인스턴스 — {@code validateTarget()} 을 통째로 건너뛴다. 가드
   * 자체의 정확성은 {@link #guardedService()} 계열 테스트가 맡으므로, 여기서는 프로브의 응답
   * 파싱·리다이렉트 미추적·비밀 비유출 동작만 확인한다.
   */
  private OpencodeProbeService wireMockService(AiCredentialService aiCredentialService) {
    return wireMockService(aiCredentialService, Duration.ofSeconds(10));
  }

  private OpencodeProbeService wireMockService(AiCredentialService aiCredentialService, Duration timeout) {
    return new OpencodeProbeService(aiCredentialService, new SsrfProtectionService(), WebClient.builder(), timeout) {
      @Override
      protected void validateTarget(URI uri) {
        // no-op: WireMock 은 127.0.0.1 에서 뜬다 — 실제 가드라면 언제나 차단한다.
      }
    };
  }

  private String wireMockUrl(String path) {
    return "http://localhost:" + wireMock.port() + path;
  }

  /** {@code agentType}/payload/secret 을 가진 tenant_settings 원문을 만든다. */
  private String tenantDocJson(String agentType, String baseUrl, String apiKeyCipher) {
    AiCredentialDocument doc = AiCredentialDocument.empty(agentType);
    if (baseUrl != null) {
      doc.payload().put("baseURL", baseUrl);
      doc.payload().put("providerId", "openai");
    }
    if (apiKeyCipher != null) {
      doc.withSecret("apiKey", apiKeyCipher);
    }
    return doc.toJson();
  }

  /**
   * 실제 {@link AiCredentialService} 를 Mockito 로 저장소만 가짜로 채워 구성한다.
   * {@link OpencodeProbeService#tenantOpencodeCredential} 경로(=평면 교차 폴백 금지 로직)를 진짜
   * 구현으로 통과시켜 검증하기 위해서다 — {@code AiCredentialService} 자체를 통째로 mock 하면
   * "OpencodeProbeService 가 올바른 메서드를 부르는지"만 보고, 그 메서드 내부가 실제로 테넌트
   * 행만 읽는지는 확인하지 못한다.
   */
  private AiCredentialService realAiCredentialService(String tenantRawJson, String platformRawJson) {
    TenantSettingsRepository tenantRepo = mock(TenantSettingsRepository.class);
    SettingsRepository platformRepo = mock(SettingsRepository.class);
    EncryptionService encryption = mock(EncryptionService.class);
    when(tenantRepo.findValue(AiCredentialService.KEY)).thenReturn(Optional.ofNullable(tenantRawJson));
    when(platformRepo.getValue(AiCredentialService.KEY)).thenReturn(Optional.ofNullable(platformRawJson));
    // "enc:" 접두사를 벗기는 가짜 복호화 — 실제 AES 는 필요 없다(EncryptionService 자체의 정확성은
    // EncryptionServiceTest 가 검증한다). 이 테스트는 AiCredentialService 가 "어느 저장소에서"
    // 읽는지를 검증하는 것이지 암호화 알고리즘을 재검증하는 것이 아니다.
    when(encryption.decrypt(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).replaceFirst("^enc:", ""));
    return new AiCredentialService(platformRepo, tenantRepo, encryption);
  }

  // ---------------------------------------------------------------------
  // 1. SSRF 가드 — 사설 대역 / 스킴 / 포트 / DNS rebinding
  // ---------------------------------------------------------------------

  @Test
  void 사설_대역은_거부한다() {
    OpencodeProbeService service = guardedService();

    // brief 원문 4종 — ok() 는 넷 다 false 다.
    for (String url :
        List.of(
            "http://127.0.0.1/v1", "https://10.0.0.5/v1", "https://192.168.1.1/v1", "https://169.254.169.254/v1")) {
      assertThat(service.probe(url, "k").ok()).isFalse();
    }

    // ok()==false 만으로는 공허하다(가드를 지워도 연결이 실패해 여전히 false 가 된다) — 어떤
    // 가드가 막았는지 문구+reason 으로 구분한다.
    ProbeResultAssertHelper.assertBlocked(
        service.probe("http://127.0.0.1/v1", "k"), OpencodeProbeService.MSG_SCHEME, Reason.SCHEME_NOT_ALLOWED);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://10.0.0.5/v1", "k"), OpencodeProbeService.MSG_BLOCKED_ADDRESS, Reason.BLOCKED_ADDRESS);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://192.168.1.1/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://169.254.169.254/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  /**
   * 브리프의 {@code http://127.0.0.1/v1} 은 스킴 가드가 먼저 걸려 주소 판정 자체(사설/루프백 검사
   * 코드 경로)를 한 번도 안 거친다 — https 를 써서 IPv4 루프백에 대해 주소 판정 자체가 발동하는지를
   * 별도로 고정한다.
   */
  @Test
  void IPv4_루프백_https는_주소_판정에서_거부된다() {
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://127.0.0.1/v1", "k"), OpencodeProbeService.MSG_BLOCKED_ADDRESS, Reason.BLOCKED_ADDRESS);
  }

  @Test
  void http_는_거부한다() {
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("http://example.com/v1", "k"), OpencodeProbeService.MSG_SCHEME, Reason.SCHEME_NOT_ALLOWED);
  }

  @Test
  void 허용되지_않은_포트는_거부한다() {
    // 8.8.8.8 은 공인 IP(구글 DNS) — 주소 자체는 통과해야 하고, 포트만으로 막혀야 한다.
    // IP 리터럴이라 실제 DNS 조회 없이 즉시 파싱되므로 네트워크 접근이 없다.
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://8.8.8.8:9999/v1", "k"), OpencodeProbeService.MSG_PORT, Reason.PORT_NOT_ALLOWED);
  }

  @Test
  void IPv6_루프백도_거부한다() {
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://[::1]/v1", "k"), OpencodeProbeService.MSG_BLOCKED_ADDRESS, Reason.BLOCKED_ADDRESS);
  }

  /**
   * IPv4-매핑 IPv6({@code ::ffff:10.0.0.5}): JDK 가 이걸 {@code Inet4Address} 로 정규화해 돌려주므로
   * {@code isCgnat}/{@code isIpv6UniqueLocal} 의 "길이로 먼저 거른다" 방어가 실제로는 안 걸리고
   * 대신 {@code SsrfProtectionService} 의 IPv4 사설 대역 판정이 잡는다 — 이 정규화는 JDK 구현
   * 세부사항이라 미래 버전이 바꿀 수 있다. 지금 핀 박아두면 바뀌었을 때 조용한 구멍이 아니라
   * 빨간 테스트로 드러난다.
   */
  @Test
  void IPv4_매핑_IPv6도_거부한다() throws UnknownHostException {
    InetAddress mapped = InetAddress.getByName("::ffff:10.0.0.5");
    OpencodeProbeService service = guardedServiceWithFakeDns(mapped);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  /**
   * DNS rebinding: 호스트명은 평범해 보이지만("models.probe-test.invalid") 실제로 이 이름이
   * 가리키는 IP(해석 결과)는 사설 대역이다. "호스트명 문자열이 사설 대역처럼 안 보이면 통과"하는
   * 구현(문자열 매칭만 하고 DNS 해석 결과를 안 보는 변종)이면 이 테스트가 못 잡는다 — resolve() 를
   * 실제로 호출해 그 반환값을 검사해야만 통과한다.
   */
  @Test
  void DNS_해석_결과가_사설_대역이면_거부한다() throws UnknownHostException {
    InetAddress privateAddress = InetAddress.getByAddress(new byte[] {10, 0, 0, 5});
    OpencodeProbeService service = guardedServiceWithFakeDns(privateAddress);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  @Test
  void CGNAT_대역도_거부한다() throws UnknownHostException {
    InetAddress cgnat = InetAddress.getByAddress(new byte[] {100, 64, 0, 1}); // 100.64.0.0/10
    OpencodeProbeService service = guardedServiceWithFakeDns(cgnat);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  @Test
  void IPv6_ULA_대역도_거부한다() throws UnknownHostException {
    InetAddress ula =
        InetAddress.getByAddress(new byte[] {(byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}); // fc00::/7
    OpencodeProbeService service = guardedServiceWithFakeDns(ula);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  /**
   * 0.0.0.0/8("이 네트워크"): {@link InetAddress#isAnyLocalAddress()} 는 정확히 0.0.0.0 하나만
   * 참이라 0.0.0.1 은 기존 SsrfProtectionService 의 어떤 판정도 통과한다 — 리뷰에서 jshell 로
   * 실측 확인됐다. isReservedZeroNet() 보강이 없으면 이 테스트가 못 잡는다.
   */
  @Test
  void 예약된_0대역도_거부한다() throws UnknownHostException {
    InetAddress zeroNet = InetAddress.getByAddress(new byte[] {0, 0, 0, 1}); // 0.0.0.0/8, 0.0.0.0 자체가 아님
    OpencodeProbeService service = guardedServiceWithFakeDns(zeroNet);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_BLOCKED_ADDRESS,
        Reason.BLOCKED_ADDRESS);
  }

  /**
   * 가드의 "허용" 경로 자체를 확인한다 — 지금까지의 모든 가드 테스트는 "막혔다"만 단언했으므로,
   * {@code validateTarget()} 끝에 무조건 {@code throw} 를 붙여 전체 인터넷을 차단하는 뮤턴트도
   * 이론상 전부 통과할 수 있었다(실제로 리뷰가 이 뮤턴트를 넣어 확인했다). 공인 주소로 DNS 를
   * 위조해 가드를 통과시키고, 그 뒤 실제 접속은 `.invalid` 호스트라 실패하는 것으로 "가드는
   * 통과했다"를 증명한다 — reason 이 BLOCKED_ADDRESS 가 아니라 UNREACHABLE 이면 가드를 통과한
   * 것이다.
   */
  @Test
  void 공인_주소는_가드를_통과한다() throws UnknownHostException {
    InetAddress publicAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8}); // 구글 DNS, 공인
    OpencodeProbeService service = guardedServiceWithFakeDns(publicAddress);
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://models.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_UNREACHABLE,
        Reason.UNREACHABLE);
  }

  /** {@code buildModelsUri()} 가 스킴/포트/DNS 판정까지 가지도 못하고 자체적으로 막는 입력. */
  @Test
  void 호스트가_없는_URL은_형식_오류다() {
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://", "k"), OpencodeProbeService.MSG_INVALID_URL, Reason.INVALID_URL);
  }

  /**
   * {@code resolve()} 를 오버라이드하지 않은 <b>진짜</b> 리졸버로 존재하지 않는 호스트를 조회한다
   * — {@code .invalid} 는 RFC 2606 예약 TLD 라 절대 해석되지 않는다(오프라인이라도 로컬 리졸버가
   * NXDOMAIN/미해석으로 답한다, 네트워크 접근이 실제로 필요하지는 않다). {@code
   * reasonForGuardMessage()} 의 {@code UNRESOLVED_HOST} 분기는 이 테스트가 없으면 한 번도 실행되지
   * 않는 죽은 코드였다 — {@code BLOCKED_ADDRESS} 로 잘못 매핑해도 잡을 테스트가 없었다는 뜻이다.
   */
  @Test
  void 존재하지_않는_호스트는_해석_실패다() {
    OpencodeProbeService service = guardedService();
    ProbeResultAssertHelper.assertBlocked(
        service.probe("https://genuinely-does-not-exist.probe-test.invalid/v1", "k"),
        OpencodeProbeService.MSG_UNRESOLVED_HOST,
        Reason.UNRESOLVED_HOST);
  }

  // ---------------------------------------------------------------------
  // 1b. validateTargetOnly — 보안 리뷰 Fix1: apiKey 없이 저장할 때도 baseURL 을 검증한다
  // ---------------------------------------------------------------------

  /**
   * {@link OpencodeProbeService#validateTargetOnly} 는 {@link OpencodeProbeService#probe} 와 같은
   * 가드를 타지만 네트워크 호출을 하지 않는다 — apiKey 가 없어 프로브(HTTP GET)를 아예 못/안 부를
   * 때도 baseURL 의 SSRF 안전성만은 저장 시점에 확인해야 하기 때문이다(컨트롤러 Fix1). 사설 대역이
   * 실제로 막히는지, 공인 주소가 실제로 통과하는지(이 메서드가 "항상 실패" 로 퇴화하지 않았는지)
   * 둘 다 확인한다.
   */
  @Test
  void validateTargetOnly는_사설대역을_거부한다() {
    OpencodeProbeService service = guardedService();
    OpencodeProbeService.TargetCheck result = service.validateTargetOnly("https://169.254.169.254/v1");
    assertThat(result.ok()).isFalse();
    assertThat(result.failure().message()).isEqualTo(OpencodeProbeService.MSG_BLOCKED_ADDRESS);
    assertThat(result.failure().reason()).isEqualTo(Reason.BLOCKED_ADDRESS);
  }

  @Test
  void validateTargetOnly는_스킴을_거부한다() {
    OpencodeProbeService service = guardedService();
    OpencodeProbeService.TargetCheck result = service.validateTargetOnly("http://169.254.169.254/v1");
    assertThat(result.ok()).isFalse();
    assertThat(result.failure().message()).isEqualTo(OpencodeProbeService.MSG_SCHEME);
    assertThat(result.failure().reason()).isEqualTo(Reason.SCHEME_NOT_ALLOWED);
  }

  @Test
  void validateTargetOnly는_형식_오류를_INVALID_URL로_돌려준다() {
    OpencodeProbeService service = guardedService();
    OpencodeProbeService.TargetCheck result = service.validateTargetOnly("not-a-url");
    assertThat(result.ok()).isFalse();
    assertThat(result.failure().reason()).isEqualTo(Reason.INVALID_URL);
  }

  /**
   * 통과하는 경로도 확인한다 — 이 메서드가 "항상 실패"로 퇴화해도(뮤턴트) 위 두 테스트는 여전히
   * 통과하므로, 허용 경로를 보는 이 테스트가 없으면 그 퇴화를 못 잡는다(Task 6 가 겪은 것과 같은
   * 공허 패턴, Ruling #25 주변 기록 참고). IP 리터럴이라 실제 DNS 조회가 없다 — 네트워크 접근 없이
   * 결정적이다.
   */
  @Test
  void validateTargetOnly는_공인_주소를_통과시킨다() {
    OpencodeProbeService service = guardedService();
    OpencodeProbeService.TargetCheck result = service.validateTargetOnly("https://8.8.8.8/v1");
    assertThat(result.ok()).isTrue();
    // 통과했으면 실패 결과를 들고 있지 않다(가드 통과 = failure() 가 null).
    assertThat(result.failure()).isNull();
  }

  // ---------------------------------------------------------------------
  // 2. WireMock 그룹 — 리다이렉트 미추적 / 응답 파싱 / 비밀 비유출
  // ---------------------------------------------------------------------

  @Test
  void 리다이렉트를_따라가지_않는다() {
    // Location 이 WireMock 자신의 다른 stub(따라가면 성공할 것)을 가리키게 해 "정말 안 따라가는지"를
    // 확실히 가른다 — 도달 불가한 외부 도메인으로 리다이렉트시키면, 리다이렉트를 실제로 따라가도
    // (샌드박스에 인터넷이 없어) 연결이 실패해 ok()==false 가 되므로 뮤턴트(리다이렉트 허용)를
    // 못 잡는 공허한 테스트가 된다.
    wireMock.stubFor(get("/v1/models").willReturn(temporaryRedirect(wireMockUrl("/v1/models-if-followed"))));
    wireMock.stubFor(
        get("/v1/models-if-followed").willReturn(okJson("{\"data\":[{\"id\":\"should-not-be-seen\"}]}")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    ProbeResultAssertHelper.assertBlocked(
        service.probe(wireMockUrl("/v1"), "k"), OpencodeProbeService.MSG_REDIRECT, Reason.REDIRECT_BLOCKED);

    wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/models-if-followed")));
  }

  @Test
  void 성공하면_모델_ID_만_돌려준다() {
    wireMock.stubFor(
        get("/v1/models")
            .willReturn(okJson("{\"data\":[{\"id\":\"gpt-4o\",\"owner\":\"x\"},{\"id\":\"gpt-4o-mini\"}]}")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    var result = service.probe(wireMockUrl("/v1"), "k");
    assertThat(result.ok()).isTrue();
    assertThat(result.reason()).isEqualTo(Reason.OK);
    assertThat(result.models()).containsExactly("gpt-4o", "gpt-4o-mini");
  }

  @Test
  void 배열_직반환도_받는다() {
    wireMock.stubFor(get("/v1/models").willReturn(okJson("[{\"id\":\"m1\"}]")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    assertThat(service.probe(wireMockUrl("/v1"), "k").models()).containsExactly("m1");
  }

  @Test
  void 빈_목록도_성공이다() {
    // 설계서: 목록이 비면(공급자가 /models 를 제대로 안 준다) 화면이 자유 입력으로 전환된다 — 저장을
    // 막지 않는다. 프로브 레벨에서도 "빈 목록"은 실패가 아니라 성공+빈 배열이어야 한다.
    wireMock.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    var result = service.probe(wireMockUrl("/v1"), "k");
    assertThat(result.ok()).isTrue();
    assertThat(result.models()).isEmpty();
  }

  @Test
  void 해석할_수_없는_응답은_실패다() {
    wireMock.stubFor(get("/v1/models").willReturn(okJson("{\"unexpected\":\"shape\"}")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    ProbeResultAssertHelper.assertBlocked(
        service.probe(wireMockUrl("/v1"), "k"), OpencodeProbeService.MSG_PARSE, Reason.PARSE_ERROR);
  }

  @Test
  void 실패_메시지에_apiKey_와_upstream_본문이_없다() {
    wireMock.stubFor(get("/v1/models").willReturn(aResponse().withStatus(401).withBody("bad key sk-secret-123")));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    var r = service.probe(wireMockUrl("/v1"), "sk-secret-123");
    assertThat(r.ok()).isFalse();
    assertThat(r.reason()).isEqualTo(Reason.PROVIDER_REJECTED);
    assertThat(r.message()).doesNotContain("sk-secret-123").doesNotContain("bad key");
    // 상태 코드(숫자)는 진단에 남기되, 이유 문구(reason phrase)·본문은 담지 않는다는 설계 결정을
    // 명시적으로 고정한다.
    assertThat(r.message()).isEqualTo(OpencodeProbeService.MSG_PROVIDER_REJECTED + " (status=401)");
  }

  @Test
  void 타임아웃은_구분된_메시지를_낸다() {
    wireMock.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}").withFixedDelay(500)));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class), Duration.ofMillis(100));
    ProbeResultAssertHelper.assertBlocked(
        service.probe(wireMockUrl("/v1"), "k"), OpencodeProbeService.MSG_TIMEOUT, Reason.TIMEOUT);
  }

  /**
   * 502 행: 접속 자체가 거부되는 경우. 타임아웃(위 테스트)과 헷갈리지 않는지 — 둘 다 offline 으로
   * 재현 가능하고, 뮤턴트로 두 메시지/이유를 맞바꾸면 이 테스트와 타임아웃 테스트가 동시에 RED가
   * 되어야 한다(진짜로 구분되는지 확인, 아래 "뮤테이션 검사" 참고).
   */
  @Test
  void 연결이_거부되면_unreachable_이다() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    // 소켓을 닫은 직후라 그 포트는 즉시 connection refused 를 반환한다 — validateTarget() 을
    // 건너뛰는 wireMockService 그룹을 써서(로컬호스트라 실제 가드라면 막힌다) 진짜 doRequest() 의
    // 예외 매핑만 재현한다.
    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    ProbeResultAssertHelper.assertBlocked(
        service.probe("http://localhost:" + closedPort + "/v1", "k"),
        OpencodeProbeService.MSG_UNREACHABLE,
        Reason.UNREACHABLE);
  }

  /**
   * 응답 크기 상한(1MB, {@code OpencodeProbeService.MAX_RESPONSE_BYTES})을 넘는 본문 — WebClient 의
   * {@code ExchangeStrategies} 코덱 한도가 {@link org.springframework.core.io.buffer.DataBufferLimitException}
   * 을 던지고, 그걸 {@code MSG_TOO_LARGE}/{@code Reason.TOO_LARGE} 로 잡아야 한다. 유효한 JSON 일
   * 필요가 없다 — 크기 초과는 파싱 전에 버퍼링 단계에서 걸린다.
   */
  @Test
  void 응답이_너무_크면_too_large_다() {
    String hugeBody = "a".repeat(2_000_000); // 상한(1_000_000) 의 두 배 — 여유 있게 초과시킨다
    wireMock.stubFor(get("/v1/models").willReturn(aResponse().withStatus(200).withBody(hugeBody)));

    OpencodeProbeService service = wireMockService(mock(AiCredentialService.class));
    ProbeResultAssertHelper.assertBlocked(
        service.probe(wireMockUrl("/v1"), "k"), OpencodeProbeService.MSG_TOO_LARGE, Reason.TOO_LARGE);
  }

  // ---------------------------------------------------------------------
  // 3. 평면 교차 폴백 금지 — apiKey 생략 시 테넌트 행만 본다
  // ---------------------------------------------------------------------

  @Test
  void apiKey_생략시_테넌트_행이_없으면_플랫폼_키로_새지_않고_400이다() {
    // 테넌트 행 없음 + 플랫폼 행에는 키가 있다. 뮤턴트(플랫폼으로 폴백)가 들어가면 이 테스트가
    // "성공"으로 뒤집히거나(플랫폼 키를 실어 보냄) 최소한 메시지가 달라진다.
    TenantContext.set(1L);
    AiCredentialService cred = realAiCredentialService(null, tenantDocJson("opencode", wireMockUrl("/v1"), "enc:platform-secret"));
    wireMock.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

    OpencodeProbeService service = wireMockService(cred);
    assertThatThrownBy(() -> service.probe(wireMockUrl("/v1"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OpencodeProbeService.MSG_NO_STORED_KEY);

    // 플랫폼 키를 들고 실제로 요청을 보내지 않았다는 것까지 확인한다(메시지만 보면 우연히 같은
    // 문구를 내면서 실제로는 요청을 보내는 구현도 통과해 버릴 수 있다).
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void apiKey_생략시_테넌트_행의_키를_쓴다() {
    TenantContext.set(1L);
    String tenantBaseUrl = wireMockUrl("/v1");
    AiCredentialService cred = realAiCredentialService(tenantDocJson("opencode", tenantBaseUrl, "enc:tenant-secret"), null);

    // Authorization 헤더가 정확히 테넌트의 복호화된 키여야만 매치되는 stub — 폴백이 빈 값/다른
    // 값을 쓰는 뮤턴트라면 매치가 안 돼 WireMock 기본 404 가 나서 ok()==false 로 드러난다.
    wireMock.stubFor(
        get("/v1/models")
            .withHeader("Authorization", equalTo("Bearer tenant-secret"))
            .willReturn(okJson("{\"data\":[{\"id\":\"m1\"}]}")));

    OpencodeProbeService service = wireMockService(cred);
    var result = service.probe(tenantBaseUrl, null);
    assertThat(result.ok()).isTrue();
    assertThat(result.models()).containsExactly("m1");
  }

  @Test
  void apiKey_생략시_baseUrl이_저장된_값과_다르면_400이다() {
    TenantContext.set(1L);
    AiCredentialService cred =
        realAiCredentialService(tenantDocJson("opencode", wireMockUrl("/v1-stored"), "enc:tenant-secret"), null);

    OpencodeProbeService service = wireMockService(cred);
    assertThatThrownBy(() -> service.probe(wireMockUrl("/v1-different"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OpencodeProbeService.MSG_BASE_URL_MISMATCH);
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /**
   * baseUrl 자체가 생략(null)된 요청 — 화면/컨트롤러가 필드를 빼먹고 그대로 넘기면 생길 수 있다.
   * {@code UrlUtils.normalizeBaseUrl(null)} 은 {@code null} 을 그대로 돌려준다 — equals() 의
   * 수신자를 요청 쪽(null 일 수 있는 쪽)에 두면 NPE 가 나 400 대신 500 이 된다. 저장된 값(never
   * null)을 수신자로 둬야 "다르다"로 자연스럽게 떨어진다.
   */
  @Test
  void baseUrl이_null이고_apiKey도_생략이면_NPE_없이_400이다() {
    TenantContext.set(1L);
    AiCredentialService cred =
        realAiCredentialService(tenantDocJson("opencode", wireMockUrl("/v1"), "enc:tenant-secret"), null);

    OpencodeProbeService service = wireMockService(cred);
    // (String) 캐스트 — probe 에 String/TargetCheck 두 오버로드가 있어 생 null 은 모호하다.
    assertThatThrownBy(() -> service.probe((String) null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OpencodeProbeService.MSG_BASE_URL_MISMATCH);
  }

  @Test
  void apiKey_생략시_baseUrl_끝_슬래시_차이는_허용한다() {
    TenantContext.set(1L);
    String stored = wireMockUrl("/v1");
    AiCredentialService cred = realAiCredentialService(tenantDocJson("opencode", stored, "enc:tenant-secret"), null);
    wireMock.stubFor(
        get("/v1/models")
            .withHeader("Authorization", equalTo("Bearer tenant-secret"))
            .willReturn(okJson("{\"data\":[]}")));

    OpencodeProbeService service = wireMockService(cred);
    // 끝에 "/" 하나만 다르다 — UrlUtils.normalizeBaseUrl 이 이 차이만 흡수한다("그 이상의 정교한
    // 정규화는 하지 않는다"는 설계 결정을 고정한다).
    var result = service.probe(stored + "/", null);
    assertThat(result.ok()).isTrue();
  }

  /**
   * {@code sdk} 도 {@code apiKey} 라는 이름의 secret 필드를 갖는다(Anthropic 키). 테넌트가
   * opencode 가 아니라 sdk 로 재정의돼 있으면, 이름이 같다는 이유로 그 Anthropic 키를 재사용해서는
   * 안 된다 — agentType 필터가 없는 변종이면 이 테스트가 (재사용해 성공하거나, 최소한 다른
   * 예외/메시지로) 잡는다.
   */
  @Test
  void 테넌트가_sdk_유형이면_그_apiKey를_재사용하지_않는다() {
    TenantContext.set(1L);
    AiCredentialDocument sdkDoc = AiCredentialDocument.empty("sdk");
    sdkDoc.withSecret("apiKey", "enc:anthropic-secret");
    AiCredentialService cred = realAiCredentialService(sdkDoc.toJson(), null);

    OpencodeProbeService service = wireMockService(cred);
    assertThatThrownBy(() -> service.probe(wireMockUrl("/v1"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OpencodeProbeService.MSG_NO_STORED_KEY);
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void apiKey가_주어지면_테넌트_행을_보지_않는다() {
    // apiKey 가 요청에 있으면 저장된 값을 볼 필요가 없다 — AiCredentialService 를 아예 건드리지
    // 않는다는 것을 mock 으로 확인한다(테넌트 컨텍스트조차 없어도 동작해야 한다).
    AiCredentialService cred = mock(AiCredentialService.class);
    wireMock.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

    OpencodeProbeService service = wireMockService(cred);
    var result = service.probe(wireMockUrl("/v1"), "explicit-key");
    assertThat(result.ok()).isTrue();
    verifyNoInteractions(cred);
  }

  /**
   * {@code ProbeResult} 의 {@code ok() ⇔ reason==OK} 불변식(리뷰 라운드 1, "세 가지 작은 것들"
   * 항목). 이 record 를 소비하는 두 호출부({@code OpencodeCredentialValidation.statusFor} 의
   * 양쪽 컨트롤러)는 전부 {@code !result.ok()} 로 먼저 걸러낸 뒤에만 {@code statusFor(reason())}
   * 를 부른다 — record 자체가 이 조합을 막지 않으면, 실수로 "ok=false 인데 reason=OK"(또는 그
   * 반대) 를 만드는 생성자 호출이 향후 생겨도 컴파일도 기존 테스트도 통과한 채로
   * {@code statusFor(OK)} 가 실제로 호출돼 500 이 된다. 생성 시점에 막아 그 사고를 record 생성
   * 즉시 드러나게 한다.
   */
  @Test
  void ProbeResult은_ok와_reason이_어긋나면_생성_시점에_거부된다() {
    assertThatThrownBy(() -> new ProbeResult(false, List.of(), "x", Reason.OK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProbeResult(true, List.of(), null, Reason.UNREACHABLE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 짧은 단언 헬퍼 — ok()/message()/reason() 을 한 번에 확인한다. message 뿐 아니라 reason 도 함께
   * 요구한다 — 프로그램이 실패 종류를 분기할 통로는 reason 이지 message(사람이 읽는 한국어 문구)가
   * 아니라는 설계 결정을, 테스트가 message 만 확인하고 지나가지 않도록 강제한다.
   */
  private static final class ProbeResultAssertHelper {
    static void assertBlocked(ProbeResult result, String expectedMessage, Reason expectedReason) {
      assertThat(result.ok()).isFalse();
      assertThat(result.message()).isEqualTo(expectedMessage);
      assertThat(result.reason()).isEqualTo(expectedReason);
    }
  }
}
