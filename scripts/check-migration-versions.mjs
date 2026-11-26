#!/usr/bin/env node
// Flyway 마이그레이션 번호 확인 (WP-1081 → WD-506 이식) — `node scripts/check-migration-versions.mjs <base-ref> [<head-ref>]`
// 두 PR 이 같은 번호(V<n>__)를 각자 더하면 파일 이름이 달라 git 충돌도 없고 PR 마다의 검사도 통과하지만,
// 합친 main 에서 Flyway 가 번호 중복으로 실패한다. main 의 최대 번호 이하를 늦게 머지해도 이미 적용한 DB 에서 실패한다.
// 그래서 사람·AI 기억에 맡기지 않고 아래를 기계적으로 본다(head 기본값 HEAD):
//   1) head 트리 안에서 같은 번호가 둘 이상인가
//   2) head 가 갈라진 뒤(merge-base) 더한 마이그레이션의 번호가 base 의 최대 번호 이하인가
//   3) 더한 마이그레이션이 정수가 아닌 버전(V230_1__·V230.1__)인가 — PG·DataStore 는 V<정수>__ 만 쓴다. 놓치지 않게 막는다
//      (Tibero 묶음만 Tibero 전용 후속 V<정수>.<정수>__ 소수점 버전을 허용하고 튜플로 비교한다)
// Flyway 처럼 하위 폴더까지 보고, PG(SQL+Java)·Tibero 를 묶음별로 따로 센다(WD-1171).
// 하나라도 걸리면 번호를 적고 종료 코드 1, ref 를 읽지 못하면 2.
// 쓰는 곳: 기차 쌓기·rebase(scripts/husky/train.sh), 검토자 머지 직전(base = origin/main — .work-loop/project.md 9절).
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const MIGRATION_DIR = 'apps/firehub-api/src/main/resources/db/migration'

/**
 * 번호를 따로 세는 묶음 — Flyway `locations`(application.yml 기본, application-tibero.yml)가 DB 별로 다른 폴더를 읽기 때문이다.
 * PG 는 SQL 폴더와 Java 마이그레이션 폴더를 한 번호 공간으로 읽는다(Java 는 파일 이름을 `java/` 로 구분해 보인다).
 * Tibero 는 별도 SQL 폴더만 본다 — Java 위치가 없는 까닭: 패키지 이름에 `-`(migration-tibero)를 쓸 수 없어서다.
 * DataStore 는 DataStoreFlywayConfig 가 따로 돌리는 Flyway(db/datastore-postgres)라 별도 묶음이다.
 * ext: 폴더마다 Flyway 가 실행하는 확장자(리소스는 .sql, 자바 폴더는 .java)만 센다.
 * allowDecimal: Tibero 는 Tibero 전용 후속(V155.1 등)에 소수점 버전을 쓰므로 허용한다(PG·DataStore 는 정수만).
 * 묶음끼리는 번호가 같아도(PG·Tibero 짝) 괜찮다.
 */
export const MIGRATION_GROUPS = [
  {
    name: 'PG',
    dirs: [
      { dir: MIGRATION_DIR, prefix: '', ext: '.sql' },
      { dir: 'apps/firehub-api/src/main/java/db/migration', prefix: 'java/', ext: '.java' },
    ],
  },
  {
    name: 'Tibero',
    allowDecimal: true,
    dirs: [{ dir: 'apps/firehub-api/src/main/resources/db/migration-tibero', prefix: '', ext: '.sql' }],
  },
  { name: 'DataStore', dirs: [{ dir: 'apps/firehub-api/src/main/resources/db/datastore-postgres', prefix: '', ext: '.sql' }] },
]

/** Flyway 가 버전 마이그레이션으로 읽는 이름(V<버전>__*.sql·*.java — 버전에 . 이나 _ 가 들어갈 수 있다). */
const ANY_VERSIONED = /^V.+?__.+\.(sql|java)$/i
/** 이 확인이 받는 버전 — V<정수>(.<정수>)*__*.sql·*.java. 정수 묶음은 아래 parseVersion 이 한 자리만 받는다. */
const NUMERIC_VERSIONED = /^V(\d+(?:\.\d+)*)__.+\.(sql|java)$/

