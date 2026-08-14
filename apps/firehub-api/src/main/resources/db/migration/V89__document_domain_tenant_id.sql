-- V89: document 도메인에 tenant_id 를 추가한다 (정책은 V90).
--
-- document_file 은 dataset 에서, document_chunk 는 document_file 에서 백필한다(둘 다 NOT NULL FK).
-- 순서가 중요하다 — document_chunk 는 document_file 이 채워진 뒤에 읽어야 한다.
-- DEFAULT 는 백필 뒤에 붙인다(Flyway 는 GUC 없이 도는 소유자 롤이라 먼저 붙이면 NOT NULL 위반).

ALTER TABLE document_file ADD COLUMN tenant_id BIGINT;
UPDATE document_file c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE document_file ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE document_file ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_file
  ADD CONSTRAINT fk_document_file_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_document_file_tenant ON document_file(tenant_id);

ALTER TABLE document_chunk ADD COLUMN tenant_id BIGINT;
UPDATE document_chunk c SET tenant_id = p.tenant_id
  FROM document_file p WHERE c.document_file_id = p.id;
ALTER TABLE document_chunk ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE document_chunk ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_chunk
  ADD CONSTRAINT fk_document_chunk_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_document_chunk_tenant ON document_chunk(tenant_id);

-- uq_document_file_dataset_checksum(dataset_id, checksum) 은 이미 dataset 스코프라
-- 크로스테넌트 충돌이 불가능하다 — 변경하지 않는다.
