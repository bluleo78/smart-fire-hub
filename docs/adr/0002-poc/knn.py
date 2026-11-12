"""WD-716 PoC 항목 2 — 필터 결합 k-NN 의 지연·재현율.

ADR 0002 결정 7 컬렉션 구성(dense 1024 + kw_nori/kw_bigram sparse idf, tenant_id is_tenant,
샤드 6, int8 양자화 + 원본 on_disk)을 그대로 만들고, 군집 분포 합성 벡터를 넣는다.
정확 검색(exact=true) 결과를 기준으로 HNSW 검색의 recall@10 과 지연을 필터 선택도별로 잰다.

사용: python knn.py load N   /  python knn.py bench
"""
import json
import sys
import time

import numpy as np
import requests

URL = "http://localhost:16333"
H = {"api-key": "poc-key"}
COL = "dataset_dense_1024_poc"
DIM = 1024
TENANTS = 4          # 테넌트 1 이 절반을 차지하게 치우치게 둔다
DS_PER_TENANT = 4000  # 테넌트당 데이터셋 수 — "볼 수 있는 dataset_id 수천 개" 상황
CLUSTERS = 200
rng = np.random.default_rng(7)
CENTERS = rng.normal(size=(CLUSTERS, DIM)).astype(np.float32)


def vec(n):
    """군집 중심 + 잡음 — 균등 난수보다 실제 임베딩 분포에 가깝다."""
    c = CENTERS[rng.integers(0, CLUSTERS, n)]
    v = c + 0.6 * rng.normal(size=(n, DIM)).astype(np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def req(method, path, body=None):
    r = requests.request(method, URL + path, headers=H, json=body, timeout=600)
    if r.status_code >= 300:
        raise SystemExit(f"{method} {path} -> {r.status_code} {r.text[:400]}")
    return r.json()


def create():
    requests.delete(f"{URL}/collections/{COL}", headers=H)
    req("PUT", f"/collections/{COL}", {
        "shard_number": 6,
        "vectors": {"dense": {"size": DIM, "distance": "Cosine", "on_disk": True}},
        "sparse_vectors": {"kw_nori": {"modifier": "idf"}, "kw_bigram": {"modifier": "idf"}},
        "hnsw_config": {"m": 16, "ef_construct": 100, "payload_m": 16},
        "quantization_config": {"scalar": {"type": "int8", "quantile": 0.99, "always_ram": True}},
    })
    req("PUT", f"/collections/{COL}/index",
        {"field_name": "tenant_id", "field_schema": {"type": "integer", "is_tenant": True}})
    req("PUT", f"/collections/{COL}/index", {"field_name": "dataset_id", "field_schema": "integer"})
    req("PUT", f"/collections/{COL}/index", {"field_name": "model", "field_schema": "keyword"})


def tenant_of(i):
    # 0..49% → 1, 50..74 → 2, 75..89 → 3, 90..99 → 4
    p = i % 100
    return 1 if p < 50 else 2 if p < 75 else 3 if p < 90 else 4


def load(n):
    create()
    b = 500
    t0 = time.time()
    for s in range(0, n, b):
        v = vec(min(b, n - s))
        pts = []
        for j, x in enumerate(v):
            i = s + j
            t = tenant_of(i)
            pts.append({"id": i + 1, "vector": {"dense": x.tolist()},
                        "payload": {"tenant_id": t, "dataset_id": t * 100000 + int(rng.integers(0, DS_PER_TENANT)),
                                    "model": "bge-m3", "seq": i}})
        req("PUT", f"/collections/{COL}/points?wait=true", {"points": pts})
    print(f"loaded {n} in {time.time()-t0:.0f}s")
    # 색인 완료(green) 대기
    while True:
        info = req("GET", f"/collections/{COL}")["result"]
        if info["status"] == "green" and info.get("indexed_vectors_count", 0) >= n * 0.95:
            break
        time.sleep(3)
    print("indexed", info["indexed_vectors_count"], "points", info["points_count"])


def bench():
    qs = vec(60)
    cases = {
        # 테넌트 1 의 데이터셋 중 일부만 볼 수 있다 — 선택도 ≈ 전체의 0.5%, 1%, 12%, 45%
        "sel~0.5%": (1, 40), "sel~1%": (1, 80), "sel~12%": (1, 1000), "sel~45%": (1, 3600),
        "tenant4 sel~1%": (4, 400),
    }
    for name, (t, k) in cases.items():
        ids = [t * 100000 + d for d in rng.choice(DS_PER_TENANT, k, replace=False).tolist()]
        flt = {"must": [{"key": "tenant_id", "match": {"value": t}},
                        {"key": "model", "match": {"value": "bge-m3"}},
                        {"key": "dataset_id", "match": {"any": ids}}]}
        rec, lat, lat_exact = [], [], []
        for q in qs:
            body = {"query": q.tolist(), "using": "dense", "limit": 10, "filter": flt,
                    "params": {"hnsw_ef": 128, "quantization": {"rescore": True, "oversampling": 2.0}}}
            t0 = time.perf_counter(); a = req("POST", f"/collections/{COL}/points/query", body)
            lat.append((time.perf_counter() - t0) * 1000)
            body["params"] = {"exact": True}
            t0 = time.perf_counter(); e = req("POST", f"/collections/{COL}/points/query", body)
            lat_exact.append((time.perf_counter() - t0) * 1000)
            ga = {p["id"] for p in a["result"]["points"]}
            ge = {p["id"] for p in e["result"]["points"]}
            if ge:
                rec.append(len(ga & ge) / len(ge))
        cnt = req("POST", f"/collections/{COL}/points/count", {"filter": flt, "exact": True})["result"]["count"]
        print(json.dumps({"case": name, "matching": cnt, "datasets": k,
                          "recall@10": round(float(np.mean(rec)), 4), "recall_min": round(float(np.min(rec)), 2),
                          "p50_ms": round(float(np.percentile(lat, 50)), 1),
                          "p95_ms": round(float(np.percentile(lat, 95)), 1),
                          "exact_p50_ms": round(float(np.percentile(lat_exact, 50)), 1)}, ensure_ascii=False))


if __name__ == "__main__":
    if sys.argv[1] == "load":
        load(int(sys.argv[2]))
    else:
        bench()
