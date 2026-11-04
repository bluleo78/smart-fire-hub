package com.smartfirehub.job.repository;

import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 범용 비동기 작업(async_job) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqAsyncJobRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AsyncJobRepository {

  /** 비동기 작업을 새로 등록한다(초기 단계는 PENDING, 진행률 0). */
  void insert(
      String id,
      String jobType,
      String resource,
      String resourceId,
      Long userId,
      Map<String, Object> metadata);

  /**
   * 데이터셋 대상 작업(resource = 'dataset')의 데이터셋 id. 내보내기 파일 다운로드 시점 재판정(스펙 §4.4)에 쓴다. 다른 자원의 작업(임포트 외
   * API 연결 등)이거나 id 가 숫자가 아니면 빈 값 — 엉뚱한 데이터셋으로 판정하거나 500 이 나지 않게.
   */
  Optional<Long> findDatasetResourceId(String jobId);

  /** id 로 작업 상태 한 건을 조회한다. 없으면 빈 값. */
  Optional<AsyncJobStatusResponse> findById(String id);

  /** 작업의 단계·진행률·메시지·메타데이터를 갱신한다. */
  void updateStageAndProgress(
      String id, String stage, int progress, String message, Map<String, Object> metadata);

  /**
   * 작업 실패 상태로 업데이트. progress도 함께 저장하여 UI에서 마지막 진행률을 표시할 수 있도록 한다.
   *
   * @param id 작업 ID
   * @param stage 새 단계 (예: "FAILED")
   * @param progress 마지막으로 알려진 진행률 (0~100)
   * @param errorMessage 오류 메시지
   */
  void updateStageAndError(String id, String stage, int progress, String errorMessage);

  /** 같은 자원(유형·자원 종류·자원 id)에 대해 아직 끝나지 않은 작업 목록을 조회한다. */
  List<AsyncJobStatusResponse> findActiveByResource(
      String jobType, String resource, String resourceId);

  /** 사용자가 시작한 특정 유형의 아직 끝나지 않은 작업 목록을 조회한다. */
  List<AsyncJobStatusResponse> findActiveByUserAndJobType(Long userId, String jobType);

  /** 기준 시각 이전에 갱신이 멈춘 채 끝나지 않은 작업(정체 작업) 목록을 조회한다. */
  List<AsyncJobStatusResponse> findStaleJobs(LocalDateTime before);

  /** 기준 시각보다 오래된 작업 행을 삭제하고 삭제 건수를 돌려준다. */
  int deleteOlderThan(LocalDateTime before);

  /** 작업을 시작한 사용자 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findOwnerById(String id);
}
