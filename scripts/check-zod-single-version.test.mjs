// pnpm-lock.yaml 의 zod 판 가드 (WD-829) — `node --test scripts/check-zod-single-version.test.mjs`
// 루트·web·admin·ai-agent 가 서로 다른 zod 판을 잡아 zod 4 가 둘 이상 설치되는(타입·instanceof 불일치) 드리프트를 막는다.
// yaml 패키지를 쓰지 않고 lockfile 텍스트를 줄 단위로 읽는다(의존성 추가 금지).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const lockPath = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'pnpm-lock.yaml')
// 두 테스트가 함께 쓰도록 lockfile 은 한 번만 읽는다.
const lockText = fs.readFileSync(lockPath, 'utf8')

/** 같은 zod 4 판을 써야 하는 importer 들. */
const IMPORTERS = ['.', 'apps/firehub-web', 'apps/firehub-admin', 'apps/firehub-ai-agent']

/**
 * lockfile 텍스트의 `importers:` 절에서 importer 별 zod 의 resolved version 을 읽는다.
 * zod 는 importer 에 따라 `dependencies:`·`devDependencies:` 어느 쪽에도 있을 수 있어 블록 안 위치를 가리지 않는다.
 * @param {string} lockText pnpm-lock.yaml 전체 텍스트
 * @returns {Record<string, string>} importer 경로 → zod version (zod 가 없는 importer 는 빠진다)
 */
function readZodVersions(lockText) {
  const result = {}
  let inImporters = false
  let importer = null
  let inZod = false
  for (const line of lockText.split('\n')) {
    // 최상위 키(들여쓰기 없음)로 importers 절의 시작·끝을 가른다.
    if (/^\S/.test(line)) {
      inImporters = /^importers:\s*$/.test(line)
      importer = null
      inZod = false
      continue
    }
    if (!inImporters) continue
    // importer 헤더: 들여쓰기 2칸(`  .:`, `  apps/firehub-web:`, 따옴표 가능)
    const header = line.match(/^ {2}(?:'([^']+)'|"([^"]+)"|([^\s'"][^:]*)):\s*$/)
    if (header) {
      importer = header[1] ?? header[2] ?? header[3]
      inZod = false
      continue
    }
    // 의존성 항목: 들여쓰기 6칸. zod 항목에 들어갈 때만 version 줄을 본다.
    const dep = line.match(/^ {6}(?:'([^']+)'|"([^"]+)"|([^\s'"][^:]*)):\s*$/)
    if (dep) {
      inZod = (dep[1] ?? dep[2] ?? dep[3]) === 'zod'
      continue
    }
    const ver = line.match(/^ {8}version:\s*(\S+)/)
    if (inZod && ver) result[importer] = ver[1]
  }
  return result
}

/**
 * 주어진 importer 들의 zod 판이 모두 있고 같으며 4.x 인지 검사해 문제 문장 목록을 돌려준다(없으면 빈 배열).
 * @param {Record<string, string>} versions readZodVersions 결과
 * @param {string[]} importers 검사할 importer
 * @returns {string[]}
 */
function findZodProblems(versions, importers = IMPORTERS) {
  const problems = []
  for (const name of importers) {
    if (!versions[name]) problems.push(`${name}: zod 판이 없다`)
    else if (!versions[name].startsWith('4.')) problems.push(`${name}: zod 4 가 아니다 (${versions[name]})`)
  }
  const distinct = new Set(importers.map((n) => versions[n]).filter(Boolean))
  if (distinct.size > 1) problems.push(`importer 간 zod 판이 갈린다: ${[...distinct].join(', ')}`)
  return problems
}

test('루트·web·admin·ai-agent 가 같은 zod 4 판을 쓴다', () => {
  assert.deepEqual(findZodProblems(readZodVersions(lockText)), [])
})

test('lockfile 에 zod 4 판은 하나뿐이다', () => {
  // packages: 절(다음 최상위 키 전까지)의 `  zod@4.x.y:` 키만 센다. snapshots: 절의 같은 키는 세지 않는다.
  const packagesPart = lockText.split(/^packages:\s*$/m)[1]
  assert.ok(packagesPart, 'lockfile 에 packages: 절이 없다 — 형식이 바뀌었는지 확인')
  const packagesSection = packagesPart.split(/^\S/m)[0]
  const keys = packagesSection.match(/^ {2}'?zod@4\.[^:']+'?:/gm) ?? []
  assert.equal(keys.length, 1, `zod 4 판 키: ${keys.join(' ')}`)
})

test('판이 갈리면 잡는다', () => {
  const fixture = [
    'importers:',
    '',
    '  .:',
    '    devDependencies:',
    '      zod:',
    '        specifier: ^4.6.6',
    '        version: 4.6.7',
    '',
    '  apps/firehub-web:',
    '    dependencies:',
    '      zod:',
    '        specifier: ^4.0.0',
    '        version: 4.1.0',
    '',
    '  apps/firehub-admin:',
    '    dependencies:',
    '      zod:',
    '        specifier: ^3.25.0',
    '        version: 3.25.76',
    '',
    'packages:',
    '',
    '  zod@4.1.0:',
    '    resolution: {}',
    '',
  ].join('\n')
  const versions = readZodVersions(fixture)
  assert.deepEqual(versions, { '.': '4.6.7', 'apps/firehub-web': '4.1.0', 'apps/firehub-admin': '3.25.76' })
  const problems = findZodProblems(versions)
  assert.ok(problems.some((p) => p.includes('갈린다')))
  assert.ok(problems.some((p) => p.includes('apps/firehub-admin: zod 4 가 아니다')))
  assert.ok(problems.some((p) => p.includes('apps/firehub-ai-agent: zod 판이 없다')))
})
