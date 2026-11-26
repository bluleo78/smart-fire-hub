// scripts/check-migration-versions.mjs 단위·CLI 테스트 (WP-1081 → WD-506 이식) — `node --test scripts/check-migration-versions.test.mjs`
// 두 PR 이 같은 Flyway 번호를 더하거나 main 의 최대 번호 이하를 늦게 머지하는 경우를 잡는지 본다.
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { compareTuples, describeProblems, findMigrationProblems, MIGRATION_DIR, MIGRATION_GROUPS, migrationVersion, migrationVersionTuple } from './check-migration-versions.mjs'

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-migration-versions.mjs')

test('버전 마이그레이션 이름에서만 번호를 꺼낸다', () => {
  assert.equal(migrationVersion('V137__add_x.sql'), 137)
  assert.equal(migrationVersion('V9__multi_assignee.sql'), 9)
  assert.equal(migrationVersion('R__view.sql'), null)
  assert.equal(migrationVersion('V12_missing_double_underscore.sql'), null)
  assert.equal(migrationVersion('README.md'), null)
  assert.equal(migrationVersion('sub/V230__x.sql'), 230)
  assert.equal(migrationVersion('V172__blank.java'), 172)
  assert.equal(migrationVersion('java/V3__x.java'), 3)
})

test('PG 의 SQL 과 Java 가 같은 번호면 중복, Java 의 정수 아닌 버전도 잡는다', () => {
  const dup = findMigrationProblems({ head: ['V1__a.sql', 'java/V1__b.java'], fork: [], base: [] })
  assert.deepEqual(dup.duplicates, [{ version: 1, files: ['V1__a.sql', 'java/V1__b.java'] }])
  assert.deepEqual(findMigrationProblems({ head: ['java/V2_1__x.java'], fork: [], base: [] }).nonInteger, ['java/V2_1__x.java'])
})

test('더한 번호가 main 의 최대보다 크고 겹치지 않으면 문제가 없다', () => {
  const base = ['V1__a.sql', 'V2__b.sql']
  const result = findMigrationProblems({ head: [...base, 'V3__c.sql'], fork: base, base })
  assert.deepEqual(result, { duplicates: [], notAfterBase: [], nonInteger: [], baseMax: 2 })
  assert.deepEqual(describeProblems(result, 'origin/main'), [])
})

test('같은 번호가 트리에 둘이면 중복으로 잡는다(합친 상태 — 머지 커밋)', () => {
  const fork = ['V1__a.sql']
  const base = [...fork, 'V2__main.sql']
  const head = [...base, 'V2__pr.sql']
  const result = findMigrationProblems({ head, fork: base, base })
  assert.deepEqual(result.duplicates, [{ version: 2, files: ['V2__main.sql', 'V2__pr.sql'] }])
  assert.deepEqual(describeProblems(result, 'HEAD^1'), [
    '번호 중복 V2: V2__main.sql, V2__pr.sql',
    'HEAD^1 의 최대 번호 V2 이하: V2__pr.sql (V2)',
  ])
})

test('갈라진 뒤 main 이 같은 번호를 먼저 넣으면 PR 의 번호는 main 최대 이하로 잡는다(PR 트리엔 main 것이 없다)', () => {
  const fork = ['V1__a.sql']
  const result = findMigrationProblems({ head: [...fork, 'V2__pr.sql'], fork, base: [...fork, 'V2__main.sql'] })
  assert.deepEqual(result.duplicates, [])
  assert.deepEqual(result.notAfterBase, [{ version: 2, file: 'V2__pr.sql' }])
})

test('main 의 최대보다 낮은 번호를 늦게 더해도 잡는다', () => {
  const fork = ['V1__a.sql']
  const base = [...fork, 'V5__main.sql']
  const result = findMigrationProblems({ head: [...fork, 'V3__pr.sql', 'V6__pr.sql'], fork, base })
  assert.deepEqual(result.notAfterBase, [{ version: 3, file: 'V3__pr.sql' }])
  assert.equal(result.baseMax, 5)
})