/**
 * 파일(하위 폴더 경로 가능)에서 숫자 튜플 버전을 꺼낸다 — 정수·소수점 묶음이 같은 파서를 쓴다(따로 두면 손으로 맞춰야 한다).
 * allowDecimal 이 false 면 적힌 그대로 한 자리(V<정수>__)만 받는다 — V173.0 도 PG·DataStore 에선 정수가 아니다.
 * 형식이 아니면 null.
 */
function parseVersion(file, allowDecimal) {
  const m = NUMERIC_VERSIONED.exec(path.posix.basename(file))
  if (!m) return null
  const t = m[1].split('.').map(Number)
  if (!allowDecimal && t.length > 1) return null
  // Flyway 는 끝의 0 을 버려 V173 과 V173.0 을 같은 버전으로 본다 — 한 자리는 남긴다(V0 → [0])
  while (t.length > 1 && t[t.length - 1] === 0) t.pop()
  return t
}

/** 파일에서 정수 버전 번호를 꺼낸다 — V<정수>__*.sql·*.java 가 아니면 null. */
export function migrationVersion(file) {
  const t = parseVersion(file, false)
  return t === null ? null : t[0]
}

/** 파일에서 소수점 버전의 숫자 튜플을 꺼낸다(예: V173.1__x.sql → [173, 1]) — 형식이 아니면 null. */
export function migrationVersionTuple(file) {
  return parseVersion(file, true)
}

/** 튜플 비교 — 앞 자리부터 비교하고 짧은 쪽이 같은 접두면 작다(V173 < V173.1 < V173.2). */
export function compareTuples(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? -1) - (b[i] ?? -1)
    if (d !== 0) return d
  }
  return 0
}

/** Flyway 버전 마이그레이션이지만 정수 번호가 아닌 파일인가(V230_1__·V230.1__·.SQL). */
function isNonIntegerVersioned(file, allowDecimal = false) {
  const name = path.posix.basename(file)
  if (!ANY_VERSIONED.test(name)) return false
  // 소수점 허용 묶음에선 V<정수>.<정수>__ 는 정상 — 그 밖 형식(V1_2__·V1.x__)만 막는다
  return parseVersion(name, allowDecimal) === null
}

/**
 * 번호 문제를 찾는다(순수 함수 — git 은 CLI 가 읽어 넘긴다).
 * @param head 검사할 트리의 마이그레이션 파일 이름들
 * @param fork head 가 갈라진 지점(merge-base)의 파일 이름들 — head 에만 있는 것이 「더한 것」
 * @param base 기준(main)의 파일 이름들 — 더한 것은 이 최대 번호보다 커야 한다
 * @param allowDecimal true 면 V<정수>(.<정수>)* 를 튜플로 비교한다(Tibero) — 아니면 정수만
 * @returns duplicates: [{ version, files }], notAfterBase: [{ version, file }], nonInteger: [file], baseMax
 *   (version·baseMax 는 파일에 적힌 대로 — 소수점 묶음은 '173.1' 같은 문자열, 아니면 숫자)
 */
export function findMigrationProblems({ head, fork, base }, allowDecimal = false) {
  // 번호 튜플 — 정수 묶음은 한 자리 튜플. 표시는 정수 묶음이 숫자, 소수점 묶음이 '173.1' 문자열
  const tupleOf = (name) => parseVersion(name, allowDecimal)
  const show = (t) => (allowDecimal ? t.join('.') : t[0])

  const byVersion = new Map()
  for (const name of head) {
    const t = tupleOf(name)
    if (t === null) continue
    const key = t.join('.')
    if (!byVersion.has(key)) byVersion.set(key, { tuple: t, files: [] })
    byVersion.get(key).files.push(name)
  }
  const duplicates = [...byVersion.values()]
    .filter(({ files }) => files.length > 1)
    .sort((a, b) => compareTuples(a.tuple, b.tuple))
    .map(({ tuple, files }) => ({ version: show(tuple), files: files.sort() }))

  const baseTuples = base.map(tupleOf).filter((t) => t !== null)
  const maxTuple = baseTuples.reduce((m, t) => (compareTuples(t, m) > 0 ? t : m), [0])
  const forkSet = new Set(fork)
  const added = head.filter((name) => !forkSet.has(name))
  const notAfterBase = added
    .map((file) => ({ tuple: tupleOf(file), file }))
    .filter(({ tuple }) => tuple !== null && compareTuples(tuple, maxTuple) <= 0)
    .sort((a, b) => compareTuples(a.tuple, b.tuple))
    .map(({ tuple, file }) => ({ version: show(tuple), file }))
  const nonInteger = added.filter((f) => isNonIntegerVersioned(f, allowDecimal)).sort()

  return { duplicates, notAfterBase, nonInteger, baseMax: show(maxTuple) }
}

