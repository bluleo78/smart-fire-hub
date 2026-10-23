package com.smartfirehub.apiconnection.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * API 키를 쿼리 파라미터로 보내는 인증(API_KEY + placement=query)에서, 실제 호출 URL 과 화면·응답에 내보낼 표시용 URL 을 함께 만들고 비밀값을
 * 가리는 유틸(WD-177).
 *
 * <p><b>왜 필요한가.</b> 예전에는 「테스트」 결과의 {@code requestUrl}·API_CALL 미리보기의 {@code resolvedUrl} 이 키를 붙인 호출
 * URL 그대로여서 {@code ?serviceKey=<키 원문>} 이 응답·화면·AI 챗으로 나갔다. 다른 화면(maskedAuthConfig)은 {@code
 * ****9876} 으로 가리는데 여기만 드러났다. 호출 URL 과 표시 URL 을 <b>같은 빌더 입력</b>에서 만들고 인증 파라미터 값만 {@link
 * #maskSecret} 으로 바꿔, 인코딩 전 원문이든 인코딩된 형태든 표시 URL 에는 남지 않게 한다(완성된 URL 에서 문자열 치환을 하면 인코딩 형태가 달라 놓친다).
 *
 * <p><b>인증 키 인코딩(WD-182).</b> 호출 URL 의 인증 파라미터 값은 {@link QueryApiKeyEncoding#encode} 로 한 번만 인코딩한다 —
 * 이미 인코딩된 키({@code abc%2Bd%3D%3D})는 그대로, 디코딩 키({@code abc+d==})는 {@code abc%2Bd%3D%3D} 로 같은 바이트가
 * 나간다. 연결 테스트·상태 점검·미리보기·Java 실행기가 모두 이 빌더를 거치므로 규칙이 한 곳에 있다. 예전에는 값을 그대로 {@code build(true)} 에 넣어
 * 디코딩 키({@code = 공백 한글})는 URL 을 만들지 못했고, {@code +} 는 서버가 공백으로 읽었다.
 *
 * <p><b>정적·페이지네이션 쿼리 파라미터 인코딩(WD-200).</b> 붙이는 파라미터의 이름·값은 인증 키와 똑같이 {@link
 * QueryApiKeyEncoding#encode} 로 한 번만 인코딩한다. 예전에는 값을 그대로 {@code build(true)} 에 넣어, 이미 인코딩된 값({@code
 * abc%2Bd%3D%3D})은 그대로 나가고 생 값({@code = 공백 한글})은 「URL 인코딩해 넣으세요」로 거부했다. 그런데 운영 실행(Python 실행기)은 같은
 * 값을 httpx 로 다시 인코딩해 {@code %252B} 로 보내 미리보기는 되는데 실행은 인증에 실패했다. 이제 미리보기·Java 실행기·Python 실행기({@code
 * _build_request_url})가 같은 규칙으로 같은 바이트를 보낸다(공용 사례 표 {@code
 * fixtures/query-param-encoding-vectors.json}). 인증 파라미터는 같은 이름의 정적 파라미터가 있어도 하나만, 맨 끝에 붙인다(Python
 * {@code _apply_auth} 와 같은 순서).
 *
 * <p><b>URL 을 만들 수 없을 때.</b> 기준 URL(경로·이미 붙어 있던 쿼리)은 예전처럼 다시 인코딩하지 않는다. {@code build(true)} 는 그런 곳에
 * {@code = 공백 한글} 처럼 URL 에 그대로 쓸 수 없는 문자가 있으면 {@code Invalid character ... in "<값 원문>"} 을 던진다. 이
 * 예외가 400 응답·실행 기록·로그로 나가지 않도록 원문을 담지 않은 고정 문구의 {@link InvalidRequestUrlException} 으로 바꾼다(원인 예외를
 * cause 로 잇지 않는다 — 원인 사슬을 따라가 근본 원인 메시지를 쓰는 분류기가 다시 원문을 꺼내기 때문이다).
 */
public final class AuthQueryMasker {

  /** 가린 값의 접두. maskedAuthConfig({@code EncryptionService.maskValue})와 같은 형식이다. */
  static final String MASK_PREFIX = "****";

  /** 자유 문장에서 비밀값을 찾아 지울 최소 길이 — 너무 짧은 값을 지우면 문장의 평범한 글자까지 지워진다. */
  private static final int MIN_SCRUB_LENGTH = 4;

  private AuthQueryMasker() {}

  /**
   * 비밀값을 {@code ****} + 끝 4자로 가린다. 4자 미만이면 {@code ****}. maskedAuthConfig 가 쓰는 형식과 같아야 하므로 {@code
   * EncryptionService.maskValue} 가 이 메서드를 그대로 부른다(형식을 한 곳에서만 정한다).
   */
  public static String maskSecret(String value) {
    if (value == null || value.length() < 4) {
      return MASK_PREFIX;
    }
    return MASK_PREFIX + value.substring(value.length() - 4);
  }

  /**
   * 호출용 URL 과 표시용 URL 을 함께 만든다.
   *
   * @param url 쿼리를 붙일 기준 URL(이미 쿼리가 있어도 된다)
   * @param queryParams 붙일 쿼리 파라미터(순서 유지). 인증 파라미터가 이미 들어 있으면 그 값을 가린다
   * @param authParamName 인증 쿼리 파라미터 이름(null 이면 가릴 것 없음)
   * @param authSecret 인증 쿼리 파라미터 값(키 원문)
   * @throws InvalidRequestUrlException 기준 URL(경로·원래 쿼리)에 URL 에 그대로 쓸 수 없는 문자가 있어 호출 URL 을 만들 수 없을
   *     때(문구에 원문 없음). 붙이는 파라미터의 이름·값은 {@link QueryApiKeyEncoding#encode} 로 인코딩하므로 원인이 되지
   *     않는다(WD-182·WD-200)
   */
  public static AuthQueryUrls build(
      String url, Map<String, String> queryParams, String authParamName, String authSecret) {
    Map<String, String> params = queryParams != null ? queryParams : Map.of();

    // 붙일 순서(WD-200): 인증 파라미터가 아닌 것은 받은 순서대로, 인증 파라미터는 맨 끝 하나. 호출자가 같은 이름의 정적 파라미터 자리에 키를
    // 덮어 넣으면(LinkedHashMap.put) 그 자리에 남는데, Python 실행기는 맨 끝에 붙이므로 여기서 순서를 맞춘다.
    Map<String, String> ordered = new LinkedHashMap<>();
    params.forEach(
        (name, value) -> {
          if (authParamName == null || !authParamName.equals(name)) ordered.put(name, value);
        });
    boolean hasAuth = authParamName != null && params.containsKey(authParamName);
    if (hasAuth) {
      ordered.put(authParamName, params.get(authParamName));
    }

    // 표시용: 같은 입력에서 인증 파라미터 값만 가린 값으로 바꾼다. 나머지는 실제로 보내는 인코딩 형태 그대로 보인다(WD-200).
    UriComponentsBuilder displayBuilder = UriComponentsBuilder.fromUriString(url);
    ordered.forEach(
        (name, value) ->
            displayBuilder.queryParam(
                encodeParam(name),
                hasAuth && authParamName.equals(name)
                    ? maskSecret(authSecret)
                    : encodeParam(value)));
    // build()(encoded=false) 는 문자 검사 없이 이어 붙이기만 한다 — 가린 값의 끝 4자에 '=' 나 잘린 '%3' 가 있어도 표시 URL 은
    // 만들어져야 하므로 검사하는 build(true) 를 쓰지 않는다. 혹시 기준 URL 자체에 키가 있으면 그것도 지운다(방어).
    String displayUrl = scrubSecret(displayBuilder.build().toUriString(), authSecret);

    // 호출용: 이름·값을 모두 공용 규칙으로 한 번 인코딩한다(인증 키 WD-182, 정적·페이지네이션 파라미터 WD-200). 인코딩한 조각은 비예약 문자와
    // %XX 로만 이뤄져 build(true) 검사를 항상 통과하므로, build(true) 가 실패하는 것은 기준 URL 자체가 잘못된 경우뿐이다.
    UriComponentsBuilder callBuilder = UriComponentsBuilder.fromUriString(url);
    ordered.forEach((name, value) -> callBuilder.queryParam(encodeParam(name), encodeParam(value)));
    String callUrl;
    try {
      callUrl = callBuilder.build(true).toUriString();
    } catch (IllegalArgumentException e) {
      // 원문을 담은 Spring 메시지를 버리고, cause 도 잇지 않는다(클래스 설명 참고).
      throw new InvalidRequestUrlException();
    }
    return new AuthQueryUrls(callUrl, displayUrl);
  }

  /**
   * 쿼리 파라미터 이름·값 하나를 공용 규칙({@link QueryApiKeyEncoding#encode})으로 인코딩한다. null 값은 빈 값으로 본다 — Spring 은
   * null 값을 {@code name}(= 없음)으로, Python 실행기는 {@code name=} 으로 보내 바이트가 달라지므로 빈 값 하나로 맞춘다(WD-200).
   */
  private static String encodeParam(String raw) {
    return QueryApiKeyEncoding.encode(raw != null ? raw : "");
  }

  /**
   * 인증 설정(authType·placement·paramName·apiKey)으로 쿼리 인증 파라미터를 붙인 호출·표시 URL 을 만든다. API_KEY +
   * placement=query 가 아니면 원본 URL 을 그대로 쓴다.
   */
  public static AuthQueryUrls buildForAuth(
      String url, String authType, Map<String, String> config) {
    String paramName = queryAuthParamName(authType, config);
    if (paramName == null) {
      // 쿼리 인증이 없으면 예전처럼 URL 을 손대지 않는다 — 빌더로 다시 만들면 경로의 한글·공백(WebClient 가 인코딩해 주던 것)에서
      // build(true) 가 실패해 지금 되는 연결이 깨진다.
      return new AuthQueryUrls(url, url);
    }
    String apiKey = config.get("apiKey");
    Map<String, String> params = new LinkedHashMap<>();
    params.put(paramName, apiKey);
    return build(url, params, paramName, apiKey);
  }

  /** API_KEY + placement=query 이고 paramName·apiKey 가 있으면 인증 파라미터 이름을, 아니면 null 을 돌려준다. */
  public static String queryAuthParamName(String authType, Map<String, String> config) {
    if (config == null || !"API_KEY".equals(authType)) return null;
    if (!"query".equals(config.getOrDefault("placement", "header"))) return null;
    String paramName = config.get("paramName");
    String apiKey = config.get("apiKey");
    if (paramName == null || paramName.isBlank() || apiKey == null) return null;
    return paramName;
  }

  /**
   * 자유 문장(오류 메시지 등)에서 비밀값을 원문·URL 인코딩형·URL 디코딩형 모두 {@link #maskSecret} 으로 바꾼다. 예외 메시지에 URL 이나 값이 실려
   * 오는 경우를 막는 마지막 방어선이다. 값이 너무 짧으면(4자 미만) 평범한 글자까지 지워지므로 건드리지 않는다.
   */
  public static String scrubSecret(String text, String secret) {
    if (text == null || secret == null || secret.length() < MIN_SCRUB_LENGTH) return text;
    String mask = maskSecret(secret);
    String out = text;
    for (String form : secretForms(secret)) {
      if (form.length() >= MIN_SCRUB_LENGTH) {
        out = out.replace(form, mask);
      }
    }
    return out;
  }

  /**
   * 비밀값이 문장에 나타날 수 있는 형태들(실제로 보낸 형태, 원문, form 인코딩, %20 인코딩, 이미 인코딩된 값의 디코딩형). 긴 것부터 지운다.
   *
   * <p>실제로 보낸 형태({@link QueryApiKeyEncoding#encode})는 섞인 키({@code abc%2Bd==} → {@code
   * abc%2Bd%3D%3D})나 {@code * ~} 가 든 키에서 다른 형태와 다르다 — 리다이렉트 Location·응답 본문이 보낸 쿼리를 되울리면 이 형태로 실려
   * 온다(WD-182).
   */
  private static Set<String> secretForms(String secret) {
    Set<String> forms = new LinkedHashSet<>();
    forms.add(QueryApiKeyEncoding.encode(secret));
    String formEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
    forms.add(formEncoded);
    forms.add(formEncoded.replace("+", "%20"));
    forms.add(secret);
    try {
      forms.add(URLDecoder.decode(secret, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException ignored) {
      // 잘못된 % 시퀀스 — 디코딩형은 없다고 본다.
    }
    return forms.stream()
        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * 호출용 URI 와 표시용 URL 묶음.
   *
   * @param callUrl 실제 HTTP 호출에 쓰는 URL(키 원문 포함 — 응답·로그·DB 로 내보내지 말 것)
   * @param displayUrl 응답·화면·로그에 내보낼 URL(인증 파라미터 값이 {@code ****XXXX})
   */
  public record AuthQueryUrls(String callUrl, String displayUrl) {

    /** 호출용 URI. 인코딩된 문자열을 그대로 해석한다(예전 {@code build(true).toUri()} 와 같은 값). */
    public URI callUri() {
      return URI.create(callUrl);
    }
  }

  /**
   * 주소(경로·주소에 직접 쓴 쿼리)에 URL 에 그대로 쓸 수 없는 문자가 있어 호출 URL 을 만들 수 없을 때. 메시지에 값 원문을 담지 않는다. 인증
   * 키(WD-182)와 쿼리 파라미터 이름·값(WD-200)은 공용 규칙으로 인코딩되므로 그 때문에는 나지 않는다 — 「쿼리 파라미터 값은 URL 인코딩해 넣으세요」 안내를
   * 걷어 냈다.
   */
  public static final class InvalidRequestUrlException extends IllegalArgumentException {

    public InvalidRequestUrlException() {
      super(
          "요청 URL 을 만들 수 없습니다 — 주소에 URL 에 그대로 쓸 수 없는 문자(공백·한글 등)가 있습니다."
              + " 주소에 직접 쓴 경로·쿼리 값은 URL 인코딩해 넣으세요"
              + "(쿼리 파라미터 칸·API 키는 인코딩한 값·인코딩하지 않은 값 모두 넣을 수 있습니다)");
    }
  }
}
