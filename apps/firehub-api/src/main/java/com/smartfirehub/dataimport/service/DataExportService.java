package com.smartfirehub.dataimport.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportColumnInfo;
import com.smartfirehub.dataimport.dto.ExportEstimate;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataimport.service.export.CsvExportWriter;
import com.smartfirehub.dataimport.service.export.ExcelExportWriter;
import com.smartfirehub.dataimport.service.export.ExportWriter;
import com.smartfirehub.dataimport.service.export.GeoJsonExportWriter;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.job.service.AsyncJobService;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class DataExportService {

  private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

  static final int SYNC_THRESHOLD = 50_000;
  private static final int PAGE_SIZE = 1000;
  static final Path EXPORT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "firehub-exports");

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableRowService dataTableRowService;
  private final AsyncJobService asyncJobService;
  private final AsyncJobRepository asyncJobRepository;
  private final AuditLogService auditLogService;

  public DataExportService(
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      DataTableRowService dataTableRowService,
      AsyncJobService asyncJobService,
      AsyncJobRepository asyncJobRepository,
      AuditLogService auditLogService) {
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.dataTableRowService = dataTableRowService;
    this.asyncJobService = asyncJobService;
    this.asyncJobRepository = asyncJobRepository;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public ExportEstimate estimateExport(Long datasetId, ExportRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
    List<DatasetColumnResponse> selectedColumns = resolveColumns(allColumns, request);
    Map<String, String> columnTypes = buildColumnTypes(allColumns);

    List<String> columnNames =
        selectedColumns.stream().map(DatasetColumnResponse::columnName).toList();
    long rowCount =
        dataTableRowService.countRows(
            dataset.tableName(), columnNames, request.search(), columnTypes);

    boolean hasGeometry =
        allColumns.stream().anyMatch(col -> "GEOMETRY".equalsIgnoreCase(col.dataType()));

    List<ExportColumnInfo> columnInfos =
        allColumns.stream()
            .map(
                col ->
                    new ExportColumnInfo(
                        col.columnName(),
                        col.displayName(),
                        col.dataType(),
                        "GEOMETRY".equalsIgnoreCase(col.dataType())))
            .toList();

    return new ExportEstimate(rowCount, rowCount > SYNC_THRESHOLD, hasGeometry, columnInfos);
  }

  @Transactional(readOnly = true)
  public ExportResult exportDataset(
      Long datasetId,
      ExportRequest request,
      Long userId,
      String username,
      String ipAddress,
      String userAgent) {

    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
    List<DatasetColumnResponse> selectedColumns = resolveColumns(allColumns, request);
    Map<String, String> columnTypes = buildColumnTypes(allColumns);

    // GeoJSON geometry column resolution
    String geometryColumn = null;
    if (request.format() == ExportFormat.GEOJSON) {
      geometryColumn = resolveGeometryColumnForExport(selectedColumns, request);
    }

    String filename = buildFilename(dataset.name(), request.format());

    List<String> columnNames =
        selectedColumns.stream().map(DatasetColumnResponse::columnName).toList();
    long rowCount =
        dataTableRowService.countRows(
            dataset.tableName(), columnNames, request.search(), columnTypes);

    if (rowCount > SYNC_THRESHOLD) {
      // Async
      checkRateLimit(userId);

      String jobId =
          asyncJobService.createJob(
              "DATA_EXPORT",
              "dataset",
              String.valueOf(datasetId),
              userId,
              Map.of("format", request.format().name(), "filename", filename));

      executeAsyncExport(
          jobId,
          dataset,
          selectedColumns,
          columnTypes,
          request.search(),
          request.format(),
          geometryColumn,
          filename,
          userId,
          username,
          ipAddress,
          userAgent);

      return ExportResult.async(jobId);
    } else {
      // Sync
      StreamingResponseBody body =
          createSyncExport(
              dataset,
              selectedColumns,
              columnTypes,
              request.search(),
              request.format(),
              geometryColumn);

      auditLogService.log(
          userId,
          username,
          "DATA_EXPORT",
          "dataset",
          String.valueOf(datasetId),
          request.format().name() + " 내보내기 완료 (" + rowCount + "행)",
          ipAddress,
          userAgent,
          "SUCCESS",
          null,
          Map.of("format", request.format().name(), "rowCount", rowCount));

      return ExportResult.sync(body, filename, request.format().getContentType());
    }
  }

  public Path getExportFile(String jobId, Long userId) {
    AsyncJobStatusResponse status =
        asyncJobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    if (!status.userId().equals(userId)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Access denied: not the owner of job " + jobId);
    }
    if (!"COMPLETED".equals(status.stage())) {
      throw new IllegalStateException("Job is not completed: " + status.stage());
    }
    String filePath = (String) status.metadata().get("filePath");
    if (filePath == null) {
      throw new IllegalStateException("Export file path not found in job metadata");
    }
    Path path = Path.of(filePath);
    if (!Files.exists(path)) {
      throw new IllegalStateException("Export file not found (may have been cleaned up)");
    }
    return path;
  }

  public StreamingResponseBody exportQueryResult(
      List<String> columnNames, List<Map<String, Object>> rows, ExportFormat format) {
    if (format == ExportFormat.GEOJSON) {
      throw new IllegalArgumentException("쿼리 결과는 GeoJSON 형식으로 내보낼 수 없습니다.");
    }
    return outputStream -> {
      try (ExportWriter writer = createWriter(format, outputStream, null)) {
        writer.writeHeader(columnNames);
        for (Map<String, Object> row : rows) {
          String[] values = new String[columnNames.size()];
          for (int i = 0; i < columnNames.size(); i++) {
            Object val = row.get(columnNames.get(i));
            values[i] = val != null ? val.toString() : "";
          }
          writer.writeRow(values);
        }
      }
    };
  }

  // --- Async export ---

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

    Path filePath = EXPORT_DIR.resolve(jobId + "." + format.getExtension());
    try {
      Files.createDirectories(EXPORT_DIR);

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

  // --- Sync export ---

  private StreamingResponseBody createSyncExport(
      DatasetResponse dataset,
      List<DatasetColumnResponse> selectedColumns,
      Map<String, String> columnTypes,
      String search,
      ExportFormat format,
      String geometryColumn) {

    return outputStream -> {
      try (ExportWriter writer = createWriter(format, outputStream, geometryColumn)) {
        writeHeader(writer, selectedColumns, format);

        List<String> columnNames =
            selectedColumns.stream().map(DatasetColumnResponse::columnName).toList();
        int page = 0;
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
          page++;
        }
      }
    };
  }

  // --- Helpers ---

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

  private ExportWriter createWriter(ExportFormat format, OutputStream os, String geometryColumn)
      throws IOException {
    return switch (format) {
      case CSV -> new CsvExportWriter(os);
      case EXCEL -> new ExcelExportWriter(os);
      case GEOJSON -> new GeoJsonExportWriter(os, geometryColumn);
    };
  }

  private List<DatasetColumnResponse> resolveColumns(
      List<DatasetColumnResponse> allColumns, ExportRequest request) {
    if (request.columns() == null || request.columns().isEmpty()) {
      return allColumns;
    }
    Set<String> selected = new LinkedHashSet<>(request.columns());
    return allColumns.stream().filter(col -> selected.contains(col.columnName())).toList();
  }

  private String resolveGeometryColumnForExport(
      List<DatasetColumnResponse> columns, ExportRequest request) {
    Map<String, String> types = new HashMap<>();
    for (var col : columns) types.put(col.columnName(), col.dataType());
    return DataTableRowService.resolveGeometryColumn(columns, types, request.geometryColumn());
  }

  private void checkRateLimit(Long userId) {
    List<AsyncJobStatusResponse> activeExports =
        asyncJobRepository.findActiveByUserAndJobType(userId, "DATA_EXPORT");
    if (activeExports.size() >= 3) {
      throw new IllegalStateException("동시 내보내기 작업이 3개를 초과할 수 없습니다. 기존 작업이 완료된 후 다시 시도하세요.");
    }
  }

  private Map<String, String> buildColumnTypes(List<DatasetColumnResponse> columns) {
    Map<String, String> types = new HashMap<>();
    for (var col : columns) {
      types.put(col.columnName(), col.dataType());
    }
    return types;
  }

  private String buildFilename(String datasetName, ExportFormat format) {
    String sanitized = datasetName.replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return sanitized + "_export_" + date + "." + format.getExtension();
  }
}
