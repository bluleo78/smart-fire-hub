import { describe, it, expect } from 'vitest';
import { stripPropertyNames, sanitizeOutgoingMessage } from './schema-compat.js';

describe('stripPropertyNames', () => {
  it('중첩된 propertyNames 키를 재귀적으로 제거한다', () => {
    // add_row 형태: z.record(z.string(), z.unknown()) → propertyNames + additionalProperties
    const schema = {
      type: 'object',
      properties: {
        data: { type: 'object', propertyNames: { type: 'string' }, additionalProperties: {} },
      },
      required: ['data'],
    };
    stripPropertyNames(schema);
    expect(schema.properties.data).not.toHaveProperty('propertyNames');
    // 다른 키는 보존 (의미 손실 없음)
    expect(schema.properties.data).toHaveProperty('additionalProperties');
    expect(schema.properties.data.type).toBe('object');
    expect(schema.required).toEqual(['data']);
  });

  it('배열 안의 propertyNames 도 제거한다', () => {
    const schema = { type: 'array', items: { type: 'object', propertyNames: { type: 'string' }, properties: {} } };
    stripPropertyNames(schema);
    expect((schema.items as Record<string, unknown>)).not.toHaveProperty('propertyNames');
  });

  it('propertyNames 가 없으면 아무것도 바꾸지 않는다', () => {
    const schema = { type: 'object', properties: { k: { type: 'string' } } };
    stripPropertyNames(schema);
    expect(schema).toEqual({ type: 'object', properties: { k: { type: 'string' } } });
  });
});

describe('sanitizeOutgoingMessage', () => {
  it('tools/list 응답의 각 inputSchema 에서 propertyNames 를 제거한다', () => {
    const msg = {
      jsonrpc: '2.0',
      id: 1,
      result: {
        tools: [
          { name: 'add_row', inputSchema: { type: 'object', properties: { data: { type: 'object', propertyNames: { type: 'string' } } } } },
          { name: 'noop', inputSchema: { type: 'object', properties: { k: { type: 'string' } } } },
        ],
      },
    };
    sanitizeOutgoingMessage(msg);
    expect(msg.result.tools[0].inputSchema.properties.data).not.toHaveProperty('propertyNames');
    expect(msg.result.tools[1].inputSchema.properties.k).toEqual({ type: 'string' });
  });

  it('tools/list 응답이 아니면 무시한다', () => {
    const msg = { jsonrpc: '2.0', id: 1, result: { content: [] } };
    expect(() => sanitizeOutgoingMessage(msg)).not.toThrow();
  });
});
