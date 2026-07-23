package com.smartfirehub.dataimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PoiTempFileConfig}가 해결하는 문제(거대 ZIP 엔트리로 인한 POI 파싱 실패/OOM)를 순수 단위(Spring
 * 컨텍스트 없이)로 재현·검증한다.
 *
 * <p>시나리오: {@code IOUtils.setByteArrayMaxOverride}로 엔트리당 byte[] 상한을 인위적으로 낮춘 뒤, 충분히
 * 큰 시트 XML 엔트리를 가진 xlsx를 만들어 {@code OPCPackage.open}이 (1) temp-file spill 임계값이 비활성
 * (-1, POI 기본값)일 때 실패하고, (2) 임계값이 활성화되어 있으면 성공함을 확인한다.
 *
 * <p><b>중요한 전제(픽스처 재구성 이유)</b>: {@code ZipArchiveFakeEntry}는 ZIP 엔트리의 압축 해제 크기
 * ({@code entry.getSize()})가 임계값 이상일 때만 temp 파일로 spill 한다. 그런데 POI가 자체적으로
 * {@code OutputStream}에 저장한 zip(즉 {@code SXSSFWorkbook.write(OutputStream)}이 만드는 zip)은
 * seek이 불가능한 스트림에 쓰기 때문에 ZIP "data descriptor"(local header size=0, 실제 크기는 엔트리
 * 데이터 뒤에 별도 기록)를 사용한다 — 이 경우 {@code ZipArchiveInputStream.getNextEntry()} 시점에
 * entry.getSize()가 항상 -1(알 수 없음)이 되어 임계값 검사 자체가 무력화된다(threshold 값과 무관하게 항상
 * 메모리 byte[] 경로로 빠진다). 반면 실제 업로드되는 대용량 xlsb/xlsx 파일은 종종(파일을 만든 도구가
 * seekable 출력에 직접 쓰는 경우) local header에 실제 크기가 기록되어 있어 임계값 검사가 정상 동작한다.
 * 이 테스트는 그 "크기가 알려진" 케이스를 재현하기 위해, SXSSFWorkbook이 만든 원본 zip을 풀어서
 * {@code ZipArchiveOutputStream(File)}(seek 가능)로 재압축한 픽스처를 사용한다 — 이렇게 하면 data
 * descriptor 없이 local header에 실제 크기가 기록되어, PoiTempFileConfig가 다루는 시나리오를 충실히
 * 재현할 수 있다.
 *
 * <p>POI의 {@code ZipInputStreamZipEntrySource}/{@code IOUtils} 설정은 static(전역) 상태이므로 각 테스트
 * 종료 시 반드시 원복한다(다른 테스트 오염 방지).
 */
class PoiTempFileConfigTest {

  /** 엔트리당 byte[] 상한을 인위적으로 낮춰 실패를 재현하기 위한 값(1MB). */
  private static final int LOWERED_BYTE_ARRAY_MAX = 1_000_000;

  /** POI의 temp-file spill 비활성 기본값(-1). 0은 "모든 엔트리를 spill"을 의미하므로 비활성과 다르다. */
  private static final int THRESHOLD_DISABLED = -1;

  @AfterEach
  void restoreGlobalPoiState() {
    // 다른 테스트에 영향이 가지 않도록 POI 전역(static) 설정을 기본값으로 원복한다.
    IOUtils.setByteArrayMaxOverride(-1);
    ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(THRESHOLD_DISABLED);
  }

  @Test
  void 임계값_비활성_상태에서는_거대_엔트리_open이_실패한다() throws IOException {
    byte[] xlsxBytes = createLargeSheetXlsxWithKnownEntrySizes();

    // 엔트리당 byte[] 상한을 1MB로 낮춰 거대 엔트리가 상한을 넘도록 만든다.
    IOUtils.setByteArrayMaxOverride(LOWERED_BYTE_ARRAY_MAX);
    // temp-file spill 비활성화(POI 기본값, RED 재현 조건).
    ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(THRESHOLD_DISABLED);

    assertThatThrownBy(() -> openPackage(xlsxBytes)).isInstanceOf(Throwable.class);
  }

  @Test
  void 임계값_활성화_시_동일한_거대_엔트리_open이_성공한다() throws IOException {
    byte[] xlsxBytes = createLargeSheetXlsxWithKnownEntrySizes();

    IOUtils.setByteArrayMaxOverride(LOWERED_BYTE_ARRAY_MAX);
    // PoiTempFileConfig가 적용하는 것과 동일한 임계값(16MB)으로 temp 파일 spill 활성화(GREEN).
    ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(
        PoiTempFileConfig.THRESHOLD_BYTES_FOR_TEMP_FILES);

    assertThatCode(() -> openPackage(xlsxBytes)).doesNotThrowAnyException();
  }

  @Test
  void PoiTempFileConfig_초기화_후_전역_임계값이_16MB로_설정된다() {
    new PoiTempFileConfig().configurePoiTempFileThreshold();

    assertThat(ZipInputStreamZipEntrySource.getThresholdBytesForTempFiles())
        .isEqualTo(PoiTempFileConfig.THRESHOLD_BYTES_FOR_TEMP_FILES);
  }

