package com.smartfirehub.global.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/** PG 이름 비교 공백 집합이 서버 인코딩에 따라 고르는지(WD-1162) — 스프링 없이 DSLContext 를 mock 으로 둔다. */
class PgEdgeSpaceCharsTest {

  @Test
  void supportsUnicodeBinding_UTF8과_null만_true() {
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("UTF8")).isTrue();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("utf8")).isTrue();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("UTF-8")).isTrue();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding(null)).isTrue();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("SQL_ASCII")).isFalse();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("LATIN1")).isFalse();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("EUC_KR")).isFalse();
    assertThat(PgEdgeSpaceChars.supportsUnicodeBinding("WIN1252")).isFalse();
  }

  @Test
  void LATIN1이면_ASCII_공백으로_물러선다() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchValue("SHOW server_encoding")).thenReturn("LATIN1");
    PgEdgeSpaceChars chars = new PgEdgeSpaceChars(dsl);
    chars.init();

    PgEdgeSpaceChars.TranslatePair pair = chars.translatePair();
    assertThat(pair.from()).isEqualTo(TextRules.ASCII_EDGE_SPACE_CHARS);
    assertThat(pair.to()).isEqualTo(" ".repeat(6));
    assertThat(pair.from()).hasSameSizeAs(pair.to());
    assertThat(chars.translatePair()).isEqualTo(pair);
    verify(dsl, times(1)).fetchValue("SHOW server_encoding");
  }

  @Test
  void UTF8이면_유니코드_공백_집합을_쓴다() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchValue("SHOW server_encoding")).thenReturn("UTF8");
    PgEdgeSpaceChars chars = new PgEdgeSpaceChars(dsl);
    chars.init();

    PgEdgeSpaceChars.TranslatePair pair = chars.translatePair();
    assertThat(pair.from()).isEqualTo(TextRules.EDGE_SPACE_CHARS);
    assertThat(pair.to()).hasSize(TextRules.EDGE_SPACE_CHARS.length());
    assertThat(pair.from()).hasSameSizeAs(pair.to());
  }

  @Test
  void 기동_조회가_실패하면_유니코드_쌍을_쓰고_요청_경로는_DB_를_안_부른다() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchValue("SHOW server_encoding")).thenThrow(new RuntimeException("boom"));
    PgEdgeSpaceChars chars = new PgEdgeSpaceChars(dsl);
    chars.init();

    PgEdgeSpaceChars.TranslatePair pair = chars.translatePair();
    assertThat(pair.from()).isEqualTo(TextRules.EDGE_SPACE_CHARS);
    assertThat(pair.from()).hasSameSizeAs(pair.to());
    assertThat(chars.translatePair()).isEqualTo(pair);
    verify(dsl, times(1)).fetchValue("SHOW server_encoding");
    verifyNoMoreInteractions(dsl);
  }

  @Test
  void init_전에는_조회_없이_유니코드_쌍을_돌려준다() {
    DSLContext dsl = mock(DSLContext.class);
    PgEdgeSpaceChars chars = new PgEdgeSpaceChars(dsl);

    PgEdgeSpaceChars.TranslatePair pair = chars.translatePair();
    assertThat(pair.from()).isEqualTo(TextRules.EDGE_SPACE_CHARS);
    assertThat(pair.from()).hasSameSizeAs(pair.to());
    verifyNoInteractions(dsl);
  }

  @Test
  void ASCII_폴백_상수는_유니코드_집합의_ASCII_부분이다() {
    assertThat(TextRules.ASCII_EDGE_SPACE_CHARS).isEqualTo("\t\n\u000B\f\r ");
    assertThat(TextRules.EDGE_SPACE_CHARS).contains(TextRules.ASCII_EDGE_SPACE_CHARS.split(""));
  }

  @Test
  void normalize_는_고른_쌍으로_translate_식을_만든다() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchValue("SHOW server_encoding")).thenReturn("LATIN1");
    PgEdgeSpaceChars chars = new PgEdgeSpaceChars(dsl);
    chars.init();

    String sql = DSL.using(SQLDialect.POSTGRES).renderInlined(chars.normalize(DSL.field("n", String.class)));
    assertThat(sql).startsWith("lower(trim(translate(n, '");
    assertThat(sql).doesNotContain("\u3000");
  }
}
