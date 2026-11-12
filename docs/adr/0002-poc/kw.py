"""WD-716 PoC 항목 1 — 한국어 키워드 품질: Nori·음절 바이그램 sparse(Qdrant idf) 대 pg_trgm word_similarity.

말뭉치: KorQuAD 1.0 학습+검증 문단(제목+본문, 약 1만 건 — 문서 청크 규모).
질의(직접 만든 질의 세트, 정답 = 문서 id 집합):
  Q  KorQuAD 질문(자연어 문장) → 그 문단
  K  문단에서 고른 드문 낱말(한글 4~8음절, 말뭉치에서 3건 이하) → 그 낱말을 가진 문단
  P  K 의 부분 일치(앞/뒤 3음절 이상)
  S  K 의 띄어쓰기 변형(복합어 가운데 띄움)
  T  K 의 오타(가운데 음절 하나를 다른 음절로)
pg_trgm 은 앱과 같은 쿼리(`%>` + word_similarity 정렬, 임계값 0.1)로 돈다.

사용: python kw.py prep | load | eval
"""
import hashlib
import json
import math
import random
import re
import subprocess
import sys
import time
from collections import Counter

import psycopg
import requests

U = "http://localhost:16333"
H = {"api-key": "poc-key"}
COL = "kw_poc"
PG = "host=localhost port=5433 dbname=poc_kw user=app password=app"
NORI_DIR = "nori"
random.seed(11)
HANGUL = re.compile(r"[가-힣]{4,10}")
PARTICLE = re.compile(r"(으로|에서|에게|까지|부터|이다|였다|하였|했다|은|는|이|가|을|를|의|에|와|과|도|로|만)$")


def one_line(s):
    return re.sub(r"\s+", " ", s).strip()


def nori(texts, mode="MIXED"):
    """JVM Lucene Nori 로 토큰화 — 한 번에 묶어 보낸다."""
    p = subprocess.run(["java", "-cp", "lib/*", "Tok.java", mode], cwd=NORI_DIR,
                       input="\n".join(one_line(t) for t in texts) + "\n", capture_output=True, text=True, check=True)
    lines = p.stdout.split("\n")
    return [l.split() for l in lines[: len(texts)]]


def bigrams(text):
    """음절 바이그램 — 낱말(공백·기호로 자른 덩어리) 안에서만 만든다. 한 음절 낱말은 그대로."""
    out = []
    for w in re.findall(r"[0-9a-z가-힣]+", text.lower()):
        out += [w] if len(w) == 1 else [w[i:i + 2] for i in range(len(w) - 1)]
    return out


def hid(tok):
    return int.from_bytes(hashlib.md5(tok.encode()).digest()[:4], "little")


def bm25_sparse(tokens, avgdl, k1=1.2, b=0.75):
    """문서 쪽 값 = BM25 의 TF 포화 항. IDF 는 Qdrant(modifier idf)가 곱한다."""
    tf = Counter(hid(t) for t in tokens)
    dl = len(tokens) or 1
    idx = list(tf)
    val = [tf[i] * (k1 + 1) / (tf[i] + k1 * (1 - b + b * dl / avgdl)) for i in idx]
    return {"indices": idx, "values": val}


def query_sparse(tokens):
    tf = Counter(hid(t) for t in tokens)
    return {"indices": list(tf), "values": [1.0] * len(tf)}


