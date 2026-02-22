package com.smartfirehub.dataimport.service;

import com.opencsv.CSVWriter;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataExportService {

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableRowService dataTableRowService;

  public DataExportService(
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      DataTableRowService dataTableRowService) {
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.dataTableRowService = dataTableRowService;
  }

  @Transactional(readOnly = true)
  public byte[] exportDatasetCsv(Long datasetId) throws Exception {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

    int pageSize = 1000;
    int page = 0;
    List<Map<String, Object>> allRows = new java.util.ArrayList<>();

    while (true) {
      List<Map<String, Object>> pageRows =
          dataTableRowService.queryData(dataset.tableName(), columnNames, null, page, pageSize);
      if (pageRows.isEmpty()) {
        break;
      }
      allRows.addAll(pageRows);
      page++;

      if (allRows.size() > 100000) {
        break;
      }
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    baos.write(0xEF);
    baos.write(0xBB);
    baos.write(0xBF);

    try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        CSVWriter writer = new CSVWriter(osw)) {

      String[] headers =
          columns.stream()
              .map(
                  col ->
                      col.displayName() != null && !col.displayName().isEmpty()
                          ? col.displayName()
                          : col.columnName())
              .toArray(String[]::new);
      writer.writeNext(headers);

      for (Map<String, Object> row : allRows) {
        String[] rowData = new String[columnNames.size()];
        for (int i = 0; i < columnNames.size(); i++) {
          Object value = row.get(columnNames.get(i));
          rowData[i] = value != null ? value.toString() : "";
        }
        writer.writeNext(rowData);
      }
    }

    return baos.toByteArray();
  }
}
