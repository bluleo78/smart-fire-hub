import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import type { SSEEvent } from './agent-sdk.js';
import { truncate } from '../utils.js';

export function processMessage(
  msg: SDKMessage,
  tag: () => string,
  hasStreamedText: boolean,
): SSEEvent[] {
  const events: SSEEvent[] = [];

  switch (msg.type) {
    case 'system': {
      if (msg.subtype === 'init') {
        console.log(`${tag()} ● Session init: ${msg.session_id}`);
        events.push({
          type: 'init',
          sessionId: msg.session_id,
        });
      } else {
        console.log(`${tag()} ● System: ${msg.subtype}`);
      }
      break;
    }

    case 'assistant': {
      // Normally text is streamed via stream_event (text_delta), so we skip text blocks
      // here to avoid duplication. However, in error cases (e.g. credit balance too low),
      // the SDK may return text directly in the assistant message without streaming.
      // In that case, emit the text so the frontend can display it.
      if (msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'text' && 'text' in block) {
            console.log(`${tag()} ◀ Text: "${truncate(String(block.text))}"`);
            if (!hasStreamedText) {
              events.push({ type: 'text', content: block.text });
            }
          } else if (block.type === 'tool_use' && 'name' in block) {
            const input = 'input' in block ? block.input : {};
            console.log(`${tag()} ◀ Tool call: ${block.name}(${truncate(JSON.stringify(input))})`);
            events.push({
              type: 'tool_use',
              toolName: block.name,
              input,
            });
          } else {
            console.log(`${tag()} ◀ Assistant block: ${block.type}`);
          }
        }
      }
      break;
    }

    case 'user': {
      if (msg.message?.content && Array.isArray(msg.message.content)) {
        for (const block of msg.message.content) {
          if (typeof block === 'object' && 'type' in block && block.type === 'tool_result') {
            const rawContent = 'content' in block ? block.content : undefined;
            let resultStr: string | undefined;
            if (typeof rawContent === 'string') {
              resultStr = rawContent;
            } else if (Array.isArray(rawContent)) {
              resultStr = rawContent
                .map((c: unknown) =>
                  typeof c === 'object' && c !== null && 'text' in c
                    ? (c as { text: string }).text
                    : JSON.stringify(c),
                )
                .join('\n');
            } else if (rawContent !== undefined) {
              resultStr = JSON.stringify(rawContent);
            }
            const toolId = 'tool_use_id' in block ? String(block.tool_use_id) : 'unknown';
            console.log(`${tag()} ◀ Tool result [${toolId}]: ${truncate(resultStr ?? '(empty)')}`);
            events.push({
              type: 'tool_result',
              toolName: toolId,
              result: resultStr,
            });
          } else {
            const blockType =
              typeof block === 'object' && block !== null && 'type' in block
                ? (block as { type: string }).type
                : 'unknown';
            console.log(`${tag()} ◀ User block: ${blockType}`);
          }
        }
      }
      break;
    }

    case 'result': {
      const resultMsg = msg as {
        usage?: Record<string, number>;
        modelUsage?: Record<
          string,
          {
            inputTokens: number;
            outputTokens: number;
            cacheReadInputTokens: number;
            cacheCreationInputTokens: number;
          }
        >;
      };
      let totalInputTokens = 0;
      if (resultMsg.usage) {
        const u = resultMsg.usage;
        totalInputTokens =
          (u.input_tokens ?? 0) +
          (u.cache_read_input_tokens ?? 0) +
          (u.cache_creation_input_tokens ?? 0);
        console.log(
          `${tag()} 📊 Total tokens — input: ${u.input_tokens ?? 0}, output: ${u.output_tokens ?? 0}, cache_read: ${u.cache_read_input_tokens ?? 0}, cache_create: ${u.cache_creation_input_tokens ?? 0} (total_input: ${totalInputTokens})`,
        );
      }
      if (resultMsg.modelUsage) {
        for (const [modelName, u] of Object.entries(resultMsg.modelUsage)) {
          console.log(
            `${tag()} 📊 Model ${modelName} — input: ${u.inputTokens}, output: ${u.outputTokens}, cache_read: ${u.cacheReadInputTokens}, cache_create: ${u.cacheCreationInputTokens}`,
          );
        }
      }
      if (msg.subtype === 'success') {
        console.log(`${tag()} ✓ Session completed: ${msg.session_id}`);
        events.push({
          type: 'done',
          sessionId: msg.session_id,
          inputTokens: totalInputTokens,
        });
      } else {
        const errorMsg = 'errors' in msg ? msg.errors.join('; ') : 'Agent execution failed';
        console.error(`${tag()} ✗ Session failed: ${errorMsg}`);
        events.push({
          type: 'error',
          message: errorMsg,
        });
      }
      break;
    }

    case 'stream_event': {
      const event = msg.event;
      if (event.type === 'content_block_delta' && 'delta' in event) {
        const delta = event.delta;
        if (delta.type === 'text_delta' && 'text' in delta) {
          events.push({
            type: 'text',
            content: delta.text,
          });
        }
      } else if (event.type === 'message_delta') {
        const delta = event as { type: string; usage?: { output_tokens?: number } };
        if (delta.usage?.output_tokens) {
          console.log(
            `${tag()} ⚡ Stream: message_delta (output_tokens: ${delta.usage.output_tokens})`,
          );
        } else {
          console.log(`${tag()} ⚡ Stream: message_delta`);
        }
      } else if (event.type !== 'content_block_delta') {
        console.log(`${tag()} ⚡ Stream: ${event.type}`);
      }
      break;
    }

    default: {
      console.log(`${tag()} ❓ Unknown SDK message: ${(msg as { type: string }).type}`);
      break;
    }
  }

  return events;
}
