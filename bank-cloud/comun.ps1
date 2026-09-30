# ---------------------------------------------------------------------------
# Funciones compartidas por levantar.ps1 y probar_eventos.ps1.
#
# Se carga con punto:   . (Join-Path $PSScriptRoot "comun.ps1")
#
# En la semana 6 cada script tenia su propia copia de la sonda de salud y de
# la forma de arrancar un jar. Con diez procesos, y con una evidencia que
# detiene y relanza cinco de ellos, dos copias de lo mismo son dos lugares
# donde corregir cada trampa de PowerShell 5.1 que aparezca.
# ---------------------------------------------------------------------------

$raiz = $PSScriptRoot
$archivoPids = Join-Path $raiz ".procesos.txt"
$logs = Join-Path $raiz "logs"

$java = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
if (-not (Test-Path $java)) {
    throw "No se encuentra el JDK en $java. Ajusta la ruta al principio de comun.ps1."
}

# curl.exe y no Invoke-RestMethod: el de PowerShell 5.1 no completa el
# handshake TLS contra los certificados autofirmados de los BFF y falla con un
# error que parece un servicio caido. Ver el encabezado de levantar.ps1.
$curl = Join-Path $env:SystemRoot "System32\curl.exe"
if (-not (Test-Path $curl)) {
    throw "No se encuentra curl.exe en $curl. Viene con Windows 10 y 11."
}

# ---------------------------------------------------------------------------
# El catalogo: cada proceso del ecosistema
# ---------------------------------------------------------------------------
# El heap se fija por servicio: la JVM toma por defecto un cuarto de la RAM, y
# diez procesos reservarian 40 GB sobre un equipo de 16. Ver levantar.ps1.
#
# ms-cuentas-2 es el MISMO jar que ms-cuentas en otro puerto: la segunda
# instancia que demuestra la escalabilidad horizontal. Eureka la registra
# como otra instancia de la misma aplicacion, y el broker le reparte eventos
# de la misma suscripcion.
$Servicios = [ordered]@{
    "config-server"     = @{ heap = "192m"; jar = "config-server";     puerto = 7888; salud = "http://localhost:7888/actuator/health";  extra = @() }
    "eureka-server"     = @{ heap = "192m"; jar = "eureka-server";     puerto = 8761; salud = "http://localhost:8761/actuator/health";  extra = @() }
    "broker-mensajeria" = @{ heap = "256m"; jar = "broker-mensajeria"; puerto = 8161; salud = "http://localhost:8161/actuator/health";  extra = @() }
    "ms-cuentas"        = @{ heap = "384m"; jar = "ms-cuentas";        puerto = 8090; salud = "http://localhost:8090/actuator/health";  extra = @() }
    "ms-cuentas-2"      = @{ heap = "384m"; jar = "ms-cuentas";        puerto = 8093; salud = "http://localhost:8093/actuator/health";  extra = @("--server.port=8093") }
    "ms-transferencias" = @{ heap = "256m"; jar = "ms-transferencias"; puerto = 8091; salud = "http://localhost:8091/actuator/health";  extra = @() }
    "ms-auditoria"      = @{ heap = "256m"; jar = "ms-auditoria";      puerto = 8092; salud = "http://localhost:8092/actuator/health";  extra = @() }
    "bff-web"           = @{ heap = "256m"; jar = "bff-web";           puerto = 8081; salud = "https://localhost:8081/actuator/health"; extra = @() }
    "bff-movil"         = @{ heap = "192m"; jar = "bff-movil";         puerto = 8082; salud = "https://localhost:8082/actuator/health"; extra = @() }
    "bff-cajero"        = @{ heap = "192m"; jar = "bff-cajero";        puerto = 8083; salud = "https://localhost:8083/actuator/health"; extra = @() }
}

function Get-RutaJar {
    param([string]$Modulo)
    Join-Path $raiz ("{0}\target\{0}-0.0.1-SNAPSHOT.jar" -f $Modulo)
}

