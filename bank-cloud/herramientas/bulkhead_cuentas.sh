#!/bin/bash
# Uso: echo <token> | bash bulkhead.sh <url> <n1> <n2> ...
# Dispara n peticiones simultaneas y cuenta los codigos HTTP y los tiempos.
T=$(grep -E '^ey' | tr -d '\015')
URL=$1; shift
for n in "$@"; do
  echo "== $n en paralelo -> $URL"
  seq "$n" | xargs -P "$n" -I{} curl -sk -o /dev/null -w '%{http_code} %{time_total}\n' \
      -H "Authorization: Bearer $T" "$URL" \
    | awk '{c[$1]++; if ($2>m) m=$2; s+=$2}
           END {for (k in c) printf "  HTTP %s x%d\n", k, c[k];
                printf "  tiempo promedio %.2f s, maximo %.2f s\n", s/NR, m}'
  # el circuito queda 10 s abierto si algo fallo: no mezclar una ronda con la siguiente
  sleep 12
done
echo "== /actuator/bulkheadevents (acumulado)"
curl -sk -H "Authorization: Bearer $T" https://localhost:8081/actuator/bulkheadevents \
  | grep -o '"bulkheadName":"[A-Za-z]*","type":"[A-Z_]*"' | sort | uniq -c
echo "== circuito msCuentas de bff-web"
curl -sk https://localhost:8081/actuator/health | grep -o '"msCuentas":{[^}]*}[^}]*}'
