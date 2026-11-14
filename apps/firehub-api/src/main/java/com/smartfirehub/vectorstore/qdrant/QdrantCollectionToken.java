package com.smartfirehub.vectorstore.qdrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 정해진 컬렉션에만 읽기·쓰기를 허용하는 Qdrant JWT 를 만든다(WD-722, 2026-11-14 사용자 결정 ③).
 *
 * <p>행 검색 색인은 테넌트별 컬렉션에 둔다(RDB 의 테넌트 스키마와 같은 수준). api 는 행 색인을 읽고 쓸 때 그 테넌트 컬렉션만 허용한 토큰을 쓴다. 그래서 앱
 * 필터에 버그가 있어도 다른 테넌트 컬렉션은 Qdrant 가 403 으로 거부한다(서버 강제). 컬렉션 만들기·지우기·목록은 전역 권한이 필요해 마스터 키로 한다(실측
 * 2026-11-14: 컬렉션 토큰으로 만들기·지우기 403, 목록은 허용된 것만 보임).
 *
 * <p>Qdrant 는 서버의 API 키로 서명한 HS256 토큰을 받는다(설정 {@code service.jwt_rbac: true}). 서명은 JDK HMAC 으로 직접
 * 한다 — jjwt 는 HS256 키가 32바이트보다 짧으면 거부하는데, Qdrant API 키에는 그런 제한이 없어 운영 키 길이에 따라 기동이 깨질 수 있다.
 *
 * <p>컬렉션 이름에는 와일드카드가 없다고 보고 정확한 이름만 넣는다.
 */
final class QdrantCollectionToken {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final String HEADER = encode(Map.of("alg", "HS256", "typ", "JWT"));

  private QdrantCollectionToken() {}

  /**
   * 토큰을 만든다.
   *
   * @param apiKey 서버 API 키(서명 키)
   * @param collections 읽기·쓰기를 허용할 컬렉션 이름
   * @param now 지금 시각
   * @param ttl 유효 기간
   */
  static String issue(String apiKey, Collection<String> collections, Instant now, Duration ttl) {
    List<Map<String, String>> access = new ArrayList<>();
    for (String c : collections) {
      access.add(Map.of("collection", c, "access", "rw"));
    }
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("exp", now.plus(ttl).getEpochSecond());
    claims.put("access", access);
    String unsigned = HEADER + "." + encode(claims);
    return unsigned + "." + B64.encodeToString(hmac(apiKey, unsigned));
  }

  private static String encode(Object value) {
    try {
      return B64.encodeToString(JSON.writeValueAsBytes(value));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Qdrant 토큰 직렬화 실패", e);
    }
  }

  private static byte[] hmac(String key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다", e);
    }
  }
}
