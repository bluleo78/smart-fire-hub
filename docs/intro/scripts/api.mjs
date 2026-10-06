#!/usr/bin/env node
/**
 * 격리 스택 API 를 촬영 계정으로 한 번 호출해 결과를 출력하는 점검용 도구.
 *
 *   node docs/intro/scripts/api.mjs GET /datasets
 *   node docs/intro/scripts/api.mjs POST /analytics/queries/execute '{"sql":"select 1","readOnly":true}'
 */
import { PEOPLE, call, login } from './seed-demo.mjs';

const [method = 'GET', path = '/auth/me', body] = process.argv.slice(2);
const token = await login(PEOPLE[0].username);
const out = await call(token, method, path, body ? JSON.parse(body) : undefined);
console.log(JSON.stringify(out, null, 1).slice(0, Number(process.env.MAX ?? 4000)));
