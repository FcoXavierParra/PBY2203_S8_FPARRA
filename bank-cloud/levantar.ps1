# ---------------------------------------------------------------------------
# Levanta el ecosistema completo: diez procesos, por etapas, esperando a cada
# uno por su endpoint de salud.
#
# Uso:
#   .\levantar.ps1                        # todo, contra H2
#   .\levantar.ps1 -Oracle                # ms-cuentas contra Autonomous Database
#   .\levantar.ps1 -Detener               # baja todo
#   .\levantar.ps1 -Servicio ms-auditoria            # arranca solo ese
#   .\levantar.ps1 -Servicio ms-auditoria -Detener   # detiene solo ese
#
# LAS ETAPAS
# ----------
#   1. config-server 7888, eureka-server 8761     no dependen de nadie
#   2. broker-mensajeria 8161 / 61616             pide su config al 7888
#   3. ms-cuentas 8090                            carga el dataset en la base
#   4. ms-cuentas-2 8093, ms-transferencias 8091, ms-auditoria 8092
#   5. bff-web 8081, bff-movil 8082, bff-cajero 8083
#
# Dentro de una etapa los servicios arrancan EN PARALELO y el script espera a
# que respondan todos antes de pasar a la siguiente. En la semana 6 el
# arranque era estrictamente uno por uno; con diez procesos y un equipo donde
# cada JVM tarda uno o dos minutos, eso eran veinte minutos.
#
# ms-cuentas-2 va en la etapa 4 y no junto a ms-cuentas por una razon
# concreta: los dos comparten la base, y la carga del dataset solo ocurre si
# la tabla esta vacia. Si arrancaran a la vez, los dos la verian vacia y los
# dos cargarian.
#
# QUE CAMBIO RESPECTO DE LA SEMANA 6
# ----------------------------------
# El orden ya NO es obligatorio. Desde que spring-retry esta en los pom, un
# servicio que arranca antes que el Config Server lo espera hasta cuatro
# minutos en vez de morir al primer intento. Las etapas se mantienen porque
# ahorran reintentos y dejan un log limpio, no porque sin ellas algo falle.
# La evidencia lo comprueba arrancando servicios con el Config Server abajo.
# ---------------------------------------------------------------------------

param(
    [switch]$Detener,
    [switch]$Oracle,
    [string]$Servicio
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "comun.ps1")

$etapas = @(
    # config-server solo, antes que nadie: arrancando en paralelo con eureka
    # choco con "Port ... was already in use". Ver
    # Start-ServicioEsperando en comun.ps1.
    @("config-server"),
    @("eureka-server"),
    @("broker-mensajeria"),
    @("ms-cuentas"),
    @("ms-cuentas-2", "ms-transferencias", "ms-auditoria"),
    @("bff-web", "bff-movil", "bff-cajero")
)

function Get-ExtraOracle {
    param([string]$Nombre)
    if ($Oracle -and $Nombre -like "ms-cuentas*") { return @("--spring.profiles.active=oracle") }
    return @()
}

# ---------------------------------------------------------------------------
# Un solo servicio
# ---------------------------------------------------------------------------
if ($Servicio) {
    if (-not $Servicios.Contains($Servicio)) {
        throw ("Servicio desconocido: {0}. Validos: {1}" -f $Servicio, ($Servicios.Keys -join ", "))
    }
    if ($Detener) {
        if (Stop-Servicio $Servicio) { Write-Host "  detenido  $Servicio" } else { Write-Host "  $Servicio no estaba registrado" }
        exit 0
    }
    Write-Host ("  arrancando  {0}... " -f $Servicio) -NoNewline
    if (Start-ServicioEsperando -Nombre $Servicio -Extra (Get-ExtraOracle $Servicio)) { Write-Host "UP"; exit 0 }
    Write-Host "SIN RESPUESTA"; exit 1
}

# ---------------------------------------------------------------------------
# Detener todo
# ---------------------------------------------------------------------------
if ($Detener) {
    $registro = Get-Registro
    if ($registro.Count -eq 0) {
        Write-Host "No hay procesos registrados. Nada que detener."
        exit 0
    }
    foreach ($linea in $registro) {
        $partes = $linea -split ";"
        $p = Get-Process -Id ([int]$partes[0]) -ErrorAction SilentlyContinue
        if ($p) {
            Stop-Process -Id $p.Id -Force
            Write-Host ("  detenido  {0} (pid {1})" -f $partes[1], $p.Id)
        }
    }
    Set-Registro @()
    Write-Host "`nEcosistema detenido."
    exit 0
}

# ---------------------------------------------------------------------------
# Comprobaciones previas
# ---------------------------------------------------------------------------
foreach ($nombre in $Servicios.Keys) {
    $jar = Get-RutaJar $Servicios[$nombre].jar
    if (-not (Test-Path $jar)) {
        throw ("Falta {0}. Ejecuta primero:  mvn clean package -DskipTests" -f $jar)
    }
}

