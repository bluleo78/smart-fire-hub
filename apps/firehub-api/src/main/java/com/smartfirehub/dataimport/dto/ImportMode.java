package com.smartfirehub.dataimport.dto;

public enum ImportMode {
    APPEND,   // Current default: insert all rows
    UPSERT,   // Insert or update based on PK
    REPLACE   // Truncate then insert
}
