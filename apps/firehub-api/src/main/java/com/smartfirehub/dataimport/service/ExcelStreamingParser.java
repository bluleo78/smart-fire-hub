package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ExcelSheetInfo;
import com.smartfirehub.dataimport.exception.ExcelSheetSelectionException;
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
import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.binary.XSSFBRecordType;
import org.apache.poi.xssf.binary.XSSFBSharedStringsTable;
import org.apache.poi.xssf.binary.XSSFBSheetHandler;
import org.apache.poi.xssf.binary.XSSFBStylesTable;
import org.apache.poi.xssf.binary.XSSFBUtils;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFBReader;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STSheetState;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorkbookDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * XLSX/XLS/XLSB 파일을 이벤트 기반으로 파싱한다.
 *
 * <p>워크북 전체 DOM을 메모리에 적재하지 않고 한 행씩 {@link RowConsumer}로 흘려보낸다. RowConsumer가 false를 반환하면 즉시 파싱을
 * 중단한다(early-exit).
 *
 * <p>시트 한 장만 읽는다(WD-253). 시트 이름을 주지 않으면 탭 순서에서 <b>첫 번째 보이는</b> 워크시트를 읽는다 — 숨김·매우 숨김 시트와 차트 시트는
 * 건너뛴다. 예전에는 숨긴 시트라도 워크북 맨 앞 시트를 읽어, 화면에 보이지도 않는 데이터가 적재됐다. 이름을 주면 그 시트(숨긴 시트 포함)를 읽고, 없으면 {@link
 * ExcelSheetSelectionException} 으로 실패한다. 시트 목록·선택 규칙은 {@link #describeSheets} 와 파싱이 같은 {@link
 * #resolveSheetIndex} 를 써서, 미리보기가 알려 준 시트와 실제로 읽는 시트가 어긋나지 않는다.
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
   * 포맷을 자동 감지하여 XLSX/XLSB 또는 XLS 시트 한 장을 읽는다. {@code sheetName} 이 null 이면 첫 번째 보이는 시트.
   *
   * <p><b>Thin wrapper:</b> {@link OPCPackage#open(InputStream)}은 ZIP의 각 엔트리를 전부 메모리 byte[]로 버퍼링하기
   * 때문에(특히 엔트리 크기를 미리 알 수 없는 data-descriptor zip에서는 POI temp-file 임계값 설정도 무력화됨) 대용량 파일에서 OOM/파싱 실패를
   * 유발한다. 이를 피하기 위해 InputStream을 임시 파일로 1회 복사한 뒤 실제 파싱은 랜덤액세스가 가능한 {@link #parse(File, String,
   * RowConsumer)}에 위임하고, 종료 시 임시 파일을 즉시 삭제한다.
   */
  public static void parse(InputStream in, String sheetName, RowConsumer consumer)
      throws Exception {
    File temp = File.createTempFile("excel-streaming-", ".tmp");
    // try-with-resources로 전달받은 스트림을 복사 후 닫는다. 기존 구현은 OPCPackage.open(in)/
    // POIFSFileSystem(in)이 close 시 내부적으로 스트림을 닫아줬으므로, 그 닫기 계약을 그대로 유지해
    // 파일 디스크립터 누수를 막는다(close는 멱등이라 호출자가 다시 닫아도 안전).
    try (in) {
      Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
      parse(temp, sheetName, consumer);
    } finally {
      Files.deleteIfExists(temp.toPath());
    }
  }

  /**
   * 파일에서 직접 랜덤액세스로 열어 시트 한 장을 읽는 core 진입점. {@code sheetName} 이 null 이면 첫 번째 보이는 시트(클래스 설명 참고).
   *
   * <p>{@link FileMagic#valueOf(File)}로 포맷을 감지한 뒤, OOXML(XLSX/XLSB)은 {@link OPCPackage#open(File,
   * PackageAccess)}로, OLE2(XLS)는 {@link POIFSFileSystem#POIFSFileSystem(File)}로 연다. 두 API 모두
   * ZIP/OLE2 컨테이너를 파일 채널 기반 랜덤액세스로 읽어 엔트리를 전량 메모리에 적재하지 않고 필요한 부분만 온디맨드로 읽으므로, {@code InputStream}
   * 기반 오픈에서 발생하던 대용량 파일 OOM을 근본적으로 회피한다.
   *
   * <p>읽을 시트는 여기서 한 번만 정한다(워크북 머리의 시트 목록 → {@link #resolveSheetIndex}). 형식별 리더는 정해진 시트만 받는다.
   */
  public static void parse(File file, String sheetName, RowConsumer consumer) throws Exception {
    // 콜백 예외에 표식을 달아 파일 파싱 실패와 구별한다(ConsumerFailure 참고).
    RowConsumer guarded =
        (idx, cells) -> {
          try {
            return consumer.accept(idx, cells);
          } catch (RuntimeException e) {
            throw new ConsumerFailure(e);
          }
        };
    guardExcel(
        file,
        () -> {
          try (OpenWorkbook workbook = openWorkbook(file, sheetName)) {
            int idx = resolveSheetIndex(workbook.sheets(), sheetName);
            if (idx >= 0) {
              workbook.read(workbook.sheets().get(idx), guarded);
            }
          }
          return null;
        });
  }

  /**
   * 워크북의 시트 목록과, {@code sheetName} 으로 실제로 읽게 될 시트 (WD-253).
   *
   * @param sheets 워크시트 목록(탭 순서, 숨김 여부 포함). 차트 시트 등 행이 없는 시트는 뺀다. 시트가 없는 형식(CSV)은 null
   * @param selectedName 파싱이 읽을 시트 이름. 워크시트가 하나도 없거나 CSV 면 null
   */
  public record SheetSelection(List<ExcelSheetInfo> sheets, String selectedName) {
    /** 시트 개념이 없는 파일(CSV)용 — 미리보기 응답의 sheets/sheetName 이 둘 다 null 이 된다. */
    public static final SheetSelection NONE = new SheetSelection(null, null);
  }

  /**
   * 시트 목록을 읽고 {@code sheetName} 으로 읽을 시트를 정한다 — 미리보기 응답의 sheets/sheetName 용 (WD-253).
   *
   * <p>행을 읽지 않고 워크북 머리(xlsx 의 workbook.xml, xlsb 의 workbook.bin, xls 의 전역 레코드)만 읽어 싸다. 선택 규칙은 파싱과
   * 같은 {@link #resolveSheetIndex} 라, 이 결과가 곧 파싱이 읽을 시트다. 지정한 시트가 없거나 보이는 시트가 없으면 {@link
   * ExcelSheetSelectionException}.
   */
  public static SheetSelection describeSheets(File file, String sheetName) throws Exception {
    return guardExcel(
        file,
        () -> {
          try (OpenWorkbook workbook = openWorkbook(file, sheetName)) {
            List<SheetEntry> sheets = workbook.sheets();
            int idx = resolveSheetIndex(sheets, sheetName);
            return new SheetSelection(
                sheets.stream().map(SheetEntry::info).toList(),
                idx < 0 ? null : sheets.get(idx).name());
          }
        });
  }

  /**
   * 읽을 시트의 위치를 정한다 (WD-253) — 시트 목록·파싱 모두 이 규칙 하나만 쓴다.
   *
   * <ul>
   *   <li>{@code requested} 가 null: 첫 번째 숨기지 않은 시트. 워크시트가 아예 없으면 -1(읽을 행 없음, 예전과 같다). 모두 숨겼으면 예외
   *   <li>이름 지정: 같은 이름 → 없으면 요청 이름과 시트 이름 <b>둘 다</b> 앞뒤 공백을 빼고 대소문자 무시 비교(엑셀 시트 이름은 대소문자를 구별하지 않는다)
   *       → 그래도 없으면 예외. 느슨한 비교에 여러 시트가 걸리면(「Data」·「Data 」) 어느 쪽인지 단정할 수 없어 예외로 정확한 이름을 다시 받는다 — 엉뚱한
   *       시트를 조용히 적재하지 않기 위해서다.
   * </ul>
   */
  static int resolveSheetIndex(List<SheetEntry> sheets, String requested) {
    if (requested == null) {
      if (sheets.isEmpty()) return -1;
      for (int i = 0; i < sheets.size(); i++) {
        if (!sheets.get(i).hidden()) return i;
      }
      throw ExcelSheetSelectionException.noVisibleSheet(infos(sheets));
    }
    for (int i = 0; i < sheets.size(); i++) {
      if (sheets.get(i).name().equals(requested)) return i;
    }
    String trimmed = requested.trim();
    int found = -1;
    for (int i = 0; i < sheets.size(); i++) {
      if (sheets.get(i).name().trim().equalsIgnoreCase(trimmed)) {
        if (found >= 0) throw ExcelSheetSelectionException.ambiguous(requested, infos(sheets));
        found = i;
      }
    }
    if (found >= 0) return found;
    throw ExcelSheetSelectionException.notFound(requested, infos(sheets));
  }

  /** 오류 문구용으로 시트 위치 목록을 화면 정보로 바꾼다. */
  private static List<ExcelSheetInfo> infos(List<SheetEntry> sheets) {
    return sheets.stream().map(SheetEntry::info).toList();
  }

  /** 엑셀 처리 본문 — checked 예외를 그대로 던질 수 있게 한다. */
  @FunctionalInterface
  private interface ExcelCall<T> {
    T call() throws Exception;
  }

  /**
   * 엑셀 처리 중 난 예외를 사용자에게 보일 예외로 정리한다 — 파싱·시트 목록 읽기가 같은 규칙을 쓴다.
   *
   * <ol>
   *   <li>콜백이 던진 예외는 원래 예외 그대로 돌려준다(SAX 가 감쌌을 수 있어 원인 체인을 따라간다).
   *   <li>시트 선택 오류(WD-253)는 이미 사용자용 한국어 문구라 그대로 둔다 — 아래 4 의 「파일을 읽을 수 없습니다」로 바뀌면 안 된다.
   *   <li>파일 자체가 사라진 경우(업로드 중단 등)는 형식 문제가 아니므로 기존 예외를 유지한다 — 임포트 잡의 사용자 메시지 변환이 「파일 업로드가 중간에
   *       실패했습니다」로 따로 안내한다.
   *   <li>그 밖의 모든 예외(알 수 없는 포맷·손상된 OOXML·빈 파일·구형 XLS·레코드 손상 등)는 파일을 읽지 못한 것이다. POI 원문(영문·내부 구조 노출)은
   *       로그에만 남기고 사용자에게는 한국어 안내만 보낸다.
   * </ol>
   */
  private static <T> T guardExcel(File file, ExcelCall<T> call) throws Exception {
    try {
      return call.call();
    } catch (Exception e) {
      ConsumerFailure consumerFailure = findCause(e, ConsumerFailure.class);
      if (consumerFailure != null) {
        throw consumerFailure.original;
      }
      ExcelSheetSelectionException sheetError = findCause(e, ExcelSheetSelectionException.class);
      if (sheetError != null) {
        throw sheetError;
      }
      if (!file.exists()) {
        throw e;
      }
      log.warn("Excel parse failed: {}", e.toString(), e);
      String userMessage =
          findCause(e, EncryptedDocumentException.class) != null
              ? FileUnreadableException.EXCEL_ENCRYPTED
              : FileUnreadableException.EXCEL_UNREADABLE;
      throw new FileUnreadableException(userMessage, e);
    }
  }

  /** 원인 체인(SAXException 의 내장 예외 포함)에서 주어진 타입의 첫 예외를 찾는다. 없으면 null. */
  private static <E extends Throwable> E findCause(Throwable t, Class<E> type) {
    for (Throwable cur = t; cur != null; cur = nextCause(cur)) {
      if (type.isInstance(cur)) {
        return type.cast(cur);
      }
    }
    return null;
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
  // 워크북 열기 — 형식마다 시트 목록을 읽고, 고른 시트 한 장을 읽는 방법을 돌려준다
  // ---------------------------------------------------------------------

  /**
   * 워크북 안 시트 한 장의 위치 (WD-253).
   *
   * @param relId OOXML: 워크북 파트에서 시트 파트로 가는 관계 ID(r:id). XLS 는 null
   * @param substreamIndex XLS: 파일 안 시트 서브스트림의 순번(BOF 위치 순). OOXML 은 -1
   */
  record SheetEntry(String name, boolean hidden, String relId, int substreamIndex) {
    /** 응답·오류 문구에 싣는 화면 정보. */
    ExcelSheetInfo info() {
      return new ExcelSheetInfo(name, hidden);
    }
  }

  /** 열린 워크북 — 워크시트 목록(탭 순서)과, 그중 한 장을 읽는 방법. */
  private interface OpenWorkbook extends AutoCloseable {
    List<SheetEntry> sheets();

    void read(SheetEntry sheet, RowConsumer consumer) throws Exception;

    @Override
    void close() throws IOException;
  }

  /**
   * 포맷을 감지해 워크북을 열고 시트 목록을 읽는다. 행은 아직 읽지 않는다.
   *
   * <p>XLSX 와 XLSB 는 둘 다 ZIP 기반 OOXML 컨테이너라 {@link FileMagic} 만으로는 구분할 수 없어 워크북 파트의 content-type 으로
   * 판별한다. {@code PackageAccess.READ} 로 열어 ZipFile 랜덤액세스(중앙 디렉터리 기반 온디맨드 읽기)를 사용한다 — 엔트리 크기가 ZIP 중앙
   * 디렉터리에서 항상 확정적으로 조회되므로, data-descriptor(사이즈 미상) 엔트리에서 무력화되는 temp-file 임계값 설정과 달리 항상 안전하게 스트리밍된다.
   *
   * @param sheetName 요청한 시트 — 시트 목록이 깨진 xlsb 의 대응이 달라진다({@link #readXlsbWorkbook})
   */
  private static OpenWorkbook openWorkbook(File file, String sheetName) throws Exception {
    FileMagic magic = FileMagic.valueOf(file);
    switch (magic) {
      case OOXML -> {
        OPCPackage pkg;
        try {
          pkg = OPCPackage.open(file, PackageAccess.READ);
        } catch (OpenXML4JException e) {
          throw new IOException("Failed to open XLSX/XLSB package", e);
        }
        try {
          boolean xlsb =
              !pkg.getPartsByContentType(XSSFRelation.XLSB_BINARY_WORKBOOK.getContentType())
                  .isEmpty();
          WorkbookReader reader = new WorkbookReader(pkg);
          WorkbookInfo info = xlsb ? readXlsbWorkbook(reader, sheetName) : readXlsxWorkbook(reader);
          return new OoxmlWorkbook(pkg, reader, xlsb, info);
        } catch (Exception e) {
          pkg.revert();
          throw e;
        }
      }
      case OLE2 -> {
        List<SheetEntry> sheets = listXlsSheetEntries(file);
        return new OpenWorkbook() {
          @Override
          public List<SheetEntry> sheets() {
            return sheets;
          }

          @Override
          public void read(SheetEntry sheet, RowConsumer consumer) throws Exception {
            parseXls(file, sheet.substreamIndex(), consumer);
          }

          @Override
          public void close() {
            // 시트를 읽을 때마다 파일을 열고 닫는다 — 여기서 닫을 것이 없다
          }
        };
      }
      default -> throw new IOException("Unsupported Excel format: " + magic);
    }
  }

  /** XLSX·XLSB 워크북 — 열린 패키지와 시트 목록. 고른 시트는 관계 ID(r:id)로 연다. */
  private record OoxmlWorkbook(
      OPCPackage pkg, WorkbookReader reader, boolean xlsb, WorkbookInfo info)
      implements OpenWorkbook {
    @Override
    public List<SheetEntry> sheets() {
      return info.sheets();
    }

    @Override
    public void read(SheetEntry sheet, RowConsumer consumer) throws Exception {
      try (InputStream sheetStream = reader.getSheet(sheet.relId())) {
        if (xlsb) {
          parseXlsbSheet(pkg, reader, sheetStream, info.date1904(), consumer);
        } else {
          parseXlsxSheet(pkg, reader, sheetStream, info.date1904(), consumer);
        }
      }
    }

    @Override
    public void close() throws IOException {
      pkg.close();
    }
  }

  /**
   * XLSX·XLSB 공용 POI 리더. POI 가 protected 로만 두는 워크북 파트의 관계를 조회하려고 하위 클래스로 둔다.
   *
   * <p>XSSFBReader 를 상속하는 이유: XSSFBReader 는 XSSFReader 의 하위라 XLSX 에도 그대로 쓸 수 있고(워크북 데이터·관계 ID 로 시트
   * 열기·스타일은 XSSFReader 의 것을 쓴다), XLSB 전용 기능(바이너리 스타일, 깨진 시트 목록의 첫 시트를 찾는 바이너리 시트 반복자)도 함께 갖는다. 한
   * 클래스로 두 형식을 다뤄 리더 클래스를 형식마다 따로 두지 않는다. XLSX 에는 {@link #getSheetsData()}(바이너리 반복자)를 쓰지 않는다.
   */
  private static final class WorkbookReader extends XSSFBReader {
    WorkbookReader(OPCPackage pkg) throws IOException, OpenXML4JException {
      super(pkg);
    }

    /**
     * 시트 관계 ID 가 워크시트를 가리키는지 본다. 차트 시트·매크로 시트·대화 상자 시트는 읽을 행이 없어 목록과 기본 선택에서 뺀다 — 맨 앞 보이는 시트가 차트
     * 시트여도 그 뒤의 데이터 시트를 읽게 한다.
     */
    boolean isWorksheet(String relId) {
      PackageRelationship rel = workbookPart.getRelationship(relId);
      return rel != null && rel.getRelationshipType().endsWith("/worksheet");
    }

    /** 워크북 파트에서 주어진 시트 파트로 가는 관계 ID. 없으면 null. */
    String relIdOf(PackagePart sheetPart) throws Exception {
      for (PackageRelationship rel : workbookPart.getRelationships()) {
        if (rel.getTargetMode() == org.apache.poi.openxml4j.opc.TargetMode.INTERNAL
            && org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName(rel.getTargetURI())
                .equals(sheetPart.getPartName())) {
          return rel.getId();
        }
      }
      return null;
    }
  }

  // ---------------------------------------------------------------------
  // XLSX — XSSFReader + XSSFSheetXMLHandler SAX
  // ---------------------------------------------------------------------

  /** XLSX 시트 한 장을 SAX 로 읽어 행 단위로 흘려보낸다. */
  private static void parseXlsxSheet(
      OPCPackage pkg,
      WorkbookReader reader,
      InputStream sheetStream,
      boolean date1904,
      RowConsumer consumer)
      throws Exception {
    ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
    StylesTable styles = reader.getStylesTable();
    // 1904 날짜 체계 워크북이면 날짜 셀이 1,462일 앞당겨지지 않도록 formatter 에 알린다(WD-252).
    LegacyExcelDataFormatter formatter = new LegacyExcelDataFormatter(date1904);

    RowAggregatingHandler handler = new RowAggregatingHandler(consumer);
    XMLReader xmlReader = newXmlReader();
    xmlReader.setContentHandler(new ErrorAwareSheetXmlHandler(styles, strings, handler, formatter));
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

  /**
   * 워크북 머리(xlsx 의 workbook.xml, xlsb 의 workbook.bin)에서 읽은 정보.
   *
   * @param date1904 1904 날짜 체계 여부
   * @param sheets 워크시트 목록(탭 순서)
   */
  record WorkbookInfo(boolean date1904, List<SheetEntry> sheets) {}

  /**
   * workbook.xml 에서 1904 날짜 체계 여부({@code <workbookPr date1904>})와 시트 목록({@code <sheet name state
   * r:id>})을 읽는다. POI 의 시트 반복자는 시트의 숨김 상태(hidden·veryHidden)를 알려 주지 않아 XMLBeans 로 직접 읽는다(WD-253).
   * r:id 가 없거나 워크시트가 아닌 시트(차트 시트 등)는 뺀다.
   */
  private static WorkbookInfo readXlsxWorkbook(WorkbookReader reader) throws Exception {
    CTWorkbook workbook;
    try (InputStream wb = reader.getWorkbookData()) {
      workbook =
          WorkbookDocument.Factory.parse(wb, POIXMLTypeLoader.DEFAULT_XML_OPTIONS).getWorkbook();
    }
    boolean date1904 = workbook.isSetWorkbookPr() && workbook.getWorkbookPr().getDate1904();
    List<SheetEntry> sheets = new ArrayList<>();
    if (workbook.getSheets() != null) {
      for (CTSheet sheet : workbook.getSheets().getSheetArray()) {
        String relId = sheet.getId();
        if (sheet.getName() == null
            || relId == null
            || relId.isEmpty()
            || !reader.isWorksheet(relId)) {
          continue;
        }
        boolean hidden =
            sheet.getState() == STSheetState.HIDDEN || sheet.getState() == STSheetState.VERY_HIDDEN;
        sheets.add(new SheetEntry(sheet.getName(), hidden, relId, -1));
      }
    }
    return new WorkbookInfo(date1904, sheets);
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

    // 결과 없는 수식 셀 감지(WD-380) — POI 는 수식 셀의 캐시 값(<v>)만 읽어, 값이 없으면 셀을 내보내지 않거나 빈 값으로 내보낸다.
    /** 지금 읽는 셀의 참조(A1 형식). */
    private String cellRef;

    /** 지금 읽는 셀에 수식(<f>)이 있는지. */
    private boolean cellHasFormula;

    /** 지금 읽는 셀이 문자열 결과 수식({@code t="str"})인지 — 빈 문자열 결과(=IF(…,"",…))는 엑셀도 빈 <v/> 로 쓴다. */
    private boolean cellIsStringFormula;

    /** 지금 읽는 셀에 결과 값 요소(<v>·<is>)가 있는지. */
    private boolean cellHasResultElement;

    /** 지금 읽는 셀에 비어 있지 않은 결과 값이 있는지. */
    private boolean cellHasResult;

    /** 결과 값 요소(<v>·<is>) 안을 읽는 중인지. */
    private boolean inResult;

    @Override
    public void startElement(
        String uri, String localName, String qName, org.xml.sax.Attributes attributes)
        throws SAXException {
      if ("c".equals(localName)) {
        rows.currentCellIsXssfError = "e".equals(attributes.getValue("t"));
        cellRef = attributes.getValue("r");
        cellIsStringFormula = "str".equals(attributes.getValue("t"));
        cellHasFormula = false;
        cellHasResultElement = false;
        cellHasResult = false;
      } else if ("f".equals(localName)) {
        cellHasFormula = true;
      } else if ("v".equals(localName) || "is".equals(localName)) {
        inResult = true;
        cellHasResultElement = true;
      }
      super.startElement(uri, localName, qName, attributes);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      if (inResult && length > 0) cellHasResult = true;
      super.characters(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      super.endElement(uri, localName, qName);
      if ("v".equals(localName) || "is".equals(localName)) {
        inResult = false;
      } else if ("c".equals(localName) && cellHasFormula && isMissingFormulaResult()) {
        rows.markNoFormulaResult(cellRef);
      }
    }

    /**
     * 수식 셀에 결과가 없는지. <v> 가 아예 없거나(손으로 만든 파일 등), 빈 <v/> 인데 문자열 결과 수식이 아니면(openpyxl 은 t 없이 빈 <v/> 를
     * 쓴다) 결과가 없는 것이다. 문자열 결과 수식의 빈 <v/> 는 엑셀이 빈 문자열 결과를 저장한 모양이라 정상 값으로 둔다.
     */
    private boolean isMissingFormulaResult() {
      if (!cellHasResultElement) return true;
      return !cellHasResult && !cellIsStringFormula;
    }
  }

  // ---------------------------------------------------------------------
  // XLSB — XSSFBReader + XSSFBSheetHandler 바이너리 레코드 파서
  // ---------------------------------------------------------------------

  /**
   * XLSB 시트 한 장을 읽는다. XLSB는 시트가 XML이 아닌 바이너리 레코드(BIFF12)로 저장된다. POI의 {@code
   * org.apache.poi.xssf.binary} 패키지가 제공하는 저수준 레코드 파서를 사용하며, {@link XSSFBSheetHandler}는 XLSX와 동일한
   * {@link SheetContentsHandler} 인터페이스를 받으므로 {@link RowAggregatingHandler}를 그대로 재사용한다.
   */
  private static void parseXlsbSheet(
      OPCPackage pkg,
      WorkbookReader reader,
      InputStream sheetStream,
      boolean date1904,
      RowConsumer consumer)
      throws Exception {
    XSSFBSharedStringsTable strings = new XSSFBSharedStringsTable(pkg);
    XSSFBStylesTable styles = reader.getXSSFBStylesTable();
    LegacyExcelDataFormatter formatter = new LegacyExcelDataFormatter(date1904);
    RowAggregatingHandler handler = new RowAggregatingHandler(consumer);
    XSSFBSheetHandler sheetHandler =
        new ErrorAwareXlsbSheetHandler(sheetStream, styles, strings, handler, formatter);
    try {
      sheetHandler.parse();
    } catch (EarlyStopException ignore) {
      // 정상 조기 종료
    }
  }

  /** BIFF12 워크북 속성 레코드(BrtWbProp) 번호. POI 가 정의해 두지 않아 직접 둔다. */
  private static final int XLSB_BRT_WB_PROP = 153;

  /** 시트 목록 끝 레코드(BrtEndBundleShs) 번호. POI 가 정의해 두지 않아 직접 둔다. */
  private static final int XLSB_BRT_END_BUNDLE_SHS = 144;

  /** XLSB 오류 셀 레코드(BrtCellError)·오류 결과 수식 레코드(BrtFmlaError) 번호. */
  private static final int XLSB_BRT_CELL_ERROR = 3;

  private static final int XLSB_BRT_FMLA_ERROR = 11;

  /** 셀 레코드 앞의 셀 머리(열 4바이트 + 스타일·플래그 4바이트) 길이. 오류 코드는 그 바로 뒤 1바이트다. */
  private static final int XLSB_CELL_HEADER_LENGTH = 8;

  /**
   * XLSB 워크북 정보를 읽는다. workbook.bin 이 깨졌으면(BrtBundleSh·BrtWbProp 손상, 옛 베타 엑셀 형식 등) 경고를 남기고:
   *
   * <ul>
   *   <li>이 기능 전의 동작 그대로 POI 시트 반복자의 첫 시트 한 장을 담은 정보를 돌려준다. 예전에 가져와지던 파일이 계속 가져와지게 하려는 것이다.
   *       첫 시트 한 장만 알려 주므로 화면은 고를 수 없는 「가져올 시트」 한 줄이 된다. 날짜 체계는 깨지기 전에 읽은 BrtWbProp 값을 쓴다(못 읽었으면
   *       1900).
   *   <li>시트를 지정했으면 그 이름이 이 첫 시트의 이름과 정확히 같을 때만 그 시트를 쓴다 — 미리보기가 돌려준 시트 이름을
   *       화면·AI 가 검증·가져오기에 다시 보내기 때문이다(PR #792 리뷰). 다른 이름은 목록을 몰라 찾을 수 없으므로 한국어 시트 선택 오류로 끝낸다.
   * </ul>
   */
  private static WorkbookInfo readXlsbWorkbook(WorkbookReader reader, String sheetName)
      throws Exception {
    try (InputStream wb = reader.getWorkbookData()) {
      return readXlsbWorkbookRecords(wb, reader::isWorksheet);
    } catch (IOException | RuntimeException e) {
      log.warn("XLSB workbook records unreadable, falling back to first sheet: {}", e.toString());
      boolean date1904 = e instanceof XlsbWorkbookUnreadableException u && u.date1904();
      SheetEntry first;
      try {
        first = firstSheetByPoi(reader);
      } catch (Exception poiFailure) {
        poiFailure.addSuppressed(e); // 처음 깨진 이유(workbook.bin)를 함께 남긴다
        throw poiFailure;
      }
      // 정확히 같은 이름만 받는다 — 느슨한 비교는 보이지 않는 다른 시트(「Data」 대 「Data 」)를 대신 읽을 수 있다. 미리보기는 이 이름을 그대로 돌려준다.
      if (sheetName != null && (first == null || !first.name().equals(sheetName))) {
        throw ExcelSheetSelectionException.sheetListUnreadable(sheetName);
      }
      return new WorkbookInfo(date1904, first == null ? List.of() : List.of(first));
    }
  }

  /** POI 시트 반복자가 주는 첫 시트(숨김 여부는 알 수 없어 보이는 시트로 둔다). 시트가 없으면 null. */
  private static SheetEntry firstSheetByPoi(WorkbookReader reader) throws Exception {
    XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
    if (!sheets.hasNext()) return null;
    try (InputStream ignored = sheets.next()) {
      return new SheetEntry(
          sheets.getSheetName(), false, reader.relIdOf(sheets.getSheetPart()), -1);
    }
  }

  /**
   * workbook.bin 레코드를 읽다 깨졌다. 그 전에 읽은 1904 날짜 체계 여부를 함께 담아, 대체 경로가 날짜를 1,462일 당기지 않게 한다(BrtWbProp 는
   * 시트 목록보다 앞에 온다).
   */
  static final class XlsbWorkbookUnreadableException extends IOException {
    private final boolean date1904;

    XlsbWorkbookUnreadableException(boolean date1904, Throwable cause) {
      super("XLSB workbook records unreadable", cause);
      this.date1904 = date1904;
    }

    boolean date1904() {
      return date1904;
    }
  }

  /**
   * workbook.bin 레코드를 훑어 1904 날짜 체계 여부와 시트 목록을 읽는다. 시트 목록 끝(BrtEndBundleShs)에서 멈춘다.
   *
   * <ul>
   *   <li>BrtWbProp: 첫 4바이트 플래그의 최하위 비트(f1904)가 1904 날짜 체계다. POI 가 이 레코드를 해석하지 않아 직접 읽는다.
   *   <li>BrtBundleSh: hsState(4바이트, 0 보임·1 숨김·2 매우 숨김) · iTabID(4바이트) ·
   *       strRelID(XLNullableWideString) · strName(XLWideString). POI 의 시트 반복자는 hsState 를 버려 숨김 여부를
   *       알 수 없으므로 직접 읽는다(WD-253).
   * </ul>
   *
   * @param isWorksheet 관계 ID 가 워크시트를 가리키는지(차트 시트 등을 뺀다). 테스트는 항상 true 를 넘길 수 있다
   */
  static WorkbookInfo readXlsbWorkbookRecords(
      InputStream workbookBin, java.util.function.Predicate<String> isWorksheet)
      throws IOException {
    final boolean[] date1904 = {false};
    final List<SheetEntry> sheets = new ArrayList<>();
    try {
      new org.apache.poi.xssf.binary.XSSFBParser(workbookBin) {
        @Override
        public void handleRecord(int recordType, byte[] data)
            throws org.apache.poi.xssf.binary.XSSFBParseException {
          if (recordType == XLSB_BRT_WB_PROP && data.length >= 4) {
            date1904[0] = (org.apache.poi.util.LittleEndian.getInt(data, 0) & 1) != 0;
          } else if (recordType == XSSFBRecordType.BrtBundleSh.getId()) {
            SheetEntry entry = readXlsbBundleSheet(data);
            if (entry != null && isWorksheet.test(entry.relId())) {
              sheets.add(entry);
            }
          } else if (recordType == XLSB_BRT_END_BUNDLE_SHS) {
            throw EarlyStopException.INSTANCE; // 시트 목록을 다 읽었다
          }
        }
      }.parse();
    } catch (EarlyStopException ignore) {
      // 필요한 정보를 읽었다
    } catch (IOException | RuntimeException e) {
      throw new XlsbWorkbookUnreadableException(date1904[0], e);
    }
    return new WorkbookInfo(date1904[0], sheets);
  }

  /** BrtBundleSh 의 XLNullableWideString 이 null 임을 나타내는 글자 수 값. */
  private static final long XLSB_NULL_WIDE_STRING = 0xFFFFFFFFL;

  /**
   * BrtBundleSh 레코드 한 개를 읽는다(필드 배치는 {@link #readXlsbWorkbookRecords} 참고). 관계 ID 가 null·빈 값이면 열 수 없는
   * 시트라 null.
   */
  private static SheetEntry readXlsbBundleSheet(byte[] data)
      throws org.apache.poi.xssf.binary.XSSFBParseException {
    final int relIdOffset = 8; // hsState(4) + iTabID(4) 뒤
    if (data.length < relIdOffset + 4) {
      throw new org.apache.poi.xssf.binary.XSSFBParseException("BrtBundleSh too short");
    }
    // POI 의 공개 읽기 함수는 null 문자열(글자 수 0xFFFFFFFF)을 다루지 않아 먼저 거른다
    if (org.apache.poi.util.LittleEndian.getUInt(data, relIdOffset) == XLSB_NULL_WIDE_STRING) {
      return null;
    }
    StringBuilder relId = new StringBuilder();
    int offset = relIdOffset + XSSFBUtils.readXLWideString(data, relIdOffset, relId);
    StringBuilder name = new StringBuilder();
    XSSFBUtils.readXLWideString(data, offset, name);
    if (relId.isEmpty()) return null;
    long hsState = org.apache.poi.util.LittleEndian.getUInt(data, 0) & 0x3;
    return new SheetEntry(name.toString(), hsState != 0, relId.toString(), -1);
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

    /**
     * 결과 없는 수식 셀을 표식으로 둔다(WD-380). POI 가 그 칸을 빈 값으로 이미 내보냈으면 그 자리를 바꾸고, 내보내지 않았으면 그 열에 새로 넣는다 — 검증이
     * 「수식 결과가 저장되지 않은 셀」로 행·칸과 함께 거부한다.
     */
    void markNoFormulaResult(String cellReference) {
      if (currentRow == null) return;
      int colIdx =
          cellReference == null ? currentRow.size() : new CellAddress(cellReference).getColumn();
      String marker = LegacyExcelDataFormatter.noFormulaResultCell();
      if (colIdx < currentRow.size()) {
        currentRow.set(colIdx, marker);
        return;
      }
      while (currentRow.size() < colIdx) {
        currentRow.add("");
      }
      currentRow.add(marker);
    }
  }

  // ---------------------------------------------------------------------
  // XLS (BIFF) — POIFSFileSystem + HSSFEventFactory
  // ---------------------------------------------------------------------

  /**
   * XLS 시트 한 장을 읽는다.
   *
   * @param targetSubstream 읽을 시트의 서브스트림 순번({@link SheetEntry#substreamIndex()})
   */
  private static void parseXls(File file, int targetSubstream, RowConsumer consumer)
      throws Exception {
    // readOnly=true — 파싱 전용이므로 파일 잠금/쓰기 권한이 필요 없다(읽기 전용 마운트에서도 안전).
    try (POIFSFileSystem fs = openXls(file)) {
      org.apache.poi.hssf.eventusermodel.HSSFEventFactory factory =
          new org.apache.poi.hssf.eventusermodel.HSSFEventFactory();
      org.apache.poi.hssf.eventusermodel.HSSFRequest request =
          new org.apache.poi.hssf.eventusermodel.HSSFRequest();

      XlsListener xls = new XlsListener(consumer, targetSubstream);
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
   * XLS(OLE2) 파일을 연다. 암호가 걸린 .xlsx 는 OLE2 컨테이너(EncryptedPackage) 안에 들어 있어 이 경로로 온다. HSSF 는 이를
   * 「Workbook 항목 없음」으로만 실패시키므로, 사용자에게 「암호 해제」 안내를 줄 수 있도록 먼저 판별한다(WD-204).
   */
  private static POIFSFileSystem openXls(File file) throws IOException {
    POIFSFileSystem fs = new POIFSFileSystem(file, true);
    if (fs.getRoot().hasEntryCaseInsensitive(Decryptor.DEFAULT_POIFS_ENTRY)) {
      fs.close();
      throw new EncryptedDocumentException("Encrypted OOXML package in OLE2 container");
    }
    return fs;
  }

  /**
   * XLS 의 워크시트 목록을 읽는다(WD-253). 시트 정의(BoundSheetRecord)는 모두 워크북 전역 영역에 있으므로 전역 영역 끝(첫 EOF)에서 멈춘다 —
   * 셀 레코드는 읽지 않는다.
   */
  private static List<SheetEntry> listXlsSheetEntries(File file) throws IOException {
    try (POIFSFileSystem fs = openXls(file)) {
      List<org.apache.poi.hssf.record.BoundSheetRecord> bound = new ArrayList<>();
      org.apache.poi.hssf.eventusermodel.HSSFRequest request =
          new org.apache.poi.hssf.eventusermodel.HSSFRequest();
      request.addListenerForAllRecords(
          record -> {
            if (record instanceof org.apache.poi.hssf.record.BoundSheetRecord b) {
              bound.add(b);
            } else if (record.getSid() == org.apache.poi.hssf.record.EOFRecord.sid) {
              throw XlsListener.EarlyStopRuntimeException.INSTANCE; // 전역 영역 끝
            }
          });
      try {
        new org.apache.poi.hssf.eventusermodel.HSSFEventFactory()
            .processWorkbookEvents(request, fs);
      } catch (XlsListener.EarlyStopRuntimeException ignore) {
        // 시트 정의를 다 읽었다
      }
      return xlsSheetEntries(bound);
    }
  }

  /**
   * BoundSheetRecord 목록(탭 순서)을 워크시트 목록으로 바꾼다.
   *
   * <p>각 시트의 {@code substreamIndex} 는 파일 안 시트 서브스트림 순번이다. 서브스트림은 BOF 위치 순으로 놓이고 탭 순서와 다를 수 있어
   * {@link org.apache.poi.hssf.record.BoundSheetRecord#orderByBofPosition} 의 순번을 쓴다. 차트·매크로·VB 모듈
   * 시트도 서브스트림을 차지하므로 순번은 전체 시트 기준으로 매기고, 목록에는 워크시트만 남긴다.
   */
  private static List<SheetEntry> xlsSheetEntries(
      List<org.apache.poi.hssf.record.BoundSheetRecord> bound) {
    org.apache.poi.hssf.record.BoundSheetRecord[] byBof =
        org.apache.poi.hssf.record.BoundSheetRecord.orderByBofPosition(bound);
    List<SheetEntry> entries = new ArrayList<>();
    for (org.apache.poi.hssf.record.BoundSheetRecord b : bound) {
      if (!isXlsWorksheet(b)) continue;
      int substream = -1;
      for (int i = 0; i < byBof.length; i++) {
        if (byBof[i] == b) substream = i;
      }
      entries.add(
          new SheetEntry(b.getSheetname(), b.isHidden() || b.isVeryHidden(), null, substream));
    }
    return entries;
  }

  /**
   * 시트 종류(dt)가 워크시트(0)인지 본다. POI 가 종류 getter 를 두지 않아 직렬화한 레코드에서 읽는다 — 머리 4바이트(sid·길이) 뒤 BOF 위치
   * 4바이트, 그 뒤 옵션 2바이트의 상위 바이트가 dt 다(1 매크로·2 차트·6 VB 모듈).
   */
  private static boolean isXlsWorksheet(org.apache.poi.hssf.record.BoundSheetRecord b) {
    byte[] raw = b.serialize();
    return raw.length > 9 && raw[9] == 0;
  }

  /**
   * HSSF 이벤트 리스너. 고른 시트 한 장만 처리하며 LastCellOfRowDummyRecord에서 RowConsumer를 호출한다.
   *
   * <p>시트 고르기(WD-253): 읽을 시트의 서브스트림 순번을 받아, 전역 영역 다음의 최상위 서브스트림(BOF~EOF)을 순서대로 세어 그 시트의 서브스트림에서만 셀을
   * 모은다. 워크시트 안에 든 차트도 BOF~EOF 서브스트림을 갖기 때문에 깊이를 세어 최상위만 센다. 고른 시트의 EOF 에서 바로 멈춘다(뒤 시트는 읽지 않는다).
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

    /** BOF/EOF 깊이. 0 = 서브스트림 밖, 1 = 최상위 서브스트림 안, 2 이상 = 워크시트 안의 차트 등. */
    private int depth = 0;

    /** 전역 영역 다음부터 센 최상위 시트 서브스트림 순번(-1 = 아직 없음). */
    private int substreamIndex = -1;

    /** 읽을 시트의 서브스트림 순번. */
    private final int targetSubstream;

    /** 지금 고른 시트의 최상위 서브스트림 안에 있는지. */
    private boolean inTarget = false;

    private List<String> currentRow;
    private int currentRowIndex = -1;

    /** Early-exit 신호용 RuntimeException sentinel. HSSFEventFactory processRecord에서 던진다. */
    static final class EarlyStopRuntimeException extends RuntimeException {
      static final EarlyStopRuntimeException INSTANCE = new EarlyStopRuntimeException();
    }

    XlsListener(RowConsumer consumer, int targetSubstream) {
      this.consumer = consumer;
      this.targetSubstream = targetSubstream;
    }

    void setFormatTracker(org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener tracker) {
      this.formatTracker = tracker;
    }

    @Override
    public void processRecord(org.apache.poi.hssf.record.Record record) {
      switch (record.getSid()) {
        case org.apache.poi.hssf.record.BOFRecord.sid:
          org.apache.poi.hssf.record.BOFRecord bof = (org.apache.poi.hssf.record.BOFRecord) record;
          if (depth == 0 && bof.getType() != org.apache.poi.hssf.record.BOFRecord.TYPE_WORKBOOK) {
            // 최상위 시트 서브스트림 시작
            substreamIndex++;
            inTarget = substreamIndex == targetSubstream;
          }
          depth++;
          return;
        case org.apache.poi.hssf.record.EOFRecord.sid:
          depth = Math.max(0, depth - 1);
          if (depth == 0 && inTarget) {
            throw EarlyStopRuntimeException.INSTANCE; // 고른 시트를 다 읽었다
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

      // 고른 시트의 최상위 서브스트림 안(그 안의 차트 서브스트림 제외)에서만 셀을 모은다
      if (!inTarget || depth != 1) return;

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
