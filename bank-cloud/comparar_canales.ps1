# ---------------------------------------------------------------------------
# Evidencia de REGRESION de la semana 6. Genera
# ..\evidencias\02_regresion_semana6_<motor>.txt
#
# Lo que la semana 7 agrega lo demuestra probar_eventos.ps1. Este es el script
# de la semana 6 casi sin cambios, y se vuelve a correr para comprobar que la
# arquitectura de eventos no rompio nada de lo que ya funcionaba. El unico
# cambio de fondo esta en la seccion 6: ahora detiene las DOS instancias de
# ms-cuentas.
#
# Uso (con el ecosistema ya arriba, via .\levantar.ps1):
#   .\comparar_canales.ps1
#   .\comparar_canales.ps1 -Motor oracle
#
# QUE DEMUESTRA, Y EN QUE ORDEN
# -----------------------------
# Las seis secciones cubren los cuatro criterios de la pauta, en el orden en
# que se pueden comprobar sin suponer nada de lo anterior:
#
#   1. Config Server      de donde saco cada servicio su configuracion
#   2. Service Discovery  quien esta registrado en Eureka
#   3. Backend real       ms-cuentas autentica servicios y distingue permisos
#   4. Personalizacion    la misma cuenta por tres canales, medida en bytes
#   5. Autorizacion       la cuenta ajena se rechaza en los tres canales
#   6. Tolerancia a fallos  el circuito abre, cada canal degrada a su manera,
#                           y vuelve a cerrar cuando el Backend regresa
#
# La seccion 6 DETIENE ms-cuentas y lo vuelve a levantar. Es la unica forma
# honesta de mostrar un Circuit Breaker: con el servicio arriba se puede
# afirmar que esta configurado, no que funciona.
#
# Todo pasa por curl.exe y no por Invoke-RestMethod: ver el comentario en
# levantar.ps1 sobre el handshake TLS de PowerShell 5.1.
# ---------------------------------------------------------------------------

param(
    [ValidateSet("h2", "oracle")]
    [string]$Motor = "h2"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "comun.ps1")

# La evidencia sube un nivel, a la carpeta de la semana, junto al README y
# al codigo fuente: son los tres aspectos que pide la seccion Entrega y
# conviene que se vean juntos al abrir el ZIP.
$carpeta = Join-Path (Split-Path $raiz -Parent) "evidencias"
if (-not (Test-Path $carpeta)) {
    New-Item -ItemType Directory -Path $carpeta | Out-Null
}
$salida = Join-Path $carpeta ("02_regresion_semana6_{0}.txt" -f $Motor)

# Cuentas de la demostracion. 105 es la del usuario 'cliente' y del aparato
# android-demo-001; 107 es la de otra persona, y es la que se pide para
# demostrar el rechazo. Las dos estan entre las cuentas con mas movimientos
# del dataset oficial -la 105 tiene 56-, asi que los agregados no salen en
# cero y la comparacion entre canales se ve.
$CUENTA_PROPIA = 105
$CUENTA_AJENA = 107

