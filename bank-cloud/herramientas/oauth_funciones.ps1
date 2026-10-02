param([string]$Auth = "http://localhost:9000")
$ErrorActionPreference = "Continue"
$tmp = Join-Path $env:TEMP "evidencia_s8"; New-Item -ItemType Directory -Force $tmp | Out-Null
$curl = "$env:SystemRoot\System32\curl.exe"

function B64Url([byte[]]$b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_') }
function Carga([string]$jwt) {
    $p = $jwt.Split('.')[1].Replace('-','+').Replace('_','/')
    while ($p.Length % 4) { $p += '=' }
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json
}
function TokenMaquina([string]$id, [string]$secreto, [string]$scope) {
    $r = & $curl -s -w "`n%{http_code}" -u "${id}:${secreto}" -d "grant_type=client_credentials" -d "scope=$scope" "$Auth/oauth2/token"
    $l = $r -split "`n"; @{ codigo = $l[-1]; cuerpo = ($l[0..($l.Length-2)] -join "`n") }
}
function Ubicacion([string]$cabeceras) {
    (Get-Content $cabeceras | Where-Object { $_ -match '^Location:' } | Select-Object -Last 1) -replace '^Location:\s*','' -replace '\s+$',''
}
function TokenPersona([string]$cliente, [string]$usuario, [string]$clave, [switch]$SinVerificador) {
    $jar = Join-Path $tmp "cookies-$cliente-$usuario.txt"; Remove-Item $jar -ErrorAction SilentlyContinue
    $cab = Join-Path $tmp "cab.txt"
    $bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $verificador = B64Url $bytes
    $desafio = B64Url ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::ASCII.GetBytes($verificador)))
    $redir = "http://127.0.0.1:8080/callback"
    $url = "$Auth/oauth2/authorize?response_type=code&client_id=$cliente&redirect_uri=$redir&code_challenge=$desafio&code_challenge_method=S256&state=xyz"
    & $curl -s -o NUL -D $cab -c $jar -b $jar -H "Accept: text/html" $url
    $paso1 = Ubicacion $cab
    $html = & $curl -s -c $jar -b $jar "$Auth/login"
    $csrf = [regex]::Match(($html -join ''), 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
    & $curl -s -o NUL -D $cab -c $jar -b $jar --data-urlencode "username=$usuario" --data-urlencode "password=$clave" --data-urlencode "_csrf=$csrf" "$Auth/login"
    $paso2 = Ubicacion $cab
    & $curl -s -o NUL -D $cab -c $jar -b $jar -H "Accept: text/html" $paso2
    $paso3 = Ubicacion $cab
    $codigo = if ($paso3) { [regex]::Match($paso3, '[?&]code=([^&]+)').Groups[1].Value } else { '' }
    $datos = @("-d", "grant_type=authorization_code", "--data-urlencode", "code=$codigo", "--data-urlencode", "redirect_uri=$redir", "-d", "client_id=$cliente")
    if (-not $SinVerificador) { $datos += @("-d", "code_verifier=$verificador") }
    $r = & $curl -s -w "`n%{http_code}" @datos "$Auth/oauth2/token"
    $l = $r -split "`n"
    @{ login = $paso1; tras_login = $paso2; redireccion = $paso3; csrf = ($csrf.Length -gt 0); codigo = $l[-1]; cuerpo = ($l[0..($l.Length-2)] -join "`n") }
}

