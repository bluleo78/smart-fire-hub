package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ColumnMappingDto;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ColumnMappingService {

    public List<ColumnMappingDto> suggestMappings(List<String> fileHeaders, List<DatasetColumnResponse> datasetColumns) {
        Set<String> usedDatasetColumns = new HashSet<>();
        List<ColumnMappingDto> mappings = new ArrayList<>();

        for (String fileHeader : fileHeaders) {
            ColumnMappingDto mapping = findBestMatch(fileHeader, datasetColumns, usedDatasetColumns);
            mappings.add(mapping);

            if (mapping.datasetColumn() != null) {
                usedDatasetColumns.add(mapping.datasetColumn());
            }
        }

        return mappings;
    }

    private ColumnMappingDto findBestMatch(String fileHeader, List<DatasetColumnResponse> datasetColumns, Set<String> usedColumns) {
        // 1. Exact match on columnName
        for (DatasetColumnResponse col : datasetColumns) {
            if (!usedColumns.contains(col.columnName()) && col.columnName().equals(fileHeader)) {
                return new ColumnMappingDto(fileHeader, col.columnName(), "EXACT", 1.0);
            }
        }

        // 2. Case-insensitive match on columnName
        for (DatasetColumnResponse col : datasetColumns) {
            if (!usedColumns.contains(col.columnName()) && col.columnName().equalsIgnoreCase(fileHeader)) {
                return new ColumnMappingDto(fileHeader, col.columnName(), "CASE_INSENSITIVE", 0.9);
            }
        }

        // 3. Case-insensitive match on displayName
        for (DatasetColumnResponse col : datasetColumns) {
            if (!usedColumns.contains(col.columnName())
                && col.displayName() != null
                && col.displayName().equalsIgnoreCase(fileHeader)) {
                return new ColumnMappingDto(fileHeader, col.columnName(), "DISPLAY_NAME", 0.8);
            }
        }

        // 4. Normalized comparison
        String normalizedHeader = normalize(fileHeader);
        for (DatasetColumnResponse col : datasetColumns) {
            if (!usedColumns.contains(col.columnName())) {
                String normalizedColumnName = normalize(col.columnName());
                if (normalizedColumnName.equals(normalizedHeader)) {
                    return new ColumnMappingDto(fileHeader, col.columnName(), "NORMALIZED", 0.7);
                }

                if (col.displayName() != null) {
                    String normalizedDisplayName = normalize(col.displayName());
                    if (normalizedDisplayName.equals(normalizedHeader)) {
                        return new ColumnMappingDto(fileHeader, col.columnName(), "NORMALIZED", 0.7);
                    }
                }
            }
        }

        // 5. No match
        return new ColumnMappingDto(fileHeader, null, "NONE", 0.0);
    }

    private String normalize(String s) {
        return s.replaceAll("[\\s_\\-]", "").toLowerCase();
    }
}
