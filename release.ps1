# Lança uma versão nova do StreamPLAY: atualiza a versão no projeto, gera o exe e cria a release no GitHub.
# Os apps instalados encontram a release sozinhos e se atualizam.
# Antes: faça commit e push do código (a release marca o último commit do GitHub).
# Uso: .\release.ps1 -Versao 1.0.1 -Notas "o que mudou"
param(
    [Parameter(Mandatory = $true)][string]$Versao,
    [string]$Notas = ""
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Versao -notmatch '^\d+\.\d+\.\d+$') { throw "Use o formato 1.2.3" }

$csproj = Join-Path $PSScriptRoot 'StreamPLAY.csproj'
$xml = [IO.File]::ReadAllText($csproj)
$xml = [regex]::Replace($xml, '<Version>[^<]+</Version>', "<Version>$Versao</Version>")
[IO.File]::WriteAllText($csproj, $xml, (New-Object Text.UTF8Encoding $false))

$dotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet\dotnet.exe'
if (-not (Test-Path $dotnet)) { $dotnet = 'dotnet' }
& $dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=none -o dist
if ($LASTEXITCODE -ne 0) { throw "A compilação falhou" }
$arquivos = @('dist\StreamPLAY.exe')

# App Android (celular e TV): mesma versão, e o APK vai junto em toda release. O guia dos clientes baixa pelo link
# .../releases/latest/download/StreamPLAY.apk, que para de funcionar se a última release não tiver o APK.
$gradle = Join-Path $PSScriptRoot 'android\app\build.gradle.kts'
if (Test-Path $gradle) {
    $g = [IO.File]::ReadAllText($gradle)
    $g = [regex]::Replace($g, 'val versao = "[^"]+"', "val versao = `"$Versao`"")
    [IO.File]::WriteAllText($gradle, $g, (New-Object Text.UTF8Encoding $false))
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'AndroidBuild\jdk-17.0.20.1+1' }
    Push-Location (Join-Path $PSScriptRoot 'android')
    & .\gradlew.bat :app:assembleRelease --console=plain
    $apkOk = $LASTEXITCODE -eq 0
    Pop-Location
    if (-not $apkOk) { throw "A compilação do APK falhou" }
    Copy-Item 'android\app\build\outputs\apk\release\app-release.apk' 'dist\StreamPLAY.apk' -Force
    $arquivos += 'dist\StreamPLAY.apk'
}

if (-not $Notas) { $Notas = "StreamPLAY $Versao" }
gh release create "v$Versao" @arquivos --title "StreamPLAY $Versao" --notes $Notas
if ($LASTEXITCODE -ne 0) { throw "Não consegui criar a release no GitHub" }
Write-Host "Release v$Versao publicada."
