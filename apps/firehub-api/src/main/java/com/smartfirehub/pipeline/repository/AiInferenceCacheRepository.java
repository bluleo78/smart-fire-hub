package com.smartfirehub.pipeline.repository;

import java.util.List;
import java.util.Optional;

/**
 * AI 추론 캐시(ai_inference_cache) 접근 리포지토리 — AI_CLASSIFY 실행기가 행 내용 해시별 분류 결과를 조회·저장한다.
 *
 * <p>구현은 {@code JooqAiInferenceCacheRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AiInferenceCacheRepository {

  /** 캐시에 넣을 한 건 — 행 내용 해시와 분류 결과 JSON 문자열. */
  record Entry(String rowHash, String resultJson) {}

  /**
   * 행 해시·프롬프트 버전·테넌트로 캐시된 분류 결과 JSON 을 조회한다. 없거나 결과 컬럼이 null 이면 비어 돌아온다(= 캐시 미스).
   *
   * @return 캐시된 결과 JSON 문자열
   */
  Optional<String> findResultJson(String rowHash, String promptVersion, Long tenantId);

  /**
   * 캐시 항목들을 다중 VALUES 한 문장으로 넣는다. 이미 있는 키는 건드리지 않는다.
   *
   * <p>tenant_id 는 일부러 세팅하지 않는다 — GUC 파생 DEFAULT(V103)가 채운다. 앱이 직접 싣는 유일한 지점이 되면 GUC 와 어긋날 여지가 생기고,
   * 이 저장소의 다른 테넌트 테이블 선례(MappingRepository, DatasetOntologyRepository)와도 달라진다. {@code
   * onConflictDoNothing()} 은 conflict target 이 없는 형태라 인덱스 추론을 하지 않는다 — V103 의 유니크 접기로 깨질 지점이 아니다.
   *
   * @param promptVersion 모든 항목에 공통인 프롬프트 버전(해시)
   * @param entries 넣을 항목(비어 있으면 호출하지 않는다)
   */
  void insertIgnoringConflicts(String promptVersion, List<Entry> entries);
}
