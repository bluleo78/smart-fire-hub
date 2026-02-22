package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for FileParserService — no Spring context required. All test data is created
 * programmatically as byte arrays.
 */
class FileParserServiceTest {

  private FileParserService service;

  @BeforeEach
  void setUp() {
    service = new FileParserService();
  }

  // Helper: CSV string → InputStream
  private static InputStream toStream(String csv) {
    return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
  }

  // Helper: CSV string → byte[]
  private static byte[] toBytes(String csv) {
    return csv.getBytes(StandardCharsets.UTF_8);
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — standard CSV with header
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_standardCsvWithHeader_returnsCorrectRows() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul\nBob,25,Busan";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).hasSize(2);

    Map<String, String> first = rows.get(0);
    assertThat(first)
        .containsEntry("name", "Alice")
        .containsEntry("age", "30")
        .containsEntry("city", "Seoul");

    Map<String, String> second = rows.get(1);
    assertThat(second)
        .containsEntry("name", "Bob")
        .containsEntry("age", "25")
        .containsEntry("city", "Busan");
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — custom delimiter: tab
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_tabDelimited_parsesCorrectly() throws Exception {
    String tsv = "name\tage\tcity\nAlice\t30\tSeoul\nBob\t25\tBusan";
    ParseOptions opts = new ParseOptions("\t", "UTF-8", true, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(tsv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("age", "30");
    assertThat(rows.get(1)).containsEntry("name", "Bob").containsEntry("city", "Busan");
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — custom delimiter: semicolon
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_semicolonDelimited_parsesCorrectly() throws Exception {
    String csv = "name;age;city\nAlice;30;Seoul\nBob;25;Busan";
    ParseOptions opts = new ParseOptions(";", "UTF-8", true, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("city", "Seoul");
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — quoted values containing commas
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_quotedValuesWithCommasInside_handlesCorrectly() throws Exception {
    // RFC-4180: value containing comma must be double-quoted
    String csv = "name,address\nAlice,\"Seoul, Gangnam\"\nBob,\"Busan, Haeundae\"";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("address", "Seoul, Gangnam");
    assertThat(rows.get(1)).containsEntry("address", "Busan, Haeundae");
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — empty input
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_emptyInputStream_returnsEmptyList() throws Exception {
    List<Map<String, String>> rows = service.parseCSV(toStream(""));

    assertThat(rows).isEmpty();
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — header only, no data rows
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_headerOnlyNoDataRows_returnsEmptyList() throws Exception {
    String csv = "name,age,city";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).isEmpty();
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream) — no header mode, auto-generates column names
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_noHeaderMode_autoGeneratesColumnNames() throws Exception {
    String csv = "Alice,30,Seoul\nBob,25,Busan";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsKey("column_1").containsKey("column_2").containsKey("column_3");
    assertThat(rows.get(0))
        .containsEntry("column_1", "Alice")
        .containsEntry("column_2", "30")
        .containsEntry("column_3", "Seoul");
  }

  // -----------------------------------------------------------------------
  // parse (byte[]) — unsupported file type throws UnsupportedFileTypeException
  // -----------------------------------------------------------------------

  @Test
  void parse_unsupportedFileType_throwsUnsupportedFileTypeException() {
    byte[] data = "dummy content".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.parse(data, "pdf"))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("Unsupported file type");
  }

  // -----------------------------------------------------------------------
  // parseHeaders (byte[]) — CSV returns header names
  // -----------------------------------------------------------------------

  @Test
  void parseHeaders_csv_returnsHeaderNames() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul";
    byte[] data = toBytes(csv);

    List<String> headers = service.parseHeaders(data, "csv");

    assertThat(headers).containsExactly("name", "age", "city");
  }

  // -----------------------------------------------------------------------
  // countRows (byte[]) — CSV with 3 data rows returns 3
  // -----------------------------------------------------------------------

  @Test
  void countRows_csvWith3DataRows_returns3() throws Exception {
    String csv = "name,age\nAlice,30\nBob,25\nCharlie,35";
    byte[] data = toBytes(csv);

    int count = service.countRows(data, "csv");

    assertThat(count).isEqualTo(3);
  }

  // -----------------------------------------------------------------------
  // parse (byte[]) — CSV routing works correctly
  // -----------------------------------------------------------------------

  @Test
  void parse_csvFileType_routesToCsvParser() throws Exception {
    String csv = "id,value\n1,foo\n2,bar";
    byte[] data = toBytes(csv);

    List<Map<String, String>> rows = service.parse(data, "csv");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("id", "1").containsEntry("value", "foo");
    assertThat(rows.get(1)).containsEntry("id", "2").containsEntry("value", "bar");
  }
}
