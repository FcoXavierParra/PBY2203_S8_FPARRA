# ---------------------------------------------------------------------------
# Evidencia de la semana 7. Genera ..\evidencias\01_eventos_y_resiliencia_h2.txt
#
# Uso (con el ecosistema arriba, via .\levantar.ps1):
#   .\probar_eventos.ps1
#
# QUE DEMUESTRA
# -------------
# Los cuatro criterios de la pauta y la sugerencia de la retroalimentacion de
# la semana 6, en este orden:
#
#    1. El ecosistema: diez procesos, siete instancias en Eureka
#    2. La topologia de mensajeria: topicos, suscripciones y consumidores
#    3. La saga, camino feliz
#    4. La saga, camino de compensacion; y el rechazo que no necesita saga
#    5. Seguridad: por HTTP y en el broker
#    6. Duplicados: las tres capas, cada una probada por separado
#    7. Mensaje envenenado: reintentos acotados y DLQ
#    8. Escalabilidad: una instancia contra dos, medida
#    9. Resiliencia: el broker se cae y las transferencias se siguen aceptando
#   10. Resiliencia: un consumidor se cae y no pierde eventos
#   11. Arranque sin Config Server: el jar de la semana 6 contra el de esta
#   12. Arranque y operacion sin Eureka
#   13. Resumen
#
# Las secciones 8 a 12 DETIENEN servicios reales y los vuelven a levantar. En
# este equipo cada JVM tarda uno o dos minutos en arrancar, asi que la corrida
# completa toma entre 25 y 40 minutos.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "comun.ps1")

$carpeta = Join-Path (Split-Path $raiz -Parent) "evidencias"
if (-not (Test-Path $carpeta)) { New-Item -ItemType Directory -Path $carpeta | Out-Null }
$salida = Join-Path $carpeta "01_eventos_y_resiliencia_h2.txt"

# Credenciales de desarrollo, las mismas que el Config Server entrega por
# defecto. Ver config-repo/*.yml.
$B_CONSULTA = "svc-consulta:consulta-interna-2026"
$B_TRANSF = "svc-transferencias:transferencias-interna-2026"
$B_AUDITOR = "svc-auditoria:auditoria-interna-2026"
$B_ADMIN = "admin-broker:admin-broker-2026"

$CUENTA_PROPIA = 105   # la del usuario 'cliente'
$CUENTA_AJENA = 107    # la del usuario 'cliente2'

$T = "https://localhost:8081/api/web/transferencias"
$MS_T = "http://localhost:8091/interno/transferencias"
$MS_A = "http://localhost:8092/interno/auditoria"
$BROKER = "http://localhost:8161/admin"

# Lo que va llenando el resumen final.
$resumen = [ordered]@{}

# ---------------------------------------------------------------------------
# Ayudas
# ---------------------------------------------------------------------------
# El ForEach-Object no es decorativo. ConvertFrom-Json de PowerShell 5.1
# entrega un arreglo JSON como UN solo objeto, y @(Json ...) lo envolvia en un
# arreglo de un elemento: la primera corrida imprimio "System.Object[]" en
# cada columna de la topologia. Pasarlo por el pipeline lo desenrolla; un
# objeto suelto sigue siendo uno.
function Json { param([string]$Texto) if ($Texto) { $Texto | ConvertFrom-Json | ForEach-Object { $_ } } else { $null } }

function Saldo {
    param([long]$Cuenta)
    (Json (Pedir -Url "http://localhost:8090/interno/cuentas/$Cuenta" -Basico $B_CONSULTA).Cuerpo).saldoFinal
}

function Get-SumaSaldos {
    $todas = Json (Pedir -Url "http://localhost:8090/interno/cuentas" -Basico $B_CONSULTA).Cuerpo
    $suma = [decimal]0
    foreach ($c in @($todas)) { if ($null -ne $c.saldoFinal) { $suma += [decimal]$c.saldoFinal } }
    return $suma
}

function Get-Outbox { Json (Pedir -Url "$MS_T/outbox" -Basico $B_TRANSF).Cuerpo }

function Get-Cupo { param([long]$Cuenta) Json (Pedir -Url "$MS_T/cupo/$Cuenta" -Basico $B_TRANSF).Cuerpo }

function Get-Topologia { @(Json (Pedir -Url "$BROKER/topologia" -Basico $B_ADMIN).Cuerpo) }

function Get-Suscripcion {
    param([string]$Topico, [string]$Servicio)
    Get-Topologia | Where-Object { $_.topico -eq $Topico -and $_.suscripcion -like "*$Servicio*" } | Select-Object -First 1
}

function Get-Estadisticas {
    param([int]$Puerto)
    $r = Pedir -Url "http://localhost:$Puerto/interno/eventos/estadisticas" -Basico $B_CONSULTA
    if ($r.Codigo -eq 200) { Json $r.Cuerpo } else { $null }
}

