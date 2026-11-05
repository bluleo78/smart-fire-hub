package com.smartfirehub.global.platformdb.tibero.flyway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.BackgroundJobServerConfiguration;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;

/**
 * JobRunr 작업 등록 → 실행(실패) → 재시도 → 성공 1회전을 저장소에 상관없이 똑같이 돌리는 시험 도구(WD-488).
 *
 * <p>Tibero(OracleStorageProvider)와 PostgreSQL(대조군)에 같은 코드를 돌려, Tibero 에서 본 "FAILED 저장이 갱신 0건으로
 * 실패하고 고아 감지로 넘어가는" 현상이 Tibero 고유인지 가른다. 워커 1개·폴링 5초(최소값)로 공유 서버 세션을 아낀다.
 */
final class JobRunrRetryCycle {

  /** 한 작업의 결과. firstFailure 는 첫 FAILED 상태의 예외 — 의도한 예외인지 JobRunr 고아 감지인지 가른다. */
  record Outcome(String label, StateName state, int attempts, String firstFailure, String history) {
    /** 첫 실패가 작업이 던진 의도한 예외로 기록됐으면 true(FAILED 저장이 성공했다는 뜻). */
    boolean failureRecordedAsThrown() {
      return firstFailure.contains("WD-488 의도한 첫 실패");
    }

    @Override
    public String toString() {
      return label
          + " state="
          + state
          + " attempts="
          + attempts
          + " firstFailure="
          + firstFailure
          + " history="
          + history;
    }
  }

  private JobRunrRetryCycle() {}

  /**
   * 서버를 띄우고 두 작업을 돌린다: ① 기동 직후 등록·즉시 실패 ② 15초 뒤 등록·3초 뒤 실패. 각 작업이 SUCCEEDED 가 되거나 제한 시간이 지날 때까지
   * 기다린다. 서버는 반드시 멈춘다.
   */
  static List<Outcome> run(StorageProvider storage) throws InterruptedException {
    JacksonJsonMapper json = new JacksonJsonMapper();
    storage.setJobMapper(new JobMapper(json));
    BackgroundJobServer server =
        new BackgroundJobServer(
            storage,
            json,
            null,
            BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration()
                .andWorkerCount(1)
                .andPollInterval(Duration.ofSeconds(5)));
    List<Outcome> out = new ArrayList<>();
    try {
      server.start();
      JobScheduler scheduler = new JobScheduler(storage);
      String early = UUID.randomUUID().toString();
      JobId earlyId = scheduler.enqueue(() -> TiberoPocFlakyJob.run(early));
      Thread.sleep(15_000);
      String late = UUID.randomUUID().toString();
      JobId lateId = scheduler.enqueue(() -> TiberoPocFlakyJob.run(late, 3_000));
      out.add(outcome("early", early, await(storage, earlyId)));
      out.add(outcome("late", late, await(storage, lateId)));
    } finally {
      server.stop();
    }
    return out;
  }

  private static Job await(StorageProvider storage, JobId id) throws InterruptedException {
    Job job = storage.getJobById(id);
    long deadline = System.nanoTime() + Duration.ofSeconds(240).toNanos();
    while (job.getState() != StateName.SUCCEEDED && System.nanoTime() < deadline) {
      Thread.sleep(1000);
      job = storage.getJobById(id);
    }
    return job;
  }

  private static Outcome outcome(String label, String key, Job job) {
    var attempts = TiberoPocFlakyJob.ATTEMPTS.get(key);
    String firstFailure =
        job.getJobStates().stream()
            .filter(FailedState.class::isInstance)
            .map(FailedState.class::cast)
            .map(f -> f.getExceptionType() + ": " + f.getExceptionMessage())
            .findFirst()
            .orElse("none");
    String history =
        job.getJobStates().stream().map(s -> s.getName().name()).collect(Collectors.joining("→"));
    return new Outcome(
        label, job.getState(), attempts == null ? 0 : attempts.get(), firstFailure, history);
  }
}
