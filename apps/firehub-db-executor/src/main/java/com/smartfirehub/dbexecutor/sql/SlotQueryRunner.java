package com.smartfirehub.dbexecutor.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 슬롯 조회 스트리밍(WD-78, 스펙 §5.2) — Python 스크립트가 db-executor {@code /execute/query} 에 {@code readSlot} 과
 * {@code stream:true} 를 실어 테이블을 끝까지 읽는 길이다. 테넌트의 <b>슬롯 계정</b>({@code GENIA_T{id}_S{k}}, 풀 크기 3 —
 * Ruling 17, 세션 {@code CURRENT_SCHEMA = GENIA_T{id}})으로 실행하므로 볼 수 없는 테이블은 DB 권한(Task 12 의 GRANT)이
 * 거부한다.
 *
 * <p>응답은 NDJSON 이다(두 실행기 같은 형식, 계약 벡터 {@code query.streaming}).
 *
 * <ol>
 *   <li>첫 줄 {@code {"columns":[...]}} — 이름 규칙은 분석 조회와 같다(대문자 전용은 소문자, 겹치면 고유화).
 *   <li>행마다 {@code {"row":{...}}} — 값 변환은 비스트리밍 결과와 같은 {@link ValueConverter}.
 *   <li>마지막 줄 {@code {"success":true,"row_count":N}} 또는 {@code
 *       {"success":false,"error":"...","db_error":{...}|null}}. 실행 전 거부(검증·슬롯 계정 없음·접속 실패)는 이 실패 줄
 *       하나만 나간다. 행을 보낸 뒤 DB 오류·시간 초과가 나면 실패 줄로 끝난다 — 받는 쪽은 마지막 줄을 보고 성공을 판정해야 한다(조용히 잘린 결과를 성공으로 보지
 *       않게).
 * </ol>
 *
 * <p>행 상한이 없다({@code max_rows} 무시, {@code setMaxRows} 없음). 드라이버가 {@link #FETCH_SIZE} 행씩 나눠 가져오고,
 * 응답은 {@link #FLUSH_EVERY} 행마다 내보낸다 — 결과 전체를 메모리에 모으지 않는다. 메모리 한도는 받는 쪽(샌드박스 rlimit)이 지킨다.
 *
 * <p>기한은 호출자가 준 {@link Deadline}(스크립트 토큰 만료까지)이다. 행마다 기한을 확인하고, 실행 중 문장은 기한이 취소한다. 클라이언트가 끊으면(쓰기
 * {@link IOException}) 문장을 취소하고 롤백한 뒤 커넥션을 반납한다 — 남은 행을 읽지 않는다.
 *
 * <p>문장 검사는 분석 조회와 같다({@link QueryRunner#validatedQuery}: SELECT·WITH 만, FOR UPDATE 거부, 외부 스키마·SYS
 * 한정자 거부). 대여·기한·실패 처리 뼈대는 {@link LeasedExecution} 이다.
 */
@Component
public class SlotQueryRunner {

  static final String NO_SLOT_ACCOUNT = "Tibero 슬롯 계정이 준비되지 않았습니다.";

  /** 슬롯 풀(같은 테넌트·같은 슬롯)이 꽉 차 대기 시간 안에 커넥션을 못 얻었을 때(Ruling 17) — 접속 실패와 구분한다. */
  static final String SLOT_BUSY = "같은 등급 슬롯의 동시 조회가 많습니다 — 잠시 후 다시 실행하세요";

  /** 드라이버가 한 번에 가져오는 행 수 — 큰 테이블을 왕복 수 적게, 메모리는 적게. */
  static final int FETCH_SIZE = 1000;

  /** 이 행 수마다 응답을 내보낸다 — 받는 쪽이 진행을 보고, 끊김이 쓰기 오류로 빨리 드러나게. */
  static final int FLUSH_EVERY = 1000;

  /** 응답 버퍼 크기(바이트). */
  private static final int BUFFER_BYTES = 64 * 1024;

  private final TenantPools slotPools;
  private final StoreDescriptorClient descriptors;
  private final ObjectMapper mapper;

  /**
   * @param mapper 스프링의 ObjectMapper — 운영 설정(BigDecimal 지수 표기 없음)을 비스트리밍 응답과 같게 쓰기 위해 주입받는다
   */
  public SlotQueryRunner(
      @Qualifier("slotPools") TenantPools slotPools,
      StoreDescriptorClient descriptors,
      ObjectMapper mapper) {
    this.slotPools = slotPools;
    this.descriptors = descriptors;
    this.mapper = mapper;
  }

  /** 클라이언트 끊김(쓰기 실패) 표지 — 내부 결함과 구분해 롤백·반납만 하고 조용히 끝낸다. */
  private static final class ClientGone extends RuntimeException {
    ClientGone(IOException cause) {
      super(cause);
    }
  }

  /**
   * 검증 → 슬롯 풀 대여 → 스트리밍 → 롤백. 오류는 예외가 아니라 마지막 실패 줄로 쓴다.
   *
   * @param d 테넌트 기술자(슬롯 계정 포함)
   * @param slot 읽기 슬롯(1~10) — 스크립트 토큰의 값
   * @param query 조회 한 문장
   * @param deadline 전체 기한. 닫기는 호출자 몫이다
   * @param out 응답 본문 — 끝나면 flush 하고 닫지 않는다
   * @throws IOException 마지막 줄을 쓰지 못했을 때(클라이언트가 이미 끊음) — 커넥션은 이미 반납됐다
   */
  public void stream(StoreDescriptor d, int slot, String query, Deadline deadline, OutputStream out)
      throws IOException {
    BufferedOutputStream buffered = new BufferedOutputStream(out, BUFFER_BYTES);
    Map<String, Object> last = run(d, slot, query, deadline, buffered);
    if (last == null) {
      // 클라이언트가 끊었다 — 버퍼에 남은 것을 더 보내려 하지 않는다(커넥션은 이미 반납됐다).
      return;
    }
    writeLine(buffered, last);
    buffered.flush();
  }

  /** 실행하고 마지막 줄을 돌려준다. 클라이언트가 끊었으면 null(더 쓸 곳이 없다). */
  private Map<String, Object> run(
      StoreDescriptor cached, int slot, String query, Deadline deadline, OutputStream out) {
    DescriptorLookup.Resolved resolved;
    try {
      // 요청 슬롯의 계정이 캐시에 없으면 한 번 새로 받아 확인한다(WD-263, 근거는 DescriptorLookup).
      resolved =
          DescriptorLookup.resolve(
              descriptors,
              cached,
              query,
              desc -> desc.slot(slot) != null,
              NO_SLOT_ACCOUNT,
              deadline);
    } catch (TiberoSqlGuard.Rejected e) {
      return failure(e.getMessage(), null);
    }
    if (resolved.failure() != null) {
      return failure(resolved.failure(), null);
    }
    StoreDescriptor d = resolved.descriptor();
    String sql = resolved.body();
    String timeout = QueryRunner.timeoutMessage(deadline.totalSeconds());
    LeasedExecution execution =
        new LeasedExecution(desc -> slotPools.borrow(desc, slot), descriptors);
    return execution.withLease(
        d,
        deadline,
        // 슬롯 풀 고갈만 접속 실패와 구분한다(Ruling 17).
        LeasedExecution.failures(timeout, SLOT_BUSY, SlotQueryRunner::failure),
        c -> {
          long rows;
          try {
            rows =
                deadline.execute(
                    c, deadline.totalSeconds(), st -> streamRows(st, sql, deadline, out));
          } catch (ClientGone e) {
            // 끊김: 문장은 streamRows 가 취소했다. 트랜잭션을 닫고(try-with-resources 가 커넥션을 반납) 더 쓰지 않는다.
            LeasedExecution.rollbackQuietly(c);
            return null;
          }
          // 읽기만 했지만 트랜잭션을 닫아 커넥션을 반납한다(실패 경로의 ROLLBACK 은 LeasedExecution 이 한다).
          LeasedExecution.rollbackQuietly(c);
          Map<String, Object> ok = new LinkedHashMap<>();
          ok.put("success", true);
          ok.put("row_count", rows);
          return ok;
        });
  }

  /** 문장을 실행해 컬럼 줄과 행 줄을 쓴다. 쓰기 실패면 문장을 취소하고 {@link ClientGone} 을 던진다. */
  private long streamRows(Statement st, String body, Deadline deadline, OutputStream out)
      throws SQLException {
    st.setFetchSize(FETCH_SIZE);
    try (ResultSet rs = st.executeQuery(body)) {
      SqlRunner.Columns cols = SqlRunner.columns(rs.getMetaData(), true);
      write(st, out, Map.of("columns", cols.names()));
      flush(st, out);
      long count = 0;
      while (rs.next()) {
        if (deadline.expired()) {
          // 드라이버가 버퍼에서 주는 행은 cancel 로 끊기지 않는다 — 행마다 확인해 기한을 지킨다. LeasedExecution 이 시간 초과로 판정한다.
          throw new SQLTimeoutException("기한 경과");
        }
        write(st, out, Map.of("row", SqlRunner.row(rs, cols)));
        if (++count % FLUSH_EVERY == 0) {
          flush(st, out);
        }
      }
      return count;
    }
  }

  private void write(Statement st, OutputStream out, Object line) {
    try {
      writeLine(out, line);
    } catch (IOException e) {
      throw gone(st, e);
    }
  }

  private static void flush(Statement st, OutputStream out) {
    try {
      out.flush();
    } catch (IOException e) {
      throw gone(st, e);
    }
  }

  /** 끊김 처리: 서버 쪽 문장을 멈춘 뒤 표지를 돌려준다. */
  private static ClientGone gone(Statement st, IOException e) {
    try {
      st.cancel();
    } catch (SQLException | RuntimeException ignored) {
      // 이미 끝난 문장의 취소 실패는 무시한다 — 닫기·롤백이 이어진다.
    }
    return new ClientGone(e);
  }

  /**
   * JSON 한 줄 + 줄바꿈. 대상 스트림을 닫지 않으려고 바이트로 만든 뒤 쓴다(Jackson 의 writeValue 는 대상을 닫는다). 직렬화 실패는 끊김이 아니라
   * 내부 결함이라 IOException 으로 내보내지 않는다(그러면 끊김으로 오인된다).
   */
  private void writeLine(OutputStream out, Object line) throws IOException {
    byte[] json;
    try {
      json = mapper.writeValueAsBytes(line);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("결과 행을 JSON 으로 바꾸지 못했습니다", e);
    }
    out.write(json);
    out.write('\n');
  }

  /**
   * 실패 마지막 줄 — {@code db_error} 키는 값이 없어도 싣는다(계약 벡터의 실패 줄 키, 순서 success·error·db_error). 컨트롤러의 실행 전
   * 거부 줄도 이것으로 만들어 두 곳의 NDJSON 실패 모양이 갈리지 않게 한다.
   */
  public static Map<String, Object> failure(String error, DbError dbError) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("success", false);
    m.put("error", error);
    m.put("db_error", dbError);
    return m;
  }
}
