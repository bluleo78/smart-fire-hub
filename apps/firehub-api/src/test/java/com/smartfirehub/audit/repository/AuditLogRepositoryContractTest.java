package com.smartfirehub.audit.repository;

import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * AuditLogRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>audit_log 는 NULL 테넌트를 허용하는 정책 아래 있다(PG V99 {@code IS NOT DISTINCT FROM}, Tibero {@code
 * FH_TENANT_POLICY_NULLABLE}). PG 의미를 고정한다: 컨텍스트가 있으면 그 테넌트 행만(NULL 행도 안 보인다), 없으면 tenant_id 가 NULL
 * 인 행만. 플랫폼 조회는 컨텍스트 없이 NULL 행을 읽고, 컨텍스트가 남아 있으면 0행이다(fail-closed).
 *
 * <p>공유 DB 에서 다른 시험이 플랫폼(NULL) 감사 행을 동시에 남기므로 플랫폼 조회는 이 실행의 고유 행위자(접두)로 늘 좁힌다. 테넌트 조회는 이 시험이 만든 새
 * 테넌트라 이 시험의 행만 보인다. 리포지토리 호출은 늘 컨텍스트를 명시한다(PG 시험 기반은 기본 테넌트 1 을 세워 둔다).
 */
public abstract class AuditLogRepositoryContractTest extends AccessContractBase {

  private static final ObjectMapper JSON = new ObjectMapper();

  protected abstract AuditLogRepository repository();

  private long tenantA;
  private long tenantB;
  private long userA;

