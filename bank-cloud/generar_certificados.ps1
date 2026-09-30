# ---------------------------------------------------------------------------
# Genera un certificado TLS autofirmado por cada BFF.
#
# Uso:
#   .\generar_certificados.ps1            # crea los que falten
#   .\generar_certificados.ps1 -Rehacer   # los regenera todos
#
# POR QUE UN CERTIFICADO POR CANAL Y NO UNO COMPARTIDO
# ----------------------------------------------------
# Son tres backends que se despliegan por separado, en tres procesos y tres
# puertos. Compartir una clave privada entre ellos significaria que
# comprometer el cajero -el equipo expuesto en la calle- entrega tambien la
# identidad del canal web. Un certificado por servicio es lo que permite rotar
# o revocar uno sin tocar los otros, y es coherente con la estrategia de
# backends independientes que sostiene todo el proyecto.
#
# POR QUE NO VAN AL REPOSITORIO
# -----------------------------
# Un keystore contiene una clave privada. Versionarlo en un repositorio publico
# lo vuelve inutil como credencial, aunque sea autofirmado y de desarrollo:
# quien clone el repositorio puede suplantar al servidor. Por eso certs/ esta
# en .gitignore y este script los recrea en un comando.
#
# ALCANCE: son certificados AUTOFIRMADOS, para desarrollo. Un navegador y curl
# los rechazan salvo que se los indique explicitamente, porque ninguna
# autoridad los respalda. En produccion irian certificados emitidos por una CA
# -o gestionados por el balanceador, que es lo habitual cuando hay varios
# servicios detras-.
# ---------------------------------------------------------------------------

param(
    [switch]$Rehacer,
    # La clave del keystore. No es un secreto real: protege un certificado de
    # desarrollo que este mismo script puede regenerar. Se parametriza para que
    # quien despliegue de verdad pueda pasar la suya.
    [string]$Clave = "bffbancoxyz"
)

# keytool escribe su mensaje de progreso en stderr. En Windows PowerShell 5.1
# el stderr de un ejecutable nativo se envuelve en un ErrorRecord y, con
# ErrorActionPreference en Stop, aborta el script aunque el comando haya
# terminado bien. Basta con que la consola capture ese stderr para que ocurra,
# asi que no alcanza con abstenerse de escribir 2>&1 aqui.
#
# Por eso la llamada a keytool baja la preferencia solo mientras dura, y el
# exito se comprueba viendo si el keystore quedo creado. Ver el bloque de abajo.
$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
}
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    Write-Output "ERROR: no se encontro keytool en $keytool. Definir JAVA_HOME."
    exit 1
}

$destino = Join-Path $PSScriptRoot "certs"
New-Item -ItemType Directory -Force -Path $destino | Out-Null

$canales = [ordered]@{
    "bff-web"    = "BFF Web"
    "bff-movil"  = "BFF Movil"
    "bff-cajero" = "BFF Cajero"
}

foreach ($alias in $canales.Keys) {

    $archivo = Join-Path $destino "$alias.p12"

    if ((Test-Path $archivo) -and -not $Rehacer) {
        Write-Output ("  {0,-12} ya existe" -f $alias)
        continue
    }
    if (Test-Path $archivo) {
        Remove-Item $archivo -Force
    }

    # SAN con localhost y 127.0.0.1: sin Subject Alternative Name los clientes
    # modernos rechazan el certificado aunque el CN coincida. El CN por si solo
    # dejo de ser suficiente hace anos.
    # keytool escribe en stderr una linea informativa aunque todo salga bien
    # ("Generando par de claves RSA..."). Con ErrorActionPreference en Stop, y
    # en cualquier consola que capture el stderr de los ejecutables nativos
    # -las de los entornos de integracion continua lo hacen-, esa linea se
    # convierte en un NativeCommandError que aborta el script pese al exito.
    #
    # Bajar la preferencia solo alrededor de esta llamada, y comprobar el
    # resultado por la existencia del keystore, es lo que hace que el script
    # funcione igual lo invoque quien lo invoque.
    $preferenciaPrevia = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"

    & $keytool -genkeypair `
        -alias $alias `
        -keyalg RSA -keysize 2048 -validity 365 `
        -storetype PKCS12 `
        -keystore $archivo `
        -storepass $Clave `
        -dname "CN=localhost, OU=$($canales[$alias]), O=Banco XYZ, L=Santiago, C=CL" `
        -ext "SAN=dns:localhost,ip:127.0.0.1" 2>&1 | Out-Null

    $ErrorActionPreference = $preferenciaPrevia

    if (-not (Test-Path $archivo)) {
        Write-Output ("  {0,-12} ERROR al generar" -f $alias)
        exit 1
    }
    Write-Output ("  {0,-12} generado  ({1})" -f $alias, $canales[$alias])
}

Write-Output ""
Write-Output "Certificados en: $destino"
Write-Output "No se versionan: certs/ esta en .gitignore porque contienen la clave privada."
