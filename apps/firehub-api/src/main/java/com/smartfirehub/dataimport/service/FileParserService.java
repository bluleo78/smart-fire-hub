package com.smartfirehub.dataimport.service;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
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
      case "xlsx", "xls" -> parseHeadersExcel(inputStream);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 샘플 행을 InputStream에서 파싱한다. */
  public List<Map<String, String>> parseSampleRows(
      InputStream inputStream, String fileType, int maxRows, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseSampleRowsCsvFromStream(inputStream, maxRows, opts);
      case "xlsx", "xls" -> parseSampleRowsExcel(inputStream, maxRows);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 전체 행 수를 InputStream에서 계산한다. */
  public int countRows(InputStream inputStream, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> countRowsCsvFromStream(inputStream, opts);
      case "xlsx", "xls" -> countRowsExcel(inputStream);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  /** CSV/XLSX 전체 데이터를 InputStream에서 파싱한다. */
  public List<Map<String, String>> parse(
      InputStream inputStream, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseCsvFromStream(inputStream, opts);
      case "xlsx", "xls" -> parseExcel(inputStream);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  // -----------------------------------------------------------------------
  // Public API — with ParseOptions
  // -----------------------------------------------------------------------

  public List<Map<String, String>> parseCSV(InputStream inputStream, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReader(inputStream, opts)) {
      // skip rows before header
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) break;
      }

      List<String[]> allRows = reader.readAll();
      if (allRows.isEmpty()) {
        return Collections.emptyList();
      }

      String[] headers;
      int dataStart;
      if (opts.hasHeader()) {
        headers = allRows.get(0);
        dataStart = 1;
      } else {
        // generate column_1, column_2, ... based on first row width
        int colCount = allRows.get(0).length;
        headers = new String[colCount];
        for (int i = 0; i < colCount; i++) {
          headers[i] = "column_" + (i + 1);
        }
        dataStart = 0;
      }

      List<Map<String, String>> result = new ArrayList<>();
      for (int i = dataStart; i < allRows.size(); i++) {
        String[] row = allRows.get(i);
        Map<String, String> rowMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < row.length; j++) {
          rowMap.put(headers[j], row[j]);
        }
        result.add(rowMap);
      }
      return result;
    }
  }

  public List<Map<String, String>> parse(byte[] fileData, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseCsvFromBytes(fileData, opts);
      case "xlsx", "xls" -> parseExcel(new ByteArrayInputStream(fileData));
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public List<String> parseHeaders(byte[] fileData, String fileType, ParseOptions opts)
      throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseHeadersCsvFromBytes(fileData, opts);
      case "xlsx", "xls" -> parseHeadersExcel(new ByteArrayInputStream(fileData));
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public List<Map<String, String>> parseSampleRows(
      byte[] fileData, String fileType, int maxRows, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> parseSampleRowsCsvFromBytes(fileData, maxRows, opts);
      case "xlsx", "xls" -> parseSampleRowsExcel(new ByteArrayInputStream(fileData), maxRows);
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  public int countRows(byte[] fileData, String fileType, ParseOptions opts) throws Exception {
    return switch (fileType.toLowerCase()) {
      case "csv" -> countRowsCsvFromBytes(fileData, opts);
      case "xlsx", "xls" -> countRowsExcel(new ByteArrayInputStream(fileData));
      default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
    };
  }

  // -----------------------------------------------------------------------
  // Excel (no ParseOptions — Excel handles encoding internally)
  // -----------------------------------------------------------------------

  /**
   * XLSX/XLS 파일 전체를 파싱하여 헤더-값 맵의 리스트로 반환한다.
   *
   * <p>첫 행을 헤더로 인식하고, 나머지 행을 {헤더: 값} 형태의 Map으로 누적한다. ExcelStreamingParser를 통해 DOM 없이 스트리밍 방식으로
   * 처리한다.
   */
  public List<Map<String, String>> parseExcel(InputStream inputStream) throws Exception {
    // 첫 행을 헤더로 캡처하기 위해 배열로 감싼다(람다 캡처 제약 우회)
    @SuppressWarnings("unchecked")
    final List<String>[] headerHolder = new List[] {null};
    final List<Map<String, String>> result = new ArrayList<>();

    ExcelStreamingParser.parse(
        inputStream,
        (idx, cells) -> {
          if (headerHolder[0] == null) {
            // 첫 행 = 헤더
            headerHolder[0] = new ArrayList<>(cells);
            return true;
          }
          List<String> headers = headerHolder[0];
          Map<String, String> row = new HashMap<>();
          for (int i = 0; i < headers.size(); i++) {
            String value = i < cells.size() ? cells.get(i) : "";
            row.put(headers.get(i), value);
          }
          result.add(row);
          return true;
        });

    return result;
  }

  // -----------------------------------------------------------------------
  // Private CSV helpers with ParseOptions
  // -----------------------------------------------------------------------

  // -----------------------------------------------------------------------
  // Private CSV helpers with byte[] (supports AUTO encoding detection)
  // -----------------------------------------------------------------------

  private List<Map<String, String>> parseCsvFromBytes(byte[] data, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) break;
      }
      List<String[]> allRows = reader.readAll();
      if (allRows.isEmpty()) return Collections.emptyList();
      String[] headers;
      int dataStart;
      if (opts.hasHeader()) {
        headers = allRows.get(0);
        dataStart = 1;
      } else {
        int colCount = allRows.get(0).length;
        headers = new String[colCount];
        for (int i = 0; i < colCount; i++) headers[i] = "column_" + (i + 1);
        dataStart = 0;
      }
      List<Map<String, String>> result = new ArrayList<>();
      for (int i = dataStart; i < allRows.size(); i++) {
        String[] row = allRows.get(i);
        Map<String, String> rowMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
        result.add(rowMap);
      }
      return result;
    }
  }

  private List<String> parseHeadersCsvFromBytes(byte[] data, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return Collections.emptyList();
      }
      if (opts.hasHeader()) {
        String[] headers = reader.readNext();
        return headers != null ? Arrays.asList(headers) : Collections.emptyList();
      } else {
        String[] firstRow = reader.readNext();
        if (firstRow == null) return Collections.emptyList();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < firstRow.length; i++) headers.add("column_" + (i + 1));
        return headers;
      }
    }
  }

  private List<Map<String, String>> parseSampleRowsCsvFromBytes(
      byte[] data, int maxRows, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return Collections.emptyList();
      }
      // readAll() 대신 readNextRecord() 반복으로 maxRows 개수만 읽어 메모리 최소화(#169)
      String[] headers;
      if (opts.hasHeader()) {
        headers = reader.readNext();
        if (headers == null) return Collections.emptyList();
      } else {
        String[] firstRow = reader.readNext();
        if (firstRow == null) return Collections.emptyList();
        int colCount = firstRow.length;
        headers = new String[colCount];
        for (int i = 0; i < colCount; i++) headers[i] = "column_" + (i + 1);
        // 헤더가 없는 경우 첫 행도 데이터로 포함
        Map<String, String> firstMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < firstRow.length; j++)
          firstMap.put(headers[j], firstRow[j]);
        List<Map<String, String>> result = new ArrayList<>();
        result.add(firstMap);
        String[] row;
        while (result.size() < maxRows && (row = reader.readNext()) != null) {
          Map<String, String> rowMap = new HashMap<>();
          for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
          result.add(rowMap);
        }
        return result;
      }
      List<Map<String, String>> result = new ArrayList<>();
      String[] row;
      while (result.size() < maxRows && (row = reader.readNext()) != null) {
        Map<String, String> rowMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
        result.add(rowMap);
      }
      return result;
    }
  }

  private int countRowsCsvFromBytes(byte[] data, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromBytes(data, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return 0;
      }
      List<String[]> allRows = reader.readAll();
      if (allRows.isEmpty()) return 0;
      return opts.hasHeader() ? Math.max(0, allRows.size() - 1) : allRows.size();
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
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) break;
      }
      List<String[]> allRows = reader.readAll();
      if (allRows.isEmpty()) return Collections.emptyList();
      String[] headers;
      int dataStart;
      if (opts.hasHeader()) {
        headers = allRows.get(0);
        dataStart = 1;
      } else {
        int colCount = allRows.get(0).length;
        headers = new String[colCount];
        for (int i = 0; i < colCount; i++) headers[i] = "column_" + (i + 1);
        dataStart = 0;
      }
      List<Map<String, String>> result = new ArrayList<>();
      for (int i = dataStart; i < allRows.size(); i++) {
        String[] row = allRows.get(i);
        Map<String, String> rowMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
        result.add(rowMap);
      }
      return result;
    }
  }

  private List<String> parseHeadersCsvFromStream(InputStream inputStream, ParseOptions opts)
      throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return Collections.emptyList();
      }
      if (opts.hasHeader()) {
        String[] headers = reader.readNext();
        return headers != null ? Arrays.asList(headers) : Collections.emptyList();
      } else {
        String[] firstRow = reader.readNext();
        if (firstRow == null) return Collections.emptyList();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < firstRow.length; i++) headers.add("column_" + (i + 1));
        return headers;
      }
    }
  }

  private List<Map<String, String>> parseSampleRowsCsvFromStream(
      InputStream inputStream, int maxRows, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return Collections.emptyList();
      }
      // readAll() 대신 readNextRecord() 반복으로 maxRows 개수만 읽어 메모리 최소화(#169)
      String[] headers;
      if (opts.hasHeader()) {
        headers = reader.readNext();
        if (headers == null) return Collections.emptyList();
      } else {
        String[] firstRow = reader.readNext();
        if (firstRow == null) return Collections.emptyList();
        int colCount = firstRow.length;
        headers = new String[colCount];
        for (int i = 0; i < colCount; i++) headers[i] = "column_" + (i + 1);
        // 헤더가 없는 경우 첫 행도 데이터로 포함
        Map<String, String> firstMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < firstRow.length; j++)
          firstMap.put(headers[j], firstRow[j]);
        List<Map<String, String>> result = new ArrayList<>();
        result.add(firstMap);
        String[] row;
        while (result.size() < maxRows && (row = reader.readNext()) != null) {
          Map<String, String> rowMap = new HashMap<>();
          for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
          result.add(rowMap);
        }
        return result;
      }
      List<Map<String, String>> result = new ArrayList<>();
      String[] row;
      while (result.size() < maxRows && (row = reader.readNext()) != null) {
        Map<String, String> rowMap = new HashMap<>();
        for (int j = 0; j < headers.length && j < row.length; j++) rowMap.put(headers[j], row[j]);
        result.add(rowMap);
      }
      return result;
    }
  }

  private int countRowsCsvFromStream(InputStream inputStream, ParseOptions opts) throws Exception {
    try (CSVReader reader = buildCsvReaderFromStream(inputStream, opts)) {
      for (int s = 0; s < opts.skipRows(); s++) {
        if (reader.readNext() == null) return 0;
      }
      List<String[]> allRows = reader.readAll();
      if (allRows.isEmpty()) return 0;
      return opts.hasHeader() ? Math.max(0, allRows.size() - 1) : allRows.size();
    }
  }

  // -----------------------------------------------------------------------
  // Private CSV reader builders
  // -----------------------------------------------------------------------

  private CSVReader buildCsvReader(InputStream inputStream, ParseOptions opts) {
    char sep = opts.delimiter().charAt(0);
    String encoding = opts.encoding();
    if ("AUTO".equals(encoding)) {
      encoding = "UTF-8"; // fallback; use buildCsvReaderFromBytes for auto-detect
    }
    Charset charset = Charset.forName(encoding);
    CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
    return new CSVReaderBuilder(new InputStreamReader(inputStream, charset))
        .withCSVParser(parser)
        .build();
  }

  private CSVReader buildCsvReaderFromBytes(byte[] data, ParseOptions opts) {
    char sep = opts.delimiter().charAt(0);
    String encoding = opts.encoding();
    if ("AUTO".equals(encoding)) {
      encoding = ParseOptions.detectEncoding(data);
    }
    Charset charset = Charset.forName(encoding);
    CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
    return new CSVReaderBuilder(new InputStreamReader(new ByteArrayInputStream(data), charset))
        .withCSVParser(parser)
        .build();
  }

  /**
   * InputStream 기반 CSVReader 빌더. AUTO 인코딩인 경우: BufferedInputStream에 mark를 설정하고 선행 바이트를 읽어 인코딩을 감지한
   * 뒤 reset()으로 스트림 처음으로 돌아간다. mark를 지원하지 않는 InputStream이면 BufferedInputStream으로 감싼다.
   */
  private CSVReader buildCsvReaderFromStream(InputStream inputStream, ParseOptions opts)
      throws IOException {
    char sep = opts.delimiter().charAt(0);
    String encoding = opts.encoding();

    if ("AUTO".equals(encoding)) {
      // mark/reset으로 선행 바이트만 읽어 인코딩 감지 후 스트림 리셋
      BufferedInputStream buffered =
          inputStream instanceof BufferedInputStream bi
              ? bi
              : new BufferedInputStream(inputStream, ENCODING_PEEK_LIMIT * 2);
      buffered.mark(ENCODING_PEEK_LIMIT);
      byte[] peek = buffered.readNBytes(ENCODING_PEEK_LIMIT);
      buffered.reset();
      encoding = ParseOptions.detectEncoding(peek);
      inputStream = buffered; // reset 된 스트림을 사용
    }

    Charset charset = Charset.forName(encoding);
    CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
    return new CSVReaderBuilder(new InputStreamReader(inputStream, charset))
        .withCSVParser(parser)
        .build();
  }

  // -----------------------------------------------------------------------
  // Private Excel helpers (no ParseOptions)
  // -----------------------------------------------------------------------

  /**
   * XLSX/XLS 파일의 첫 행(헤더)만 파싱하여 반환한다.
   *
   * <p>첫 행 수신 직후 false를 반환해 파싱을 조기 종료한다(early-exit). 대용량 파일에서 불필요한 DOM 적재 없이 빠르게 헤더를 추출한다.
   */
  private List<String> parseHeadersExcel(InputStream inputStream) throws Exception {
    @SuppressWarnings("unchecked")
    final List<String>[] headerHolder = new List[] {null};

    ExcelStreamingParser.parse(
        inputStream,
        (idx, cells) -> {
          headerHolder[0] = new ArrayList<>(cells);
          return false; // 첫 행 후 즉시 중단
        });

    return headerHolder[0] != null ? headerHolder[0] : Collections.emptyList();
  }

  /**
   * XLSX/XLS 파일에서 헤더를 포함하여 최대 maxRows개의 샘플 데이터 행을 파싱한다.
   *
   * <p>결과가 maxRows에 도달하면 false를 반환하여 파싱을 조기 종료한다. 대용량 파일에서 미리보기 용도로 일부만 읽을 때 활용한다.
   */
  private List<Map<String, String>> parseSampleRowsExcel(InputStream inputStream, int maxRows)
      throws Exception {
    @SuppressWarnings("unchecked")
    final List<String>[] headerHolder = new List[] {null};
    final List<Map<String, String>> result = new ArrayList<>();

    ExcelStreamingParser.parse(
        inputStream,
        (idx, cells) -> {
          if (headerHolder[0] == null) {
            // 첫 행 = 헤더
            headerHolder[0] = new ArrayList<>(cells);
            return true;
          }
          List<String> headers = headerHolder[0];
          Map<String, String> row = new HashMap<>();
          for (int i = 0; i < headers.size(); i++) {
            String value = i < cells.size() ? cells.get(i) : "";
            row.put(headers.get(i), value);
          }
          result.add(row);
          return result.size() < maxRows; // maxRows 도달 시 중단
        });

    return result;
  }

  /**
   * XLSX/XLS 파일의 데이터 행 수(헤더 제외)를 반환한다.
   *
   * <p>콜백으로 전체 행을 카운트한 뒤 헤더 1행을 빼서 반환한다. 기존 lastRowNum 기반 동작(0-based 인덱스 = 데이터 행 수)과 동등하다.
   */
  private int countRowsExcel(InputStream inputStream) throws Exception {
    final int[] counter = new int[] {0};

    ExcelStreamingParser.parse(
        inputStream,
        (idx, cells) -> {
          counter[0]++;
          return true;
        });

    // 헤더 행 1개를 제외한 데이터 행 수 반환
    return Math.max(0, counter[0] - 1);
  }
}
