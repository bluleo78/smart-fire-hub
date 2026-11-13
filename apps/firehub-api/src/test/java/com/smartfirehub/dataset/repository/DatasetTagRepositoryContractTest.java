package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DatasetTagRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). dataset_tag 는 테넌트 정책 아래 있어 호출은 {@code
 * fx.inTenant} 로 감싼다. 같은 데이터셋에 대소문자만 다른 태그는 중복이다(두 DB 모두 유일 색인 uq_dataset_tag_dataset_lower_name
 * (dataset_id, LOWER(tag_name)) 이 있다).
 */
public abstract class DatasetTagRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetTagRepository repository();

  private long userA;

  @BeforeEach
  void setUpExtra() {
    userA = fx.createUser(prefix + "-u", null);
  }

  @Test
  @DisplayName("insert 는 넣은 행 수 1 을 돌려주고 findByDatasetId 가 이름 오름차순으로 읽는다")
  void insert_findByDatasetId() {
    long ds = fx.createDataset(tenantA, prefix + " ds");

    assertThat(inA(() -> repository().insert(ds, "b-tag", userA))).isEqualTo(1);
    assertThat(inA(() -> repository().insert(ds, "a-tag", userA))).isEqualTo(1);
    // 만든 이가 없어도(null) 들어간다.
    assertThat(inA(() -> repository().insert(ds, "c-tag", null))).isEqualTo(1);

    assertThat(inA(() -> repository().findByDatasetId(ds)))
        .containsExactly("a-tag", "b-tag", "c-tag");
    assertThat(inA(() -> repository().findByDatasetId(-1L))).isEmpty();
  }

  @Test
  @DisplayName("같은 태그(정확 일치·대소문자만 다름·한글)를 또 넣으면 0 — 예외 없이 한 행만 남는다")
  void insert_duplicateIgnoringCase_returnsZero() {
    long ds = fx.createDataset(tenantA, prefix + " ds");

    assertThat(inA(() -> repository().insert(ds, "Fire", userA))).isEqualTo(1);
    assertThat(inA(() -> repository().insert(ds, "Fire", userA))).isZero();
    assertThat(inA(() -> repository().insert(ds, "fire", userA))).isZero();
    assertThat(inA(() -> repository().insert(ds, "FIRE", userA))).isZero();
    assertThat(inA(() -> repository().insert(ds, "화재", userA))).isEqualTo(1);
    assertThat(inA(() -> repository().insert(ds, "화재", userA))).isZero();

    assertThat(inA(() -> repository().findByDatasetId(ds))).containsExactlyInAnyOrder("Fire", "화재");
    // 다른 데이터셋에는 같은 이름을 넣을 수 있다.
    long ds2 = fx.createDataset(tenantA, prefix + " ds2");
    assertThat(inA(() -> repository().insert(ds2, "Fire", userA))).isEqualTo(1);
  }

  /**
   * MERGE(Tibero)·ON CONFLICT(PG) 를 거치지 않는 직접 INSERT 도 (dataset_id, LOWER(tag_name)) 유일 색인이
   * 막는다(WD-726). 두 DB 기준선 모두 같은 색인이 있어 공통 계약에 둔다. 픽스처는 번역기 없는 날 JDBC 라 PG 는
   * DuplicateKeyException(23505), Tibero 는 DataIntegrityViolationException(JDBC-10007) 이 나와 공통 상위
   * 타입으로 확인한다. 리포지토리 경로는 TiberoSqlExceptionTranslator 가 -10007 을 DuplicateKeyException 으로 옮기고 재시도가
   * 흡수한다(TiberoSqlErrorCodesTest).
   */
  @Test
  @DisplayName("색인이 MERGE 를 거치지 않은 직접 INSERT 도 막는다")
  void directInsert_caseOnlyDifference_isRejectedByUniqueIndex() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    fx.addTag(ds, "Fire");

    assertThatThrownBy(() -> fx.addTag(ds, "fire"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(inA(() -> repository().findByDatasetId(ds))).containsExactly("Fire");
  }

  @Test
  @DisplayName("deleteIgnoreCase 는 대소문자 무시로 지우고 지운 행 수를 돌려준다(없으면 0)")
  void deleteIgnoreCase() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    inA(() -> repository().insert(ds, "Fire", userA));
    inA(() -> repository().insert(ds, "keep", userA));

    assertThat(inA(() -> repository().deleteIgnoreCase(ds, "fire"))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteIgnoreCase(ds, "fire"))).isZero();
    assertThat(inA(() -> repository().deleteIgnoreCase(ds, "none"))).isZero();
    assertThat(inA(() -> repository().findByDatasetId(ds))).containsExactly("keep");
  }

  @Test
  @DisplayName("findAllDistinctTags — 가시성(제한 없음·등급·역할 없음)과 다른 테넌트 차단, 중복 태그는 한 번")
  void findAllDistinctTags_visibilityAndTenant() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long visibleDs = fx.createDataset(tenantA, userA, prefix + "_vis", null);
    long visibleDs2 = fx.createDataset(tenantA, userA, prefix + "_vis2", null);
    long secretDs = fx.createDataset(tenantA, userA, prefix + "_sec", secretLevel);
    long otherTenantDs = fx.createDataset(tenantB, userA, prefix + "_oth", null);
    inA(() -> repository().insert(visibleDs, "a-open", userA));
    inA(() -> repository().insert(visibleDs2, "a-open", userA));
    inA(() -> repository().insert(secretDs, "b-secret", userA));
    fx.inTenant(tenantB, () -> repository().insert(otherTenantDs, "c-other", userA));

    DatasetVisibility rank2 =
        new DatasetVisibility(new Clearance(userA, tenantA, 2, Set.of(), false, Set.of()), null);
    assertThat(inA(() -> repository().findAllDistinctTags(rank2))).containsExactly("a-open");
    assertThat(inA(() -> repository().findAllDistinctTags(DatasetVisibility.UNRESTRICTED)))
        .containsExactly("a-open", "b-secret");
    DatasetVisibility none = new DatasetVisibility(Clearance.none(userA, tenantA), null);
    assertThat(inA(() -> repository().findAllDistinctTags(none))).isEmpty();
    // B 컨텍스트에서는 B 의 태그만 보인다.
    assertThat(
            fx.inTenant(
                tenantB, () -> repository().findAllDistinctTags(DatasetVisibility.UNRESTRICTED)))
        .containsExactly("c-other");
  }
}
