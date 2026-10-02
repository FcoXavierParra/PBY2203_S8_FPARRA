#!/bin/bash
# Lo ejecuta evidencia_nube.ps1 dentro de la EC2. Muestra sobre que corre la
# evidencia: la maquina, las imagenes, los contenedores, el registro de Eureka
# y la configuracion del despliegue (sin claves).
cd ~/bank-cloud || exit 1
echo "-- maquina"
echo "  $(. /etc/os-release; echo "$PRETTY_NAME"), $(nproc) vCPU, $(free -h | awk '/Mem/ {print $2" RAM, "$3" en uso"}')"
echo "  $(docker --version)"
echo "  $(docker compose version)"
echo
echo "-- configuracion del despliegue (.env, sin claves)"
grep -E '^(PERFIL_BD|ORACLE_JDBC_URL|CUENTAS_REPLICAS|TRANSF_[A-Z_]+)=' .env | sed 's/^/  /'
echo
echo "-- imagenes (una por servicio, mismo Dockerfile multi-etapa)"
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep bank-cloud | sort
echo
echo "-- contenedores"
docker compose ps -a --format '  {{.Name}}\t{{.Status}}' | sort
echo
echo "-- memoria por contenedor (uso / limite del compose)"
docker stats --no-stream --format '  {{.Name}}\t{{.MemUsage}}' | sort
echo
echo "-- Eureka: instancias registradas"
curl -s -H 'Accept: application/json' http://127.0.0.1:8761/eureka/apps \
  | grep -oE '"(app|instanceId|status)":"[^"]*"' \
  | awk -F'"' '/"app"/ {app=$4} /"instanceId"/ {id=$4} /"status"/ && id {printf "  %-18s %-45s %s\n", app, id, $4; id=""}'