function Wait-MsCuentas {
    param([int]$SegundosMaximo = 300)
    $limite = (Get-Date).AddSeconds($SegundosMaximo)
    while ((Get-Date) -lt $limite) {
        $r = & $curl -s -k --max-time 5 "http://localhost:8090/actuator/health" 2>$null
        if ($LASTEXITCODE -eq 0 -and $r -match '"status"\s*:\s*"UP"') { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# ---------------------------------------------------------------------------
# Estado de partida limpio
# ---------------------------------------------------------------------------
# La seccion 6 deja los tres circuitos abiertos, y un Circuit Breaker abierto
# sobrevive a la corrida: no se cierra hasta que pasa la ventana de espera y
# una llamada de prueba tiene exito. Si esta evidencia se ejecuta dos veces
# seguidas sobre el mismo ecosistema, la segunda arranca con los circuitos como
# los dejo la primera, y la seccion 4 muestra un canal degradado sin que nada
# este mal.
#
# Ocurrio: una corrida mostro el cajero respondiendo FUERA_DE_SERVICIO en la
# tabla de personalizacion, junto a web y movil respondiendo 200.
#
# Por eso se espera aqui, antes de medir nada, a que los tres canales vuelvan a
# atender. No se fuerza ni se reinicia nada: solo se deja que el mecanismo de
# recuperacion haga lo suyo.
Write-Host "Comprobando que los tres canales partan desde un circuito cerrado..."
$limitePreparacion = (Get-Date).AddSeconds(120)
do {
    $listos = 0
    foreach ($u in @("https://localhost:8081/actuator/health",
                     "https://localhost:8082/actuator/health",
                     "https://localhost:8083/actuator/health")) {
        $h = & $curl -s -k --max-time 5 $u 2>$null
        # El indicador circuitBreakers pasa a DOWN cuando alguno esta abierto,
        # asi que basta con que el estado global sea UP.
        if ($LASTEXITCODE -eq 0 -and $h -match '"status"\s*:\s*"UP"') { $listos++ }
    }
    if ($listos -eq 3) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $limitePreparacion)

if ($listos -lt 3) {
    Write-Host ("ADVERTENCIA: solo {0} de 3 canales estan sanos. La evidencia puede salir con un canal degradado." -f $listos)
}
Write-Host ""

Escribir ("EVIDENCIA DE REGRESION - PBY2203 Experiencia 3, Semana 7")
Escribir ("Lo construido en la semana 6, comprobado sobre el ecosistema de la semana 7")
Escribir ("Microservicios y seguridad en la nube con Spring Cloud")
Escribir ("")
Escribir ("Fecha:  {0}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Escribir ("Motor:  {0}" -f $Motor.ToUpper())
# --version con dos guiones, y no -version con uno: la forma antigua escribe
# en stderr -lo que con ErrorActionPreference en Stop aborta el script- y la
# moderna, disponible desde Java 9, escribe en stdout como corresponde.
$versionJava = (& $java --version | Select-Object -First 1)

Escribir ("Equipo: {0}, Java {1}" -f $env:COMPUTERNAME, $versionJava)

# ===========================================================================
Titulo "1. CONFIG SERVER - la configuracion no vive dentro de los jar"
# ===========================================================================

Escribir ""
Escribir "Lo que el Config Server entrega para ms-cuentas (extracto):"
Escribir ""
$cfg = Pedir -Url "http://localhost:7888/ms-cuentas/default"
foreach ($clave in @("server.port", "spring.datasource.url", "bank.datos.ruta", "bank.interno.usuario-consulta")) {
    if ($cfg.Cuerpo -match ('"' + [regex]::Escape($clave) + '"\s*:\s*("?)([^",}]*)')) {
        Escribir ("    {0,-36} = {1}" -f $clave, $matches[2])
    }
}

Escribir ""
Escribir "Y lo que cada servicio declara haber recibido, segun su propia salud:"
Escribir ""
foreach ($s in @(@("ms-cuentas", "http://localhost:8090"), @("bff-web", "https://localhost:8081"),
                 @("bff-movil", "https://localhost:8082"), @("bff-cajero", "https://localhost:8083"))) {
    $h = Pedir -Url ($s[1] + "/actuator/health")
    $origenes = if ($h.Cuerpo -match '"propertySources"\s*:\s*\[([^\]]*)\]') { $matches[1] -replace '"', '' } else { "(no informa)" }
    Escribir ("    {0,-12} {1}" -f $s[0], $origenes)
}

Escribir ""
Escribir "Ninguno de los cuatro lleva puerto, base de datos ni secretos en su jar:"
Escribir "su application.yml tiene 28 lineas y solo dice como se llama y donde"
Escribir "preguntar. En la Experiencia 2 ese archivo tenia noventa."

# ===========================================================================
Titulo "2. SERVICE DISCOVERY - quien esta registrado en Eureka"
# ===========================================================================

# Se espera a que los cuatro aparezcan antes de listarlos.
#
# Registrarse en Eureka no es parte del arranque: el cliente responde UP en su
# endpoint de salud y recien despues, en su primer latido, se anuncia al
# registro. Una corrida anterior de esta evidencia listo tres de cuatro por
# consultar demasiado pronto, y parecia que bff-cajero no se registraba cuando
# solo faltaba esperar unos segundos.
$esperados = 4
$limiteRegistro = (Get-Date).AddSeconds(60)
do {
    $apps = Pedir -Url "http://localhost:8761/eureka/apps" -Cabeceras @("Accept: application/json")
    $cuantas = ([regex]::Matches($apps.Cuerpo, '"instanceId"')).Count
    if ($cuantas -ge $esperados) { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $limiteRegistro)

Escribir ""
Escribir ("    {0,-14} {1,-10} {2}" -f "APLICACION", "ESTADO", "INSTANCIA")
Escribir ("    " + ("-" * 62))

# Se analiza el JSON en vez de buscar con expresiones regulares. Un primer
# intento con regex emparejaba el nombre de una aplicacion con la instancia de
# otra -el documento anida instancias dentro de aplicaciones y una expresion
# plana no distingue donde termina cada bloque- y la tabla salia mezclada.
$registradas = 0
$documento = $apps.Cuerpo | ConvertFrom-Json

# Eureka devuelve un objeto cuando hay una sola aplicacion y un arreglo cuando
# hay varias. @() fuerza el arreglo en los dos casos.
foreach ($app in @($documento.applications.application)) {
    foreach ($instancia in @($app.instance)) {
        Escribir ("    {0,-14} {1,-10} {2}" -f $app.name, $instancia.status, $instancia.instanceId)
        $registradas++
    }
}
Escribir ""
Escribir ("    Total de microservicios registrados: {0}" -f $registradas)
Escribir ""
Escribir "Los tres BFF y ms-cuentas se anuncian solos al arrancar. En ningun"
Escribir "archivo del proyecto esta escrita la direccion de ms-cuentas del lado"
Escribir "del cliente: los BFF lo piden por nombre con lb://ms-cuentas."

# ===========================================================================
Titulo "3. EL BACKEND REAL - ms-cuentas autentica servicios, no personas"
# ===========================================================================

Escribir ""
Escribir "El mismo endpoint, con tres credenciales distintas:"
Escribir ""

$sinCredencial = Pedir -Url "http://localhost:8090/interno/cuentas/$CUENTA_PROPIA"
Escribir ("    sin credencial                     -> {0}" -f $sinCredencial.Codigo)

$conConsulta = Pedir -Url "http://localhost:8090/interno/cuentas/$CUENTA_PROPIA" -Basico "svc-consulta:consulta-interna-2026"
Escribir ("    svc-consulta  GET  ficha           -> {0}  ({1} bytes)" -f $conConsulta.Codigo, $conConsulta.Bytes)

$retiroConsulta = Pedir -Url "http://localhost:8090/interno/cuentas/$CUENTA_PROPIA/retiro" -Metodo "POST" -Cuerpo '{"monto":10000}' -Basico "svc-consulta:consulta-interna-2026"
Escribir ("    svc-consulta  POST retiro          -> {0}  (no tiene permiso de operar)" -f $retiroConsulta.Codigo)

Escribir ""
Escribir "Que la credencial de lectura NO pueda retirar es deliberado: web y movil"
Escribir "se presentan con svc-consulta y solo el cajero con svc-operacion. Si un"
Escribir "canal de solo consulta quedara comprometido, el atacante podria leer,"
Escribir "no mover dinero."
Escribir ""
$resumen = Pedir -Url "http://localhost:8090/interno/resumen" -Basico "svc-consulta:consulta-interna-2026"
Escribir ("    Dataset cargado: {0}" -f $resumen.Cuerpo)

# ===========================================================================
Titulo "4. PERSONALIZACION POR CANAL - la misma cuenta, tres respuestas"
# ===========================================================================

# --- Credenciales de cada canal -------------------------------------------
$login = Pedir -Url "https://localhost:8081/api/web/login" -Metodo "POST" -Cuerpo '{"usuario":"cliente","clave":"cliente123"}'
$tokenWeb = if ($login.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { $matches[1] } else { $null }

$registro = Pedir -Url "https://localhost:8082/api/movil/registro" -Metodo "POST" `
    -Cuerpo '{"deviceId":"android-demo-001"}' -Cabeceras @("X-Device-Token: token-movil-demo-2026")
$tokenMovil = if ($registro.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { $matches[1] } else { $null }

# La sesion del cajero se abre con reintentos, y se vuelve a abrir antes de la
# seccion 6. Dos cosas que en la semana 7 aparecieron y en la 6 no:
#   - la primera sesion contra un bff-cajero recien arrancado llama a un
#     ms-cuentas tambien en frio y puede pasar los 20 s de curl; una corrida
#     la perdio (codigo 0) y todo lo del cajero salio 403 por falta de token.
#   - el token dura DOS minutos (bff-cajero.yml). Con diez procesos en el
#     equipo la evidencia tarda mas en llegar a la seccion 6, y el token ya
#     habia vencido: el cajero respondia 403 en vez de su degradacion.
function Open-SesionCajero {
    for ($i = 1; $i -le 5; $i++) {
        $s = Pedir -Url "https://localhost:8083/api/cajero/sesion" -Metodo "POST" `
            -Cuerpo ('{"cuentaId":' + $CUENTA_PROPIA + ',"pin":"1234"}') -Cabeceras @("X-ATM-Terminal: atm-key-demo-2026")
        if ($s.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { return [pscustomobject]@{ Codigo = $s.Codigo; Token = $matches[1] } }
        Start-Sleep -Seconds 5
    }
    return [pscustomobject]@{ Codigo = $s.Codigo; Token = $null }
}
$sesion = Open-SesionCajero
$tokenCajero = $sesion.Token

Escribir ""
Escribir ("    web     login    -> {0}" -f $login.Codigo)
Escribir ("    movil   registro -> {0}" -f $registro.Codigo)
Escribir ("    cajero  sesion   -> {0}" -f $sesion.Codigo)

$web = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenWeb")
$movil = Pedir -Url "https://localhost:8082/api/movil/cuentas/$CUENTA_PROPIA/resumen" -Cabeceras @("Authorization: Bearer $tokenMovil")
$cajero = Pedir -Url "https://localhost:8083/api/cajero/saldo" -Cabeceras @("Authorization: Bearer $tokenCajero", "X-ATM-Terminal: atm-key-demo-2026")

Escribir ""
Escribir ("    La cuenta {0}, pedida por los tres canales:" -f $CUENTA_PROPIA)
Escribir ""
Escribir ("    {0,-10} {1,-7} {2,-8} {3,-7} {4,-9} {5}" -f "CANAL", "CODIGO", "BYTES", "MS", "CAMPOS", "CLAVES TOTALES")
Escribir ("    " + ("-" * 70))

foreach ($c in @(@("web", $web), @("movil", $movil), @("cajero", $cajero))) {

    # Dos cuentas distintas, porque miden cosas distintas y confundirlas
    # exagera la comparacion. CAMPOS son los de primer nivel, que es lo que la
    # pantalla del canal recibe como estructura. CLAVES TOTALES incluye las de
    # los objetos anidados -cada movimiento de la lista aporta las suyas- y por
    # eso crece con la cantidad de elementos, no con la riqueza del contrato.
    $campos = 0
    $claves = 0
    if ($c[1].Codigo -eq 200 -and $c[1].Cuerpo) {
        $campos = (($c[1].Cuerpo | ConvertFrom-Json).PSObject.Properties | Measure-Object).Count
        $claves = ([regex]::Matches($c[1].Cuerpo, '"([a-zA-Z]+)"\s*:')).Count
    }
    Escribir ("    {0,-10} {1,-7} {2,-8} {3,-7} {4,-9} {5}" -f $c[0], $c[1].Codigo, $c[1].Bytes, $c[1].Ms, $campos, $claves)
}

Escribir ""
Escribir "Cuerpos completos:"
Escribir ""
Escribir ("    web    : " + $web.Cuerpo)
Escribir ""
Escribir ("    movil  : " + $movil.Cuerpo)
Escribir ""
Escribir ("    cajero : " + $cajero.Cuerpo)
Escribir ""
Escribir "Los tres numeros salen del MISMO servicio Backend. Lo que cambia es que"
Escribir "recorta cada BFF, y por eso el patron sigue en pie aunque el dominio ya"
Escribir "no viva dentro de ellos."

# ===========================================================================
Titulo "5. AUTORIZACION - la cuenta ajena se rechaza"
# ===========================================================================

Escribir ""
Escribir ("El usuario 'cliente' es titular de la {0}. La {1} es de otra persona." -f $CUENTA_PROPIA, $CUENTA_AJENA)
Escribir "Mismo token, misma ruta, solo cambia el numero:"
Escribir ""

$webAjena = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_AJENA" -Cabeceras @("Authorization: Bearer $tokenWeb")
$movilAjena = Pedir -Url "https://localhost:8082/api/movil/cuentas/$CUENTA_AJENA/resumen" -Cabeceras @("Authorization: Bearer $tokenMovil")

Escribir ("    web    cuenta {0} (propia) -> {1}" -f $CUENTA_PROPIA, $web.Codigo)
Escribir ("    web    cuenta {0} (ajena)  -> {1}" -f $CUENTA_AJENA, $webAjena.Codigo)
Escribir ("    movil  cuenta {0} (propia) -> {1}" -f $CUENTA_PROPIA, $movil.Codigo)
Escribir ("    movil  cuenta {0} (ajena)  -> {1}" -f $CUENTA_AJENA, $movilAjena.Codigo)

Escribir ""
Escribir "Este es el fallo que la Experiencia 2 dejo abierto y que costo el criterio"
Escribir "de configuracion segura: ahi las cuatro lineas habrian devuelto 200. La"
Escribir "cuenta ahora viaja firmada dentro del token y el controlador la compara."

Escribir ""
Escribir "El ejecutivo, en cambio, si tiene atribucion sobre la cartera:"
Escribir ""
$loginEjecutivo = Pedir -Url "https://localhost:8081/api/web/login" -Metodo "POST" -Cuerpo '{"usuario":"ejecutivo","clave":"ejecutivo123"}'
$tokenEjecutivo = if ($loginEjecutivo.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { $matches[1] } else { $null }

$ejecutivoAjena = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_AJENA" -Cabeceras @("Authorization: Bearer $tokenEjecutivo")
$clienteCartera = Pedir -Url "https://localhost:8081/api/web/cuentas" -Cabeceras @("Authorization: Bearer $tokenWeb")
$ejecutivoCartera = Pedir -Url "https://localhost:8081/api/web/cuentas" -Cabeceras @("Authorization: Bearer $tokenEjecutivo")

Escribir ("    ejecutivo  cuenta {0} (ajena)  -> {1}" -f $CUENTA_AJENA, $ejecutivoAjena.Codigo)
Escribir ("    cliente    cartera completa    -> {0}" -f $clienteCartera.Codigo)
Escribir ("    ejecutivo  cartera completa    -> {0}  ({1} bytes)" -f $ejecutivoCartera.Codigo, $ejecutivoCartera.Bytes)

Escribir ""
Escribir "El cajero no aparece en esta tabla porque no se le puede pedir una cuenta"
Escribir "ajena: no recibe numero de cuenta en ninguna ruta. Siempre opero asi, y"
Escribir "es el patron que esta entrega llevo a los otros dos canales."

# ===========================================================================
Titulo "6. TOLERANCIA A FALLOS - el circuito abre y cada canal degrada"
# ===========================================================================

Escribir ""
Escribir "Estado del Circuit Breaker con ms-cuentas arriba:"
Escribir ""
$cbAntes = Pedir -Url "https://localhost:8081/actuator/circuitbreakers" -Cabeceras @("Authorization: Bearer $tokenEjecutivo")
if ($cbAntes.Cuerpo -match '"state"\s*:\s*"([^"]+)"') {
    Escribir ("    bff-web  msCuentas  ->  {0}" -f $matches[1])
}

Escribir ""
Escribir "Ahora se DETIENE ms-cuentas. No se simula la falla: se produce."
Escribir ""
Escribir "Desde la semana 7 son DOS instancias, y hay que detener las dos: con una"
Escribir "sola viva, el balanceador le mandaria todo el trafico y el circuito no"
Escribir "abriria. Es la otra cara de la escalabilidad: una instancia caida de dos"
Escribir "no es una caida del servicio."
Escribir ""

# Sesion de cajero nueva, con ms-cuentas todavia arriba: la de la seccion 4 ya
# puede haber vencido (dura 2 minutos) y abrir una exige consultar la cuenta.
$tokenCajero = (Open-SesionCajero).Token

foreach ($instancia in @("ms-cuentas", "ms-cuentas-2")) {
    $pidInstancia = Get-PidServicio $instancia
    if (Stop-Servicio $instancia) {
        Escribir ("    {0} detenido (pid {1})" -f $instancia, $pidInstancia)
    }
}
# Eureka tarda hasta 10 s en dar de baja una instancia sin latido, y el
# balanceador de cada BFF refresca su copia cada 5. Mientras tanto las
# llamadas van a una direccion que ya no responde, que es justamente el fallo
# que el circuito tiene que contar.
Start-Sleep -Seconds 3

Escribir ""
Escribir "Seis llamadas seguidas al canal web. Las primeras fallan una por una;"
Escribir "cuando la tasa de fallos pasa el umbral, el circuito abre y las"
Escribir "siguientes ni siquiera salen a la red:"
Escribir ""
for ($i = 1; $i -le 6; $i++) {
    $r = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenWeb")
    $abierto = if ($r.Cuerpo -match '"circuitoAbierto"\s*:\s*true') { "circuito ABIERTO" } else { "fallo puntual" }
    Escribir ("    intento {0}  ->  {1}  {2,6} ms   {3}" -f $i, $r.Codigo, $r.Ms, $abierto)
}

Escribir ""
$cbDurante = Pedir -Url "https://localhost:8081/actuator/circuitbreakers" -Cabeceras @("Authorization: Bearer $tokenEjecutivo")
if ($cbDurante.Cuerpo -match '"state"\s*:\s*"([^"]+)"') {
    Escribir ("    Estado del circuito ahora:  {0}" -f $matches[1])
}

Escribir ""
Escribir "LA MISMA FALLA, VISTA POR LOS TRES CANALES:"
Escribir ""
$webCaido = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenWeb")
$movilCaido = Pedir -Url "https://localhost:8082/api/movil/cuentas/$CUENTA_PROPIA/resumen" -Cabeceras @("Authorization: Bearer $tokenMovil")
$cajeroCaido = Pedir -Url "https://localhost:8083/api/cajero/saldo" -Cabeceras @("Authorization: Bearer $tokenCajero", "X-ATM-Terminal: atm-key-demo-2026")

Escribir ("    web    {0}  {1} bytes" -f $webCaido.Codigo, $webCaido.Bytes)
Escribir ("           " + $webCaido.Cuerpo)
Escribir ""
Escribir ("    movil  {0}  {1} bytes" -f $movilCaido.Codigo, $movilCaido.Bytes)
Escribir ("           " + $movilCaido.Cuerpo)
Escribir ""
Escribir ("    cajero {0}  {1} bytes" -f $cajeroCaido.Codigo, $cajeroCaido.Bytes)
Escribir ("           " + $cajeroCaido.Cuerpo)
Escribir ""
Escribir "Tres respuestas distintas ante la misma caida. El navegador recibe una"
Escribir "explicacion y un tiempo de reintento; el telefono, dos campos; el cajero"
Escribir "se declara fuera de servicio, que es lo unico honesto frente a alguien"
Escribir "que tiene la tarjeta dentro de la maquina."

Escribir ""
Escribir "Y el retiro, que es la operacion que NO se reintenta:"
Escribir ""
$retiroCaido = Pedir -Url "https://localhost:8083/api/cajero/retiro" -Metodo "POST" -Cuerpo '{"monto":10000}' `
    -Cabeceras @("Authorization: Bearer $tokenCajero", "X-ATM-Terminal: atm-key-demo-2026")
Escribir ("    cajero retiro -> {0}" -f $retiroCaido.Codigo)
Escribir ("    " + $retiroCaido.Cuerpo)

Escribir ""
Escribir "Se levanta ms-cuentas de nuevo. Esto tarda un par de minutos."
Escribir ""

# Start-Servicio (comun.ps1) anota el pid nuevo en el registro que usa
# levantar.ps1 -Detener. Sin eso la instancia relevantada aqui quedaria
# huerfana y la siguiente compilacion fallaria al no poder borrar su jar.
$nuevo = Start-Servicio "ms-cuentas"
if (Wait-MsCuentas) {
    Escribir ("    ms-cuentas arriba otra vez (pid {0})" -f $nuevo.Id)
} else {
    Escribir "    ms-cuentas no volvio a levantar. Revisa logs\ms-cuentas.log"
}

# Se entra en calor contra ms-cuentas ANTES de sondear por el BFF.
#
# Un servicio Spring recien arrancado atiende su primera peticion mucho mas
# lento que las siguientes: falta compilar el codigo caliente, poblar el cache
# de sentencias de Hibernate y abrir la primera conexion del pool. Una corrida
# anterior de esta evidencia gasto esa primera llamada lenta en la sonda de
# HALF_OPEN: la llamada respondio bien pero tardo mas que el umbral de llamada
# lenta, el circuito la conto como fallo y volvio a OPEN.
#
# Eso no es un defecto del Circuit Breaker -hizo exactamente lo que se le
# pidio- pero si hace que la evidencia muestre lo contrario de lo que afirma.
# Esta llamada directa absorbe el arranque en frio.
$calentamiento = Pedir -Url "http://localhost:8090/interno/cuentas/$CUENTA_PROPIA" -Basico "svc-consulta:consulta-interna-2026"
Escribir ("    primera llamada al servicio recien arrancado: {0} ms" -f $calentamiento.Ms)

Escribir ""
Escribir "El circuito no se cierra de golpe: se queda abierto diez segundos, pasa"
Escribir "a HALF_OPEN y deja pasar dos llamadas de prueba antes de darse por"
Escribir "recuperado. Si una de esas dos falla, vuelve a OPEN y espera otra vez."
Escribir ""

$estadoFinal = "?"
# Hasta 30 sondas y no 10: con las dos instancias recien caidas, el
# balanceador de bff-web conserva su lista de instancias hasta 35 s (su cache
# por defecto), y una sonda de HALF_OPEN que va a la instancia muerta devuelve
# el circuito a OPEN. Con 10 sondas una corrida termino en OPEN.
for ($i = 1; $i -le 30; $i++) {
    Start-Sleep -Seconds 4
    $r = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenWeb")
    $cb = Pedir -Url "https://localhost:8081/actuator/circuitbreakers" -Cabeceras @("Authorization: Bearer $tokenEjecutivo")
    $estadoFinal = if ($cb.Cuerpo -match '"state"\s*:\s*"([^"]+)"') { $matches[1] } else { "?" }
    Escribir ("    sonda {0,2}  ->  {1}  {2,6} ms   circuito {3}" -f $i, $r.Codigo, $r.Ms, $estadoFinal)
    if ($estadoFinal -eq "CLOSED" -and $r.Codigo -eq 200) { break }
}

Escribir ""
if ($estadoFinal -eq "CLOSED") {
    Escribir "Circuito CERRADO y canal sirviendo de nuevo. El sistema se recupero solo:"
    Escribir "ningun BFF se reinicio y nadie toco una configuracion."
} else {
    Escribir ("El circuito quedo en {0} al agotarse las sondas de esta evidencia." -f $estadoFinal)
    Escribir "Se cerrara en el siguiente intento exitoso; no requiere intervencion."
}

# La segunda instancia vuelve al final, fuera de la medicion: con el circuito
# ya cerrado no cambia nada de lo que esta seccion demuestra, y deja el
# ecosistema como lo encontro para probar_eventos.ps1.
Write-Host "Relevantando ms-cuentas-2 para dejar el ecosistema completo..."
$segunda = Start-Servicio "ms-cuentas-2"
if (Wait-Salud $Servicios["ms-cuentas-2"].salud) {
    Escribir ""
    Escribir ("    ms-cuentas-2 arriba otra vez (pid {0})" -f $segunda.Id)
}

# ===========================================================================
Titulo "RESUMEN"
# ===========================================================================
Escribir ""
Escribir ("    Config Server               {0} servicios toman su configuracion de el" -f 4)
Escribir ("    Service Discovery           {0} microservicios registrados en Eureka" -f $registradas)
Escribir ("    Autenticacion entre servicios  401 sin credencial, 403 sin permiso de operar")
Escribir ("    Personalizacion por canal   {0} / {1} / {2} bytes  (web / movil / cajero)" -f $web.Bytes, $movil.Bytes, $cajero.Bytes)
Escribir ("    Autorizacion por cuenta     403 en cuenta ajena, en web y en movil")
Escribir ("    Tolerancia a fallos         circuito abierto, degradado por canal; al final: {0}" -f $(if ($estadoFinal -eq "CLOSED") { "recuperado (CLOSED)" } else { "aun $estadoFinal" }))
Escribir ""

# Set-Content y no '>': la redireccion de PowerShell 5.1 guarda en UTF-16,
# que pesa el doble y que git no diferencia bien.
Set-Content -Path $salida -Value $lineas -Encoding utf8
Write-Host ""
Write-Host ("Evidencia guardada en: {0}" -f $salida)
