import type { ClassifyProvider, ClassifyProviderOptions } from './types.js';
import type { ClassifyResponse } from '../services/classification-service.js';
import { classifyBatch } from '../services/classification-service.js';
import { DEFAULT_MODEL } from '../constants.js';

/**
 * 분류 프로바이더.
 *
 * 자격증명은 요청 바디로 흘러온 값을 그대로 전달한다 — ai-agent 가 firehub-api 를 역호출해
 * 스스로 키를 조회하던 경로는 제거되었다(ADMIN 전용 권한 문제 + OAuth 미지원).
 */
export class ClaudeClassifyProvider implements ClassifyProvider {
  readonly name = 'claude-classify';

  async classify(options: ClassifyProviderOptions): Promise<ClassifyResponse> {
    return classifyBatch(
      { rows: options.rows, prompt: options.prompt, outputColumns: options.outputColumns },
      {
        apiKey: options.apiKey,
        oauthToken: options.oauthToken,
        // agentType/baseUrl/providerId 는 opencode 테넌트에만 의미가 있다. 여기서 누락하면
        // classifyBatch → callClassifyCompletion → createCompletionProvider 로 흘러가며 사라져
        // OpenAI 호환 분기가 아니라 Claude SDK 로 떨어진다(Task 8 이 고치는 회귀 그 자체).
        agentType: options.agentType,
        baseUrl: options.baseUrl,
        providerId: options.providerId,
        reasoningEffort: options.reasoningEffort,
      },
      options.model || DEFAULT_MODEL,
    );
  }
}
