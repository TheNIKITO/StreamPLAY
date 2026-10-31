@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Adicionar episódios na lista
echo.
echo  ============================================
echo     StreamPLAY - adicionar episódios
echo  ============================================
echo.
where python >nul 2>nul
if errorlevel 1 (
  echo  [ERRO] Precisa do Python instalado: https://www.python.org/downloads/
  goto :fim
)
if not exist "episodios-novos.txt" python -I "%~dp0ferramentas\adicionar.py" >nul 2>nul
echo  Abrindo o episodios-novos.txt no Bloco de Notas.
echo  Coloque o nome da série e embaixo os episódios ^(S04E01 link^),
echo  salve com Ctrl+S e FECHE o Bloco de Notas para continuar.
start "" /wait notepad.exe "%~dp0episodios-novos.txt"
echo.
python -I -u "%~dp0ferramentas\adicionar.py"
if errorlevel 1 goto :fim

echo.
choice /c SN /n /m "  Completar nome, descrição e imagem dos episódios novos pelo TMDB agora? [S/N] "
if not errorlevel 2 python -I -u "%~dp0ferramentas\episodios.py"
echo.
choice /c SN /n /m "  Publicar a lista agora? [S/N] "
if not errorlevel 2 (
  call "%~dp0publicar-lista.bat"
  exit /b 0
)

:fim
echo.
pause
