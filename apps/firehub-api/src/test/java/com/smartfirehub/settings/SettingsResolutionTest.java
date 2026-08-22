package com.smartfirehub.settings;

import static com.smartfirehub.support.SettingsTestSupport.deleteSystemSetting;
import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.restoreSystemSettingValue;
import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 해석 규칙 테스트(Task 4). {@code SettingsService.getValue}/{@code getAsMap} 가 {@code
 * tenant_settings} 오버라이드 → {@code system_settings} 폴백을 올바르게 해석하는지 검증한다.
 *
 * <p><b>픽스처와 검증 대상의 트랜잭션 취급이 다르다.</b> 오버라이드 행을 만드는 {@code
 * tenantSettingsRepository.upsert} 는 {@code runInTenantTransaction(transactionTemplate, tenantId,
 * ...)} 으로 감싸야 GUC 가 주입되고 정책의 WITH CHECK 를 통과한다. 반대로 검증 대상인 {@code
 * settingsService.getValue} 는 자기 {@code @Transactional} 로 트랜잭션을 열고 그 시점의 {@code
 * TenantContext} 로 GUC 를 받으므로 <b>감싸지 않고 그대로 부른다</b> — 감싸면 실제 배선을 우회해
 * 버린다. 픽스처를 감싸지 않으면 0행이 들어가 "폴백이 잘 되네" 하며 통과하는, 정확히 검증하려던
 * 것을 놓치는 테스트가 된다.
 */
class SettingsResolutionTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @AfterEach
  void cleanup() {
    if (testTenant != null) {
      deleteTenants(dsl, testTenant);
      testTenant = null;
    }
    // IntegrationTestBase#clearTenantContext 가 뒤이어 돌지만, 일부 테스트가 명시적으로 컨텍스트를
    // 지우거나 다른 테넌트로 바꾸므로 여기서도 원복해 다음 테스트에 영향이 새지 않게 한다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void 오버라이드가_있으면_테넌트_값을_돌려준다() {
    testTenant = createActiveTenant(dsl, "sr-override");
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.model", "tenant-model", null));

    // 검증 대상은 트랜잭션으로 감싸지 않고 그대로 부른다 — 자기 @Transactional 이 이 시점의
    // TenantContext 로 GUC 를 받는다.
    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains("tenant-model");
  }

  @Test
  void 오버라이드가_없으면_플랫폼_값으로_폴백한다() {
    testTenant = createActiveTenant(dsl, "sr-fallback");
    // 오버라이드 행을 만들지 않은 채로 조회한다.
    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
  }

  @Test
  void 플랫폼_잠금_키는_오버라이드_행이_있어도_무시한다() {
    // 방어적 계약. 화이트리스트가 나중에 좁아지거나 누가 직접 SQL 로 행을 넣어도, 읽기는
    // 화이트리스트를 다시 확인하므로 잠긴 키가 테넌트 값으로 해석되지 않는다.
    testTenant = createActiveTenant(dsl, "sr-locked");
    // ai.api_key 는 SettingsOverridePolicy 화이트리스트에 없는 플랫폼 잠금 키다. 정상 upsert 경로로는
    // 만들 수 없는 상태(직접 SQL 로 밀어넣은 것)를 재현하기 위해 저장소 자체가 아니라 직접 DML 을 쓴다.
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () ->
            dsl.execute(
                "insert into tenant_settings (tenant_id, key, value) values (?, 'ai.api_key', 'sneaky-key')",
                testTenant));

    TenantContext.set(testTenant);
    // 플랫폼 값(비어있거나 seed 값)으로 폴백해야 한다 — 절대 'sneaky-key' 가 아니다.
    assertThat(settingsService.getValue("ai.api_key")).isNotEqualTo(java.util.Optional.of("sneaky-key"));
  }

  @Test
  void 컨텍스트가_없으면_예외가_아니라_플랫폼_값이다() {
    // 이 단언이 이 밴드의 핵심 회귀 가드다. TenantContext 없이 getValue 를 부르는 것은 JobRunr
    // @Job·@Async·@Scheduled 배경 경로에서 정상적으로 일어난다. require() 를 쓰면 임베딩 백필과
    // 문서 인제스션이 영구 무동작이 된다(P3-a·P2-g 전례).
    TenantContext.clear();
    assertThat(settingsService.getValue("ai.model")).isPresent();
    assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
  }

  @Test
  void getAsMap_은_오버라이드를_덮어쓴_결과를_준다() {
    // AI 채팅·프로액티브 잡이 실제로 읽는 경로다. getValue 만 고치고 getAsMap 을 빠뜨리면 화면에는
    // 오버라이드가 보이지만 실제 호출은 플랫폼 값으로 나간다.
    testTenant = createActiveTenant(dsl, "sr-asmap");
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.model", "tenant-model", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getAsMap("ai");
    assertThat(resolved).containsEntry("ai.model", "tenant-model");
    // 오버라이드 대상이 아닌 다른 ai.* 키는 여전히 플랫폼 값이어야 한다.
    assertThat(resolved).containsKey("ai.max_turns");
  }

  @Test
  void getAsMap_은_플랫폼_행이_없는_오버라이드_전용_키도_보여준다() {
    // Fix round 1: ai.session_max_tokens 는 어떤 마이그레이션도 system_settings 에 시드하지 않았다
    // (V15/V31/V40/V41 이 다른 ai.* 키는 시드해도 이 키는 빠져 있다). 화이트리스트에는 있으므로
    // 테넌트가 오버라이드할 수 있는데, platform.keySet() 만 오버라이드 조회에 넘기던 이전 구현은
    // 플랫폼 행이 없는 이 키의 오버라이드를 절대 드러내지 못했다(교집합). 합집합이어야 통과한다.
    testTenant = createActiveTenant(dsl, "sr-asmap-only");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.session_max_tokens", "12345", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getAsMap("ai");
    assertThat(resolved).containsEntry("ai.session_max_tokens", "12345");
  }

  @Test
  void getResolvedByPrefix_은_플랫폼_행이_없는_오버라이드_전용_키를_overridden으로_보여준다() {
    testTenant = createActiveTenant(dsl, "sr-resolved-only");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.session_max_tokens", "54321", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getResolvedByPrefix("ai");
    var entry =
        resolved.stream()
            .filter(r -> r.key().equals("ai.session_max_tokens"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ai.session_max_tokens 가 목록에 없다 — 합집합이 아니라 교집합이다"));

    assertThat(entry.value()).isEqualTo("54321");
    assertThat(entry.overridden()).isTrue();
    assertThat(entry.tenantEditable()).isTrue();
  }

  @Test
  void 프리픽스_경로도_잠긴_키_오버라이드는_무시한다() {
    // 단일 키 조회(findValue)에서 프리픽스 조회(findByPrefix)로 바꾸면서 화이트리스트 필터가
    // 새어나가면 안 된다 — 합집합으로 바뀐 것이 "잠긴 키까지 전부 노출"로 변질되지 않았는지 확인.
    testTenant = createActiveTenant(dsl, "sr-locked-prefix");
    // 저장소는 화이트리스트를 모른다(서비스만 안다) — upsert 로 직접 잠긴 키를 심어 재현한다.
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.api_key", "sneaky-key", null));
    try {
      TenantContext.set(testTenant);

      var asMap = settingsService.getAsMap("ai");
      assertThat(asMap.get("ai.api_key")).isNotEqualTo("sneaky-key");

      var resolved = settingsService.getResolvedByPrefix("ai");
      var apiKeyEntry =
          resolved.stream().filter(r -> r.key().equals("ai.api_key")).findFirst().orElseThrow();
      assertThat(apiKeyEntry.overridden()).isFalse();
      assertThat(apiKeyEntry.value()).isNotEqualTo("sneaky-key");
    } finally {
      // upsert 로 심은 오버라이드 행을 명시적으로 정리한다(deleteTenants 가 tenant_settings 를
      // cascade 로 지우긴 하지만, 여기서 직접 지워 이 테스트의 의도를 코드로 남긴다).
      runInTenantTransaction(
          transactionTemplate, testTenant, () -> tenantSettingsRepository.delete("ai.api_key"));
    }
  }

  @Test
  void 다른_테넌트로_전환하면_각자의_오버라이드만_보인다() {
    // 세 상태 전이가 실제로 컨텍스트에 반응하는지 — 캐싱/고정 등으로 값이 굳어 있지 않은지 확인한다.
    long tenantA = createActiveTenant(dsl, "sr-a");
    long tenantB = createActiveTenant(dsl, "sr-b");
    try {
      runInTenantTransaction(
          transactionTemplate, tenantA, () -> tenantSettingsRepository.upsert("ai.model", "model-a", null));
      // tenantB 는 오버라이드를 만들지 않는다 — 플랫폼 값이어야 한다.

      TenantContext.set(tenantA);
      assertThat(settingsService.getValue("ai.model")).contains("model-a");

      TenantContext.set(tenantB);
      assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
    } finally {
      deleteTenants(dsl, tenantA, tenantB);
    }
  }

  /**
   * {@code getResolvedByPrefix} 도 비밀 키를 마스킹한다.
   *
   * <p>이 경로는 web 설정 화면이 실제로 부르는 경로다({@code GET /api/v1/settings?prefix=ai}).
   * 마스킹을 빠뜨리면 {@code ai.api_key} 의 <b>AES 암호문이 그대로</b> 응답에 실린다. 이 프로젝트는
   * 정확히 그 사고를 이미 한 번 냈다 — SMTP 전용 읽기 메서드만 마스킹하고 {@code getAll} 은
   * 빠뜨려서 암호문이 나갔다. 읽기 경로를 새로 만들 때마다 같은 실수가 가능하므로 경로별로 단언한다.
   *
   * <p><b>여기서는</b> 오버라이드 값 쪽을 검사하지 않는다. 예전에는 그 근거가 "비밀 키는 전부
   * 플랫폼 잠금이라 오버라이드 행으로 존재할 수 없다"였고, 그 사실이 곧 "이 누락이 어떤 기존
   * 테스트에도 걸리지 않은" 이유였다. <b>P7-c1 이 그 전제를 죽였다</b> — {@code smtp.*} 6키가
   * 테넌트 오버라이드로 열리면서 {@code SECRET_KEYS} 의 원소인 {@code smtp.password} 가 실제로
   * 오버라이드 행으로 존재한다. 그 경로의 마스킹은
   * {@code SettingsWritePlaneTest.테넌트_SMTP_비밀번호는_암호화_저장되고_마스킹되어_읽힌다} 가
   * 조건 없이 단언한다(평문도 암호문도 아님까지). 이 문단을 "존재할 수 없다"로 되돌리지 마라 —
   * 노출 안전성에 대한 거짓 안심이 되고, 그것을 믿고 어떤 읽기 경로의 오버라이드 마스킹을
   * 생략하면 테넌트 비밀번호 암호문이 나간다.
   *
   * <p><b>먼저 진짜 키를 저장한다.</b> 테스트 DB 의 {@code ai.api_key} 시드 값은 빈 문자열이라,
   * 값이 있을 때만 단언하는 형태로 두면 <b>단언이 한 줄도 실행되지 않는</b> 공허한 테스트가 된다
   * (실제로 그렇게 쓰여 있었다). 그 상태에서는 {@code getResolvedByPrefix} 의 {@code maskSecret}
   * 을 통째로 지워도 이 테스트가 녹색으로 남는다 — 즉 막으려던 유출을 전혀 막지 못한다.
   * 조건부 가드 대신 값을 만들어 두고 <b>무조건</b> 단언한다.
   *
   * <p>공유 테스트 DB 이므로 원래 값을 저장했다가 {@code finally} 에서 그대로 되돌린다.
   */
  @Test
  void getResolvedByPrefix_는_비밀_키를_마스킹한다() {
    String original = rawSystemSettingValue(dsl, "ai.api_key");
    try {
      // 평문을 넣으면 서비스가 암호화해 저장한다 — 마스킹이 없으면 이 암호문이 그대로 응답에 실린다.
      settingsService.updatePlatformSettings(java.util.Map.of("ai.api_key", "sk-real-secret"), null);
      // 전제 확인: 저장된 원문이 실제로 암호문("iv:ciphertext")이어야 이 테스트가 의미를 갖는다.
      assertThat(rawSystemSettingValue(dsl, "ai.api_key")).contains(":");

      var apiKey =
          settingsService.getResolvedByPrefix("ai").stream()
              .filter(s -> "ai.api_key".equals(s.key()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("ai.api_key 가 프리픽스 조회 결과에 없다"));

      assertThat(apiKey.value()).startsWith("****");
      // 암호문은 "iv:ciphertext" 형태이므로 콜론이 없다는 것이 곧 암호문이 아니라는 뜻이다.
      assertThat(apiKey.value()).doesNotContain(":");
      assertThat(apiKey.value()).doesNotContain("sk-real-secret");
    } finally {
      restoreSystemSettingValue(dsl, "ai.api_key", original);
    }
  }


  /**
   * 프리픽스에 마침표를 붙이면 아무것도 매칭하지 않는다 — {@code ProactiveJobAsyncRunner} 가 빠졌던
   * 함정의 회귀 가드(Task 8).
   *
   * <p>{@code findByPrefix} 가 스스로 {@code prefix + ".%"} 를 만들기 때문에 {@code "ai."} 는
   * {@code "ai..%"} 가 되어 <b>예외 없이 빈 맵</b>을 돌려준다. 조용한 빈 결과는 호출부에서 폴백
   * 기본값으로 흡수되므로(그 잡은 {@code agent_type} 을 항상 {@code "sdk"} 로 읽었다) 로그에도
   * 흔적이 남지 않는다. 두 형태를 같은 테스트에서 대조해 두면, 누군가 다시 마침표를 붙였을 때
   * "왜 설정이 안 먹지"를 런타임에서 추적하지 않아도 된다.
   */
  @Test
  void 프리픽스에_마침표를_붙이면_조용히_빈_맵이_된다() {
    assertThat(settingsService.getAsMap("ai"))
        .as("마침표 없는 형태가 올바르다")
        .containsKey("ai.agent_type");

    assertThat(settingsService.getAsMap("ai."))
        .as("마침표를 붙이면 ai..%% 패턴이 되어 0행 — 이 형태를 쓰면 안 된다")
        .isEmpty();
  }

  /**
   * 테넌트가 저장한 SMTP 설정이 <b>실제 발송 경로</b>에 반영된다.
   *
   * <p>이 단언이 없으면 "저장은 성공하고 화면도 재정의됐다고 보여주는데 메일만 플랫폼 SMTP 로
   * 나가는" 상태를 아무도 못 본다 — 저장·표시·동작 셋 중 둘만 맞는 무동작이다.
   * {@code getSmtpConfig} 는 발송 3경로(알림 채널·프로액티브 리포트·연결 테스트)가 공유하는
   * 유일한 설정 진입점이라, 여기 한 곳이 곧 실제 동작이다.
   *
   * <p><b>복호화까지 단언한다.</b> Task 2 가 테넌트 오버라이드 {@code smtp.password} 도 암호화해
   * 저장하게 만들었으므로, 해석기만 태우고 복호화 자리를 안 옮기면 발송이 <b>암호문으로 인증을
   * 시도</b>해 조용히 실패한다. 그래서 픽스처를 {@code tenantSettingsRepository.upsert}(평문)가
   * 아니라 실제 쓰기 경로인 {@code updateSettings} 로 만들고, 저장된 원문이 정말 암호문인지를
   * 먼저 확인한다 — 그 전제 단언이 없으면 평문 저장으로 퇴행해도 이 테스트가 녹색으로 남는다.
   *
   * <p><b>마지막 단언은 Task 5 에서 뒤집혔다.</b> 예전에는 "오버라이드하지 않은 키는 플랫폼 값
   * 그대로 — 부분 오버라이드가 나머지를 지우지 않는다"며 {@code smtp.port == platformPort} 를
   * 단언했다. <b>그 문장이 곧 밴드 리뷰가 찾은 보안 결함이었다</b>: 테넌트가 지정한 호스트에
   * 플랫폼 공용 자격증명이 붙는다. 이제 연결 5키는 원자적으로 해석되므로 행이 없는 키는 플랫폼
   * 값이 아니라 빈 값이다. 단언을 지우지 않고 <b>반대로</b> 남긴다 — 규칙이 되돌아가면 여기서 걸린다.
   */
  @Test
  void 테넌트_SMTP_오버라이드가_발송_설정에_반영된다() {
    testTenant = createActiveTenant(dsl, "sr-smtp");
    // 전제: 플랫폼 smtp.port 시드가 비어 있지 않아야 "번들이 플랫폼 값을 쓰지 않는다"는 단언이
    // 공허해지지 않는다(빈 문자열끼리 비교하면 무엇도 증명하지 못한다).
    String platformPort = rawSystemSettingValue(dsl, "smtp.port");
    assertThat(platformPort).as("플랫폼 smtp.port 시드가 있어야 이 테스트가 의미를 갖는다").isNotBlank();

    // 검증 대상과 마찬가지로 감싸지 않고 부른다 — updateSettings 자신의 @Transactional 이
    // 이 시점의 TenantContext 로 GUC 를 받는다.
    TenantContext.set(testTenant);
    settingsService.updateSettings(
        java.util.Map.of("smtp.host", "tenant-smtp.example.com", "smtp.password", "tenant-pw"),
        null);

    // 전제 확인: tenant_settings 에 저장된 원문이 암호문("iv:ciphertext")이어야 한다.
    assertThat(rawTenantSettingValue("smtp.password"))
        .as("오버라이드 smtp.password 가 암호화 저장되지 않았다 — 복호화 단언이 무의미해진다")
        .contains(":");

    var config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.host", "tenant-smtp.example.com");
    // 발송용 읽기는 복호화된 평문이어야 한다(마스킹도 암호문도 아니다).
    assertThat(config).containsEntry("smtp.password", "tenant-pw");
    // 연결 번들: 행이 없는 연결 키는 플랫폼 값(587)이 아니라 빈 값이다.
    assertThat(config)
        .as("행 없는 연결 키가 플랫폼 값으로 채워지면 테넌트 호스트에 플랫폼 접속 정보가 붙는다")
        .containsEntry("smtp.port", "");
  }

  /**
   * <b>연결 번들 규칙의 핵심 보안 단언</b>: {@code smtp.host} 하나만 재정의해도 플랫폼
   * {@code smtp.password} 가 발송 설정에 <b>실리지 않는다</b>.
   *
   * <p>이것이 밴드 리뷰가 찾은 MUST-FIX 다. 키 단위 상속에서는 테넌트 ADMIN 이 호스트 한 필드만
   * 자기 서버로 바꿔 저장하면, {@code getSmtpConfig} 가 그 호스트 + <b>플랫폼 공용 계정의 평문
   * 비밀번호</b>를 조합해 내보내고 발송(또는 연결 테스트 한 번)이 그 자격증명을 남의 서버로
   * 넘겨준다. 성공/실패까지 응답으로 확인된다.
   *
   * <p><b>플랫폼 비밀번호를 먼저 진짜로 저장한다.</b> {@code system_settings} 의 SMTP 시드는 V42
   * 기준 {@code ''} 라(port·starttls 만 예외), 그대로 두고 "빈 값이다"를 단언하면 <b>빈 문자열끼리
   * 비교하는 공허한 테스트</b>가 된다 — 번들 규칙을 통째로 지워도 녹색으로 남는다. 공유 test DB
   * 이므로 {@code finally} 에서 원본으로 되돌린다.
   */
  @Test
  void 호스트만_재정의해도_플랫폼_SMTP_자격증명은_발송에_실리지_않는다() {
    String originalPassword = rawSystemSettingValue(dsl, "smtp.password");
    String originalUsername = rawSystemSettingValue(dsl, "smtp.username");
    try {
      // 플랫폼 평면에 진짜 자격증명을 심는다(updatePlatformSettings 가 비밀번호를 암호화 저장한다).
      settingsService.updatePlatformSettings(
          java.util.Map.of(
              "smtp.password", "platform-shared-password",
              "smtp.username", "platform-shared-user"),
          null);
      // 전제 확인: 플랫폼 값이 실제로 non-blank 여야 아래 "빈 값" 단언이 의미를 갖는다.
      assertThat(rawSystemSettingValue(dsl, "smtp.password")).isNotBlank();
      assertThat(rawSystemSettingValue(dsl, "smtp.username")).isEqualTo("platform-shared-user");

      testTenant = createActiveTenant(dsl, "sr-smtp-bundle");
      TenantContext.set(testTenant);
      // 테넌트는 **호스트 한 필드만** 자기가 통제하는 서버로 바꾼다.
      settingsService.updateSettings(
          java.util.Map.of("smtp.host", "attacker-controlled.example.com"), null);

      var config = settingsService.getSmtpConfig();
      assertThat(config).containsEntry("smtp.host", "attacker-controlled.example.com");
      // (1) 비밀번호: 플랫폼 공용 비밀번호가 아니라 빈 값이다.
      assertThat(config)
          .as("플랫폼 공용 SMTP 비밀번호가 테넌트가 지정한 호스트로 나간다")
          .containsEntry("smtp.password", "");
      // (2) 사용자 이름: 빈 값이어야 SMTP AUTH 자체가 시도되지 않는다
      //     (SettingsController.testSmtpSettings 가 username 유무로 mail.smtp.auth 를 정한다).
      assertThat(config)
          .as("사용자 이름이 남아 있으면 mail.smtp.auth=true 로 인증이 시도된다")
          .containsEntry("smtp.username", "");
    } finally {
      restoreSystemSettingValue(dsl, "smtp.password", originalPassword);
      restoreSystemSettingValue(dsl, "smtp.username", originalUsername);
    }
  }

  /**
   * {@code smtp.from_address} 는 <b>번들이 아니다</b> — 연결 5키를 재정의해도 발신자 주소는 여전히
   * 플랫폼 값으로 해석된다.
   *
   * <p>접속과 무관한 표시 값이라 자격증명 묶음에 속하지 않는다. 6키를 통째로 묶으면 "발신자 주소만
   * 바꾸고 싶다"는 정당한 사용이 5키 전체 재정의를 강요당한다 — 밴드가 명시적으로 기각한 대안이다.
   *
   * <p>플랫폼 {@code smtp.from_address} 시드는 {@code ''} 라 먼저 진짜 값을 심는다. 안 그러면
   * "빈 값 == 빈 값" 이 되어 번들이 과잉 발동해도 통과한다.
   */
  @Test
  void 발신자_주소는_연결_번들에_휩쓸리지_않는다() {
    String originalFrom = rawSystemSettingValue(dsl, "smtp.from_address");
    try {
      settingsService.updatePlatformSettings(
          java.util.Map.of("smtp.from_address", "platform-noreply@example.com"), null);
      assertThat(rawSystemSettingValue(dsl, "smtp.from_address")).isNotBlank();

      testTenant = createActiveTenant(dsl, "sr-smtp-from");
      TenantContext.set(testTenant);
      settingsService.updateSettings(java.util.Map.of("smtp.host", "tenant-relay.example.com"), null);

      assertThat(settingsService.getSmtpConfig())
          .as("from_address 가 번들에 끌려 들어가면 발신자 주소만 바꾸는 정당한 사용이 깨진다")
          .containsEntry("smtp.from_address", "platform-noreply@example.com");
    } finally {
      restoreSystemSettingValue(dsl, "smtp.from_address", originalFrom);
    }
  }

  /**
   * 번들 규칙이 <b>과잉 발동하지 않는다</b>: 연결 5키를 하나도 재정의하지 않고
   * {@code smtp.from_address} 만 재정의하면 5키는 전부 플랫폼 값 그대로다.
   *
   * <p>이 단언이 없으면 "SMTP 프리픽스에 오버라이드 행이 하나라도 있으면 5키를 비운다"는 잘못된
   * 구현도 위 두 테스트를 통과한다 — 그리고 그 구현은 발신자 주소만 바꾼 테넌트의 <b>메일 발송을
   * 통째로 멈춘다</b>.
   *
   * <p>플랫폼 host/username 시드가 {@code ''} 라 여기서도 먼저 진짜 값을 심는다.
   */
  @Test
  void 발신자_주소만_재정의하면_연결_5키는_플랫폼_값_그대로다() {
    String originalHost = rawSystemSettingValue(dsl, "smtp.host");
    String originalUsername = rawSystemSettingValue(dsl, "smtp.username");
    try {
      settingsService.updatePlatformSettings(
          java.util.Map.of("smtp.host", "platform-smtp.example.com", "smtp.username", "platform-user"),
          null);
      String platformPort = rawSystemSettingValue(dsl, "smtp.port");
      assertThat(platformPort).isNotBlank();

      testTenant = createActiveTenant(dsl, "sr-smtp-from-only");
      TenantContext.set(testTenant);
      settingsService.updateSettings(
          java.util.Map.of("smtp.from_address", "tenant-noreply@example.com"), null);

      var config = settingsService.getSmtpConfig();
      assertThat(config).containsEntry("smtp.from_address", "tenant-noreply@example.com");
      assertThat(config).containsEntry("smtp.host", "platform-smtp.example.com");
      assertThat(config).containsEntry("smtp.username", "platform-user");
      assertThat(config).containsEntry("smtp.port", platformPort);
    } finally {
      restoreSystemSettingValue(dsl, "smtp.host", originalHost);
      restoreSystemSettingValue(dsl, "smtp.username", originalUsername);
    }
  }

  /**
   * 화면 경로({@code getResolvedByPrefix})도 <b>같은 해석</b>을 본다: 번들 재정의 상태에서 연결
   * 5키가 전부 {@code overridden=true} 로 내려가고, 행이 없는 키의 {@code value} 는 {@code ""} 다.
   *
   * <p><b>이것이 의도다.</b> 플래그의 뜻은 "이 키는 테넌트 평면에서 해석된다"이고, 채워 넣지 않으면
   * 화면이 {@code 기본값 사용 중} 배지를 다는데 그 플랫폼 값은 실제로 쓰이지 않는다 — 원 결함의
   * 거짓말을 화면에 재생산하는 셈이다. 서버가 단일 권위여야 web 이 파생을 틀려도 거짓말이 나가지
   * 않는다.
   *
   * <p>{@code smtp.from_address} 는 같은 응답에서 {@code overridden=false} 로 남아야 한다 —
   * 그 대비가 "규칙이 다르다"를 화면이 그릴 수 있는 유일한 근거다.
   */
  @Test
  void getResolvedByPrefix_는_번들_재정의_시_연결_5키를_전부_overridden으로_내려준다() {
    String originalPassword = rawSystemSettingValue(dsl, "smtp.password");
    try {
      settingsService.updatePlatformSettings(
          java.util.Map.of("smtp.password", "platform-shared-password"), null);
      assertThat(rawSystemSettingValue(dsl, "smtp.password")).isNotBlank();

      testTenant = createActiveTenant(dsl, "sr-smtp-resolved");
      TenantContext.set(testTenant);
      settingsService.updateSettings(java.util.Map.of("smtp.host", "tenant-relay.example.com"), null);

      var byKey =
          settingsService.getResolvedByPrefix("smtp").stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      com.smartfirehub.settings.dto.ResolvedSettingResponse::key, r -> r));

      for (String key :
          java.util.List.of(
              "smtp.host", "smtp.port", "smtp.username", "smtp.password", "smtp.starttls")) {
        assertThat(byKey.get(key).overridden())
            .as("%s 가 overridden=false 면 화면이 쓰이지도 않는 플랫폼 값에 '기본값 사용 중'을 단다", key)
            .isTrue();
      }
      assertThat(byKey.get("smtp.host").value()).isEqualTo("tenant-relay.example.com");
      // 행이 없는 키는 빈 값이다 — 비밀번호는 빈 값이라 마스킹도 걸리지 않는다("****" 가 아니다).
      assertThat(byKey.get("smtp.port").value()).isEmpty();
      assertThat(byKey.get("smtp.username").value()).isEmpty();
      assertThat(byKey.get("smtp.password").value()).isEmpty();
      // starttls 만 예외로 "true" 다(RULING F) — 보안 토글이라 빈 값이 덜 안전한 방향이다.
      // 화면의 Switch 도 이 값을 그대로 읽어 켜짐으로 그린다.
      assertThat(byKey.get("smtp.starttls").value()).isEqualTo("true");
      // from_address 는 번들 밖이므로 상속 그대로다.
      assertThat(byKey.get("smtp.from_address").overridden()).isFalse();
    } finally {
      restoreSystemSettingValue(dsl, "smtp.password", originalPassword);
    }
  }

  /**
   * 오버라이드 {@code smtp.password} 가 <b>빈 문자열</b>이면 복호화하지 않는다.
   *
   * <p>인증 없는 릴레이를 쓰는 테넌트가 실제로 이 상태다. 빈 값은 {@code encryptIfSecret} 이
   * 암호화하지 않고 그대로 두므로, 읽기에서 무조건 복호화하면 빈 ciphertext 복호화 실패로
   * <b>SMTP 설정 조회 자체가 터진다</b>(발송 전체가 멈춘다). 기존 플랫폼 경로에 있던
   * {@code !isBlank()} 가드를 오버라이드 경로에도 똑같이 적용했는지 고정한다.
   */
  @Test
  void 빈_오버라이드_비밀번호는_복호화하지_않는다() {
    testTenant = createActiveTenant(dsl, "sr-smtp-blank");
    TenantContext.set(testTenant);
    settingsService.updateSettings(
        java.util.Map.of("smtp.host", "relay.example.com", "smtp.password", ""), null);

    // 전제: 빈 값은 암호화되지 않은 채 저장돼 있다.
    assertThat(rawTenantSettingValue("smtp.password")).isEmpty();

    var config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.password", "");
    assertThat(config).containsEntry("smtp.host", "relay.example.com");
  }

  /**
   * 테넌트 컨텍스트가 없으면 플랫폼 SMTP 로 폴백한다(예외를 던지지 않는다).
   *
   * <p>설계서 §4.5: 해석기는 배경 경로에서 <b>컨텍스트 없음 분기를 명시</b>한다.
   * 여기서 {@code TenantContext.require()} 를 부르면 배경 잡이 영구 무동작이나 42501 이 된다
   * (P3-a·P2-g 에서 두 번 겪었다). 알림 발송 워커·프로액티브 리포트가 이 경로를 탄다.
   */
  @Test
  void 컨텍스트_없으면_플랫폼_SMTP_로_폴백한다() {
    String platformPort = rawSystemSettingValue(dsl, "smtp.port");
    assertThat(platformPort).isNotBlank();

    TenantContext.clear();
    var config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.port", platformPort);
    assertThat(config).containsKey("smtp.host");
  }

  /**
   * <b>RULING F 회귀 가드</b>: 호스트·사용자 이름·비밀번호를 재정의해도 {@code smtp.starttls} 는
   * <b>켜진 채로</b> 해석된다.
   *
   * <p>이것이 밴드 리뷰가 찾은 MUST-FIX 다. 테넌트 ADMIN 이 자기 회사 SMTP 자격증명을 채워 저장하는
   * 것은 <b>이 화면의 가장 흔한 정상 조작</b>인데, 그때 STARTTLS 스위치는 화면에 켜짐으로 보이므로
   * (플랫폼 값 {@code 'true'} 가 폼에 시드된다) 손댈 이유가 없고, web 은 바뀐 키만 보내므로
   * {@code smtp.starttls} 행이 생기지 않는다. 번들이 그 키를 빈 값으로 채우면 세 소비자가 전부
   * 꺼짐으로 읽어({@code Boolean.parseBoolean("")}, {@code "true".equalsIgnoreCase("")})
   * <b>테넌트가 방금 입력한 자격증명이 평문 채널로</b> 나간다. 발송은 성공하므로 아무도 못 본다.
   *
   * <p><b>이 상태를 덮는 테스트가 백엔드에도 E2E 에도 없었다.</b> 바로 위
   * {@code getResolvedByPrefix_는_번들...} 은 호스트 하나만 재정의하는 화면 경로를 보고,
   * {@code 호스트만_재정의해도...} 는 자격증명이 <b>비는</b> 쪽만 본다 — 자격증명이 <b>채워진</b>
   * 상태에서 암호화가 꺼지는 조합은 둘 다 지나쳤다.
   *
   * <p>발송 경로({@code getSmtpConfig})와 화면 경로({@code getResolvedByPrefix})를 한 테스트에서
   * 함께 단언한다. 규칙이 한 곳에 있으므로 둘은 갈라질 수 없지만, 갈라지지 않는다는 것이 이
   * 태스크의 주장이므로 그 주장 자체를 고정한다.
   */
  @Test
  void 자격증명을_재정의해도_STARTTLS_는_켜진_채로_해석된다() {
    testTenant = createActiveTenant(dsl, "sr-smtp-tls");
    TenantContext.set(testTenant);
    // 사용자가 실제로 하는 조작: 호스트·사용자 이름·비밀번호만 채우고 스위치는 건드리지 않는다.
    settingsService.updateSettings(
        java.util.Map.of(
            "smtp.host", "smtp.ourcompany.com",
            "smtp.username", "tenant-user@ourcompany.com",
            "smtp.password", "tenant-real-password"),
        null);

    // 전제 확인: 사용자가 스위치를 안 건드렸으므로 tenant_settings 에 starttls 행이 없다.
    // 이 단언이 없으면 "행이 있어서 true 인" 경우와 "채움이 true 인" 경우를 구별하지 못해
    // 규칙을 지워도 통과하는 공허한 테스트가 된다.
    assertThat(rawTenantSettingValue("smtp.starttls"))
        .as("starttls 행이 있으면 이 테스트는 채움 규칙이 아니라 저장을 검증하게 된다")
        .isNull();

    var config = settingsService.getSmtpConfig();
    // 발송 경로: 자격증명은 테넌트 값 그대로 실리고, 암호화는 켜진 채다.
    assertThat(config).containsEntry("smtp.username", "tenant-user@ourcompany.com");
    assertThat(config).containsEntry("smtp.password", "tenant-real-password");
    assertThat(config)
        .as("테넌트가 방금 입력한 자격증명이 STARTTLS 없이 평문으로 나간다")
        .containsEntry("smtp.starttls", "true");

    // 화면 경로도 같은 값을 본다 — Switch 가 켜짐으로 그려지는 근거.
    var starttls =
        settingsService.getResolvedByPrefix("smtp").stream()
            .filter(r -> "smtp.starttls".equals(r.key()))
            .findFirst()
            .orElseThrow();
    assertThat(starttls.value()).isEqualTo("true");
    assertThat(starttls.overridden()).isTrue();
  }

  /**
   * RLS 가 걸린 {@code tenant_settings} 의 <b>저장된 원문</b>을 읽는다(암호화 여부 전제 확인용).
   * GUC 가 필요하므로 반드시 테넌트 트랜잭션 안에서 조회한다.
   */
  private String rawTenantSettingValue(String key) {
    String[] holder = new String[1];
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> {
          var row =
              dsl.fetchOne(
                  "select value from tenant_settings where tenant_id = ? and key = ?",
                  testTenant,
                  key);
          holder[0] = row == null ? null : row.get(0, String.class);
        });
    return holder[0];
  }
}
