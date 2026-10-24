package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * ContentDispositions 단위 테스트 — Tomcat 은 ISO-8859-1 밖 문자가 든 헤더를 지우므로(WD-249) 헤더 값 전체가 출력 가능한 ASCII
 * 여야 하고, 한글 원래 이름은 filename* 로 되살아나야 한다.
 */
class ContentDispositionsTest {

  /** 헤더 값이 Tomcat 이 받아들이는 출력 가능 ASCII(0x20~0x7E)로만 이뤄졌는지. */
  private static void assertPrintableAscii(String header) {
    assertThat(header.chars()).allMatch(c -> c >= 0x20 && c <= 0x7E);
  }

  /** filename* 의 퍼센트 인코딩 부분을 UTF-8 로 되돌린다. */
  private static String decodeExtended(String header) {
    String marker = "filename*=UTF-8''";
    String encoded = header.substring(header.indexOf(marker) + marker.length());
    return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
  }

  @Test
  void 한글_이름은_ASCII_대체와_UTF8_확장_이름으로_나뉜다() {
    String header = ContentDispositions.attachment("화재통계_20261025.csv");

    assertPrintableAscii(header);
    assertThat(header)
        .isEqualTo(
            "attachment; filename=\"_____20261025.csv\"; filename*=UTF-8''"
                + "%ED%99%94%EC%9E%AC%ED%86%B5%EA%B3%84_20261025.csv");
    assertThat(decodeExtended(header)).isEqualTo("화재통계_20261025.csv");
  }

  @Test
  void 잘린_쿼리_결과_접미사도_한글이_filename_따옴표_안에_들어가지_않는다() {
    String header = ContentDispositions.attachment("query_result_20261025_상위1000행.xlsx");

    assertPrintableAscii(header);
    assertThat(header).startsWith("attachment; filename=\"query_result_20261025___1000_.xlsx\";");
    assertThat(decodeExtended(header)).isEqualTo("query_result_20261025_상위1000행.xlsx");
  }

  @Test
  void ASCII_이름은_기존_형식과_같다() {
    // 수정 전과 같은 결과여야 한다 — 기존 클라이언트(웹 download.ts 등) 호환
    assertThat(ContentDispositions.attachment("export.csv"))
        .isEqualTo("attachment; filename=\"export.csv\"; filename*=UTF-8''export.csv");
    assertThat(ContentDispositions.attachment("my-data_v1.2.xlsx"))
        .isEqualTo(
            "attachment; filename=\"my-data_v1.2.xlsx\"; filename*=UTF-8''my-data_v1.2.xlsx");
  }

  @Test
  void 공백_따옴표_역슬래시_CRLF는_밑줄로_바뀌어_헤더가_깨지지_않는다() {
    String header = ContentDispositions.attachment("a b\"c\\d\r\ne;f.csv");

    assertPrintableAscii(header);
    assertThat(header)
        .isEqualTo("attachment; filename=\"a_b_c_d__e_f.csv\"; filename*=UTF-8''a_b_c_d__e_f.csv");
  }

  @Test
  void 한글과_특수문자가_섞인_이름도_확장_이름은_한글을_유지한다() {
    String header = ContentDispositions.attachment("월간 \"보고서\".pdf");

    assertPrintableAscii(header);
    assertThat(header).startsWith("attachment; filename=\"________.pdf\"; filename*=UTF-8''");
    assertThat(decodeExtended(header)).isEqualTo("월간__보고서_.pdf");
  }
}
