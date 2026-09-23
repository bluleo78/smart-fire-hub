import { describe, expect, it, vi } from 'vitest';
import { completeOrNull } from './complete-or-null.js';
import {
  AUTH_FAILURE_KOREAN_MESSAGE,
  AiCredentialFailureError,
  MissingAiCredentialError,
} from '../agent/ai-auth-failure.js';

/**
 * GraphRAG LLM 호출의 공통 실패 정책(#711) — 네 호출부(extractor/mapping/ontology/semantic-link)가 공유한다.
 */
describe('completeOrNull', () => {
  it('성공하면 응답 텍스트를 그대로 돌려준다', async () => {
    const complete = vi.fn().mockResolvedValue('응답');
    await expect(completeOrNull(complete, 'sys', 'user', 't')).resolves.toBe('응답');
    expect(complete).toHaveBeenCalledWith('sys', 'user');
  });

  it('일반 실패는 경고 후 null(호출부 폴백으로 계속)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const complete = vi.fn().mockRejectedValue(new Error('timeout'));
    await expect(completeOrNull(complete, 'sys', 'user', 't')).resolves.toBeNull();
    warn.mockRestore();
  });

  it('자격증명 실패(인증·결제·자격증명 없음)는 삼키지 않고 전파한다', async () => {
    await expect(
      completeOrNull(vi.fn().mockRejectedValue(new AiCredentialFailureError(AUTH_FAILURE_KOREAN_MESSAGE)), 's', 'u', 't'),
    ).rejects.toThrow(AUTH_FAILURE_KOREAN_MESSAGE);
    await expect(
      completeOrNull(vi.fn().mockRejectedValue(new MissingAiCredentialError()), 's', 'u', 't'),
    ).rejects.toThrow(MissingAiCredentialError);
  });
});