# ---------------------------------------------------------------------------
# Sonda de salud
# ---------------------------------------------------------------------------
# Tope de 600 s: en este equipo ms-cuentas tarda entre 140 y 240 s en arrancar
# solo, y con tres JVM arrancando a la vez en la etapa 5 una paso los 300 s
# que tenia antes, sin ningun error: solo lenta. Un tope alto no alarga nada
# cuando todo va bien: se sale en cuanto hay UP.
#
# Si se le pasa el proceso, deja de esperar en cuanto el proceso muere: no
# tiene sentido esperar cinco minutos la salud de una JVM que ya termino.
function Wait-Salud {
    param([string]$Url, [int]$SegundosMaximo = 600, [System.Diagnostics.Process]$Proceso)
    $limite = (Get-Date).AddSeconds($SegundosMaximo)
    while ((Get-Date) -lt $limite) {
        $r = & $curl -s -k --max-time 5 $Url 2>$null
        if ($LASTEXITCODE -eq 0 -and $r -match '"status"\s*:\s*"UP"') { return $true }
        if ($Proceso -and $Proceso.HasExited) { return $false }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# ---------------------------------------------------------------------------
# Arrancar y esperar, reintentando si el puerto estaba tomado de paso
# ---------------------------------------------------------------------------
# En este equipo el rango de puertos dinamicos de Windows empieza en 1024
# (netsh int ipv4 show dynamicport tcp) y no en 49152 como viene de fabrica:
# una conexion SALIENTE puede recibir como puerto local uno de los del
# ecosistema durante unos segundos. Si justo entonces arranca el servicio
# duenio de ese puerto, Tomcat falla con "Port ... was already in use".
# El script reconoce ese fallo concreto y reintenta.
#
# OJO: esto NO explico el caso del 8888, que fallaba siempre, arrancando solo
# y con tres reintentos, sin nadie escuchando segun netstat. Eso se resolvio
# moviendo el Config Server al 7888; ver su application.yml.
function Start-ServicioEsperando {
    param([string]$Nombre, [string[]]$Extra = @(), [int]$Intentos = 3)
    for ($i = 1; $i -le $Intentos; $i++) {
        $p = Start-Servicio -Nombre $Nombre -Extra $Extra
        if (Wait-Salud -Url $Servicios[$Nombre].salud -Proceso $p) { return $p }
        $log = Join-Path $logs "$Nombre.log"
        $puertoTomado = (Test-Path $log) -and (Select-String -Path $log -Pattern "already in use" -Quiet)
        Stop-Servicio $Nombre | Out-Null
        if (-not $puertoTomado) { return $null }
        Write-Host ("    [{0}: puerto {1} tomado de paso por otra conexion; reintento {2}]" -f `
            $Nombre, $Servicios[$Nombre].puerto, $i) -NoNewline
        Start-Sleep -Seconds 2
    }
    return $null
}

function Test-Salud {
    param([string]$Url)
    $r = & $curl -s -k --max-time 3 $Url 2>$null
    return ($LASTEXITCODE -eq 0 -and $r -match '"status"\s*:\s*"UP"')
}

# ---------------------------------------------------------------------------
# Registro de procesos: una linea "pid;nombre" por proceso vivo
# ---------------------------------------------------------------------------
function Get-Registro {
    if (-not (Test-Path $archivoPids)) { return @() }
    return @(Get-Content $archivoPids | Where-Object { $_ -match ";" })
}

function Set-Registro {
    param([string[]]$Lineas)
    if ($Lineas.Count -eq 0) {
        if (Test-Path $archivoPids) { Remove-Item $archivoPids -Force }
        return
    }
    Set-Content -Path $archivoPids -Value $Lineas -Encoding utf8
}

# ---------------------------------------------------------------------------
# Arrancar un servicio
# ---------------------------------------------------------------------------
# -Jar permite arrancar el servicio con OTRO jar: la evidencia lo usa para
# correr el ms-cuentas de la semana 6 y comparar su arranque sin Config Server
# con el de esta semana.
function Start-Servicio {
    param(
        [string]$Nombre,
        [string[]]$Extra = @(),
        [string]$Jar
    )
    $s = $Servicios[$Nombre]
    if (-not $s) { throw "Servicio desconocido: $Nombre" }
    if (-not $Jar) { $Jar = Get-RutaJar $s.jar }
    if (-not (Test-Path $Jar)) {
        throw ("Falta {0}. Ejecuta primero:  mvn clean package -DskipTests" -f $Jar)
    }
    if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Path $logs | Out-Null }

    # La ruta del jar va entre comillas porque la carpeta del curso tiene
    # espacios. Start-Process une -ArgumentList con espacios y no entrecomilla,
    # y la JVM responde "Unable to access jarfile" con la ruta cortada.
    #
    # Los nulos se filtran porque PowerShell convierte en $null el @() que
    # devuelve una funcion, y Start-Process rechaza un -ArgumentList que
    # contenga un $null con un error que no dice cual.
    $argumentos = @(@(("-Xmx" + $s.heap), "-jar", ('"{0}"' -f $Jar)) + @($s.extra) + @($Extra) |
        Where-Object { $_ })

    $proceso = Start-Process -FilePath $java -ArgumentList $argumentos `
        -WorkingDirectory $raiz -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "$Nombre.log") `
        -RedirectStandardError (Join-Path $logs "$Nombre.err.log")

    Set-Registro (@(Get-Registro) + ("{0};{1}" -f $proceso.Id, $Nombre))
    return $proceso
}

# ---------------------------------------------------------------------------
# Detener un servicio
# ---------------------------------------------------------------------------
function Stop-Servicio {
    param([string]$Nombre)
    $quedan = @()
    $detenido = $false
    foreach ($linea in Get-Registro) {
        $partes = $linea -split ";"
        if ($partes[1] -eq $Nombre) {
            $p = Get-Process -Id ([int]$partes[0]) -ErrorAction SilentlyContinue
            if ($p) { Stop-Process -Id $p.Id -Force; $detenido = $true }
        } else {
            $quedan += $linea
        }
    }
    Set-Registro $quedan
    return $detenido
}

function Get-PidServicio {
    param([string]$Nombre)
    foreach ($linea in Get-Registro) {
        $partes = $linea -split ";"
        if ($partes[1] -eq $Nombre) { return [int]$partes[0] }
    }
    return $null
}

# ---------------------------------------------------------------------------
# Salida de la evidencia y peticiones HTTP (las usan los dos scripts de evidencia)
# ---------------------------------------------------------------------------
$lineas = New-Object System.Collections.Generic.List[string]

function Escribir {
    param([string]$Texto = "")
    $lineas.Add($Texto) | Out-Null
    Write-Host $Texto
}

function Titulo {
    param([string]$Texto)
    Escribir ""
    Escribir ("=" * 78)
    Escribir $Texto
    Escribir ("=" * 78)
}

# Devuelve un objeto con codigo, bytes, milisegundos y cuerpo.
function Pedir {
    param(
        [string]$Url,
        [string]$Metodo = "GET",
        [string[]]$Cabeceras = @(),
        [string]$Cuerpo,
        [string]$Basico
    )

    $temporal = [System.IO.Path]::GetTempFileName()
    $argumentos = @("-s", "-k", "--max-time", "20", "-o", $temporal,
                    "-w", "%{http_code} %{size_download} %{time_total}",
                    "-X", $Metodo)

    foreach ($c in $Cabeceras) { $argumentos += @("-H", $c) }
    if ($Basico) { $argumentos += @("-u", $Basico) }

    # ------------------------------------------------------------------
    # El cuerpo JSON va por ARCHIVO y no como argumento de -d
    # ------------------------------------------------------------------
    # Windows PowerShell 5.1 reescribe las comillas dobles al construir la
    # linea de comandos de un ejecutable nativo, asi que
    # -d '{"usuario":"cliente"}' le llega a curl como {usuario:cliente}.
    # El servidor recibe JSON malformado, y lo que se ve del otro lado es un
    # codigo de error que no tiene nada que ver con el cuerpo.
    #
    # --data-binary con @archivo evita el problema entero: el JSON nunca pasa
    # por el analizador de linea de comandos.
    $archivoCuerpo = $null
    if ($Cuerpo) {
        $archivoCuerpo = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($archivoCuerpo, $Cuerpo, (New-Object System.Text.UTF8Encoding($false)))
        $argumentos += @("-H", "Content-Type: application/json", "--data-binary", ("@" + $archivoCuerpo))
    }
    $argumentos += $Url

    $medicion = (& $curl @argumentos) -split "\s+"
    $contenido = if (Test-Path $temporal) { Get-Content $temporal -Raw -Encoding UTF8 } else { "" }
    Remove-Item $temporal -Force -ErrorAction SilentlyContinue
    if ($archivoCuerpo) { Remove-Item $archivoCuerpo -Force -ErrorAction SilentlyContinue }

    [pscustomobject]@{
        Codigo = [int]$medicion[0]
        Bytes  = [int]$medicion[1]
        # InvariantCulture a proposito: curl escribe el tiempo con punto
        # decimal, y en una consola con configuracion regional es-CL el cast
        # directo a [double] lee "0.123" como ciento veintitres, y los
        # milisegundos de la evidencia salen multiplicados por mil.
        Ms     = [math]::Round([double]::Parse($medicion[2], [Globalization.CultureInfo]::InvariantCulture) * 1000)
        Cuerpo = if ($null -eq $contenido) { "" } else { $contenido.Trim() }
    }
}

