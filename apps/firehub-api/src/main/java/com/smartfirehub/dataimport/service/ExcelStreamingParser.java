package com.smartfirehub.dataimport.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.binary.XSSFBSharedStringsTable;
import org.apache.poi.xssf.binary.XSSFBSheetHandler;
import org.apache.poi.xssf.binary.XSSFBStylesTable;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFBReader;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * XLSX/XLS/XLSB 파일을 이벤트 기반으로 파싱한다.
 *
 * <p>워크북 전체 DOM을 메모리에 적재하지 않고 한 행씩 {@link RowConsumer}로 흘려보낸다. RowConsumer가 false를 반환하면 즉시 파싱을
 * 중단한다(early-exit). 첫 시트만 처리한다(현재 동작과 동일).
 */
public final class ExcelStreamingParser {

  private ExcelStreamingParser() {}

  /**
   * Early-exit 신호용 sentinel. RuntimeException으로 선언하여 SheetContentsHandler 콜백에서 checked exception 제약
   * 없이 던질 수 있다. SAX 파서가 RuntimeException을 SAXException으로 감싸므로 parseXlsx에서 언래핑하여 흡수한다.
   */
  private static final class EarlyStopException extends RuntimeException {
    private static final EarlyStopException INSTANCE = new EarlyStopException();
  }

  /**
   * 포맷을 자동 감지하여 XLSX/XLSB 또는 XLS 파서로 분기한다.
   *
   * <p><b>Thin wrapper:</b> {@link OPCPackage#open(InputStream)}은 ZIP의 각 엔트리를 전부 메모리 byte[]로
   * 버퍼링하기 때문에(특히 엔트리 크기를 미리 알 수 없는 data-descriptor zip에서는 POI temp-file 임계값 설정도
   * 무력화됨) 대용량 파일에서 OOM/파싱 실패를 유발한다. 이를 피하기 위해 InputStream을 임시 파일로 1회 복사한 뒤
   * 실제 파싱은 랜덤액세스가 가능한 {@link #parse(File, RowConsumer)}에 위임하고, 종료 시 임시 파일을 즉시
   * 삭제한다. 기존 호출부(FileParserService 등)는 시그니처 변경 없이 그대로 사용 가능하다.
   */
  public static void parse(InputStream in, RowConsumer consumer) throws Exception {
    File temp = File.createTempFile("excel-streaming-", ".tmp");
    // try-with-resources로 전달받은 스트림을 복사 후 닫는다. 기존 구현은 OPCPackage.open(in)/
    // POIFSFileSystem(in)이 close 시 내부적으로 스트림을 닫아줬으므로, 그 닫기 계약을 그대로 유지해
    // 파일 디스크립터 누수를 막는다(close는 멱등이라 호출자가 다시 닫아도 안전).
    try (in) {
      Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
      parse(temp, consumer);
    } finally {
      Files.deleteIfExists(temp.toPath());
    }
  }

  /**
   * 파일에서 직접 랜덤액세스로 열어 파싱하는 core 진입점.
   *
   * <p>{@link FileMagic#valueOf(File)}로 포맷을 감지한 뒤, OOXML(XLSX/XLSB)은 {@link OPCPackage#open(File,
   * PackageAccess)}로, OLE2(XLS)는 {@link POIFSFileSystem#POIFSFileSystem(File)}로 연다. 두 API 모두 ZIP/OLE2
   * 컨테이너를 파일 채널 기반 랜덤액세스로 읽어 엔트리를 전량 메모리에 적재하지 않고 필요한 부분만 온디맨드로
   * 읽으므로, {@code InputStream} 기반 오픈에서 발생하던 대용량 파일 OOM을 근본적으로 회피한다.
   */
  public static void parse(File file, RowConsumer consumer) throws Exception {
    FileMagic magic = FileMagic.valueOf(file);
    switch (magic) {
      case OOXML -> parseOoxml(file, consumer);
      case OLE2 -> parseXls(file, consumer);
      default -> throw new IOException("Unsupported Excel format: " + magic);
    }
  }

  // ---------------------------------------------------------------------
  // XLSX/XLSB (OOXML 컨테이너) — 워크북 파트 content-type으로 XLSB 여부 판별 후 분기
  // ---------------------------------------------------------------------

