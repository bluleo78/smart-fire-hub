package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import java.util.List;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.oracle.OracleStorageProvider;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.Test;

/**
 * JobRunr 저장소가 <b>런타임 계정</b>(운영 앱 풀과 같은 {@code CURRENT_SCHEMA = 소유자})으로 기동·잡 등록·조회를 하는지 실서버로 본다
 * (WD-706, WD-492 에서 넘겨받은 일).
 *
 * <p>왜: 운영 JobRunr 전용 풀은 업무 풀의 세션 초기화(소유자 {@code CURRENT_SCHEMA})를 물려받고 {@code skip-create} 다. 테이블은
 * 소유자 스키마에 있고 런타임 계정에는 객체가 하나도 없으므로, JobRunr 가 테이블 확인을 사용자 스키마 메타데이터로 하면 기동이 깨진다. 설정은 운영과 같게 둔다 —
 * {@code TiberoPlatformDbConfig.storageProvider} 와 같은 생성자, 테이블 접두사는 {@code application.yml} 에 없어
 * null, {@code SKIP_CREATE}.
 */
@TiberoLive
class TiberoJobRunrRuntimeAccountLiveTest {

  /** {@code org.jobrunr.database.table-prefix} — application.yml 에 없다. */
  private static final String TABLE_PREFIX = null;

  @Test
  void runtimeAccountSavesAndReadsJob() {
    TiberoPlatformSchema poc = TiberoPlatformSchema.shared();
    OracleStorageProvider storage =
        new OracleStorageProvider(poc.dataSource(), TABLE_PREFIX, DatabaseOptions.SKIP_CREATE);
    storage.setJobMapper(new JobMapper(new JacksonJsonMapper()));

    Job job =
        new Job(
            new JobDetails(
                TiberoJobRunrRuntimeAccountLiveTest.class.getName(), null, "noop", List.of()),
            new EnqueuedState());
    // 저장이 실패하면 지울 것이 없다 — 정리 예외가 원래 실패를 가리지 않게 try 밖에 둔다.
    storage.save(job);
    try {
      Job read = storage.getJobById(job.getId());
      assertThat(read.getId()).isEqualTo(job.getId());
      assertThat(read.getState()).isEqualTo(StateName.ENQUEUED);
      assertThat(storage.countJobs(StateName.ENQUEUED)).isGreaterThanOrEqualTo(1);
    } finally {
      storage.deletePermanently(job.getId());
    }
  }

  /** 잡 본문 자리 — 저장·조회만 보므로 실행되지 않는다. */
  public static void noop() {}
}
