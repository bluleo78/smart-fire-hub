"""WD-716 PoC 항목 3 — 노드 추가 뒤 샤드 수동 이동(move_shard), 이동 중 검색 가용성·이동 뒤 개수 확인."""
import time, requests, numpy as np
U="http://localhost:16333"; H={"api-key":"poc-key"}; C="dataset_dense_1024_poc"
peers=requests.get(U+"/cluster",headers=H).json()["result"]
me=peers["peer_id"]; other=[int(p) for p in peers["peers"] if int(p)!=me][0]
t0=time.time()
for sid in (3,4,5):
    r=requests.post(f"{U}/collections/{C}/cluster",headers=H,json={"move_shard":{"shard_id":sid,"from_peer_id":me,"to_peer_id":other,"method":"stream_records"}})
    print("move",sid,r.json().get("status"))
rng=np.random.default_rng(1); errs=0; n=0
while True:
    q=rng.normal(size=1024).tolist()
    r=requests.post(f"{U}/collections/{C}/points/query",headers=H,json={"query":q,"using":"dense","limit":10,"filter":{"must":[{"key":"tenant_id","match":{"value":1}}]}})
    n+=1; errs+= r.status_code!=200 or len(r.json()["result"]["points"])!=10
    ci=requests.get(f"{U}/collections/{C}/cluster",headers=H).json()["result"]
    if not ci["shard_transfers"]: break
    time.sleep(0.5)
print(f"transfer done {time.time()-t0:.0f}s, queries during move {n}, failed {errs}")
print("local",[(s['shard_id'],s['points_count'],s['state']) for s in ci['local_shards']])
print("remote",[(s['shard_id'],s['peer_id'],s['state']) for s in ci['remote_shards']])
print("count",requests.post(f"{U}/collections/{C}/points/count",headers=H,json={"exact":True}).json()["result"])
