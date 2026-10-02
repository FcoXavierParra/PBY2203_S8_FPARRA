# ---------------------------------------------------------------------------
# Evidencia de la semana 8. Genera ..\evidencias\01_despliegue_ec2_oracle.txt
#
# Uso, con el docker-compose.yaml arriba en la EC2 (ver README):
#   .\evidencia_nube.ps1 -Servidor <ip publica> -Llave C:\ruta\llave.pem
#
# QUE DEMUESTRA
# -------------
#    0. Sobre que corre: la EC2, las imagenes, los contenedores, Eureka
#    1. OAuth 2.0: el auth-server y sus dos flujos, con los rechazos
#    2. Los tres canales protegidos con tokens del auth-server
#    3. Latencia de la nube: el Circuit Breaker del cajero sigue cerrado
#    4. Bulkhead hacia ms-cuentas: la sobrecarga se rechaza y el circuito
#       no se abre por ella
#    5. Bulkhead hacia ms-transferencias: tope de 5, y la saga cuadra
#    6. Falla real: ms-transferencias se detiene; el circuito abre, el canal
#       degrada y, cuando el servicio vuelve, el circuito cierra solo
#    7. Falla real: se detiene una replica de ms-cuentas; la otra atiende las
#       consultas y la saga, y la detenida se vuelve a registrar en Eureka
#
# Las secciones 6 y 7 DETIENEN contenedores reales y los vuelven a levantar.
#
# Todo se pide desde ESTE equipo a la IP publica, como lo haria un cliente,
# salvo las rafagas de las secciones 4 y 5: para que de verdad lleguen
# juntas se disparan dentro de la EC2 (herramientas/bulkhead_*.sh). Desde
# aqui, cada peticion paga su propio handshake TLS a Virginia y se reparten
# en el tiempo.
#
# OJO: las secciones 2 y 5 mueven saldo REAL de la base (una transferencia de
# 1.000, un retiro de 10.000 de la cuenta con mas saldo y hasta 600 en
# transferencias de 100). Con Oracle la 105 parte en 10.050 y ninguna cuenta
# pasa de 10.150: cada corrida agota el saldo de una cuenta para el cajero.
# ---------------------------------------------------------------------------
param(
    [Parameter(Mandatory = $true)][string]$Servidor,
    [Parameter(Mandatory = $true)][string]$Llave,
    [string]$Salida = (Join-Path $PSScriptRoot "..\evidencias\01_despliegue_ec2_oracle.txt")
)
$ErrorActionPreference = "Continue"
$Auth = "http://${Servidor}:9000"
. (Join-Path $PSScriptRoot "herramientas\oauth_funciones.ps1") -Auth $Auth
$ssh = Join-Path $env:SystemRoot "System32\OpenSSH\ssh.exe"
$scp = Join-Path $env:SystemRoot "System32\OpenSSH\scp.exe"
$vm = "ubuntu@$Servidor"

function EnVm([string]$comando, [string]$entrada) {
    # La entrada (un token) viaja por stdin y no por la linea de comandos. Se
    # antepone una linea vacia: PowerShell 5.1 agrega un BOM al hacer pipe a
    # un ejecutable y estropearia la primera linea.
    if ($entrada) { "`n$entrada`n" | & $ssh -i $Llave $vm $comando }
    else { & $ssh -i $Llave $vm $comando }
}
function Llamar([string]$Metodo, [string]$Url, [string]$Token, [hashtable]$Cabeceras = @{}, [string]$Cuerpo) {
    $a = @("-sk", "-m", "20", "-o", "$tmp\resp.txt", "-w", "%{http_code}", "-X", $Metodo)
    if ($Token) { $a += @("-H", "Authorization: Bearer $Token") }
    foreach ($c in $Cabeceras.Keys) { $a += @("-H", "${c}: $($Cabeceras[$c])") }
    if ($Cuerpo) { [IO.File]::WriteAllText("$tmp\body.json", $Cuerpo); $a += @("-H", "Content-Type: application/json", "--data-binary", "@$tmp\body.json") }
    $codigo = & $curl @a $Url
    $txt = if (Test-Path "$tmp\resp.txt") { Get-Content "$tmp\resp.txt" -Raw } else { "" }
    @{ codigo = $codigo; cuerpo = $txt }
}
function Corto([string]$s, [int]$n = 150) { if (-not $s) { return "" }; $s = $s -replace '\s+', ' '; if ($s.Length -gt $n) { $s.Substring(0, $n) + "..." } else { $s } }
function Titulo([string]$t) { ""; "=" * 78; $t; "=" * 78 }
function Saldo([string]$tok) { (& $curl -sk -H "Authorization: Bearer $tok" "https://${Servidor}:8081/api/web/cuentas/105" | ConvertFrom-Json).saldo }
function Transferir([string]$tok, [int]$monto) {
    $s = Get-Date
    $r = Llamar POST "https://${Servidor}:8081/api/web/transferencias" $tok @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } ('{"cuentaDestino":107,"monto":' + $monto + '}')
    $r.segundos = ((Get-Date) - $s).TotalSeconds
    $r
}
# Estado de un circuito de bff-web, leido de su /actuator/health publico.
function Circuito([string]$nombre) {
    $d = (& $curl -sk "https://${Servidor}:8081/actuator/health" | ConvertFrom-Json).components.circuitBreakers.details.$nombre.details
    "{0,-9} (fallidas {1}, sin intentar {2})" -f $d.state, $d.failedCalls, $d.notPermittedCalls
}

