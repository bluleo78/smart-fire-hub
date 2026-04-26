package com.smartfirehub.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AsyncJobCleanupService 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 Mockito만으로 스케줄러 정리 로직을 검증한다. @Scheduled는 단위 테스트에서 실행되지 않으므로 메서드를 직접 호출한다.
 */
@ExtendWith(MockitoExtension.class)
class AsyncJobCleanupServiceTest {

  /** AsyncJobRepository 모킹 — DB 접근 없이 반환값 제어 */
  @Mock private AsyncJobRepository asyncJobRepository;

  /** AsyncJobService 모킹 — failJob 호출 검증 */
  @Mock private AsyncJobService asyncJobService;

  /** 테스트 대상 서비스 */
  @InjectMocks private AsyncJobCleanupService asyncJobCleanupService;

  // =========================================================================
  // failStaleJobs — 스테일 잡 실패 처리
  // =========================================================================

  /**
   * 정상: 스테일 잡이 있으면 각 잡에 대해 failJob이 호출되어야 한다. findStaleJobs가 2개를 반환하면 asyncJobService.failJob이 2번
   * 호출된다.
   */
  @Test
  void failStaleJobs_withStaleJobs_callsFailJobForEach() {
    AsyncJobStatusResponse job1 = makeJob("job-1", "IMPORT", "PROCESSING");
    AsyncJobStatusResponse job2 = makeJob("job-2", "EXPORT", "RUNNING");
    when(asyncJobRepository.findStaleJobs(any(LocalDateTime.class)))
        .thenReturn(List.of(job1, job2));

    asyncJobCleanupService.failStaleJobs();

    verify(asyncJobService, times(2)).failJob(anyString(), contains("timed out"));
  }

  /** 정상: failJob 호출 시 각 잡의 jobId가 정확하게 전달되어야 한다. */
  @Test
  void failStaleJobs_withStaleJobs_passesCorrectJobIds() {
    AsyncJobStatusResponse job = makeJob("specific-job-id", "IMPORT", "PROCESSING");
    when(asyncJobRepository.findStaleJobs(any(LocalDateTime.class))).thenReturn(List.of(job));

    asyncJobCleanupService.failStaleJobs();

    ArgumentCaptor<String> jobIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(asyncJobService).failJob(jobIdCaptor.capture(), anyString());
    assertThat(jobIdCaptor.getValue()).isEqualTo("specific-job-id");
  }

  /** 엣지 케이스: 스테일 잡이 없으면 asyncJobService와 상호작용이 없어야 한다. 조기 반환 경로 검증. */
  @Test
  void failStaleJobs_noStaleJobs_noFailJobCalled() {
    when(asyncJobRepository.findStaleJobs(any(LocalDateTime.class))).thenReturn(List.of());

    asyncJobCleanupService.failStaleJobs();

    verifyNoInteractions(asyncJobService);
  }

  /**
   * 정상: findStaleJobs 호출 시 현재 시각 기준 30분 이전 임계값이 사용되어야 한다. 임계값이 현재 시각보다 이전이고 현재 시각으로부터 30~31분 이내임을
   * 검증한다.
   */
  @Test
  void failStaleJobs_usesThirtyMinuteThreshold() {
    when(asyncJobRepository.findStaleJobs(any(LocalDateTime.class))).thenReturn(List.of());

    LocalDateTime before = LocalDateTime.now();
    asyncJobCleanupService.failStaleJobs();
    LocalDateTime after = LocalDateTime.now();

    ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(asyncJobRepository).findStaleJobs(thresholdCaptor.capture());

    LocalDateTime threshold = thresholdCaptor.getValue();
    // 임계값이 30분 전 ± 1분 범위 내에 있어야 함
    assertThat(threshold).isBefore(before.minusMinutes(29));
    assertThat(threshold).isAfter(after.minusMinutes(31));
  }

  // =========================================================================
  // deleteOldJobs — 오래된 잡 삭제
  // =========================================================================

  /**
   * 정상: deleteOlderThan이 삭제 건수를 반환하면 정상 종료되어야 한다. asyncJobRepository.deleteOlderThan이 1번 호출됨을 검증한다.
   */
  @Test
  void deleteOldJobs_callsRepositoryDeleteOlderThan() {
    when(asyncJobRepository.deleteOlderThan(any(LocalDateTime.class))).thenReturn(5);

    asyncJobCleanupService.deleteOldJobs();

    verify(asyncJobRepository).deleteOlderThan(any(LocalDateTime.class));
  }

  /** 정상: deleteOlderThan에 현재 시각 기준 30일 이전 임계값이 사용되어야 한다. */
  @Test
  void deleteOldJobs_usesThirtyDayRetention() {
    when(asyncJobRepository.deleteOlderThan(any(LocalDateTime.class))).thenReturn(0);

    LocalDateTime before = LocalDateTime.now();
    asyncJobCleanupService.deleteOldJobs();
    LocalDateTime after = LocalDateTime.now();

    ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(asyncJobRepository).deleteOlderThan(thresholdCaptor.capture());

    LocalDateTime threshold = thresholdCaptor.getValue();
    // 임계값이 30일 전 ± 1분 범위 내에 있어야 함
    assertThat(threshold).isBefore(before.minusDays(30).plusMinutes(1));
    assertThat(threshold).isAfter(after.minusDays(30).minusMinutes(1));
  }

  /** 엣지 케이스: 삭제 건수가 0이면 asyncJobService 상호작용 없이 정상 종료되어야 한다. */
  @Test
  void deleteOldJobs_nothingDeleted_noException() {
    when(asyncJobRepository.deleteOlderThan(any(LocalDateTime.class))).thenReturn(0);

    asyncJobCleanupService.deleteOldJobs();

    verifyNoInteractions(asyncJobService);
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /** 테스트용 AsyncJobStatusResponse 생성 헬퍼. */
  private AsyncJobStatusResponse makeJob(String jobId, String jobType, String stage) {
    return new AsyncJobStatusResponse(
        jobId, jobType, stage, 0, null, null, null, LocalDateTime.now(), LocalDateTime.now(), 1L);
  }
}
