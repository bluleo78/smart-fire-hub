package com.smartfirehub.dataimport.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.service.export.CsvExportWriter;
import com.smartfirehub.dataimport.service.export.ExcelExportWriter;
import com.smartfirehub.dataimport.service.export.ExportWriter;
import com.smartfirehub.dataimport.service.export.GeoJsonExportWriter;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.job.service.AsyncJobService;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * DataExportService의 비동기 Export 실행을 담당하는 별도 Spring Bean.
 *
 * <p>같은 클래스 내 자기호출(self-invocation)로는 Spring AOP 프록시를 우회하여 {@code @Async}가 적용되지 않는 문제를 방지하기 위해 별도
 * 빈으로 분리한다. DataExportService가 이 빈을 주입받아 호출함으로써 프록시를 통한 정상적인 비동기 실행이 보장된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataExportAsyncRunner {

  /** 페이지당 조회 행 수 — DataExportService와 동일 값 유지 */
  private static final int PAGE_SIZE = 1000;

  private final DataTableRowService dataTableRowService;
  private final AsyncJobService asyncJobService;
  private final AuditLogService auditLogService;

  /**
   * 대용량 데이터셋을 비동기로 Export한다.
   *
   * <p>이 메서드는 {@code exportExecutor} 스레드풀에서 실행되므로 HTTP 요청 스레드를 블록하지 않는다. 진행 상황은 AsyncJobService를 통해
   * 실시간으로 갱신된다.
   *
   * @param jobId 추적할 비동기 작업 ID
   * @param dataset 내보낼 데이터셋 정보
   * @param selectedColumns 내보낼 컬럼 목록
   * @param columnTypes 컬럼명 → 타입 매핑 (쿼리 필터 처리에 사용)
   * @param search 검색 필터 문자열 (null이면 전체)
   * @param format 내보내기 포맷 (CSV / EXCEL / GEOJSON)
   * @param geometryColumn GeoJSON 내보내기 시 사용할 geometry 컬럼명 (GEOJSON 포맷이 아니면 null)
   * @param filename 다운로드 파일명
   * @param userId 요청 사용자 ID (감사 로그용)
   * @param username 요청 사용자명 (감사 로그용)
   * @param ipAddress 클라이언트 IP (감사 로그용)
   * @param userAgent 클라이언트 User-Agent (감사 로그용)
   */
  @Async("exportExecutor")
  public void executeAsyncExport(
      String jobId,
      DatasetResponse dataset,
      List<DatasetColumnResponse> selectedColumns,
      Map<String, String> columnTypes,
      String search,
      ExportFormat format,
      String geometryColumn,
      String filename,
      Long userId,
      String username,
      String ipAddress,
      String userAgent) {

    Path filePath = DataExportService.EXPORT_DIR.resolve(jobId + "." + format.getExtension());
    try {
      Files.createDirectories(DataExportService.EXPORT_DIR);

      List<String> columnNames =
          selectedColumns.stream().map(DatasetColumnResponse::columnName).toList();
      long totalRows =
          dataTableRowService.countRows(dataset.tableName(), columnNames, search, columnTypes);

      asyncJobService.updateProgress(
          jobId, "EXPORTING", 0, "내보내기 시작: " + totalRows + "행", Map.of("totalRows", totalRows));

      try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(filePath));
          ExportWriter writer = createWriter(format, fos, geometryColumn)) {

        writeHeader(writer, selectedColumns, format);

        int page = 0;
        long processedRows = 0;
        while (true) {
          List<Map<String, Object>> rows =
              dataTableRowService.queryData(
                  dataset.tableName(),
                  columnNames,
                  search,
                  page,
                  PAGE_SIZE,
                  null,
                  "ASC",
                  columnTypes);
          if (rows.isEmpty()) break;

          for (Map<String, Object> row : rows) {
            String[] values = new String[columnNames.size()];
            for (int i = 0; i < columnNames.size(); i++) {
              Object val = row.get(columnNames.get(i));
              values[i] = val != null ? val.toString() : "";
            }
            writer.writeRow(values);
          }

          processedRows += rows.size();
          int progress = totalRows > 0 ? (int) (processedRows * 100 / totalRows) : 0;
          asyncJobService.updateProgress(
              jobId,
              "EXPORTING",
              Math.min(progress, 99),
              processedRows + "/" + totalRows + " 행 처리 중",
              Map.of("processedRows", processedRows, "totalRows", totalRows));
          page++;
        }
      }

      long fileSize = Files.size(filePath);
      asyncJobService.completeJob(
          jobId,
          Map.of(
              "filePath",
              filePath.toString(),
              "filename",
              filename,
              "contentType",
              format.getContentType(),
              "fileSize",
              fileSize));

      auditLogService.log(
          userId,
          username,
          "DATA_EXPORT",
          "dataset",
          String.valueOf(dataset.id()),
          format.name() + " 내보내기 완료 (" + totalRows + "행)",
          ipAddress,
          userAgent,
          "SUCCESS",
          null,
          Map.of("format", format.name(), "rowCount", totalRows, "fileSize", fileSize));

    } catch (Exception e) {
      log.error("Async export failed for jobId={}: {}", jobId, e.getMessage(), e);
      asyncJobService.failJob(jobId, "내보내기 실패: " + e.getMessage());
      try {
        Files.deleteIfExists(filePath);
      } catch (IOException ignored) {
        // 파일 삭제 실패는 무시 (이미 예외 처리 중)
      }

      auditLogService.log(
          userId,
          username,
          "DATA_EXPORT",
          "dataset",
          String.valueOf(dataset.id()),
          format.name() + " 내보내기 실패",
          ipAddress,
          userAgent,
          "FAILURE",
          e.getMessage(),
          null);
    }
  }

  /**
   * ExportFormat에 따라 적절한 ExportWriter를 생성한다.
   *
   * @param format 내보내기 포맷
   * @param os 출력 스트림
   * @param geometryColumn GeoJSON 사용 시 geometry 컬럼명
   */
  private ExportWriter createWriter(ExportFormat format, OutputStream os, String geometryColumn)
      throws IOException {
    return switch (format) {
      case CSV -> new CsvExportWriter(os);
      case EXCEL -> new ExcelExportWriter(os);
      case GEOJSON -> new GeoJsonExportWriter(os, geometryColumn);
    };
  }

  /**
   * 포맷에 따라 헤더 행을 작성한다. GeoJSON은 컬럼명(columnName)을, 그 외 포맷은 표시명(displayName)을 사용한다.
   *
   * @param writer 헤더를 쓸 ExportWriter
   * @param selectedColumns 내보낼 컬럼 목록
   * @param format 내보내기 포맷
   */
  private void writeHeader(
      ExportWriter writer, List<DatasetColumnResponse> selectedColumns, ExportFormat format)
      throws IOException {
    if (format == ExportFormat.GEOJSON) {
      writer.writeHeader(selectedColumns.stream().map(DatasetColumnResponse::columnName).toList());
    } else {
      writer.writeHeader(
          selectedColumns.stream()
              .map(
                  col ->
                      col.displayName() != null && !col.displayName().isEmpty()
                          ? col.displayName()
                          : col.columnName())
              .toList());
    }
  }
}
