package com.smartfirehub.dataimport.service;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvMalformedLineException;
import com.opencsv.exceptions.CsvValidationException;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.FileUnreadableException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FileParserService {

  // -----------------------------------------------------------------------
  // Public API — no-options variants (backward-compatible, use defaults)
  // -----------------------------------------------------------------------

  public List<Map<String, String>> parseCSV(InputStream inputStream) throws Exception {
    return parseCSV(inputStream, ParseOptions.defaults());
  }

  public List<Map<String, String>> parse(byte[] fileData, String fileType) throws Exception {
    return parse(fileData, fileType, ParseOptions.defaults());
  }

  public List<String> parseHeaders(byte[] fileData, String fileType) throws Exception {
    return parseHeaders(fileData, fileType, ParseOptions.defaults());
  }

  public List<Map<String, String>> parseSampleRows(byte[] fileData, String fileType, int maxRows)
      throws Exception {
    return parseSampleRows(fileData, fileType, maxRows, ParseOptions.defaults());
  }

  public int countRows(byte[] fileData, String fileType) throws Exception {
    return countRows(fileData, fileType, ParseOptions.defaults());
  }

  // -----------------------------------------------------------------------
  // Public API — InputStream overloads (OOM 방지: MultipartFile.getInputStream() 경로)
  // byte[] 오버로드는 processImport 등 기존 호출부와 호환 유지
  // -----------------------------------------------------------------------

  /**
   * CSV/XLSX 헤더를 InputStream에서 파싱한다. AUTO 인코딩 감지가 필요한 CSV의 경우, BufferedInputStream의 mark/reset을 이용해
   * 앞부분 바이트만 읽어 인코딩을 판별한 후 스트림 전체를 재읽는다.
   */
  public List<String> parseHeaders(InputStream inputStream, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseHeadersCsvFromStream(inputStream, opts);
      case "xlsx", "xls", "xlsb" -> excelHeaders(excel(inputStream), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 샘플 행을 InputStream에서 파싱한다. */
  public List<Map<String, String>> parseSampleRows(
      InputStream inputStream, String fileType, int maxRows, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseSampleRowsCsvFromStream(inputStream, maxRows, opts);
      case "xlsx", "xls", "xlsb" -> excelRows(excel(inputStream), opts, maxRows);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 전체 행 수를 InputStream에서 계산한다. */
  public int countRows(InputStream inputStream, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> countRowsCsvFromStream(inputStream, opts);
      case "xlsx", "xls", "xlsb" -> excelCount(excel(inputStream), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 전체 데이터를 InputStream에서 파싱한다. */
  public List<Map<String, String>> parse(
      InputStream inputStream, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseCsvFromStream(inputStream, opts);
      case "xlsx", "xls", "xlsb" -> excelRows(excel(inputStream), opts, Integer.MAX_VALUE);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  // -----------------------------------------------------------------------
  // Public API — Path 오버로드 (임시 파일을 이미 디스크에 spill한 호출자용)
  // InputStream 오버로드와 달리 파일을 다시 메모리/temp로 복사하지 않고 그대로 재사용한다.
  // CSV는 Files.newInputStream으로 스트림을 열고, Excel은 ExcelStreamingParser.parse(File,...)
  // core 진입점(랜덤액세스)에 직접 위임하여 InputStream 오버로드 경로의 2차 spill을 피한다.
  // -----------------------------------------------------------------------

  /**
   * CSV/XLSX 헤더를 파일 경로에서 파싱한다.
   *
   * <p>주의: Excel(xlsx/xls/xlsb)은 항상 첫 행을 헤더로 취급하며 {@code opts}의 hasHeader=false/skipRows는 적용되지
   * 않는다(기존 Excel 파싱 동작과 동일). CSV만 두 옵션을 온전히 반영한다. Excel 은 {@code opts.sheetName()} 으로 읽을 시트만
   * 고른다(WD-253).
   */
  public List<String> parseHeaders(Path file, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> {
        try (InputStream in = Files.newInputStream(file)) {
          yield guardCsv(() -> parseHeadersCsvFromStream(in, opts));
        }
      }
      case "xlsx", "xls", "xlsb" -> excelHeaders(excel(file.toFile()), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /**
   * CSV/XLSX 샘플 행을 파일 경로에서 파싱한다.
   *
   * <p>주의: Excel은 항상 첫 행을 헤더로 취급하며 hasHeader=false/skipRows는 적용되지 않는다(기존 동작과 동일). CSV만 해당 옵션을 반영한다.
   */
  public List<Map<String, String>> parseSampleRows(
      Path file, String fileType, int maxRows, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> {
        try (InputStream in = Files.newInputStream(file)) {
          yield guardCsv(() -> parseSampleRowsCsvFromStream(in, maxRows, opts));
        }
      }
      case "xlsx", "xls", "xlsb" -> excelRows(excel(file.toFile()), opts, maxRows);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /**
   * CSV/XLSX 전체 행 수를 파일 경로에서 계산한다.
   *
   * <p>주의: Excel은 항상 첫 행을 헤더로 취급하며 hasHeader=false/skipRows는 적용되지 않는다(기존 동작과 동일). CSV만 해당 옵션을 반영한다.
   */
  public int countRows(Path file, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> {
        try (InputStream in = Files.newInputStream(file)) {
          yield guardCsv(() -> countRowsCsvFromStream(in, opts));
        }
      }
      case "xlsx", "xls", "xlsb" -> excelCount(excel(file.toFile()), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /**
   * CSV/XLSX 전체 데이터를 파일 경로에서 파싱한다.
   *
   * <p>주의: Excel은 항상 첫 행을 헤더로 취급하며 hasHeader=false/skipRows는 적용되지 않는다(기존 동작과 동일). CSV만 해당 옵션을 반영한다.
   */
  public List<Map<String, String>> parse(Path file, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> {
        try (InputStream in = Files.newInputStream(file)) {
          yield guardCsv(() -> parseCsvFromStream(in, opts));
        }
      }
      case "xlsx", "xls", "xlsb" -> excelRows(excel(file.toFile()), opts, Integer.MAX_VALUE);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /**
   * CSV/XLSX 대용량 파일을 배치 콜백 방식으로 스트리밍 파싱한다.
   *
   * <p>전체 행을 메모리에 적재하지 않고, batchSize개씩 모아 {@code onBatch}를 호출한 뒤 배치 리스트를 비운다. 100~400MB급 파일을 임포트할 때
   * OOM 없이 처리하기 위한 진입점이다(#169 연장선).
   *
   * <p>CSV: {@link #buildCsvReaderFromStream}로 AUTO 인코딩까지 지원하는 리더를 얻어 skipRows를 건너뛴 뒤 readNext()
   * 루프로 행을 순회한다({@code readAll()} 금지). Excel: {@link ExcelStreamingParser#parse(java.io.File,
   * String, ExcelStreamingParser.RowConsumer)} core 진입점(랜덤액세스)에 위임한다.
   *
   * <p>주의: Excel(xlsx/xls/xlsb)은 항상 첫 행을 헤더로 취급하며 {@code opts}의 hasHeader=false/skipRows는 적용되지
   * 않는다(기존 InputStream 기반 parseExcel/parseHeadersExcel과 동일한 동작이며 회귀가 아니다). CSV만 두 옵션을 온전히 반영하므로,
   * hasHeader=false나 skipRows에 의존하는 호출은 Excel 입력에 사용하지 말 것.
   *
   * <p>모든 셀이 비었거나 공백뿐인 데이터 행({@link #isBlankRow})은 배치에 넣지 않는다(WD-255).
   *
   * @return 건너뛴 빈 행 수 — 임포트 결과에 「빈 행 건너뜀」으로 보여 준다
   */
  public int parseStreaming(
      Path file,
      String fileType,
      ParseOptions opts,
      int batchSize,
      Consumer<List<Map<String, String>>> onBatch)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> {
        try (InputStream in = Files.newInputStream(file)) {
          yield guardCsv(() -> parseStreamingCsvFromStream(in, opts, batchSize, onBatch));
        }
      }
      // Excel 분기: opts 중 sheetName 만 쓴다(hasHeader/skipRows 미사용) — 위 Javadoc 참고
      case "xlsx", "xls", "xlsb" -> excelStreaming(excel(file.toFile()), opts, batchSize, onBatch);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV 파싱 호출을 감싸는 함수형 인터페이스 — checked 예외를 그대로 던질 수 있게 한다. */
  @FunctionalInterface
  private interface CsvCall<T> {
    T call() throws Exception;
  }

  /**
   * CSV 구문 오류(닫히지 않은 따옴표 등)를 한국어 안내 예외로 바꾼다 (WD-204).
   *
   * <p>opencsv 의 {@link CsvMalformedLineException} 은 {@link IOException} 하위라, 그대로 두면 전역 IOException
   * 핸들러로 흘러가 미리보기·검증이 실패 응답 대신 빈 응답이 됐다. {@link CsvValidationException} 도 원문(영문)이 보이지 않게 함께 바꾼다. 배치
   * 콜백이 던지는 예외(검증 중단·DB 오류)는 이 두 타입이 아니므로 그대로 전파된다.
   */
  private static <T> T guardCsv(CsvCall<T> call) throws Exception {
    try {
      return call.call();
    } catch (CsvMalformedLineException | CsvValidationException e) {
      log.warn("CSV parse failed: {}", e.toString(), e);
      throw new FileUnreadableException(FileUnreadableException.CSV_UNREADABLE, e);
    }
  }

  // -----------------------------------------------------------------------
  // Public API — with ParseOptions
  // -----------------------------------------------------------------------

  public List<Map<String, String>> parseCSV(InputStream inputStream, ParseOptions opts)
      throws Exception {
    // WD-245: 스트림 경로 빌더를 함께 써 BOM 소비·AUTO 인코딩 감지를 다른 CSV 경로와 똑같이 적용한다(이전엔 AUTO 를 UTF-8 로 고정하고 BOM 을
    // 남겼다).
    return parseCsvFromStream(inputStream, opts);
  }

  public List<Map<String, String>> parse(byte[] fileData, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseCsvFromBytes(fileData, opts);
      case "xlsx", "xls", "xlsb" ->
          excelRows(excel(new ByteArrayInputStream(fileData)), opts, Integer.MAX_VALUE);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public List<String> parseHeaders(byte[] fileData, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseHeadersCsvFromBytes(fileData, opts);
      case "xlsx", "xls", "xlsb" -> excelHeaders(excel(new ByteArrayInputStream(fileData)), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public List<Map<String, String>> parseSampleRows(
      byte[] fileData, String fileType, int maxRows, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseSampleRowsCsvFromBytes(fileData, maxRows, opts);
      case "xlsx", "xls", "xlsb" ->
          excelRows(excel(new ByteArrayInputStream(fileData)), opts, maxRows);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public int countRows(byte[] fileData, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> countRowsCsvFromBytes(fileData, opts);
      case "xlsx", "xls", "xlsb" -> excelCount(excel(new ByteArrayInputStream(fileData)), opts);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  // -----------------------------------------------------------------------
  // Excel (인코딩은 엑셀이 스스로 처리 — ParseOptions 중 sheetName 만 의미가 있다)
  // -----------------------------------------------------------------------

  /**
   * 엑셀 파일의 시트 목록과 실제로 읽을 시트를 알려 준다(WD-253). CSV 는 시트가 없어 {@link
   * ExcelStreamingParser.SheetSelection#NONE}(둘 다 null) — 호출자가 null 분기 없이 그대로 응답에 담는다.
   *
   * <p>미리보기 응답의 sheets/sheetName 에 쓴다. 행은 읽지 않고 워크북 머리만 읽는다. 선택 규칙이 파싱과 같아, 같은 {@code opts} 로 부른
   * 헤더·샘플·행 수·임포트가 이 시트를 읽는다. 지정한 시트가 없거나 보이는 시트가 없으면 {@link
   * com.smartfirehub.dataimport.exception.ExcelSheetSelectionException}(400).
   */
  public ExcelStreamingParser.SheetSelection describeSheets(
      Path file, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> ExcelStreamingParser.SheetSelection.NONE;
      case "xlsx", "xls", "xlsb" ->
          ExcelStreamingParser.describeSheets(file.toFile(), opts.sheetName());
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  // -----------------------------------------------------------------------
  // Private CSV helpers with byte[] (supports AUTO encoding detection)
  // -----------------------------------------------------------------------

  private List<Map<String, String>> parseCsvFromBytes(byte[] data, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      skipLeadingRows(reader, opts);
      return readRowMaps(reader, Integer.MAX_VALUE, opts);
    }
  }

  private List<String> parseHeadersCsvFromBytes(byte[] data, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      skipLeadingRows(reader, opts);
      return readHeaderNames(reader, opts);
    }
  }

  private List<Map<String, String>> parseSampleRowsCsvFromBytes(
      byte[] data, int maxRows, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      skipLeadingRows(reader, opts);
      return readRowMaps(reader, maxRows, opts);
    }
  }

  private int countRowsCsvFromBytes(byte[] data, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      skipLeadingRows(reader, opts);
      return countDataRows(reader, opts);
    }
  }

  // -----------------------------------------------------------------------
  // Private CSV helpers with InputStream
  // AUTO 인코딩: mark/reset으로 앞 바이트만 읽어 인코딩을 판별하고 스트림을 재사용한다.
  // InputStream이 mark를 지원하지 않으면 BufferedInputStream으로 감싼다.
  // -----------------------------------------------------------------------

  /**
   * 인코딩 감지에 사용할 최대 선행 바이트 수. peek 버퍼 끝에서 멀티바이트가 잘려도 {@link
   * com.smartfirehub.dataimport.dto.ParseOptions#detectEncoding(byte[])}가 올바로 처리하지만, 더 큰 prefix를 보면
   * 오감지 가능성이 낮아진다(예: ASCII 헤더 뒤에 비ASCII가 한참 뒤에 나오는 파일).
   */
  private static final int ENCODING_PEEK_LIMIT = 65536;

  private List<Map<String, String>> parseCsvFromStream(InputStream inputStream, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      skipLeadingRows(reader, opts);
      return readRowMaps(reader, Integer.MAX_VALUE, opts);
    }
  }

  private List<String> parseHeadersCsvFromStream(InputStream inputStream, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      skipLeadingRows(reader, opts);
      return readHeaderNames(reader, opts);
    }
  }

  private List<Map<String, String>> parseSampleRowsCsvFromStream(
      InputStream inputStream, int maxRows, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      skipLeadingRows(reader, opts);
      return readRowMaps(reader, maxRows, opts);
    }
  }

  private int countRowsCsvFromStream(InputStream inputStream, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      skipLeadingRows(reader, opts);
      return countDataRows(reader, opts);
    }
  }

  /**
   * CSV를 batchSize 단위로 배치 콜백 스트리밍 파싱한다. {@code readAll()}을 쓰지 않고 readNext()만 반복하므로 전체 파일 크기와 무관하게
   * 배치 하나 분량만 메모리에 유지한다.
   *
   * <p>hasHeader=false인 경우 첫 행도 column_N 헤더로 Map을 만들어 데이터로 포함한다(기존 규칙과 동일). 빈 행은 배치에 넣지 않고 그 수를
   * 돌려준다(WD-255).
   */
  private int parseStreamingCsvFromStream(
      InputStream inputStream,
      ParseOptions opts,
      int batchSize,
      Consumer<List<Map<String, String>>> onBatch)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      skipLeadingRows(reader, opts); // skipRows가 파일 범위를 넘으면 아래에서 결과 없음
      int[] skipped = {0};
      CsvHead head = readCsvHead(reader, opts, skipped);
      if (head == null) return skipped[0]; // 헤더뿐이거나 빈 파일

      List<Map<String, String>> batch = new ArrayList<>(batchSize);
      if (head.firstDataRow() != null) batch.add(toRowMap(head.headers(), head.firstDataRow()));
      String[] row;
      while ((row = readNextDataRow(reader, head.headers().length, skipped)) != null) {
        batch.add(toRowMap(head.headers(), row));
        if (batch.size() >= batchSize) {
          onBatch.accept(batch);
          batch = new ArrayList<>(batchSize);
        }
      }
      if (!batch.isEmpty()) {
        onBatch.accept(batch);
      }
      return skipped[0];
    }
  }

  // -----------------------------------------------------------------------
  // CSV 공통 읽기 — 위 byte[]/InputStream 경로가 리더만 달리 만들어 같은 규칙으로 읽는다.
  // 미리보기(헤더·샘플·전체 행 수)·검증·임포트가 모두 같은 행을 보게 하려는 것이다(WD-255).
  // -----------------------------------------------------------------------

  /** 헤더 앞 skipRows 줄을 물리 줄 그대로 건너뛴다(빈 줄도 한 줄로 센다). 파일이 먼저 끝나면 거기서 멈춘다. */
  private static void skipLeadingRows(CSVReader reader, ParseOptions opts) throws Exception {
    for (int s = 0; s < opts.skipRows(); s++) {
      if (reader.readNext() == null) return;
    }
  }

  /**
   * 헤더와, 헤더 없는 파일이면 그 너비를 정한 첫 데이터 행.
   *
   * @param headers 열 이름(hasHeader=false 면 column_1, column_2, …)
   * @param firstDataRow hasHeader=false 일 때 헤더 너비를 정하고 데이터로도 들어가는 첫 행. 헤더가 있으면 null
   */
  private record CsvHead(String[] headers, String[] firstDataRow) {}

  /**
   * skipRows 를 지난 리더에서 헤더를 정한다. 읽을 행이 없으면 null.
   *
   * <p>hasHeader=false 면 첫 <b>빈 행이 아닌</b> 행의 너비로 column_N 을 만든다 — 앞의 빈 줄 너비(1열)로 열이 정해지지 않게 한다. 헤더
   * 행 자체는 빈 행이어도 거르지 않는다(헤더 판정은 범위 밖).
   */
  private static CsvHead readCsvHead(CSVReader reader, ParseOptions opts, int[] skipped)
      throws Exception {
    if (opts.hasHeader()) {
      String[] headers = reader.readNext();
      if (headers == null) return null;
      FileHeaderValidator.requireDistinctNames(headers); // WD-205: 같은 이름 열 중복 거절
      return new CsvHead(headers, null);
    }
    String[] firstRow = readNextDataRow(reader, Integer.MAX_VALUE, skipped);
    if (firstRow == null) return null;
    String[] headers = new String[firstRow.length];
    for (int i = 0; i < firstRow.length; i++) headers[i] = "column_" + (i + 1);
    return new CsvHead(headers, firstRow);
  }

  /**
   * 다음 데이터 행을 읽되 빈 행({@link #isBlankRow})은 건너뛰고 {@code skipped}(null 가능)에 센다. 끝이면 null.
   *
   * @param width 헤더 열 수 — 그 뒤의 셀은 어차피 버려지므로 빈 행 판정에서도 보지 않는다(「,,x」 를 헤더 2열 파일에서 빈 행으로 본다)
   */
  private static String[] readNextDataRow(CSVReader reader, int width, int[] skipped)
      throws Exception {
    String[] row;
    while ((row = reader.readNext()) != null) {
      if (!isBlankRow(Arrays.asList(row), width)) return row;
      if (skipped != null) skipped[0]++;
    }
    return null;
  }

  private static List<String> readHeaderNames(CSVReader reader, ParseOptions opts)
      throws Exception {
    CsvHead head = readCsvHead(reader, opts, null);
    return head == null ? Collections.emptyList() : Arrays.asList(head.headers());
  }

  /**
   * 데이터 행을 최대 maxRows 개 Map 으로 읽는다(전체는 {@link Integer#MAX_VALUE}). readAll() 대신 readNext() 반복으로 필요한
   * 만큼만 읽어 메모리를 줄인다(#169). 빈 행은 maxRows 에 세지 않는다.
   */
  private static List<Map<String, String>> readRowMaps(
      CSVReader reader, int maxRows, ParseOptions opts) throws Exception {
    CsvHead head = readCsvHead(reader, opts, null);
    if (head == null) return Collections.emptyList();
    List<Map<String, String>> result = new ArrayList<>();
    if (head.firstDataRow() != null) result.add(toRowMap(head.headers(), head.firstDataRow()));
    String[] row;
    while (result.size() < maxRows
        && (row = readNextDataRow(reader, head.headers().length, null)) != null) {
      result.add(toRowMap(head.headers(), row));
    }
    return result;
  }

  /**
   * 데이터 행 수(헤더·빈 행 제외)를 센다. readAll() 대신 readNext() 반복으로 전체 행을 메모리에 올리지 않는다(대용량 CSV OOM 방지, #169
   * 연장선).
   */
  private static int countDataRows(CSVReader reader, ParseOptions opts) throws Exception {
    CsvHead head = readCsvHead(reader, opts, null);
    if (head == null) return 0;
    int count = head.firstDataRow() != null ? 1 : 0;
    while (readNextDataRow(reader, head.headers().length, null) != null) count++;
    return count;
  }

  /** header[]/row[]를 {헤더: 값} Map으로 변환하는 공통 헬퍼. */
  private static Map<String, String> toRowMap(String[] headers, String[] row) {
    Map<String, String> rowMap =
        HashMap.newHashMap(headers.length); // 넓은 파일에서 행마다 rehash 하지 않게 미리 크기를 잡는다
    for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
    return rowMap;
  }

  // -----------------------------------------------------------------------
  // 빈 행 판정 (WD-255)
  // -----------------------------------------------------------------------

  /**
   * 앞 {@code width} 개 셀이 모두 비었거나 공백뿐인 행인지 본다(WD-255). CSV·엑셀이 같은 규칙을 쓴다.
   *
   * <p>CSV 의 빈 줄({@code [""]})·쉼표만 있는 줄({@code ["",""]}), 엑셀의 셀 없는 행(빈 {@code <row/>})·값 없는 서식 셀만
   * 있는 행이 여기에 걸린다. 이런 행은 데이터가 아니라 파일 편집 흔적이라 미리보기 행 수·샘플·검증·적재에서 똑같이 뺀다 — 그대로 두면 모든 값이 NULL 인 행으로
   * 적재된다. 헤더 너비 밖의 셀은 Map 에 들어가지 않으므로 보지 않는다.
   */
  static boolean isBlankRow(List<String> cells, int width) {
    for (int i = 0; i < cells.size() && i < width; i++) {
      String cell = cells.get(i);
      if (cell != null && !cell.isBlank()) return false;
    }
    return true;
  }

  // -----------------------------------------------------------------------
  // Private CSV reader builders
  // -----------------------------------------------------------------------

  /**
   * 바이트 배열 기반 CSVReader 빌더. 앞의 BOM 은 건너뛰고(WD-245), AUTO 인코딩이면 전체 바이트로 인코딩을 감지한다.
   *
   * <p>BOM 처리 규칙은 {@link #resolveEncoding} 참고.
   */
  private CSVReader buildCsvReaderFromBytes(byte[] data, ParseOptions opts) {
    ParseOptions.Bom bom = ParseOptions.detectBom(data);
    String encoding = resolveEncoding(opts, bom, () -> ParseOptions.detectEncoding(data));
    int offset = bom == null ? 0 : bom.length();
    // BOM 바이트를 뺀 나머지만 리더에 넘긴다 — U+FEFF 가 첫 헤더 이름에 섞이지 않게 한다.
    return newCsvReader(
        new ByteArrayInputStream(data, offset, data.length - offset), encoding, opts);
  }

  /**
   * InputStream 기반 CSVReader 빌더. 미리보기·검증·임포트·사전 검증 샘플의 스트림 경로가 모두 여기를 지난다.
   *
   * <p>BufferedInputStream 에 mark 를 걸고 선행 바이트를 읽어 BOM(항상)과 인코딩(AUTO 일 때)을 판별한 뒤 reset 하고, BOM 이 있으면
   * 그 바이트만큼 건너뛴다(WD-245). mark 를 지원하는 BufferedInputStream 이 아니면 감싼다.
   */
  private CSVReader buildCsvReaderFromStream(InputStream inputStream, ParseOptions opts)
      throws IOException {
    BufferedInputStream buffered =
        inputStream instanceof BufferedInputStream bi
            ? bi
            : new BufferedInputStream(inputStream, ENCODING_PEEK_LIMIT * 2);
    boolean auto = "AUTO".equals(opts.encoding());
    // AUTO 면 인코딩 감지용으로 넉넉히, 명시 인코딩이면 BOM 판별에 필요한 3바이트만 본다.
    int peekLimit = auto ? ENCODING_PEEK_LIMIT : BOM_PEEK_LIMIT;
    buffered.mark(peekLimit);
    byte[] peek = buffered.readNBytes(peekLimit);
    buffered.reset();

    ParseOptions.Bom bom = ParseOptions.detectBom(peek);
    String encoding = resolveEncoding(opts, bom, () -> ParseOptions.detectEncoding(peek));
    if (bom != null) buffered.skipNBytes(bom.length()); // U+FEFF 가 첫 헤더에 섞이지 않게 BOM 소비
    return newCsvReader(buffered, encoding, opts);
  }

  /** BOM 판별에 필요한 최대 선행 바이트 수(UTF-8 BOM 3바이트). */
  private static final int BOM_PEEK_LIMIT = 3;

  /**
   * 실제로 읽을 인코딩을 정한다(WD-245).
   *
   * <p>BOM 이 있으면 사용자가 고른 인코딩보다 BOM 을 우선한다. BOM 은 파일 스스로 밝힌 인코딩이라, 예컨대 UTF-8 BOM 파일을 EUC-KR 로 읽으면
   * BOM 3바이트가 깨진 글자로 첫 헤더에 붙고 한글 본문도 모두 깨진다 — 사용자 선택을 따를 이유가 없다. BOM 이 없을 때만 명시 인코딩을 쓰고, AUTO 면 감지
   * 결과를 쓴다.
   */
  private static String resolveEncoding(
      ParseOptions opts, ParseOptions.Bom bom, java.util.function.Supplier<String> autoDetect) {
    if (bom != null) return bom.encoding();
    return "AUTO".equals(opts.encoding()) ? autoDetect.get() : opts.encoding();
  }

  /** 인코딩·구분자를 적용한 CSVReader 를 만든다. 입력 스트림은 이미 BOM 이 소비된 상태여야 한다. */
  private static CSVReader newCsvReader(InputStream in, String encoding, ParseOptions opts) {
    char sep = opts.delimiter().charAt(0);
    Charset charset = Charset.forName(encoding);
    CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
    return new CSVReaderBuilder(new InputStreamReader(in, charset)).withCSVParser(parser).build();
  }

  // -----------------------------------------------------------------------
  // Private Excel helpers — 엑셀은 첫 행을 항상 헤더로 취급하고 ParseOptions 중 sheetName(읽을 시트, WD-253)만
  // 쓴다(hasHeader=false/skipRows 미반영 — 기존 동작이며 회귀가 아니다. CSV 경로만 두 옵션을 지원한다).
  // InputStream·File·byte[] 경로가 같은 helper 를 쓰도록 「어디서 읽는지」는 ExcelSource 로 넘긴다.
  // -----------------------------------------------------------------------

  /** 엑셀 행을 어디서 읽는지(InputStream 또는 파일) 감춘 읽기 함수 — 헤더·샘플·행 수·전체·스트리밍 helper 를 경로마다 두 벌 두지 않으려고 쓴다. */
  @FunctionalInterface
  private interface ExcelSource {
    void read(String sheetName, RowConsumer consumer) throws Exception;
  }

  /** InputStream 에서 읽는 엑셀 원본 — ExcelStreamingParser 가 임시 파일로 한 번 옮겨 랜덤액세스로 읽는다. */
  private static ExcelSource excel(InputStream in) {
    return (sheetName, consumer) -> ExcelStreamingParser.parse(in, sheetName, consumer);
  }

  /** 파일에서 바로 읽는 엑셀 원본 — 임시 파일 재복사(spill) 없이 core 진입점에 위임한다. */
  private static ExcelSource excel(java.io.File file) {
    return (sheetName, consumer) -> ExcelStreamingParser.parse(file, sheetName, consumer);
  }

  /**
   * 엑셀 행 콜백의 공통 규칙: 첫 행 = 헤더, 그 뒤의 빈 행({@link #isBlankRow})은 건너뛰어 {@code skipped}(null 가능)에 세고, 데이터
   * 행만 {@code onRow(헤더, 셀)} 에 넘긴다(WD-255). onRow 가 false 를 돌려주면 파싱을 멈춘다.
   *
   * <p>전체·샘플·행 수·스트리밍이 모두 이 규칙을 지나 미리보기와 적재가 같은 행을 본다.
   */
  private static RowConsumer excelDataRows(
      int[] skipped, BiPredicate<List<String>, List<String>> onRow) {
    @SuppressWarnings("unchecked")
    final List<String>[] headerHolder = new List[] {null}; // 람다 캡처 제약 우회
    return (idx, cells) -> {
      if (headerHolder[0] == null) {
        // 첫 행 = 헤더
        headerHolder[0] = new ArrayList<>(cells);
        FileHeaderValidator.requireDistinctNames(headerHolder[0]); // WD-205
        return true;
      }
      if (isBlankRow(cells, headerHolder[0].size())) {
        if (skipped != null) skipped[0]++;
        return true; // 빈 행은 건너뛰고 계속 읽는다(샘플 maxRows 에도 세지 않는다)
      }
      return onRow.test(headerHolder[0], cells);
    };
  }

  /** 헤더 이름 목록과 셀 목록을 {헤더: 값} Map 으로 바꾼다. 셀이 모자라면 빈 문자열로 채운다. */
  private static Map<String, String> toRowMap(List<String> headers, List<String> cells) {
    Map<String, String> row = HashMap.newHashMap(headers.size());
    for (int i = 0; i < headers.size(); i++) {
      row.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
    }
    return row;
  }

  /**
   * 엑셀의 첫 행(헤더)만 읽는다.
   *
   * <p>첫 행 수신 직후 false를 반환해 파싱을 조기 종료한다(early-exit). 대용량 파일에서도 헤더만 빠르게 얻는다.
   */
  private static List<String> excelHeaders(ExcelSource source, ParseOptions opts) throws Exception {
    @SuppressWarnings("unchecked")
    final List<String>[] headerHolder = new List[] {null};
    source.read(
        opts.sheetName(),
        (idx, cells) -> {
          headerHolder[0] = new ArrayList<>(cells);
          FileHeaderValidator.requireDistinctNames(headerHolder[0]); // WD-205
          return false; // 첫 행 후 즉시 중단
        });
    return headerHolder[0] != null ? headerHolder[0] : Collections.emptyList();
  }

  /**
   * 엑셀의 데이터 행을 최대 {@code maxRows} 개까지 {헤더: 값} Map 으로 읽는다. 전체를 읽을 땐 {@link Integer#MAX_VALUE}.
   *
   * <p>maxRows 에 닿으면 false 를 돌려 파싱을 조기 종료한다 — 미리보기 샘플이 파일 전체를 읽지 않게 한다.
   */
  private static List<Map<String, String>> excelRows(
      ExcelSource source, ParseOptions opts, int maxRows) throws Exception {
    final List<Map<String, String>> result = new ArrayList<>();
    source.read(
        opts.sheetName(),
        excelDataRows(
            null,
            (headers, cells) -> {
              result.add(toRowMap(headers, cells));
              return result.size() < maxRows; // maxRows 도달 시 중단
            }));
    return result;
  }

  /**
   * 엑셀의 데이터 행 수(헤더·빈 행 제외)를 센다.
   *
   * <p>Map 을 만들지 않고 데이터 행만 센다 — 미리보기 totalRows 가 실제 적재 행 수와 같아야 한다(WD-255).
   */
  private static int excelCount(ExcelSource source, ParseOptions opts) throws Exception {
    final int[] counter = new int[] {0};
    source.read(
        opts.sheetName(),
        excelDataRows(
            null,
            (headers, cells) -> {
              counter[0]++;
              return true;
            }));
    return counter[0];
  }

  /**
   * 엑셀을 batchSize 단위로 배치 콜백 스트리밍 파싱한다.
   *
   * <p>첫 행을 헤더로 캡처하고, 이후 행을 배치에 누적하다가 batchSize에 도달하면 {@code onBatch}를 호출한 뒤 배치를 비운다. 전체 행을 순회한 뒤
   * 잔여 배치를 flush하고, 건너뛴 빈 행 수를 돌려준다(WD-255).
   */
  private static int excelStreaming(
      ExcelSource source,
      ParseOptions opts,
      int batchSize,
      Consumer<List<Map<String, String>>> onBatch)
      throws Exception {
    @SuppressWarnings("unchecked")
    final List<Map<String, String>>[] batchHolder = new List[] {new ArrayList<>(batchSize)};
    final int[] skipped = {0};

    source.read(
        opts.sheetName(),
        excelDataRows(
            skipped,
            (headers, cells) -> {
              batchHolder[0].add(toRowMap(headers, cells));
              if (batchHolder[0].size() >= batchSize) {
                onBatch.accept(batchHolder[0]);
                batchHolder[0] = new ArrayList<>(batchSize);
              }
              return true;
            }));

    if (!batchHolder[0].isEmpty()) {
      onBatch.accept(batchHolder[0]);
    }
    return skipped[0];
  }
}
