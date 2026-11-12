package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * 묶음 ④(알림·채널, WD-709)·⑤(선제 실행·분석, WD-710) MyBatis Tibero 판이 기댈 문법을 공유 개발 Tibero 에서 먼저 확인하는 스파이크.
 *
 * <ul>
 *   <li>(a) 긴 JSON(htmlContent 10KB) — {@code JSON_EXISTS} 로 키 존재 판정, 짧은 키는 {@code JSON_VALUE} 로
 *       추출, JSON 전체는 문자열로 읽기(JSON_VALUE 는 4000바이트 상한이라 긴 값에 쓰지 않는다). 빈 문자열 값·키 없음도 같이 기록한다.
 *   <li>(b) {@code uk_user_channel} 과 같은 CASE 함수 유일 색인 대상 MERGE — ON 절에 같은 CASE 식을 써서 workspace 가
 *       있으면 한 행을 유지하고 NULL workspace 는 매번 새 행이 되는지(PG 의 NULL 낀 다열 UNIQUE 와 같은 동작).
 *   <li>(d) 리포트 판정(WD-709 fix C1) — PG {@code result ->> 'htmlContent' IS NOT NULL AND <> ''} 와 같은
 *       판정을 Tibero SQL 로 내는 후보식(JSON_EXISTS 필터·strict·IS JSON OBJECT·첫 글자 등)을 행별로 기록하고,
 *       title·summary 를 JSON_VALUE 로 꺼낼 때 4000바이트 초과·객체 값·빈 문자열이 어떻게 되는지 기록한다.
 *   <li>(e) JSON 배열 길이(WD-709 fix C2) — {@code $.size()}·JSON_TABLE COUNT 후보를 기록한다.
 *   <li>(c) {@code TRUNC(ts)} 가 날짜 버킷으로 {@code CAST(ts AS DATE)} 와 같은 값인지(PG {@code
 *       date_trunc('day')} 대체 후보).
 * </ul>
 *
 * <p>탐침 테이블은 정책 없는 방언 확인용이라 소유자 계정으로 만들고 끝에서 지운다. 방언이 막히면 막힌 오류를 증거로 남기는 것이 이 스파이크의 결과다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TiberoBundle45SyntaxProbeLiveTest {

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private final String execTable = "fhp_b45_exec_" + suffix;
  private final String bindTable = "fhp_b45_bind_" + suffix;
  private final String tsTable = "fhp_b45_ts_" + suffix;
  private final String rptTable = "fhp_b45_rpt_" + suffix;
  private final String secTable = "fhp_b45_sec_" + suffix;
  private final List<String> createdTables = new ArrayList<>();

  private TiberoPlatformSchema poc;

  @BeforeAll
  void setUp() {
    poc = TiberoPlatformSchema.shared();
  }

  /** 이번 실행이 만든 탐침 테이블만 지운다. */
  @AfterAll
  void tearDown() {
    for (String t : createdTables) {
      try {
        poc.jdbc().execute("DROP TABLE " + t);
      } catch (RuntimeException e) {
        TiberoTestEvidence.write(
            "B45PROBE", "drop " + t + " 실패 " + TiberoTestEvidence.rootMessage(e));
      }
    }
  }

  private void createTable(String name, String ddl) {
    poc.jdbc().execute(ddl);
    createdTables.add(name);
  }

  /** 10KB 를 넘는 본문(한글은 3바이트). */
  private static String html10k() {
    return "<p>" + "가나다abc ".repeat(1200) + "</p>";
  }

  // ---------------------------------------------------------------- (a) 긴 JSON

  @Test
  @DisplayName("(a) 10KB htmlContent JSON 에서 JSON_EXISTS·짧은 키 JSON_VALUE·전체 읽기가 된다")
  void longJson() {
    createTable(execTable, "CREATE TABLE " + execTable + " (id NUMBER(10), result JSON)");
    String longHtml = html10k();
    String longDoc =
        "{\"title\":\"긴 리포트\",\"summary\":\"요약\",\"htmlContent\":\"" + longHtml + "\"}";
    poc.jdbc().update("INSERT INTO " + execTable + " VALUES (1, ?)", longDoc);
    poc.jdbc()
        .update(
            "INSERT INTO " + execTable + " VALUES (2, ?)",
            "{\"title\":\"빈 본문\",\"htmlContent\":\"\"}");
    poc.jdbc().update("INSERT INTO " + execTable + " VALUES (3, ?)", "{\"title\":\"본문 없음\"}");
    poc.jdbc().update("INSERT INTO " + execTable + " VALUES (4, NULL)");

    List<Long> exists =
        poc.jdbc()
            .queryForList(
                "SELECT id FROM "
                    + execTable
                    + " WHERE JSON_EXISTS(result, '$.htmlContent') ORDER BY id",
                Long.class);
    TiberoTestEvidence.write("B45PROBE", "json_exists $.htmlContent ids=" + exists);

    String title =
        poc.jdbc()
            .queryForObject(
                "SELECT JSON_VALUE(result, '$.title') FROM " + execTable + " WHERE id = 1",
                String.class);
    String summary =
        poc.jdbc()
            .queryForObject(
                "SELECT JSON_VALUE(result, '$.summary') FROM " + execTable + " WHERE id = 1",
                String.class);
    String whole =
        poc.jdbc()
            .queryForObject("SELECT result FROM " + execTable + " WHERE id = 1", String.class);
    TiberoTestEvidence.write(
        "B45PROBE",
        "json_value title="
            + title
            + " summary="
            + summary
            + " whole.length="
            + whole.length()
            + " containsHtml="
            + whole.contains(longHtml));

    // 긴 값·빈 값을 JSON_VALUE 로 꺼내면 어떻게 되는지(기록만 — 쓰지 않는다)
    for (int id : new int[] {1, 2}) {
      try {
        String v =
            poc.jdbc()
                .queryForObject(
                    "SELECT JSON_VALUE(result, '$.htmlContent') FROM "
                        + execTable
                        + " WHERE id = "
                        + id,
                    String.class);
        TiberoTestEvidence.write(
            "B45PROBE",
            "json_value htmlContent id=" + id + ": " + (v == null ? "NULL" : "len " + v.length()));
      } catch (RuntimeException e) {
        TiberoTestEvidence.write(
            "B45PROBE",
            "json_value htmlContent id=" + id + ": ERROR " + TiberoTestEvidence.rootMessage(e));
      }
    }

    assertThat(exists).as("JSON_EXISTS 는 키가 있는 행(빈 문자열 값 포함)").containsExactly(1L, 2L);
    assertThat(title).isEqualTo("긴 리포트");
    assertThat(summary).isEqualTo("요약");
    assertThat(whole).contains(longHtml);
  }

  // ---------------------------------------------------------------- (d) 리포트 판정 후보식

  /** where 절 후보 하나로 고른 id 목록을 기록한다. 문법 오류면 오류를 기록하고 null. */
  private List<Long> probeIds(String tag, String table, String where) {
    try {
      List<Long> ids =
          poc.jdbc()
              .queryForList(
                  "SELECT id FROM " + table + " WHERE " + where + " ORDER BY id", Long.class);
      TiberoTestEvidence.write("B45PROBE", tag + " [" + where + "] ids=" + ids);
      return ids;
    } catch (RuntimeException e) {
      TiberoTestEvidence.write(
          "B45PROBE", tag + " [" + where + "] ERROR " + TiberoTestEvidence.rootMessage(e));
      return null;
    }
  }

  /** select 식 후보 하나를 행마다 읽어 기록한다(문자열은 길이가 200 을 넘으면 길이만). 오류면 오류를 기록하고 null. */
  private Map<Long, String> probeValues(String tag, String table, String expr) {
    try {
      Map<Long, String> values = new TreeMap<>();
      poc.jdbc()
          .query(
              "SELECT id, " + expr + " AS v FROM " + table + " ORDER BY id",
              (RowCallbackHandler) rs -> values.put(rs.getLong("id"), rs.getString("v")));
      Map<Long, String> shown = new TreeMap<>();
      values.forEach(
          (k, v) -> shown.put(k, v == null ? "NULL" : v.length() > 200 ? "len " + v.length() : v));
      TiberoTestEvidence.write("B45PROBE", tag + " [" + expr + "] " + shown);
      return values;
    } catch (RuntimeException e) {
      TiberoTestEvidence.write(
          "B45PROBE", tag + " [" + expr + "] ERROR " + TiberoTestEvidence.rootMessage(e));
      return null;
    }
  }

  @Test
  @DisplayName("(d) PG ->> 리포트 판정과 같은 Tibero 후보식과 title·summary 추출 동작을 기록한다")
  void reportPredicate() {
    createTable(rptTable, "CREATE TABLE " + rptTable + " (id NUMBER(10), result JSON)");
    String longTitle = "제목".repeat(1100); // 6600 바이트 — RETURNING VARCHAR2(4000) 한도 초과
    String hugeTitle = "제목".repeat(12_500); // 75000 바이트 — VARCHAR2 최대(65532 바이트)도 넘는다
    String html70k = "<p>" + "가나다abc ".repeat(6000) + "</p>"; // 약 72KB
    String[] docs = {
      /* 1 */ "{\"title\":\"긴 리포트\",\"summary\":\"요약\",\"htmlContent\":\"" + html10k() + "\"}",
      /* 2 */ "{\"title\":\"빈 본문\",\"summary\":\"\",\"htmlContent\":\"\"}",
      /* 3 */ "{\"title\":\"본문 없음\"}",
      /* 4 */ null,
      /* 5 */ "[{\"htmlContent\":\"x\"}]",
      /* 6 */ "{\"htmlContent\":5}",
      /* 7 */ "{\"htmlContent\":null}",
      /* 8 */ "{\"htmlContent\":{\"a\":1}}",
      /* 9 */ "{\"htmlContent\":false}",
      /* 10 */ "{\"htmlContent\":\" \"}",
      /* 11 */ "{\"title\":\""
          + longTitle
          + "\",\"summary\":\""
          + longTitle
          + "\",\"htmlContent\":\"x\"}",
      /* 12 */ "{\"title\":\"  보고서  \",\"htmlContent\":\"x\"}",
      /* 13 */ "{\"title\":{\"a\":1},\"summary\":7,\"htmlContent\":\"x\"}",
      /* 14 */ "{\"htmlContent\":[]}",
      /* 15 */ "{\"htmlContent\":\"0\"}",
      /* 16 */ "{\"title\":\"\",\"summary\":\"\",\"htmlContent\":\"x\"}",
      /* 17 */ "{\"title\":\"   \",\"htmlContent\":\"x\"}",
      /* 18 */ "{\"title\":\""
          + hugeTitle
          + "\",\"summary\":\""
          + hugeTitle
          + "\",\"htmlContent\":\"x\"}",
      /* 19 */ "{\"title\":\"큰 본문\",\"htmlContent\":\"" + html70k + "\"}"
    };
    for (int i = 0; i < docs.length; i++) {
      poc.jdbc().update("INSERT INTO " + rptTable + " VALUES (?, ?)", i + 1, docs[i]);
    }
    // PG 판정(->> 가 NULL 이 아니고 '' 가 아님): 최상위 배열(5)·키 없음(3)·NULL(4)·JSON null(7)·빈 문자열(2) 만 빠진다.
    TiberoTestEvidence.write(
        "B45PROBE", "rpt PG expected ids=[1, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]");

    // 1·2차 실측(기록): strict 경로·IS JSON [OBJECT]·$.type()·$.size() 는 문법 오류 또는 0행/NULL, 필터식의 빈 문자열
    // 리터럴("")은 JDBC-23004(빈 따옴표 식별자로 읽힘), ?(@ == null) 은 JSON null 값도 못 맞힌다. 첫 글자
    // SUBSTR(result,1,1) 은
    // 10KB 문서까지는 되지만 70KB·150KB 문서가 있는 표에서 서버 연결을 끊는다(JDBC-90405 End Of Stream) — 쓰지 않는다.
    // JSON_TABLE '$[*]' 는 최상위 객체에서 0행, 배열에서 원소 수만큼 행을 낸다((e) 실측) — 최상위 배열 판정에 쓴다.
    String[] predicates = {
      "JSON_EXISTS(result, '$.htmlContent?(@ == \"x\")')",
      TOP_LEVEL_NOT_ARRAY,
      "DBMS_LOB.GETLENGTH(JSON_VALUE(result, '$.htmlContent' RETURNING CLOB)) > 0",
      REPORT_PREDICATE_SCALAR
    };
    for (String p : predicates) {
      probeIds("rpt", rptTable, p);
    }

    String[] exprs = {
      "DBMS_LOB.GETLENGTH(JSON_VALUE(result, '$.htmlContent' RETURNING CLOB))",
      "LENGTH(JSON_VALUE(result, '$.htmlContent'))",
      "JSON_QUERY(result, '$.htmlContent' RETURNING CLOB)",
      "DBMS_LOB.SUBSTR(JSON_QUERY(result, '$.htmlContent' RETURNING CLOB), 1, 1)",
      "LENGTH(JSON_VALUE(result, '$.title'))",
      "COALESCE(TRIM(JSON_VALUE(result, '$.title')), 'JOB')",
      "JSON_VALUE(result, '$.summary')",
      "COALESCE(TRIM(JSON_VALUE(result, '$.title' RETURNING CLOB)), 'JOB')",
      "JSON_VALUE(result, '$.title')",
      "JSON_VALUE(result, '$.title' RETURNING VARCHAR2(4000))",
      "JSON_QUERY(result, '$.title')",
      "JSON_VALUE(result, '$.summary' RETURNING CLOB)"
    };
    for (String e : exprs) {
      probeValues("rpt", rptTable, e);
    }
    // VARCHAR2 최대를 넘는 title 을 ERROR ON ERROR 로 꺼내면 오류인지(기본 NULL ON ERROR 와 비교용)
    for (int id : new int[] {11, 18}) {
      try {
        String v =
            poc.jdbc()
                .queryForObject(
                    "SELECT JSON_VALUE(result, '$.title' ERROR ON ERROR) FROM "
                        + rptTable
                        + " WHERE id = "
                        + id,
                    String.class);
        TiberoTestEvidence.write(
            "B45PROBE",
            "rpt title id="
                + id
                + " ERROR ON ERROR -> "
                + (v == null ? "NULL" : "len " + v.length()));
      } catch (RuntimeException e) {
        TiberoTestEvidence.write(
            "B45PROBE",
            "rpt title id=" + id + " ERROR ON ERROR -> ERROR " + TiberoTestEvidence.rootMessage(e));
      }
    }
    // 키 존재(JSON_EXISTS)는 묶음 ④ 에서 이미 쓰던 식이라 기준선으로 단정한다.
    assertThat(probeIds("rpt", rptTable, "JSON_EXISTS(result, '$.htmlContent')")).contains(1L, 2L);
    // 매퍼가 쓰는 판정식은 PG ->> 판정과 같은 행을 고른다(긴 본문은 CLOB 이라 길이 한도 없음).
    assertThat(probeIds("rpt", rptTable, REPORT_PREDICATE))
        .containsExactly(1L, 6L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L);
    // 제목(기본 JSON_VALUE): 공백·빈 제목은 대체, 65532 바이트(VARCHAR2 최대)를 넘는 제목(18)은 NULL → 대체.
    Map<Long, String> titles =
        probeValues("rpt", rptTable, "COALESCE(TRIM(JSON_VALUE(result, '$.title')), 'JOB')");
    assertThat(titles).containsEntry(12L, "보고서").containsEntry(16L, "JOB");
    assertThat(titles).containsEntry(17L, "JOB").containsEntry(18L, "JOB");
    // 요약(RETURNING CLOB): 빈 문자열은 빈 값 그대로(PG ->> 와 같음), 큰 값도 잘리지 않는다.
    Map<Long, String> summaries =
        probeValues("rpt", rptTable, "JSON_VALUE(result, '$.summary' RETURNING CLOB)");
    assertThat(summaries).containsEntry(2L, "").containsEntry(13L, "7");
    assertThat(summaries.get(18L)).hasSize(25_000);
  }

  /** 최상위가 배열이 아님 — JSON_TABLE '$[*]' 는 최상위 객체에서 0행이다((e) 실측). */
  private static final String TOP_LEVEL_NOT_ARRAY =
      "NOT EXISTS (SELECT 1 FROM JSON_TABLE(result, '$[*]' COLUMNS (n FOR ORDINALITY)) jt)";

  /** 스칼라 htmlContent 만 보는 판정(객체·배열 값 8·14 는 놓친다 — 기록용). */
  private static final String REPORT_PREDICATE_SCALAR =
      TOP_LEVEL_NOT_ARRAY
          + " AND DBMS_LOB.GETLENGTH(JSON_VALUE(result, '$.htmlContent' RETURNING CLOB)) > 0";

  /**
   * ProactiveJobExecutionMapper.xml 의 Tibero 리포트 판정과 같은 식(WD-709 fix C1) — 스칼라는 JSON_VALUE 길이,
   * 객체·배열 값은 JSON_QUERY 텍스트의 첫 글자로 본다(JSON null 은 'null', 빈 문자열은 '""' 이라 빠진다).
   */
  private static final String REPORT_PREDICATE =
      TOP_LEVEL_NOT_ARRAY
          + " AND (DBMS_LOB.GETLENGTH(JSON_VALUE(result, '$.htmlContent' RETURNING CLOB)) > 0"
          + " OR DBMS_LOB.SUBSTR(JSON_QUERY(result, '$.htmlContent' RETURNING CLOB), 1, 1)"
          + " IN ('{', '['))";

  // ---------------------------------------------------------------- (e) JSON 배열 길이

  @Test
  @DisplayName("(e) JSON 배열 길이 후보($.size()·JSON_TABLE COUNT)를 기록한다")
  void jsonArrayLength() {
    createTable(secTable, "CREATE TABLE " + secTable + " (id NUMBER(10), sections JSON)");
    String big = "섹션".repeat(1500); // 원소 하나가 9000 바이트 — 배열 전체는 4000 바이트를 크게 넘는다
    String[] docs = {
      /* 1 */ "[\"a\",{\"x\":1}]",
      /* 2 */ "[]",
      /* 3 */ "[{\"a\":1},{\"b\":2},{\"c\":3}]",
      /* 4 */ "{\"a\":1}",
      /* 5 */ "[{\"t\":\"" + big + "\"},{\"t\":\"" + big + "\"},{\"t\":\"" + big + "\"}]",
      /* 6 */ "[null,[1,2],3]"
    };
    for (int i = 0; i < docs.length; i++) {
      poc.jdbc().update("INSERT INTO " + secTable + " VALUES (?, ?)", i + 1, docs[i]);
    }
    TiberoTestEvidence.write(
        "B45PROBE", "sec PG jsonb_array_length expected {1=2, 2=0, 3=3, 4=ERROR, 5=3, 6=3}");
    String[] exprs = {
      "JSON_VALUE(sections, '$.size()')",
      "JSON_VALUE(sections, '$.size()' RETURNING NUMBER)",
      "(SELECT COUNT(*) FROM JSON_TABLE(sections, '$[*]' COLUMNS (n FOR ORDINALITY)) jt)",
      "(SELECT COUNT(*) FROM JSON_TABLE(sections, '$[*]'"
          + " COLUMNS (e VARCHAR2(10) FORMAT JSON PATH '$' NULL ON ERROR)) jt)",
      "(SELECT COUNT(*) FROM JSON_TABLE(sections, '$[*]' COLUMNS (z NUMBER PATH '$.zz')) jt)"
    };
    for (String e : exprs) {
      probeValues("sec", secTable, e);
    }
    assertThat(probeValues("sec", secTable, "id")).hasSize(6);
  }

  // ---------------------------------------------------------------- (b) CASE 색인 MERGE

  private static final String CASE_T =
      "CASE WHEN t.tenant_id IS NOT NULL AND t.user_id IS NOT NULL AND t.channel_type IS NOT NULL"
          + " AND t.workspace_id IS NOT NULL THEN t.%s END";
  private static final String CASE_S =
      "CASE WHEN s.tenant_id IS NOT NULL AND s.user_id IS NOT NULL AND s.channel_type IS NOT NULL"
          + " AND s.workspace_id IS NOT NULL THEN s.%s END";

  /** uk_user_channel 과 같은 CASE 식으로 ON 절을 만든 MERGE 한 번. */
  private void mergeBinding(long tenant, long user, String type, Long workspace, String ext) {
    StringBuilder on = new StringBuilder();
    for (String col : new String[] {"tenant_id", "user_id", "channel_type", "workspace_id"}) {
      if (on.length() > 0) on.append(" AND ");
      on.append(String.format(CASE_T, col)).append(" = ").append(String.format(CASE_S, col));
    }
    poc.jdbc()
        .update(
            "MERGE INTO "
                + bindTable
                + " t USING (SELECT ? AS tenant_id, ? AS user_id, ? AS channel_type,"
                + " CAST(? AS NUMBER(19)) AS workspace_id, ? AS ext_id FROM DUAL) s ON ("
                + on
                + ") WHEN MATCHED THEN UPDATE SET t.ext_id = s.ext_id"
                + " WHEN NOT MATCHED THEN INSERT (tenant_id, user_id, channel_type, workspace_id,"
                + " ext_id) VALUES (s.tenant_id, s.user_id, s.channel_type, s.workspace_id,"
                + " s.ext_id)",
            tenant,
            user,
            type,
            workspace,
            ext);
  }

  @Test
  @DisplayName("(b) CASE 함수 유일 색인 대상 MERGE — workspace 가 있으면 한 행 유지, NULL workspace 는 매번 새 행")
  void caseIndexMerge() {
    createTable(
        bindTable,
        "CREATE TABLE "
            + bindTable
            + " (id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY NOT NULL, tenant_id NUMBER(19),"
            + " user_id NUMBER(19), channel_type VARCHAR2(20 CHAR), workspace_id NUMBER(19),"
            + " ext_id VARCHAR2(100 CHAR))");
    String all =
        "tenant_id IS NOT NULL AND user_id IS NOT NULL AND channel_type IS NOT NULL AND workspace_id IS NOT NULL";
    poc.jdbc()
        .execute(
            "CREATE UNIQUE INDEX uk_"
                + suffix
                + " ON "
                + bindTable
                + " (CASE WHEN "
                + all
                + " THEN tenant_id END, CASE WHEN "
                + all
                + " THEN user_id END, CASE WHEN "
                + all
                + " THEN channel_type END, CASE WHEN "
                + all
                + " THEN workspace_id END)");

    mergeBinding(1, 10, "SLACK", 7L, "A");
    mergeBinding(1, 10, "SLACK", 7L, "B");
    int withWs =
        poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM " + bindTable + " WHERE workspace_id = 7", Integer.class);
    String ext =
        poc.jdbc()
            .queryForObject(
                "SELECT ext_id FROM " + bindTable + " WHERE workspace_id = 7", String.class);

    mergeBinding(1, 10, "EMAIL", null, "X");
    mergeBinding(1, 10, "EMAIL", null, "Y");
    int nullWs =
        poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM " + bindTable + " WHERE workspace_id IS NULL", Integer.class);
    TiberoTestEvidence.write(
        "B45PROBE",
        "merge CASE ON: workspace 7 두 번 -> "
            + withWs
            + "행(ext="
            + ext
            + "), NULL workspace 두 번 -> "
            + nullWs
            + "행");
    assertThat(withWs).isEqualTo(1);
    assertThat(ext).isEqualTo("B");
    assertThat(nullWs).isEqualTo(2);
  }

  // ---------------------------------------------------------------- (c) TRUNC vs CAST

  @Test
  @DisplayName("(c) TRUNC(ts) 는 자정 날짜 버킷을 주고 CAST(ts AS DATE) 는 Tibero 에서 시각을 남긴다")
  void truncVsCast() {
    createTable(tsTable, "CREATE TABLE " + tsTable + " (id NUMBER(10), ts TIMESTAMP)");
    String[] stamps = {
      "2026-03-01 00:00:00.000",
      "2026-03-01 13:45:10.123",
      "2026-03-01 23:59:59.999",
      "2026-03-02 00:00:01.000"
    };
    for (int i = 0; i < stamps.length; i++) {
      poc.jdbc()
          .update(
              "INSERT INTO "
                  + tsTable
                  + " VALUES (?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS.FF3'))",
              i + 1,
              stamps[i]);
    }
    List<Map<String, Object>> rows =
        poc.jdbc()
            .queryForList(
                "SELECT id, TO_CHAR(TRUNC(ts), 'YYYY-MM-DD HH24:MI:SS') AS t,"
                    + " TO_CHAR(CAST(ts AS DATE), 'YYYY-MM-DD HH24:MI:SS') AS c FROM "
                    + tsTable
                    + " ORDER BY id");
    TiberoTestEvidence.write("B45PROBE", "trunc vs cast rows=" + rows);
    List<Map<String, Object>> byTrunc =
        poc.jdbc()
            .queryForList(
                "SELECT TO_CHAR(TRUNC(ts), 'YYYY-MM-DD') d, COUNT(*) n FROM "
                    + tsTable
                    + " GROUP BY TRUNC(ts) ORDER BY 1");
    TiberoTestEvidence.write("B45PROBE", "group by TRUNC(ts)=" + byTrunc);
    // Tibero DATE 는 시각을 품으므로 CAST(ts AS DATE) 가 자정으로 잘리지 않을 수 있다 — 같은지는 기록만 하고,
    // TRUNC 가 날짜 버킷(자정)을 주는지만 단정한다.
    boolean sameAsCast = rows.stream().allMatch(r -> r.get("T").equals(r.get("C")));
    TiberoTestEvidence.write("B45PROBE", "TRUNC(ts) == CAST(ts AS DATE) 모든 행 동일: " + sameAsCast);
    for (Map<String, Object> r : rows) {
      assertThat((String) r.get("T")).endsWith("00:00:00");
    }
    assertThat(byTrunc).hasSize(2);
  }
}
