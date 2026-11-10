package com.smartfirehub.securitylevel.pythonread;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * PYTHON 읽기 슬롯 세션 끊기 <b>미확인 표식</b>의 저장소(WD-701 4차) — 데이터 저장소 표 {@code
 * public.python_read_unconfirmed_slot}(플랫폼 V159 / 데이터 저장소 V2)에 둔다.
 *
 * <p><b>왜 DB 인가.</b> 표식은 예전에 {@link PythonReadSessionTerminator} 메모리에만 있어, 롤링 업데이트 중 다른 API 인스턴스의
 * 실행 준비(JIT)가 표식을 보지 못하고 옛 세션이 살아 있는 슬롯을 넓힐 수 있었다. 이제 모든 인스턴스가 같은 행을 본다. 메모리 표식은 이 표에 쓰지 못했을 때(데이터
 * 저장소 장애)의 대비책으로만 남는다 — 실행 준비는 두 쪽을 합쳐 본다({@link #read}).
 *
 * <p><b>왜 데이터 저장소인가.</b> 비교 대상인 {@code pg_stat_activity.backend_start} 가 그 서버의 시계다. 표식 시각을 같은 서버의
 * {@code clock_timestamp()} 로 적으므로 분리 설치에서도 시계 차이가 없다. 플랫폼이 Tibero 여도 PG SQL 그대로 돈다.
 *
 * <p><b>종류.</b>
 *
 * <ul>
 *   <li>{@link Kind#REDEFINED} — 등급 정의 변경으로 슬롯 k 의 뜻이 바뀌었다. 커밋 직전에 옛 정의로 슬롯을 받은 실행이 표식 <b>뒤</b>에
 *       접속했을 수도 있어 시작 시각으로 가를 수 없다 — 실행 준비는 그 슬롯의 세션이 하나도 없을 때만 넓힌다(예전 규칙 그대로).
 *   <li>{@link Kind#STALE_SESSION} — 데이터셋 좁히기의 회수 미확인·회수 뒤 끊기 미확인. 슬롯의 뜻은 그대로라, 표식 뒤에 시작한 세션은 실행
 *       준비가 그 슬롯을 커밋된 등급으로 맞춘 뒤 붙었거나 회수가 이미 반영된 뒤다 — 표식 <b>전에</b> 시작한 세션만 끊는다. 늦게 붙은 옛 준비 세션은 실행기
 *       뒷정리(회수 뒤 시작 시각과 무관한 끊기)가 닫는다.
 * </ul>
 *
 * <p><b>지우기 규칙(인스턴스 사이에도 옳게).</b>
 *
 * <ul>
 *   <li>실행 준비: 읽은 행의 {@code mark_version}·{@code marked_at} 이 둘 다 그대로일 때만 지운다 — 그 사이 다른 인스턴스가 다시 단
 *       표식(번호가 오름)도, 지워졌다가 새로 단 표식(번호가 1 로 다시 시작하지만 시각이 다름 — 리뷰 1)도 지우지 않는다. REDEFINED 는 지우는 대신
 *       {@link Kind#REDEFINED_APPLIED} 로 바꾼다.
 *   <li>실행기의 시작 시각과 무관한 끊기: 끊기 <b>전</b>에 데이터 저장소 시계로 읽은 시각 이하의 STALE_SESSION 표식만 지운다.
 *   <li>등급 정의 변경 동기화: 잠금 안에서 등급을 읽기 전에 읽은 시각 이하의 REDEFINED·REDEFINED_APPLIED 표식만 지운다(그 뒤 커밋된 정의 변경의
 *       표식은 남는다).
 * </ul>
 */
@Slf4j
final class PythonReadUnconfirmedMarks {

  /** 표식 종류 — DB {@code kind} 열 값과 같다. */
  enum Kind {
    REDEFINED,
    /**
     * 커밋 뒤 실행 준비가 REDEFINED 슬롯을 확인·넓힌 뒤 남기는 흔적(WD-701 4차 리뷰 3) — 실행 준비는 무시한다(그 슬롯의 정상 실행을 거듭 끊지
     * 않게). 등급 정의 변경 실행기 작업은 이 슬롯을 다시 확인·끊는다: 커밋 직전에 옛 정의로 준비한 스크립트가 그 실행 준비 <b>뒤에</b> 늦게 붙었을 수 있고,
     * PG 는 Tibero 와 달리 스크립트 토큰 무효화가 없다. 작업이 적용하면 지운다.
     */
    REDEFINED_APPLIED,
    STALE_SESSION
  }

  /** 표 한 행(한 종류). */
  record Row(Kind kind, OffsetDateTime markedAt, long version) {}

  /**
   * 한 슬롯의 표식들 — DB 행(종류마다 최대 하나)과 메모리 대비 표식 순번(없으면 null).
   *
   * @param rows DB 행들
   * @param memorySeq 메모리 대비 표식 순번(없으면 null)
   */
  record SlotMarks(List<Row> rows, Long memorySeq) {

    /** 시작 시각과 무관하게 그 슬롯 세션을 모두 봐야 하는가 — 등급 정의 변경 표식이나 메모리 대비 표식(종류를 모른다)이 있으면. */
    boolean allSessions() {
      return memorySeq != null || rows.stream().anyMatch(r -> r.kind() == Kind.REDEFINED);
    }

    /**
     * 끊고 확인할 세션의 기준 시각 — 이 시각 전에 시작한 세션만 본다. null 이면 전부. STALE_SESSION 만 있으면 가장 늦은 표식 시각(종류마다 한 행이라
     * 하나뿐).
     */
    OffsetDateTime cutoff() {
      if (allSessions()) {
        return null;
      }
      OffsetDateTime latest = null;
      for (Row r : rows) {
        if (latest == null || r.markedAt().isAfter(latest)) {
          latest = r.markedAt();
        }
      }
      return latest;
    }
  }

  /** 표 이름(스키마 한정 — 데이터 저장소 연결의 search_path 와 무관하게). */
  static final String TABLE = "public.python_read_unconfirmed_slot";

  /**
   * 커밋 콜백에서 표식을 쓸 연결을 기다리는 상한 — 콜백은 바깥 플랫폼 커넥션을 쥔 채 돈다(WD-664 9). 풀에 쉬는 연결이 없으면 아예 기다리지 않고, 접속이 느리면
   * 이만큼만 기다린 뒤 메모리 표식 + 실행기 재시도로 넘긴다.
   */
  static final Duration MARK_CONNECT_WAIT = Duration.ofSeconds(1);

  /** 표식 쓰기 문장·네트워크 상한(밀리초) — 데이터 저장소가 멈춰도 커밋 콜백이 오래 붙잡히지 않게. */
  static final int MARK_STATEMENT_MILLIS = 3_000;

  /** 상한 안에서 데이터 저장소 연결을 빌린다(못 빌리면 예외) — {@link PythonReadGrantSync} 의 빌리기 전용 스레드 풀을 쓴다. */
  private final Function<Duration, Connection> borrower;

  /** 메모리 대비 표식(데이터 저장소에 쓰지 못했을 때) — 끊기 장치가 들고 있다. */
  private final PythonReadSessionTerminator terminator;

  PythonReadUnconfirmedMarks(
      Function<Duration, Connection> borrower, PythonReadSessionTerminator terminator) {
    this.borrower = borrower;
    this.terminator = terminator;
  }

  /**
   * 슬롯마다 표식을 단다(이미 있으면 시각을 더 늦게 미루고 번호를 올린다). 상한 안에서 따로 빌린 연결로 곧바로 커밋한다 — 커밋 콜백·실행기 콜백에서 불리므로 그
   * 스레드에 묶인 트랜잭션(이미 끝난)에 합류하면 안 된다.
   *
   * @return 썼으면 true. 연결·문장 실패면 ERROR 로그와 false — 부르는 쪽이 메모리 표식과 재시도로 대비한다
   */
  boolean persist(long tenantId, Set<Integer> slots, Kind kind) {
    if (slots.isEmpty()) {
      return true;
    }
    try (Connection conn = borrower.apply(MARK_CONNECT_WAIT)) {
      PythonReadGrantSync.applyNetworkTimeout(conn, MARK_STATEMENT_MILLIS);
      conn.setAutoCommit(true);
      DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
      ctx.query(
              "insert into "
                  + TABLE
                  + " as m (tenant_id, slot, kind, marked_at)"
                  + " select {0}, s, {1}, clock_timestamp() from unnest({2}::smallint[]) as s"
                  + " on conflict (tenant_id, slot, kind) do update set"
                  + " marked_at = greatest(m.marked_at, excluded.marked_at),"
                  + " mark_version = m.mark_version + 1",
              DSL.val(tenantId),
              DSL.val(kind.name()),
              DSL.val(slots.stream().map(Integer::shortValue).toArray(Short[]::new)))
          .queryTimeout(Math.max(1, MARK_STATEMENT_MILLIS / 1000))
          .execute();
      return true;
    } catch (SQLException | RuntimeException e) {
      log.error(
          "PYTHON 슬롯 미확인 표식을 데이터 저장소에 쓰지 못했다 — 이 인스턴스 메모리 표식으로 막고 다시 쓴다(tenant={} slots={} kind={})",
          tenantId,
          slots,
          kind,
          e);
      return false;
    }
  }

  /**
   * 그 테넌트의 표식들(슬롯 → 표식) — DB 행과 메모리 대비 표식을 합친다. 실행 준비용이라 {@link Kind#REDEFINED_APPLIED} 는 뺀다.
   * {@code ctx} 는 잠금 트랜잭션 안이면 그 트랜잭션에 묶인 데이터 저장소 DSL, 밖이면 같은 DSL(자동 커밋)이다. 읽기 실패는 그대로 던진다 — 실행 준비가
   * 실행을 거부한다(fail-closed).
   */
  Map<Integer, SlotMarks> read(DSLContext ctx, long tenantId) {
    Map<Integer, List<Row>> rows = new TreeMap<>();
    for (var r :
        ctx.fetch(
            "select slot::int, kind, marked_at, mark_version from "
                + TABLE
                + " where tenant_id = {0} and kind <> {1}",
            DSL.val(tenantId),
            DSL.val(Kind.REDEFINED_APPLIED.name()))) {
      rows.computeIfAbsent(r.get(0, Integer.class), k -> new ArrayList<>())
          .add(
              new Row(
                  Kind.valueOf(r.get(1, String.class)),
                  r.get(2, OffsetDateTime.class),
                  r.get(3, Long.class)));
    }
    Map<Integer, Long> memory = terminator.unconfirmedMarks(tenantId);
    Map<Integer, SlotMarks> marks = new TreeMap<>();
    rows.forEach((slot, list) -> marks.put(slot, new SlotMarks(list, memory.get(slot))));
    memory.forEach((slot, seq) -> marks.putIfAbsent(slot, new SlotMarks(List.of(), seq)));
    return marks;
  }

  /**
   * 실행 준비가 그 슬롯의 (기준 시각 전) 세션이 없음을 잠금 안에서 확인하고 슬롯을 맞춘 뒤, <b>읽은 그대로인</b> 표식만 정리한다(잠금 트랜잭션과 함께 커밋).
   * 번호와 시각을 함께 본다(리뷰 1): 실행기가 그 사이 표식을 지우고 커밋 콜백이 새로 달면 번호는 기본값 1 로 다시 시작하므로 번호만으로는 "읽은 그 표식" 을 가를
   * 수 없다 — 새 표식은 시각이 더 늦다.
   *
   * <ul>
   *   <li>STALE_SESSION: 지운다.
   *   <li>REDEFINED: 지우는 대신 {@link Kind#REDEFINED_APPLIED} 로 바꾼다(같은 표식 시각) — 이 슬롯의 다음 실행 준비는 더 끊지
   *       않고, 등급 정의 변경 실행기 작업이 늦게 붙은 옛 정의 세션을 다시 확인한다.
   * </ul>
   */
  void clearAfterRun(DSLContext ctx, long tenantId, int slot, SlotMarks marks) {
    for (Row r : marks.rows()) {
      int deleted =
          ctx.execute(
              "delete from "
                  + TABLE
                  + " where tenant_id = {0} and slot = {1} and kind = {2} and mark_version = {3}"
                  + " and marked_at = {4}",
              DSL.val(tenantId),
              DSL.val((short) slot),
              DSL.val(r.kind().name()),
              DSL.val(r.version()),
              DSL.val(r.markedAt()));
      if (deleted > 0 && r.kind() == Kind.REDEFINED) {
        ctx.execute(
            "insert into "
                + TABLE
                + " as m (tenant_id, slot, kind, marked_at) values ({0}, {1}, {2}, {3})"
                + " on conflict (tenant_id, slot, kind) do update set"
                + " marked_at = greatest(m.marked_at, excluded.marked_at),"
                + " mark_version = m.mark_version + 1",
            DSL.val(tenantId),
            DSL.val((short) slot),
            DSL.val(Kind.REDEFINED_APPLIED.name()),
            DSL.val(r.markedAt()));
      }
    }
  }

  /**
   * 등급 정의 변경 실행기 작업용 — {@code upTo} 이하에 단 {@link Kind#REDEFINED_APPLIED} 슬롯들(커밋 뒤 실행 준비가 이미 넓힌 슬롯).
   * 작업은 이 슬롯도 세션이 없어야 적용한다.
   */
  Set<Integer> appliedSlots(DSLContext ctx, long tenantId, OffsetDateTime upTo) {
    return new java.util.TreeSet<>(
        ctx.fetch(
                "select slot::int from "
                    + TABLE
                    + " where tenant_id = {0} and kind = {1} and marked_at <= {2}",
                DSL.val(tenantId),
                DSL.val(Kind.REDEFINED_APPLIED.name()),
                DSL.val(upTo))
            .getValues(0, Integer.class));
  }

  /** 메모리 대비 표식을 읽은 순번 그대로일 때만 지운다 — 잠금 트랜잭션이 커밋된 뒤 부른다. */
  void clearMemoryAfterRun(long tenantId, int slot, SlotMarks marks) {
    if (marks.memorySeq() != null) {
      terminator.clearUnconfirmedIfUnchanged(tenantId, slot, marks.memorySeq());
    }
  }

  /**
   * 시작 시각과 무관하게 그 슬롯 세션을 모두 끊은 뒤 — 끊기 전에 읽은 시각({@code upTo}) 이하의 STALE_SESSION 표식을 지운다. 끊기 뒤에 단 표식은
   * 남는다. REDEFINED 는 지우지 않는다(실행 준비·등급 정의 변경 동기화만 지운다).
   *
   * @return 지운 행 수
   */
  int clearStaleUpTo(DSLContext ctx, long tenantId, Set<Integer> slots, OffsetDateTime upTo) {
    if (slots.isEmpty()) {
      return 0;
    }
    return ctx.execute(
        "delete from "
            + TABLE
            + " where tenant_id = {0} and kind = {1} and slot = any({2}::smallint[])"
            + " and marked_at <= {3}",
        DSL.val(tenantId),
        DSL.val(Kind.STALE_SESSION.name()),
        DSL.val(slots.stream().map(Integer::shortValue).toArray(Short[]::new)),
        DSL.val(upTo));
  }

  /**
   * 등급 정의 변경 동기화가 잠금 안에서 모든 슬롯을 커밋된 정의로 맞춘 뒤 — 등급을 읽기 전에 읽은 시각({@code upTo}) 이하의 REDEFINED·
   * REDEFINED_APPLIED 표식을 지운다(그 동기화의 트랜잭션과 함께 커밋). 그 뒤에 커밋된 정의 변경의 표식은 남는다.
   */
  int clearRedefinedUpTo(DSLContext ctx, long tenantId, OffsetDateTime upTo) {
    return ctx.execute(
        "delete from "
            + TABLE
            + " where tenant_id = {0} and kind in ({1}, {2}) and marked_at <= {3}",
        DSL.val(tenantId),
        DSL.val(Kind.REDEFINED.name()),
        DSL.val(Kind.REDEFINED_APPLIED.name()),
        DSL.val(upTo));
  }

  /** 데이터 저장소 시계의 지금({@code clock_timestamp()}) — 표식 시각과 같은 시계로 비교하려고. */
  static OffsetDateTime dataStoreNow(DSLContext ctx) {
    return ctx.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }
}
