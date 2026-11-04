package com.smartfirehub.analytics.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository;
import com.smartfirehub.analytics.service.AnalyticsAssetKind;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * AnalyticsWorkspaceRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>워크스페이스 분석 자산 관리(analytics:admin, WD-199) 전용 조회·변경.
 *
 * <p>작성자 경로의 리포지토리(대시보드·차트·저장 쿼리)는 모든 조회·변경에 {@code created_by = 요청자} 또는 가시성(본인 소유·공유) 조건을 건다. 이
 * 리포지토리는 요청 경로에서 작성자 조건 없이 도는 유일한 분석 자산 조회·변경이라 따로 둔다 — 작성자 경로에 섞으면 조건 하나를 빠뜨리는 순간 남의 비공개 자산이 보이거나
 * 지워진다.
 *
 * <ul>
 *   <li><b>최소 열만</b> 읽는다: id·이름·공유 여부·작성자·수정일. SQL 원문·차트 설정·위젯 구성·설명은 읽지 않는다(작성자의 데이터 권한으로 만든 내용
 *       보호).
 *   <li>RLS 에만 기대지 않고 tenant_id 를 명시한다(WD-192 ProactiveJobRepository 선례) — 정책이 빠지거나 바뀌어도 다른 워크스페이스
 *       자산이 보이거나 지워지지 않게.
 *   <li>세 테이블의 해당 열 이름이 같아 종류({@link AnalyticsAssetKind})의 테이블 이름만 바꿔 같은 질의를 쓴다.
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class JooqAnalyticsWorkspaceRepository implements AnalyticsWorkspaceRepository {

  private final DSLContext dsl;

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  /** 종류별 열 묶음 — 테이블 이름만 다르고 열 이름은 같다. */
  private record Cols(
      Table<?> source,
      Field<Long> id,
      Field<String> itemName,
      Field<Boolean> isShared,
      Field<Long> createdBy,
      Field<LocalDateTime> updatedAt,
      Field<Long> tenantId) {

    static Cols of(AnalyticsAssetKind kind) {
      String t = kind.table();
      return new Cols(
          table(name(t)),
          field(name(t, "id"), Long.class),
          field(name(t, "name"), String.class),
          field(name(t, "is_shared"), Boolean.class),
          field(name(t, "created_by"), Long.class),
          field(name(t, "updated_at"), LocalDateTime.class),
          field(name(t, "tenant_id"), Long.class));
    }
  }

  @Override
  public List<WorkspaceItemRow> findWorkspaceRows(AnalyticsAssetKind kind, long tenantId) {
    Cols c = Cols.of(kind);
    return dsl.select(c.id(), c.itemName(), c.createdBy(), U_NAME, c.isShared(), c.updatedAt())
        .from(c.source())
        .leftJoin(USER_TABLE)
        .on(U_ID.eq(c.createdBy()))
        .where(c.tenantId().eq(tenantId))
        .orderBy(c.updatedAt().desc(), c.id().desc())
        .fetch(r -> toRow(r, c));
  }

  @Override
  public Optional<WorkspaceItemRow> findWorkspaceRow(
      AnalyticsAssetKind kind, Long id, long tenantId) {
    Cols c = Cols.of(kind);
    return dsl.select(c.id(), c.itemName(), c.createdBy(), U_NAME, c.isShared(), c.updatedAt())
        .from(c.source())
        .leftJoin(USER_TABLE)
        .on(U_ID.eq(c.createdBy()))
        .where(c.id().eq(id).and(c.tenantId().eq(tenantId)))
        .fetchOptional(r -> toRow(r, c));
  }

  @Override
  public void unshareInTenant(AnalyticsAssetKind kind, Long id, long tenantId) {
    Cols c = Cols.of(kind);
    dsl.update(c.source())
        .set(c.isShared(), false)
        .where(c.id().eq(id).and(c.tenantId().eq(tenantId)))
        .execute();
  }

  @Override
  public void deleteInTenant(AnalyticsAssetKind kind, Long id, long tenantId) {
    Cols c = Cols.of(kind);
    dsl.deleteFrom(c.source()).where(c.id().eq(id).and(c.tenantId().eq(tenantId))).execute();
  }

  private static WorkspaceItemRow toRow(Record r, Cols c) {
    return new WorkspaceItemRow(
        r.get(c.id()),
        r.get(c.itemName()),
        r.get(c.createdBy()),
        r.get(U_NAME),
        Boolean.TRUE.equals(r.get(c.isShared())),
        r.get(c.updatedAt()));
  }
}
