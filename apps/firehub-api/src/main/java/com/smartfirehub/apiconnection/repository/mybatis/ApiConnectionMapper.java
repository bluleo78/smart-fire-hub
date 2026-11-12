package com.smartfirehub.apiconnection.repository.mybatis;

import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ApiConnectionRow;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.HealthCheckTarget;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ReferencingPipeline;
import com.smartfirehub.apiconnection.repository.mybatis.ApiConnectionRows.ApiConnectionInsert;
import com.smartfirehub.apiconnection.repository.mybatis.ApiConnectionRows.PreviousStatus;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * API 연결(api_connection) MyBatis 매퍼(WD-708). SQL 은 {@code
 * mybatis/apiconnection/ApiConnectionMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface ApiConnectionMapper {

  /** 전체 목록(최신 생성순). */
  List<ApiConnectionRow> selectAll();

  /** id 한 건 — 없으면 null. */
  ApiConnectionRow selectById(@Param("id") Long id);

  /** 만든 사용자별 목록(정렬 없음 — jOOQ 판과 같다). */
  List<ApiConnectionRow> selectByCreatedBy(@Param("userId") Long userId);

  /** 한 건 삽입 — 생성 키는 홀더 id 에 채워진다. */
  int insert(ApiConnectionInsert row);

  /** null 인 항목은 건드리지 않는 부분 갱신. healthCheckPath 가 빈 문자열이면 NULL 로 지운다. */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("description") String description,
      @Param("authType") String authType,
      @Param("authConfig") String authConfig,
      @Param("baseUrl") String baseUrl,
      @Param("healthCheckPath") String healthCheckPath,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 행 잠금(FOR UPDATE)으로 직전 헬스 상태를 읽는다 — 행이 없으면 null. */
  PreviousStatus selectPreviousStatusForUpdate(@Param("id") Long id);

  /** 헬스체크 결과를 반영한다. 점검 시각은 자바 시각(jOOQ 판과 같다). */
  int updateHealth(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("checkedAt") LocalDateTime checkedAt,
      @Param("latencyMs") Long latencyMs,
      @Param("errorMessage") String errorMessage);

  /** 헬스체크 경로가 있는 연결 목록. */
  List<HealthCheckTarget> selectHealthCheckable();

  /** 같은 이름의 연결 수. */
  long countByName(@Param("name") String name);

  /** 자기 자신을 뺀 같은 이름의 연결 수. */
  long countByNameExcludingId(@Param("name") String name, @Param("excludeId") Long excludeId);

  /** id 로 보이는 연결 수. */
  long countById(@Param("id") Long id);

  /** id 로 지우고 지운 행 수. */
  int deleteById(@Param("id") Long id);

  /** 이 연결을 쓰는 스텝이 속한 파이프라인(중복 제거). */
  List<ReferencingPipeline> selectReferencingPipelines(@Param("apiConnectionId") Long id);
}
