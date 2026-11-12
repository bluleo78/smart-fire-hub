package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.AiInferenceCacheRepository.Entry;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * AI 추론 캐시(ai_inference_cache) MyBatis 매퍼(WD-708). SQL 은 {@code
 * mybatis/pipeline/AiInferenceCacheMapper.xml}. 문장 1:1 바인딩만 한다.
 */
@PlatformMapper
public interface AiInferenceCacheMapper {

  /** 캐시된 결과 JSON 문자열 — 없으면 null. 조회는 인터페이스가 받은 테넌트를 명시 술어로 건다(키가 행 내용 해시라 술어 없이는 테넌트 간 히트가 샌다). */
  String selectResultJson(
      @Param("rowHash") String rowHash,
      @Param("promptVersion") String promptVersion,
      @Param("tenantId") Long tenantId);

  /**
   * 한 건을 넣되 같은 (테넌트, 해시, 버전) 이 이미 있으면 건드리지 않는다 — 넣은 행 수(0 또는 1). PG 는 ON CONFLICT DO NOTHING,
   * Tibero 는 MERGE.
   */
  int insertIgnoring(
      @Param("rowHash") String rowHash,
      @Param("promptVersion") String promptVersion,
      @Param("resultJson") String resultJson);

  /**
   * 여러 건을 한 문장으로 넣되 이미 있는 (테넌트, 해시, 버전) 은 건드리지 않는다. entries 는 해시가 서로 다르고 500 건 이하여야 한다(구현이 맞춘다). PG
   * 는 다건 ON CONFLICT DO NOTHING, Tibero 는 DUAL UNION ALL 소스 MERGE.
   */
  int insertIgnoringBatch(
      @Param("promptVersion") String promptVersion, @Param("entries") List<Entry> entries);
}
