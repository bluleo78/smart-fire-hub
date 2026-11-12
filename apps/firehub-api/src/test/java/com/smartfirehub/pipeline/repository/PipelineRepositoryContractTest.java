package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.PipelineResponse;
import com.smartfirehub.pipeline.repository.PipelineRepository.LockedPipeline;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PipelineRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-708).
 *
 * <p>pipeline 은 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트({@code inA}/{@code inB})로 감싼다. 사용자 이름 조인은 테넌트 조건
 * 없이 id 로만 잇는다. 빈 설명은 DB 가 다르게 저장하므로({@code emptyStringIsNull}) 기대값을 훅으로 가른다.
 */
public abstract class PipelineRepositoryContractTest extends TenantPairContractBase {

  protected abstract PipelineRepository repository();

  /** 파이프라인을 만든 사람 — 표시 이름이 조회에 실린다. */
  private long creator;

  @BeforeEach
  void createCreator() {
    creator = fx.createUser(prefix + "-creator", null);
    fx.setUserName(creator, prefix + "-creator-name");
  }

  private PipelineResponse save(String suffix, String description) {
    return inA(() -> repository().save(prefix + "-" + suffix, description, creator));
  }

  @Test
  @DisplayName("save — 활성·스텝 0·트리거 0 으로 저장되고 만든이 이름과 DB 시계 생성 시각이 실린다")
  void save_returnsStoredRow() {
    PipelineResponse saved = save("p1", "설명 한글");

    assertThat(saved.id()).isNotNull();
    assertThat(saved.name()).isEqualTo(prefix + "-p1");
    assertThat(saved.description()).isEqualTo("설명 한글");
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.createdBy()).isEqualTo(prefix + "-creator-name");
    assertThat(saved.stepCount()).isZero();
    assertThat(saved.triggerCount()).isZero();
    assertThat(Duration.between(saved.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(inA(() -> repository().findById(saved.id()))).contains(saved);
  }

  @Test
  @DisplayName("save — 설명이 null 이면 null, 빈 문자열은 DB 규칙대로 저장된다")
  void save_nullAndEmptyDescription() {
    assertThat(save("n", null).description()).isNull();

    PipelineResponse empty = save("e", "");
    if (emptyStringIsNull()) {
      assertThat(empty.description()).isNull();
    } else {
      assertThat(empty.description()).isEmpty();
    }
  }

  @Test
  @DisplayName("save — 같은 테넌트에 같은 이름은 무결성 예외로 막힌다")
  void save_duplicateNameRejected() {
    save("dup", null);

    assertThatThrownBy(() -> save("dup", null)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("findById — 스텝 수와 활성 트리거 수(꺼진 트리거 제외)가 실리고, 없는 id 는 빈 값이다")
  void findById_countsStepsAndEnabledTriggers() {
    long id = save("c", null).id();
    fx.createStep(id, "s1", "SQL", "select 1", null, "REPLACE");
    fx.createStep(id, "s2", "SQL", "select 2", null, "REPLACE");
    fx.createTrigger(id, "t-on", "API", "{}");
    long off = fx.createTrigger(id, "t-off", "SCHEDULE", "{}");
    fx.setTriggerEnabled(off, false);

    PipelineResponse found = inA(() -> repository().findById(id)).orElseThrow();

    assertThat(found.stepCount()).isEqualTo(2);
    assertThat(found.triggerCount()).isEqualTo(1);
    assertThat(inA(() -> repository().findById(id + 1_000_000))).isEmpty();
  }

  @Test
  @DisplayName("findAll·count — id 내림차순 페이지로 나오고 다른 테넌트 파이프라인은 세지도 보이지도 않는다")
  void findAll_pagesNewestFirstAndIsolatesTenant() {
    long p1 = save("a1", null).id();
    long p2 = save("a2", null).id();
    long p3 = save("a3", null).id();
    long other = fx.createPipeline(tenantB, prefix + "-b1", true);

    List<PipelineResponse> page0 = inA(() -> repository().findAll(0, 2));
    List<PipelineResponse> page1 = inA(() -> repository().findAll(1, 2));

    assertThat(page0).extracting(PipelineResponse::id).containsExactly(p3, p2);
    assertThat(page1).extracting(PipelineResponse::id).containsExactly(p1);
    assertThat(inA(() -> repository().findAll(2, 2))).isEmpty();
    assertThat(inA(() -> repository().count())).isEqualTo(3);
    assertThat(inB(() -> repository().count())).isEqualTo(1);
    assertThat(inB(() -> repository().findAll(0, 10)))
        .extracting(PipelineResponse::id)
        .containsExactly(other);
    assertThat(inA(() -> repository().findById(other))).isEmpty();
  }

  @Test
  @DisplayName("update — 메타데이터를 고치고 저장된 수정 시각을 돌려주며, isActive 가 null 이면 활성 여부를 유지한다")
  void update_returnsStoredUpdatedAtAndKeepsActiveWhenNull() {
    long id = save("u", "before").id();
    long editor = fx.createUser(prefix + "-editor", null);

    LocalDateTime updatedAt =
        inA(() -> repository().update(id, prefix + "-u2", "after", null, editor));

    assertThat(updatedAt).isNotNull();
    assertThat(inA(() -> repository().findUpdatedAtById(id))).contains(updatedAt);
    PipelineResponse after = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(after.name()).isEqualTo(prefix + "-u2");
    assertThat(after.description()).isEqualTo("after");
    assertThat(after.isActive()).isTrue();
    assertThat(inA(() -> repository().findUpdatedByById(id))).contains(editor);

    inA(() -> repository().update(id, prefix + "-u2", "after", false, editor));
    assertThat(inA(() -> repository().findById(id)).orElseThrow().isActive()).isFalse();
    // null 은 유지다 — 꺼진 채로 남는다.
    inA(() -> repository().update(id, prefix + "-u2", "after", null, editor));
    assertThat(inA(() -> repository().findById(id)).orElseThrow().isActive()).isFalse();
    inA(() -> repository().update(id, prefix + "-u2", "after", true, editor));
    assertThat(inA(() -> repository().findById(id)).orElseThrow().isActive()).isTrue();
  }

  @Test
  @DisplayName("update — 없는 id 는 null 을 돌려주고 다른 테넌트 파이프라인은 고치지 못한다")
  void update_missingAndOtherTenant() {
    long id = save("m", "keep").id();

    assertThat(inA(() -> repository().update(id + 1_000_000, "x", null, null, creator))).isNull();
    assertThat(inB(() -> repository().update(id, "hacked", "hacked", null, creator))).isNull();
    assertThat(inA(() -> repository().findNameById(id))).contains(prefix + "-m");
  }

  @Test
  @DisplayName("deleteById·existsById·existsByName — 삭제하면 사라지고 다른 테넌트에는 보이지 않는다")
  void deleteAndExists() {
    long id = save("d", null).id();

    assertThat(inA(() -> repository().existsById(id))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-d"))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-zzz"))).isFalse();
    assertThat(inB(() -> repository().existsById(id))).isFalse();
    assertThat(inB(() -> repository().existsByName(prefix + "-d"))).isFalse();

    // 다른 테넌트의 삭제 시도는 행을 건드리지 못한다.
    runB(() -> repository().deleteById(id));
    assertThat(inA(() -> repository().existsById(id))).isTrue();

    runA(() -> repository().deleteById(id));
    assertThat(inA(() -> repository().existsById(id))).isFalse();
    assertThat(inA(() -> repository().existsByName(prefix + "-d"))).isFalse();
  }

  @Test
  @DisplayName("findExistingIds — 빈 입력은 빈 집합, 있는 id 만 남기고 다른 테넌트 id 는 뺀다")
  void findExistingIds_filtersMissingAndOtherTenant() {
    long p1 = save("e1", null).id();
    long p2 = save("e2", null).id();
    long other = fx.createPipeline(tenantB, prefix + "-eb", true);

    assertThat(inA(() -> repository().findExistingIds(List.of()))).isEmpty();
    assertThat(inA(() -> repository().findExistingIds(List.of(p1, p2, other, p2 + 1_000_000))))
        .isEqualTo(Set.of(p1, p2));
  }

  @Test
  @DisplayName("findExistingIds — 1001개를 넘는 id 목록도 터지지 않고 실제 있는 id 만 돌려준다")
  void findExistingIds_overOneThousandIds() {
    long p1 = save("big1", null).id();
    long p2 = save("big2", null).id();
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 1200; i++) {
      ids.add(900_000_000L + i);
    }
    ids.add(p1);
    ids.add(p2);

    assertThat(inA(() -> repository().findExistingIds(ids))).isEqualTo(Set.of(p1, p2));
  }