/** 문제를 사람이 읽을 줄들로 — 문제가 없으면 빈 배열. */
export function describeProblems({ duplicates, notAfterBase, nonInteger, baseMax }, baseRef, allowDecimal = false) {
  const lines = []
  for (const { version, files } of duplicates) {
    lines.push(`번호 중복 V${version}: ${files.join(', ')}`)
  }
  for (const { version, file } of notAfterBase) {
    lines.push(`${baseRef} 의 최대 번호 V${baseMax} 이하: ${file} (V${version})`)
  }
  for (const file of nonInteger) {
    const allowed = allowDecimal ? 'V<정수>(.<정수>)__' : 'V<정수>__'
    lines.push(`정수가 아닌 버전(${allowed} 만 쓴다): ${file}`)
  }
  return lines
}

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

/**
 * ref 트리에서 한 묶음의 마이그레이션 파일들 — 묶음의 각 폴더를 읽어 `prefix + 폴더 기준 상대 경로`로 합친다(하위 폴더 포함).
 * 경로로 거르므로 그 커밋에 폴더가 없으면 빈 배열.
 */
function groupFiles(ref, group) {
  return group.dirs.flatMap(({ dir, prefix, ext }) =>
    git(['ls-tree', '-r', '--name-only', ref, '--', `${dir}/`])
      .split('\n')
      .filter(Boolean)
      // Flyway 는 폴더 종류별 확장자만 실행한다 — 리소스 폴더의 .java·자바 폴더의 .sql 은 세지 않는다
      .filter((p) => p.toLowerCase().endsWith(ext))
      .map((p) => prefix + path.posix.relative(dir, p)),
  )
}

function main([baseRef, headRef = 'HEAD']) {
  if (!baseRef) {
    console.error('사용: node scripts/check-migration-versions.mjs <base-ref> [<head-ref>]')
    return 2
  }
  // 묶음마다 따로 비교해 문제 줄에 묶음 이름을 붙인다
  // try 안에는 git 호출만 둔다 — 비교 로직의 버그가 종료 코드 2(건너뜀)로 숨어 관문을 통과시키면 안 된다
  const lines = []
  let files
  try {
    const forkSha = git(['merge-base', baseRef, headRef])
    files = MIGRATION_GROUPS.map((group) => [headRef, forkSha, baseRef].map((ref) => groupFiles(ref, group)))
  } catch (e) {
    console.error(`[migration-versions] ref 를 읽지 못함(${baseRef}, ${headRef}): ${e.stderr?.trim() || e.message}`)
    return 2
  }
  MIGRATION_GROUPS.forEach((group, i) => {
    const [head, fork, base] = files[i]
    const problems = findMigrationProblems({ head, fork, base }, group.allowDecimal)
    for (const l of describeProblems(problems, baseRef, group.allowDecimal)) lines.push(`[${group.name}] ${l}`)
  })
  if (lines.length === 0) {
    console.log(`[migration-versions] 통과 — ${headRef} 의 마이그레이션 번호가 겹치지 않고 ${baseRef} 뒤에 온다`)
    return 0
  }
  console.error(
    '[migration-versions] 마이그레이션 번호를 옮겨야 한다 — 최신 main 위로 rebase 해 가장 큰 번호 뒤로 올리고 ' +
      'cd apps/firehub-api && ./gradlew generateJooq 결과(src/main/generated/)를 함께 커밋한다',
  )
  for (const l of lines) console.error(`  - ${l}`)
  return 1
}

/**
 * 이 파일을 직접 실행했는지(테스트의 import 가 아닌지) — 심볼릭 링크 경로로 불러도 검사 없이 0 으로 끝나지 않게 실제 경로로 비교한다.
 * WP 는 공용 도우미(deploy/release/lib/cli.mjs)를 쓰지만 이 저장소에는 없어 여기 둔다.
 */
function isMainModule(metaUrl) {
  if (!process.argv[1]) return false
  try {
    return fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(metaUrl))
  } catch {
    return false
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2))
}
