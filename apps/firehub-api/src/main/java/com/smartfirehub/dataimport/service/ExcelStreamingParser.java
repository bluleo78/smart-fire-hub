package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.exception.FileUnreadableException;
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
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.crypt.Decryptor;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(ExcelStreamingParser.class);

  private ExcelStreamingParser() {}

  /**
   * RowConsumer(호출자 콜백)가 던진 예외를 POI 파싱 예외와 구별하기 위한 표식 래퍼 (WD-204).
   *
   * <p>콜백 안에서는 임포트 잡의 fail-fast 검증 중단·DB 적재 오류 등 파일 형식과 무관한 예외가 나온다. 이를 「엑셀 파일을 읽을 수 없습니다」로 바꾸면 실패
   * 사유가 거짓이 되므로, 콜백 예외만 이 래퍼로 감싸 두었다가 바깥에서 원래 예외로 되돌려 그대로 전파한다.
   */
  private static final class ConsumerFailure extends RuntimeException {
    private final RuntimeException original;

    ConsumerFailure(RuntimeException original) {
      super(null, original, false, false);
      this.original = original;
    }
  }

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
   * <p><b>Thin wrapper:</b> {@link OPCPackage#open(InputStream)}은 ZIP의 각 엔트리를 전부 메모리 byte[]로 버퍼링하기
   * 때문에(특히 엔트리 크기를 미리 알 수 없는 data-descriptor zip에서는 POI temp-file 임계값 설정도 무력화됨) 대용량 파일에서 OOM/파싱 실패를
   * 유발한다. 이를 피하기 위해 InputStream을 임시 파일로 1회 복사한 뒤 실제 파싱은 랜덤액세스가 가능한 {@link #parse(File,
   * RowConsumer)}에 위임하고, 종료 시 임시 파일을 즉시 삭제한다. 기존 호출부(FileParserService 등)는 시그니처 변경 없이 그대로 사용 가능하다.
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
   * PackageAccess)}로, OLE2(XLS)는 {@link POIFSFileSystem#POIFSFileSystem(File)}로 연다. 두 API 모두
   * ZIP/OLE2 컨테이너를 파일 채널 기반 랜덤액세스로 읽어 엔트리를 전량 메모리에 적재하지 않고 필요한 부분만 온디맨드로 읽으므로, {@code InputStream}
   * 기반 오픈에서 발생하던 대용량 파일 OOM을 근본적으로 회피한다.
   */
  public static void parse(File file, RowConsumer consumer) throws Exception {
    // 콜백 예외에 표식을 달아 파일 파싱 실패와 구별한다(ConsumerFailure 참고).
    RowConsumer guarded =
        (idx, cells) -> {
          try {
            return consumer.accept(idx, cells);
          } catch (RuntimeException e) {
            throw new ConsumerFailure(e);
          }
        };
    try {
      FileMagic magic = FileMagic.valueOf(file);
      switch (magic) {
        case OOXML -> parseOoxml(file, guarded);
        case OLE2 -> parseXls(file, guarded);
        default -> throw new IOException("Unsupported Excel format: " + magic);
      }
    } catch (Exception e) {
      // 1) 콜백이 던진 예외는 원래 예외 그대로 돌려준다(SAX 가 감쌌을 수 있어 원인 체인을 따라간다).
      ConsumerFailure consumerFailure = findConsumerFailure(e);
      if (consumerFailure != null) {
        throw consumerFailure.original;
      }
      // 2) 파일 자체가 사라진 경우(업로드 중단 등)는 형식 문제가 아니므로 기존 예외를 유지한다 —
      //    임포트 잡의 사용자 메시지 변환이 「파일 업로드가 중간에 실패했습니다」로 따로 안내한다.
      if (!file.exists()) {
        throw e;
      }
      // 3) 그 밖의 모든 예외(알 수 없는 포맷·손상된 OOXML·빈 파일·구형 XLS·레코드 손상 등)는 파일을 읽지 못한 것이다.
      //    POI 원문(영문·내부 구조 노출)은 로그에만 남기고 사용자에게는 한국어 안내만 보낸다.
      log.warn("Excel parse failed: {}", e.toString(), e);
      String userMessage =
          hasCause(e, EncryptedDocumentException.class)
              ? FileUnreadableException.EXCEL_ENCRYPTED
              : FileUnreadableException.EXCEL_UNREADABLE;
      throw new FileUnreadableException(userMessage, e);
    }
  }

  /** 원인 체인(SAXException 의 내장 예외 포함)에서 콜백 예외 표식을 찾는다. 없으면 null. */
  private static ConsumerFailure findConsumerFailure(Throwable t) {
    for (Throwable cur = t; cur != null; cur = nextCause(cur)) {
      if (cur instanceof ConsumerFailure cf) {
        return cf;
      }
    }
    return null;
  }

  /** 원인 체인에 주어진 타입의 예외가 있는지 확인한다. */
  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable cur = t; cur != null; cur = nextCause(cur)) {
      if (type.isInstance(cur)) {
        return true;
      }
    }
    return false;
  }

  /** SAXException 은 getCause 대신 getException 에 원인을 담는 경우가 있어 둘 다 본다. 자기 참조 순환은 끊는다. */
  private static Throwable nextCause(Throwable cur) {
    Throwable next =
        (cur instanceof SAXException sax && sax.getException() != null)
            ? sax.getException()
            : cur.getCause();
    return next == cur ? null : next;
  }

  // ---------------------------------------------------------------------
  // XLSX/XLSB (OOXML 컨테이너) — 워크북 파트 content-type으로 XLSB 여부 판별 후 분기
  // ---------------------------------------------------------------------

  /**
   * XLSX와 XLSB는 둘 다 ZIP 기반 OOXML 컨테이너라 {@link FileMagic}만으로는 구분할 수 없다. 워크북 파트의 content-type을 확인해
   * 바이너리(XLSB) 여부를 판별한다.
   *
   * <p>{@code PackageAccess.READ}로 열어 ZipFile 랜덤액세스(중앙 디렉터리 기반 온디맨드 읽기)를 사용한다 — 이 방식은 엔트리 크기가 ZIP
   * 중앙 디렉터리에서 항상 확정적으로 조회되므로, data-descriptor(사이즈 미상) 엔트리에서 무력화되는 temp-file 임계값 설정과 달리 항상 안전하게
   * 스트리밍된다.
   */
  private static void parseOoxml(File file, RowConsumer consumer) throws Exception {
    try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
      boolean isXlsb =
          !pkg.getPartsByContentType(XSSFRelation.XLSB_BINARY_WORKBOOK.getContentType()).isEmpty();
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

    // 1904 날짜 체계 워크북이면 날짜 셀이 1,462일 앞당겨지지 않도록 formatter 에 알린다(WD-252).
    LegacyExcelDataFormatter formatter = new LegacyExcelDataFormatter(readXlsxDate1904(reader));

    XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
    if (!sheets.hasNext()) return;
    try (InputStream sheetStream = sheets.next()) {
      RowAggregatingHandler handler = new RowAggregatingHandler(consumer);
      XMLReader xmlReader = newXmlReader();
      xmlReader.setContentHandler(
          new ErrorAwareSheetXmlHandler(styles, strings, handler, formatter));
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

  /**
   * workbook.xml 의 {@code <workbookPr date1904="1">} 를 읽어 1904 날짜 체계인지 판정한다. workbookPr 를 만나면 바로
   * 멈춘다(시트 앞쪽의 작은 요소라 전체를 읽지 않는다).
   */
  private static boolean readXlsxDate1904(XSSFReader reader) throws Exception {
    final boolean[] date1904 = {false};
    XMLReader xmlReader = newXmlReader();
    xmlReader.setContentHandler(
        new org.xml.sax.helpers.DefaultHandler() {
          @Override
          public void startElement(
              String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
            if ("workbookPr".equals(localName)) {
              String v = attributes.getValue("date1904");
              date1904[0] = "1".equals(v) || "true".equalsIgnoreCase(v);
              throw EarlyStopException.INSTANCE;
            }
            if ("sheets".equals(localName)) {
              // workbookPr 는 sheets 보다 앞에 온다 — 여기까지 없으면 기본(1900) 체계다.
              throw EarlyStopException.INSTANCE;
            }
          }
        });
    try (InputStream wb = reader.getWorkbookData()) {
      xmlReader.parse(new InputSource(wb));
    } catch (EarlyStopException ignore) {
      // 필요한 속성을 읽었다
    } catch (SAXException e) {
      if (!(e.getCause() instanceof EarlyStopException)
          && !(e.getException() instanceof EarlyStopException)) {
        throw e;
      }
    }
    return date1904[0];
  }

  /**
   * POI 의 XSSF 시트 핸들러는 오류 셀({@code <c t="e">})을 "ERROR:#DIV/0!" 문자열로만 넘겨, 같은 글자를 입력한 문자열 셀과 구별할 수
   * 없다. 셀 시작 요소의 {@code t} 속성을 먼저 보고 행 집계기에 오류 셀임을 알려, 검증 단계가 거부할 수 있는 표식으로 바꾸게 한다(WD-252).
   */
  private static final class ErrorAwareSheetXmlHandler extends XSSFSheetXMLHandler {
    private final RowAggregatingHandler rows;

    ErrorAwareSheetXmlHandler(
        StylesTable styles,
        ReadOnlySharedStringsTable strings,
        RowAggregatingHandler rows,
        LegacyExcelDataFormatter formatter) {
      super(styles, strings, rows, formatter, false);
      this.rows = rows;
    }

    @Override
    public void startElement(
        String uri, String localName, String qName, org.xml.sax.Attributes attributes)
        throws SAXException {
      if ("c".equals(localName)) {
        rows.currentCellIsXssfError = "e".equals(attributes.getValue("t"));
      }
      super.startElement(uri, localName, qName, attributes);
    }
  }

  // ---------------------------------------------------------------------
  // XLSB — XSSFBReader + XSSFBSheetHandler 바이너리 레코드 파서
  // ---------------------------------------------------------------------

  /**
   * XLSB는 시트가 XML이 아닌 바이너리 레코드(BIFF12)로 저장된다. POI의 {@code org.apache.poi.xssf.binary} 패키지가 제공하는 저수준
   * 레코드 파서를 사용하며, {@link XSSFBSheetHandler}는 XLSX와 동일한 {@link SheetContentsHandler} 인터페이스를 받으므로
   * {@link RowAggregatingHandler}를 그대로 재사용한다.
   */
  private static void parseXlsb(OPCPackage pkg, RowConsumer consumer) throws Exception {
    XSSFBReader reader = new XSSFBReader(pkg);
    XSSFBSharedStringsTable strings = new XSSFBSharedStringsTable(pkg);
    XSSFBStylesTable styles = reader.getXSSFBStylesTable();

    LegacyExcelDataFormatter formatter = new LegacyExcelDataFormatter(readXlsbDate1904(reader));

    var sheets = reader.getSheetsData();
    if (!sheets.hasNext()) return;
    try (InputStream sheetStream = sheets.next()) {
      RowAggregatingHandler handler = new RowAggregatingHandler(consumer);
      XSSFBSheetHandler sheetHandler =
          new ErrorAwareXlsbSheetHandler(sheetStream, styles, strings, handler, formatter);
      try {
        sheetHandler.parse();
      } catch (EarlyStopException ignore) {
        // 정상 조기 종료
      }
    }
  }

  /** BIFF12 워크북 속성 레코드(BrtWbProp) 번호. POI 가 정의해 두지 않아 직접 둔다. */
  private static final int XLSB_BRT_WB_PROP = 153;

  /** XLSB 오류 셀 레코드(BrtCellError)·오류 결과 수식 레코드(BrtFmlaError) 번호. */
  private static final int XLSB_BRT_CELL_ERROR = 3;

  private static final int XLSB_BRT_FMLA_ERROR = 11;

  /** 셀 레코드 앞의 셀 머리(열 4바이트 + 스타일·플래그 4바이트) 길이. 오류 코드는 그 바로 뒤 1바이트다. */
  private static final int XLSB_CELL_HEADER_LENGTH = 8;

  /**
   * XLSB 워크북의 BrtWbProp 레코드 첫 4바이트 플래그 중 최하위 비트(f1904)로 1904 날짜 체계인지 판정한다. POI 가 이 레코드를 해석하지 않아
   * 바이너리 레코드 파서로 직접 읽는다.
   */
  private static boolean readXlsbDate1904(XSSFBReader reader) throws Exception {
    final boolean[] date1904 = {false};
    try (InputStream wb = reader.getWorkbookData()) {
      new org.apache.poi.xssf.binary.XSSFBParser(wb) {
        @Override
        public void handleRecord(int recordType, byte[] data) {
          if (recordType == XLSB_BRT_WB_PROP && data.length >= 4) {
            date1904[0] = (org.apache.poi.util.LittleEndian.getInt(data, 0) & 1) != 0;
            throw EarlyStopException.INSTANCE;
          }
        }
      }.parse();
    } catch (EarlyStopException ignore) {
      // 필요한 플래그를 읽었다
    } catch (Exception e) {
      // 워크북 속성은 보조 정보다 — 읽지 못해도 시트 읽기를 막지 않고 예전 동작(1900 체계)으로 둔다.
      log.warn("XLSB workbook property read failed, assuming 1900 date system: {}", e.toString());
      return false;
    }
    return date1904[0];
  }

  /**
   * POI 의 XLSB 시트 핸들러는 오류 셀을 코드 없이 "ERROR" 로만 넘긴다. 레코드에서 오류 코드를 먼저 읽어 행 집계기에 표식 값으로 넘긴다(WD-252).
   */
  private static final class ErrorAwareXlsbSheetHandler extends XSSFBSheetHandler {
    private final RowAggregatingHandler rows;

    ErrorAwareXlsbSheetHandler(
        InputStream sheetStream,
        XSSFBStylesTable styles,
        XSSFBSharedStringsTable strings,
        RowAggregatingHandler rows,
        LegacyExcelDataFormatter formatter) {
      super(sheetStream, styles, null, strings, rows, formatter, false);
      this.rows = rows;
    }

    @Override
    public void handleRecord(int id, byte[] data)
        throws org.apache.poi.xssf.binary.XSSFBParseException {
      if ((id == XLSB_BRT_CELL_ERROR || id == XLSB_BRT_FMLA_ERROR)
          && data.length > XLSB_CELL_HEADER_LENGTH) {
        rows.overrideNextCell =
            LegacyExcelDataFormatter.errorCell(errorCodeString(data[XLSB_CELL_HEADER_LENGTH]));
        try {
          super.handleRecord(id, data);
        } finally {
          rows.overrideNextCell = null;
        }
        return;
      }
      super.handleRecord(id, data);
    }
  }

  /** 엑셀 오류 코드 바이트를 표시 문자열(#DIV/0! 등)로 바꾼다. 알 수 없는 코드면 null(표식만 남는다). */
  private static String errorCodeString(int code) {
    try {
      return org.apache.poi.ss.usermodel.FormulaError.forInt(code).getString();
    } catch (IllegalArgumentException e) {
      return null;
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

    /** XSSF: 지금 읽는 셀이 오류 셀({@code t="e"})인지. 셀 시작 요소마다 {@link ErrorAwareSheetXmlHandler} 가 갱신한다. */
    boolean currentCellIsXssfError;

    /** XLSB: 다음 셀 값 대신 쓸 오류 셀 표식. {@link ErrorAwareXlsbSheetHandler} 가 오류 레코드 처리 동안만 설정한다. */
    String overrideNextCell;

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
      String value = formattedValue;
      if (overrideNextCell != null) {
        value = overrideNextCell;
      } else if (currentCellIsXssfError && formattedValue != null) {
        // 엑셀 오류 셀 — 검증 단계가 「엑셀 오류 값은 가져올 수 없습니다」로 거부하도록 표식으로 바꾼다.
        value = LegacyExcelDataFormatter.errorCellFromXssf(formattedValue);
      }
      currentCellIsXssfError = false;
      currentRow.add(value == null ? "" : value);
    }
  }

  // ---------------------------------------------------------------------
  // XLS (BIFF) — POIFSFileSystem + HSSFEventFactory
  // ---------------------------------------------------------------------

  private static void parseXls(File file, RowConsumer consumer) throws Exception {
    // readOnly=true — 파싱 전용이므로 파일 잠금/쓰기 권한이 필요 없다(읽기 전용 마운트에서도 안전).
    try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
      // 암호가 걸린 .xlsx 는 OLE2 컨테이너(EncryptedPackage) 안에 들어 있어 이 경로로 온다. HSSF 는 이를 「Workbook 항목 없음」으로만
      // 실패시키므로, 사용자에게 「암호 해제」 안내를 줄 수 있도록 먼저 판별한다(WD-204).
      if (fs.getRoot().hasEntryCaseInsensitive(Decryptor.DEFAULT_POIFS_ENTRY)) {
        throw new EncryptedDocumentException("Encrypted OOXML package in OLE2 container");
      }
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
    // 1904 여부는 워크북 전역 레코드(DateWindow1904Record)가 시트보다 먼저 와서 알려 준다.
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
        case org.apache.poi.hssf.record.DateWindow1904Record.sid:
          // 1904 날짜 체계 워크북(맥 엑셀 옛 기본값) — 날짜가 1,462일 앞당겨지지 않게 formatter 에 알린다(WD-252).
          formatter.setUse1904Windowing(
              ((org.apache.poi.hssf.record.DateWindow1904Record) record).getWindowing() == 1);
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
            // 오류 셀(#DIV/0! 등)은 예전에 빈 값으로 바뀌어 조용히 사라졌다 — 검증이 거부할 표식으로 넘긴다(WD-252).
            appendCell(
                r.getColumn(),
                r.isBoolean()
                    ? String.valueOf(r.getBooleanValue())
                    : LegacyExcelDataFormatter.errorCell(errorCodeString(r.getErrorValue())));
            break;
          }
        case org.apache.poi.hssf.record.FormulaRecord.sid:
          {
            org.apache.poi.hssf.record.FormulaRecord r =
                (org.apache.poi.hssf.record.FormulaRecord) record;
            // 캐시된 결과 종류로 나눈다. 문자열·불리언·오류 결과는 getValue() 가 0.0 이라 숫자로 읽으면 "0" 이 저장된다.
            org.apache.poi.ss.usermodel.CellType resultType = r.getCachedResultTypeEnum();
            if (resultType == org.apache.poi.ss.usermodel.CellType.STRING) {
              // 문자열 결과 — 다음 StringRecord 대기, placeholder 추가
              appendCell(r.getColumn(), "");
            } else if (resultType == org.apache.poi.ss.usermodel.CellType.BOOLEAN) {
              appendCell(r.getColumn(), String.valueOf(r.getCachedBooleanValue()));
            } else if (resultType == org.apache.poi.ss.usermodel.CellType.ERROR) {
              appendCell(
                  r.getColumn(),
                  LegacyExcelDataFormatter.errorCell(errorCodeString(r.getCachedErrorValue())));
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
