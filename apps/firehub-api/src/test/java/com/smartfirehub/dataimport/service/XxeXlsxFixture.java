package com.smartfirehub.dataimport.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * XXE 회귀 테스트용 최소 xlsx 를 메모리에서 짠다(WD-615).
 *
 * <p>POI 로 만든 정상 파일에는 DOCTYPE 이 들어갈 수 없어, OPC 필수 파트(콘텐츠 타입·관계·workbook·sheet1)만 직접 써서 공격자가 만든 것과 같은
 * 파일을 재현한다. 셀 값은 인라인 문자열({@code t="inlineStr"})이라 sharedStrings·styles 파트 없이도 읽힌다.
 */
final class XxeXlsxFixture {

  private XxeXlsxFixture() {}

  private static final String SHEET_NS =
      "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";

  /** 헤더 1행(A1="col")·값 1행(A2=cellValue)의 시트. doctype 은 XML 선언 뒤에 그대로 끼운다(빈 문자열이면 정상 시트). */
  static String sheetXml(String doctype, String cellValue) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + doctype
        + "<worksheet "
        + SHEET_NS
        + "><sheetData>"
        + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>col</t></is></c></row>"
        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>"
        + cellValue
        + "</t></is></c></row>"
        + "</sheetData></worksheet>";
  }

  /** 외부 엔티티로 secretFile 내용을 A2 셀에 끌어오려는 시트(전형적인 XXE 파일 탈취 시도). */
  static String externalEntitySheet(Path secretFile) {
    return sheetXml(
        "<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM \"" + secretFile.toUri() + "\">]>", "&xxe;");
  }

  /** 내부 엔티티를 거듭 펼쳐 메모리를 고갈시키려는 시트(billion laughs). */
  static String billionLaughsSheet() {
    StringBuilder dtd = new StringBuilder("<!DOCTYPE worksheet [<!ENTITY lol0 \"lol\">");
    for (int i = 1; i <= 9; i++) {
      dtd.append("<!ENTITY lol")
          .append(i)
          .append(" \"")
          .append(("&lol" + (i - 1) + ";").repeat(10))
          .append("\">");
    }
    dtd.append("]>");
    return sheetXml(dtd.toString(), "&lol9;");
  }

  /** 주어진 sheet1.xml 로 최소 xlsx 바이트를 만든다. */
  static byte[] xlsx(String sheetXml) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      put(
          zip,
          "[Content_Types].xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Default Extension=\"rels\""
              + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
              + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
              + "<Override PartName=\"/xl/workbook.xml\""
              + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
              + "<Override PartName=\"/xl/worksheets/sheet1.xml\""
              + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
              + "</Types>");
      put(
          zip,
          "_rels/.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
              + " Target=\"xl/workbook.xml\"/></Relationships>");
      put(
          zip,
          "xl/workbook.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook "
              + SHEET_NS
              + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
              + "<sheets><sheet name=\"data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      put(
          zip,
          "xl/_rels/workbook.xml.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
              + " Target=\"worksheets/sheet1.xml\"/></Relationships>");
      put(zip, "xl/worksheets/sheet1.xml", sheetXml);
    }
    return bytes.toByteArray();
  }

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
