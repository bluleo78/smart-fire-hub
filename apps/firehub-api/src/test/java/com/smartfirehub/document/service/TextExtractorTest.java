package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.document.dto.ExtractedText;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class TextExtractorTest {

  private final TextExtractor extractor = new TextExtractor();

  @Test
  void extractsPlainText() {
    byte[] data = "소방 점검 보고서".getBytes(StandardCharsets.UTF_8);
    ExtractedText out = extractor.extract(data, "text/plain", "a.txt");
    assertThat(out.text()).contains("소방 점검");
    assertThat(out.pageCount()).isNull();
  }

  @Test
  void extractsMarkdown() {
    byte[] data = "# 제목\n본문".getBytes(StandardCharsets.UTF_8);
    ExtractedText out = extractor.extract(data, "text/markdown", "a.md");
    assertThat(out.text()).contains("본문");
  }

  @Test
  void extractsPdf() throws Exception {
    byte[] data = makePdf("Fire inspection report");
    ExtractedText out = extractor.extract(data, "application/pdf", "a.pdf");
    assertThat(out.text()).contains("Fire inspection report");
    assertThat(out.pageCount()).isEqualTo(1);
  }

  @Test
  void extractsDocx() throws Exception {
    byte[] data = makeDocx("점검 매뉴얼 본문");
    ExtractedText out =
        extractor.extract(
            data,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "a.docx");
    assertThat(out.text()).contains("점검 매뉴얼");
  }

  @Test
  void rejectsUnsupportedMime() {
    assertThatThrownBy(() -> extractor.extract(new byte[] {1}, "image/png", "a.png"))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  private byte[] makePdf(String text) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 12);
        cs.newLineAtOffset(50, 700);
        cs.showText(text);
        cs.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private byte[] makeDocx(String text) throws Exception {
    try (XWPFDocument doc = new XWPFDocument()) {
      doc.createParagraph().createRun().setText(text);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.write(out);
      return out.toByteArray();
    }
  }
}