  /**
   * XLSX와 XLSB는 둘 다 ZIP 기반 OOXML 컨테이너라 {@link FileMagic}만으로는 구분할 수 없다. 워크북 파트의 content-type을 확인해
   * 바이너리(XLSB) 여부를 판별한다.
   *
   * <p>{@code PackageAccess.READ}로 열어 ZipFile 랜덤액세스(중앙 디렉터리 기반 온디맨드 읽기)를 사용한다 — 이
   * 방식은 엔트리 크기가 ZIP 중앙 디렉터리에서 항상 확정적으로 조회되므로, data-descriptor(사이즈 미상) 엔트리에서
   * 무력화되는 temp-file 임계값 설정과 달리 항상 안전하게 스트리밍된다.
   */
  private static void parseOoxml(File file, RowConsumer consumer) throws Exception {
    try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
      boolean isXlsb =
          !pkg.getPartsByContentType(XSSFRelation.XLSB_BINARY_WORKBOOK.getContentType())
              .isEmpty();
      if (isXlsb) {
        parseXlsb(pkg, consumer);
      } else {
        parseXlsx(pkg, consumer);
      }
    } catch (OpenXML4JException e) {
      throw new IOException("Failed to open XLSX/XLSB package", e);
    }
  }

  // ---------------------------------------------------------------------
  // XLSX — XSSFReader + XSSFSheetXMLHandler SAX
  // ---------------------------------------------------------------------

  private static void parseXlsx(OPCPackage pkg, RowConsumer consumer) throws Exception {
    XSSFReader reader = new XSSFReader(pkg);
    ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
    StylesTable styles = reader.getStylesTable();

    XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
    if (!sheets.hasNext()) return;
    try (InputStream sheetStream = sheets.next()) {
      SheetContentsHandler handler = new RowAggregatingHandler(consumer);
      XMLReader xmlReader = newXmlReader();
      xmlReader.setContentHandler(
          new XSSFSheetXMLHandler(styles, strings, handler, new LegacyExcelDataFormatter(), false));
      try {
        xmlReader.parse(new InputSource(sheetStream));
      } catch (EarlyStopException ignore) {
        // 정상 조기 종료 — Java SAX 구현이 RuntimeException을 감싸지 않고 직접 전파함
      } catch (SAXException e) {
        // SAX 파서가 RuntimeException을 SAXException으로 감싸는 경우(구버전 런타임 대비)
        if (!(e.getCause() instanceof EarlyStopException)
            && !(e.getException() instanceof EarlyStopException)) {
          throw e;
        }
        // 정상 조기 종료
      }
    }
  }

  // ---------------------------------------------------------------------
  // XLSB — XSSFBReader + XSSFBSheetHandler 바이너리 레코드 파서
  // ---------------------------------------------------------------------

  /**
   * XLSB는 시트가 XML이 아닌 바이너리 레코드(BIFF12)로 저장된다. POI의 {@code org.apache.poi.xssf.binary} 패키지가
   * 제공하는 저수준 레코드 파서를 사용하며, {@link XSSFBSheetHandler}는 XLSX와 동일한 {@link SheetContentsHandler}
   * 인터페이스를 받으므로 {@link RowAggregatingHandler}를 그대로 재사용한다.
   */
  private static void parseXlsb(OPCPackage pkg, RowConsumer consumer) throws Exception {
    XSSFBReader reader = new XSSFBReader(pkg);
    XSSFBSharedStringsTable strings = new XSSFBSharedStringsTable(pkg);
    XSSFBStylesTable styles = reader.getXSSFBStylesTable();

    var sheets = reader.getSheetsData();
    if (!sheets.hasNext()) return;
    try (InputStream sheetStream = sheets.next()) {
      SheetContentsHandler handler = new RowAggregatingHandler(consumer);
      XSSFBSheetHandler sheetHandler =
          new XSSFBSheetHandler(
              sheetStream, styles, null, strings, handler, new LegacyExcelDataFormatter(), false);
      try {
        sheetHandler.parse();
      } catch (EarlyStopException ignore) {
        // 정상 조기 종료
      }
    }
  }

  private static XMLReader newXmlReader() throws SAXException, ParserConfigurationException {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    SAXParser parser = factory.newSAXParser();
    return parser.getXMLReader();
  }

  /**
   * SheetContentsHandler 구현. 행 단위로 셀을 누적하고 endRow에서 RowConsumer를 호출한다. 빈 셀은 cellRef의 컬럼 인덱스로 보정한다.
   */
  private static final class RowAggregatingHandler implements SheetContentsHandler {
    private final RowConsumer consumer;
    private List<String> currentRow;
    private int currentRowIndex = -1;

    RowAggregatingHandler(RowConsumer consumer) {
      this.consumer = consumer;
    }

    @Override
    public void startRow(int rowNum) {
      currentRow = new ArrayList<>();
      currentRowIndex = rowNum;
    }

    @Override
    public void endRow(int rowNum) {
      if (currentRow == null) return;
      boolean keep = consumer.accept(currentRowIndex, currentRow);
      currentRow = null;
      if (!keep) {
        throw EarlyStopException.INSTANCE;
      }
    }

    /**
     * 셀 값을 현재 행에 추가한다.
     *
     * <p>POI {@code XSSFSheetXMLHandler}는 셀 타입을 {@link SheetContentsHandler}에 노출하지 않으므로, 실제 불리언 셀과
     * "TRUE"/"FALSE" 리터럴 문자열 셀을 이 콜백에서 구분할 수 없다. 따라서 불리언 정규화를 수행하지 않고 POI가 직렬화한 값을 그대로 저장한다.
     *
     * <p><b>불리언 동작 변경 사항:</b> 실제 불리언 셀은 POI 내부 변환에 의해 "TRUE"/"FALSE"(대문자)로 출력된다. 기존 {@code
     * getCellValueAsString}이 "true"/"false"(소문자)를 반환하던 것과 미세하게 다르다. 이는 문자열 셀에 "TRUE"/"FALSE"가 입력된
     * 경우 값을 손상시키지 않기 위한 올바른 트레이드오프이다.
     */
    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
      if (currentRow == null) return;
      int colIdx;
      if (cellReference == null) {
        colIdx = currentRow.size();
      } else {
        colIdx = new CellAddress(cellReference).getColumn();
      }
      // 빈 셀 보정: 부족한 만큼 빈 문자열 채우기
      while (currentRow.size() < colIdx) {
        currentRow.add("");
      }
      currentRow.add(formattedValue == null ? "" : formattedValue);
    }
  }

  // ---------------------------------------------------------------------
  // XLS (BIFF) — POIFSFileSystem + HSSFEventFactory
  // ---------------------------------------------------------------------

  private static void parseXls(File file, RowConsumer consumer) throws Exception {
    // readOnly=true — 파싱 전용이므로 파일 잠금/쓰기 권한이 필요 없다(읽기 전용 마운트에서도 안전).
    try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
      org.apache.poi.hssf.eventusermodel.HSSFEventFactory factory =
          new org.apache.poi.hssf.eventusermodel.HSSFEventFactory();
      org.apache.poi.hssf.eventusermodel.HSSFRequest request =
          new org.apache.poi.hssf.eventusermodel.HSSFRequest();

      XlsListener xls = new XlsListener(consumer);
      org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener formatTracker =
          new org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener(
              new org.apache.poi.hssf.eventusermodel.MissingRecordAwareHSSFListener(xls));
      xls.setFormatTracker(formatTracker);
      request.addListenerForAllRecords(formatTracker);

      try {
        factory.processWorkbookEvents(request, fs);
      } catch (XlsListener.EarlyStopRuntimeException ignore) {
        // 정상 조기 종료
      }
    }
  }

  /**
   * HSSF 이벤트 리스너. 첫 시트만 처리하며 LastCellOfRowDummyRecord에서 RowConsumer를 호출한다.
   *
   * <p>SST(공유 문자열) 테이블은 SSTRecord 도착 시 캐시한다. LegacyExcelDataFormatter로 숫자/날짜 셀을 ISO 형식으로 변환한다.
   */
  private static final class XlsListener
      implements org.apache.poi.hssf.eventusermodel.HSSFListener {

    private final RowConsumer consumer;
    private org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener formatTracker;
    private final LegacyExcelDataFormatter formatter = new LegacyExcelDataFormatter();
    private org.apache.poi.hssf.record.SSTRecord sst;

    private int sheetIndex = -1;
    private boolean inFirstSheet = false;

    private List<String> currentRow;
    private int currentRowIndex = -1;

    /** Early-exit 신호용 RuntimeException sentinel. HSSFEventFactory processRecord에서 던진다. */
    static final class EarlyStopRuntimeException extends RuntimeException {
      static final EarlyStopRuntimeException INSTANCE = new EarlyStopRuntimeException();
    }

    XlsListener(RowConsumer consumer) {
      this.consumer = consumer;
    }

    void setFormatTracker(org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener tracker) {
      this.formatTracker = tracker;
    }

    @Override
    public void processRecord(org.apache.poi.hssf.record.Record record) {
      switch (record.getSid()) {
        case org.apache.poi.hssf.record.BoundSheetRecord.sid:
          // 시트 메타데이터 — 현재 사용하지 않음(첫 시트만 처리)
          return;
        case org.apache.poi.hssf.record.BOFRecord.sid:
          org.apache.poi.hssf.record.BOFRecord bof = (org.apache.poi.hssf.record.BOFRecord) record;
          if (bof.getType() == org.apache.poi.hssf.record.BOFRecord.TYPE_WORKSHEET) {
            sheetIndex++;
            inFirstSheet = (sheetIndex == 0);
          }
          return;
        case org.apache.poi.hssf.record.SSTRecord.sid:
          sst = (org.apache.poi.hssf.record.SSTRecord) record;
          return;
      }

      if (!inFirstSheet) return;

      if (record instanceof org.apache.poi.hssf.eventusermodel.dummyrecord.MissingRowDummyRecord) {
        // 빈 행: 무시(현재 동작과 동일하게 데이터 행으로 카운트하지 않음)
        return;
      }

      // 행 시작: 새 row index 감지
      int rowNum = extractRowNumber(record);
      if (rowNum >= 0 && rowNum != currentRowIndex) {
        currentRowIndex = rowNum;
        currentRow = new ArrayList<>();
      }

      switch (record.getSid()) {
        case org.apache.poi.hssf.record.LabelSSTRecord.sid:
          {
            org.apache.poi.hssf.record.LabelSSTRecord r =
                (org.apache.poi.hssf.record.LabelSSTRecord) record;
            appendCell(
                r.getColumn(), sst != null ? sst.getString(r.getSSTIndex()).getString() : "");
            break;
          }
        case org.apache.poi.hssf.record.NumberRecord.sid:
          {
            org.apache.poi.hssf.record.NumberRecord r =
                (org.apache.poi.hssf.record.NumberRecord) record;
            int formatIndex = formatTracker.getFormatIndex(r);
            String formatString = formatTracker.getFormatString(r);
            appendCell(
                r.getColumn(),
                formatter.formatRawCellContents(
                    r.getValue(), formatIndex, formatString == null ? "General" : formatString));
            break;
          }
        case org.apache.poi.hssf.record.BoolErrRecord.sid:
          {
            org.apache.poi.hssf.record.BoolErrRecord r =
                (org.apache.poi.hssf.record.BoolErrRecord) record;
            appendCell(r.getColumn(), r.isBoolean() ? String.valueOf(r.getBooleanValue()) : "");
            break;
          }
        case org.apache.poi.hssf.record.FormulaRecord.sid:
          {
            org.apache.poi.hssf.record.FormulaRecord r =
                (org.apache.poi.hssf.record.FormulaRecord) record;
            if (Double.isNaN(r.getValue())) {
              // 문자열 결과 — 다음 StringRecord 대기, placeholder 추가
              appendCell(r.getColumn(), "");
            } else {
              int formatIndex = formatTracker.getFormatIndex(r);
              String formatString = formatTracker.getFormatString(r);
              appendCell(
                  r.getColumn(),
                  formatter.formatRawCellContents(
                      r.getValue(), formatIndex, formatString == null ? "General" : formatString));
            }
            break;
          }
        case org.apache.poi.hssf.record.StringRecord.sid:
          {
            org.apache.poi.hssf.record.StringRecord r =
                (org.apache.poi.hssf.record.StringRecord) record;
            // FormulaRecord 문자열 결과를 마지막 셀에 덮어씀
            if (currentRow != null && !currentRow.isEmpty()) {
              currentRow.set(currentRow.size() - 1, r.getString());
            }
            break;
          }
        case org.apache.poi.hssf.record.BlankRecord.sid:
          {
            org.apache.poi.hssf.record.BlankRecord r =
                (org.apache.poi.hssf.record.BlankRecord) record;
            appendCell(r.getColumn(), "");
            break;
          }
        default:
          if (record
              instanceof org.apache.poi.hssf.eventusermodel.dummyrecord.MissingCellDummyRecord m) {
            appendCell(m.getColumn(), "");
          } else if (record
              instanceof org.apache.poi.hssf.eventusermodel.dummyrecord.LastCellOfRowDummyRecord) {
            if (currentRow != null) {
              boolean keep = consumer.accept(currentRowIndex, currentRow);
              currentRow = null;
              if (!keep) {
                throw XlsListener.EarlyStopRuntimeException.INSTANCE;
              }
            }
          }
      }
    }

    private void appendCell(int column, String value) {
      if (currentRow == null) return;
      while (currentRow.size() < column) currentRow.add("");
      currentRow.add(value == null ? "" : value);
    }

    private static int extractRowNumber(org.apache.poi.hssf.record.Record record) {
      if (record instanceof org.apache.poi.hssf.record.CellValueRecordInterface c) {
        return c.getRow();
      }
      if (record
          instanceof org.apache.poi.hssf.eventusermodel.dummyrecord.MissingCellDummyRecord m) {
        return m.getRow();
      }
      if (record
          instanceof org.apache.poi.hssf.eventusermodel.dummyrecord.LastCellOfRowDummyRecord l) {
        return l.getRow();
      }
      return -1;
    }
  }
}
