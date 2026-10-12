#!/usr/bin/env bash
# End-to-end smoke test of the demo path against a running app on :8081.
set -euo pipefail
BASE=${BASE:-http://localhost:8081}
CUST=1001
OTHER=2002

post() { curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Customer-Id: $2" -d "$3"; }

echo "== 1) 只读：查订单 =="
CID=$(post /api/assistant/conversations "$CUST" '{}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
echo "   会话 id=$CID"
post "/api/assistant/conversations/$CID/messages" "$CUST" '{"message":"我有哪些订单？"}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("   reply:",d["reply"][:80]);print("   tokens:",d["promptTokens"],d["completionTokens"])'

echo "== 2) 变更：只出卡片，不执行 =="
post "/api/assistant/conversations/$CID/messages" "$CUST" '{"message":"帮我下单 2 个 DOODAD-04"}' > /tmp/smoke.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/smoke.json'))['data']
print("   cards:", [(a['summary'], a['status']) for a in d['actions']])
open('/tmp/smoke.token', 'w').write(d['actions'][0]['token'])
PY
TOKEN=$(cat /tmp/smoke.token)
BEFORE=$(curl -s "$BASE/api/orders?page=1&size=50" -H "X-Customer-Id: $CUST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
echo "   确认前订单数: $BEFORE"

echo "== 3) 确认 =="
curl -s -X POST "$BASE/api/assistant/actions/$TOKEN/confirm" -H "X-Customer-Id: $CUST" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("   status:",d["status"],"replayed:",d["replayed"]);print("   result:",d["resultJson"][:90])'
AFTER=$(curl -s "$BASE/api/orders?page=1&size=50" -H "X-Customer-Id: $CUST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
echo "   确认后订单数: $AFTER"

echo "== 4) 重复确认（应 replayed 且不新增） =="
curl -s -X POST "$BASE/api/assistant/actions/$TOKEN/confirm" -H "X-Customer-Id: $CUST" \
  | python3 -c 'import sys,json;print("   replayed:",json.load(sys.stdin)["data"]["replayed"])'
STILL=$(curl -s "$BASE/api/orders?page=1&size=50" -H "X-Customer-Id: $CUST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
echo "   订单数: $STILL"

echo "== 5) 越权 =="
curl -s -o /dev/null -w '   2002 用 1001 的 token 确认: HTTP %{http_code}\n' \
  -X POST "$BASE/api/assistant/actions/$TOKEN/confirm" -H "X-Customer-Id: $OTHER"
curl -s "$BASE/api/orders" -H "X-Customer-Id: $OTHER" \
  | python3 -c 'import sys,json;print("   2002 看到的订单数:",json.load(sys.stdin)["data"]["total"])'

echo "== 6) 记忆 =="
post "/api/assistant/conversations/$CID/messages" "$CUST" '{"message":"我刚才要下单几个 DOODAD-04？只回答数字。"}' \
  | python3 -c 'import sys,json;print("   reply:",repr(json.load(sys.stdin)["data"]["reply"]))'
