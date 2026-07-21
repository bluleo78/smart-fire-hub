import { describe, it, expect, vi } from 'vitest';
import { loadOntology } from './ontology-source.js';
import { CORE_ONTOLOGY, serializeOntology, buildExtractionPrompt } from './ontology.js';

describe('loadOntology', () => {
  it('fetch 성공 시 wire 온톨로지를 역직렬화해 반환한다(프롬프트 바이트 동일)', async () => {
    const apiClient = { getOntology: vi.fn().mockResolvedValue(serializeOntology(CORE_ONTOLOGY)) };
    const ontology = await loadOntology(apiClient as never);
    expect(buildExtractionPrompt(ontology)).toBe(buildExtractionPrompt(CORE_ONTOLOGY));
  });

  it('fetch 실패 시 번들 CORE_ONTOLOGY 로 폴백한다', async () => {
    const apiClient = { getOntology: vi.fn().mockRejectedValue(new Error('api down')) };
    const ontology = await loadOntology(apiClient as never);
    expect(ontology).toBe(CORE_ONTOLOGY);
  });
});
