# WD-785 Tibero 커밋 직전 좁히기 시간 예산 시험 결정화 — 계획

## 목표
`TiberoPythonReadGrantSyncTest` 의 WD-664 6 시간 예산 시험(slowConnect·slowRevoke·slowRejudge)이 호스트 부하와 무관하게 결정적으로 통과하게 한다. 운영 코드는 바꾸지 않는다(원인이 시험 측정 구간이므로).

## 원인
- `narrowPublicToSensitive` 가 `System.nanoTime()` 을 `runInTenantTransaction(...)` 바깥에서 재 — 경과에 시험 대상(`sync.narrowDatasetInCurrentTx`)만이 아니라 트랜잭션 시작·픽스처 `dsl.update(DATASET)`·커밋이 들어간다. 게이트처럼 load 10~18 에서 공용 Testcontainers PG 의 트랜잭션·커밋이 수 초 늦어지면 상한(2000·3800ms)을 넘긴다.
- slowConnect 실패 경과 4514ms 가 흉내 낸 접속 지연(3000ms)보다 긴 것은 접속 상한이 걸리지 않아서가 아니다 — 상한 없는 접속(2인자 withDdl)을 썼다면 접속이 성공해 `confirmed()==true` 였어야 한다. 그러나 시간 단언이 맨 앞이라 실패 시 그 신호를 볼 수 없었다.

## 바꿀 것 (시험 파일 하나)
1. `narrowPublicToSensitive`: 시작·끝 시각을 람다 안에서 `sync.narrowDatasetInCurrentTx(...)` 호출만 감싸 잰다(픽스처 update·커밋 제외). Javadoc 에 왜 그 구간만 재는지(부하 아래 PG 트랜잭션·커밋 지연은 시험 대상 아님, WD-785) 적는다.
2. 세 시험 모두 **관측 신호 단언을 시간 단언보다 앞에** 둔다 — 실패하면 상한이 걸렸는지부터 보이게.
3. slowConnect: `connectDelayMs` 를 3_000 → 5_000 으로 늘리고, 시간 단언은 「흉내 낸 접속 지연보다 확실히 짧다」로 `isLessThan(3_000L)`(접속 상한 0.6초 + 여유). 상한 없는 접속이면 5초를 기다리므로 회귀는 여전히 잡힌다.
4. slowRevoke: 상한 2_800 유지(측정 구간만 좁아짐). 관측 신호(보낸 REVOKE 하나, queryTimeout 1초) 먼저.
5. slowRejudge: 상한 3_800 유지. 관측 신호 추가 — 재판정의 테넌트 전체 조회(`grantQueryTables` 중 빈 목록)가 정확히 한 번이고, 그 뒤 재판정 REVOKE 를 보내지 않았다(`executed` 기존 단언). `grantQueryTables` 는 시험 시작 전에 비운다.
6. fastTibero 시험은 시간 단언이 없어 그대로.

## Review Focus (완료 기준이 말하지 않은 경우)
- 람다 안 측정: `runInTenantTransaction` 이 결과를 돌려주므로 경과는 `long[]` 에 람다 안에서 쓴다 — 예외 시에도 테스트가 실패로 끝나므로 finally 불필요.
- `grantQueryTables` 의 타입·초기화 위치(필드 선언부) 확인, `clear()` 가 가능한지.
- 빈 목록이 「테넌트 전체 조회」라는 해석이 `slotGrantRows` 와 일치하는지(`boundTables.isEmpty()` 일 때 지연을 넣는다).
- 첫 판 조회는 테이블 IN 목록이 있어 빈 목록이 아님을 확인(아니면 기대 횟수를 실제 흐름에 맞춘다 — 추측으로 숫자를 정하지 말고 한 번 돌려 확인).
- 한국어 주석 규칙.

## 검증
- `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSyncTest"` 전체 통과.
- 부하 재현: `stress-ng` 가 있으면 `--cpu` 로 부하를 주고 같은 클래스를 다시 돌린다. 없으면 같은 클래스를 3번 연속 돌려 통과.

## 내가 정한 것
1. 운영 코드(`PreCommitDeadline` 에 시계 주입 등)는 바꾸지 않는다 — 원인이 시험 측정 구간이고, 시계 주입은 변경 범위가 커서 이 이슈의 완료 기준에 비해 과하다.
2. slowConnect 의 시간 상한을 「흉내 낸 지연보다 짧다」로 바꾸고 지연을 5초로 늘려 여유를 키운다 — 상한 0.6초와 비교할 절대값보다 「끝까지 기다리지 않았다」가 시험의 뜻에 맞다.

## 리뷰 반영(19:05)
3. 측정 구간만 옮기면 부족하다 — 예산(PreCommitDeadline)이 좁히기 진입부터 흘러 앞쪽 PG 일까지 센다. 시험 예산을 3초로 넓히고, 흉내 낸 느린 일(문장·재판정 조회)을 4초로 예산보다 길게 잡아 「그 일 하나에서 예산을 넘긴다」를 부하와 무관하게 만든다. 운영 코드(시계 주입)는 여전히 바꾸지 않는다.
