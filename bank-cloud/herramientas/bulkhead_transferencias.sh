#!/bin/bash
# Uso: echo <token cliente> | bash bulkhead_transf.sh <n> <monto>
# n transferencias simultaneas desde la cuenta del token hacia la 107, cada una
# con su propia Idempotency-Key. Compartimento msTransferencias: 5.
T=$(grep -E '^ey' | tr -d '\015')
N=${1:-15}; MONTO=${2:-100}
echo "== $N transferencias simultaneas de \$$MONTO"
seq "$N" | xargs -P "$N" -I{} sh -c "curl -sk -o /dev/null -w '%{http_code} %{time_total}\n' -X POST \
    -H 'Authorization: Bearer $T' -H 'Content-Type: application/json' \
    -H \"Idempotency-Key: \$(cat /proc/sys/kernel/random/uuid)\" \
    --data '{\"cuentaDestino\":107,\"monto\":$MONTO}' https://localhost:8081/api/web/transferencias" \
  | awk '{c[$1]++; if ($2>m) m=$2; s+=$2}
         END {for (k in c) printf "  HTTP %s x%d\n", k, c[k];
              printf "  tiempo promedio %.2f s, maximo %.2f s\n", s/NR, m}'
echo "== circuito msTransferencias de bff-web"
curl -sk https://localhost:8081/actuator/health | grep -o '"msTransferencias":{[^}]*}[^}]*}'