  @Test
  @DisplayName("findNameById·findCreatedByIdById·findUpdatedByById — 값 조회, 없거나 비어 있으면 빈 값")
  void scalarLookups() {
    long id = save("s", null).id();
    // 새 행은 수정 시각이 생성 시각으로 채워지므로, 수정 시각이 빈 옛 행을 직접 만든다.
    fx.setPipelineUpdatedAt(id, null);

    assertThat(inA(() -> repository().findNameById(id))).contains(prefix + "-s");
    assertThat(inA(() -> repository().findCreatedByIdById(id))).contains(creator);
    assertThat(inA(() -> repository().findUpdatedByById(id))).isEmpty();
    assertThat(inA(() -> repository().findUpdatedAtById(id))).isEmpty();
    assertThat(inA(() -> repository().findNameById(id + 1_000_000))).isEmpty();
    assertThat(inB(() -> repository().findNameById(id))).isEmpty();
    assertThat(inB(() -> repository().findCreatedByIdById(id))).isEmpty();
  }

  @Test
  @DisplayName("lockById — 이름과 수정 시각을 읽고, 수정 시각이 빈 옛 행도 행은 있으며, 없는 행·다른 테넌트는 빈 값이다")
  void lockById_readsRowIncludingNullUpdatedAt() {
    long id = save("l", null).id();
    // 새 행은 수정 시각이 생성 시각으로 채워지므로, 수정 시각이 빈 옛 행을 직접 만든다.
    fx.setPipelineUpdatedAt(id, null);

    LockedPipeline blank = inA(() -> repository().lockById(id)).orElseThrow();
    assertThat(blank.name()).isEqualTo(prefix + "-l");
    assertThat(blank.updatedAt()).isNull();

    LocalDateTime at = fx.dbWallClockNow().withNano(0).minusDays(1);
    fx.setPipelineUpdatedAt(id, at);
    LockedPipeline stamped = inA(() -> repository().lockById(id)).orElseThrow();
    assertThat(stamped.updatedAt()).isEqualTo(at);
    assertThat(inA(() -> repository().findUpdatedAtById(id))).contains(at);

    assertThat(inA(() -> repository().lockById(id + 1_000_000))).isEmpty();
    assertThat(inB(() -> repository().lockById(id))).isEmpty();
  }

  /** 테넌트 B 범위에서 결과 없는 호출을 실행한다. */
  private void runB(Runnable call) {
    inB(
        () -> {
          call.run();
          return null;
        });
  }
}
