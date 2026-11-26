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

  /** 마지막 헬스체크 결과(상태·시각·지연·오류)를 NULL 로 지운다(WD-753 — 헬스체크 경로를 비운 연결). */
  int clearHealth(@Param("id") Long id);

  /** 헬스체크 경로가 있는 연결 목록. */
  List<HealthCheckTarget> selectHealthCheckable();

  /**
   * 같은 이름의 연결 수. ws·sp 는 TRANSLATE 의 유니코드 공백 목록과 같은 길이의 ASCII 공백 목록(WD-1129,
   * TextRules.EDGE_SPACE_CHARS·EDGE_SPACE_AS_ASCII).
   */
  long countByName(@Param("name") String name, @Param("ws") String ws, @Param("sp") String sp);

  /** 자기 자신을 뺀 같은 이름의 연결 수. ws·sp 의미는 {@link #countByName}. */
  long countByNameExcludingId(
      @Param("name") String name,
      @Param("excludeId") Long excludeId,
      @Param("ws") String ws,
      @Param("sp") String sp);

  /**
   * 모든 연결의 이름 목록(excludeId 가 있으면 그 행 제외, WD-1172). 비 PG(Tibero)의 이름 중복 판정용 — 유니코드 값을 바인딩하지 않고 Java
   * 에서 비교한다.
   */
  List<String> selectNamesExcludingId(@Param("excludeId") Long excludeId);

  /** 행 잠금(FOR UPDATE)으로 저장된 이름을 읽는다 — 없으면 null(WD-1107). */
  String selectNameForUpdate(@Param("id") Long id);

  /** id 로 보이는 연결 수. */
  long countById(@Param("id") Long id);

  /** id 로 지우고 지운 행 수. */
  int deleteById(@Param("id") Long id);

  /** 이 연결을 쓰는 스텝이 속한 파이프라인(중복 제거). */
  List<ReferencingPipeline> selectReferencingPipelines(@Param("apiConnectionId") Long id);
}
