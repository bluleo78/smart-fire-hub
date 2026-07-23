package com.smartfirehub.dataimport.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource;
import org.springframework.context.annotation.Configuration;

/**
 * POI OOXML(xlsx/xlsb) 파싱 시 ZIP 엔트리를 메모리 byte[] 대신 temp 파일로 spill 하도록 전역 설정한다.
 *
 * <p>배경: {@code OPCPackage.open(InputStream)}은 ZIP의 각 엔트리(시트 XML, sharedStrings 등)를 전부 메모리
 * byte[]로 버퍼링한다. 400MB급 xlsb의 거대 시트 엔트리는 POI의 엔트리당 100MB 상한(RecordFormatException)
 * 또는 힙 한도를 넘어 OOM/파싱 실패를 유발한다. POI 5.3.0의
 * {@link ZipInputStreamZipEntrySource#setThresholdBytesForTempFiles(int)}는 이 임계값(byte)을 넘는 엔트리를
 * 메모리 대신 임시 파일로 저장하도록 전역(static)으로 설정한다.
 *
 * <p><b>현재 상태(Task 4 이후)</b>: {@code ExcelStreamingParser}는 이제 파일 기반 랜덤액세스({@code
 * OPCPackage.open(File, PackageAccess)})로 열기 때문에 {@code ZipInputStreamZipEntrySource}(InputStream 기반
 * ZIP 엔트리 소스) 경로 자체를 타지 않으며, 이 근본 문제(특히 data-descriptor zip에서 임계값이 무력화되는
 * 케이스)를 대체(subsume)한다. 이 설정은 (1) 아직 File 경로로 전환되지 않은 다른 코드가 OPCPackage.open(InputStream)을
 * 직접 호출하는 경우, (2) 엔트리 크기가 사전에 알려진 일반 케이스에 대한 방어적 계층으로만 남겨둔다.
 *
 * <p>주의: 이 설정은 POI 라이브러리 전역(static) 상태이므로 애플리케이션 전체(export 경로의
 * SXSSFWorkbook 쓰기 등)에 영향을 줄 수 있다. 다만 SXSSFWorkbook은 쓰기(export) 전용 경로이며 이 설정은
 * ZIP 읽기(OPCPackage.open) 엔트리 소스에만 적용되므로 export 동작에는 영향이 없다.
 */
@Configuration
public class PoiTempFileConfig {

  /** ZIP 엔트리가 이 크기(byte)를 넘으면 메모리 byte[] 대신 temp 파일로 spill 한다. (16MB) */
  static final int THRESHOLD_BYTES_FOR_TEMP_FILES = 16 * 1024 * 1024;

  /**
   * 애플리케이션 시작 시 POI 전역 설정을 적용한다.
   *
   * <p>{@code setEncryptTempFiles(false)}: 임포트 중 생성되는 temp 파일은 즉시 사용 후 삭제되는 단명
   * 파일이라 암호화 오버헤드가 불필요하므로 성능을 위해 비활성화한다.
   */
  @PostConstruct
  public void configurePoiTempFileThreshold() {
    ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(THRESHOLD_BYTES_FOR_TEMP_FILES);
    ZipInputStreamZipEntrySource.setEncryptTempFiles(false);
  }
}
