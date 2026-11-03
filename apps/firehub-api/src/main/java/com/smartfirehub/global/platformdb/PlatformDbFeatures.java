package com.smartfirehub.global.platformdb;

/**
 * 현재 플랫폼 DB 가 선택 기능을 지원하는지 알려 주는 포트.
 *
 * <p>구현체는 {@code global/platformdb/<백엔드>/} 에 둔다. 꺼진 기능은 서비스가 해당 단계만 건너뛰는 방식으로 처리한다(예: 검색은 그 단계를 빈
 * 목록으로 취급, 적재는 벡터 쓰기만 생략). 5xx 로 터뜨리지 않는다.
 *
 * <p>아직 가드가 없는 벡터 테이블 읽기·상태 경로: DocumentChunkController, EmbeddingStatusService,
 * EmbeddingBacklogService, PgRowSearchIndex — 해당 리포지토리를 인터페이스로 바꿀 때 함께 처리해야 한다.
 */
public interface PlatformDbFeatures {

  /**
   * @param feature 확인할 기능
   * @return 현재 플랫폼 DB 가 그 기능을 지원하면 true
   */
  boolean supports(PlatformDbFeature feature);
}
