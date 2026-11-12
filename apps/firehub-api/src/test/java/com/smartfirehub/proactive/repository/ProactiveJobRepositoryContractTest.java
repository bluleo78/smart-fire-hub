package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository.WorkspaceJobRow;
import com.smartfirehub.user.repository.AccessFixtures.ProactiveJobStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProactiveJobRepository 계약 테스트 — 공개 메서드 14 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는
 * Tibero 실서버(두 계정 RLS)다.
 *
 * <p>리뷰 초점: 동적 SET(null 은 유지, clearTemplate 은 NULL), enabled 왕복, 양식 이름 LEFT JOIN, 실행 기록 갱신이
 * updated_at 을 안 건드림, 명시 tenant 메서드 5 개({@code findAllEnabled}·{@code findWorkspaceRows}·{@code
 * findWorkspaceRow}·{@code disableInTenant}·{@code deleteInTenant})는 다른 테넌트의 행이 섞이거나 바뀌지 않는다. 빈
 * 프롬프트는 DB 별로 기대값이 다르다({@code emptyStringIsNull}).
 */
abstract class ProactiveJobRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ProactiveJobRepository repository();

  private long userA;
  private long userA2;
  private long userB;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-ua", null);
    userA2 = fx.createUser(prefix + "-ua2", null);
    userB = fx.createUser(prefix + "-ub", null);
  }

  private long createJob(long tenant, long user, String name, boolean enabled) {
    return fx.inTenant(
        tenant,
        () ->
            repository()
                .create(user, name, "프롬프트", null, "0 0 9 * * *", null, enabled, null, null));
  }

  @Test
  @DisplayName("create 는 생략한 값에 기본값(활성·Asia/Seoul·SCHEDULE·빈 config)을 채운다")
  void create_appliesDefaults() {
    Long id =
        inA(
            () ->
                repository()
                    .create(
                        userA, "j-" + prefix, "프롬프트", null, "0 0 9 * * *", null, null, null, null));

    ProactiveJobStored stored = fx.proactiveJobStored(id);
    assertThat(stored.name()).isEqualTo("j-" + prefix);
    assertThat(stored.prompt()).isEqualTo("프롬프트");
    assertThat(stored.cronExpression()).isEqualTo("0 0 9 * * *");
    assertThat(stored.timezone()).isEqualTo("Asia/Seoul");
    assertThat(stored.enabled()).isTrue();
    assertThat(stored.triggerType()).isEqualTo("SCHEDULE");
    assertThat(stored.configJson()).isEqualTo("{}");
    assertThat(stored.templateId()).isNull();
    assertThat(stored.lastExecutedAt()).isNull();
    assertThat(stored.nextExecuteAt()).isNull();
    LocalDateTime dbNow = fx.dbWallClockNow();
    assertThat(Duration.between(stored.createdAt(), dbNow).abs()).isLessThan(Duration.ofMinutes(5));
    assertThat(Duration.between(stored.updatedAt(), dbNow).abs()).isLessThan(Duration.ofMinutes(5));

    ProactiveJobResponse found = inA(() -> repository().findById(id, userA)).orElseThrow();
    assertThat(found.enabled()).isTrue();
    assertThat(found.triggerType()).isEqualTo("SCHEDULE");
    assertThat(found.config()).isEmpty();
    assertThat(found.templateId()).isNull();
    assertThat(found.templateName()).isNull();
    assertThat(found.userId()).isEqualTo(userA);
  }

  @Test
  @DisplayName("create 는 명시한 값(비활성·ANOMALY·config·양식)을 그대로 저장하고 양식 이름을 조인해 돌려준다")
  void create_explicitValuesRoundTrip() {
    long template = fx.createReportTemplate(tenantA, userA, "양식-" + prefix);
    Map<String, Object> config = Map.of("metrics", List.of("m1", "m2"), "n", 3, "label", "한글");
    Long id =
        inA(
            () ->
                repository()
                    .create(
                        userA,
                        "e-" + prefix,
                        "p",
                        template,
                        null,
                        "UTC",
                        false,
                        "ANOMALY",
                        config));

    ProactiveJobResponse found = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(found.enabled()).isFalse();
    assertThat(found.triggerType()).isEqualTo("ANOMALY");
    assertThat(found.timezone()).isEqualTo("UTC");
    assertThat(found.cronExpression()).isNull();
    assertThat(found.config()).isEqualTo(config);
    assertThat(found.templateId()).isEqualTo(template);
    assertThat(found.templateName()).isEqualTo("양식-" + prefix);
    assertThat(fx.proactiveJobStored(id).enabled()).isFalse();
  }

  @Test
  @DisplayName("빈 프롬프트는 PG 에서 빈 문자열, Tibero 에서 NULL 이다")
  void create_emptyPrompt() {
    Long id =
        inA(
            () ->
                repository()
                    .create(
                        userA, "ep-" + prefix, "", null, "0 0 9 * * *", null, true, null, null));
    String expected = emptyStringIsNull() ? null : "";
    assertThat(fx.proactiveJobStored(id).prompt()).isEqualTo(expected);
    assertThat(inA(() -> repository().findById(id)).orElseThrow().prompt()).isEqualTo(expected);
  }

  @Test
  @DisplayName("findByUserId 는 본인 작업만 id 내림차순으로 돌려주고 다른 테넌트에는 보이지 않는다")
  void findByUserId_ownOnly_newestFirst() {
    long template = fx.createReportTemplate(tenantA, userA, "양식-" + prefix);
    long first =
        inA(
            () ->
                repository()
                    .create(userA, "a1-" + prefix, "p", template, null, null, true, null, null));
    long second = createJob(tenantA, userA, "a2-" + prefix, true);
    long other = createJob(tenantA, userB, "b1-" + prefix, true);

    List<ProactiveJobResponse> mine = inA(() -> repository().findByUserId(userA));
    assertThat(mine).extracting(ProactiveJobResponse::id).containsExactly(second, first);
    assertThat(mine.get(1).templateName()).isEqualTo("양식-" + prefix);
    assertThat(mine.get(0).templateName()).isNull();
    assertThat(inA(() -> repository().findByUserId(userB)))
        .extracting(ProactiveJobResponse::id)
        .containsExactly(other);
    assertThat(inB(() -> repository().findByUserId(userA))).isEmpty();
  }

  @Test
  @DisplayName("findById 는 소유자 조건을 거는 판과 안 거는 판이 다르고 다른 테넌트는 빈 값이다")
  void findById_ownerAndTenant() {
    long job = createJob(tenantA, userA, "f-" + prefix, true);

    assertThat(inA(() -> repository().findById(job, userA))).isPresent();
    assertThat(inA(() -> repository().findById(job, userB))).isEmpty();
    assertThat(inA(() -> repository().findById(job))).isPresent();
    assertThat(inB(() -> repository().findById(job))).isEmpty();
    assertThat(inB(() -> repository().findById(job, userA))).isEmpty();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
  }

  @Test
  @DisplayName("lockUpdatedAtById 는 저장된 수정 시각을, 빈 옛 행은 null 을 담은 값을, 없는 행은 빈 값을 돌려준다")
  void lockUpdatedAtById() {
    long job = createJob(tenantA, userA, "lock-" + prefix, false);
    LocalDateTime fixed = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
    fx.setProactiveJobUpdatedAt(job, fixed);

    assertThat(inA(() -> repository().lockUpdatedAtById(job)))
        .contains(new LockedRowVersion(fixed));

    fx.setProactiveJobUpdatedAt(job, null);
    Optional<LockedRowVersion> locked = inA(() -> repository().lockUpdatedAtById(job));
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(job))).as("다른 테넌트").isEmpty();
  }

  @Test
  @DisplayName("update 는 넘긴 필드만 고치고 저장된 수정 시각을 돌려준다")
  void update_dynamicSet() {
    long template = fx.createReportTemplate(tenantA, userA, "양식-" + prefix);
    long job =
        inA(
            () ->
                repository()
                    .create(
                        userA,
                        "u-" + prefix,
                        "원래",
                        template,
                        "0 0 9 * * *",
                        "UTC",
                        true,
                        "SCHEDULE",
                        Map.of("k", "v")));
    LocalDateTime old = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
    fx.setProactiveJobUpdatedAt(job, old);

    // 이름만 — 나머지는 그대로, 수정 시각만 갱신.
    LocalDateTime t1 =
        inA(
            () ->
                repository()
                    .update(
                        job,
                        userA,
                        "바뀐-" + prefix,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null));
    assertThat(t1).isNotNull();
    ProactiveJobStored s1 = fx.proactiveJobStored(job);
    assertThat(s1.name()).isEqualTo("바뀐-" + prefix);
    assertThat(s1.prompt()).isEqualTo("원래");
    assertThat(s1.templateId()).isEqualTo(template);
    assertThat(s1.cronExpression()).isEqualTo("0 0 9 * * *");
    assertThat(s1.timezone()).isEqualTo("UTC");
    assertThat(s1.enabled()).isTrue();
    assertThat(s1.triggerType()).isEqualTo("SCHEDULE");
    assertThat(s1.updatedAt()).as("반환값은 저장된 수정 시각이다").isEqualTo(t1);
    assertThat(t1).isAfter(old);

    // 나머지 필드 전부 — enabled 는 false 로 왕복.
    LocalDateTime t2 =
        inA(
            () ->
                repository()
                    .update(
                        job,
                        userA,
                        null,
                        "새 프롬프트",
                        null,
                        false,
                        "0 0 10 * * *",
                        "Asia/Seoul",
                        false,
                        "BOTH",
                        Map.of("a", 1)));
    assertThat(t2).isNotNull();
    ProactiveJobResponse r2 = inA(() -> repository().findById(job)).orElseThrow();
    assertThat(r2.name()).isEqualTo("바뀐-" + prefix);
    assertThat(r2.prompt()).isEqualTo("새 프롬프트");
    assertThat(r2.templateId()).isEqualTo(template);
    assertThat(r2.templateName()).isEqualTo("양식-" + prefix);
    assertThat(r2.cronExpression()).isEqualTo("0 0 10 * * *");
    assertThat(r2.timezone()).isEqualTo("Asia/Seoul");
    assertThat(r2.enabled()).isFalse();
    assertThat(r2.triggerType()).isEqualTo("BOTH");
    assertThat(r2.config()).isEqualTo(Map.of("a", 1));
    assertThat(r2.updatedAt()).isEqualTo(t2);

    // 다시 켠다.
    inA(
        () ->
            repository().update(job, userA, null, null, null, false, null, null, true, null, null));
    assertThat(fx.proactiveJobStored(job).enabled()).isTrue();
  }

  @Test
  @DisplayName("update 의 templateId 는 교체, clearTemplate 은 NULL, 둘 다 없으면 유지한다")
  void update_templateReplaceClearKeep() {
    long t1 = fx.createReportTemplate(tenantA, userA, "t1-" + prefix);
    long t2 = fx.createReportTemplate(tenantA, userA, "t2-" + prefix);
    long job =
        inA(
            () ->
                repository()
                    .create(userA, "t-" + prefix, "p", t1, "0 0 9 * * *", null, true, null, null));

    // 고칠 필드가 하나도 없어도 수정 시각은 갱신되고, 반환값은 저장된 그 시각이다.
    LocalDateTime noField =
        inA(
            () ->
                repository()
                    .update(job, userA, null, null, null, false, null, null, null, null, null));
    assertThat(noField).isNotNull();
    assertThat(fx.proactiveJobStored(job).updatedAt()).isEqualTo(noField);
    assertThat(fx.proactiveJobStored(job).templateId()).isEqualTo(t1);

    inA(() -> repository().update(job, userA, null, null, t2, false, null, null, null, null, null));
    assertThat(fx.proactiveJobStored(job).templateId()).isEqualTo(t2);

    // templateId 가 있으면 clearTemplate 보다 우선한다.
    inA(() -> repository().update(job, userA, null, null, t1, true, null, null, null, null, null));
    assertThat(fx.proactiveJobStored(job).templateId()).isEqualTo(t1);

    inA(
        () ->
            repository().update(job, userA, null, null, null, true, null, null, null, null, null));
    assertThat(fx.proactiveJobStored(job).templateId()).isNull();
    assertThat(inA(() -> repository().findById(job)).orElseThrow().templateName()).isNull();
  }

  @Test
  @DisplayName("update 는 남의 작업·다른 테넌트·없는 작업에는 null 을 돌려주고 행을 바꾸지 않는다")
  void update_ownerOnly() {
    long job = createJob(tenantA, userA, "own-" + prefix, true);

    assertThat(
            inA(
                () ->
                    repository()
                        .update(job, userB, "x", null, null, false, null, null, null, null, null)))
        .isNull();
    assertThat(
            inB(
                () ->
                    repository()
                        .update(job, userA, "x", null, null, false, null, null, null, null, null)))
        .isNull();
    assertThat(
            inA(
                () ->
                    repository()
                        .update(-1L, userA, "x", null, null, false, null, null, null, null, null)))
        .isNull();
    assertThat(fx.proactiveJobStored(job).name()).isEqualTo("own-" + prefix);
  }

  @Test
  @DisplayName("updateLastExecuted·updateNextExecuteAt 은 실행 기록만 바꾸고 수정 시각은 건드리지 않는다")
  void updateExecutionTimes_doNotTouchUpdatedAt() {
    long job = createJob(tenantA, userA, "ex-" + prefix, true);
    LocalDateTime old = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
    fx.setProactiveJobUpdatedAt(job, old);
    LocalDateTime last = LocalDateTime.of(2031, 2, 3, 4, 5, 6);
    LocalDateTime next = LocalDateTime.of(2031, 2, 4, 4, 5, 6);

    runA(() -> repository().updateLastExecuted(job, last, next));
    ProactiveJobStored s1 = fx.proactiveJobStored(job);
    assertThat(s1.lastExecutedAt()).isEqualTo(last);
    assertThat(s1.nextExecuteAt()).isEqualTo(next);
    assertThat(s1.updatedAt()).isEqualTo(old);

    LocalDateTime next2 = LocalDateTime.of(2032, 3, 4, 5, 6, 7);
    runA(() -> repository().updateNextExecuteAt(job, next2));
    ProactiveJobStored s2 = fx.proactiveJobStored(job);
    assertThat(s2.nextExecuteAt()).isEqualTo(next2);
    assertThat(s2.lastExecutedAt()).as("마지막 실행은 그대로").isEqualTo(last);
    assertThat(s2.updatedAt()).isEqualTo(old);

    // 스케줄 해제는 null.
    runA(() -> repository().updateNextExecuteAt(job, null));
    assertThat(fx.proactiveJobStored(job).nextExecuteAt()).isNull();

    // 다른 테넌트에서는 아무 행도 바뀌지 않는다.
    runB(() -> repository().updateNextExecuteAt(job, next));
    assertThat(fx.proactiveJobStored(job).nextExecuteAt()).isNull();
  }

  private void runB(Runnable call) {
    inB(
        () -> {
          call.run();
          return null;
        });
  }

  @Test
  @DisplayName("delete 는 본인 작업만 지우고 지운 행 수를 돌려준다")
  void delete_ownerOnly() {
    long job = createJob(tenantA, userA, "del-" + prefix, true);

    assertThat(inA(() -> repository().delete(job, userB))).isZero();
    assertThat(inB(() -> repository().delete(job, userA))).as("다른 테넌트").isZero();
    assertThat(fx.proactiveJobStored(job)).isNotNull();

    assertThat(inA(() -> repository().delete(job, userA))).isEqualTo(1);
    assertThat(fx.proactiveJobStored(job)).isNull();
    assertThat(inA(() -> repository().delete(job, userA))).isZero();
  }

  @Test
  @DisplayName("findAllEnabled 는 지정 테넌트의 활성 작업만 돌려주고 다른 테넌트 행은 섞이지 않는다")
  void findAllEnabled_onlyThatTenantsEnabledJobs() {
    long aOn = createJob(tenantA, userA, "a-on-" + prefix, true);
    long aOff = createJob(tenantA, userA, "a-off-" + prefix, false);
    long bOn = createJob(tenantB, userB, "b-on-" + prefix, true);

    List<Long> idsA =
        inA(() -> repository().findAllEnabled(tenantA)).stream()
            .map(ProactiveJobResponse::id)
            .toList();
    assertThat(idsA).containsExactly(aOn);
    assertThat(idsA).doesNotContain(aOff, bOn);

    List<Long> idsB =
        inB(() -> repository().findAllEnabled(tenantB)).stream()
            .map(ProactiveJobResponse::id)
            .toList();
    assertThat(idsB).containsExactly(bOn);

    // 다른 테넌트 번호를 넘기면 내 행이 섞여 나오지 않는다.
    assertThat(inA(() -> repository().findAllEnabled(tenantB))).isEmpty();
    assertThat(inB(() -> repository().findAllEnabled(tenantA))).isEmpty();
  }

  @Test
  @DisplayName("findWorkspaceRows·findWorkspaceRow 는 그 테넌트의 작업(작성자 무관)을 작성자 이름과 함께 돌려준다")
  void findWorkspaceRows_andRow() {
    long j1 = createJob(tenantA, userA, "w1-" + prefix, true);
    long j2 = createJob(tenantA, userA2, "w2-" + prefix, false);
    long jb = createJob(tenantB, userB, "wb-" + prefix, true);
    LocalDateTime next = LocalDateTime.of(2031, 2, 4, 4, 5, 6);
    runA(() -> repository().updateNextExecuteAt(j1, next));

    List<WorkspaceJobRow> rows = inA(() -> repository().findWorkspaceRows(tenantA));
    assertThat(rows).extracting(WorkspaceJobRow::id).containsExactly(j2, j1);
    WorkspaceJobRow r1 = rows.get(1);
    assertThat(r1.name()).isEqualTo("w1-" + prefix);
    assertThat(r1.ownerId()).isEqualTo(userA);
    assertThat(r1.ownerName()).isEqualTo("n");
    assertThat(r1.enabled()).isTrue();
    assertThat(r1.nextExecuteAt()).isEqualTo(next);
    assertThat(r1.timezone()).isEqualTo("Asia/Seoul");
    assertThat(rows.get(0).enabled()).isFalse();
    assertThat(rows.get(0).ownerId()).isEqualTo(userA2);

    assertThat(inB(() -> repository().findWorkspaceRows(tenantB)))
        .extracting(WorkspaceJobRow::id)
        .containsExactly(jb);
    // 다른 테넌트 번호를 넘기면 내 행이 섞여 나오지 않는다.
    assertThat(inA(() -> repository().findWorkspaceRows(tenantB))).isEmpty();

    assertThat(inA(() -> repository().findWorkspaceRow(j1, tenantA)))
        .get()
        .extracting(WorkspaceJobRow::id, WorkspaceJobRow::ownerName)
        .containsExactly(j1, "n");
    assertThat(inA(() -> repository().findWorkspaceRow(jb, tenantA))).as("남의 테넌트 작업").isEmpty();
    assertThat(inA(() -> repository().findWorkspaceRow(j1, tenantB))).isEmpty();
    assertThat(inA(() -> repository().findWorkspaceRow(-1L, tenantA))).isEmpty();
  }

  @Test
  @DisplayName("disableInTenant 는 그 테넌트 작업만 끄고 다른 테넌트 행은 바꾸지 않는다")
  void disableInTenant_onlyThatTenant() {
    long ja = createJob(tenantA, userA, "da-" + prefix, true);
    long jb = createJob(tenantB, userB, "db-" + prefix, true);
    LocalDateTime old = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
    fx.setProactiveJobUpdatedAt(ja, old);

    // 다른 테넌트 번호로는 내 작업이 꺼지지 않는다.
    runA(() -> repository().disableInTenant(ja, tenantB));
    assertThat(fx.proactiveJobStored(ja).enabled()).isTrue();
    // 내 테넌트 컨텍스트에서 남의 작업 번호를 넘겨도 남의 행은 그대로다.
    runA(() -> repository().disableInTenant(jb, tenantA));
    assertThat(fx.proactiveJobStored(jb).enabled()).isTrue();

    runA(() -> repository().disableInTenant(ja, tenantA));
    ProactiveJobStored s = fx.proactiveJobStored(ja);
    assertThat(s.enabled()).isFalse();
    assertThat(s.updatedAt()).as("끄기는 수정 시각을 갱신한다").isAfter(old);
    assertThat(fx.proactiveJobStored(jb).enabled()).isTrue();
  }

  @Test
  @DisplayName("deleteInTenant 는 작성자와 무관하게 그 테넌트 작업만 지우고 다른 테넌트 행은 지우지 않는다")
  void deleteInTenant_onlyThatTenant() {
    long ja = createJob(tenantA, userA, "xa-" + prefix, true);
    long ja2 = createJob(tenantA, userA2, "xa2-" + prefix, true);
    long jb = createJob(tenantB, userB, "xb-" + prefix, true);

    assertThat(inA(() -> repository().deleteInTenant(ja, tenantB))).isZero();
    assertThat(inA(() -> repository().deleteInTenant(jb, tenantA))).isZero();
    assertThat(fx.proactiveJobStored(ja)).isNotNull();
    assertThat(fx.proactiveJobStored(jb)).isNotNull();

    assertThat(inA(() -> repository().deleteInTenant(ja, tenantA))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteInTenant(ja2, tenantA)))
        .as("다른 작성자의 작업도 지운다")
        .isEqualTo(1);
    assertThat(fx.proactiveJobStored(ja)).isNull();
    assertThat(fx.proactiveJobStored(ja2)).isNull();
    assertThat(fx.proactiveJobStored(jb)).isNotNull();
    assertThat(inA(() -> repository().deleteInTenant(ja, tenantA))).isZero();
  }
}
