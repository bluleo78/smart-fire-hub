package com.smartfirehub.job.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * async_job MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/job/AsyncJobMapper.xml}.
 *
 * <p>async_job 은 표준 테넌트 정책 대상이다 — 테넌트 컨텍스트가 심긴 트랜잭션 안에서만 행이 보이고, 컨텍스트가 없으면 0행이다. 문장은 tenant_id 를 쓰지
 * 않는다(INSERT 는 열 DEFAULT 가 채운다).
 */
@PlatformMapper
public interface AsyncJobMapper {

  /** 새 작업(PENDING, 진행률 0). metadataJson 은 이미 직렬화된 JSON 문자열(빈 맵이면 "{}"). */
  void insert(
      @Param("id") String id,
      @Param("jobType") String jobType,
      @Param("resource") String resource,
      @Param("resourceId") String resourceId,
      @Param("userId") Long userId,
      @Param("metadataJson") String metadataJson);

  /** resource = 'dataset' 인 작업의 resource_id 원값. 없으면 null. */
  String selectDatasetResourceId(@Param("id") String id);

  /** id 로 한 행. 없으면 null. */
  AsyncJobRow selectById(@Param("id") String id);

  /** 단계·진행률·메시지·메타데이터를 바꾸고 updated_at 을 now 로 둔다. */
  int updateStageAndProgress(
      @Param("id") String id,
      @Param("stage") String stage,
      @Param("progress") int progress,
      @Param("message") String message,
      @Param("metadataJson") String metadataJson,
      @Param("now") LocalDateTime now);

  /** 단계·진행률·오류 메시지를 바꾸고 updated_at 을 now 로 둔다. */
  int updateStageAndError(
      @Param("id") String id,
      @Param("stage") String stage,
      @Param("progress") int progress,
      @Param("errorMessage") String errorMessage,
      @Param("now") LocalDateTime now);

  /** 같은 자원의 끝나지 않은 작업. */
  List<AsyncJobRow> selectActiveByResource(
      @Param("jobType") String jobType,
      @Param("resource") String resource,
      @Param("resourceId") String resourceId);

  /** 사용자·유형의 끝나지 않은 작업. */
  List<AsyncJobRow> selectActiveByUserAndJobType(
      @Param("userId") Long userId, @Param("jobType") String jobType);

  /** updated_at &lt; before 인 끝나지 않은 작업. */
  List<AsyncJobRow> selectStale(@Param("before") LocalDateTime before);

  /** created_at &lt; before 인 끝난 작업을 지운다. */
  int deleteOlderThan(@Param("before") LocalDateTime before);

  /** 작업을 시작한 사용자 id. 없으면 null. */
  Long selectOwnerById(@Param("id") String id);
}
