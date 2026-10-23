package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

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
 * 워크스페이스 분석 자산 관리(analytics:admin, WD-199) 전용 조회·변경.
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
public class AnalyticsWorkspaceRepository {

  private final DSLContext dsl;

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  /**
   * 관리자 목록·단건 판정용 최소 행 — 자산 내용은 담지 않는다.
   *
   * @param ownerId 작성자 id(created_by)
   * @param ownerName 작성자 이름(계정이 지워졌으면 null)
   */
  public record WorkspaceItemRow(
      Long id,
      String name,
      Long ownerId,
      String ownerName,
      boolean isShared,
      LocalDateTime updatedAt) {}

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

  /** 이 워크스페이스의 해당 종류 자산 전부(최근 수정 순). 작성자 이름은 같은 질의에서 조인해 N+1 을 피한다. */
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

  /** 이 워크스페이스의 자산 한 건(최소 행). 다른 워크스페이스·없는 자산이면 빈 값 — 호출자가 404 로 바꾼다. */
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

  /**
   * 관리자 공유 해제 — is_shared 만 false 로 바꾼다. updated_at·updated_by 는 건드리지 않는다: 내용 편집이 아니라 관리 행위라 작성자의
   * 수정 이력처럼 보이면 안 되고, 누가 했는지는 감사 로그가 남긴다.
   */
  public void unshareInTenant(AnalyticsAssetKind kind, Long id, long tenantId) {
    Cols c = Cols.of(kind);
    dsl.update(c.source())
        .set(c.isShared(), false)
        .where(c.id().eq(id).and(c.tenantId().eq(tenantId)))
        .execute();
  }

  /**
   * 관리자 삭제 — 작성자와 무관하게 이 워크스페이스의 자산을 지운다. 작성자 삭제(deleteById)와 같은 단일 DELETE 라 DB ON DELETE CASCADE
   * 결과(쿼리→차트→위젯, 차트→위젯, 대시보드→위젯)가 작성자 삭제와 똑같다.
   */
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
