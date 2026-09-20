package com.smartfirehub.settings.service;

import static com.smartfirehub.settings.service.AiCredentialService.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CryptoException;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SettingsTestSupport;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AiCredentialService} 통합 테스트. {@code ai.credential} 키가 두 평면
 * (system_settings/tenant_settings) 에 걸쳐 있어 {@code IntegrationTestBase} 의 기본 테넌트(1번)
 * 컨텍스트 아래 실제 행을 만든다.
 *
 * <p><b>공유 테스트 DB 정리.</b> 기본 테넌트(1번)는 다른 테스트 클래스와도 공유되므로, 이 클래스가
 * 만든 {@code ai.credential} 행이 다음 테스트에 새면 안 된다. {@link #cleanup()} 이 테넌트 행은
 * 지우고(이 클래스가 새로 만든 것) 플랫폼 행은 시작 시점 값으로 복원한다(원래 없었으면 삭제) —
 * {@code SettingsTestSupport} 의 두 원복 semantics 를 그대로 따른다.
 */
class AiCredentialServiceTest extends IntegrationTestBase {

  // FK(updated_by → user.id) 를 실제 존재하는 사용자로 채우지 않기 위해 null 을 쓴다 —
  // 이 컬럼은 nullable 이고, 다른 settings 테스트(SettingsWritePlaneTest 등)도 같은 이유로
  // updateSettings/upsert 에 null 을 넘긴다.
  private static final Long USER = null;

  @Autowired private AiCredentialService service;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  private String platformOriginal;

  @BeforeEach
  void captureOriginalPlatformValue() {
    platformOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
  }

  @AfterEach
  void cleanup() {
    // 여기서 지운다 — IntegrationTestBase.clearTenantContext() 는 서브클래스(이 클래스) 의
    // @AfterEach 뒤에 실행되므로, 이 시점에는 아직 기본 테넌트 컨텍스트가 살아 있어
    // TenantContext.require() 가 통과한다.
    tenantSettingsRepository.delete(KEY);
    SettingsTestSupport.restoreSystemSettingValue(dsl, KEY, platformOriginal);
  }

  private AiCredentialUpsert upsert(
      String agentType, Map<String, Object> payload, Map<String, String> secret) {
    return new AiCredentialUpsert(agentType, payload, secret);
  }

  private void savePlatform(AiCredentialUpsert req) {
    service.save(req, USER, true);
  }

  /** 손으로 고친 행/롤백된 배포를 흉내낸다 — 서비스를 거치지 않고 저장소에 직접 쓴다. */
  private void seedTenantRaw(String json) {
    tenantSettingsRepository.upsert(KEY, json, USER);
  }

  @Test
  void save_유형이_바뀌면_이름이_겹치는_비밀도_전부_폐기한다() {
    // Sdk.apiKey(Anthropic)와 Opencode.apiKey(OpenAI 호환)는 이름만 같고 다른 비밀이다.
    // 남겨두면 Anthropic 키가 임의의 호환 호스트로 Bearer 전송된다.
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-ant-live")), USER, false);
    service.save(
        upsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of()),
        USER,
        false);

    AiCredential resolved = service.resolve();
    assertThat(resolved).isInstanceOf(AiCredential.Opencode.class);
    assertThat(((AiCredential.Opencode) resolved).apiKey()).isEmpty();
  }

  @Test
  void save_유형이_바뀌어도_새로_받은_비밀은_저장된다() {
    // 위 테스트(유형이 바뀌면 이름이 겹치는 비밀도 전부 폐기한다)는 "옛 비밀이 사라지는지"만
    // 본다 — "유형이 바뀔 때 secret 병합 루프 자체를 건너뛴다"는 변종도 그 테스트를 통과시킨다
    // (새로 받은 비밀도 함께 사라지므로 옛 비밀이 없다는 단언은 여전히 참). 그래서 "새 비밀이
    // 실제로 저장되는지"를 별도로 확인한다(fix round 2, 리뷰 지적 #4).
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-old")), USER, false);
    service.save(
        upsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of("apiKey", "sk-new")),
        USER,
        false);

    AiCredential.Opencode resolved = (AiCredential.Opencode) service.resolve();
    assertThat(resolved.apiKey())
        .as("유형이 바뀌어도 새로 받은 비밀은 살아남아야 한다 — 옛 sdk 값(sk-old)이 아니어야 한다")
        .isEqualTo("sk-new");
  }

  @Test
  void save_알수없는_agentType_은_거부한다() {
    assertThatThrownBy(() -> service.save(upsert("martian", Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_agentType이_null이면_거부한다() {
    assertThatThrownBy(() -> service.save(upsert(null, Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_secret_값이_null이면_거부한다() {
    // Map.of("apiKey", null) 은 그 자체로 NPE 를 던지므로(불변 맵은 null 값을 허용하지 않는다)
    // 가변 맵으로 직접 구성한다 — 화면이 "건드리지 않은 필드"를 null 로 직렬화하는 실수를
    // 흉내낸다(Ruling #13). 생략(유지)과 null(거부)이 코드에서 구분되어야 한다.
    Map<String, String> secretWithNull = new HashMap<>();
    secretWithNull.put("apiKey", null);

    assertThatThrownBy(() -> service.save(upsert("sdk", Map.of(), secretWithNull), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void read_는_손상된_암호문도_미설정으로_보여주지만_resolve_는_예외를_던진다() {
    // 손으로 고친 행(예: 마이그레이션 실수, 수동 SQL)이 유효한 암호문 형식이 아닌 값을
    // secret 에 남길 수 있다. read()/DELETE 는 그래도 동작해야 관리자가 상태를 보고 되돌릴 수
    // 있고(스펙 "알 수 없는 agentType" 문단과 같은 이유), resolve() 는 반대로 그 손상을 감추면
    // 안 된다 — 감추면 에이전트가 ambient 키로 조용히 넘어가는 과금 회귀가 재현된다
    // (fix round 2, 리뷰 지적 #5).
    seedTenantRaw(
        """
        {"v":1,"agentType":"sdk","payload":{},"secret":{"apiKey":"not-a-cipher"}}""");

    assertThat(service.read().secretFieldNames())
        .as("read() 는 손상된 암호문을 예외 없이 '미설정'으로 본다")
        .doesNotContain("apiKey");
    assertThatThrownBy(() -> service.resolve())
        .as("resolve() 는 손상을 감추지 않고 시끄럽게 실패한다")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void save_같은_유형이면_요청에_없는_payload_필드를_보존한다() {
    // AiCredentialDocument 의 역직렬화가 "모르는 필드를 보존"하는 이유(클래스 상단 javadoc)는
    // 낡은 화면의 read-modify-write 가 필드를 조용히 떨구지 않게 하려는 것이다. save() 가 매번
    // payload 를 통째로 지우면 그 보존이 쓰기 경로에서 무의미해진다(fix round 1, Ruling #8).
    service.save(
        upsert(
            "opencode",
            Map.of(
                "providerId", "openai",
                "baseURL", "https://api.openai.com/v1",
                "reasoningEffort", "medium"),
            Map.of("apiKey", "k")),
        USER,
        false);

    // 같은 유형으로 다시 저장하되 reasoningEffort 는 요청에 담지 않는다(낡은 화면 흉내).
    service.save(
        upsert(
            "opencode", Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"), Map.of()),
        USER,
        false);

    AiCredential.Opencode resolved = (AiCredential.Opencode) service.resolve();
    assertThat(resolved.reasoningEffort())
        .as("요청에 없는 payload 필드는 지워지면 안 된다 — 통째 교체는 read-modify-write 를 깬다")
        .isEqualTo("medium");
  }

  @Test
  void save_비밀을_생략하면_현재_값을_유지한다() {
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-keep")), USER, false);
    service.save(upsert("sdk", Map.of(), Map.of()), USER, false); // 생략

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEqualTo("sk-keep");
  }

  @Test
  void save_비밀에_빈_문자열을_주면_삭제한다() {
    // oauthToken 도 함께 채워 둔다 — apiKey 만 지우면 이 문서(테넌트 소유 sdk)에 비밀이 하나도
    // 안 남아 Fix3 가드(테넌트 소유 sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다,
    // save_테넌트_소유_sdk는_비밀이_모두_지워지면_거부한다 참고)에 걸린다. 이 테스트가 보려는
    // 것은 "빈 문자열이 실제로 지운다"는 삭제 메커니즘이지 Fix3 의 존재 검사가 아니므로,
    // oauthToken 을 남겨 그 가드를 우회하지 않고 자연스럽게 통과시킨다.
    service.save(
        upsert("sdk", Map.of(), Map.of("apiKey", "sk-del", "oauthToken", "oauth-keep")), USER, false);
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "")), USER, false);

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEmpty();
    assertThat(service.read().secretFieldNames()).doesNotContain("apiKey");
  }

  // -------------------------------------------------------------------------
  // 보안 리뷰 Fix3 — 테넌트 소유 sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다
  // -------------------------------------------------------------------------

  /**
   * 핵심 회귀 시나리오. 테넌트 관리자가 "우리 조직이 직접 설정" + sdk 를 고르고 비밀 없이
   * 저장하면(화면은 이를 막지 않는다), 병합된 문서는 secretFieldNames 가 비어 있다 — 그런데도
   * 저장이 성공하면 화면은 "우리 조직 값 적용 중"이라 말하면서 실제 classify/proactive 호출은
   * 컨테이너 ambient ANTHROPIC_API_KEY(=플랫폼 계정)로 과금된다. 이게 이 브랜치가 막으려던
   * 사고(6b1c6383)의 세 번째 경로였다(Ruling #31 반박, 보안 리뷰 Fix3).
   */
  @Test
  void save_테넌트_소유_sdk는_비밀이_없으면_거부한다() {
    assertThatThrownBy(() -> service.save(upsert("sdk", Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** cli/cli-api 도 같은 가드를 탄다 — sdk 하나만 고정하면 세 유형 중 둘이 무테스트로 남는다. */
  @Test
  void save_테넌트_소유_cli는_비밀이_없으면_거부한다() {
    assertThatThrownBy(() -> service.save(upsert("cli", Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_테넌트_소유_cliApi는_비밀이_없으면_거부한다() {
    assertThatThrownBy(() -> service.save(upsert("cli-api", Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * advisor 지적 — "비밀이 하나라도 있으면 통과"로는 부족하다. {@code cli} 는
   * {@link AiCredentialService#toCredential} 이 {@code oauthToken} 만 읽는데(그 스위치 참고),
   * 여기서 {@code apiKey}(cli 가 안 쓰는 이름)만 채워 저장하면 병합된 문서의
   * {@code secretNames()} 는 비어 있지 않으니(이름만 다를 뿐 비밀은 "있다") 이름 대조 없는
   * 구현은 통과시킨다 — 그런데 {@code resolve()} 는 여전히 {@code Cli(oauthToken="")} 를 돌려줘
   * ambient 키로 새는 원래 사고가 필드 이름만 바꿔 재발한다. 화면은 유형별 필드만 보내
   * (CREDENTIAL_FIELDS) 이 모양을 만들 수 없지만, 이 서비스는 화면을 거치지 않는 API 호출도
   * 받으므로 화면의 필터링에 기대면 안 된다.
   */
  @Test
  void save_테넌트_소유_cli는_그_유형이_안_쓰는_이름의_비밀만_있으면_거부한다() {
    assertThatThrownBy(
            () -> service.save(upsert("cli", Map.of(), Map.of("apiKey", "sk-wrong-field")), USER, false))
        .as("cli 는 oauthToken 만 읽는다 — apiKey 는 cli 에서 아무도 안 읽는 죽은 값이다")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 이미 저장된 비밀이 있는 상태에서 secret 을 통째로 생략하는(=유지) 저장은 막지 않는다 —
   * Ruling #16 이 열어 둔 자리("병합된 문서를 봐야, baseURL 만 고치고 저장된 키는 그대로 두는
   * 정상 저장이 스푸리어스 400 을 안 받는다")를 이 가드에서도 지킨다. 요청만 보고 판정했다면
   * (예: 잘못된 구현이 {@code req.secret()} 만 검사) 이 저장도 거부됐을 것이다.
   */
  @Test
  void save_테넌트_소유_저장이_저장된_비밀을_생략해도_통과한다() {
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-existing")), USER, false);

    // secret 을 통째로 생략한다 — payload 만 있는 새로운(사실상 빈) 요청.
    service.save(upsert("sdk", Map.of(), Map.of()), USER, false);

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEqualTo("sk-existing");
  }

  /**
   * opencode 는 Fix3 대상이 아니다 — baseURL 로만 나가는 별개 공급자라 비밀이 없으면 그 호출이
   * 그냥 실패할 뿐, ambient(Anthropic) 키로 몰래 넘어가는 경로가 없다.
   */
  @Test
  void save_테넌트_소유_opencode는_비밀이_없어도_허용된다() {
    service.save(
        upsert("opencode", Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"), Map.of()),
        USER,
        false);

    assertThat(service.read().agentType()).isEqualTo("opencode");
  }

  // -------------------------------------------------------------------------
  // 재검토 N1 — 필수 비밀 검사는 손상된 암호문에서 터지지 않는다(관용 복호화)
  // -------------------------------------------------------------------------

  /** iv 자리가 base64 두 토막("iv:ciphertext")이 아닌 값 — EncryptionService 가 IllegalArgumentException 을 던진다. */
  private static final String CIPHER_FORMAT_BROKEN = "not-a-cipher";

  /**
   * 형식은 맞지만(12바이트 0 IV : 32바이트 0 본문) GCM 태그 검증이 실패하는 값 — 암호화 키
   * 로테이션 뒤 남은 옛 암호문의 전형적인 모양이다. EncryptionService 는 여기서
   * {@code CryptoException}(RuntimeException) 을 던진다.
   */
  private static final String CIPHER_GCM_BROKEN =
      "AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private String sdkRawWithSecret(String secretName, String cipher) {
    return "{\"v\":1,\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\""
        + secretName
        + "\":\""
        + cipher
        + "\"}}";
  }

  /**
   * 재검토 N1 — 형식이 깨진 암호문이 남아 있어도 PUT 은 <b>한국어 거부 메시지</b>로 끝나야 한다.
   *
   * <p>수정 전에는 {@code requireSecretForTenantOwnedCredential} 이 던지는 복호화
   * ({@code decryptOrEmpty})로 기존 암호문을 전부 읽어, 손상된 값 하나가
   * {@code IllegalArgumentException("Invalid encrypted format — expected 'iv:ciphertext'")} 을
   * 그대로 응답에 실어 보냈다(400 이지만 내부 암호화 형식이 노출된다). 예외 <b>타입</b>만 보면
   * 수정 전후가 같으므로(둘 다 IllegalArgumentException), 이 테스트는 반드시 <b>메시지</b>로
   * 판정한다 — 그래야 관용 복호화를 되돌리는 뮤턴트가 RED 가 된다.
   */
  @Test
  void save_테넌트_손상된_암호문이_있어도_내부_예외_문구_대신_한국어로_거부한다() {
    seedTenantRaw(sdkRawWithSecret("oauthToken", CIPHER_FORMAT_BROKEN));

    assertThatThrownBy(() -> service.save(upsert("sdk", Map.of(), Map.of()), USER, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("우리 조직이 직접 설정하는")
        .as("내부 암호화 형식 문구가 응답에 실리면 안 된다")
        .hasMessageNotContaining("Invalid encrypted format");
  }

  /**
   * 재검토 N1 — GCM 태그 검증 실패(키 로테이션의 전형)는 수정 전이면 {@code CryptoException} →
   * <b>500</b> 이었다. 관용 복호화 뒤에는 "쓸 수 없는 비밀 = 없는 비밀"로 판정돼 같은 한국어
   * 400 으로 끝난다.
   */
  @Test
  void save_테넌트_GCM_복호화_실패_암호문도_500이_아니라_한국어_거부로_끝난다() {
    seedTenantRaw(sdkRawWithSecret("oauthToken", CIPHER_GCM_BROKEN));

    assertThatThrownBy(() -> service.save(upsert("sdk", Map.of(), Map.of()), USER, false))
        .isNotInstanceOf(CryptoException.class)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("우리 조직이 직접 설정하는");
  }

  /**
   * 재검토 N1 의 반대편 경계 — 손상된 암호문이 남아 있어도 <b>이번에 보낸 비밀이 멀쩡하면 저장은
   * 성공</b>해야 한다. 이게 Fix7 이 열어 둔 복구 경로다(관리자가 올바른 새 값으로 덮어쓴다).
   *
   * <p>{@code resolve()} 가 아니라 {@code read()} 로 확인한다 — 손상된 {@code oauthToken} 은 저장
   * 뒤에도 문서에 남아 있어 {@code resolve()}(fail-closed) 는 여전히 던지는 게 <b>맞다</b>.
   */
  @Test
  void save_테넌트_손상된_암호문이_있어도_새_비밀이_멀쩡하면_저장된다() {
    seedTenantRaw(sdkRawWithSecret("oauthToken", CIPHER_FORMAT_BROKEN));

    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-recovered")), USER, false);

    assertThat(service.read().secretFieldNames()).containsExactly("apiKey");
  }

  // -------------------------------------------------------------------------
  // 재검토 N7 — tenantOpencodeCredential() 은 손상된 행에서 던지지 않는다
  // -------------------------------------------------------------------------

  /**
   * 재검토 N7 — 손상된 JSON 행에서 {@code AiCredentialDocument::parse} 를 직접 부르면
   * {@code POST /ai-credential/probe}(apiKey 생략) 가 500 이 됐다. {@code tryParse} 로 감싸
   * "오버라이드 없음"과 같게 취급한다.
   */
  @Test
  void tenantOpencodeCredential_은_행이_손상돼도_던지지_않고_빈_값이다() {
    seedTenantRaw("not-json-at-all{{{");

    assertThat(service.tenantOpencodeCredential()).isEmpty();
  }

  /**
   * 재검토 N7 — JSON 은 멀쩡하지만 저장된 apiKey 암호문이 손상된 경우. 복호화에서 던지면 역시
   * 500 이 된다 — 관용 복호화로 빈 키를 내주면 프로브가 "재사용할 저장된 키가 없다"는 한국어
   * 400 으로 끝난다.
   */
  @Test
  void tenantOpencodeCredential_은_암호문이_손상돼도_던지지_않고_빈_키를_돌려준다() {
    seedTenantRaw(
        "{\"v\":1,\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"openai\","
            + "\"baseURL\":\"https://api.openai.com/v1\"},\"secret\":{\"apiKey\":\""
            + CIPHER_GCM_BROKEN
            + "\"}}");

    AiCredentialService.StoredOpencodeCredential stored =
        service.tenantOpencodeCredential().orElseThrow();

    assertThat(stored.baseUrl()).isEqualTo("https://api.openai.com/v1");
    assertThat(stored.apiKey()).isEmpty();
  }

  /**
   * 플랫폼 평면은 이 가드에서 예외다 — 플랫폼 문서가 비어도 소비처는 컨테이너 ambient 키로
   * 폴백하는데, 그 키는 플랫폼 자신의 키라 과금 주체가 어긋나지 않는다(Ruling #31). 여기서 막으면
   * "플랫폼 자체가 ambient 키에 의존하는" 정당한 배포(스펙이 인정하는 형태)까지 저장이 막힌다.
   */
  @Test
  void save_플랫폼_평면은_비밀이_없어도_허용된다() {
    service.save(upsert("sdk", Map.of(), Map.of()), USER, true);

    assertThat(service.read().tenantOwned()).isFalse();
    assertThat(service.read().agentType()).isEqualTo("sdk");
  }

  // -------------------------------------------------------------------------
  // 보안 리뷰 Fix7 — 손상된 JSON 은 read()/save() 를 막지 않는다
  // -------------------------------------------------------------------------

  /**
   * 손으로 고친 행이 JSON 문법 자체를 깨뜨린 경우(agentType 이 이상한 정도가 아니라 파싱 자체가
   * 실패) — read() 는 예외 없이 "미설정"으로 보여준다. tenantOwned 는 그래도 정확히 true 를
   * 보고한다(어느 평면이 손상됐는지는 알 수 있어야 관리자가 DELETE/PUT 중 무엇을 쓸지 고른다).
   */
  @Test
  void read_는_JSON_파싱_자체가_실패해도_예외_없이_미설정으로_보여준다() {
    seedTenantRaw("not-json-at-all{{{");

    AiCredentialService.AiCredentialView view = service.read();

    assertThat(view.agentType()).isEqualTo("sdk"); // DEFAULT_AGENT_TYPE
    assertThat(view.secretFieldNames()).isEmpty();
    assertThat(view.tenantOwned()).isTrue();
  }

  /**
   * 손상된 플랫폼 행은 DELETE 가 없어(PlatformAiCredentialController) GET/PUT 이 유일한 복구
   * 통로다 — save() 가 파싱 실패를 "행이 없다"로 흡수하지 않으면 이 PUT 자체가 손상된 기존
   * 값을 파싱하려다 던져서 관리자가 API 로는 영영 복구할 수 없다.
   */
  @Test
  void save_는_기존_플랫폼_행이_손상돼도_새_값으로_덮어쓴다() {
    dsl.execute(
        "insert into system_settings (key, value) values (?, ?)"
            + " on conflict (key) do update set value = excluded.value",
        KEY,
        "{ this is not valid json");

    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-recovered")), USER, true);

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEqualTo("sk-recovered");
  }

  @Test
  void save_opencode_는_baseURL_이_없으면_거부한다() {
    assertThatThrownBy(
            () ->
                service.save(
                    upsert("opencode", Map.of("providerId", "openai"), Map.of("apiKey", "k")),
                    USER,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolve_알수없는_agentType_은_예외다() {
    // fail-closed. 플랫폼 값으로 폴백하면 6b1c6383 과 같은 과금 회귀가 된다.
    // 플랫폼에 유효한 값을 미리 심어 둔다 — 그렇지 않으면 "폴백해도 예외가 난다"(빈 기본값
    // 경유)와 "폴백을 안 해서 예외가 난다"를 구분할 수 없다(fix round 2, 리뷰 지적).
    savePlatform(upsert("sdk", Map.of(), Map.of("apiKey", "sk-platform")));
    seedTenantRaw(
        """
        {"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThatThrownBy(() -> service.resolve()).isInstanceOf(UnknownAgentTypeException.class);
  }

  @Test
  void read_는_알수없는_agentType_이어도_동작한다() {
    // 관리자가 화면에서 되돌릴 수 있어야 한다.
    seedTenantRaw(
        """
        {"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThat(service.read().agentType()).isEqualTo("martian");
  }

  @Test
  void read_는_비밀_값을_절대_내보내지_않는다() {
    // sdk 는 payload 가 항상 비어 있어 "평문이 payload 에 안 보인다"는 이 테스트가 원래도
    // 참일 수밖에 없었다(무엇을 검사하든 통과). opencode 로 바꿔 payload 에 실제 내용을 담고,
    // 평문뿐 아니라 저장된 암호문 자체도 새지 않는지 함께 검사한다(fix round 2, 리뷰 지적 #3 —
    // read() 의 payload 맵에 secret 노드를 그대로 끼워 넣는 변종은 평문만 검사해서는 안 잡힌다).
    service.save(
        upsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of("apiKey", "sk-secret")),
        USER,
        false);

    String storedCipher =
        AiCredentialDocument.parse(tenantSettingsRepository.findValue(KEY).orElseThrow())
            .secretCipher("apiKey");
    assertThat(storedCipher).isNotBlank();

    AiCredentialService.AiCredentialView view = service.read();
    String payloadJson = view.payload().toString();
    assertThat(payloadJson).as("평문이 새면 안 된다").doesNotContain("sk-secret");
    assertThat(payloadJson)
        .as("저장된 암호문도 새면 안 된다 — payload 에 secret 노드를 그대로 끼워 넣는 변종을 잡는다")
        .doesNotContain(storedCipher);
    assertThat(view.secretFieldNames()).containsExactly("apiKey");
  }

  @Test
  void clearTenantOverride_후에는_플랫폼_값이_해석된다() {
    savePlatform(upsert("sdk", Map.of(), Map.of("apiKey", "sk-platform")));
    service.save(upsert("cli-api", Map.of(), Map.of("apiKey", "sk-tenant")), USER, false);
    service.clearTenantOverride();

    // isInstanceOf(Sdk.class) 만으로는 "플랫폼 값을 읽었다"와 "아무 값도 없어 DEFAULT_AGENT_TYPE
    // 빈 sdk 로 떨어졌다"를 구분하지 못한다(fix round 2, 리뷰 지적 #1 — 플랫폼 폴백을 지워도
    // 9/9 가 GREEN 이었다). apiKey 값 자체를 확인해야 실제로 플랫폼 값을 읽었다는 증거가 된다.
    AiCredential resolved = service.resolve();
    assertThat(resolved).isInstanceOf(AiCredential.Sdk.class);
    assertThat(((AiCredential.Sdk) resolved).apiKey()).isEqualTo("sk-platform");
    assertThat(service.read().tenantOwned()).isFalse();
  }

  /**
   * (리뷰 라운드 1 지적) "테넌트가 유형을 골랐으면 플랫폼 apiKey 를 물려받으면 안 된다"는 삭제된
   * {@code AiCredentialBundleTest} 의 핵심 단언이었는데, 여기서는 두 평면이 <b>동시에</b> 값을
   * 가진 상태에서 직접 비교로 재확인한 적이 없었다({@code clearTenantOverride_...} 는 테넌트를
   * 지운 *뒤*의 플랫폼 폴백만 본다). {@code loadEffectiveDocument()} 의
   * {@code readTenantRaw().or(this::readPlatformRaw)} 순서를 뒤집으면(뮤턴트:
   * {@code readPlatformRaw().or(this::readTenantRaw)}) 이 테스트만 잡아야 한다 — 두 평면 모두
   * 존재하는데 값이 반대로 나오기 때문이다.
   */
  @Test
  void 테넌트와_플랫폼이_동시에_있으면_테넌트_값이_우선한다() {
    savePlatform(upsert("sdk", Map.of(), Map.of("apiKey", "sk-platform-Y")));
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-tenant-X")), USER, false);

    AiCredential resolved = service.resolve();

    assertThat(resolved).isInstanceOf(AiCredential.Sdk.class);
    assertThat(((AiCredential.Sdk) resolved).apiKey()).isEqualTo("sk-tenant-X");
  }
}
