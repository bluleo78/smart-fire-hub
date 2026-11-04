package com.smartfirehub.audit.repository;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 감사 로그 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: {@code AuditLogService.log} 에는 트랜잭션이 없다. 로그인처럼 트랜잭션
 * 안에서 호출되는 경로는 편승하지만, 알림·비동기 내보내기 경로는 편승할 트랜잭션이 없어 RLS 정책 아래에서 tenant_id DEFAULT 가 NULL 이 되고 INSERT
 * 가 WITH CHECK 를 통과하지 못한다. 전파는 REQUIRED 이므로 기존 경로의 동작은 불변이다. (이슈 #384-3)
 *
 * <p>구현은 {@code JooqAuditLogRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AuditLogRepository {

  /** 감사 로그 한 건을 저장하고 생성된 id 를 돌려준다. */
  Long save(
      Long userId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      String ipAddress,
      String userAgent,
      String result,
      String errorMessage,
      String metadataJson);

  /** id 로 감사 로그 한 건을 조회한다. 없으면 빈 값. */
  Optional<AuditLogResponse> findById(Long id);

  /** 액션 유형·리소스 종류·리소스 id 가 모두 일치하는 감사 로그를 조회한다. */
  List<AuditLogResponse> findByResource(String actionType, String resource, String resourceId);

  /**
   * 감사 로그 전체 조회 (페이지네이션 + 복합 필터)
   *
   * @param search 사용자명/설명 검색어
   * @param userId 사용자 ID 정확 일치 필터 (null이면 무제한, #89)
   * @param actionType 액션 유형 필터
   * @param resource 리소스 유형 필터
   * @param result 결과(SUCCESS/FAILURE) 필터
   * @param startDate 날짜 범위 시작 (inclusive, null이면 무제한)
   * @param endDate 날짜 범위 종료 (inclusive, null이면 무제한)
   * @param page 페이지 번호 (0부터)
   * @param size 페이지 크기
   * @param datasetVisibility 데이터셋 열람 가시성({@code "dataset"} 테이블 기준으로 렌더). {@code Optional.empty()} 면
   *     데이터셋 필터를 붙이지 않아 모든 행을 본다(ADMIN 경로 — 삭제된 데이터셋의 행 포함). 값이 있으면 데이터셋 행은 그 조건을 만족하는 데이터셋의 행만
   *     남기고, 지금 없는(삭제된) 데이터셋의 행은 뺀다(WD-289)
   */
  PageResponse<AuditLogResponse> findAll(
      String search,
      Long userId,
      String actionType,
      String resource,
      String result,
      LocalDateTime startDate,
      LocalDateTime endDate,
      int page,
      int size,
      Optional<DatasetVisibility> datasetVisibility);

  /**
   * 플랫폼 감사 로그 조회(WD-4) — {@code tenant_id IS NULL} 행만.
   *
   * <p>RLS 형태 (b)(V99)는 GUC 가 없을 때 NULL 행만 보여 주지만, 이 조건을 쿼리에도 명시한다: 어떤 경로로든 GUC 가 남아 있으면 RLS 는 그
   * 테넌트 행만, 이 조건은 NULL 행만 고르므로 결과가 0행이 된다(fail-closed) — 테넌트 행이 새지 않는다.
   *
   * @param actor 행위자 username 부분일치(대소문자 무시). null/공백이면 무시
   * @param target 대상 — resource_id 정확 일치 또는 metadata.targetUsername·tenantSlug·tenantName 부분일치.
   *     null/공백이면 무시
   * @param actionType 액션 정확 일치. null/공백이면 무시
   * @param fromInclusive 이 시각 이상(null 이면 무제한)
   * @param toExclusive 이 시각 미만(null 이면 무제한)
   */
  PageResponse<AuditLogResponse> findPlatform(
      String actor,
      String target,
      String actionType,
      LocalDateTime fromInclusive,
      LocalDateTime toExclusive,
      int page,
      int size);
}
