package com.smartfirehub.document.service;

import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.document.dto.ExtractedText;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

/** mime별 문서 텍스트 추출기. Phase 1: PDF / DOCX / TXT / MD. */
@Service
public class TextExtractor {

  /** 파일 바이트와 mime으로 텍스트를 추출한다. 지원하지 않는 포맷은 예외. */
  public ExtractedText extract(byte[] data, String mimeType, String fileName) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase();
    try {
      if (mime.equals("application/pdf")) {
        return extractPdf(data);
      }
      if (mime.equals(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
        return extractDocx(data);
      }
      if (mime.startsWith("text/")) {
        return new ExtractedText(new String(data, StandardCharsets.UTF_8), null);
      }
    } catch (UnsupportedFileTypeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("문서 텍스트 추출 실패: " + fileName, e);
    }
    throw new UnsupportedFileTypeException(
        "지원하지 않는 문서 형식입니다: " + mimeType + " (" + fileName + ")");
  }

  /** PDF 텍스트 추출. 페이지 수도 함께 반환한다. */
  private ExtractedText extractPdf(byte[] data) throws Exception {
    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(data))) {
      String text = new PDFTextStripper().getText(doc);
      return new ExtractedText(text, doc.getNumberOfPages());
    }
  }

  /** DOCX 텍스트 추출. 페이지 개념이 없어 pageCount는 null. */
  private ExtractedText extractDocx(byte[] data) throws Exception {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
        XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
      return new ExtractedText(ex.getText(), null);
    }
  }
}