  /**
   * OPCPackage를 InputStream으로 연다.
   *
   * <p>{@code ZipInputStreamZipEntrySource}의 생성자는 zip의 모든 엔트리를 즉시(eager) 순회하며 각
   * 엔트리를 메모리 byte[] 또는 temp 파일로 읽어들인다({@code ZipArchiveFakeEntry} 생성자). 즉 이 클래스가
   * 재현하려는 상한 초과/spill 여부는 {@code OPCPackage.open(InputStream)} 호출 자체에서 이미 결정되며,
   * 이후 파트를 조회하거나 읽을 필요가 없다.
   */
  private void openPackage(byte[] xlsxBytes)
      throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(xlsxBytes))) {
      // no-op: open() 자체가 모든 엔트리를 읽어들이므로 close까지 성공하면 충분하다.
    }
  }

  /**
   * sheet1.xml 엔트리가 1MB(LOWERED_BYTE_ARRAY_MAX)를 넘도록 충분히 많은 행/열을 가진 xlsx를 SXSSFWorkbook으로
   * 생성한 뒤, ZIP local header에 실제(압축 해제) 크기가 기록되도록 재압축한다.
   *
   * <p>SXSSFWorkbook.write(OutputStream)이 만드는 원본 zip은 data descriptor를 사용해 local header
   * 크기를 0으로 남기므로(클래스 Javadoc 참고), 그 상태로는 임계값 기반 spill이 절대 발동하지 않는다. 이를
   * 실제 대용량 업로드 파일과 동등하게 만들기 위해 원본 zip의 엔트리를 그대로 풀어
   * {@code ZipArchiveOutputStream(File)}(seek 가능한 대상)로 다시 압축한다 — seek 가능한 대상에 쓰면
   * commons-compress가 데이터를 다 쓴 뒤 local header로 되돌아가 실제 크기를 채워 넣으므로 data descriptor가
   * 필요 없어진다.
   */
  private byte[] createLargeSheetXlsxWithKnownEntrySizes() throws IOException {
    byte[] originalZipBytes = writeSxssfWorkbookToBytes();
    return repackageWithKnownEntrySizes(originalZipBytes);
  }

  private byte[] writeSxssfWorkbookToBytes() throws IOException {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
      SXSSFSheet sheet = workbook.createSheet("large");
      for (int row = 0; row < 20_000; row++) {
        var r = sheet.createRow(row);
        for (int col = 0; col < 20; col++) {
          r.createCell(col).setCellValue("value-" + row + "-" + col + "-padding-padding-padding");
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      workbook.dispose();
      return out.toByteArray();
    }
  }

  /** 주어진 zip 바이트의 엔트리들을 읽어, seek 가능한 임시 파일 대상 {@code ZipArchiveOutputStream}으로 재압축한다. */
  private byte[] repackageWithKnownEntrySizes(byte[] originalZipBytes) throws IOException {
    File tempFile = File.createTempFile("poi-temp-file-config-test", ".xlsx");
    tempFile.deleteOnExit();
    try {
      try (ZipArchiveInputStream zis =
              new ZipArchiveInputStream(new ByteArrayInputStream(originalZipBytes));
          ZipArchiveOutputStream zos = new ZipArchiveOutputStream(tempFile)) {
        ZipArchiveEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
          var newEntry = new ZipArchiveEntry(entry.getName());
          zos.putArchiveEntry(newEntry);
          zis.transferTo(zos);
          zos.closeArchiveEntry();
        }
      }
      return Files.readAllBytes(tempFile.toPath());
    } finally {
      tempFile.delete();
    }
  }

  /**
   * 재압축된 픽스처가 실제로 "크기가 알려진" 케이스를 재현하는지 검증한다: commons-compress의
   * {@code ZipArchiveInputStream}(POI가 내부적으로 쓰는 것과 동일한 구현체)으로 sheet1.xml 엔트리를
   * getNextEntry() 하자마자 getSize()가 -1이 아닌 실제 값을 반환해야 한다(= data descriptor 미사용).
   */
  @Test
  void 재압축된_픽스처는_엔트리_크기가_사전에_알려져있다() throws IOException {
    byte[] xlsxBytes = createLargeSheetXlsxWithKnownEntrySizes();

    try (ZipArchiveInputStream zis = new ZipArchiveInputStream(new ByteArrayInputStream(xlsxBytes))) {
      ZipArchiveEntry entry;
      boolean foundLargeEntryWithKnownSize = false;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
          assertThat(entry.getSize())
              .as("재압축 픽스처는 getNextEntry() 시점에 이미 실제 크기를 알고 있어야 threshold 검사가 유효하다")
              .isGreaterThan(LOWERED_BYTE_ARRAY_MAX);
          foundLargeEntryWithKnownSize = true;
        }
      }
      assertThat(foundLargeEntryWithKnownSize).isTrue();
    }
  }
}
