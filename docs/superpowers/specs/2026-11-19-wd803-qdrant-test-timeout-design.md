# WD-803 Qdrant 시험 호출 대기 상한 — 설계

## 문제

기차 34 의 api 전체 시험(호스트 load 평균 최고 128)에서 `QdrantVectorStoreLifecycleTest` 두 시험이 Qdrant gRPC 응답 30초 대기를 넘겨 실패했다.

- `restartWithSameConfigPassesVerification` — 수명주기 `ensure` 가 `props.timeout()`(하네스 `QdrantTestContainer.properties()` 가 30초로 고정)으로 `CreateFieldIndex` 를 기다리다 초과했다.
- `missingPayloadIndexesAreFilledOnNextStartup` — 시험 헬퍼 `info()` 의 `.get(30, SECONDS)` 가 초과했다.

부하가 낮을 때 따로 돌리면 둘 다 통과한다. 시험 대상 동작이 틀린 것이 아니라 시험 환경의 대기 상한이 공유 머신의 순간 과부하를 견디지 못한 것이다.

## 결정

- 시험 하네스 `QdrantTestContainer` 에 Qdrant 호출 하나의 대기 상한 상수 `CALL_TIMEOUT`(120초)을 둔다.
  - `properties()` 가 만드는 `QdrantProperties.timeout` 이 이 상수를 쓴다.
  - Qdrant 시험들이 하네스 클라이언트를 직접 부르는 `.get(30, TimeUnit.SECONDS)` 도 이 상수를 쓴다(같은 원인으로 같은 자리에서 다시 깨지지 않게).
- 운영 기본값(`QDRANT_TIMEOUT` 10s)과 `QdrantSupport.await` 동작(시간 초과면 예외)은 바꾸지 않는다.
- 시간 초과를 일부러 만드는 시험(존재하지 않는 주소·꺼진 컨테이너에 2~10초 `QdrantProperties` 를 직접 만드는 것)은 건드리지 않는다 — 짧은 상한이 그 시험의 목적이다.

## 고르지 않은 것

- **시간 초과 한 번 다시 시도**: 수명주기 `ensure` 의 컬렉션·색인 생성은 시간 초과 뒤 서버에서 이미 끝났을 수 있어, 다시 시도하면 시험이 운영과 다른 경로(이미 있음)를 탄다. 시험이 보는 동작을 흐린다.
- **Spring 컨텍스트 Qdrant 시험의 10초 기본값 올리기**: 이번 실패와 무관하고 지금까지 이 원인으로 깨진 기록이 없다. 범위 밖.

## 확인

- Qdrant 시험 패키지(`com.smartfirehub.vectorstore.*`, `QdrantRowSearchServiceTest`) 통과.
- 부하 재현: 하네스 Qdrant 컨테이너의 CPU 를 `docker update --cpus` 로 크게 줄인 상태에서 `QdrantVectorStoreLifecycleTest` 를 돌려, 고치기 전(30초)에는 시간 초과로 실패하고 고친 뒤(120초)에는 통과하는지 본다.
