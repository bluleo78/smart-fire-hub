package com.smartfirehub.job.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.job.repository.mybatis.AsyncJobMapper;
import com.smartfirehub.job.repository.mybatis.AsyncJobRow;
import com.smartfirehub.job.repository.mybatis.MyBatisAsyncJobRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DuplicateKeyException;

/**
 * AsyncJobRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(구현의 클래스 레벨 {@code @Transactional} 을 하네스 프록시가 그대로 읽어 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로
 * 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisAsyncJobRepositoryContractTest extends AsyncJobRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private AsyncJobRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            AsyncJobRepository.class,
            new MyBatisAsyncJobRepository(poc.mapper(AsyncJobMapper.class), new ObjectMapper()));
  }

  @Override
  protected AsyncJobRepository repository() {
    return repository;
  }

  @Override
  protected boolean hasRawMapper() {
    return true;
  }

  /** 런타임 계정 매퍼를 트랜잭션 없이 부른다 — 하네스 매니저를 거치지 않아 set_tenant 가 돌지 않는다. */
  @Override
  protected AsyncJobRow rawSelectByIdWithoutTransaction(String id) {
    return poc.mapper(AsyncJobMapper.class).selectById(id);
  }

  /**
   * DataImportService 의 409 판별(진행 중 임포트 고유 인덱스 충돌)이 Tibero 에서도 맞는지 고정한다 — 그 서비스는 원인 체인에서 제약 이름
   * {@code idx_async_job_active_unique} 를 {@code PlatformDbErrors.violatesConstraintOnCauseChain} 로
   * 찾는다. Tibero 유일 위반 메시지의 ('스키마'.'제약') 에서 이름을 뽑으므로 함수 기반 유일 인덱스 이름도 그대로 나와야 한다.
   */
  @Test
  @DisplayName(
      "Tibero 진행 중 임포트 중복의 예외 체인에서 idx_async_job_active_unique 를 알아본다(DataImportService 409 판별)")
  void activeImportConflict_recognizedByConstraintName() {
    long tenant = fx.createTenant();
    long user = fx.createUser(prefix + "-ci", null);
    String resourceId = prefix + "-ci";
    fx.inTenant(
        tenant,
        () -> {
          repository.insert(
              UUID.randomUUID().toString(), "DATA_IMPORT", "dataset", resourceId, user, null);
          return null;
        });

    Throwable thrown =
        catchThrowable(
            () ->
                fx.inTenant(
                    tenant,
                    () -> {
                      repository.insert(
                          UUID.randomUUID().toString(),
                          "DATA_IMPORT",
                          "dataset",
                          resourceId,
                          user,
                          null);
                      return null;
                    }));

    assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
    assertThat(
            new TiberoPlatformDbErrors()
                .violatesConstraintOnCauseChain(thrown, "idx_async_job_active_unique"))
        .as("제약 이름 판별 — 메시지: %s", thrown.getMessage())
        .isTrue();
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