test('하위 폴더의 같은 번호도 중복으로 잡는다(Flyway 는 하위 폴더까지 읽는다)', () => {
  const base = ['V1__a.sql', 'V2__main.sql']
  const result = findMigrationProblems({ head: [...base, 'sub/V2__pr.sql'], fork: base, base })
  assert.deepEqual(result.duplicates, [{ version: 2, files: ['V2__main.sql', 'sub/V2__pr.sql'] }])
})

test('정수가 아닌 버전(V2_1__·V2.1__)을 더하면 잡는다 — 번호 비교를 빠져나가지 않게', () => {
  const base = ['V1__a.sql', 'V5__main.sql']
  const result = findMigrationProblems({ head: [...base, 'V2_1__x.sql', 'V2.1__y.sql', 'R__view.sql'], fork: base, base })
  assert.deepEqual(result.nonInteger, ['V2.1__y.sql', 'V2_1__x.sql'])
  assert.deepEqual(describeProblems(result, 'origin/main'), [
    '정수가 아닌 버전(V<정수>__ 만 쓴다): V2.1__y.sql',
    '정수가 아닌 버전(V<정수>__ 만 쓴다): V2_1__x.sql',
  ])
})

test('갈라진 뒤 main 이 옮긴(이름을 바꾼) 옛 파일은 PR 이 더한 것으로 보지 않는다', () => {
  // main 이 V2__x 를 V3__x 로 옮겼고, 옛 main 에서 갈라진 PR 트리엔 V2__x 가 그대로 있다
  const fork = ['V1__a.sql', 'V2__x.sql']
  const result = findMigrationProblems({ head: [...fork, 'V4__pr.sql'], fork, base: ['V1__a.sql', 'V3__x.sql'] })
  assert.deepEqual(result.notAfterBase, [])
})

// 임시 저장소에 실제 커밋을 만들어 CLI(종료 코드·출력)를 본다.
// 사용자 전역 git 설정(commit.gpgsign·훅 등)이 끼어들지 않게 전역·시스템 설정을 끄고, 커밋 훅이 넘긴 인덱스 변수도 지운다.
const env = { ...process.env, GIT_CONFIG_GLOBAL: os.devNull, GIT_CONFIG_NOSYSTEM: '1' }
for (const k of ['GIT_INDEX_FILE', 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_PREFIX', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES']) {
  delete env[k]
}

function repo(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'migver-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const run = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', env }).trim()
  run('init', '-q', '-b', 'main')
  run('config', 'user.email', 't@t')
  run('config', 'user.name', 't')
  // folder 로 다른 묶음의 폴더(Java·Tibero)에도 파일을 더한다 — .java 는 자바 내용으로
  const add = (name, folder = MIGRATION_DIR) => {
    fs.mkdirSync(path.dirname(path.join(dir, folder, name)), { recursive: true })
    fs.writeFileSync(path.join(dir, folder, name), name.endsWith('.java') ? 'class X {}\n' : 'select 1;\n')
    run('add', '-A')
    run('commit', '-qm', name)
  }
  // 스크립트 경로를 바꿔 부를 수 있게(심볼릭 링크 테스트) — 기본은 이 저장소의 스크립트
  const checkWith = (file, ...args) => spawnSync('node', [file, ...args], { cwd: dir, encoding: 'utf8', env })
  const check = (...args) => checkWith(script, ...args)
  return { dir, run, add, check, checkWith }
}

test('CLI: 갈라진 뒤 main 과 같은 번호를 더한 브랜치는 종료 코드 1 과 겹친 파일·jOOQ 안내를 낸다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  r.run('checkout', '-q', '-b', 'pr')
  r.add('sub/V2__pr.sql')
  r.run('checkout', '-q', 'main')
  r.add('V2__main.sql')

  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /main 의 최대 번호 V2 이하: sub\/V2__pr\.sql/)
  assert.match(bad.stderr, /generateJooq/)

  // 합친 상태(머지 커밋, HEAD^1 기준)에선 중복으로도 잡힌다
  r.run('merge', '-q', '--no-edit', 'pr')
  const merged = r.check('HEAD^1')
  assert.equal(merged.status, 1)
  assert.match(merged.stderr, /번호 중복 V2: V2__main\.sql, sub\/V2__pr\.sql/)
})

