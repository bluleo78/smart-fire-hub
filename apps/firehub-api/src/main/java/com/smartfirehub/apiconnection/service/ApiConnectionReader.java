package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ApiConnectionService} 의 조회 전용 협력자.
 *
 * <p><b>왜 별도 빈인가</b>: {@code testConnection} 은 외부 HTTP 를 트랜잭션 밖에서 수행해야 해서
 * 무애노테이션으로 남겨야 하는데, 그 안에서 조회 메서드를 {@code this.getById(...)} 로 자기호출하면
 * Spring AOP 프록시가 우회돼 서비스 레이어 트랜잭션이 열리지 않는다. RLS 하에서 GUC 는 트랜잭션이
 * 열릴 때만 주입되므로, 조회가 트랜잭션 경계를 갖지 못하면 조용히 0행이 될 위험을 구조적으로
 * 안고 가게 된다. 조회부를 별도 빈으로 분리해야 호출이 실제로 프록시를 통과한다.
 *
 * <p>여기에는 <b>조회·복호화·마스킹만</b> 둔다. 외부 HTTP 호출은 여전히 {@code ApiConnectionService}
 * 쪽 트랜잭션 밖에 남아 있어야 하며, 이 클래스로 들어와서는 안 된다(HTTP 5초를 트랜잭션이 물면
 * 커넥션 풀이 고갈된다).
 */
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

  /** 단건 조회 — authConfig 는 마스킹된 형태로 반환한다. */
  public ApiConnectionResponse getById(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    return toResponse(record);
  }

  /**
   * 복호화된 authConfig 를 반환한다. 인증 헤더/쿼리 파라미터 조립에 쓰이므로 마스킹하지 않는다.
   *
   * <p>authType 은 별도 컬럼이라 복호화된 Map 에 들어있지 않다. 호출자(Preview/Executor 등)가
   * authType 으로 분기(API_KEY + placement=query 등)할 수 있도록 함께 합쳐 반환한다. (#113)
   */
  public Map<String, String> getDecryptedAuthConfig(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    String encryptedConfig = record.get(field(name("api_connection", "auth_config"), String.class));
    Map<String, String> config = decryptToMap(encryptedConfig);
    String authType = record.get(field(name("api_connection", "auth_type"), String.class));
    if (authType != null) {
      config = new HashMap<>(config);
      config.putIfAbsent("authType", authType);
    }
    return config;
  }

  /**
   * jOOQ Record → 응답 DTO 변환. 목록 조회(ApiConnectionService.getAll)도 같은 변환이 필요하므로
   * 공개한다 — 서비스에 복호화/마스킹 로직을 중복시키지 않기 위함이다.
   */
  public ApiConnectionResponse toResponse(Record r) {
    String encryptedConfig = r.get(field(name("api_connection", "auth_config"), String.class));
    Map<String, String> plainConfig = decryptToMap(encryptedConfig);
    Map<String, String> masked = maskAuthConfig(plainConfig);

    return new ApiConnectionResponse(
        r.get(field(name("api_connection", "id"), Long.class)),
        r.get(field(name("api_connection", "name"), String.class)),
        r.get(field(name("api_connection", "description"), String.class)),
        r.get(field(name("api_connection", "auth_type"), String.class)),
        masked,
        r.get(field(name("api_connection", "base_url"), String.class)),
        r.get(field(name("api_connection", "health_check_path"), String.class)),
        r.get(field(name("api_connection", "last_status"), String.class)),
        r.get(field(name("api_connection", "last_checked_at"), LocalDateTime.class)),
        r.get(field(name("api_connection", "last_latency_ms"), Long.class)),
        r.get(field(name("api_connection", "last_error_message"), String.class)),
        r.get(field(name("api_connection", "created_by"), Long.class)),
        r.get(field(name("api_connection", "created_at"), LocalDateTime.class)),
        r.get(field(name("api_connection", "updated_at"), LocalDateTime.class)));
  }

  /** 암호문을 복호화해 Map 으로 되돌린다. */
  private Map<String, String> decryptToMap(String encryptedConfig) {
    try {
      String json = encryptionService.decrypt(encryptedConfig);
      return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException e) {
      throw new ApiConnectionException("Failed to deserialize authConfig: " + e.getMessage());
    }
  }

  /** 민감 키(apiKey/token/secret/password 등)의 값을 가려 응답에 노출하지 않는다. */
  private Map<String, String> maskAuthConfig(Map<String, String> authConfig) {
    Map<String, String> masked = new HashMap<>();
    for (Map.Entry<String, String> entry : authConfig.entrySet()) {
      String key = entry.getKey().toLowerCase();
      boolean isSensitive = SENSITIVE_KEY_PARTS.stream().anyMatch(key::contains);
      masked.put(
          entry.getKey(),
          isSensitive ? encryptionService.maskValue(entry.getValue()) : entry.getValue());
    }
    return masked;
  }
}
