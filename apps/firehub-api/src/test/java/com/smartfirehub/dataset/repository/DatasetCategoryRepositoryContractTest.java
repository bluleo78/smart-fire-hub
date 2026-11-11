package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * DatasetCategoryRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707).
 *
 * <p>dataset_category 는 테넌트 정책(RLS) 아래 있다 — 리포지토리 호출은 늘 테넌트 컨텍스트 트랜잭션({@code fx.inTenant})으로 감싼다.
 * 테넌트는 이 시험이 새로 만들어 다른 시험의 카테고리와 섞이지 않는다.
 */
public abstract class DatasetCategoryRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetCategoryRepository repository();

  @Test
  @DisplayName("save 는 저장된 행(id·이름·설명)을 돌려주고 findById·findAll 이 id 순으로 읽는다")
  void save_findById_findAll() {
    CategoryResponse a = inA(() -> repository().save(prefix + "-a", "설명 A"));
    CategoryResponse b = inA(() -> repository().save(prefix + "-b", null));

    assertThat(a.id()).isNotNull();
    assertThat(a.name()).isEqualTo(prefix + "-a");
    assertThat(a.description()).isEqualTo("설명 A");
    assertThat(b.description()).isNull();
    assertThat(inA(() -> repository().findById(a.id()))).contains(a);
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findAll())).containsExactly(a, b);
  }

  @Test
  @DisplayName("빈 문자열 설명 — PG 는 그대로, Tibero 는 NULL 로 돌아온다")
  void save_emptyDescription() {
    CategoryResponse saved = inA(() -> repository().save(prefix + "-e", ""));

    assertThat(saved.description()).isEqualTo(emptyStringIsNull() ? null : "");
  }

  @Test
  @DisplayName("update 는 이름·설명을 바꾸고 deleteById 는 지운 행 수를 돌려준다(두 번째는 0)")
  void update_deleteById() {
    CategoryResponse c = inA(() -> repository().save(prefix + "-u", "옛 설명"));

    inA(
        () -> {
          repository().update(c.id(), prefix + "-u2", "새 설명");
          return null;
        });
    assertThat(inA(() -> repository().findById(c.id())))
        .contains(new CategoryResponse(c.id(), prefix + "-u2", "새 설명"));

    assertThat(inA(() -> repository().deleteById(c.id()))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteById(c.id()))).isZero();
    assertThat(inA(() -> repository().findById(c.id()))).isEmpty();
  }

  @Test
  @DisplayName("같은 이름을 두 번 저장하면 DuplicateKeyException")
  void save_duplicateName_throwsDuplicateKey() {
    inA(() -> repository().save(prefix + "-dup", null));

    assertThatThrownBy(() -> inA(() -> repository().save(prefix + "-dup", null)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("existsByName — 앞뒤 공백·대소문자(영문·한글)를 무시하고, 저장된 값의 공백도 걷어 비교한다")
  void existsByName_ignoresTrimAndCase() {
    inA(() -> repository().save("  " + prefix + "-Haeng  ", null));
    inA(() -> repository().save(" 행정안전 ", null));

    assertThat(inA(() -> repository().existsByName(prefix + "-haeng"))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-HAENG"))).isTrue();
    assertThat(inA(() -> repository().existsByName("행정안전"))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-other"))).isFalse();
    // 부분 일치는 같은 이름이 아니다.
    assertThat(inA(() -> repository().existsByName(prefix + "-haen"))).isFalse();
  }

  @Test
  @DisplayName("existsByNameExcludingId — 자기 자신은 빼고 다른 카테고리와의 중복만 본다")
  void existsByNameExcludingId() {
    CategoryResponse a = inA(() -> repository().save(prefix + "-x", null));
    CategoryResponse b = inA(() -> repository().save(prefix + "-y", null));

    assertThat(inA(() -> repository().existsByNameExcludingId(prefix + "-X", a.id()))).isFalse();
    assertThat(inA(() -> repository().existsByNameExcludingId(prefix + "-x", b.id()))).isTrue();
    assertThat(inA(() -> repository().existsByNameExcludingId(prefix + "-none", a.id()))).isFalse();
  }

  @Test
  @DisplayName("existsDatasetUsing — 데이터셋이 연결된 카테고리만 true(같은 테넌트 데이터셋만 본다)")
  void existsDatasetUsing() {
    CategoryResponse used = inA(() -> repository().save(prefix + "-used", null));
    CategoryResponse free = inA(() -> repository().save(prefix + "-free", null));
    long ds = fx.createDataset(tenantA, prefix + " ds");
    fx.assignCategory(ds, used.id());

    assertThat(inA(() -> repository().existsDatasetUsing(used.id()))).isTrue();
    assertThat(inA(() -> repository().existsDatasetUsing(free.id()))).isFalse();
    // 다른 테넌트 컨텍스트에서는 데이터셋이 가려져 참조가 없다고 답한다.
    assertThat(inB(() -> repository().existsDatasetUsing(used.id()))).isFalse();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트는 A 의 카테고리를 읽지도 지우지도 못하고 같은 이름도 따로 쓴다")
  void otherTenantIsBlocked() {
    CategoryResponse a = inA(() -> repository().save(prefix + "-t", "A"));

    assertThat(inB(() -> repository().findById(a.id()))).isEmpty();
    assertThat(inB(() -> repository().findAll())).isEmpty();
    assertThat(inB(() -> repository().existsByName(prefix + "-t"))).isFalse();
    assertThat(inB(() -> repository().deleteById(a.id()))).isZero();
    // (테넌트, 이름) 유일이라 B 는 같은 이름을 쓸 수 있다.
    CategoryResponse b = inB(() -> repository().save(prefix + "-t", "B"));
    assertThat(inB(() -> repository().findAll())).containsExactly(b);
    assertThat(inA(() -> repository().findAll())).isEqualTo(List.of(a));
  }
}
