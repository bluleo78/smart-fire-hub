package com.smartfirehub.vectorstore;

/**
 * 설치본의 벡터 저장소 종류(ADR 0002 결정 1). 설치할 때 {@value #PROPERTY} 로 고르고, 기본값은 {@link #PLATFORM} 이다.
 *
 * <p>플랫폼 DB 종류({@code firehub.platform-db.type})와 따로 고르는 값이라 PG/Tibero × platform/qdrant 네 조합이 나온다.
 * 벡터·키워드 색인 구현 빈은 이 값과 플랫폼 DB 종류를 함께 보고 고른다(platform+postgres → pgvector, platform+tibero → 꺼짐).
 */
public enum VectorStoreType {
  /** 플랫폼 DB 안에 둔다. PostgreSQL 이면 pgvector·pg_trgm 을 쓰고 Tibero 면 꺼진다. 속성이 없으면 이 값이다. */
  PLATFORM,
  /**
   * 별도 Qdrant 에 둔다(ADR 0002). 데이터셋·문서 청크의 벡터·키워드 검색이 플랫폼 DB 종류와 무관하게 켜진다(WD-720). 행 검색은 Qdrant 행
   * 색인(WD-722) 전까지 꺼진다.
   */
  QDRANT;

  /** 벡터 저장소 종류를 고르는 설정 키. 값은 {@code platform} 또는 {@code qdrant}. */
  public static final String PROPERTY = "firehub.vector-store.type";

  /**
   * 설정값을 종류로 바꾼다. 속성이 아예 없을 때만 {@link #PLATFORM} 이다.
   *
   * <p>플랫폼 DB 종류와 같은 규칙으로 빈 값·앞뒤 공백은 받지 않는다. 구현 빈을 고르는 {@code @ConditionalOnProperty} 는 값을 다듬지 않고
   * 대소문자만 무시한다. 여기서 더 너그럽게 받으면 검사는 통과하는데 어느 구현도 등록되지 않는 어긋남이 생긴다.
   *
   * @throws IllegalStateException 알 수 없는 값일 때
   */
  public static VectorStoreType resolve(String raw) {
    if (raw == null) {
      return PLATFORM;
    }
    for (VectorStoreType type : values()) {
      if (type.name().equalsIgnoreCase(raw)) {
        return type;
      }
    }
    throw new IllegalStateException(
        PROPERTY + " 값은 platform 또는 qdrant 여야 합니다(앞뒤 공백 없이): '" + raw + "'");
  }
}
