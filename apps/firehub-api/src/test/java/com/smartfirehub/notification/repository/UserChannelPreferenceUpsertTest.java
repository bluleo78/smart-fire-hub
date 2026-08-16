package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER_CHANNEL_PREFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code UserChannelPreferenceRepository.setEnabled} 의 upsert 경로(ON CONFLICT) 행위 검증.
 *
 * <p><b>왜 이 파일이 따로 있는가.</b> V106 이 {@code uk_preference} 를 {@code (tenant_id, user_id,
 * channel_type)} 으로 접었으므로, {@code ON CONFLICT} 의 컬럼 목록도 함께 접히지 않으면 PostgreSQL 이
 * {@code 42P10 there is no unique or exclusion constraint matching the ON CONFLICT specification}
 * 으로 죽는다. 그런데 기존 {@link UserChannelPreferenceConstraintTest} 는 CHECK 제약만 보고
 * {@code ON CONFLICT} 경로를 아예 타지 않아, 전체 스위트가 초록이어도 이 결함이 살아남았다.
 * 즉 이 파일은 "커버리지가 0이던 프로덕션 경로"를 처음으로 실행한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트 트랜잭션이 GUC 를
 * 공급해 버려서, 리포지토리가 스스로 트랜잭션을 열지 못하는 배선 결함(= {@code tenant_id} NOT NULL
 * 위반)까지 함께 가려진다. 검증 대상인 {@code setEnabled}/{@code isEnabled} 호출은 반드시 픽스처
 * 트랜잭션 <b>밖</b>에 둔다.
 */
class UserChannelPreferenceUpsertTest extends IntegrationTestBase {

  @Autowired private UserChannelPreferenceRepository repo;
  @Autowired private DSLContext dsl;

  private Long userId;

  @AfterEach
  void cleanUp() {
    // 공유 테스트 DB 라 이 테스트가 만든 행만 지운다. FK 역순: preference → user.
    inTenantFixture(
        () -> {
          if (userId != null) {
            dsl.deleteFrom(USER_CHANNEL_PREFERENCE)
                .where(USER_CHANNEL_PREFERENCE.USER_ID.eq(userId))
                .execute();
          }
          TenantRlsTestSupport.deleteUser(dsl, userId);
        });
  }

  /**
   * 같은 (tenant, user, channel) 로 {@code setEnabled} 를 두 번 부르면 두 번째가 갱신되어야 한다.
   *
   * <p>첫 호출은 INSERT 경로(= 리포지토리가 스스로 트랜잭션을 열어 GUC 를 받는지), 두 번째 호출은
   * {@code ON CONFLICT ... DO UPDATE} 경로(= 접힌 유니크와 컬럼 목록이 맞는지)를 각각 검증한다.
   * 접기 전 코드({@code .onConflict(USER_ID, CHANNEL_TYPE)})에서는 두 번째 호출이 42P10 으로 죽는다.
   */
  @Test
  void setEnabled_secondCallUpdatesInsteadOfFailing() {
    userId = inTenantFixture(() -> TenantRlsTestSupport.insertUser(dsl, "pref-upsert-"));

    // ── 검증 대상 프로덕션 호출: 픽스처 트랜잭션 밖 ──
    repo.setEnabled(userId, ChannelType.SLACK, false);
    repo.setEnabled(userId, ChannelType.SLACK, true);

    // upsert 이므로 행은 1개여야 하고(중복 INSERT 가 아니고), 값은 마지막 호출로 갱신돼야 한다.
    Integer rowCount =
        inTenantFixture(
            () ->
                dsl.selectCount()
                    .from(USER_CHANNEL_PREFERENCE)
                    .where(USER_CHANNEL_PREFERENCE.USER_ID.eq(userId))
                    .and(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE.eq(ChannelType.SLACK.name()))
                    .fetchOne(0, Integer.class));
    assertThat(rowCount).as("upsert 인데 행이 늘었다 — ON CONFLICT 가 동작하지 않는다").isEqualTo(1);

    // 읽기 경로도 같은 배선에 의존한다 — 트랜잭션이 없으면 RLS 이후 조용히 0행이 된다.
    assertThat(repo.isEnabled(userId, ChannelType.SLACK))
        .as("두 번째 setEnabled 의 값이 반영되지 않았다")
        .isTrue();
  }

  /** {@code tenant_id} 가 GUC 로 채워졌는지 직접 확인한다 — 리포지토리 트랜잭션 배선의 행위 증거. */
  @Test
  void setEnabled_fillsTenantIdFromGuc() {
    userId = inTenantFixture(() -> TenantRlsTestSupport.insertUser(dsl, "pref-tenant-"));

    repo.setEnabled(userId, ChannelType.SLACK, false);

    Long tenantId =
        inTenantFixture(
            () ->
                dsl.select(USER_CHANNEL_PREFERENCE.TENANT_ID)
                    .from(USER_CHANNEL_PREFERENCE)
                    .where(USER_CHANNEL_PREFERENCE.USER_ID.eq(userId))
                    .fetchOne(USER_CHANNEL_PREFERENCE.TENANT_ID));
    assertThat(tenantId).isEqualTo(DEFAULT_TEST_TENANT_ID);
  }
}
