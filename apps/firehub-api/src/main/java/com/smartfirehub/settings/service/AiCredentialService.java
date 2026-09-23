package com.smartfirehub.settings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 자격증명 슬롯({@link AiCredentialSlot}: {@code ai.credential}, {@code ai.classify_credential} +
 * {@code ai.classify_model})의 <b>유일한 소유자</b>. 이 키들을 읽고 쓰는 모든 경로가 여기를 지난다.
 *
 * <p><b>왜 {@link SettingsService} 가 아닌가</b>: 범용 설정 경로는 {@code Map<String,String>} 을
 * 키마다 검증·암호화한다. 이 값은 JSON 이고 비밀이 <b>하위 필드</b>에 있어, 범용 경로에 얹으면
 * (1) 검증이 문자열 파싱이 되고 (2) 하위 필드 암호화를 범용 경로가 알아야 하며 (3)
 * {@code secretFieldNames} 같은 응답 성형이 불가능하다.
 *
 * <p><b>테넌트 전용(단일 평면, #706)</b>: 값은 {@code tenant_settings} 의 현재 테넌트 행에만
 * 있다. 행이 없거나 테넌트 컨텍스트가 없으면 "미설정"이다 — {@code system_settings}(플랫폼)는
 * 읽지도 쓰지도 않는다. 플랫폼 폴백은 테넌트가 모르는 사이 플랫폼 계정으로 과금되게 하므로,
 * 미설정 테넌트는 호출 전에 {@link AiCredential#incompleteMessage()} 로 명확히 멈춘다.
 * 블롭이 하나라 저장은 항상 통째다.
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
   * 채팅 슬롯 키의 별칭. 같은 패키지 테스트({@code AiCredentialServiceTest} 등)가 static import 로
   * 행을 직접 심으므로 이름을 유지한다 — 진짜 출처는 {@link AiCredentialSlot#CHAT}.
   */
  static final String KEY = AiCredentialSlot.CHAT.key();

  /** 분류 모델 공백 거부 문구. 컨트롤러·화면이 같은 문구를 보여야 해서 공개한다. */
  public static final String MSG_CLASSIFY_MODEL_REQUIRED = "분류 모델을 선택하세요";

  /**
   * 분류 자격증명 행은 있는데 모델 행이 없는(묶음 불변식이 깨진 — 수동 DB 조작 등) 상태의 문구.
   * 채팅으로 조용히 폴백하지 않고 스텝을 실패시킨다(fail-closed).
   */
  static final String MSG_CLASSIFY_BUNDLE_BROKEN =
      "분류 전용 AI 설정이 손상됐습니다(분류 모델이 없습니다). 관리자 설정의 AI 분류 탭에서 다시 저장하거나 설정을 해제하세요.";

  /**
   * 테넌트 행이 없을 때(미설정) 쓰는 기본 유형. 빈 {@code sdk} 문서는 비밀이 없어
   * {@link AiCredential#isComplete()} 가 거짓이므로, 소비처가 호출 전에 "설정되지 않았다" 오류로
   * 멈춘다. 화면도 이 유형으로 폼을 시작한다({@code configured=false} 와 함께).
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
   * 보안 리뷰 Fix3 — 이 세 유형은 저장할 때 그 유형이 실제로 읽는 비밀 필드 중 최소 하나가
   * 비어 있지 않아야 한다(평면 구분이 사라져 이제 무조건 적용된다). {@code opencode} 는 넣지
   * 않는다 — opencode 는 애초에 baseURL 로만 나가는 별개 공급자라 비밀이 없으면 그냥 그 공급자 호출이 실패할 뿐 "컨테이너
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
  private static final Map<String, Set<String>> REQUIRED_SECRET_FIELDS =
      Map.of(
          "sdk", Set.of("oauthToken", "apiKey"),
          "cli", Set.of("oauthToken"),
          "cli-api", Set.of("apiKey"));

  private final TenantSettingsRepository tenantSettingsRepository;
  private final EncryptionService encryptionService;

  // payload 값(Map<String,Object>) ↔ JsonNode 변환에 쓴다. AiCredentialDocument 내부 MAPPER 와
  // 별개 인스턴스다 — 그 클래스의 상수는 private 이고, 여기서 하는 일(POJO↔트리 변환)은 커스텀
  // 설정이 필요 없는 표준 동작이라 공유할 이유가 없다.
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 채팅 슬롯 해석 — 채팅·프로액티브·분류(미설정 시) 호출처가 쓰는 기존 진입점. */
  public AiCredential resolve() {
    return resolve(AiCredentialSlot.CHAT);
  }

  /**
   * 현재 테넌트의 {@code slot} 자격증명을 타입으로 바꾼다. 테넌트 행이 없거나 테넌트 컨텍스트가 없으면
   * {@link #DEFAULT_AGENT_TYPE} 빈 문서(= {@code isComplete()==false}, 미설정)다 — 플랫폼 행은
   * 절대 읽지 않는다(클래스 javadoc "테넌트 전용" 참고).
   *
   * <p><b>알 수 없는 {@code agentType} 은 fail-closed 다</b>({@link UnknownAgentTypeException}).
   * 빈 자격증명으로 조용히 넘어가지 않는다 — 소비처가 ambient 키로 떨어질 여지를 주면
   * {@code 6b1c6383} 과 같은 과금 회귀가 된다.
   */
  public AiCredential resolve(AiCredentialSlot slot) {
    AiCredentialDocument doc =
        readTenantRaw(slot.key())
            .map(AiCredentialDocument::parse)
            .orElseGet(() -> AiCredentialDocument.empty(DEFAULT_AGENT_TYPE));
    return toCredential(doc);
  }

  /** 채팅 슬롯 화면용 읽기. */
  public AiCredentialView read() {
    return read(AiCredentialSlot.CHAT);
  }

  /**
   * {@code slot} 의 화면용 읽기. <b>{@link #resolve()} 를 쓰지 않는다</b> — 알 수 없는 {@code agentType} 에서도
   * 동작해야 관리자가 값을 보고 되돌릴 수 있다. {@code agentType}/{@code payload}/
   * {@code secretFieldNames} 를 문서에서 직접 뽑는다.
   *
   * <p><b>JSON 파싱 자체가 실패해도(손상된 값) throw 하지 않는다</b>(보안 리뷰 Fix7). {@code
   * agentType} 을 모르는 경우(위 문단)는 이미 관대하게 다뤘지만, {@link AiCredentialDocument#parse}
   * 는 JSON 문법 자체가 깨졌거나 최상위가 객체가 아니면 여전히 던졌다 — 그러면 이 메서드가
   * 그대로 예외를 전파해 GET 자체가 500 이 됐고, 손상된 행을 보고 고칠 화면 자체가 막혔다.
   * {@link #tryParse} 로 감싸 손상을 "미설정"처럼 보여주고, {@link #save} 가 그 자리를 덮어써
   * 복구할 수 있게 한다.
   *
   * <p><b>{@code configured} 는 테넌트 행의 존재 여부 그대로다</b> — 행이 손상돼 내용이 빈 폼으로
   * 보이더라도 행이 있으면 참이다(무엇인가 저장돼 있다는 사실은 정확히 보고한다). 행이 없으면
   * {@code {agentType:"sdk", payload:{}, secretFieldNames:[], configured:false}} 이고, 화면은 이
   * 값을 보고 "AI 설정이 없습니다" 안내와 직접 설정 폼을 띄운다.
   */
  public AiCredentialView read(AiCredentialSlot slot) {
    Optional<String> tenantRaw = readTenantRaw(slot.key());
    boolean configured = tenantRaw.isPresent();
    AiCredentialDocument doc =
        tenantRaw
            .flatMap(raw -> tryParse(raw, slot.key(), "GET"))
            .orElseGet(() -> AiCredentialDocument.empty(DEFAULT_AGENT_TYPE));

    Map<String, Object> payload =
        MAPPER.convertValue(doc.payload(), new TypeReference<Map<String, Object>>() {});
    // decryptOrEmpty 가 아니라 decryptOrEmptyLenient 를 쓴다 — 손으로 고친 행이 암호문 형식이
    // 아닌 값을 담고 있어도(예: "not-a-cipher") 화면은 죽지 않고 "미설정"으로 보여야 관리자가
    // 되돌릴 수 있다(스펙 §"알 수 없는 agentType" 문단과 같은 이유, 아래 메서드 javadoc 참고).
    List<String> secretFieldNames = doc.secretNames(cipher -> decryptOrEmptyLenient(cipher, slot.key()));
    return new AiCredentialView(doc.agentType(), payload, secretFieldNames, configured);
  }

  /** 채팅 슬롯 저장 — 기존 호출처(채팅 PUT) 진입점. */
  public void save(AiCredentialUpsert req, Long userId) {
    save(AiCredentialSlot.CHAT, req, userId);
  }

  /**
   * 현재 테넌트의 {@code tenant_settings} {@code slot} 행에 저장한다(유일한 저장 위치). 병합 규칙은
   * 슬롯과 무관하다(아래 문단들, 실제 병합은 {@link #mergeForSave}).
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
   * <p><b>sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다</b>(보안 리뷰 Fix3). 병합이 끝난
   * <b>문서</b>(요청이 아니라)를 검사한다 — 요청만 보면 "baseURL 만 고치고 저장된 키는 그대로
   * 두는" 정상 저장이 스푸리어스 400 을 받는다(Ruling #16 이 열어 둔 자리, 아래
   * {@link #requireUsableSecret} 참고). 비밀 없이 저장되면 화면은 "설정됨"이라 말하는데 실제
   * 호출은 비밀이 없어 실패하거나, 더 나쁘게는 ai-agent 컨테이너의 ambient 키로 과금되는
   * 오귀속이 생긴다 — 이 규칙이 막으려는 사고(6b1c6383)의 한 경로다.
   */
  public void save(AiCredentialSlot slot, AiCredentialUpsert req, Long userId) {
    AiCredentialDocument doc = mergeForSave(slot, req);
    tenantSettingsRepository.upsert(slot.key(), doc.toJson(), userId);
  }

  /**
   * 요청을 검증하고 기존 문서와 병합한 결과를 돌려준다 — <b>아직 쓰지 않는다</b>. 채팅 저장과
   * 분류 묶음 저장이 같은 병합 규칙(생략 secret=유지, ""=삭제, 유형 변경=새 문서, 비밀 필수)을
   * 공유하되, 분류 쪽은 모델 검사를 끝낸 뒤 두 키를 한 트랜잭션에서 쓰기 위해 분리했다.
   */
  private AiCredentialDocument mergeForSave(AiCredentialSlot slot, AiCredentialUpsert req) {
    validate(req);

    // 손상된 JSON(파싱 실패)을 만나도 "행이 없다"와 똑같이 취급한다(보안 리뷰 Fix7) — typeChanged
    // 분기가 이미 그 경우를 AiCredentialDocument.empty() 로 시작하는 경로로 처리하므로, 손상된
    // 기존 값을 null 로 흡수하기만 하면 자연히 같은 길을 탄다. 파싱 실패를 여기서 던지면 이 PUT 이
    // 손상을 덮어쓸 유일한 통로인데도 막혀 버린다 — 그 값은 이번 저장으로 통째로 교체되므로
    // 흡수해도 잃는 게 없다.
    Optional<String> existingRaw = readTenantRaw(slot.key());
    AiCredentialDocument existing = existingRaw.flatMap(raw -> tryParse(raw, slot.key(), "PUT")).orElse(null);
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
    requireUsableSecret(doc, slot.key());
    return doc;
  }

  /**
   * 보안 리뷰 Fix3 — 병합된 문서를 검사해, sdk/cli/cli-api 에 그 유형이 실제로 읽는 비밀 필드가
   * 하나도 채워져 있지 않으면 거부한다. {@link #save} javadoc "sdk/cli/cli-api 는 비밀이 최소
   * 하나 있어야 한다" 문단, {@link #REQUIRED_SECRET_FIELDS} javadoc "필드 이름까지 좁히는 이유"
   * 참고 — 이름이 아무거나면 안 되고 {@link #toCredential} 이 그 유형에서 실제로 읽는 필드여야
   * 한다.
   */
  private void requireUsableSecret(AiCredentialDocument doc, String key) {
    Set<String> requiredFields = REQUIRED_SECRET_FIELDS.get(doc.agentType());
    if (requiredFields == null) return; // opencode 등 이 가드 대상이 아닌 유형

    // 관용 복호화를 쓴다(재검토 N1) — "복호화가 안 되는 비밀"은 "없는 비밀"과 같다. 여기서
    // 던지는 decryptOrEmpty 를 쓰면, 암호화 키 로테이션 등으로 기존 암호문 하나가 손상된 순간
    // 테넌트의 정상 PUT 이 400(형식 파손 → IllegalArgumentException, 게다가 응답에 내부 암호화
    // 형식 문구가 그대로 실린다)이나 500(GCM 태그 실패 → CryptoException)으로 떨어져, Fix7 이
    // 연 손상 복구 경로(save 로 덮어쓰기)를 이 검사가 다시 닫아 버린다. 판정 결과는 관용 쪽이
    // 더 정확하기도 하다 — 복호화되지 않는 값은 resolve() 에서 어차피 쓸 수 없으므로 "설정됨"
    // 으로 세면 안 된다. 그래서 손상 = 미설정으로 보고, 거부는 아래 한국어 안내 메시지로 낸다
    // (내부 예외 문구는 응답에 싣지 않고 로그로만 남긴다 — decryptOrEmptyLenient 참고).
    List<String> nonBlankSecrets = doc.secretNames(cipher -> decryptOrEmptyLenient(cipher, key));
    boolean hasUsableSecret = nonBlankSecrets.stream().anyMatch(requiredFields::contains);
    if (hasUsableSecret) return;

    throw new IllegalArgumentException(
        doc.agentType()
            + " 자격증명은 "
            + String.join("/", requiredFields)
            + " 중 최소 하나가 있어야 한다 — 비밀 없이(혹은 이 유형이 쓰지 않는 이름으로만) 저장하면"
            + " 실제 호출이 인증에 실패하거나 컨테이너의 ambient 키로 과금된다.");
  }

  /**
   * JSON 을 파싱하되, 파싱 자체가 실패하면(손상된 값) 예외 대신 빈 값을 돌려준다(보안 리뷰
   * Fix7). {@link #read}/{@link #save}/{@link #tenantOpencodeCredential} 가 이 도우미를 쓴다 —
   * {@link #resolve} 는 쓰지 않는다: 그쪽은 화면이 아니라 실제 호출에 쓰이는
   * 값이라 손상을 감추면 안 된다(클래스 상단 javadoc "복호화는 이 서비스 한 곳에서만" 문단과
   * 같은 이유로, fail-closed 를 유지해야 하는 경로다). {@code context} 는 로그에만 쓰는 라벨이다
   * (예: "GET"/"PUT") — 어느 호출부에서 손상을 만났는지 운영 로그로 구분하기 위해서다. {@code key}
   * 는 손상된 행의 슬롯 키(채팅/분류)로, 역시 로그에만 쓴다.
   */
  private Optional<AiCredentialDocument> tryParse(String raw, String key, String context) {
    try {
      return Optional.of(AiCredentialDocument.parse(raw));
    } catch (RuntimeException e) {
      // 로그에 실제 슬롯 키를 남긴다 — 채팅/분류 중 어느 행이 손상됐는지 운영에서 구분해야 한다(#707).
      log.warn("{} 문서가 손상돼 파싱할 수 없다({}) — 미설정으로 취급한다: {}", key, context, e.toString());
      return Optional.empty();
    }
  }

  /** 채팅 슬롯 프로브용 저장 키 — 기존 호출처 진입점. */
  public Optional<StoredOpencodeCredential> tenantOpencodeCredential() {
    return tenantOpencodeCredential(AiCredentialSlot.CHAT);
  }

  /**
   * {@code OpencodeProbeService} 전용 — 테넌트 자신의 {@code slot} opencode 자격증명만 읽는다.
   *
   * <p><b>슬롯을 섞지 않는다</b> — 채팅 키로 분류 게이트웨이를 찌르거나 그 반대가 되면 안 된다
   * (baseURL 일치 검사와 별개의 경계, #707).
   *
   * <p><b>{@link #resolve()} 를 쓰지 않는 이유</b>: 프로브는 인증된 외부 호출(임의 baseURL 에
   * Bearer 를 실어 보낸다)을 만드는 화면 경로라, 손상된 행에서 {@code resolve()} 처럼 던지지 않고
   * 관용적으로 읽어야 하며(아래 본문 주석) 유형 필터도 필요하다(다음 문단). 두 메서드 모두
   * 현재 테넌트의 {@code tenant_settings} 행만 본다.
   *
   * <p><b>{@code agentType} 필터가 필요한 이유</b>: {@code sdk} 문서도 {@code apiKey} 라는 이름의
   * secret 필드를 갖는다(Anthropic 키, {@link AiCredential.Sdk#apiKey}). 필터 없이
   * {@code doc.secretCipher("apiKey")} 를 그대로 돌려주면, 테넌트가 현재 {@code sdk} 유형으로
   * 설정돼 있을 때 그 Anthropic 키가 필드 이름이 같다는 이유만으로 opencode 프로브에 실려 임의의
   * OpenAI 호환 호스트로 전송된다 — {@link #save} 클래스 javadoc 이 경고하는 "이름만 같고 다른
   * 비밀" 사고와 같은 모양이다.
   *
   * @return 테넌트 컨텍스트가 없거나, 테넌트 행이 없거나, 유형이 {@code opencode} 가 아니면
   *     빈 값. 있으면 baseURL(평문)과 apiKey(복호화된 평문).
   */
  public Optional<StoredOpencodeCredential> tenantOpencodeCredential(AiCredentialSlot slot) {
    // 손상된 행에서 500 이 나지 않게 관용적으로 읽는다(재검토 N7). 파싱 실패는 "테넌트 행이
    // 없다"와 같게 취급하고(=빈 값), 복호화 실패는 "저장된 키가 없다"와 같게
    // 취급한다 — 둘 다 OpencodeProbeService 가 이미 한국어 400(MSG_NO_STORED_KEY)으로 다루는
    // 모양이다. 여기서 던지면 POST /ai-credential/probe 가 500 이 되어, 손상된 행을 고치려는
    // 관리자가 "연결 테스트" 버튼부터 막힌다. resolve() 와 달리 이 값은 화면이 눌러 보는
    // 프로브용이라 fail-closed 를 고집할 이유가 없다(자격증명이 비면 프로브가 거부한다).
    return readTenantRaw(slot.key())
        .flatMap(raw -> tryParse(raw, slot.key(), "probe"))
        .filter(doc -> "opencode".equals(doc.agentType()))
        .map(
            doc ->
                new StoredOpencodeCredential(
                    doc.payload().path("baseURL").asText(""),
                    decryptOrEmptyLenient(doc.secretCipher("apiKey"), slot.key())));
  }

  // ---- 분류 전용 묶음(#707) ----

  /**
   * 분류 전용 묶음을 해석한다. 분류 자격증명 행이 없으면 빈 값(= 분류는 채팅 설정을 통째로 쓴다).
   *
   * <p>행이 있으면 {@link #resolve(AiCredentialSlot)} 와 같은 fail-closed 규칙(알 수 없는 유형·손상된
   * 암호문은 예외)으로 타입을 만들고, 모델 행이 없거나 공백이면 {@link IllegalStateException} 을
   * 던진다 — <b>채팅으로 폴백하지 않는다</b>. 폴백하면 테넌트가 고른 공급자가 아닌 곳으로 과금된다.
   *
   * <p>{@code readOnly} 트랜잭션으로 두 조회를 묶는다 — 저장소 호출마다 따로 트랜잭션이면 두 조회
   * 사이에 동시 {@link #clearClassify} 가 끼어 "자격증명은 있고 모델은 없는" 가짜 손상으로 보인다.
   */
  @Transactional(readOnly = true)
  public Optional<ClassifyBinding> resolveClassify() {
    Optional<String> raw = readTenantRaw(AiCredentialSlot.CLASSIFY.key());
    if (raw.isEmpty()) return Optional.empty();
    AiCredential credential = toCredential(AiCredentialDocument.parse(raw.get()));
    String model =
        readTenantRaw(AiCredentialSlot.CLASSIFY_MODEL_KEY)
            .map(String::trim)
            .filter(m -> !m.isEmpty())
            .orElseThrow(() -> new IllegalStateException(MSG_CLASSIFY_BUNDLE_BROKEN));
    return Optional.of(new ClassifyBinding(credential, model));
  }

  /** 분류 탭 화면용 읽기. 규칙은 {@link #read(AiCredentialSlot)} 와 같고 모델을 덧붙인다(한 스냅샷으로 읽는다). */
  @Transactional(readOnly = true)
  public AiClassifyCredentialView readClassify() {
    AiCredentialView credential = read(AiCredentialSlot.CLASSIFY);
    String model = readTenantRaw(AiCredentialSlot.CLASSIFY_MODEL_KEY).orElse("");
    return new AiClassifyCredentialView(
        credential.agentType(),
        credential.payload(),
        credential.secretFieldNames(),
        credential.configured(),
        model);
  }

  /**
   * 분류 전용 묶음(자격증명 + 모델)을 <b>한 트랜잭션에서</b> 저장한다.
   *
   * <p>{@code @Transactional} 이 여기 있어야 하는 이유: {@link TenantSettingsRepository} 의 클래스
   * 레벨 애노테이션은 호출마다 따로 커밋한다 — 두 upsert 가 각자 커밋되면 두 번째가 실패할 때
   * 모델 없는 자격증명(= {@link #resolveClassify} 가 fail-closed 로 막는 손상 상태)이 남는다.
   *
   * <p>검사 순서: 모델 공백(400) → 자격증명 병합·검증(채팅과 같은 규칙) → opencode 모델 형식.
   * 전부 쓰기 전에 끝나므로 거부되면 아무 행도 바뀌지 않는다. 채팅과 달리 슬래시 없는 opencode
   * 모델을 통과시키지 않는다 — 채팅이 그걸 허용하는 이유(자격증명 먼저 저장해야 모델을 고를 수
   * 있는 순환 잠금)가 분류에는 없다(모델이 같은 요청에 실린다).
   */
  @Transactional
  public void saveClassify(AiCredentialUpsert req, String model, Long userId) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(MSG_CLASSIFY_MODEL_REQUIRED);
    }
    String trimmedModel = model.trim();
    AiCredentialDocument doc = mergeForSave(AiCredentialSlot.CLASSIFY, req);
    String problem = opencodeModelProblem(doc, trimmedModel);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    tenantSettingsRepository.upsert(AiCredentialSlot.CLASSIFY.key(), doc.toJson(), userId);
    tenantSettingsRepository.upsert(AiCredentialSlot.CLASSIFY_MODEL_KEY, trimmedModel, userId);
  }

  /** 분류 전용 묶음을 함께 지운다(= 채팅 설정 사용으로 복귀). 없어도 성공(멱등). */
  @Transactional
  public void clearClassify() {
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY.key());
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY_MODEL_KEY);
  }

  /**
   * opencode 문서면 모델이 {@code providerId/모델} 형식이고 공급자가 일치하는지 본다. 판정과 문구는
   * 실행 시점 가드({@link AiCredential#modelProblem})와 같은 한 곳에서 온다. payload 만 쓰므로
   * 비밀을 복호화하지 않는다(손상된 옛 암호문이 저장 경로를 막지 않게).
   */
  private static String opencodeModelProblem(AiCredentialDocument doc, String model) {
    if (!"opencode".equals(doc.agentType())) return null;
    JsonNode payload = doc.payload();
    return new AiCredential.Opencode(
            payload.path("providerId").asText(""), payload.path("baseURL").asText(""), "", "")
        .modelProblem(model);
  }

  // ---- 내부 헬퍼 ----

  /**
   * 현재 테넌트의 {@code key} 원문. 컨텍스트가 없으면 조회 자체를 하지 않고 빈 값(= 미설정)이다 — 어느
   * 테넌트의 값인지 모르는 채로 아무 행이나 읽을 수 없고, 대신 읽을 플랫폼 값도 없다. 테넌트
   * 소유 배경 작업(프로액티브 등)은 {@code TenantScopedRunner} 가 컨텍스트를 세운 뒤 부른다.
   */
  private Optional<String> readTenantRaw(String key) {
    if (TenantContext.get() == null) return Optional.empty();
    return tenantSettingsRepository.findValue(key);
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
   *   <li>{@link #read} — 화면(GET)은 손상된 값에서도 동작해야 관리자가 상태를 보고 다시 저장해
   *       고칠 수 있다. 여기서 던지면 그 되돌릴 방법 자체가 막힌다.
   *   <li>{@link #requireUsableSecret} — 필수 비밀 검사(재검토 N1). 복호화가
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
  private String decryptOrEmptyLenient(String cipher, String key) {
    try {
      return decryptOrEmpty(cipher);
    } catch (RuntimeException e) {
      // 비밀 값은 싣지 않고 슬롯 키만 남긴다(어느 슬롯의 암호문이 손상됐는지 구분용, #707).
      log.warn("{} 비밀을 복호화할 수 없다 — 미설정으로 취급한다: {}", key, e.toString());
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

  /**
   * 화면용 읽기 결과. {@code payload} 는 평문(비밀 아님), 비밀 값 자체는 절대 담지 않는다.
   * {@code configured} 는 현재 테넌트에 {@code ai.credential} 행이 있는지다(없으면 AI 미설정).
   */
  public record AiCredentialView(
      String agentType, Map<String, Object> payload, List<String> secretFieldNames, boolean configured) {}

  /** {@link #tenantOpencodeCredential()} 반환 형태. {@code baseUrl}/{@code apiKey} 모두 평문(복호화됨)이다. */
  public record StoredOpencodeCredential(String baseUrl, String apiKey) {}

  /**
   * 저장 요청. {@code secret} 은 부분 맵이다 — 생략된 키는 {@link #save} 가 "유지"로 해석한다
   * (클래스 상단 {@link #save} javadoc 참고).
   */
  public record AiCredentialUpsert(String agentType, Map<String, Object> payload, Map<String, String> secret) {}

  /** {@link #resolveClassify()} 결과 — 분류가 실제로 쓸 자격증명과 모델. */
  public record ClassifyBinding(AiCredential credential, String model) {
    /** 기본 record toString 은 자격증명 record 의 비밀 필드까지 찍는다 — 로그 유출을 막는다(#707). */
    @Override
    public String toString() {
      return "ClassifyBinding[" + credential.nonSecretSummary(model) + "]";
    }
  }

  /**
   * 분류 탭 화면용 읽기 결과(평평한 모양 — HTTP 응답 그대로). 미설정이면
   * {@code configured=false, agentType="sdk", model=""}. 비밀 값은 담지 않는다.
   */
  public record AiClassifyCredentialView(
      String agentType,
      Map<String, Object> payload,
      List<String> secretFieldNames,
      boolean configured,
      String model) {}
}
