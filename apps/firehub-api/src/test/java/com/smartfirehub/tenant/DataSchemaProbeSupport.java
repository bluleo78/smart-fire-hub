package com.smartfirehub.tenant;

import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.regex.Pattern;
import org.jooq.DSLContext;

/**
 * {@code data} 스키마에 <b>검증용 프로브 테이블</b>을 심고 지운다 — P3-a 의 스키마 가드들이 공허하게
 * 통과하지 않도록 만드는 토대.
 *
 * <p><b>왜 있는가(두 개의 실패에서 나왔다).</b>
 *
 * <ul>
 *   <li>test DB 의 {@code data} 스키마에는 객체가 하나도 없다(dev 에는 25개). P1 Task 7 의 스키마
 *       소유권 가드는 이 빈 스키마를 열거하며 2108건이 통과했지만, 열거 결과가 0행이라 실제로는
 *       아무것도 검사하지 않았다. 가드가 의미를 가지려면 검사 대상이 먼저 존재해야 한다.
 *   <li>같은 가드는 프로브를 <b>런타임 롤로</b> 만들었다. 그래서 프로브는 언제나 {@code app_tenant}
 *       소유였고, "소유자가 {@code app} 인 테이블" 이라는 문제 상황을 재현할 수 없어 단언이 결코
 *       실패할 수 없었다. 그래서 이 헬퍼는 소유자를 <b>호출자가 넘긴 {@link DSLContext} 의 롤</b>로
 *       결정한다 — 소유자 자격증명(app)으로 만들면 app 소유, 런타임 자격증명으로 만들면 app_tenant
 *       소유가 된다. 헬퍼가 소유권을 임의로 정규화하지 않는 것이 핵심이다.
 * </ul>
 *
 * <p><b>왜 접두어를 본문에서 강제하는가.</b> {@code smartfirehub_test} 는 다른 세션과 <b>공유</b>된다.
 * P2-f 에서 정리 헬퍼의 제약이 이름 규약(=주석)에만 있었던 탓에 다른 세션의 행 3946건을 지운 사고가
 * 있었다. 그래서 {@link #PROBE_PREFIX} 검사는 규약이 아니라 코드로 막는다 — 접두어가 없으면
 * {@link IllegalArgumentException} 이고, 생성도 삭제도 일어나지 않는다.
 *
 * <p>테스트 전용 클래스라 {@code src/test} 에 둔다. 프로덕션 코드는 이 헬퍼를 알지 못한다.
 */
public final class DataSchemaProbeSupport {

  private DataSchemaProbeSupport() {}

  /** 이 헬퍼가 만들고 지울 수 있는 유일한 이름 접두어. */
  public static final String PROBE_PREFIX = "p3probe_";

  // 식별자는 문자열로 조립되므로(동적 스키마 DDL 은 바인드 파라미터를 쓸 수 없다) 형태를 못박아
  // 인젝션 여지를 없앤다. DataTableService 가 동적 테이블명에 쓰는 패턴과 같은 규칙이다.
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

  /**
   * 실행마다 고유한 프로브 이름을 만든다. 공유 test DB 에서 동시 실행이 서로의 프로브를 만들거나
   * 지우는 것을 막으려면 고정 이름을 쓰면 안 된다 — 접두어가 같으면 남의 것도 가드를 통과하기
   * 때문이다. 접미어 발급은 {@link TenantRlsTestSupport#nextTenantId()} 를 재사용한다(프로세스마다
   * 겹치지 않는 값에서 증가한다).
   */
  public static String uniqueProbeName(String label) {
    return PROBE_PREFIX + label + "_" + TenantRlsTestSupport.nextTenantId();
  }

  /**
   * 프로브 테이블을 하나 만든다. 소유자는 {@code owner} 커넥션이 접속한 롤이 된다.
   *
   * <p>{@code IF NOT EXISTS} 를 쓰지 <b>않는다</b>. 같은 이름이 이미 있으면 조용히 넘어가는 대신
   * 시끄럽게 실패해야 한다 — 넘어가면 이후 단언이 <b>남이 만든 테이블</b>의 소유자를 읽고도 통과해
   * 정확히 P1 Task 7 의 공허함을 되풀이한다.
   *
   * @param owner 이 커넥션의 롤이 곧 프로브의 소유자다
   * @param tableName {@link #PROBE_PREFIX} 로 시작해야 한다
   */
  public static void createProbeTable(DSLContext owner, String schema, String tableName) {
    owner.execute("create table " + qualify(schema, tableName) + " (id bigint primary key, v text)");
  }

  /**
   * 프로브 테이블을 지운다. app 소유 프로브는 app_tenant 로 DROP 할 수 없으므로, 만들 때 쓴 것과
   * 같은(또는 더 강한) 롤의 커넥션을 넘겨야 한다.
   *
   * <p>없는 테이블에 대한 호출은 조용히 끝난다({@code IF EXISTS}) — 정리는 멱등해야 앞선 단언이
   * 실패해 프로브가 안 만들어진 경우에도 teardown 이 원래 실패를 가리지 않는다.
   */
  public static void dropProbeTable(DSLContext owner, String schema, String tableName) {
    owner.execute("drop table if exists " + qualify(schema, tableName));
  }

  /**
   * 테이블의 소유 롤 이름을 돌려준다. 테이블이 없으면 {@code null}.
   *
   * <p>{@code pg_tables.tableowner} 는 이미 롤 이름으로 해석된 값이라 {@code pg_class}/{@code
   * pg_roles} 조인이 필요 없고, {@code DataSchemaGrantTest} 가 쓰는 열거 뷰와도 같다. 소유권 확인은
   * P3-a 의 모든 스키마 가드가 반복할 질의이므로 여기 한 곳에 둔다.
   */
  public static String tableOwner(DSLContext dsl, String schema, String tableName) {
    return (String)
        dsl.fetchValue(
            "select tableowner from pg_tables where schemaname = ? and tablename = ?",
            schema,
            tableName);
  }

  /**
   * 스키마·테이블 이름을 검증하고 정규화된 식별자로 합친다.
   *
   * <p>접두어 검사가 여기(=실행 경로 본문)에 있다는 점이 이 클래스의 계약이다. 이름 규약이나 주석에
   * 맡기면 공유 DB 에서 남의 객체를 건드리는 사고로 이어진다.
   */
  private static String qualify(String schema, String tableName) {
    if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
      throw new IllegalArgumentException("스키마 이름 형태가 올바르지 않다: " + schema);
    }
    if (tableName == null || !tableName.startsWith(PROBE_PREFIX)) {
      throw new IllegalArgumentException(
          "프로브 이름은 '"
              + PROBE_PREFIX
              + "' 로 시작해야 한다(공유 test DB 에서 남의 객체를 건드리지 않기 위한 본문 가드): "
              + tableName);
    }
    if (!IDENTIFIER.matcher(tableName).matches()) {
      throw new IllegalArgumentException("프로브 이름 형태가 올바르지 않다: " + tableName);
    }
    return schema + "." + tableName;
  }
}
