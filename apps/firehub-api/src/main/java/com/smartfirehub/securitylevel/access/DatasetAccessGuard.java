package com.smartfirehub.securitylevel.access;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository.AccessFacts;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 열람 통제의 단일 진입점(스펙 §4.1). 판정은 {@link DatasetAccessPolicy} 에 위임하고, 이 클래스는 사실 조회와 결과 표현(404·SQL
 * 조각)만 맡는다.
 */
@Service
@RequiredArgsConstructor
public class DatasetAccessGuard {

  private final DatasetAccessRepository accessRepository;
  private final ClearanceResolver clearanceResolver;
  private final DSLContext dsl;

  /** 현재 요청 사용자 기준 VIEW 강제. */
  public void requireView(long datasetId) {
    requireView(clearanceResolver.current(), datasetId);
  }

  /**
   * VIEW 강제. 볼 수 없으면 <b>존재하지 않는 데이터셋과 같은</b> 404 를 던진다(스펙 §2.5 존재 은닉) — 메시지 형식은 기존
   * DatasetNotFoundException 사용처와 바이트 단위로 같아야 한다.
   *
   * <p>가드 메서드에는 @Transactional 을 두지 않는다(판단 사항 19): 트랜잭션은 리포지토리가 보장하고, 가드가 던지는 예외가 호출자의 바깥 트랜잭션을
   * rollback-only 로 만들지 않게 한다.
   */
  public void requireView(Clearance c, long datasetId) {
    if (!check(c, datasetId, DatasetAction.VIEW, null).allowed()) {
      throw new DatasetNotFoundException("Dataset not found: " + datasetId);
    }
  }

  /** 행위 판정. 데이터셋이 없으면 LEVEL_UNKNOWN 거부(존재 여부를 따로 드러내지 않는다). */
  public Decision check(
      Clearance c, long datasetId, DatasetAction action, ProviderHosting hosting) {
    AccessFacts f = accessRepository.findFactsByDatasetIds(List.of(datasetId), c).get(datasetId);
    if (f == null) {
      return Decision.deny("LEVEL_UNKNOWN", null, null);
    }
    return decide(c, f, action, hosting);
  }

  /** 이미 읽은 사실로 판정(SQL 경로가 여러 데이터셋을 한 번에 읽은 뒤 쓴다). */
  Decision decide(Clearance c, AccessFacts f, DatasetAction action, ProviderHosting hosting) {
    return DatasetAccessPolicy.decide(
        new AccessInput(
            c.rank(),
            f.onAllowlist(),
            c.tenantAdmin(),
            f.level(),
            action,
            c.permissions(),
            hosting));
  }

  /**
   * 현재 사용자 + DatasetRepository 관례({@code "dataset"."id"}, {@code "dataset"."security_level_id"}).
   */
  public Condition visibleCondition() {
    return visibleCondition(
        clearanceResolver.current(),
        field(name("dataset", "id"), Long.class),
        field(name("dataset", "security_level_id"), Long.class));
  }

  /**
   * 목록 쿼리용 조건 — {@link DatasetAccessPolicy#canView} 와 같은 규칙을 SQL 로 옮긴 것. 둘의 일치는
   * DatasetAccessGuardTest#visibleCondition_agreesWithPolicy_acrossMatrix 가 고정한다.
   */
  public Condition visibleCondition(
      Clearance c, Field<Long> datasetIdField, Field<Long> levelIdField) {
    if (c.rank() == Clearance.NO_RANK) {
      return falseCondition();
    }
    var sl = SECURITY_LEVEL.as("vis_sl");
    Condition allowlistOk =
        sl.ALLOWLIST_REQUIRED
            .isFalse()
            .or(c.tenantAdmin() ? sl.ADMIN_BYPASS.isTrue() : falseCondition())
            .or(DatasetAccessRepository.onAllowlistCondition(c, datasetIdField));
    return exists(
        selectOne()
            .from(sl)
            .where(sl.ID.eq(levelIdField))
            .and(sl.RANK.le(c.rank()))
            .and(allowlistOk));
  }

  /**
   * 문자열 SQL 을 조립하는 호출부(시맨틱 검색·스키마 조회)용. 값이 모두 long/boolean 이라 인라인 렌더링이 안전하다.
   *
   * @param datasetAlias 호출부 SQL 에서 dataset 테이블의 별칭(예: "d")
   */
  public String visibleSql(Clearance c, String datasetAlias) {
    return dsl.renderInlined(
        visibleCondition(
            c,
            field(name(datasetAlias, "id"), Long.class),
            field(name(datasetAlias, "security_level_id"), Long.class)));
  }
}
