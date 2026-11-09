package com.smartfirehub.apiconnection.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.smartfirehub.global.util.ExactJsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * {@code _apply_resolved_auth} 와 같은 순서).
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
   * 값이 가린 형식({@code ****…})으로 시작하는지. 요청 authConfig 의 가린 값 판정은 앞뒤 공백·「Bearer 」 를 먼저 떼는 {@link
   * AuthConfigValidator#isMaskedSecret} 을 쓴다(WD-639) — 이 메서드를 원값에 바로 쓰면 「Bearer ****…」 를 놓친다.
   */
  static boolean isMasked(String value) {
    return value != null && value.startsWith(MASK_PREFIX);
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
   * 자유 문장(오류 메시지 등)에서 비밀값을 원문·URL 인코딩형·URL 디코딩형 모두 {@link #maskSecret} 으로 바꾼다. 예외 메시지에 URL 이나 값이 실려
   * 오는 경우를 막는 마지막 방어선이다. 값이 너무 짧으면(4자 미만) 평범한 글자까지 지워지므로 건드리지 않는다.
   */
  public static String scrubSecret(String text, String secret) {
    Scrubber scrubber = Scrubber.of(secret);
    return text == null || scrubber == null ? text : scrubber.scrub(text);
  }

  /** {@link #scrubSecret(String, String)} 와 같되 가림 문자열을 정한다 — 끝 4자도 보이면 안 되는 비밀번호용(SMTP, WD-676). */
  public static String scrubSecret(String text, String secret, String mask) {
    return text == null ? null : scrubberFor(secret, mask).apply(text);
  }

  /**
   * {@link #scrubSecret(String, String, String)} 의 가릴 형태(원문·URL 인코딩형·디코딩형)를 한 번만 계산해 둔 함수(WD-686) —
   * 같은 비밀로 여러 문장을 가리는 호출부(SMTP 실패 사슬의 예외마다)가 형태를 매번 다시 만들지 않게 한다. 비밀이 없거나 너무 짧으면 그대로 돌려주는 함수다.
   */
  public static java.util.function.UnaryOperator<String> scrubberFor(String secret, String mask) {
    Scrubber scrubber = Scrubber.of(secret);
    if (scrubber == null) return text -> text;
    Scrubber masked = new Scrubber(scrubber.forms(), mask);
    return masked::scrub;
  }

  /**
   * 응답 본문에서 보낸 비밀을 가린다(WD-657) — API_CALL 미리보기(결과 행·rawJson 이 함께 쓰는 본문)와 연결 테스트의 응답 본문이 같은 규칙을 쓴다.
   *
   * <p>본문 문자열에서 비밀을 바로 바꾸면 새는 경우가 남는다 — JSON 이스케이프된 형태(슬래시 앞 역슬래시·유니코드 이스케이프)는 문자열 비교로 못 찾지만 파싱하면
   * 원문이 되고, 따옴표 없는 숫자 비밀을 바꾸면 JSON 이 깨진다. 그래서 JSON 이면 파싱한 값마다 가려 다시 쓴다.
   *
   * <ul>
   *   <li>비밀이 없거나 최소 길이(4자) 미만이면 손대지 않는다 — 짧은 값을 지우면 평범한 글자까지 지워진다.
   *   <li>어느 형태의 비밀도 없고 역슬래시(JSON 이스케이프)도 없으면 파싱하지 않고 그대로 돌려준다 — 대부분의 응답이 여기서 끝난다.
   *   <li>문자열에 걸린 것은 없고 역슬래시만 있으면 스트리밍 판정({@link #jsonValuesContainSecret})으로 이스케이프된 비밀이 실제로 있을 때만
   *       트리를 만든다(WD-676) — 없으면 원문을 그대로 돌려준다.
   *   <li>가린 값이 하나도 없으면 원문 형태(줄바꿈·들여쓰기)를 지키려고 문자열 가림만 한 본문을 돌려준다.
   *   <li>JSON 이 아니면(파싱 실패) 문자열 가림 결과.
   * </ul>
   *
   * @param body 응답 본문(null 가능)
   * @param secret 보낸 비밀. null 이면 가릴 것 없음
   */
  public static String maskBody(String body, String secret) {
    Scrubber scrubber = Scrubber.of(secret);
    if (body == null || scrubber == null) return body;
    String textMasked = scrubber.scrub(body);
    if (textMasked.equals(body)) {
      if (body.indexOf('\\') < 0) return body;
      // 문자열 가림에 걸린 것이 없고 역슬래시만 있으면(WD-676) 트리를 만들기 전에 스트리밍으로 비밀이 실제로 있는지 본다 — 역슬래시는
      // 흔해(「\/」·「\n」·유니코드 이스케이프) 큰 응답마다 전체 트리를 만들던 비용을 비밀이 있을 때만 치른다.
      if (!jsonValuesContainSecret(body, scrubber)) return body;
    }
    return maskJsonTree(body, scrubber, textMasked);
  }

  /**
   * 본문을 JSON 트리로 읽어 값마다 가린다. 가린 것이 없거나 JSON 이 아니면 {@code fallback}(문자열 가림 결과)을 돌려준다.
   *
   * @param fallback 가린 것이 없을 때 돌려줄 본문 — 원문 형태(줄바꿈·들여쓰기)를 지킨다
   */
  private static String maskJsonTree(String body, Scrubber scrubber, String fallback) {
    ObjectMapper mapper = ExactJsonMapper.INSTANCE;
    try {
      JsonNode tree = mapper.readTree(body);
      if (tree == null) return fallback;
      JsonNode masked = maskNode(tree, scrubber);
      // 바뀐 노드만 새로 만들므로 같은 객체면 가린 것이 없다.
      return masked == tree ? fallback : mapper.writeValueAsString(masked);
    } catch (JsonProcessingException e) {
      return fallback;
    }
  }

  /** 테스트 교차 검증용 — 스트리밍 판정을 건너뛰고 늘 트리 경로로 가린다(WD-676, {@link #jsonValuesContainSecret} 의 기준). */
  static String maskBodyViaTree(String body, String secret) {
    Scrubber scrubber = Scrubber.of(secret);
    if (body == null || scrubber == null) return body;
    return maskJsonTree(body, scrubber, scrubber.scrub(body));
  }

  /**
   * JSON 본문의 키·값 가운데 {@link #maskNode} 가 가릴 것이 하나라도 있는지 스트리밍으로 본다(WD-676) — 트리를 만들지 않는다.
   *
   * <p>판정 규칙은 {@link #maskNode} 와 같은 근거를 쓴다 — 어긋나면 이스케이프된 비밀이 새므로 두 경로를 같은 입력 표로 교차 검증한다.
   *
   * <ul>
   *   <li>키·문자열: 이스케이프를 푼 텍스트에 {@link Scrubber#scrub} 을 적용해 바뀌면 있음.
   *   <li>정수: {@code getBigIntegerValue().toString()} 이 비밀 형태와 같으면 있음 — 트리 정수 노드의 {@code asText()}
   *       와 같은 표기.
   *   <li>소수: {@code getDecimalValue().toString()} 이 비밀 형태와 같으면 있음 — {@link ExactJsonMapper} 의 정확한
   *       BigDecimal 노드 {@code asText()} 와 같은 정규형(예: {@code 1e3} → {@code 1E+3}).
   * </ul>
   *
   * JSON 이 아니면(파싱 오류) 없음으로 본다 — 트리 경로도 파싱 실패면 문자열 가림 결과를 돌려주므로 결과가 같다.
   */
  static boolean jsonValuesContainSecret(String body, Scrubber scrubber) {
    try (JsonParser parser = ExactJsonMapper.INSTANCE.getFactory().createParser(body)) {
      JsonToken token;
      while ((token = parser.nextToken()) != null) {
        switch (token) {
          case FIELD_NAME, VALUE_STRING -> {
            String text = parser.getText();
            if (!scrubber.scrub(text).equals(text)) return true;
          }
          case VALUE_NUMBER_INT -> {
            if (scrubber.forms().contains(parser.getBigIntegerValue().toString())) return true;
          }
          case VALUE_NUMBER_FLOAT -> {
            if (scrubber.forms().contains(parser.getDecimalValue().toString())) return true;
          }
          default -> {
            // 구조 토큰·불리언·null 은 가릴 것이 없다.
          }
        }
      }
      return false;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * JSON 값 하나를 재귀로 가린다. 바뀐 것이 없으면 받은 노드를 그대로 돌려주고, 바뀐 노드(와 그 조상)만 새로 만든다.
   *
   * <ul>
   *   <li>문자열·키: 든 비밀을 가린 문자열로 바꾼다.
   *   <li>숫자: 숫자 그대로 둔다 — 값 전체가 비밀의 한 형태와 똑같을 때만 가린 문자열로 바꾼다. 비밀을 일부로 품은 숫자까지 바꾸면 미리보기 행의 타입·값이 실제
   *       실행과 달라진다.
   *   <li>키를 가린 결과가 같은 객체의 다른 키와 겹치면 둘 다 남긴다 — 뒤의 것에 「 (2)」 같은 꼬리를 붙인다(덮어쓰면 값이 사라진다).
   * </ul>
   */
  private static JsonNode maskNode(JsonNode node, Scrubber scrubber) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
      boolean changed = false;
      for (var it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String key = scrubber.scrub(e.getKey());
        JsonNode value = maskNode(e.getValue(), scrubber);
        if (!key.equals(e.getKey()) || value != e.getValue()) changed = true;
        entries.add(Map.entry(key, value));
      }
      if (!changed) return node;
      ObjectNode out = ExactJsonMapper.INSTANCE.createObjectNode();
      for (Map.Entry<String, JsonNode> e : entries) {
        out.set(uniqueKey(out, e.getKey()), e.getValue());
      }
      return out;
    }
    if (node.isArray()) {
      List<JsonNode> items = new ArrayList<>();
      boolean changed = false;
      for (JsonNode child : node) {
        JsonNode masked = maskNode(child, scrubber);
        if (masked != child) changed = true;
        items.add(masked);
      }
      if (!changed) return node;
      ArrayNode out = ExactJsonMapper.INSTANCE.createArrayNode();
      items.forEach(out::add);
      return out;
    }
    if (node.isTextual()) {
      String text = node.asText();
      String scrubbed = scrubber.scrub(text);
      return scrubbed.equals(text) ? node : TextNode.valueOf(scrubbed);
    }
    if (node.isNumber() && scrubber.forms().contains(node.asText())) {
      return TextNode.valueOf(scrubber.mask());
    }
    return node;
  }

  /** 객체에 이미 있는 키면 「 (2)」「 (3)」… 꼬리를 붙여 겹치지 않는 키를 만든다. */
  private static String uniqueKey(ObjectNode out, String key) {
    if (!out.has(key)) return key;
    int n = 2;
    while (out.has(key + " (" + n + ")")) n++;
    return key + " (" + n + ")";
  }

  /**
   * 비밀 하나를 가리는 데 필요한 값을 한 번만 계산해 둔 것(WD-657) — 본문의 값마다 형태·가림 문자열을 다시 만들지 않는다. 패키지 공개는 테스트가 스트리밍 판정에
   * 직접 넘기기 위해서다(WD-676).
   *
   * @param forms 지울 형태(긴 것부터, 최소 길이 이상만)
   * @param mask 바꿔 넣을 가림 문자열({@link #maskSecret})
   */
  record Scrubber(List<String> forms, String mask) {

    /** 비밀이 없거나 최소 길이 미만이면 null — 가릴 것이 없다. */
    static Scrubber of(String secret) {
      if (secret == null || secret.length() < MIN_SCRUB_LENGTH) return null;
      List<String> forms =
          secretForms(secret).stream().filter(f -> f.length() >= MIN_SCRUB_LENGTH).toList();
      return new Scrubber(forms, maskSecret(secret));
    }

    String scrub(String text) {
      String out = text;
      for (String form : forms) {
        out = out.replace(form, mask);
      }
      return out;
    }
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