def prep():
    docs, qs = [], []
    for f in ("korquad_train.json", "korquad_dev.json"):
        for art in json.load(open(f))["data"]:
            for p in art["paragraphs"]:
                did = len(docs) + 1
                docs.append({"id": did, "text": one_line(art["title"] + " " + p["context"])})
                if f.endswith("dev.json"):
                    for qa in p["qas"]:
                        qs.append({"type": "Q", "q": qa["question"], "rel": [did]})
    random.shuffle(qs)
    qs = qs[:300]
    # 드문 낱말 후보 — 조사를 떼고 4음절 이상, 말뭉치 전체에서 1~3건에만 나오는 것
    alltext = [d["text"] for d in docs]
    picks = []
    for d in random.sample(docs, 1500):
        words = [PARTICLE.sub("", w) for w in HANGUL.findall(d["text"])]
        words = [w for w in words if 4 <= len(w) <= 8]
        random.shuffle(words)
        for w in words[:3]:
            rel = [x["id"] for x, t in zip(docs, alltext) if w in t]
            if 1 <= len(rel) <= 3:
                picks.append((w, rel))
                break
        if len(picks) >= 300:
            break
    for w, rel in picks:
        qs.append({"type": "K", "q": w, "rel": rel})
        part = w[: max(3, len(w) * 2 // 3)] if random.random() < 0.5 else w[-max(3, len(w) * 2 // 3):]
        qs.append({"type": "P", "q": part, "rel": rel})
        m = len(w) // 2
        qs.append({"type": "S", "q": w[:m] + " " + w[m:], "rel": rel})
        i = random.randrange(1, len(w) - 1)
        typo = chr(0xAC00 + (ord(w[i]) - 0xAC00 + random.choice([28, -28, 1, -1, 588])) % 11172)
        qs.append({"type": "T", "q": w[:i] + typo + w[i + 1:], "rel": rel})
    json.dump(docs, open("kw_docs.json", "w"), ensure_ascii=False)
    json.dump(qs, open("kw_queries.json", "w"), ensure_ascii=False)
    print(len(docs), "docs", Counter(q["type"] for q in qs))


def load():
    docs = json.load(open("kw_docs.json"))
    t0 = time.time()
    toks = nori([d["text"] for d in docs])
    print(f"nori {len(docs)} docs {time.time()-t0:.1f}s")
    bgs = [bigrams(d["text"]) for d in docs]
    av_n = sum(map(len, toks)) / len(toks)
    av_b = sum(map(len, bgs)) / len(bgs)
    requests.delete(f"{U}/collections/{COL}", headers=H)
    requests.put(f"{U}/collections/{COL}", headers=H, json={
        "shard_number": 1,
        "sparse_vectors": {"kw_nori": {"modifier": "idf"}, "kw_bigram": {"modifier": "idf"},
                           "kw_mix": {"modifier": "idf"}}}).raise_for_status()
    mix = [["n:" + t for t in n] + ["b:" + t for t in b] for n, b in zip(toks, bgs)]
    av_m = sum(map(len, mix)) / len(mix)
    for s in range(0, len(docs), 500):
        pts = [{"id": d["id"], "vector": {"kw_nori": bm25_sparse(n, av_n), "kw_bigram": bm25_sparse(b, av_b),
                                          "kw_mix": bm25_sparse(m, av_m)}}
               for d, n, b, m in zip(docs[s:s + 500], toks[s:s + 500], bgs[s:s + 500], mix[s:s + 500])]
        requests.put(f"{U}/collections/{COL}/points?wait=true", headers=H, json={"points": pts}).raise_for_status()
    if "--no-pg" in sys.argv:
        print("loaded qdrant only"); return
    with psycopg.connect(PG) as c:
        c.execute("TRUNCATE doc")
        with c.cursor().copy("COPY doc(id, source_text) FROM STDIN") as cp:
            for d in docs:
                cp.write_row((d["id"], d["text"]))
    print("loaded", len(docs))


def qd(vec_name, sparse, k=10):
    r = requests.post(f"{U}/collections/{COL}/points/query", headers=H,
                      json={"query": sparse, "using": vec_name, "limit": k})
    r.raise_for_status()
    return [p["id"] for p in r.json()["result"]["points"]]


def rrf(lists, weights, k=60, top=10):
    sc = Counter()
    for l, w in zip(lists, weights):
        for r, i in enumerate(l):
            sc[i] += w / (k + r + 1)
    return [i for i, _ in sc.most_common(top)]


def evaluate():
    qs = json.load(open("kw_queries.json"))
    ntoks = nori([q["q"] for q in qs])
    # 원래 낱말(K)이 Nori 기준 명사로만 이뤄졌는지 — 데이터셋 검색어는 대개 명사라 이 부분집합을 따로 본다
    import os
    kw = [q for q in qs if q["type"] == "K"]
    disc = nori([q["q"] for q in kw], "DISCARD")
    noun_words = {q["q"] for q, t in zip(kw, disc) if "".join(t) == q["q"]}
    for j, q in enumerate(qs):
        if q["type"] == "Q":
            q["noun"] = None
    kq = [q for q in qs if q["type"] != "Q"]
    for i in range(0, len(kq), 4):  # prep 이 K,P,S,T 순서로 넣었다
        flag = kq[i]["q"] in noun_words
        for q in kq[i:i + 4]:
            q["noun"] = flag
    cache = json.load(open("pg_cache.json")) if os.path.exists("pg_cache.json") else {}
    systems = {}
    lat = Counter()
    with psycopg.connect(PG) as c:
        res = {}
        for q, nt in zip(qs, ntoks):
            if q["q"] not in cache:
                t0 = time.perf_counter()
                with c.transaction():
                    c.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.1")
                    rows = c.execute("SELECT id FROM doc WHERE source_text %%> %s ORDER BY word_similarity(%s, source_text) DESC LIMIT 10",
                                     (q["q"], q["q"])).fetchall()
                cache[q["q"]] = {"ids": [x[0] for x in rows], "ms": (time.perf_counter() - t0) * 1000}
            lat["pg_trgm"] += cache[q["q"]]["ms"] / 1000
            r = {"pg_trgm": cache[q["q"]]["ids"]}
            t0 = time.perf_counter()
            r["nori"] = qd("kw_nori", query_sparse(nt), 30) if nt else []
            r["bigram"] = qd("kw_bigram", query_sparse(bigrams(q["q"])), 30)
            r["mix(1 vector)"] = qd("kw_mix", query_sparse(["n:" + t for t in nt] + ["b:" + t for t in bigrams(q["q"])]), 10)
            lat["qdrant(2 queries)"] += time.perf_counter() - t0
            for w in (0.2, 0.3, 0.5):
                r[f"rrf nori:{w}"] = rrf([r["nori"], r["bigram"]], [w, 1 - w])
            r["nori"], r["bigram"] = r["nori"][:10], r["bigram"][:10]
            res[id(q)] = r
    json.dump(cache, open("pg_cache.json", "w"), ensure_ascii=False)
    names = list(next(iter(res.values())).keys())
    qs_all = qs
    types = ["Q", "K", "P", "S", "T"]
    for label, keep in (("전체", lambda q: True), ("명사 낱말만(K/P/S/T)", lambda q: q["type"] == "Q" or q["noun"])):
      qs = [q for q in qs_all if keep(q)]
      print(f"\n[{label}] n={len(qs)}  success@10 / MRR@10")
      print(f"{'system':16}" + "".join(f"{t:>14}" for t in types + ["ALL"]))
      for n in names:
        cells = []
        for t in types + ["ALL"]:
            sel = [q for q in qs if t == "ALL" or q["type"] == t]
            hit, mrr = 0, 0.0
            for q in sel:
                l = res[id(q)][n]
                ranks = [l.index(x) + 1 for x in q["rel"] if x in l]
                if ranks:
                    hit += 1
                    mrr += 1 / min(ranks)
            cells.append(f"{hit/len(sel):.3f}/{mrr/len(sel):.2f}")
        print(f"{n:16}" + "".join(f"{c:>14}" for c in cells))
    qs = qs_all
    print({k: f"{v/len(qs)*1000:.1f}ms/q" for k, v in lat.items()})
    # 사례 몇 개 — 판정 근거로 남긴다
    for t in ("S", "T", "P"):
        for q in [q for q in qs if q["type"] == t][:3]:
            r = res[id(q)]
            print(t, q["q"], {n: (q["rel"][0] in r[n]) for n in ("pg_trgm", "nori", "bigram", "rrf nori:0.5")})


if __name__ == "__main__":
    {"prep": prep, "load": load, "eval": evaluate}[sys.argv[1]]()
