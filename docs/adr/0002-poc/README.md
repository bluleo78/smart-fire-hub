# ADR 0002 PoC (WD-716) 재현 스크립트

결과와 판정은 WD-714 코멘트와 ADR 0002 결정 7·8·11에 있다. 이 폴더에는 다시 잴 수 있는 스크립트만 두고, 데이터는 넣지 않는다.

## 준비

- **Qdrant:** `docker compose -p qpoc up -d node1`로 띄운다(`compose.yml`, v1.20.0, API 키 `poc-key`, 포트 16333). 노드 2는 `docker compose -p qpoc --profile two up -d node2`로 띄운다.
- **Python:** `python3 -m venv .venv && .venv/bin/pip install numpy requests "psycopg[binary]"`
- **Nori:** Maven Central에서 `lucene-core`·`lucene-analysis-common`·`lucene-analysis-nori` 10.3.1 jar를 받아 `nori/lib/`에 둔다(JDK 21).
- **말뭉치:** KorQuAD 1.0의 `KorQuAD_v1.0_train.json`·`KorQuAD_v1.0_dev.json`을 korquad.github.io에서 받는다. 라이선스가 CC BY-ND 2.0이라 리포에는 넣지 않는다.
- **pg_trgm 기준선:** 테스트 DB 컨테이너(포트 5433)에 임시 DB `poc_kw`를 만들고 `CREATE EXTENSION pg_trgm; CREATE TABLE doc(id bigint primary key, source_text text);`를 실행한다.

## 실행

- **필터 k-NN(항목 2):** `python knn.py load 200000`을 실행한 뒤 `python knn.py bench`를 실행한다.
- **샤드 이동(항목 3):** 노드 2를 띄운 뒤 `python move.py`를 실행한다.
- **한국어 키워드(항목 1):** `python kw.py prep`, `python kw.py load`, `python kw.py eval` 순서로 실행한다. 질의 세트는 시드가 고정돼 있어 말뭉치가 같으면 결과도 같다. Nori를 더할지 다시 볼 때 이 세트로 잰다(ADR 0002 결정 8).