test('CLI: 번호가 main 뒤에 오면 통과하고, 마이그레이션 폴더가 없어도 통과한다', (t) => {
  const r = repo(t)
  fs.writeFileSync(path.join(r.dir, 'a.txt'), 'a\n')
  r.run('add', '-A')
  r.run('commit', '-qm', 'init')
  assert.equal(r.check('main').status, 0)

  r.add('V1__a.sql')
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V3__pr.sql')
  r.run('checkout', '-q', 'main')
  r.add('V2__main.sql')
  const ok = r.check('main', 'pr')
  assert.equal(ok.status, 0, ok.stderr)
})

test('CLI: 심볼릭 링크 경로로 불러도 검사한다', (t) => {
  const r = repo(t)
  const link = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'migver-link-')), 'check.mjs')
  t.after(() => fs.rmSync(path.dirname(link), { recursive: true, force: true }))
  fs.symlinkSync(script, link)
  r.add('V1__a.sql')
  r.add('V1__b.sql')
  const out = r.checkWith(link, 'HEAD')
  assert.equal(out.status, 1)
  assert.match(out.stderr, /번호 중복 V1/)
})

test('CLI: base 가 없거나 읽을 수 없으면 종료 코드 2', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  assert.equal(r.check().status, 2)
  assert.equal(r.check('nope').status, 2)
})

const JAVA_DIR = MIGRATION_GROUPS[0].dirs[1].dir
const TIBERO_DIR = MIGRATION_GROUPS[1].dirs[0].dir

test('CLI: Java 마이그레이션이 main 에 먼저 들어간 같은 번호를 PR 의 SQL 이 더하면 잡는다(WD-1171 재현)', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2__pr.sql')
  r.run('checkout', '-q', 'main')
  r.add('V2__main.java', JAVA_DIR)

  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[PG\] main 의 최대 번호 V2 이하: V2__pr\.sql/)

  r.run('merge', '-q', '--no-edit', 'pr')
  const merged = r.check('HEAD^1')
  assert.equal(merged.status, 1)
  assert.match(merged.stderr, /\[PG\] 번호 중복 V2: V2__pr\.sql, java\/V2__main\.java/)
})

test('CLI: Tibero 폴더의 같은 번호도 잡는다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2__pr.sql', TIBERO_DIR)
  r.run('checkout', '-q', 'main')
  r.add('V2__main.sql', TIBERO_DIR)

  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[Tibero\] main 의 최대 번호 V2 이하: V2__pr\.sql/)
})

test('CLI: PG 와 Tibero 의 같은 번호(짝)는 통과한다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2__x.sql')
  r.add('V2__x.sql', TIBERO_DIR)
  const ok = r.check('main', 'pr')
  assert.equal(ok.status, 0, ok.stderr)
})

test('CLI: base 에 Java 폴더가 없어도 PR 이 더한 Java 마이그레이션은 통과한다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2__a.java', JAVA_DIR)
  const ok = r.check('main', 'pr')
  assert.equal(ok.status, 0, ok.stderr)
})

const DATASTORE_DIR = MIGRATION_GROUPS[2].dirs[0].dir

test('소수점 버전 튜플 파서·비교(Tibero)', () => {
  assert.deepEqual(migrationVersionTuple('V173.1__x.sql'), [173, 1])
  assert.deepEqual(migrationVersionTuple('V2__x.java'), [2])
  assert.equal(migrationVersionTuple('V1_2__x.sql'), null)
  assert.equal(migrationVersionTuple('V1.x__x.sql'), null)
  assert.ok(compareTuples([173, 1], [173]) > 0)
  assert.ok(compareTuples([173, 1], [173, 2]) < 0)
  const r = findMigrationProblems({ head: ['V2__a.sql', 'V2.1__b.sql', 'V1_2__c.sql'], fork: [], base: [] }, true)
  assert.deepEqual(r.nonInteger, ['V1_2__c.sql'])
})