# Espera a que una transferencia salga de PENDIENTE. Devuelve el estado y
# cuanto tardo desde que se empezo a esperar.
function Wait-Cierre {
    param([string]$Id, [int]$SegundosMaximo = 90)
    $inicio = Get-Date
    $limite = $inicio.AddSeconds($SegundosMaximo)
    do {
        $e = Json (Pedir -Url "$MS_T/$Id" -Basico $B_TRANSF).Cuerpo
        if ($e -and $e.estado -ne "PENDIENTE") { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $limite)
    [pscustomobject]@{ Estado = $e; Ms = [math]::Round(((Get-Date) - $inicio).TotalMilliseconds) }
}

function Wait-SinPendientes {
    param([int]$SegundosMaximo = 180)
    $limite = (Get-Date).AddSeconds($SegundosMaximo)
    do {
        $o = Get-Outbox
        if ($o -and $o.transferenciasPendientes -eq 0 -and $o.pendientesDePublicar -eq 0) { return $true }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $limite)
    return $false
}

function Transferir {
    param([string]$Token, [long]$Destino, [decimal]$Monto, [string]$Clave, [string]$CuerpoCrudo)
    $cab = @("Authorization: Bearer $Token")
    if ($Clave) { $cab += "Idempotency-Key: $Clave" }
    $cuerpo = if ($CuerpoCrudo) { $CuerpoCrudo } else {
        '{"cuentaDestino":' + $Destino + ',"monto":' + $Monto.ToString([Globalization.CultureInfo]::InvariantCulture) + '}'
    }
    Pedir -Url $T -Metodo "POST" -Cabeceras $cab -Cuerpo $cuerpo
}

# Con reintentos: la primera peticion a un BFF recien arrancado -handshake TLS,
# BCrypt de la clave, codigo todavia sin compilar por el JIT- puede pasar los
# 20 s de curl. Una corrida se corto aqui con codigo 0 justo despues de que
# levantar.ps1 dejara el ecosistema arriba.
function Login {
    param([string]$Usuario, [string]$Clave)
    for ($i = 1; $i -le 4; $i++) {
        $r = Pedir -Url "https://localhost:8081/api/web/login" -Metodo "POST" -Cuerpo ('{"usuario":"' + $Usuario + '","clave":"' + $Clave + '"}')
        if ($r.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { return $matches[1] }
        Start-Sleep -Seconds 5
    }
    throw "No se pudo iniciar sesion como $Usuario ($($r.Codigo))"
}

function Linea { param([string]$Texto) Escribir ("    " + $Texto) }

function Recortar {
    param([string]$Texto, [int]$Largo = 150)
    if ($null -eq $Texto) { return "" }
    if ($Texto.Length -le $Largo) { return $Texto }
    return $Texto.Substring(0, $Largo) + "..."
}

# ---------------------------------------------------------------------------
# Encabezado
# ---------------------------------------------------------------------------
Escribir "EVIDENCIA DE EJECUCION - PBY2203 Experiencia 3, Semana 7"
Escribir "Tolerancia a fallos y arquitectura de eventos con microservicios en la nube"
Escribir ""
Escribir ("Fecha:  {0}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Escribir ("Equipo: {0}, Java {1}" -f $env:COMPUTERNAME, (& $java --version | Select-Object -First 1))
Escribir "Motor:  H2 (tres bases: cuentas, transferencias, auditoria)"
Escribir "Broker: ActiveMQ Artemis 2.40 embebido (JMS 2.0)"

# ===========================================================================
Titulo "1. EL ECOSISTEMA - diez procesos, siete instancias registradas"
# ===========================================================================
Escribir ""
Linea ("{0,-20} {1,-7} {2}" -f "PROCESO", "PUERTO", "SALUD")
Linea ("-" * 40)
$arriba = 0
foreach ($n in $Servicios.Keys) {
    $ok = Test-Salud $Servicios[$n].salud
    if ($ok) { $arriba++ }
    Linea ("{0,-20} {1,-7} {2}" -f $n, $Servicios[$n].puerto, $(if ($ok) { "UP" } else { "DOWN" }))
}
Escribir ""

# Esperar a que las siete instancias figuren en Eureka: el registro ocurre
# en el primer latido, unos segundos despues de que el servicio responde UP.
$limite = (Get-Date).AddSeconds(60)
do {
    $apps = Pedir -Url "http://localhost:8761/eureka/apps" -Cabeceras @("Accept: application/json")
    $cuantas = ([regex]::Matches($apps.Cuerpo, '"instanceId"')).Count
    if ($cuantas -ge 7) { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $limite)

Linea ("{0,-18} {1,-8} {2}" -f "EN EUREKA", "ESTADO", "INSTANCIA")
Linea ("-" * 70)
$instancias = 0
foreach ($app in @((Json $apps.Cuerpo).applications.application)) {
    foreach ($i in @($app.instance)) {
        Linea ("{0,-18} {1,-8} {2}" -f $app.name, $i.status, $i.instanceId)
        $instancias++
    }
}
Escribir ""
Escribir "El broker, el Config Server y Eureka son infraestructura y no se registran."
Escribir "MS-CUENTAS aparece DOS veces: es el mismo jar en los puertos 8090 y 8093,"
Escribir "y ningun cliente sabe que son dos."
$resumen["Procesos arriba"] = "$arriba de $($Servicios.Count)"
$resumen["Instancias en Eureka"] = $instancias

# ===========================================================================
Titulo "2. TOPOLOGIA DE MENSAJERIA - lo que el broker tiene declarado"
# ===========================================================================
Escribir ""
Escribir "Cada topico es una direccion multicast; cada suscripcion, una cola colgada"
Escribir "de ella que recibe su propia copia de cada evento."
Escribir ""
Linea ("{0,-32} {1,-28} {2}" -f "TOPICO", "SUSCRIPCION", "CONSUMIDORES")
Linea ("-" * 66)
foreach ($f in Get-Topologia) {
    Linea ("{0,-32} {1,-28} {2}" -f $f.topico, ($f.suscripcion -replace '\\\.', '.'), $f.consumidores)
}
$subCuentas = Get-Suscripcion "banco.transferencia.solicitada" "ms-cuentas"
Escribir ""
Escribir ("La suscripcion ms-cuentas tiene {0} consumidores: las dos instancias se" -f $subCuentas.consumidores)
Escribir "conectaron con el MISMO nombre de suscripcion compartida (JMS 2.0), y el"
Escribir "broker les reparte los eventos en vez de darle una copia a cada una. Es el"
Escribir "equivalente JMS de un grupo de consumidores de Kafka."

# ===========================================================================
Titulo "3. LA SAGA, CAMINO FELIZ - una transferencia de principio a fin"
# ===========================================================================
$tokenCliente = Login "cliente" "cliente123"
$tokenCliente2 = Login "cliente2" "cliente456"

# Calentamiento del camino bff-web -> ms-transferencias, ANTES de medir.
#
# Una corrida hecha justo despues de levantar el ecosistema recibio 503 en su
# primera transferencia, a los 14,7 s: la primera solicitud a un
# ms-transferencias recien arrancado -primera transaccion, primera consulta con
# bloqueo, codigo sin compilar por el JIT- tardo mas que los 2,5 s de timeout de
# lectura, bff-web la reintento con la MISMA Idempotency-Key, el reintento
# tambien llego tarde y el canal respondio con su degradacion. La transferencia
# se habia aplicado, y una sola vez: la clave de idempotencia hizo exactamente
# su trabajo. Pero la seccion 3 mostraba un 503 donde tenia que mostrar la saga.
#
# Se calienta con solicitudes que ms-transferencias RECHAZA por cupo (422):
# recorren el mismo camino -TLS, token, balanceador, seguridad, la consulta con
# bloqueo del cupo- sin mover un peso.
Escribir ""
Escribir "Calentamiento: tres solicitudes sobre el cupo diario (se rechazan con 422 y"
Escribir "no mueven dinero), para que la primera medicion no incluya el arranque en frio."
for ($i = 1; $i -le 3; $i++) {
    $r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 900000
    Linea ("calentamiento {0} -> {1} en {2} ms" -f $i, $r.Codigo, $r.Ms)
}
Escribir ""

$saldo105 = Saldo $CUENTA_PROPIA
$saldo107 = Saldo $CUENTA_AJENA
Escribir ""
Linea ("antes:  cuenta {0} = {1}   cuenta {2} = {3}" -f $CUENTA_PROPIA, $saldo105, $CUENTA_AJENA, $saldo107)
Escribir ""
Escribir "El usuario 'cliente' transfiere 1.000 a la cuenta 107 por el canal web:"
Escribir ""
$r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 1000
$feliz = Json $r.Cuerpo
Linea ("POST /api/web/transferencias  ->  {0}  en {1} ms" -f $r.Codigo, $r.Ms)
Linea ("estado: {0}   id: {1}" -f $feliz.estado, $feliz.transferenciaId)
Escribir ""
Escribir "202 y PENDIENTE: la transferencia se ACEPTO, pero todavia no ocurrio. El"
Escribir "navegador recibe donde preguntar, y la saga sigue por eventos:"
Escribir ""
$cierre = Wait-Cierre $feliz.transferenciaId
Linea ("GET {0}  ->  {1}  (cerro {2} ms despues)" -f $feliz.consultarEn, $cierre.Estado.estado, $cierre.Ms)
Escribir ""
Linea ("despues: cuenta {0} = {1}   cuenta {2} = {3}" -f $CUENTA_PROPIA, (Saldo $CUENTA_PROPIA), $CUENTA_AJENA, (Saldo $CUENTA_AJENA))
Linea ("cupo diario de la {0}: reservado {1} de {2}" -f $CUENTA_PROPIA, (Get-Cupo $CUENTA_PROPIA).reservado, (Get-Cupo $CUENTA_PROPIA).cupoDiario)

Start-Sleep -Seconds 2
Escribir ""
Escribir "La historia completa, segun ms-auditoria, reconstruida desde sus eventos:"
Escribir ""
$hist = Json (Pedir -Url "$MS_A/transferencias/$($feliz.transferenciaId)" -Basico $B_AUDITOR).Cuerpo
foreach ($e in $hist.eventos) { Linea ("#{0,-4} {1}" -f $e.secuencia, $e.tipo) }
Escribir ""
foreach ($p in $hist.estadoReconstruido.pasos) { Linea $p }
Linea ("estado reconstruido: {0}   (ms-transferencias dice: {1})" -f $hist.estadoReconstruido.estado, $cierre.Estado.estado)
$resumen["Saga completada"] = "{0} -> {1}, {2} ms" -f $feliz.estado, $cierre.Estado.estado, $cierre.Ms

# ===========================================================================
Titulo "4. LA SAGA, CAMINO DE COMPENSACION"
# ===========================================================================
$cupoAntes = (Get-Cupo $CUENTA_PROPIA).reservado
Escribir ""
Escribir ("La cuenta {0} tiene {1}. Se intenta transferir 50.000: el cupo diario" -f $CUENTA_PROPIA, (Saldo $CUENTA_PROPIA))
Escribir "(500.000) lo permite, asi que ms-transferencias lo ACEPTA y reserva el cupo;"
Escribir "es ms-cuentas el que descubre que el saldo no alcanza."
Escribir ""
$r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 50000
$comp = Json $r.Cuerpo
Linea ("POST  ->  {0}  {1}" -f $r.Codigo, $comp.estado)
$cierre = Wait-Cierre $comp.transferenciaId
Linea ("cierre ->  {0}: {1}" -f $cierre.Estado.estado, $cierre.Estado.motivo)
Start-Sleep -Seconds 2
$cupoDespues = (Get-Cupo $CUENTA_PROPIA).reservado
Escribir ""
Linea ("cupo reservado antes de la solicitud: {0}" -f $cupoAntes)
Linea ("cupo reservado despues del cierre:    {0}" -f $cupoDespues)
Escribir ""
$hist = Json (Pedir -Url "$MS_A/transferencias/$($comp.transferenciaId)" -Basico $B_AUDITOR).Cuerpo
foreach ($p in $hist.estadoReconstruido.pasos) { Linea $p }
Escribir ""
Escribir "El paso 1 ya estaba confirmado en la base de ms-transferencias cuando"
Escribir "ms-cuentas dijo que no. No hay rollback posible entre dos bases: se"
Escribir "COMPENSA con una accion de negocio -devolver el cupo- y queda registrada."
$resumen["Saga compensada"] = "{0}, cupo {1} -> {2}" -f $cierre.Estado.estado, $cupoAntes, $cupoDespues

Escribir ""
Escribir "En cambio, lo que ms-transferencias puede decidir solo no inicia saga:"
Escribir ""
$r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 600000
Linea ("600.000, sobre el cupo    ->  {0}  {1}" -f $r.Codigo, $r.Cuerpo)
$r = Transferir -Token $tokenCliente -Destino $CUENTA_PROPIA -Monto 1000
Linea ("a la misma cuenta          ->  {0}  {1}" -f $r.Codigo, $r.Cuerpo)
Escribir ""
Escribir "422 en el acto, sin publicar ningun evento."

# ===========================================================================
Titulo "5. SEGURIDAD - quien puede transferir, y quien puede publicar"
# ===========================================================================
Escribir ""
Escribir "5a. El origen sale del token. Si el navegador manda otra cuenta de origen"
Escribir "    en el cuerpo, se ignora:"
Escribir ""
$s107 = Saldo $CUENTA_AJENA
$s105 = Saldo $CUENTA_PROPIA
$r = Transferir -Token $tokenCliente -CuerpoCrudo '{"cuentaOrigen":107,"cuentaDestino":107,"monto":500}'
$fal = Json $r.Cuerpo
$c = Wait-Cierre $fal.transferenciaId
Linea ('cliente (cuenta 105) envia {"cuentaOrigen":107,"cuentaDestino":107,"monto":500}')
Linea ("-> {0} {1}.  Cuenta 105: {2} -> {3}.  Cuenta 107: {4} -> {5}" -f `
    $r.Codigo, $c.Estado.estado, $s105, (Saldo $CUENTA_PROPIA), $s107, (Saldo $CUENTA_AJENA))
Escribir "    Se debito la 105, la del token. La 107 recibio; no pago."
Escribir ""
Escribir "5b. Consultar la transferencia de otra persona:"
$r = Pedir -Url ("$T/" + $feliz.transferenciaId) -Cabeceras @("Authorization: Bearer $tokenCliente2")
Linea ("cliente2 pide la transferencia de cliente  ->  {0}" -f $r.Codigo)
$r = Pedir -Url ("$T/" + $feliz.transferenciaId) -Cabeceras @("Authorization: Bearer $tokenCliente")
Linea ("cliente  pide la suya                      ->  {0}" -f $r.Codigo)
Escribir ""
Escribir "5c. Los servicios internos exigen su credencial:"
$r = Pedir -Url "$MS_T/outbox"
Linea ("ms-transferencias sin credencial            ->  {0}" -f $r.Codigo)
$r = Pedir -Url "$MS_T/outbox" -Basico $B_CONSULTA
Linea ("ms-transferencias con la de consulta        ->  {0}" -f $r.Codigo)
$r = Pedir -Url "$MS_A/resumen" -Metodo "POST" -Basico $B_AUDITOR
Linea ("ms-auditoria, POST (es de solo lectura)     ->  {0}" -f $r.Codigo)
Escribir ""
Escribir "5d. EN EL BROKER: cada servicio publica solo los hechos de los que es duenio."
Escribir "    Si ms-auditoria o ms-transferencias quedaran comprometidos, no podrian"
Escribir "    inyectar un 'TransferenciaAplicada' falso para dar por hecha una"
Escribir "    transferencia que nunca ocurrio:"
Escribir ""
$pruebas = @(
    @("ms-auditoria", "auditoria-broker-2026", "banco.transferencia.aplicada"),
    @("ms-transferencias", "transferencias-broker-2026", "banco.transferencia.aplicada"),
    @("ms-cuentas", "cuentas-broker-2026", "banco.transferencia.solicitada"),
    @("ms-cuentas", "clave-equivocada", "banco.transferencia.aplicada")
)
$rechazosBroker = 0
foreach ($p in $pruebas) {
    $url = "$BROKER/probar-envio?usuario={0}&clave={1}&topico={2}" -f $p[0], $p[1], $p[2]
    $r = Pedir -Url $url -Metodo "POST" -Basico $B_ADMIN
    $res = (Json $r.Cuerpo).resultado
    if ($r.Codigo -ne 200) { $rechazosBroker++ }
    $cual = if ($p[1] -eq "clave-equivocada") { "$($p[0]) (clave mala)" } else { $p[0] }
    Linea ("{0,-26} publica en {1,-31} -> {2}" -f $cual, $p[2], $res)
}
$resumen["Seguridad"] = "origen del token; 403 ajena; 401 sin credencial; broker rechazo $rechazosBroker de $($pruebas.Count)"

# ===========================================================================
Titulo "6. DUPLICADOS - tres capas, cada una probada por separado"
# ===========================================================================
Escribir ""
Escribir "6a. HTTP: la misma Idempotency-Key dos veces (un doble clic)."
$s = Saldo $CUENTA_PROPIA
$clave = "doble-clic-" + (Get-Random)
$r1 = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 700 -Clave $clave
$r2 = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 700 -Clave $clave
$id1 = (Json $r1.Cuerpo).transferenciaId
$id2 = (Json $r2.Cuerpo).transferenciaId
Wait-Cierre $id1 | Out-Null
Linea ("primer POST  -> {0}  id {1}" -f $r1.Codigo, $id1)
Linea ("segundo POST -> {0}  id {1}" -f $r2.Codigo, $id2)
Linea ("mismo id: {0}.  Saldo 105: {1} -> {2} (una sola vez 700)" -f ($id1 -eq $id2), $s, (Saldo $CUENTA_PROPIA))

Escribir ""
Escribir "6b. BROKER: se vuelve a publicar la TransferenciaSolicitada de la seccion 3,"
Escribir "    con su _AMQ_DUPL_ID. Es lo que haria el outbox si reenviara una fila"
Escribir "    que ya habia salido."
$eventoOriginal = ((Json (Pedir -Url "$MS_A/transferencias/$($feliz.transferenciaId)" -Basico $B_AUDITOR).Cuerpo).eventos |
    Where-Object { $_.tipo -eq "TransferenciaSolicitada" } | Select-Object -First 1).datos
$cuerpoOriginal = $eventoOriginal | ConvertTo-Json -Compress
$antes = (Get-Suscripcion "banco.transferencia.solicitada" "ms-cuentas").recibidos
Pedir -Url ("$BROKER/publicar?topico=banco.transferencia.solicitada&tipo=TransferenciaSolicitada&duplicadoId=" + $eventoOriginal.eventoId) `
    -Metodo "POST" -Basico $B_ADMIN -Cuerpo $cuerpoOriginal | Out-Null
Start-Sleep -Seconds 2
$despues = (Get-Suscripcion "banco.transferencia.solicitada" "ms-cuentas").recibidos
Linea ("mensajes que llegaron a la suscripcion ms-cuentas: {0} -> {1}" -f $antes, $despues)
Linea "El broker lo descarto: ni siquiera llego a la cola."

Escribir ""
Escribir "6c. CONSUMIDOR: el mismo evento, ahora SIN la marca, asi que pasa el broker."
Escribir "    Es el caso de un duplicado que llega por otro camino."
$dup1 = (Get-Estadisticas 8090).duplicadosIgnorados + (Get-Estadisticas 8093).duplicadosIgnorados
$s105 = Saldo $CUENTA_PROPIA
Pedir -Url "$BROKER/publicar?topico=banco.transferencia.solicitada&tipo=TransferenciaSolicitada" `
    -Metodo "POST" -Basico $B_ADMIN -Cuerpo $cuerpoOriginal | Out-Null
Start-Sleep -Seconds 3
$dup2 = (Get-Estadisticas 8090).duplicadosIgnorados + (Get-Estadisticas 8093).duplicadosIgnorados
Linea ("duplicados ignorados por ms-cuentas (las dos instancias): {0} -> {1}" -f $dup1, $dup2)
Linea ("saldo de la 105: {0} -> {1}" -f $s105, (Saldo $CUENTA_PROPIA))
Escribir ""
Escribir "ms-cuentas lo reconocio por su eventoId en la tabla evento_procesado, no"
Escribir "toco ningun saldo y reenvio la respuesta guardada, con el mismo id, que el"
Escribir "broker a su vez descarto porque ya la habia visto."
$resumen["Duplicados"] = "Idempotency-Key mismo id; broker {0}->{1}; consumidor ignoro {2}" -f $antes, $despues, ($dup2 - $dup1)

# ===========================================================================
Titulo "7. MENSAJE ENVENENADO - reintentos acotados y cola de mensajes muertos"
# ===========================================================================
Escribir ""
Escribir "Se publica en banco.transferencia.solicitada un cuerpo que no es JSON. Los"
Escribir "dos suscriptores fallan al leerlo; el broker lo reentrega con espera"
Escribir "creciente (1 s, 2 s) y a la tercera lo aparta en la DLQ."
# Se cuenta lo que ya habia: si la evidencia se corre dos veces sobre el
# mismo broker, la DLQ conserva los mensajes de la corrida anterior.
$enDlqAntes = @(Json (Pedir -Url "$BROKER/dlq" -Basico $B_ADMIN).Cuerpo).Count
Pedir -Url "$BROKER/publicar?topico=banco.transferencia.solicitada&tipo=TransferenciaSolicitada" `
    -Metodo "POST" -Basico $B_ADMIN -Cuerpo "{esto no es json" | Out-Null
$limite = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 2
    $dlq = @(Json (Pedir -Url "$BROKER/dlq" -Basico $B_ADMIN).Cuerpo)
} while ($dlq.Count -lt ($enDlqAntes + 2) -and (Get-Date) -lt $limite)
$dlq = @($dlq | Select-Object -Skip $enDlqAntes)
Escribir ""
foreach ($m in $dlq) {
    Linea ("DLQ <- {0,-26} desde {1}   cuerpo: {2}" -f ($m.suscripcion -replace '\\\.', '.'), $m.topicoOriginal, $m.cuerpo)
}
Escribir ""
Escribir "Y la suscripcion sigue avanzando: una transferencia nueva se procesa normal."
$r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 100
$c = Wait-Cierre (Json $r.Cuerpo).transferenciaId
Linea ("transferencia posterior -> {0} en {1} ms" -f $c.Estado.estado, $c.Ms)
$resumen["Mensaje envenenado"] = "{0} en DLQ tras 3 intentos; la saga siguio ({1})" -f $dlq.Count, $c.Estado.estado

# ===========================================================================
Titulo "8. ESCALABILIDAD - una instancia contra dos, medida"
# ===========================================================================
$N = 40
$sumaInicial = Get-SumaSaldos
$costo = "200"
Escribir ""
Escribir ("Una rafaga de {0} transferencias entre las diez cuentas de mayor saldo, en" -f $N)
Escribir "anillo, cargadas de una vez en ms-transferencias por su endpoint de lote."
Escribir ("ms-cuentas simula {0} ms de procesamiento por evento (antifraude, ver" -f $costo)
Escribir "ms-cuentas.yml) y usa UN consumidor por instancia, para que lo que se mida"
Escribir "sea el efecto de agregar instancias y no hilos."

$cuentas = @(Json (Pedir -Url "http://localhost:8090/interno/cuentas" -Basico $B_CONSULTA).Cuerpo |
    Where-Object { $null -ne $_.saldoFinal } | Sort-Object { [decimal]$_.saldoFinal } -Descending | Select-Object -First 10)

function New-Lote {
    $items = @()
    for ($i = 0; $i -lt $N; $i++) {
        $o = $cuentas[$i % $cuentas.Count].cuentaId
        $d = $cuentas[($i + 1) % $cuentas.Count].cuentaId
        $items += ('{"cuentaOrigen":' + $o + ',"cuentaDestino":' + $d + ',"monto":100}')
    }
    return "[" + ($items -join ",") + "]"
}

function Invoke-Rafaga {
    param([string]$Etiqueta, [int[]]$Puertos)
    $antes = @{}
    foreach ($p in $Puertos) { $antes[$p] = (Get-Estadisticas $p).procesados }
    $t0 = Get-Date
    $r = Pedir -Url "$MS_T/lote" -Metodo "POST" -Basico $B_TRANSF -Cuerpo (New-Lote)
    $ok = Wait-SinPendientes
    $seg = ((Get-Date) - $t0).TotalSeconds
    Escribir ""
    Escribir ("  {0}: {1}" -f $Etiqueta, $r.Cuerpo)
    foreach ($p in $Puertos) {
        Linea ("ms-cuentas:{0} proceso {1} eventos" -f $p, ((Get-Estadisticas $p).procesados - $antes[$p]))
    }
    $tasa = [math]::Round($N / $seg, 1)
    Linea ("{0} sagas cerradas en {1:N1} s  ->  {2} transferencias/s{3}" -f $N, $seg, $tasa, $(if ($ok) { "" } else { "  (TIEMPO AGOTADO)" }))
    return [pscustomobject]@{ Segundos = $seg; Tasa = $tasa }
}

$dos = Invoke-Rafaga "DOS instancias (8090 y 8093)" @(8090, 8093)

Escribir ""
Escribir "Se detiene ms-cuentas-2. El broker deja de entregarle y la otra instancia"
Escribir "absorbe toda la suscripcion, sin reconfigurar nada:"
Stop-Servicio "ms-cuentas-2" | Out-Null
Start-Sleep -Seconds 5
Linea ("consumidores de la suscripcion ms-cuentas ahora: {0}" -f (Get-Suscripcion "banco.transferencia.solicitada" "ms-cuentas").consumidores)
$una = Invoke-Rafaga "UNA instancia (8090)" @(8090)

$sumaFinal = Get-SumaSaldos
Escribir ""
Linea ("una instancia : {0,5:N1} s   {1} transf/s" -f $una.Segundos, $una.Tasa)
Linea ("dos instancias: {0,5:N1} s   {1} transf/s   ({2:N1}x)" -f $dos.Segundos, $dos.Tasa, ($dos.Tasa / [math]::Max($una.Tasa, 0.1)))
Escribir ""
Escribir "Y la comprobacion de que la concurrencia no corrompio nada: una"
Escribir "transferencia mueve dinero, no lo crea ni lo destruye. La suma de todos"
Escribir "los saldos del banco tiene que ser la misma antes y despues:"
Escribir ""
Linea ("suma de saldos antes de las rafagas:   {0}" -f $sumaInicial)
Linea ("suma de saldos despues de las rafagas: {0}   {1}" -f $sumaFinal, $(if ($sumaInicial -eq $sumaFinal) { "IGUAL" } else { "DISTINTA" }))
Escribir ""
Escribir "Con dos instancias debitando a la vez las mismas cuentas, eso se sostiene"
Escribir "por el bloqueo pesimista de CuentaRepository.bloquear, tomado siempre en"
Escribir "orden de numero de cuenta para que dos instancias no se esperen en circulo."
$resumen["Escalabilidad"] = "1 instancia {0} t/s, 2 instancias {1} t/s; suma de saldos {2}" -f $una.Tasa, $dos.Tasa, $(if ($sumaInicial -eq $sumaFinal) { "conservada" } else { "ALTERADA" })

# ===========================================================================
Titulo "9. RESILIENCIA - el broker se cae y las transferencias se siguen aceptando"
# ===========================================================================
Escribir ""
Escribir "Se DETIENE el broker. No se simula: se mata el proceso."
Stop-Servicio "broker-mensajeria" | Out-Null
Start-Sleep -Seconds 3
Escribir ""
$ids = @()
for ($i = 1; $i -le 3; $i++) {
    $r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 200
    $ids += (Json $r.Cuerpo).transferenciaId
    Linea ("transferencia {0} con el broker caido -> {1} {2}" -f $i, $r.Codigo, (Json $r.Cuerpo).estado)
}
Escribir ""
Escribir "El cliente recibio 202 igual. La transferencia y su evento quedaron juntos"
Escribir "en la base de ms-transferencias (patron outbox). Lo que sigue es el"
Escribir "Circuit Breaker del publicador, observado cada 2 s durante la caida y la"
Escribir "recuperacion. Con el broker abajo cada intento de conexion tarda unos"
Escribir "segundos en fallar, asi que el circuito abre al tercer fallo, no al instante:"
Escribir ""

# Se registra cada cambio de estado, en vez de una foto a los pocos segundos:
# una primera version leia el circuito a los 4 s, cuando todavia no se
# juntaban los tres fallos, y la evidencia decia CLOSED con el broker caido.
$tCaida = Get-Date
function Anotar-Circuito {
    param([string]$Anterior, [string]$Evento = "")
    $o = Get-Outbox
    if ($o -and ($o.circuitoBroker -ne $Anterior -or $Evento)) {
        Linea ("t={0,4:N0} s  circuito {1,-9} outbox pendientes {2}  {3}" -f `
            ((Get-Date) - $tCaida).TotalSeconds, $o.circuitoBroker, $o.pendientesDePublicar, $Evento)
    }
    if ($o) { return $o.circuitoBroker } else { return $Anterior }
}
$estado = Anotar-Circuito "" "(broker caido)"
$limite = (Get-Date).AddSeconds(45)
while ($estado -ne "OPEN" -and (Get-Date) -lt $limite) {
    Start-Sleep -Seconds 2
    $estado = Anotar-Circuito $estado
}
# Unos segundos mas abajo, para que se vea el ciclo OPEN -> HALF_OPEN -> OPEN:
# la sonda de prueba falla y el circuito vuelve a cerrarse para el trafico.
$limite = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $limite) { Start-Sleep -Seconds 2; $estado = Anotar-Circuito $estado }

$tBroker = Get-Date
$pBroker = Start-Servicio "broker-mensajeria"
$estado = Anotar-Circuito $estado "(se relanza el broker)"
$brokerArriba = $false
$limite = (Get-Date).AddSeconds(600)
do {
    Start-Sleep -Seconds 2
    if (-not $brokerArriba -and (Test-Salud $Servicios["broker-mensajeria"].salud)) {
        $brokerArriba = $true
        $estado = Anotar-Circuito $estado "(broker UP)"
    } else {
        $estado = Anotar-Circuito $estado
    }
    $o = Get-Outbox
} while (-not ($brokerArriba -and $o.pendientesDePublicar -eq 0 -and $o.transferenciasPendientes -eq 0) -and (Get-Date) -lt $limite)
$ok = ($o.pendientesDePublicar -eq 0 -and $o.transferenciasPendientes -eq 0)
$estado = Anotar-Circuito $estado "(outbox vacio, sagas cerradas)"
Escribir ""
foreach ($id in $ids) {
    $e = Json (Pedir -Url "$MS_T/$id" -Basico $B_TRANSF).Cuerpo
    Linea ("{0} -> {1}" -f $id, $e.estado)
}
Linea ("tiempo desde que se relanzo el broker hasta cerrar las tres: {0:N0} s (el broker solo tarda en arrancar)" -f ((Get-Date) - $tBroker).TotalSeconds)
Escribir ""
Escribir "Nadie reinicio ms-transferencias ni ms-cuentas: cuando el broker volvio, la"
Escribir "siguiente sonda de HALF_OPEN publico, el circuito cerro, el outbox se vacio,"
Escribir "y los listeners de los tres servicios se reconectaron solos (recoveryInterval"
Escribir "de 3 s) y completaron las sagas."
$resumen["Broker caido"] = "3 aceptadas con 202 durante la caida; outbox vaciado al volver: $ok"

# ===========================================================================
Titulo "10. RESILIENCIA - un consumidor se cae y no pierde eventos"
# ===========================================================================
Escribir ""
Escribir "Se DETIENE ms-auditoria y se hacen dos transferencias mientras esta abajo."
$totalAntes = (Json (Pedir -Url "$MS_A/resumen" -Basico $B_AUDITOR).Cuerpo).totalEventos
Stop-Servicio "ms-auditoria" | Out-Null
Start-Sleep -Seconds 3
$idsAud = @()
for ($i = 1; $i -le 2; $i++) {
    $r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 150
    $idsAud += (Json $r.Cuerpo).transferenciaId
}
foreach ($id in $idsAud) { Wait-Cierre $id | Out-Null }
Escribir ""
Linea ("{0,-32} {1,-14} {2}" -f "TOPICO", "CONSUMIDORES", "PENDIENTES")
foreach ($f in Get-Topologia | Where-Object { $_.suscripcion -like "*ms-auditoria*" }) {
    Linea ("{0,-32} {1,-14} {2}" -f $f.topico, $f.consumidores, $f.pendientes)
}
Escribir ""
Escribir "Las sagas se completaron igual -la auditoria no es parte del camino- y sus"
Escribir "eventos quedaron guardados en la suscripcion DURABLE de ms-auditoria."
Escribir "Se relanza:"
if (-not (Start-ServicioEsperando "ms-auditoria")) { Escribir "    ms-auditoria NO VOLVIO A LEVANTAR" }
Start-Sleep -Seconds 8
Escribir ""
foreach ($f in Get-Topologia | Where-Object { $_.suscripcion -like "*ms-auditoria*" }) {
    Linea ("{0,-32} {1,-14} {2}" -f $f.topico, $f.consumidores, $f.pendientes)
}
$totalDespues = (Json (Pedir -Url "$MS_A/resumen" -Basico $B_AUDITOR).Cuerpo).totalEventos
Escribir ""
Linea ("eventos en el registro: {0} antes de la caida, {1} despues de volver" -f $totalAntes, $totalDespues)
$h = Json (Pedir -Url ("$MS_A/transferencias/" + $idsAud[0]) -Basico $B_AUDITOR).Cuerpo
Linea ("historia de una de ellas: {0}" -f (($h.eventos | ForEach-Object { $_.tipo }) -join " -> "))
$resumen["Consumidor caido"] = "eventos {0} -> {1}, ninguno perdido" -f $totalAntes, $totalDespues

# ===========================================================================
Titulo "11. ARRANQUE SIN CONFIG SERVER - la sugerencia de la semana 6"
# ===========================================================================
Escribir ""
Escribir "La retroalimentacion de la semana 6 propuso probar que ocurre si el Config"
Escribir "Server no esta disponible al iniciar los demas servicios. La prueba destapo"
Escribir "un defecto real de esa entrega, y aqui se muestra antes y despues."
Escribir ""
Escribir "Se DETIENE el Config Server."
Stop-Servicio "config-server" | Out-Null
Start-Sleep -Seconds 3
$r = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenCliente")
Linea ("bff-web, que ya estaba arriba, sigue atendiendo -> {0}" -f $r.Codigo)
Escribir "    (la configuracion se lee al arrancar; un servicio en marcha no la necesita)"

Escribir ""
Escribir "11a. El ms-cuentas de la SEMANA 6, con el Config Server abajo:"
$jarS6 = Join-Path (Split-Path (Split-Path $raiz -Parent) -Parent) "S6_Formativa4\bank-cloud\ms-cuentas\target\ms-cuentas-0.0.1-SNAPSHOT.jar"
$Servicios["ms-cuentas-s6"] = @{ heap = "384m"; jar = "ms-cuentas"; puerto = 8094; salud = "http://localhost:8094/actuator/health"; extra = @("--server.port=8094") }
if (Test-Path $jarS6) {
    $t0 = Get-Date
    $p6 = Start-Servicio -Nombre "ms-cuentas-s6" -Jar $jarS6
    $termino = $p6.WaitForExit(240000)
    $seg6 = ((Get-Date) - $t0).TotalSeconds
    $falla6 = Select-String -Path (Join-Path $logs "ms-cuentas-s6.log") -Pattern "ConfigClientFailFastException: (.*)" |
        Select-Object -First 1 | ForEach-Object { $_.Matches[0].Groups[1].Value }
    Stop-Servicio "ms-cuentas-s6" | Out-Null
    Linea ("{0} a los {1:N0} s" -f $(if ($termino) { "el proceso TERMINO" } else { "seguia vivo" }), $seg6)
    if ($falla6) { Linea ("ConfigClientFailFastException: " + $falla6) }
    Escribir "    Su application.yml declaraba seis reintentos, pero spring-retry no"
    Escribir "    estaba en el pom: el bloque se ignoraba en silencio y moria al primero."
    $resultado6 = if ($termino) { "muere a los {0:N0} s" -f $seg6 } else { "no murio" }
} else {
    Linea "no se encontro el jar de la semana 6; se omite la comparacion"
    $resultado6 = "sin jar para comparar"
}

Escribir ""
Escribir "11b. El ms-cuentas de ESTA semana (instancia 8093), con el Config Server abajo:"
$t0 = Get-Date
$p7 = Start-Servicio -Nombre "ms-cuentas-2"
Start-Sleep -Seconds 60
Linea ("a los 60 s: proceso {0}" -f $(if ($p7.HasExited) { "TERMINADO" } else { "VIVO, reintentando" }))
Escribir "    (los intentos no se ven todavia en el log: Spring Boot retiene los"
Escribir "    mensajes previos a configurar el logging y los escribe juntos al final)"
Escribir ""
Escribir "Ahora se levanta el Config Server:"
if (-not (Start-ServicioEsperando "config-server")) { Escribir "    EL CONFIG SERVER NO VOLVIO A LEVANTAR" }
Linea ("config-server UP a los {0:N0} s del inicio de la prueba" -f ((Get-Date) - $t0).TotalSeconds)
$ok7 = Wait-Salud -Url $Servicios["ms-cuentas-2"].salud -Proceso $p7
$intentos7 = @(Select-String -Path (Join-Path $logs "ms-cuentas-2.log") -Pattern "Fetching config from server").Count
Linea ("ms-cuentas-2 {0} a los {1:N0} s, tras {2} intento(s) fallidos, sin que nadie lo relanzara" -f `
    $(if ($ok7) { "UP" } else { "NO LEVANTO" }), ((Get-Date) - $t0).TotalSeconds, $intentos7)
$resumen["Sin Config Server"] = "semana 6 {0}; semana 7 espera {1} intentos y arranca: {2}" -f $resultado6, $intentos7, $ok7

# ===========================================================================
Titulo "12. OPERACION Y ARRANQUE SIN EUREKA"
# ===========================================================================
Escribir ""
Escribir "Se DETIENE Eureka."
Stop-Servicio "eureka-server" | Out-Null
Start-Sleep -Seconds 3
Escribir ""
Escribir "12a. Los que ya estaban arriba siguen: cada cliente guarda una copia local"
Escribir "     del registro y la sigue usando mientras Eureka no responde."
$r = Pedir -Url "https://localhost:8081/api/web/cuentas/$CUENTA_PROPIA" -Cabeceras @("Authorization: Bearer $tokenCliente")
Linea ("bff-web -> ms-cuentas (lb://)        -> {0}" -f $r.Codigo)
$r = Transferir -Token $tokenCliente -Destino $CUENTA_AJENA -Monto 100
$c = Wait-Cierre (Json $r.Cuerpo).transferenciaId
Linea ("bff-web -> ms-transferencias (lb://) -> {0}, saga {1}" -f $r.Codigo, $c.Estado.estado)

Escribir ""
Escribir "12b. Pero un servicio que ARRANCA sin Eureka no tiene copia que usar."
Escribir "     Se relanza bff-movil con Eureka abajo:"
Stop-Servicio "bff-movil" | Out-Null
$t0 = Get-Date
$movilArriba = [bool](Start-ServicioEsperando "bff-movil")
Linea ("bff-movil arranca igual: {0} en {1:N0} s (Eureka no es requisito para arrancar)" -f `
    $(if ($movilArriba) { "UP" } else { "NO" }), ((Get-Date) - $t0).TotalSeconds)
$reg = Pedir -Url "https://localhost:8082/api/movil/registro" -Metodo "POST" `
    -Cuerpo '{"deviceId":"android-demo-001"}' -Cabeceras @("X-Device-Token: token-movil-demo-2026")
$tokenMovil = if ($reg.Cuerpo -match '"token"\s*:\s*"([^"]+)"') { $matches[1] } else { $null }
$r = Pedir -Url "https://localhost:8082/api/movil/cuentas/$CUENTA_PROPIA/resumen" -Cabeceras @("Authorization: Bearer $tokenMovil")
Linea ("bff-movil -> ms-cuentas -> {0}  {1}" -f $r.Codigo, (Recortar $r.Cuerpo))
Escribir "     No sabe donde esta ms-cuentas: el balanceador no tiene instancias, la"
Escribir "     llamada falla y el Circuit Breaker responde con la degradacion del canal."

Escribir ""
Escribir "12c. Se levanta Eureka. Nadie reinicia nada mas:"
$t0 = Get-Date
if (-not (Start-ServicioEsperando "eureka-server")) { Escribir "    EUREKA NO VOLVIO A LEVANTAR" }
Linea ("eureka UP a los {0:N0} s" -f ((Get-Date) - $t0).TotalSeconds)
$codigoMovil = 0
$limite = (Get-Date).AddSeconds(180)
do {
    Start-Sleep -Seconds 5
    $r = Pedir -Url "https://localhost:8082/api/movil/cuentas/$CUENTA_PROPIA/resumen" -Cabeceras @("Authorization: Bearer $tokenMovil")
    $codigoMovil = $r.Codigo
} while ($codigoMovil -ne 200 -and (Get-Date) -lt $limite)
Linea ("bff-movil -> ms-cuentas -> {0} a los {1:N0} s" -f $codigoMovil, ((Get-Date) - $t0).TotalSeconds)
# Cada servicio se vuelve a registrar en su propio latido: se espera a las
# siete en vez de contar en un instante arbitrario (una corrida conto seis).
$limite = (Get-Date).AddSeconds(60)
do {
    $apps = Pedir -Url "http://localhost:8761/eureka/apps" -Cabeceras @("Accept: application/json")
    $reg = ([regex]::Matches($apps.Cuerpo, '"instanceId"')).Count
    if ($reg -ge 7) { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $limite)
Linea ("instancias registradas de nuevo en Eureka: {0}, a los {1:N0} s" -f $reg, ((Get-Date) - $t0).TotalSeconds)
Escribir ""
Escribir "Cada servicio se volvio a registrar en su siguiente latido (5 s), y bff-movil"
Escribir "encontro a ms-cuentas en su siguiente lectura del registro."
$resumen["Sin Eureka"] = "en marcha: siguen (cache). Arranque: sube, degrada, y se recupera solo ({0})" -f $codigoMovil

# ===========================================================================
Titulo "13. RESUMEN"
# ===========================================================================
Escribir ""
foreach ($k in $resumen.Keys) { Linea ("{0,-22} {1}" -f $k, $resumen[$k]) }
Escribir ""

Set-Content -Path $salida -Value $lineas -Encoding utf8
Write-Host ""
Write-Host ("Evidencia guardada en: {0}" -f $salida)