if ($Oracle) {
    foreach ($v in @("ORACLE_JDBC_URL", "ORACLE_USER", "ORACLE_PASSWORD")) {
        if (-not (Get-Item -Path ("Env:" + $v) -ErrorAction SilentlyContinue)) {
            throw ("Falta la variable de entorno {0}. Ver config-repo\ms-cuentas-oracle.yml." -f $v)
        }
    }
}

$certificados = @("bff-web", "bff-movil", "bff-cajero") | ForEach-Object { Join-Path $raiz "certs\$_.p12" }
if ($certificados | Where-Object { -not (Test-Path $_) }) {
    Write-Host "Faltan certificados. Generandolos...`n"
    & (Join-Path $raiz "generar_certificados.ps1")
}

if ((Get-Registro).Count -gt 0) {
    Write-Host "Hay una corrida anterior registrada. Deteniendola primero...`n"
    & $PSCommandPath -Detener | Out-Null
}

# Estado de partida limpio: las tres bases H2 y el journal del broker. Asi
# cada corrida parte del dataset oficial, sin transferencias previas y sin
# mensajes pendientes de la anterior. Contra Oracle la base no se toca, pero
# las de ms-transferencias y ms-auditoria si, porque son siempre H2.
if ($Oracle) {
    Get-ChildItem (Join-Path $raiz "basedatos") -Filter "*.db" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "bank.*" } | Remove-Item -Force
} else {
    $baseDatos = Join-Path $raiz "basedatos"
    if (Test-Path $baseDatos) { Remove-Item $baseDatos -Recurse -Force }
}
$datosBroker = Join-Path $raiz "datos-broker"
if (Test-Path $datosBroker) { Remove-Item $datosBroker -Recurse -Force }

# ---------------------------------------------------------------------------
# Arranque por etapas
# ---------------------------------------------------------------------------
$inicio = Get-Date
$numero = 0
foreach ($etapa in $etapas) {
    $numero++
    Write-Host ("Etapa {0}: {1}" -f $numero, ($etapa -join ", "))

    $procesos = @{}
    foreach ($nombre in $etapa) {
        $procesos[$nombre] = Start-Servicio -Nombre $nombre -Extra (Get-ExtraOracle $nombre)
        Write-Host ("  arrancando  {0} (pid {1})" -f $nombre, $procesos[$nombre].Id)
    }
    foreach ($nombre in $etapa) {
        Write-Host ("  esperando   {0}... " -f $nombre) -NoNewline
        $ok = Wait-Salud -Url $Servicios[$nombre].salud -Proceso $procesos[$nombre]
        if (-not $ok) {
            # Murio antes de responder. Si fue por el puerto tomado de paso
            # (ver Start-ServicioEsperando en comun.ps1), se reintenta solo.
            $log = Join-Path $logs "$nombre.log"
            if ((Test-Path $log) -and (Select-String -Path $log -Pattern "already in use" -Quiet)) {
                Stop-Servicio $nombre | Out-Null
                $ok = [bool](Start-ServicioEsperando -Nombre $nombre -Extra (Get-ExtraOracle $nombre))
            }
        }
        if ($ok) {
            Write-Host "UP"
        } else {
            Write-Host "SIN RESPUESTA"
            Write-Host ("`nRevisa {0}" -f (Join-Path $logs "$nombre.log"))
            Write-Host "Bajando lo que alcanzo a quedar arriba..."
            & $PSCommandPath -Detener | Out-Null
            exit 1
        }
    }
}

Write-Host ("`nEcosistema arriba en {0:N0} s:" -f ((Get-Date) - $inicio).TotalSeconds)
Write-Host "  Config Server      http://localhost:7888/ms-cuentas/default"
Write-Host "  Eureka             http://localhost:8761"
Write-Host "  Broker (consola)   http://localhost:8161/admin/topologia   (admin-broker)"
Write-Host "  ms-cuentas         http://localhost:8090  y  http://localhost:8093"
Write-Host "  ms-transferencias  http://localhost:8091/interno/transferencias/outbox"
Write-Host "  ms-auditoria       http://localhost:8092/interno/auditoria/resumen"
Write-Host "  bff-web            https://localhost:8081/api/web"
Write-Host "  bff-movil          https://localhost:8082/api/movil"
Write-Host "  bff-cajero         https://localhost:8083/api/cajero"
Write-Host "`nEvidencia:  .\probar_eventos.ps1   (semana 7)"
Write-Host "            .\comparar_canales.ps1 (semana 6, regresion)"
Write-Host "Detener:    .\levantar.ps1 -Detener"
