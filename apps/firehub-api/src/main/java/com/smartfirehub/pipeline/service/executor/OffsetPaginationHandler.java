package com.smartfirehub.pipeline.service.executor;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OffsetPaginationHandler {

    /** Safety cap: stop after fetching this many pages regardless of totalCount. */
    private static final int MAX_PAGES = 10_000;

    /**
     * Decides whether there is a next page to fetch.
     *
     * @param currentOffset  the offset used for the page just fetched
     * @param pageSize       configured page size
     * @param totalCount     total row count from API, or null if not available
     * @param currentPageRows number of rows returned in the latest page
     * @return true if another page should be fetched
     */
    public boolean hasNextPage(int currentOffset, int pageSize, Integer totalCount, int currentPageRows) {
        // Safety: never fetch beyond MAX_PAGES pages
        if (currentOffset >= (long) MAX_PAGES * pageSize) {
            return false;
        }

        if (totalCount != null) {
            return currentOffset + pageSize < totalCount;
        }

        // Unknown total: stop when we received a partial (or empty) page
        return currentPageRows >= pageSize;
    }

    /**
     * Returns the offset to use for the next page.
     */
    public int getNextOffset(int currentOffset, int pageSize) {
        return currentOffset + pageSize;
    }

    /**
     * Builds the query-parameter map for a paginated request.
     *
     * @param offsetParam query param name for the offset (e.g. "offset")
     * @param limitParam  query param name for the page size (e.g. "limit")
     * @param offset      current offset value
     * @param pageSize    page size value
     * @return map with pagination query parameters
     */
    public Map<String, String> buildPaginationParams(
            String offsetParam,
            String limitParam,
            int offset,
            int pageSize) {

        Map<String, String> params = new LinkedHashMap<>();
        if (offsetParam != null && !offsetParam.isBlank()) {
            params.put(offsetParam, String.valueOf(offset));
        }
        if (limitParam != null && !limitParam.isBlank()) {
            params.put(limitParam, String.valueOf(pageSize));
        }
        return params;
    }
}