$tmp = Join-Path $env:TEMP "evidencia_s8"
New-Item -ItemType Directory -Force $tmp | Out-Null
$web = "https://${Servidor}:8081/api/web"
$movil = "https://${Servidor}:8082/api/movil"
$cajero = "https://${Servidor}:8083/api/cajero"

$cuerpo = {
    "Evidencia S8 - despliegue en AWS EC2 con Oracle Autonomous Database"
    "Generada: {0}   Servidor: {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Servidor

    # ---------------------------------------------------------------------
    Titulo "0. Sobre que corre"
    & $scp -q -i $Llave (Join-Path $PSScriptRoot "herramientas\contexto_ec2.sh") `
        (Join-Path $PSScriptRoot "herramientas\bulkhead_cuentas.sh") `
        (Join-Path $PSScriptRoot "herramientas\bulkhead_transferencias.sh") "${vm}:"
    EnVm "bash ~/contexto_ec2.sh"

    # ---------------------------------------------------------------------
    Titulo "1. OAuth 2.0: auth-server (Spring Authorization Server, RS256)"
    "-- 1.1 metadatos publicados"
    $m = & $curl -s "$Auth/.well-known/oauth-authorization-server" | ConvertFrom-Json
    "  issuer={0}`n  grant_types={1}`n  pkce={2}" -f $m.issuer, ($m.grant_types_supported -join ','), ($m.code_challenge_methods_supported -join ',')
    "-- 1.2 client_credentials: bff-web pide un token para ms-cuentas"
    $t = TokenMaquina "bff-web" "bff-web-desarrollo-2026" "cuentas.leer"
    $c = Carga (($t.cuerpo | ConvertFrom-Json).access_token)
    "  HTTP {0}  sub={1} aud={2} scope={3} dura={4}s" -f $t.codigo, $c.sub, ($c.aud -join ','), ($c.scope -join ','), ($c.exp - $c.iat)
    "-- 1.3 dos scopes de dos microservicios: el token va dirigido a ambos"
    $t = TokenMaquina "bff-web" "bff-web-desarrollo-2026" "cuentas.leer transferencias.crear"
    $c = Carga (($t.cuerpo | ConvertFrom-Json).access_token)
    "  HTTP {0}  aud={1} scope={2}" -f $t.codigo, ($c.aud -join ','), ($c.scope -join ',')
    "-- 1.4 secreto equivocado"
    $t = TokenMaquina "bff-web" "otro" "cuentas.leer"; "  HTTP {0}  {1}" -f $t.codigo, $t.cuerpo
    "-- 1.5 bff-movil pide cuentas.operar, que no tiene asignado"
    $t = TokenMaquina "bff-movil" "bff-movil-desarrollo-2026" "cuentas.operar"; "  HTTP {0}  {1}" -f $t.codigo, $t.cuerpo
    "-- 1.6 authorization_code + PKCE: una persona entra por el canal web"
    $p = TokenPersona "canal-web" "cliente" "cliente123"
    "  redireccion -> {0}" -f ($p.redireccion -replace 'code=[^&]*', 'code=<...>')
    $c = Carga (($p.cuerpo | ConvertFrom-Json).access_token)
    "  HTTP {0}  sub={1} aud={2} canal={3} roles={4} cuenta={5} dura={6}s" -f $p.codigo, $c.sub, ($c.aud -join ','), $c.canal, ($c.roles -join ','), $c.cuenta, ($c.exp - $c.iat)
    "-- 1.7 el ejecutivo no esta habilitado en el canal movil"
    $p = TokenPersona "canal-movil" "ejecutivo" "ejecutivo123"; "  HTTP {0}  {1}" -f $p.codigo, $p.cuerpo
    "-- 1.8 codigo canjeado sin el verificador PKCE"
    $p = TokenPersona "canal-web" "cliente2" "cliente456" -SinVerificador; "  HTTP {0}" -f $p.codigo
    "-- 1.9 clave equivocada en el formulario de login"
    $p = TokenPersona "canal-web" "cliente" "mala"; "  tras login -> {0}   token HTTP {1}" -f ($p.tras_login -replace '^.*/login', '/login'), $p.codigo

    # ---------------------------------------------------------------------
    Titulo "2. Los tres canales, protegidos con tokens del auth-server"
    $tc = ((TokenPersona "canal-web" "cliente" "cliente123").cuerpo | ConvertFrom-Json).access_token
    $te = ((TokenPersona "canal-web" "ejecutivo" "ejecutivo123").cuerpo | ConvertFrom-Json).access_token
    "-- WEB (8081)"
    $r = Llamar GET "$web/cuentas/105" $null; "  sin token, cuenta 105            -> {0}" -f $r.codigo
    $r = Llamar GET "$web/cuentas/105" $tc;   "  cliente, SU cuenta 105           -> {0}  {1}" -f $r.codigo, (Corto $r.cuerpo)
    $r = Llamar GET "$web/cuentas/107" $tc;   "  cliente, cuenta AJENA 107        -> {0}" -f $r.codigo
    $r = Llamar GET "$web/cuentas" $tc;       "  cliente, cartera completa        -> {0}" -f $r.codigo
    $r = Llamar GET "$web/cuentas/107" $te;   "  ejecutivo, cuenta 107            -> {0}" -f $r.codigo
    $antes = Saldo $tc
    $r = Llamar POST "$web/transferencias" $tc @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } '{"cuentaDestino":107,"monto":1000}'
    "  cliente transfiere 1000 a 107    -> {0}  {1}" -f $r.codigo, (Corto $r.cuerpo)
    Start-Sleep -Seconds 8
    "  saldo de la 105 antes {0}, despues de la saga {1}" -f $antes, (Saldo $tc)
    "-- MOVIL (8082)"
    $tm = ((TokenPersona "canal-movil" "cliente" "cliente123").cuerpo | ConvertFrom-Json).access_token
    $r = Llamar GET "$movil/cuentas/105/resumen" $tm; "  cliente movil, SU cuenta         -> {0}  {1}" -f $r.codigo, (Corto $r.cuerpo)
    $r = Llamar GET "$movil/cuentas/107/resumen" $tm; "  cliente movil, cuenta ajena      -> {0}" -f $r.codigo
    $r = Llamar GET "$movil/cuentas/105/resumen" $tc; "  token WEB en el BFF movil        -> {0}" -f $r.codigo
    $r = Llamar GET "$web/cuentas/105" $tm;           "  token MOVIL en el BFF web        -> {0}" -f $r.codigo
    "-- CAJERO (8083): token de terminal (client_credentials) + PIN"
    # El retiro es en billetes de 10.000 y con Oracle ninguna cuenta pasa de
    # 10.150: se usa la de mayor saldo, que el ejecutivo ve en la cartera.
    $cc = (@(& $curl -sk -H "Authorization: Bearer $te" "$web/cuentas" | ConvertFrom-Json) | ForEach-Object { $_ } | Sort-Object saldo -Descending | Select-Object -First 1).cuentaId
    "  cuenta del cajero: $cc (la de mayor saldo)"
    $tt = ((TokenMaquina "cajero-terminal-01" "terminal-01-desarrollo-2026" "cajero.terminal").cuerpo | ConvertFrom-Json).access_token
    $r = Llamar POST "$cajero/sesion" $null @{} ('{"cuentaId":' + $cc + ',"pin":"1234"}'); "  sesion sin token de terminal     -> {0}" -f $r.codigo
    $r = Llamar POST "$cajero/sesion" $tc @{} ('{"cuentaId":' + $cc + ',"pin":"1234"}');   "  sesion con token de PERSONA web  -> {0}" -f $r.codigo
    $r = Llamar POST "$cajero/sesion" $tt @{} ('{"cuentaId":' + $cc + ',"pin":"9999"}');   "  terminal, PIN malo               -> {0}" -f $r.codigo
    $r = Llamar POST "$cajero/sesion" $tt @{} ('{"cuentaId":' + $cc + ',"pin":"1234"}');   "  terminal, PIN bueno              -> {0}  sesion de {1} s" -f $r.codigo, ($r.cuerpo | ConvertFrom-Json).expiraEnSegundos
    $ses = ($r.cuerpo | ConvertFrom-Json).sesion
    $r = Llamar GET "$cajero/saldo" $tt;                               "  saldo sin sesion                 -> {0}" -f $r.codigo
    $r = Llamar GET "$cajero/saldo" $tt @{ "X-Sesion-Cajero" = $ses }; "  saldo con sesion                 -> {0}  {1}" -f $r.codigo, (Corto $r.cuerpo)
    $r = Llamar POST "$cajero/retiro" $tt @{ "X-Sesion-Cajero" = $ses } '{"monto":10000}'; "  retiro 10000 (billetes de 10.000) -> {0}  {1}" -f $r.codigo, (Corto $r.cuerpo)
    $r = Llamar GET "$cajero/saldo" $tt @{ "X-Sesion-Cajero" = $ses }; "  saldo con la sesion ya cerrada   -> {0}" -f $r.codigo

    # ---------------------------------------------------------------------
    Titulo "3. Latencia de la nube y el Circuit Breaker del cajero (llamada lenta = 1 s)"
    "La EC2 esta en us-east-1 y la base en sa-santiago-1: ~210 ms por viaje."
    $r = Llamar POST "$cajero/sesion" $tt @{} ('{"cuentaId":' + $cc + ',"pin":"1234"}')
    $ses = ($r.cuerpo | ConvertFrom-Json).sesion
    $t = 1..10 | ForEach-Object { & $curl -sk -o NUL -w "%{http_code}/%{time_total}s" -H "Authorization: Bearer $tt" -H "X-Sesion-Cajero: $ses" "$cajero/saldo" }
    "  10 consultas de saldo desde Chile (codigo/tiempo total): " + ($t -join ' ')
    $cb = (& $curl -sk "https://${Servidor}:8083/actuator/health" | ConvertFrom-Json).components.circuitBreakers.details.msCuentas.details
    "  circuito msCuentas del cajero: estado={0} llamadas={1} lentas={2} fallidas={3}" -f $cb.state, $cb.bufferedCalls, $cb.slowCalls, $cb.failedCalls

    # ---------------------------------------------------------------------
    Titulo "4. Bulkhead hacia ms-cuentas (bff-web, 20 llamadas simultaneas)"
    "Ficha de una cuenta, rafagas disparadas dentro de la EC2."
    $te = ((TokenPersona "canal-web" "ejecutivo" "ejecutivo123").cuerpo | ConvertFrom-Json).access_token
    EnVm 'umask 077; cat > ~/tok; bash ~/bulkhead_cuentas.sh https://localhost:8081/api/web/cuentas/107 1 16 40 < ~/tok; : > ~/tok' $te

    # ---------------------------------------------------------------------
    Titulo "5. Bulkhead hacia ms-transferencias (bff-web, 5 simultaneas)"
    $tc = ((TokenPersona "canal-web" "cliente" "cliente123").cuerpo | ConvertFrom-Json).access_token
    $antes = Saldo $tc
    EnVm 'umask 077; cat > ~/tok; for n in 1 15; do bash ~/bulkhead_transferencias.sh $n 100 < ~/tok; sleep 3; done; : > ~/tok' $tc
    Start-Sleep -Seconds 15
    $despues = Saldo $tc
    "-- la saga cuadra: cada 202 es una transferencia aplicada, cada 503 no salio del BFF"
    "  saldo de la 105 antes {0}, despues {1}: se debitaron {2}" -f $antes, $despues, ($antes - $despues)

    # ---------------------------------------------------------------------
    Titulo "6. Falla real: ms-transferencias se cae y vuelve"
    "Se detiene el contenedor de verdad (docker compose stop). Nadie reinicia"
    "bff-web: el circuito abre, el canal degrada, y al volver el servicio el"
    "circuito pasa por HALF_OPEN y cierra solo."
    $tc = ((TokenPersona "canal-web" "cliente" "cliente123").cuerpo | ConvertFrom-Json).access_token
    $antes = Saldo $tc; $aceptadas = 0
    EnVm 'cd ~/bank-cloud && docker compose stop ms-transferencias 2>&1 | tail -1'
    $t0 = Get-Date
    foreach ($i in 1..8) {
        $r = Transferir $tc 100
        if ($r.codigo -eq "202") { $aceptadas++ }
        "  t={0,4:N0} s  transferir -> {1} en {2,5:N2} s  circuito {3}  {4}" -f ((Get-Date) - $t0).TotalSeconds, $r.codigo, $r.segundos, (Circuito "msTransferencias"), (Corto $r.cuerpo 70)
        Start-Sleep -Seconds 2
    }
    $r = Llamar GET "$web/cuentas/105" $tc
    "  mientras tanto, consultar la cuenta (otro servicio, otro circuito) -> {0}" -f $r.codigo
    "-- se vuelve a levantar ms-transferencias"
    EnVm 'cd ~/bank-cloud && docker compose start ms-transferencias 2>&1 | tail -1'
    $seguidas = 0
    while (((Get-Date) - $t0).TotalSeconds -lt 420 -and $seguidas -lt 2) {
        Start-Sleep -Seconds 10
        $r = Transferir $tc 100
        $estado = Circuito "msTransferencias"
        if ($r.codigo -eq "202") { $aceptadas++; $seguidas++ } else { $seguidas = 0 }
        "  t={0,4:N0} s  transferir -> {1} en {2,5:N2} s  circuito {3}" -f ((Get-Date) - $t0).TotalSeconds, $r.codigo, $r.segundos, $estado
    }
    Start-Sleep -Seconds 10
    "  circuito al final: {0}" -f (Circuito "msTransferencias")
    $despues = Saldo $tc
    "  transferencias aceptadas (202): {0}; saldo de la 105 antes {1}, despues {2}: se debitaron {3}" -f $aceptadas, $antes, $despues, ($antes - $despues)

    # ---------------------------------------------------------------------
    Titulo "7. Falla real: se cae una de las dos replicas de ms-cuentas"
    "Se detiene una replica (docker stop). El balanceador y el Retry mandan las"
    "consultas a la otra, y la suscripcion compartida le entrega a ella todos"
    "los eventos de la saga."
    EnVm 'docker stop bank-cloud-ms-cuentas-2 > /dev/null && echo "  bank-cloud-ms-cuentas-2 detenido"'
    $t = 1..10 | ForEach-Object { & $curl -sk -o NUL -w "%{http_code}/%{time_total}s" -H "Authorization: Bearer $tc" "$web/cuentas/105"; Start-Sleep -Milliseconds 500 }
    "  10 consultas con una sola replica viva (codigo/tiempo): " + ($t -join ' ')
    "  circuito msCuentas de bff-web: {0}" -f (Circuito "msCuentas")
    $antes = Saldo $tc
    $r = Transferir $tc 100
    Start-Sleep -Seconds 10
    "  transferencia de 100 -> {0}; saldo de la 105 antes {1}, despues {2}: la proceso la replica que quedo" -f $r.codigo, $antes, (Saldo $tc)
    "-- se vuelve a levantar la replica"
    EnVm 'docker start bank-cloud-ms-cuentas-2 > /dev/null; for i in $(seq 60); do s=$(docker inspect -f {{.State.Health.Status}} bank-cloud-ms-cuentas-2); [ $s = healthy ] && break; sleep 5; done; echo "  bank-cloud-ms-cuentas-2 $s tras $((i*5)) s"; for i in $(seq 24); do n=$(curl -s -H Accept:application/json http://127.0.0.1:8761/eureka/apps/MS-CUENTAS | grep -o instanceId | wc -l); [ $n -ge 2 ] && break; sleep 5; done; echo "  instancias de MS-CUENTAS en Eureka: $n"'
}

$lineas = & $cuerpo 2>&1 | ForEach-Object { "$_" }
$lineas
$dir = Split-Path $Salida
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
# Set-Content y no '>': la redireccion de PowerShell 5.1 guarda en UTF-16.
$lineas | Set-Content -Path $Salida -Encoding utf8
"`nEvidencia guardada en $Salida"
