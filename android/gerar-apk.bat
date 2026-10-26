@echo off
rem Gera o StreamPLAY.apk (celular e TV Android) em android\dist\StreamPLAY.apk
setlocal
cd /d "%~dp0"

if "%JAVA_HOME%"=="" if exist "%LOCALAPPDATA%\AndroidBuild\jdk-17.0.20.1+1\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\AndroidBuild\jdk-17.0.20.1+1"
if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%JAVA_HOME%"=="" (
  echo Precisa do JDK 17. Instale e rode de novo.
  pause
  exit /b 1
)
if not exist "%USERPROFILE%\.streamplay\assinatura.properties" (
  echo AVISO: chave de assinatura nao encontrada em %USERPROFILE%\.streamplay
  echo O APK sai com a chave de teste e NAO vai atualizar por cima dos que ja estao instalados.
  echo.
)

call gradlew.bat :app:assembleRelease --console=plain
if errorlevel 1 (
  echo.
  echo Deu erro ao gerar o APK.
  pause
  exit /b 1
)

if not exist dist mkdir dist
copy /y "app\build\outputs\apk\release\app-release.apk" "dist\StreamPLAY.apk" >nul
echo.
echo Pronto: %~dp0dist\StreamPLAY.apk
explorer /select,"%~dp0dist\StreamPLAY.apk"
