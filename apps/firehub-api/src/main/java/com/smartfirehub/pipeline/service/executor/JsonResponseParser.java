package com.smartfirehub.pipeline.service.executor;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JsonResponseParser {

    private static final Logger log = LoggerFactory.getLogger(JsonResponseParser.class);

    /**
     * Parses a JSON response string, extracts an array at the given JSONPath,
     * and maps each element to a row according to the provided field mappings.
     *
     * @param jsonResponse   raw JSON string
     * @param dataPath       JSONPath expression to the data array (e.g. "$.data.items")
     * @param fieldMappings  column mapping rules
     * @return list of mapped rows ready for insertBatch
     * @throws ApiCallException if the dataPath is not found
     */
    public List<Map<String, Object>> parseAndMap(
            String jsonResponse,
            String dataPath,
            List<ApiCallConfig.FieldMapping> fieldMappings) {

        Object extracted;
        try {
            extracted = JsonPath.read(jsonResponse, dataPath);
        } catch (PathNotFoundException e) {
            throw new ApiCallException("Data path not found: " + dataPath, e);
        }

        List<?> rawList;
        if (extracted instanceof List<?> list) {
            rawList = list;
        } else {
            rawList = List.of(extracted);
        }

        List<Map<String, Object>> result = new ArrayList<>(rawList.size());
        int skipped = 0;

        for (Object item : rawList) {
            try {
                Map<String, Object> row = mapItem(item, fieldMappings);
                result.add(row);
            } catch (Exception e) {
                skipped++;
                log.warn("Skipping row due to conversion error: {}", e.getMessage());
            }
        }

        if (skipped > 0) {
            log.warn("Skipped {} rows due to type conversion errors (total extracted: {})", skipped, rawList.size());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapItem(Object item, List<ApiCallConfig.FieldMapping> fieldMappings) {
        Map<String, Object> sourceMap = item instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> row = new LinkedHashMap<>();

        for (ApiCallConfig.FieldMapping mapping : fieldMappings) {
            Object rawValue = sourceMap.get(mapping.sourceField());
            Object converted = convertValue(rawValue, mapping);
            row.put(mapping.targetColumn(), converted);
        }

        return row;
    }

    private Object convertValue(Object rawValue, ApiCallConfig.FieldMapping mapping) {
        if (rawValue == null) {
            return null;
        }

        String dataType = mapping.dataType();
        if (dataType == null) {
            return rawValue.toString();
        }

        return switch (dataType.toUpperCase()) {
            case "TEXT" -> rawValue.toString();
            case "INTEGER" -> toLong(rawValue, mapping);
            case "DECIMAL" -> toBigDecimal(rawValue, mapping);
            case "BOOLEAN" -> toBoolean(rawValue);
            case "DATE" -> toLocalDate(rawValue, mapping);
            case "TIMESTAMP" -> toLocalDateTime(rawValue, mapping);
            default -> rawValue.toString();
        };
    }

    private Long toLong(Object value, ApiCallConfig.FieldMapping mapping) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = value.toString();
        if ("comma_separated".equalsIgnoreCase(mapping.numberFormat())) {
            s = s.replace(",", "");
        }
        return Long.parseLong(s.strip());
    }

    private BigDecimal toBigDecimal(Object value, ApiCallConfig.FieldMapping mapping) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = value.toString();
        if ("comma_separated".equalsIgnoreCase(mapping.numberFormat())) {
            s = s.replace(",", "");
        }
        return new BigDecimal(s.strip());
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private LocalDate toLocalDate(Object value, ApiCallConfig.FieldMapping mapping) {
        String s = value.toString();
        String fmt = mapping.dateFormat();
        if (fmt != null && !fmt.isBlank()) {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(fmt));
        }
        return LocalDate.parse(s);
    }

    private LocalDateTime toLocalDateTime(Object value, ApiCallConfig.FieldMapping mapping) {
        String s = value.toString();
        String fmt = mapping.dateFormat();

        // Determine timezone: field-level override takes priority, then parent
        String tzStr = mapping.sourceTimezone();
        ZoneId zone = (tzStr != null && !tzStr.isBlank()) ? ZoneId.of(tzStr) : ZoneId.of("UTC");

        if (fmt != null && !fmt.isBlank()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
            try {
                // Try parsing as ZonedDateTime first (if format includes zone)
                ZonedDateTime zdt = ZonedDateTime.parse(s, formatter);
                return zdt.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
            } catch (Exception ignored) {
                // Fall through to LocalDateTime parse
            }
            LocalDateTime ldt = LocalDateTime.parse(s, formatter);
            return ZonedDateTime.of(ldt, zone).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
        }

        // No format specified — try ISO formats
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(s);
            return zdt.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
        } catch (Exception ignored) {
            // Fall through
        }
        LocalDateTime ldt = LocalDateTime.parse(s);
        return ZonedDateTime.of(ldt, zone).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    }

    /**
     * Reads a single value from JSON at the given path.
     *
     * @throws ApiCallException if the path is not found
     */
    public <T> T readPath(String json, String path, Class<T> type) {
        try {
            return JsonPath.read(json, path);
        } catch (PathNotFoundException e) {
            throw new ApiCallException("JSON path not found: " + path, e);
        }
    }
}
