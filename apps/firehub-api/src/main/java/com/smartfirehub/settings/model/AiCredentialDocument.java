package com.smartfirehub.settings.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code ai.credential} 의 저장 형태 {@code {v, agentType, payload, secret}}.
 *
 * <p><b>ObjectNode 를 그대로 보관한다.</b> POJO 로 매핑하면 3단계가 필드를 더했을 때 낡은 화면의
 * read-modify-write 가 그 필드를 조용히 떨군다. {@link #parse(String)} 이 알지 못하는 최상위/
 * payload 필드도 이 클래스는 지우지 않고 그대로 들고 있다가 {@link #toJson()} 에서 되살린다.
 *
 * <p>{@code secret} 의 값은 <b>암호문</b>이다. 이 클래스는 복호화하지 않는다 — 복호화 책임을 한
 * 곳({@code AiCredentialService})에 묶어 평문이 도는 경로를 좁힌다. {@link #secretNames}
 * 만 예외적으로 {@code decrypt} 함수를 파라미터로 받는데, 이는 "값이 비어 있지 않은지" 판정에만
 * 쓰고 복호화된 값 자체는 반환하지 않는다.
 */
public final class AiCredentialDocument {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ObjectNode root;

  private AiCredentialDocument(ObjectNode root) {
    this.root = root;
  }

  /**
   * 저장된 JSON 문자열을 파싱한다. 알 수 없는 {@code agentType} 이어도 거부하지 않는다 —
   * fail-closed 는 이 문서를 실제로 사용하는 소비처(예: {@code resolve()})의 책임이다. 여기서
   * throw 하면 관리자가 GET/DELETE 화면으로 잘못된 값을 되돌릴 방법이 사라진다(롤백된 배포가
   * 남긴 행을 화면에서 고칠 수 없게 된다).
   */
  public static AiCredentialDocument parse(String json) {
    try {
      JsonNode node = MAPPER.readTree(json);
      if (!(node instanceof ObjectNode objectNode)) {
        throw new IllegalArgumentException("ai.credential JSON 은 객체여야 한다: " + json);
      }
      return new AiCredentialDocument(objectNode);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("ai.credential JSON 파싱에 실패했다: " + json, e);
    }
  }

  /** 새 자격증명 문서를 만든다. v=1 고정, payload/secret 은 빈 객체로 시작한다. */
  public static AiCredentialDocument empty(String agentType) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("v", 1);
    root.put("agentType", agentType);
    root.set("payload", MAPPER.createObjectNode());
    root.set("secret", MAPPER.createObjectNode());
    return new AiCredentialDocument(root);
  }

  /** 미해석 원본 문자열이다. 알려진 값인지 판별하고 거부하는 것은 이 클래스가 아니라 소비처의 책임이다. */
  public String agentType() {
    return root.path("agentType").asText("");
  }

  /**
   * payload 서브 객체를 반환한다. 필드가 없거나 객체가 아니면 빈 객체를 새로 만들어 root 에
   * 심어 두므로, 호출부가 반환값을 직접 mutate 해도 이 문서에 반영된다.
   */
  public ObjectNode payload() {
    return objectChild("payload");
  }

  /** secret 서브 객체. {@link #objectChild} 와 마찬가지로 없으면 처음 읽을 때 빈 객체를 만들어 root 에 심는다(getter 지만 트리를 mutate 한다). */
  private ObjectNode secret() {
    return objectChild("secret");
  }

  private ObjectNode objectChild(String field) {
    JsonNode child = root.get(field);
    if (child instanceof ObjectNode objectNode) {
      return objectNode;
    }
    ObjectNode created = MAPPER.createObjectNode();
    root.set(field, created);
    return created;
  }

  /**
   * 값이 실제로 있는 비밀의 이름만 반환한다. {@code decrypt} 로 복호화해 공백이 아닌 것만 센다.
   *
   * <p>지운 비밀은 (구현에 따라) 빈 문자열 암호문으로 남을 수 있다 — 암호문의 존재 여부만 보고
   * "설정됨"을 판정하면 지운 값도 설정된 것처럼 화면에 나타나 사용자가 있지도 않은 값을 믿게
   * 된다. 그래서 반드시 복호화한 뒤 빈 값을 걸러낸다.
   */
  public List<String> secretNames(UnaryOperator<String> decrypt) {
    List<String> names = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> fields = secret().fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String cipher = entry.getValue().asText("");
      if (!decrypt.apply(cipher).isEmpty()) {
        names.add(entry.getKey());
      }
    }
    return names;
  }

  /**
   * 이름으로 저장된 암호문 하나를 꺼낸다(없으면 빈 문자열). {@code AiCredentialService.resolve()}
   * 가 개별 필드를 복호화해 타입 있는 record 로 바꾸려면 이 통로가 필요하다 — {@link #secretNames}
   * 가 이름 목록만 내주는 것과 짝을 이루는, 이름 하나로 좁힌 접근이다. 이 메서드도 복호화하지
   * 않는다(값을 그대로 돌려준다) — "복호화는 {@code AiCredentialService} 한 곳에서만" 이라는
   * 클래스 상단 계약은 그대로다.
   *
   * <p><b>public 이다.</b> 유일한 소비처가 다른 패키지({@code settings.service})에 있어
   * package-private 으로는 호출할 수 없다. {@link #payload()}/{@link #agentType()}/
   * {@link #secretNames} 등 기존 공개 접근자와 같은 노출 수준이고, 이전에 서비스가 이 값을 얻으려
   * 썼던 {@code toJson()} 재파싱(문서 전체를 문자열로 넘기는 것)보다 오히려 노출 폭이 좁다.
   */
  public String secretCipher(String name) {
    return secret().path(name).asText("");
  }

  /**
   * 비밀 하나를 채운다(이미 있으면 덮어쓴다). {@code cipher} 는 호출부가 이미 암호화한 값이어야
   * 한다.
   *
   * <p><b>{@code with} 접두사가 붙었지만 불변 복사본을 만들지 않는다.</b> 이 문서(수신자) 를
   * 그 자리에서 mutate 하고 {@code this} 를 그대로 반환한다 — record 의 {@code with} 나
   * {@code @With} 가 주는 "새 인스턴스" 인상과 다르니 주의. 2단계가 체이닝
   * ({@code doc.withSecret(...).withSecret(...)}) 을 쓰더라도 실제로는 같은 인스턴스를 계속
   * 고치는 것이다.
   */
  public AiCredentialDocument withSecret(String name, String cipher) {
    secret().put(name, cipher);
    return this;
  }

  /** 저장용 직렬화. 모르는 필드를 포함해 root 에 있는 모든 필드를 그대로 되살린다. */
  public String toJson() {
    return root.toString();
  }
}
