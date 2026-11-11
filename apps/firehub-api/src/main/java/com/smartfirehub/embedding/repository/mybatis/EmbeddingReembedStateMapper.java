package com.smartfirehub.embedding.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/**
 * embedding_reembed_state MyBatis 매퍼(WD-712). SQL 은 {@code
 * mybatis/embedding/EmbeddingReembedStateMapper.xml}.
 *
 * <p>테넌트 정책(RLS) 대상이고 PK 가 tenant_id 라 현재 테넌트에게 보이는 행은 많아야 하나다 — 문장은 tenant_id 를 쓰지 않는다(넣을 때는
 * DEFAULT 가 채우고, 읽기·수정은 정책이 좁힌다). 시각은 {@link Instant} 로 읽는다 — Tibero 는 전역 {@code
 * UtcInstantTypeHandler} 가 UTC 벽시계를, PG 는 기본 핸들러가 timestamptz 순간을 읽어 JVM 기본 시간대와 무관하다.
 */
@PlatformMapper
public interface EmbeddingReembedStateMapper {

  /**
   * 임대가 비었거나 만료됐으면 잡는다(PG INSERT … ON CONFLICT DO UPDATE … WHERE, Tibero MERGE … WHEN MATCHED
   * UPDATE … WHERE). 잡았으면 1, 유효한 임대가 있으면 0 을 돌려준다(RETURNING 대신 영향 행 수).
   */
  int tryAcquire(@Param("seconds") long seconds);

  /** 임대 만료를 DB 시계 + seconds 로 늘린다(조건 없음 — 행이 없으면 0). */
  int renewLease(@Param("seconds") long seconds);

  /** RUNNING + 공간(모델·차원) 기록 + 실패 사유 지움. */
  int markRunning(@Param("model") String model, @Param("dimension") int dimension);

  /** 상태·사유만 바꾼다(임대는 그대로). */
  int setStatus(@Param("status") String status, @Param("error") String error);

  /** 임대를 비운다(상태는 그대로). */
  int release();

  /** RUNNING 이고 임대가 DB 시계 기준 아직 유효한 행 수(0 또는 1). */
  int countActiveLease();

  /** FAILED 이고 그 뒤로 embedding.config 설정이 저장되지 않은 행 수(0 또는 1). */
  int countFailedSinceLastConfigSave();

  /** 현재 테넌트의 상태 행(없으면 null). */
  Row selectState();

  /** embedding_reembed_state 한 행의 원시 값. */
  record Row(String status, String model, Integer dimension, String lastError, Instant updatedAt) {}
}
