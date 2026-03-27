import type { ClassifyProvider, ClassifyProviderOptions } from './types.js';
import type { ClassifyResponse } from '../services/classification-service.js';
import { classifyBatch } from '../services/classification-service.js';

export class ClaudeClassifyProvider implements ClassifyProvider {
  readonly name = 'claude-classify';

  constructor(
    private readonly apiBaseUrl: string,
    private readonly internalToken: string,
  ) {}

  async classify(options: ClassifyProviderOptions): Promise<ClassifyResponse> {
    return classifyBatch(
      { rows: options.rows, prompt: options.prompt, outputColumns: options.outputColumns },
      this.apiBaseUrl,
      this.internalToken,
      options.userId,
    );
  }
}
