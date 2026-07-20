import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import axios from 'axios';
import { createGraphSourceApi } from './graph-source-api.js';

afterEach(() => nock.cleanAll());

describe('graph-source-api', () => {
  it('listDocumentChunks는 datasetId 청크 배열을 반환한다', async () => {
    nock('http://api.test')
      .get('/datasets/7/document-chunks')
      .reply(200, [
        { chunkId: 1, content: 'a' },
        { chunkId: 2, content: 'b' },
      ]);
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createGraphSourceApi(client);
    const chunks = await api.listDocumentChunks(7);
    expect(chunks).toEqual([
      { chunkId: 1, content: 'a' },
      { chunkId: 2, content: 'b' },
    ]);
  });
});
