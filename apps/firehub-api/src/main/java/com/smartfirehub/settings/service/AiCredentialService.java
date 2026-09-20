package com.smartfirehub.settings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@code ai.credential} 키의 <b>유일한 소유자</b>. 이 키를 읽고 쓰는 모든 경로가 여기를 지난다.
 *
 * <p><b>왜 {@link SettingsService} 가 아닌가</b>: 범용 설정 경로는 {@code Map<String,String>} 을
 * 키마다 검증·암호화한다. 이 값은 JSON 이고 비밀이 <b>하위 필드</b>에 있어, 범용 경로에 얹으면
 * (1) 검증이 문자열 파싱이 되고 (2) 하위 필드 암호화를 범용 경로가 알아야 하며 (3)
 * {@code secretFieldNames} 같은 응답 성형이 불가능하다.
 *
 * <p><b>두 평면</b>: 테넌트 행이 있으면 그 값, 없으면 플랫폼 값. 블롭이 하나라 재정의는 항상
 * 통째다 — 1단계 번들 규칙이 원하던 원자성이 구조상 공짜가 된다.
 *
 * <p><b>복호화는 이 서비스 한 곳에서만 한다.</b> {@link AiCredentialDocument} 는 이름으로
 * 암호문을 꺼내는 {@link AiCredentialDocument#secretCipher(String)} 는 내주지만 복호화는 하지
 * 않는다 — {@link #resolve} 가 그 암호문을 받아 여기서 복호화해 타입 있는 record 필드로 바꾼다.
 * {@code read()} 는 반대로, 손상된 암호문을 만나도 예외 없이 "미설정"으로 보여야 하는 화면
 * 경로라 별도 복호화 함수({@link #decryptOrEmptyLenient})를 쓴다(아래 해당 메서드 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCredentialService {

  /**
   * 저장 키. 패키지 가시성 — 같은 패키지의 {@code AiCredentialServiceTest} 가 손으로 행을
   * 심어(fail-closed 시나리오) 이 서비스를 우회하는 경로를 재현해야 하기 때문이고,
   * {@code SettingsService}/{@code SettingsOverridePolicy} 도 같은 이유로(범용 경로에서
   * 이 키를 걸러내야 한다) 이 상수를 그대로 참조한다 — 리터럴을 여러 곳에 복제하지 않는다.
   */
  static final String KEY = "ai.credential";

  /**
   * 문서가 어느 평면에도 전혀 없을 때(마이그레이션 이전, 혹은 시드가 안 된 테스트 환경) 쓰는
   * 기본 유형. 1단계 번들 채움 규칙("미설정은 sdk, 자격증명은 빈 문자열"이라는 옛
   * {@code SettingsService} AI 자격증명 번들의 관례)과 같은 선상을 그대로 잇는다.
   */
  private static final String DEFAULT_AGENT_TYPE = "sdk";

  /**
   * {@link #save} 가 받아들이는 {@code agentType} 전체 집합. {@link AiCredential} 의 4개 record
   * 와 1:1로 맞춘다 — 여기 없는 문자열을 저장하면 이 키의 유일한 소유자인 이 서비스 스스로
   * {@link #resolve} 가 fail-closed 로 거부하는 "손상된 행"을 만들어내는 꼴이 된다(손으로 고친
   * 행이나 롤백된 배포가 남기는 것과 같은 상태를, 정상 쓰기 경로로 재현하는 셈).
   */
  private static final Set<String> KNOWN_AGENT_TYPES = Set.of("sdk", "cli", "cli-api", "opencode");

  /**
   * 보안 리뷰 Fix3 — 이 세 유형은 <b>테넌트 소유</b> 문서일 때, 그 유형이 실제로 읽는 비밀 필드
   * 중 최소 하나가 비어 있지 않아야 한다. {@code opencode} 는 넣지 않는다 — opencode 는 애초에
   * baseURL 로만 나가는 별개 공급자라 비밀이 없으면 그냥 그 공급자 호출이 실패할 뿐 "컨테이너
   * ambient 키로 몰래 넘어가는" 경로가 없다(3방향 세 소비처 중 sdk/cli/cli-api 만
   * {@code ANTHROPIC_API_KEY}/{@code CLAUDE_CODE_OAUTH_TOKEN} ambient 폴백을 갖는다).
   *
   * <p><b>필드 이름까지 좁히는 이유(보안 리뷰 advisor 지적).</b> 처음엔 "비밀이 하나라도 있으면
   * 통과"로 구현했는데, 유형이 실제로 읽지 않는 이름의 비밀도 "있다"로 세어 통과시켜 버린다 —
   * 예를 들어 {@code {agentType:"cli", secret:{apiKey:"x"}}} 는 {@link #toCredential} 의
   * {@code case "cli"} 가 {@code oauthToken} 만 읽으므로(위 스위치 참고) {@code apiKey} 는 조용히
   * 무시되고 {@code Cli.oauthToken} 은 여전히 빈 문자열이다. 화면은 CREDENTIAL_FIELDS 로 유형별
   * 필드를 이미 걸러 보내지만, 이 서비스는 API 라 화면을 거치지 않는 요청(손으로 만든 요청, 다른
   * 클라이언트)도 받는다 — 화면의 필터링에 기대면 이 API 가 그 필터를 제 손으로 우회할 수 있는
   * 구멍이 된다. {@link #toCredential} 의 필드 매핑과 이 맵이 갈리지 않게 유지할 것.
   */
  private static final Map<String, Set<String>> TENANT_REQUIRED_SECRET_FIELDS =
      Map.of(
          "sdk", Set.of("oauthToken", "apiKey"),
          "cli", Set.of("oauthToken"),
          "cli-api", Set.of("apiKey"));

  private final SettingsRepository settingsRepository;
  private final TenantSettingsRepository tenantSettingsRepository;
  private final EncryptionService encryptionService;

  // payload 값(Map<String,Object>) ↔ JsonNode 변환에 쓴다. AiCredentialDocument 내부 MAPPER 와
  // 별개 인스턴스다 — 그 클래스의 상수는 private 이고, 여기서 하는 일(POJO↔트리 변환)은 커스텀
  // 설정이 필요 없는 표준 동작이라 공유할 이유가 없다.
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 두 평면 해석 후 타입으로 바꾼다. 테넌트 행 → 없으면 플랫폼 행 → 그것도 없으면
   * {@link #DEFAULT_AGENT_TYPE} 빈 문서.
   *
   * <p><b>알 수 없는 {@code agentType} 은 fail-closed 다</b>({@link UnknownAgentTypeException}).
   * 플랫폼 값으로 폴백하지 않는다 — 손으로 고친 테넌트 행이 우연히 플랫폼 자격증명을 쓰게
   * 되면(과금 주체가 섞이는) {@code 6b1c6383} 과 같은 회귀가 된다.
   */
  public AiCredential resolve() {
    AiCredentialDocument doc =
        loadEffectiveDocument().orElseGet(() -> AiCredentialDocument.empty(DEFAULT_AGENT_TYPE));
    return toCredential(doc);
  }

  /**
   * 화면용 읽기. <b>{@link #resolve()} 를 쓰지 않는다</b> — 알 수 없는 {@code agentType} 에서도
   * 동작해야 관리자가 값을 보고 되돌릴 수 있다. {@code agentType}/{@code payload}/
   * {@code secretFieldNames} 를 문서에서 직접 뽑는다.
   *
   * <p><b>JSON 파싱 자체가 실패해도(손상된 값) throw 하지 않는다</b>(보안 리뷰 Fix7). {@code
   * agentType} 을 모르는 경우(위 문단)는 이미 관대하게 다뤘지만, {@link AiCredentialDocument#parse}
   * 는 JSON 문법 자체가 깨졌거나 최상위가 객체가 아니면 여전히 던졌다 — 그러면 이 메서드가
   * 그대로 예외를 전파해 GET 자체가 500 이 됐다. 플랫폼 평면은 {@code DELETE} 가 없어(스펙 98행,
   * {@code PlatformAiCredentialController} 클래스 javadoc), 손상된 플랫폼 행을 만나면 GET 도 PUT
   * 도 막혀 관리자가 API 로는 복구할 방법이 아예 없었다. {@link #tryParse} 로 감싸 손상을 "미설정"
   * 으로 보여주고({@code tenantOwned} 는 그대로 정확히 보고한다 — 어느 평면이 손상됐는지는 알 수
   * 있어야 한다), {@link #save} 가 그 자리를 덮어써 복구할 수 있게 한다.
   */
  public AiCredentialView read() {
    Optional<String> tenantRaw = readTenantRaw();
    boolean tenantOwned = tenantRaw.isPresent();
    AiCredentialDocument doc =
        tenantRaw
            .or(this::readPlatformRaw)
            .flatMap(raw -> tryParse(raw, "GET"))
            .orElseGet(() -> AiCredentialDocument.empty(DEFAULT_AGENT_TYPE));

    Map<String, Object> payload =
        MAPPER.convertValue(doc.payload(), new TypeReference<Map<String, Object>>() {});
    // decryptOrEmpty 가 아니라 decryptOrEmptyLenient 를 쓴다 — 손으로 고친 행이 암호문 형식이
    // 아닌 값을 담고 있어도(예: "not-a-cipher") 화면은 죽지 않고 "미설정"으로 보여야 관리자가
    // 되돌릴 수 있다(스펙 §"알 수 없는 agentType" 문단과 같은 이유, 아래 메서드 javadoc 참고).
    List<String> secretFieldNames = doc.secretNames(this::decryptOrEmptyLenient);
    return new AiCredentialView(doc.agentType(), payload, secretFieldNames, tenantOwned);
  }

  /**
   * 저장한다. {@code platformPlane} 이면 {@code system_settings}, 아니면 {@code tenant_settings}.
   *
   * <p><b>payload 는 secret 과 같은 규칙으로 병합한다(요청에 있는 키만 덮어쓰고, 없는 키는
   * 그대로 둔다) — 단, 유형이 바뀌지 않았을 때만.</b> {@link AiCredentialDocument} 의 역직렬화가
   * "모르는 필드를 보존"하는 이유(클래스 상단 javadoc, {@code v} 필드 설계 의도)는 3단계가 필드를
   * 늘렸을 때 낡은 화면의 read-modify-write 가 그 필드를 조용히 떨구지 않게 하기 위해서다. 저장
   * 경로가 매번 payload 를 통째로 지우면, 읽을 때 지켜준 보존이 쓸 때 무의미해진다 — 이전 구현이
   * 정확히 그 실수였다({@code removeAll()} 로 통째 교체). 필드를 지우려면 화면이 그 필드를 빈
   * 문자열로 명시적으로 보내야 한다(현재 전체 폼을 보내는 화면 동작과 맞다).
   *
   * <p><b>{@code agentType} 이 바뀌면 payload 도 통째로 새로 시작한다.</b> 유형이 다르면 payload
   * 스키마 자체가 다르므로({@code sdk} 는 payload 가 비고, {@code opencode} 는 providerId/
   * baseURL/reasoningEffort 를 쓴다) 옛 유형의 payload 를 보존할 이유가 없다 —
   * {@link AiCredentialDocument#empty} 로 시작하는 문서는 이미 payload 가 비어 있으므로 별도
   * 처리 없이 자연히 "통째 새로 시작"이 된다.
   *
   * <p><b>secret 은 다른 규칙이다(생략=유지, 빈 문자열=삭제).</b> 화면은 이미 저장된 비밀 값을
   * 복호화해 되돌려받지 못하므로(never export), "바꾸지 않았다"를 표현하려면 생략할 수밖에
   * 없다. 그래서 요청에 있는 키만 덮어쓰고, 요청에 없는 키는 문서에 있던 값을 그대로 둔다(아무
   * 동작도 하지 않는 것 자체가 "유지"다). 빈 문자열은 명시적 삭제 의도이므로 빈 문자열을
   * 암호화해 저장한다 — {@link AiCredentialDocument#secretNames} 가 복호화 결과로 "설정됨"을
   * 판정하므로, 빈 평문의 암호문은 그 쪽에서 자동으로 "미설정"으로 잡힌다.
   *
   * <p><b>{@code agentType} 이 바뀌면 secret 을 통째로 비우고 새로 받은 것만 넣는다.</b>
   * {@code Sdk.apiKey}(Anthropic)와 {@code Opencode.apiKey}(OpenAI 호환)처럼 이름만 같고 다른
   * 비밀이 이전 유형에서 남아 있으면, 유형을 바꾼 뒤에도 옛 비밀이 새 유형의 필드로 해석되어
   * 엉뚱한 호스트로 전송될 수 있다. 그래서 유형이 바뀌면 옛 문서를 재사용하지 않고
   * {@link AiCredentialDocument#empty} 로 새로 시작한다 — secret 하위 객체를 비우는 공개 API가
   * 없으므로, "빈 문서에서 다시 시작"이 유일한 통째 초기화 방법이다.
   *
   * <p><b>테넌트 소유 sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다</b>(보안 리뷰 Fix3). 병합이
   * 끝난 <b>문서</b>(요청이 아니라)를 검사한다 — 요청만 보면 "baseURL 만 고치고 저장된 키는
   * 그대로 두는" 정상 저장이 스푸리어스 400 을 받는다(Ruling #16 이 열어 둔 자리, 아래
   * {@link #requireSecretForTenantOwnedCredential} 참고). 플랫폼 평면은 예외다 — 그 문서가
   * 비어도 소비처는 컨테이너 ambient 키로 폴백하는데, 그 키는 플랫폼 자신의 키라 과금 주체가
   * 어긋나지 않는다(Ruling #31). 반대로 테넌트 소유 문서가 비밀 없이 저장되면 화면은 "우리 조직
   * 값 적용 중"이라 말하면서 실제로는 ambient(=플랫폼) 키로 과금되는 오귀속이 생긴다 — 이
   * 브랜치가 막으려는 바로 그 사고(6b1c6383)의 세 번째 경로였다.
   */
  public void save(AiCredentialUpsert req, Long userId, boolean platformPlane) {
    validate(req);

    // 손상된 JSON(파싱 실패)을 만나도 "행이 없다"와 똑같이 취급한다(보안 리뷰 Fix7) — typeChanged
    // 분기가 이미 그 경우를 AiCredentialDocument.empty() 로 시작하는 경로로 처리하므로, 손상된
    // 기존 값을 null 로 흡수하기만 하면 자연히 같은 길을 탄다. 플랫폼 평면은 DELETE 가 없어(spec
    // 98행), 파싱 실패를 여기서 던지면(예전 동작) 이 PUT 이 손상을 덮어쓸 유일한 통로였는데도
    // 막혀 버렸다 — 그 값은 이번 저장으로 통째로 교체되므로 흡수해도 잃는 게 없다.
    Optional<String> existingRaw = platformPlane ? readPlatformRaw() : readTenantRaw();
    AiCredentialDocument existing = existingRaw.flatMap(raw -> tryParse(raw, "PUT")).orElse(null);
    boolean typeChanged = existing == null || !req.agentType().equals(existing.agentType());
    AiCredentialDocument doc = typeChanged ? AiCredentialDocument.empty(req.agentType()) : existing;

    // 요청에 있는 키만 덮어쓴다 — removeAll() 을 하지 않는다. 유형이 바뀐 경우는 doc 이 이미
    // AiCredentialDocument.empty() 로 시작해 payload 가 비어 있으므로, 이 한 줄로 "같은 유형=병합
    // / 다른 유형=통째 새로 시작"이 자연히 갈린다(위 save() javadoc 참고).
    ObjectNode payloadNode = doc.payload();
    req.payload().forEach((key, value) -> payloadNode.set(key, MAPPER.valueToTree(value)));

    // validate() 가 이미 null 값을 거부했으므로 여기서는 있는 그대로 암호화한다 — null 을 ""로
    // 되메우면 "null=거부"와 "생략=유지"가 코드상 구분 안 되는 지점이 다시 생긴다(Ruling #13).
    req.secret().forEach((name, value) -> doc.withSecret(name, encryptionService.encrypt(value)));

    // 병합이 끝난 doc 을 검사한다 — 아직 DB 에 쓰기 전이라 여기서 던져도 부수효과가 없다(Fix3).
    requireSecretForTenantOwnedCredential(doc, platformPlane);

    String json = doc.toJson();
    if (platformPlane) {
      settingsRepository.updateSettings(Map.of(KEY, json), userId);
    } else {
      tenantSettingsRepository.upsert(KEY, json, userId);
    }
  }

  /**
   * 보안 리뷰 Fix3 — 병합된 문서를 검사해, 테넌트 소유 sdk/cli/cli-api 에 그 유형이 실제로 읽는
   * 비밀 필드가 하나도 채워져 있지 않으면 거부한다. {@link #save} javadoc "테넌트 소유
   * sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다" 문단, {@link #TENANT_REQUIRED_SECRET_FIELDS}
   * javadoc "필드 이름까지 좁히는 이유" 참고 — 이름이 아무거나면 안 되고 {@link #toCredential}
   * 이 그 유형에서 실제로 읽는 필드여야 한다.
   */
  private void requireSecretForTenantOwnedCredential(AiCredentialDocument doc, boolean platformPlane) {
    if (platformPlane) return;
    Set<String> requiredFields = TENANT_REQUIRED_SECRET_FIELDS.get(doc.agentType());
    if (requiredFields == null) return; // opencode 등 이 가드 대상이 아닌 유형

    // 관용 복호화를 쓴다(재검토 N1) — "복호화가 안 되는 비밀"은 "없는 비밀"과 같다. 여기서
    // 던지는 decryptOrEmpty 를 쓰면, 암호화 키 로테이션 등으로 기존 암호문 하나가 손상된 순간
    // 테넌트의 정상 PUT 이 400(형식 파손 → IllegalArgumentException, 게다가 응답에 내부 암호화
    // 형식 문구가 그대로 실린다)이나 500(GCM 태그 실패 → CryptoException)으로 떨어져, Fix7 이
    // 연 손상 복구 경로(save 로 덮어쓰기)를 이 검사가 다시 닫아 버린다. 판정 결과는 관용 쪽이
    // 더 정확하기도 하다 — 복호화되지 않는 값은 resolve() 에서 어차피 쓸 수 없으므로 "설정됨"
    // 으로 세면 안 된다. 그래서 손상 = 미설정으로 보고, 거부는 아래 한국어 안내 메시지로 낸다
    // (내부 예외 문구는 응답에 싣지 않고 로그로만 남긴다 — decryptOrEmptyLenient 참고).
    List<String> nonBlankSecrets = doc.secretNames(this::decryptOrEmptyLenient);
    boolean hasUsableSecret = nonBlankSecrets.stream().anyMatch(requiredFields::contains);
    if (hasUsableSecret) return;

    throw new IllegalArgumentException(
        "우리 조직이 직접 설정하는 "
            + doc.agentType()
            + " 자격증명은 "
            + String.join("/", requiredFields)
            + " 중 최소 하나가 있어야 한다 — 비밀 없이(혹은 이 유형이 쓰지 않는 이름으로만) 저장하면"
            + " 실제 호출이 컨테이너의 ambient 키(플랫폼 계정)로 과금된다. 플랫폼 값을 쓰려면"
            + " DELETE 로 테넌트 오버라이드를 지워라.");
  }

  /**
   * JSON 을 파싱하되, 파싱 자체가 실패하면(손상된 값) 예외 대신 빈 값을 돌려준다(보안 리뷰
   * Fix7). {@link #read}/{@link #save} 둘 다 이 도우미를 쓴다 — {@link #resolve}(정확히는
   * {@link #loadEffectiveDocument})는 쓰지 않는다: 그쪽은 화면이 아니라 실제 호출에 쓰이는
   * 값이라 손상을 감추면 안 된다(클래스 상단 javadoc "복호화는 이 서비스 한 곳에서만" 문단과
   * 같은 이유로, fail-closed 를 유지해야 하는 경로다). {@code context} 는 로그에만 쓰는 라벨이다
   * (예: "GET"/"PUT") — 어느 호출부에서 손상을 만났는지 운영 로그로 구분하기 위해서다.
   */
  private Optional<AiCredentialDocument> tryParse(String raw, String context) {
    try {
      return Optional.of(AiCredentialDocument.parse(raw));
    } catch (RuntimeException e) {
      log.warn("ai.credential 문서가 손상돼 파싱할 수 없다({}) — 미설정으로 취급한다: {}", context, e.toString());
      return Optional.empty();
    }
  }

  /** 테넌트 오버라이드를 지운다. 이후 해석은 플랫폼 값으로 되돌아간다. */
  public void clearTenantOverride() {
    tenantSettingsRepository.delete(KEY);
  }

  /**
   * {@code OpencodeProbeService} 전용 — 테넌트 자신의 opencode 자격증명만 읽는다.
   *
   * <p><b>{@link #resolve()} 를 쓰지 않는 이유</b>: 프로브는 인증된 외부 호출(임의 baseURL 에
   * Bearer 를 실어 보낸다)을 만든다. 요청이 {@code apiKey} 를 생략했을 때 "저장된 값"으로
   * {@link #resolve()}(두 평면 해석)를 쓰면, 테넌트가 opencode 로 재정의하지 않은 경우 조용히
   * 플랫폼 행으로 폴백해 <b>플랫폼의 apiKey 를 테넌트가 지정한 임의 baseURL 로 전송</b>하게
   * 된다 — 이 메서드가 막는 것이 정확히 그 유출이다(설계서 "평면 교차 폴백 금지"). 그래서 여기서는
   * {@code tenant_settings} 행이 실제로 있을 때만 값을 내준다.
   *
   * <p><b>{@code agentType} 필터가 필요한 이유</b>: {@code sdk} 문서도 {@code apiKey} 라는 이름의
   * secret 필드를 갖는다(Anthropic 키, {@link AiCredential.Sdk#apiKey}). 필터 없이
   * {@code doc.secretCipher("apiKey")} 를 그대로 돌려주면, 테넌트가 현재 {@code sdk} 유형으로
   * 설정돼 있을 때 그 Anthropic 키가 필드 이름이 같다는 이유만으로 opencode 프로브에 실려 임의의
   * OpenAI 호환 호스트로 전송된다 — {@link #save} 클래스 javadoc 이 경고하는 "이름만 같고 다른
   * 비밀" 사고와 같은 모양이다.
   *
   * @return 테넌트 컨텍스트가 없거나, 테넌트가 재정의하지 않았거나, 재정의했지만 유형이
   *     {@code opencode} 가 아니면 빈 값. 있으면 baseURL(평문)과 apiKey(복호화된 평문).
   */
  public Optional<StoredOpencodeCredential> tenantOpencodeCredential() {
    // 손상된 행에서 500 이 나지 않게 관용적으로 읽는다(재검토 N7). 파싱 실패는 "테넌트
    // 오버라이드가 없다"와 같게 취급하고(=빈 값), 복호화 실패는 "저장된 키가 없다"와 같게
    // 취급한다 — 둘 다 OpencodeProbeService 가 이미 한국어 400(MSG_NO_STORED_KEY)으로 다루는
    // 모양이다. 여기서 던지면 POST /ai-credential/probe 가 500 이 되어, 손상된 행을 고치려는
    // 관리자가 "연결 테스트" 버튼부터 막힌다. resolve() 와 달리 이 값은 화면이 눌러 보는
    // 프로브용이라 fail-closed 를 고집할 이유가 없다(자격증명이 비면 프로브가 거부한다).
    return readTenantRaw()
        .flatMap(raw -> tryParse(raw, "probe"))
        .filter(doc -> "opencode".equals(doc.agentType()))
        .map(
            doc ->
                new StoredOpencodeCredential(
                    doc.payload().path("baseURL").asText(""),
                    decryptOrEmptyLenient(doc.secretCipher("apiKey"))));
  }

  // ---- 내부 헬퍼 ----

  /**
   * 테넌트 원문. 컨텍스트가 없으면(배경 경로) 조회 자체를 하지 않는다 — {@code SettingsService
   * .getValue()} 와 같은 "컨텍스트 없음 = 오버라이드 없음 = 플랫폼 값" 계약이다.
   */
  private Optional<String> readTenantRaw() {
    if (TenantContext.get() == null) return Optional.empty();
    return tenantSettingsRepository.findValue(KEY);
  }

  private Optional<String> readPlatformRaw() {
    return settingsRepository.getValue(KEY);
  }

  /** 두 평면 해석: 테넌트 우선, 없으면 플랫폼. 둘 다 없으면 빈 값. */
  private Optional<AiCredentialDocument> loadEffectiveDocument() {
    return readTenantRaw().or(this::readPlatformRaw).map(AiCredentialDocument::parse);
  }

  /**
   * 문서를 타입 있는 {@link AiCredential} 로 바꾼다. {@code agentType} 을 문자열로 비교하지만,
   * switch 라 새 유형이 늘 때 이 메서드를 안 고치면 컴파일이 아니라 런타임에 default 로 떨어진다
   * — {@link AiCredential} 자체의 sealed switch 와 달리 여기는 "저장된 문자열"을 판별하는
   * 자리라 컴파일 강제가 되지 않는다. 유형이 늘면 이 switch 도 함께 늘려야 한다.
   */
  private AiCredential toCredential(AiCredentialDocument doc) {
    UnaryOperator<String> secretOf = name -> decryptOrEmpty(doc.secretCipher(name));
    JsonNode payload = doc.payload();

    return switch (doc.agentType()) {
      case "sdk" -> new AiCredential.Sdk(secretOf.apply("oauthToken"), secretOf.apply("apiKey"));
      case "cli" -> new AiCredential.Cli(secretOf.apply("oauthToken"));
      case "cli-api" -> new AiCredential.CliApi(secretOf.apply("apiKey"));
      case "opencode" ->
          new AiCredential.Opencode(
              payload.path("providerId").asText(""),
              payload.path("baseURL").asText(""),
              payload.path("reasoningEffort").asText(""),
              secretOf.apply("apiKey"));
      default -> throw new UnknownAgentTypeException(doc.agentType());
    };
  }

  /**
   * 암호문을 복호화하되 미설정(null/빈 값)은 빈 문자열로 통일한다. <b>{@link #resolve} 전용이다
   * — 복호화 실패를 삼키지 않는다.</b> 손상된 암호문(예: 손으로 잘못 고친 값)을 여기서 빈
   * 문자열로 덮으면, 그 값을 쓰는 에이전트가 인증 실패 대신 조용히 ambient 키로 넘어가 버린다
   * (이 설계가 막으려는 과금 회귀와 같은 모양). 화면 경로({@link #read})는 이 메서드가 아니라
   * {@link #decryptOrEmptyLenient} 를 쓴다.
   */
  private String decryptOrEmpty(String cipher) {
    return cipher == null || cipher.isBlank() ? "" : encryptionService.decrypt(cipher);
  }

  /**
   * <b>관용 복호화</b> — 복호화가 실패하는 손상된 암호문(손으로 고친 행, 롤백된 배포가 남긴 값,
   * 암호화 키 로테이션 뒤의 옛 암호문)을 "설정되지 않음"으로 취급한다. {@link #decryptOrEmpty}
   * 와 정확히 반대 방향의 선택이다.
   *
   * <p><b>이 경로들이 관용인 이유</b>: 여기서 다루는 값은 "실제 호출에 쓰는 비밀"이 아니라
   * "비밀이 있는지 없는지에 대한 판정"이다. 두 소비처가 있다.
   *
   * <ul>
   *   <li>{@link #read} — 화면(GET)과 삭제(DELETE)는 손상된 값에서도 동작해야 관리자가 상태를
   *       보고 되돌릴 수 있다. 여기서 던지면 그 되돌릴 방법 자체가 막힌다.
   *   <li>{@link #requireSecretForTenantOwnedCredential} — 필수 비밀 검사(재검토 N1). 복호화가
   *       안 되는 값은 실제 호출에 쓸 수 없으므로 "설정됨"으로 세면 안 된다. 동시에, 손상된 값
   *       하나 때문에 정상 PUT 이 400/500 으로 떨어지면 Fix7 의 복구 경로가 막힌다.
   *   <li>{@link #tenantOpencodeCredential} — 프로브가 재사용할 저장된 키(재검토 N7). 손상이면
   *       빈 문자열 → 프로브는 "재사용할 키가 없다"는 한국어 400 으로 끝난다(500 이 아니다).
   * </ul>
   *
   * <p><b>{@link #resolve} 는 이 메서드를 쓰지 않는다(fail-closed 유지)</b> — 그쪽은 판정이
   * 아니라 실제로 전송되는 자격증명이다. 손상을 빈 문자열로 덮으면 에이전트가 인증 실패 대신
   * 조용히 ambient 키로 넘어가 과금 주체가 뒤바뀐다({@code 6b1c6383}). 관용 복호화를
   * {@code resolve()} 경로로 확산시키지 말 것.
   *
   * <p>삼킨 예외는 응답에 절대 싣지 않는다 — {@code EncryptionService} 의 영문 내부 메시지
   * (예: {@code "Invalid encrypted format …"})가 테넌트 관리자에게 노출되면 내부 암호화 형식을
   * 흘리는 정찰 단서가 된다. 대신 로그로만 남긴다.
   */
  private String decryptOrEmptyLenient(String cipher) {
    try {
      return decryptOrEmpty(cipher);
    } catch (RuntimeException e) {
      log.warn("ai.credential 비밀을 복호화할 수 없다 — 미설정으로 취급한다: {}", e.toString());
      return "";
    }
  }

  /**
   * 요청 검증. <b>요청 자체만 본다 — 병합된 결과 문서를 보지 않는다.</b> 예를 들어 opencode
   * 부분 갱신이 {@code baseURL} 을 생략하면, 저장된 문서에 이미 {@code baseURL} 이 있어도 이
   * 요청은 거부된다(오늘은 화면이 매번 전체 폼을 보내므로 무해하지만, 나중에 부분 갱신 UI가
   * 생기면 이 경계가 실제로 걸린다는 것을 미리 남겨 둔다).
   *
   * <ul>
   *   <li>{@code agentType} 은 {@link #KNOWN_AGENT_TYPES} 안에 있어야 한다(null 포함 거부) —
   *       그렇지 않으면 이 서비스가 스스로 {@link #resolve} 의 fail-closed 대상을 만든다.
   *   <li>{@code opencode} 는 {@code providerId}/{@code baseURL} 이 필수다.
   *   <li>{@code secret} 의 값은 null 일 수 없다 — 계약은 생략(유지)/빈 문자열(삭제) 두 모양뿐이고
   *       null 은 둘 다 아니다. 여기서 막지 않으면 {@code {"apiKey": null}} 을 보내는 화면이
   *       "생략"과 구분 안 되는 채로 값을 지워 버린다(Ruling #13).
   * </ul>
   */
  private void validate(AiCredentialUpsert req) {
    if (req.agentType() == null || !KNOWN_AGENT_TYPES.contains(req.agentType())) {
      throw new IllegalArgumentException("알 수 없는 agentType 입니다: " + req.agentType());
    }
    if ("opencode".equals(req.agentType())) {
      requireNonBlank(req.payload(), "providerId");
      requireNonBlank(req.payload(), "baseURL");
    }
    req.secret()
        .forEach(
            (name, value) -> {
              if (value == null) {
                throw new IllegalArgumentException(
                    "secret." + name + " 은 null 일 수 없다 — 생략하면 유지, 빈 문자열이면 삭제다");
              }
            });
  }

  private void requireNonBlank(Map<String, Object> payload, String field) {
    Object value = payload.get(field);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalArgumentException("opencode 는 " + field + " 이(가) 필수다: " + field);
    }
  }

  /** 화면용 읽기 결과. {@code payload} 는 평문(비밀 아님), 비밀 값 자체는 절대 담지 않는다. */
  public record AiCredentialView(
      String agentType, Map<String, Object> payload, List<String> secretFieldNames, Boolean tenantOwned) {}

  /** {@link #tenantOpencodeCredential()} 반환 형태. {@code baseUrl}/{@code apiKey} 모두 평문(복호화됨)이다. */
  public record StoredOpencodeCredential(String baseUrl, String apiKey) {}

  /**
   * 저장 요청. {@code secret} 은 부분 맵이다 — 생략된 키는 {@link #save} 가 "유지"로 해석한다
   * (클래스 상단 {@link #save} javadoc 참고).
   */
  public record AiCredentialUpsert(String agentType, Map<String, Object> payload, Map<String, String> secret) {}
}
