package com.smartfirehub.apiconnection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ApiConnectionRow;
import com.smartfirehub.global.exception.CryptoException;
import com.smartfirehub.global.exception.SerializationException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ApiConnectionService} 의 조회 전용 협력자.
 *
 * <p><b>왜 별도 빈인가</b>: {@code testConnection} 은 외부 HTTP 를 트랜잭션 밖에서 수행해야 해서 무애노테이션으로 남겨야 하는데, 그 안에서
 * 조회 메서드를 {@code this.getById(...)} 로 자기호출하면 Spring AOP 프록시가 우회돼 서비스 레이어 트랜잭션이 열리지 않는다. RLS 하에서 GUC
 * 는 트랜잭션이 열릴 때만 주입되므로, 조회가 트랜잭션 경계를 갖지 못하면 조용히 0행이 될 위험을 구조적으로 안고 가게 된다. 조회부를 별도 빈으로 분리해야 호출이 실제로
 * 프록시를 통과한다.
 *
 * <p>여기에는 <b>조회·복호화·마스킹만</b> 둔다. 외부 HTTP 호출은 여전히 {@code ApiConnectionService} 쪽 트랜잭션 밖에 남아 있어야 하며,
 * 이 클래스로 들어와서는 안 된다(HTTP 5초를 트랜잭션이 물면 커넥션 풀이 고갈된다).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApiConnectionReader {

  /** 마스킹 대상 키 조각 — 이 조각이 포함된 키의 값은 응답에서 가린다. */
  private static final Set<String> SENSITIVE_KEY_PARTS =
      Set.of("key", "token", "secret", "password");

  private final ApiConnectionRepository repository;
  private final EncryptionService encryptionService;
  private final ObjectMapper objectMapper;

  /** 단건 조회 — authConfig 는 마스킹된 형태로, baseUrl 은 사용자 정보의 비밀번호를 가린 형태로(WD-681) 반환한다. */
  public ApiConnectionResponse getById(Long id) {
    ApiConnectionRow row =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    return toResponse(row);
  }

  /**
   * 호출용 단건 조회(WD-681) — {@link #getById} 와 같되 baseUrl 을 가리지 않은 저장 원문으로 싣는다.
   *
   * <p>연결 테스트·API_CALL 미리보기·파이프라인 실행은 이 baseUrl 로 실제 요청 URL 을 만든다. 이미 저장된 옛 연결(API_KEY·BEARER + 사용자
   * 정보)은 이관하지 않았으므로(Python 실행기는 httpx 가 사용자 정보를 Basic 인증으로 보낸다) 가린 값({@code ****})으로 부르면 동작이 바뀐다.
   * <b>이 결과의 baseUrl 을 응답·로그·실행 기록으로 내보내지 말 것</b> — 내보낼 때는 {@link BaseUrlUserInfo#mask} 를 거친다.
   */
  public ApiConnectionResponse getByIdForCall(Long id) {
    ApiConnectionRow row =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    return toResponse(row, false);
  }

  /**
   * 다른 곳(파이프라인 스텝 등)이 이 연결을 참조로 저장해도 되는지 — 현재 워크스페이스에서 보이는 연결인지 판정한다(WD-176).
   *
   * <p>RLS 가 걸린 조회라 다른 워크스페이스의 연결과 없는 연결이 똑같이 {@code false} 가 된다. 호출부는 두 경우를 같은 응답으로 거절해야 연결 번호의 존재
   * 여부가 새지 않는다. {@link #getById} 를 쓰지 않는 이유: 그쪽은 「연결 없음」 404({@link
   * ApiConnectionNotFoundException})를 내보내 저장 검증 오류(400, 스텝 이름을 붙인 안내)와 어긋난다.
   */
  public boolean isVisible(Long id) {
    return id != null && repository.existsById(id);
  }

  /**
   * 복호화된 authConfig 를 반환한다. 인증 헤더/쿼리 파라미터 조립에 쓰이므로 마스킹하지 않는다.
   *
   * <p>authType 은 별도 컬럼이라 복호화된 Map 에 들어있지 않다. 호출자(Preview/Executor 등)가 authType 으로 분기(API_KEY +
   * placement=query 등)할 수 있도록 함께 합쳐 반환한다. (#113)
   *
   * <p>JSON 안에 authType 키가 따로 있어도 컬럼 값으로 덮는다(WD-647). 예전에는 putIfAbsent 라 JSON 쪽이 이겨, 빈 비밀 검사(컬럼 기준
   * {@code ApiCallAuth})와 실제 호출(이 Map 의 authType 기준 — Java 실행기·미리보기·Python 실행기)이 서로 다른 방식으로 판정할 수
   * 있었다. 연결 화면·연결 테스트도 컬럼을 본다. 컬럼이 NULL 이면 JSON 의 authType 을 그대로 둔다 — 호출 전 검사({@code
   * ApiCallAuth.resolve(conn, auth)})도 같은 규칙(컬럼, 없으면 JSON)이라 검사·전송이 같은 방식을 본다.
   */
  public Map<String, String> getDecryptedAuthConfig(Long id) {
    ApiConnectionRow row =
        repository.findById(id).orElseThrow(() -> new ApiConnectionNotFoundException(id));
    Map<String, String> config = decryptToMap(row.authConfig());
    String authType = row.authType();
    if (authType != null) {
      config = new HashMap<>(config);
      config.put("authType", authType);
    }
    return config;
  }

  /**
   * {@link #getDecryptedAuthConfig} 와 같지만, 저장값을 읽을 수 없으면(복호화 실패 {@link CryptoException}·JSON 아님
   * {@link SerializationException}) 예외 대신 빈 값을 돌려준다(WD-772 리뷰).
   *
   * <p>연결 수정은 저장값을 읽지 못해도 진행할 수 있는 경우가 있다 — 가린 값이 없으면 저장값 없이 요청값으로 덮고, 가린 값이 있으면 「다시 입력하세요」 400 으로
   * 안내한다. 수정 트랜잭션 안에서 이 클래스(트랜잭션 프록시)가 던진 런타임 예외는 바깥 트랜잭션을 롤백 전용으로 표시해, 호출자가 잡고 계속해도 커밋이 실패한다. 그래서
   * 프록시 안에서 잡아 빈 값으로 바꾼다. 잡는 것은 저장 데이터를 읽지 못한 두 타입뿐이고, 연결 없음 등 다른 예외는 그대로 나간다. 로그에는 연결 id 와 예외 종류만
   * 남긴다(복호화 원문·값은 남기지 않는다).
   *
   * @return 복호화한 authConfig(authType 포함), 읽을 수 없으면 빈 값
   */
  public Optional<Map<String, String>> findReadableDecryptedAuthConfig(Long id) {
    try {
      return Optional.of(getDecryptedAuthConfig(id));
    } catch (CryptoException | SerializationException e) {
      log.warn("API 연결 {} 의 저장된 인증 정보를 읽지 못했습니다: {}", id, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * API 연결 행 → 응답 DTO 변환. 목록 조회(ApiConnectionService.getAll)도 같은 변환이 필요하므로 공개한다 — 서비스에 복호화/마스킹 로직을
   * 중복시키지 않기 위함이다.
   */
  public ApiConnectionResponse toResponse(ApiConnectionRow r) {
    return toResponse(r, true);
  }

  /**
   * 행 → 응답 DTO.
   *
   * @param maskBaseUrl baseUrl 의 사용자 정보 비밀번호를 가릴지(WD-681). 응답·화면·AI 챗으로 나가는 경로는 true, 실제 호출 URL 을
   *     만드는 경로({@link #getByIdForCall})만 false
   */
  private ApiConnectionResponse toResponse(ApiConnectionRow r, boolean maskBaseUrl) {
    Map<String, String> plainConfig = decryptToMap(r.authConfig());
    Map<String, String> masked = maskAuthConfig(plainConfig);

    return new ApiConnectionResponse(
        r.id(),
        r.name(),
        r.description(),
        r.authType(),
        masked,
        maskBaseUrl ? BaseUrlUserInfo.mask(r.baseUrl()) : r.baseUrl(),
        r.healthCheckPath(),
        r.lastStatus(),
        r.lastCheckedAt(),
        r.lastLatencyMs(),
        r.lastErrorMessage(),
        r.createdBy(),
        r.createdAt(),
        r.updatedAt());
  }

  /**
   * 암호문을 복호화해 Map 으로 되돌린다.
   *
   * <p>읽지 못하면 두 가지 예외만 나간다(WD-772 리뷰) — 복호화 실패(키 변경·형식·Base64 오류, WD-780)는 {@link CryptoException},
   * JSON 이 아니면 {@link SerializationException}. 둘 다 저장 데이터 문제(서버 쪽, 500)다. 수정 경로용 {@link
   * #findReadableDecryptedAuthConfig} 는 이 두 타입만 잡아 빈 값으로 돌려주고, 400(가린 값을 되돌릴 수 없음)으로 안내할지는 서비스가
   * 정한다.
   */
  private Map<String, String> decryptToMap(String encryptedConfig) {
    // 형식이 틀리거나 Base64 가 깨진 암호문도 EncryptionService 가 CryptoException 으로 던진다(WD-780). 그
    // 문구(「Decryption failed」)는
    // 여러 기능이 함께 쓰는 영문이라, API 연결 화면·실행 오류에는 예전처럼 한국어 문구를 싣고 원래 예외는 원인으로만 남긴다(타입은 그대로).
    String json;
    try {
      json = encryptionService.decrypt(encryptedConfig);
    } catch (CryptoException e) {
      throw new CryptoException("저장된 API 연결 인증 정보를 복호화하지 못했습니다", e);
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException e) {
      // 저장 데이터 손상(서버 쪽 문제)이라 입력 오류(400)가 아니다 — 500(SerializationException)으로 나가게 하고, 파서 원문(값 조각이
      // 실릴 수 있다)은 응답 문구에 싣지 않고 원인(cause)으로만 남긴다(WD-180).
      throw new SerializationException("저장된 API 연결 인증 정보를 읽지 못했습니다", e);
    }
  }

  /**
   * authConfig 의 이 키가 「비밀 필드」(응답에서 가리는 필드)인지 판정한다. 키 이름(대소문자 무시)에 key/token/secret/password 조각이 들어
   * 있으면 비밀이다.
   *
   * <p>응답 가리기({@link #maskAuthConfig})와 저장 시 가린 값 거절·유지(WD-179, {@code ApiConnectionService})가 반드시
   * 같은 기준을 써야 한다 — 기준이 어긋나면 응답에 {@code ****} 로 나간 필드가 수정 때는 비밀로 인식되지 않아 가린 값이 그대로 저장된다. 그래서 판정을 이 한
   * 곳에 둔다.
   *
   * <p>추가 인증 헤더 키({@link AuthConfigValidator#EXTRA_HEADER_PREFIX}, WD-753)는 헤더 이름과 상관없이 비밀이다 —
   * 「X-Tenant」 처럼 이름에 위 조각이 없어도 값은 함께 보내는 비밀 헤더다. 가린 모양은 API 키 규칙({@code ****}+끝 4자, {@link
   * #maskSecretValue}). 접두어는 대소문자를 가리지 않는다({@link AuthConfigValidator#isExtraHeaderKey}, WD-772) —
   * 정리 전에 저장된 「ExtraHeader:X-Id」 도 평문으로 돌아가지 않게.
   */
  static boolean isSensitiveKey(String key) {
    if (key == null) return false;
    if (AuthConfigValidator.isExtraHeaderKey(key)) return true;
    String lower = key.toLowerCase(Locale.ROOT);
    return SENSITIVE_KEY_PARTS.stream().anyMatch(lower::contains);
  }

  /** 민감 키(apiKey/token/secret/password 등)의 값을 가려 응답에 노출하지 않는다. */
  private Map<String, String> maskAuthConfig(Map<String, String> authConfig) {
    Map<String, String> masked = new HashMap<>();
    for (Map.Entry<String, String> entry : authConfig.entrySet()) {
      boolean isSensitive = isSensitiveKey(entry.getKey());
      masked.put(
          entry.getKey(),
          isSensitive ? maskSecretValue(entry.getKey(), entry.getValue()) : entry.getValue());
    }
    return masked;
  }

  /**
   * 비밀 필드 값 하나의 응답용 가린 모양(WD-681 리뷰). BASIC 비밀번호({@link AuthConfigValidator#BASIC_PASSWORD_KEY})는 끝
   * 글자 없이 고정 {@link AuthQueryMasker#PASSWORD_MASK} — SMTP 비밀번호(WD-676)·baseUrl 사용자 정보({@link
   * BaseUrlUserInfo#mask})와 같다. 비밀 필드인지는 {@link #isSensitiveKey} 한 곳이 정하고, 여기서는 가린 모양만 고른다. 비밀번호는
   * 짧고 사람이 고른 값이라 끝 4자만 보여도 크게 줄어든다. API 키·토큰은 예전 그대로 {@code ****}+끝 4자(어느 키인지 알아보는 데 쓴다).
   *
   * <p>응답 가리기와 수정 시 「가린 값을 그대로 되돌려 보냈는지」 판정({@code ApiConnectionService.resolveMaskedSecrets})이 반드시
   * 이 한 함수를 같이 써야 한다 — 어긋나면 되돌려 보낸 가린 값을 알아보지 못해 거절하거나 가린 값이 저장된다.
   */
  String maskSecretValue(String key, String value) {
    return AuthConfigValidator.BASIC_PASSWORD_KEY.equals(key)
        ? AuthQueryMasker.PASSWORD_MASK
        : encryptionService.maskValue(value);
  }
}