  /** 고정 기준 시각(초 단위) — 저장 TZ 벽시계로 직접 넣는 행의 기준. */
  private final LocalDateTime base = LocalDateTime.of(2026, 3, 10, 12, 0, 0);

  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    userA = fx.createUser(prefix + "-u", null);
  }

  private <T> T inA(Supplier<T> call) {
    return fx.inTenant(tenantA, call);
  }

  /** 테넌트 컨텍스트 없이 — 리포지토리의 클래스 레벨 트랜잭션이 테넌트 없는 트랜잭션을 연다. */
  private <T> T platform(Supplier<T> call) {
    return fx.noTenant(call);
  }

  /** 행 하나(사용자명은 접두 + 꼬리). */
  private long row(Long tenant, String tail, String resource, String resourceId, LocalDateTime t) {
    return fx.insertAuditLog(
        tenant, prefix + tail, "UPDATE", resource, resourceId, prefix + tail, t, null);
  }

  private static List<Long> ids(PageResponse<AuditLogResponse> page) {
    return page.content().stream().map(AuditLogResponse::id).toList();
  }

  private Long save(String description, String metadataJson) {
    return repository()
        .save(
            userA,
            prefix + "-actor",
            "CREATE",
            "dataset",
            "42",
            description,
            "10.0.0.1",
            "agent/1.0",
            "SUCCESS",
            null,
            metadataJson);
  }

  // ── save · findById ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("save 는 모든 열을 저장하고 findById 가 그대로 돌려준다 — 시각은 DB 기본값(저장 TZ 벽시계 = JVM 시각)")
  void save_thenFindById_roundTrip() throws Exception {
    String meta = "{\"targetUsername\":\"홍길동\",\"n\":3,\"nested\":{\"k\":[1,2]}}";
    Long id =
        inA(
            () ->
                repository()
                    .save(
                        userA,
                        prefix + "-actor",
                        "CREATE",
                        "dataset",
                        "42",
                        "설명 한글",
                        "10.0.0.1",
                        "agent/1.0",
                        "FAILURE",
                        "오류",
                        meta));

    AuditLogResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.userId()).isEqualTo(userA);
    assertThat(r.username()).isEqualTo(prefix + "-actor");
    assertThat(r.actionType()).isEqualTo("CREATE");
    assertThat(r.resource()).isEqualTo("dataset");
    assertThat(r.resourceId()).isEqualTo("42");
    assertThat(r.description()).isEqualTo("설명 한글");
    assertThat(r.ipAddress()).isEqualTo("10.0.0.1");
    assertThat(r.userAgent()).isEqualTo("agent/1.0");
    assertThat(r.result()).isEqualTo("FAILURE");
    assertThat(r.errorMessage()).isEqualTo("오류");
    // PG jsonb 는 공백·키 순서를 정규화하므로 문자열이 아니라 JSON 트리로 비교한다.
    assertThat(JSON.readTree(r.metadata())).isEqualTo(JSON.readTree(meta));
    // AuditTimes 의 「저장 TZ = JVM 기본 TZ」 불변식 — 9시간 어긋나면 여기서 걸린다.
    assertThat(r.actionTime()).isCloseTo(LocalDateTime.now(), within(3, MINUTES));
    assertThat(fx.countAuditLog(tenantA, id)).isEqualTo(1);
  }

  @Test
  @DisplayName("metadata 가 null 이면 null 로, 선택 열이 null 이면 null 로 돌아온다")
  void save_nullOptionalColumns() {
    Long id =
        inA(
            () ->
                repository()
                    .save(
                        null,
                        prefix + "-n",
                        "LOGIN",
                        "auth",
                        null,
                        null,
                        null,
                        null,
                        "SUCCESS",
                        null,
                        null));
    AuditLogResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.userId()).isNull();
    assertThat(r.resourceId()).isNull();
    assertThat(r.description()).isNull();
    assertThat(r.ipAddress()).isNull();
    assertThat(r.userAgent()).isNull();
    assertThat(r.errorMessage()).isNull();
    assertThat(r.metadata()).isNull();
  }

  @Test
  @DisplayName("트랜잭션 없이 컨텍스트만 있어도 save 는 그 테넌트 행이 된다(클래스 레벨 트랜잭션, #384-3)")
  void save_withContextWithoutTransaction_isTenantRow() {
    Long id = TenantContext.runScopedGet(tenantA, () -> save("no-tx", null));
    assertThat(fx.countAuditLog(tenantA, id)).isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository().findById(id))).isPresent();
  }

  @Test
  @DisplayName("테넌트 컨텍스트 없이 save 하면 tenant_id 가 NULL 인 플랫폼 행이 되고 컨텍스트 없이만 보인다")
  void save_withoutTenant_isPlatformRow() {
    Long id = platform(() -> save("platform", null));
    fx.trackAuditLog(id);

    assertThat(fx.countAuditLog(null, id)).isEqualTo(1);
    assertThat(platform(() -> repository().findById(id))).isPresent();
    assertThat(inA(() -> repository().findById(id))).isEmpty();
  }

  @Test
  @DisplayName("정책 가시성 — A 컨텍스트는 A 행만, B 는 B 행만, 컨텍스트 없음은 NULL 행만 본다(PG IS NOT DISTINCT FROM)")
  void visibility_matchesPgNullablePolicy() {
    long a = row(tenantA, "-a", "role", "1", base);
    long b = row(tenantB, "-b", "role", "1", base);
    long n = row(null, "-n", "role", "1", base);

    assertThat(inA(() -> repository().findById(a))).isPresent();
    assertThat(inA(() -> repository().findById(b))).isEmpty();
    assertThat(inA(() -> repository().findById(n))).isEmpty();

    assertThat(fx.inTenant(tenantB, () -> repository().findById(a))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findById(b))).isPresent();
    assertThat(fx.inTenant(tenantB, () -> repository().findById(n))).isEmpty();

    assertThat(platform(() -> repository().findById(a))).isEmpty();
    assertThat(platform(() -> repository().findById(b))).isEmpty();
    assertThat(platform(() -> repository().findById(n))).isPresent();

    // 목록도 같은 경계 — A 컨텍스트의 findAll 은 A 행 하나뿐(새 테넌트라 다른 행이 없다).
    assertThat(
            ids(
                inA(
                    () ->
                        repository()
                            .findAll(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0,
                                50,
                                Optional.empty()))))
        .containsExactly(a);
  }

  // ── findByResource ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("findByResource 는 액션·리소스·id 가 모두 맞는 행만, null 인 조건은 빼고, 최신순(동시각은 id 역순)으로 자른다")
  void findByResource_filtersOrderAndTwoPages() {
    // 같은 시각 3개 + 더 최근 1개 + 다른 액션 1개 + 다른 리소스 id 1개 + 다른 리소스 1개
    long t1 = row(tenantA, "-1", "pipeline", "7", base);
    long t2 = row(tenantA, "-2", "pipeline", "7", base);
    long t3 = row(tenantA, "-3", "pipeline", "7", base);
    long newest = row(tenantA, "-4", "pipeline", "7", base.plusMinutes(1));
    long otherAction =
        fx.insertAuditLog(tenantA, prefix + "-5", "DELETE", "pipeline", "7", "x", base, null);
    long otherId = row(tenantA, "-6", "pipeline", "8", base);
    row(tenantA, "-7", "dataset", "7", base);

    PageResponse<AuditLogResponse> p0 =
        inA(() -> repository().findByResource("UPDATE", "pipeline", "7", 0, 2));
    PageResponse<AuditLogResponse> p1 =
        inA(() -> repository().findByResource("UPDATE", "pipeline", "7", 1, 2));
    assertThat(ids(p0)).containsExactly(newest, t3);
    assertThat(ids(p1)).containsExactly(t2, t1);
    assertThat(p0.totalElements()).isEqualTo(4);
    assertThat(p0.totalPages()).isEqualTo(2);
    assertThat(p1.page()).isEqualTo(1);
    assertThat(p1.size()).isEqualTo(2);

    // actionType null → 액션 무관, resourceId null → 리소스 id 무관
    assertThat(ids(inA(() -> repository().findByResource(null, "pipeline", "7", 0, 10))))
        .containsExactly(newest, otherAction, t3, t2, t1);
    assertThat(ids(inA(() -> repository().findByResource("UPDATE", "pipeline", null, 0, 10))))
        .containsExactly(newest, otherId, t3, t2, t1);
    // 다른 테넌트에서는 0행
    assertThat(
            fx.inTenant(tenantB, () -> repository().findByResource(null, "pipeline", null, 0, 10))
                .totalElements())
        .isZero();
  }

  // ── findAll ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("findAll 필터 — 검색(사용자명·설명, 대소문자 무시, LIKE 문자 그대로)·사용자·액션·리소스·결과, 공백 필터는 무시")
  void findAll_filters() {
    long byName =
        fx.insertAuditLog(tenantA, prefix + "-Alice", "UPDATE", "role", "1", "d1", base, null);
    long byDesc =
        fx.insertAuditLog(tenantA, prefix + "-x", "UPDATE", "role", "1", "has 50%_off", base, null);
    long other = fx.insertAuditLog(tenantA, prefix + "-y", "DELETE", "user", "2", "z", base, null);
    Long mine =
        inA(
            () ->
                repository()
                    .save(
                        userA,
                        prefix + "-me",
                        "LOGIN",
                        "auth",
                        null,
                        "m",
                        null,
                        null,
                        "FAILURE",
                        "e",
                        null));

    assertThat(ids(findAll("ALICE", null, null, null, null))).containsExactly(byName);
    assertThat(ids(findAll("50%_", null, null, null, null))).containsExactly(byDesc);
    // % 는 글자 그대로라 "5_%" 는 "50%_" 에 걸리지 않는다.
    assertThat(ids(findAll("5_%", null, null, null, null))).isEmpty();
    assertThat(ids(findAll(null, userA, null, null, null))).containsExactly(mine);
    assertThat(ids(findAll(null, null, "DELETE", null, null))).containsExactly(other);
    assertThat(ids(findAll(null, null, null, "user", null))).containsExactly(other);
    assertThat(ids(findAll(null, null, null, null, "FAILURE"))).containsExactly(mine);
    assertThat(ids(findAll("  ", null, " ", " ", " "))).hasSize(4);
  }

  private PageResponse<AuditLogResponse> findAll(
      String search, Long userId, String actionType, String resource, String result) {
    return inA(
        () ->
            repository()
                .findAll(
                    search,
                    userId,
                    actionType,
                    resource,
                    result,
                    null,
                    null,
                    0,
                    50,
                    Optional.empty()));
  }

  @Test
  @DisplayName("findAll 기간은 시작·끝 모두 포함한다(경계와 같은 시각 행이 들어온다)")
  void findAll_dateRangeInclusive() {
    long before = row(tenantA, "-b", "role", "1", base.minusSeconds(1));
    long atStart = row(tenantA, "-s", "role", "1", base);
    long mid = row(tenantA, "-m", "role", "1", base.plusMinutes(30));
    long atEnd = row(tenantA, "-e", "role", "1", base.plusHours(1));
    long after = row(tenantA, "-a", "role", "1", base.plusHours(1).plusSeconds(1));

    assertThat(ids(range(base, base.plusHours(1)))).containsExactly(atEnd, mid, atStart);
    assertThat(ids(range(base, null))).containsExactly(after, atEnd, mid, atStart);
    assertThat(ids(range(null, base))).containsExactly(atStart, before);
  }

  private PageResponse<AuditLogResponse> range(LocalDateTime from, LocalDateTime to) {
    return inA(
        () ->
            repository().findAll(null, null, null, null, null, from, to, 0, 50, Optional.empty()));
  }

  @Test
  @DisplayName("findAll 은 최신순, 같은 시각은 id 역순으로 두 쪽에 겹침·빠짐 없이 나눈다")
  void findAll_twoPagesWithTies() {
    long r1 = row(tenantA, "-1", "role", "1", base);
    long r2 = row(tenantA, "-2", "role", "1", base);
    long r3 = row(tenantA, "-3", "role", "1", base);
    long r4 = row(tenantA, "-4", "role", "1", base);
    long older = row(tenantA, "-5", "role", "1", base.minusDays(1));

    PageResponse<AuditLogResponse> p0 = page(0);
    PageResponse<AuditLogResponse> p1 = page(1);
    PageResponse<AuditLogResponse> p2 = page(2);
    assertThat(ids(p0)).containsExactly(r4, r3);
    assertThat(ids(p1)).containsExactly(r2, r1);
    assertThat(ids(p2)).containsExactly(older);
    assertThat(p0.totalElements()).isEqualTo(5);
    assertThat(p0.totalPages()).isEqualTo(3);
    assertThat(page(3).content()).isEmpty();
  }

  private PageResponse<AuditLogResponse> page(int page) {
    return inA(
        () ->
            repository()
                .findAll(null, null, null, null, null, null, null, page, 2, Optional.empty()));
  }

  @Test
  @DisplayName("데이터셋 가시성 — 볼 수 없는 등급·다른 테넌트 데이터셋·삭제·특정 불가 id 의 행은 빠지고, 가시성 없음(ADMIN)은 모두 본다")
  void findAll_datasetVisibility() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long visibleDs = fx.createDataset(tenantA, userA, prefix + "_vis", null);
    long secretDs = fx.createDataset(tenantA, userA, prefix + "_sec", secretLevel);
    // 다른 테넌트 데이터셋 — 기본 등급(rank 2)이라 등급만 보면 보일 자격이지만 정책이 A 컨텍스트에서 가린다.
    long otherTenantDs = fx.createDataset(tenantB, userA, prefix + "_oth", null);

    long rVisible = row(tenantA, "-vis", "dataset", String.valueOf(visibleDs), base);
    long rSecret = row(tenantA, "-sec", "dataset", String.valueOf(secretDs), base);
    long rOther = row(tenantA, "-oth", "dataset", String.valueOf(otherTenantDs), base);
    // 18자리 숫자라 형식은 맞지만 없는 데이터셋 — 지워진 데이터셋의 지난 기록(WD-289). 20자리 overflow 경로와 겹치지 않게 한다.
    long rDeleted = row(tenantA, "-del", "dataset", "999999999999999999", base);
    long rOverflow = row(tenantA, "-ovf", "dataset", "99999999999999999999", base);
    long rText = row(tenantA, "-txt", "dataset", "abc", base);
    long rNullId = row(tenantA, "-nul", "dataset", null, base);
    long rRole = row(tenantA, "-rol", "role", "1", base);

    DatasetVisibility rank2 =
        new DatasetVisibility(new Clearance(userA, tenantA, 2, Set.of(), false, Set.of()), null);
    assertThat(ids(visible(Optional.of(rank2)))).containsExactlyInAnyOrder(rVisible, rRole);
    assertThat(ids(visible(Optional.of(DatasetVisibility.UNRESTRICTED))))
        .containsExactlyInAnyOrder(rVisible, rSecret, rRole);
    assertThat(ids(visible(Optional.empty())))
        .containsExactlyInAnyOrder(
            rVisible, rSecret, rOther, rDeleted, rOverflow, rText, rNullId, rRole);
    // 역할 없는 자격은 데이터셋 행을 하나도 못 본다.
    DatasetVisibility none = new DatasetVisibility(Clearance.none(userA, tenantA), null);
    assertThat(ids(visible(Optional.of(none)))).containsExactly(rRole);
    // 건수도 같은 조건
    assertThat(visible(Optional.of(rank2)).totalElements()).isEqualTo(2);
  }

  private PageResponse<AuditLogResponse> visible(Optional<DatasetVisibility> v) {
    return inA(() -> repository().findAll(null, null, null, null, null, null, null, 0, 50, v));
  }

  // ── findPlatform ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("findPlatform 은 컨텍스트 없이 NULL 테넌트 행만, 행위자·대상·액션·[from, to) 로 거르고 동시각은 id 역순 두 쪽")
  void findPlatform_filtersAndPages() {
    String actor = prefix + "-op";
    long p1 = platformRow(actor + "1", "TENANT_CREATE", "100", base, null);
    long p2 = platformRow(actor + "2", "TENANT_CREATE", "101", base, null);
    long p3 = platformRow(actor + "3", "TENANT_CREATE", "102", base, null);
    long byUser =
        platformRow(
            actor + "4",
            "USER_LOCK",
            "103",
            base.minusMinutes(5),
            "{\"targetUsername\":\"Kim-" + prefix + "\"}");
    long bySlug =
        platformRow(
            actor + "5",
            "TENANT_SUSPEND",
            "104",
            base.minusMinutes(6),
            "{\"tenantSlug\":\"slug-" + prefix + "\",\"tenantName\":\"이름\"}");
    long byName =
        platformRow(
            actor + "6",
            "TENANT_SUSPEND",
            "105",
            base.minusMinutes(7),
            "{\"tenantName\":\"Name-" + prefix + "\"}");
    // 같은 행위자의 테넌트 행 — 플랫폼 조회에 나오면 안 된다.
    fx.insertAuditLog(tenantA, actor + "1", "TENANT_CREATE", "tenant", "100", "x", base, null);

    // 행위자 부분일치(대소문자 무시), 최신순·동시각 id 역순, 2쪽
    PageResponse<AuditLogResponse> pg0 = platformFind(actor.toUpperCase(), null, null, 0, 4);
    PageResponse<AuditLogResponse> pg1 = platformFind(actor.toUpperCase(), null, null, 1, 4);
    assertThat(ids(pg0)).containsExactly(p3, p2, p1, byUser);
    assertThat(ids(pg1)).containsExactly(bySlug, byName);
    assertThat(pg0.totalElements()).isEqualTo(6);
    assertThat(pg0.totalPages()).isEqualTo(2);

    // 대상 — resource_id 정확 일치, metadata 대상 아이디·slug·이름 부분일치(대소문자 무시)
    assertThat(ids(platformFind(actor, "101", null, 0, 10))).containsExactly(p2);
    assertThat(ids(platformFind(actor, "kim-" + prefix, null, 0, 10))).containsExactly(byUser);
    assertThat(ids(platformFind(actor, "SLUG-" + prefix, null, 0, 10))).containsExactly(bySlug);
    assertThat(ids(platformFind(actor, "name-" + prefix, null, 0, 10))).containsExactly(byName);
    // 액션 정확 일치, 공백 필터는 무시
    assertThat(ids(platformFind(actor, " ", "TENANT_SUSPEND", 0, 10)))
        .containsExactly(bySlug, byName);

    // [from, to) — from 과 같은 시각은 포함, to 와 같은 시각은 제외
    assertThat(
            ids(
                platform(
                    () ->
                        repository()
                            .findPlatform(actor, null, null, base.minusMinutes(6), base, 0, 10))))
        .containsExactly(byUser, bySlug);
  }

  private long platformRow(
      String username, String action, String resourceId, LocalDateTime t, String meta) {
    return fx.insertAuditLog(null, username, action, "tenant", resourceId, "p", t, meta);
  }

  private PageResponse<AuditLogResponse> platformFind(
      String actor, String target, String action, int page, int size) {
    return platform(() -> repository().findPlatform(actor, target, action, null, null, page, size));
  }

  @Test
  @DisplayName("findPlatform 은 테넌트 컨텍스트가 남아 있으면 0행이다(fail-closed) — 테넌트 행도 NULL 행도 새지 않는다")
  void findPlatform_withTenantContext_isEmpty() {
    String actor = prefix + "-fc";
    platformRow(actor, "TENANT_CREATE", "1", base, null);
    fx.insertAuditLog(tenantA, actor, "TENANT_CREATE", "tenant", "1", "x", base, null);

    assertThat(platform(() -> repository().findPlatform(actor, null, null, null, null, 0, 10)))
        .extracting(PageResponse::totalElements)
        .isEqualTo(1L);
    PageResponse<AuditLogResponse> scoped =
        inA(() -> repository().findPlatform(actor, null, null, null, null, 0, 10));
    assertThat(scoped.content()).isEmpty();
    assertThat(scoped.totalElements()).isZero();
  }

  // ── 빈 문자열(리뷰 포인트 2, controller Ruling) ─────────────────────────────

  /**
   * 설명(NULL 허용)의 빈 문자열은 PG 에서 그대로, Tibero 에서 null 로 돌아온다. NOT NULL 인 사용자명의 빈 문자열은 PG 에서 그대로 저장되고
   * Tibero 에서 무결성 위반으로 거절된다. 변환하지 않는다.
   */
  @Test
  @DisplayName("빈 설명은 PG 에서 그대로·Tibero 에서 null, 빈 사용자명은 PG 에서 그대로·Tibero 에서 거절")
  void emptyStrings_perDb() {
    Long id = inA(() -> save("", null));
    AuditLogResponse r = inA(() -> repository().findById(id)).orElseThrow();
    if (emptyStringIsNull()) {
      assertThat(r.description()).isNull();
      assertThatThrownBy(
              () ->
                  inA(
                      () ->
                          repository()
                              .save(
                                  null, "", "LOGIN", "auth", null, null, null, null, "SUCCESS",
                                  null, null)))
          .isInstanceOf(DataIntegrityViolationException.class);
      return;
    }
    assertThat(r.description()).isEmpty();
    Long emptyUser =
        inA(
            () ->
                repository()
                    .save(
                        null, "", "LOGIN", "auth", null, null, null, null, "SUCCESS", null, null));
    assertThat(inA(() -> repository().findById(emptyUser)).orElseThrow().username()).isEmpty();
  }

  @Test
  @DisplayName("직접 넣은 action_time 은 초 단위까지 그대로 왕복한다")
  void actionTime_roundTrip() {
    LocalDateTime t = base.plusSeconds(17).truncatedTo(SECONDS);
    long id = row(tenantA, "-t", "role", "1", t);
    assertThat(inA(() -> repository().findById(id)).orElseThrow().actionTime()).isEqualTo(t);
  }
}
