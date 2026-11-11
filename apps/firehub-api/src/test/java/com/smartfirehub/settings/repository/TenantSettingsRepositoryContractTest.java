package com.smartfirehub.settings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.user.repository.AccessContractBase;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * TenantSettingsRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>tenant_settings 는 테넌트 정책 대상이다. 읽기는 컨텍스트가 없어도 예외 없이 빈 값(정책이 막는다 — 읽기 해석기의 정상 분기, 리뷰 포인트 1), 쓰기는
 * {@code TenantContext.require} 로 거절한다. 같은 키를 두 테넌트가 가져도 서로의 쓰기·삭제가 닿지 않아야 한다(Tibero 는 MERGE 에 정책이
 * 붙는지까지 이 시험이 실측한다).
 */
public abstract class TenantSettingsRepositoryContractTest extends AccessContractBase {

  protected abstract TenantSettingsRepository repository();

  private long tenantA;
  private long tenantB;
  private long editor;

  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    editor = fx.createUser(prefix + "-ed", null);
  }

  private <T> T inA(Supplier<T> call) {
    return fx.inTenant(tenantA, call);
  }

  private void run(Long tenantId, Runnable call) {
    fx.inTenant(
        tenantId,
        () -> {
          call.run();
          return null;
        });
  }

  @Test
  @DisplayName("upsert 는 새로 넣고 같은 키를 다시 쓰면 행이 늘지 않고 값·수정자가 바뀐다")
  void upsert_insertThenUpdate() {
    String key = prefix + ".k";
    run(tenantA, () -> repository().upsert(key, "v1", null));
    assertThat(inA(() -> repository().findValue(key))).contains("v1");

    run(tenantA, () -> repository().upsert(key, "v2", editor));

    assertThat(inA(() -> repository().findValue(key))).contains("v2");
    assertThat(fx.countTenantRows("tenant_settings", tenantA)).isEqualTo(1);
    assertThat(fx.tenantSettingUpdatedBy(tenantA, key)).isEqualTo(editor);
  }

  @Test
  @DisplayName("같은 키를 두 테넌트가 가져도 쓰기·조회·삭제가 자기 테넌트 행에만 닿는다")
  void sameKeyInTwoTenants_isolated() {
    String key = prefix + ".shared";
    run(tenantB, () -> repository().upsert(key, "B", null));
    run(tenantA, () -> repository().upsert(key, "A", null));
    run(tenantA, () -> repository().upsert(key, "A2", null));

    assertThat(inA(() -> repository().findValue(key))).contains("A2");
    assertThat(fx.inTenant(tenantB, () -> repository().findValue(key))).contains("B");

    assertThat(inA(() -> repository().delete(key))).isEqualTo(1);
    assertThat(inA(() -> repository().findValue(key))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findValue(key))).contains("B");
    assertThat(inA(() -> repository().deleteAll(List.of(key)))).isZero();
    assertThat(fx.countTenantRows("tenant_settings", tenantB)).isEqualTo(1);
  }

  @Test
  @DisplayName("findByPrefix 는 프리픽스.% 행을 키 오름차순 맵으로, LIKE 특수문자는 글자 그대로")
  void findByPrefix_orderedAndEscaped() {
    String wild = prefix + "_w";
    run(
        tenantA,
        () -> {
          repository().upsert(prefix + ".c", "3", null);
          repository().upsert(prefix + ".a", "1", null);
          repository().upsert(prefix + ".b", "2", null);
          repository().upsert(prefix + "x.d", "4", null);
          repository().upsert(prefix + "Zw.k", "z", null);
          repository().upsert(wild + ".k", "w", null);
        });

    assertThat(inA(() -> repository().findByPrefix(prefix)))
        .containsExactly(
            java.util.Map.entry(prefix + ".a", "1"),
            java.util.Map.entry(prefix + ".b", "2"),
            java.util.Map.entry(prefix + ".c", "3"));
    assertThat(inA(() -> repository().findByPrefix(wild))).containsOnlyKeys(wild + ".k");
    assertThat(fx.inTenant(tenantB, () -> repository().findByPrefix(prefix))).isEmpty();
  }

  @Test
  @DisplayName("테넌트 컨텍스트가 없으면 읽기는 예외 없이 빈 값, 쓰기는 MissingTenantScopeException")
  void withoutTenant_readsEmpty_writesRejected() {
    String key = prefix + ".nt";
    run(tenantA, () -> repository().upsert(key, "v", null));

    assertThat(fx.noTenant(() -> repository().findValue(key))).isEmpty();
    assertThat(fx.noTenant(() -> repository().findByPrefix(prefix))).isEmpty();
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().upsert(key, "x", null);
                      return null;
                    }))
        .isInstanceOf(MissingTenantScopeException.class);
    assertThatThrownBy(() -> fx.noTenant(() -> repository().delete(key)))
        .isInstanceOf(MissingTenantScopeException.class);
    // 빈 목록이어도 테넌트 확인이 먼저다.
    assertThatThrownBy(() -> fx.noTenant(() -> repository().deleteAll(List.of())))
        .isInstanceOf(MissingTenantScopeException.class);
    assertThat(inA(() -> repository().findValue(key))).contains("v");
  }

  @Test
  @DisplayName("null 값은 테넌트 확인보다 먼저 NullPointerException 으로 거절한다")
  void upsert_nullValue_rejectedBeforeTenantCheck() {
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().upsert("k", null, null);
                      return null;
                    }))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("delete·deleteAll 은 지운 행 수를 돌려주고, 빈 목록은 0")
  void delete_andDeleteAll_counts() {
    run(
        tenantA,
        () -> {
          repository().upsert(prefix + ".a", "1", null);
          repository().upsert(prefix + ".b", "2", null);
          repository().upsert(prefix + ".c", "3", null);
        });

    assertThat(inA(() -> repository().delete(prefix + ".a"))).isEqualTo(1);
    assertThat(inA(() -> repository().delete(prefix + ".a"))).isZero();
    assertThat(inA(() -> repository().deleteAll(List.of(prefix + ".b", prefix + ".c", "none"))))
        .isEqualTo(2);
    assertThat(inA(() -> repository().deleteAll(List.of()))).isZero();
    assertThat(fx.countTenantRows("tenant_settings", tenantA)).isZero();
  }

  @Test
  @DisplayName("긴 값(약 5,000자, 자격증명 JSON 크기)도 그대로 왕복한다")
  void upsert_longValue_roundTrip() {
    String key = prefix + ".long";
    String longValue = "한글abc".repeat(1000);
    run(tenantA, () -> repository().upsert(key, longValue, null));
    run(tenantA, () -> repository().upsert(key, longValue + "!", null));

    assertThat(inA(() -> repository().findValue(key))).contains(longValue + "!");
  }

  /**
   * 빈 문자열(리뷰 포인트 2, controller Ruling) — value 는 NOT NULL 이다. PG 는 {@code ""} 를 그대로 저장·조회하고, Tibero
   * 는 {@code ''} 를 NULL 로 보아 거절한다(DataIntegrityViolationException 계열). 변환하지 않는다.
   */
  @Test
  @DisplayName("빈 값은 PG 에서 그대로 왕복하고 Tibero 에서 무결성 위반으로 거절된다")
  void emptyValue_perDb() {
    String key = prefix + ".empty";
    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> run(tenantA, () -> repository().upsert(key, "", null)))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(inA(() -> repository().findValue(key))).isEmpty();
      return;
    }
    run(tenantA, () -> repository().upsert(key, "", null));
    assertThat(inA(() -> repository().findValue(key))).contains("");
    assertThat(inA(() -> repository().findByPrefix(prefix))).containsEntry(key, "");
  }
}