test('PG 의 V3.1 은 여전히 정수가 아닌 버전으로 거절한다', () => {
  assert.deepEqual(findMigrationProblems({ head: ['V3.1__x.sql'], fork: [], base: [] }).nonInteger, ['V3.1__x.sql'])
})

test('CLI: Tibero 의 V2.1 후속은 base 의 V2 위에 더하면 통과한다', (t) => {
  const r = repo(t)
  r.add('V2__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2.1__x.sql', TIBERO_DIR)
  const ok = r.check('main', 'pr')
  assert.equal(ok.status, 0, ok.stderr)
})

test('CLI: 두 브랜치가 같은 Tibero V2.1 을 더하면 머지 전엔 최대 이하, 머지 뒤엔 중복으로 잡는다', (t) => {
  const r = repo(t)
  r.add('V2__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('sub/V2.1__pr.sql', TIBERO_DIR)
  r.run('checkout', '-q', 'main')
  r.add('V2.1__main.sql', TIBERO_DIR)
  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[Tibero\] main 의 최대 번호 V2\.1 이하: sub\/V2\.1__pr\.sql/)
  r.run('merge', '-q', '--no-edit', 'pr')
  const merged = r.check('HEAD^1')
  assert.equal(merged.status, 1)
  assert.match(merged.stderr, /\[Tibero\] 번호 중복 V2\.1: /)
})

test('CLI: base 최대가 V2 인데 Tibero V1.1 을 더하면 최대 이하로 잡는다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V1.1__x.sql', TIBERO_DIR)
  r.run('checkout', '-q', 'main')
  r.add('V2__b.sql', TIBERO_DIR)
  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[Tibero\] main 의 최대 번호 V2 이하: V1\.1__x\.sql \(V1\.1\)/)
})

test('CLI: DataStore 폴더의 같은 번호도 잡는다', (t) => {
  const r = repo(t)
  r.add('V1__a.sql', DATASTORE_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V2__pr.sql', DATASTORE_DIR)
  r.run('checkout', '-q', 'main')
  r.add('V2__main.sql', DATASTORE_DIR)
  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[DataStore\] main 의 최대 번호 V2 이하: V2__pr\.sql/)
})

test('CLI: Java 하위 패키지의 마이그레이션이 SQL 과 같은 번호면 잡는다(재귀 스캔)', (t) => {
  const r = repo(t)
  r.add('V1__a.sql')
  r.add('V3__x.sql')
  r.add('sub/V3__x.java', JAVA_DIR)
  const bad = r.check('HEAD')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[PG\] 번호 중복 V3: V3__x\.sql, java\/sub\/V3__x\.java/)
})

test('CLI: Tibero V173.0 은 V173 과 같은 버전이라 base 최대 이하로 잡고, V2 와 V2.0 은 중복이다', (t) => {
  const r = repo(t)
  r.add('V173__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V173.0__x.sql', TIBERO_DIR)
  const bad = r.check('main', 'pr')
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /\[Tibero\] main 의 최대 번호 V173 이하: V173\.0__x\.sql/)
  const dup = findMigrationProblems({ head: ['V2__a.sql', 'V2.0__b.sql'], fork: [], base: [] }, true)
  assert.deepEqual(dup.duplicates, [{ version: '2', files: ['V2.0__b.sql', 'V2__a.sql'] }])
})

test('CLI: Tibero 폴더의 .java 는 Flyway 가 실행하지 않으니 세지 않는다', (t) => {
  const r = repo(t)
  r.add('V5__a.sql', TIBERO_DIR)
  r.run('checkout', '-q', '-b', 'pr')
  r.add('V5__x.java', TIBERO_DIR)
  const ok = r.check('main', 'pr')
  assert.equal(ok.status, 0, ok.stderr)
})

test('Tibero 의 정수 아닌 버전 안내는 허용 형식(소수점)을 말한다', () => {
  const r = findMigrationProblems({ head: ['V1_2__x.sql'], fork: [], base: [] }, true)
  assert.deepEqual(describeProblems(r, 'main', true), ['정수가 아닌 버전(V<정수>(.<정수>)__ 만 쓴다): V1_2__x.sql'])
})
