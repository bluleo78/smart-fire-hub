package com.smartfirehub.dataset.storage;

/** {@code tenant_data_store} 한 행(WD-73). 비밀번호는 별도 {@link StoredAccount} 로 다룬다. */
public record TenantDataStoreRow(
    long tenantId,
    DataStoreBackend backend,
    String host,
    int port,
    String database,
    String schemaName,
    String tablespace,
    DataStoreProvisionMode mode,
    TenantDataStoreStatus status) {}
