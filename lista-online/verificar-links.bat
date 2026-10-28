@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Verificar links da lista
echo.
echo  ============================================
echo     StreamPLAY - verificar links quebrados
echo  ============================================
echo   Testa 20 links ao mesmo tempo. Se aparecerem
echo   muitos "acesso negado" de repente, diminua.
echo   Sua lista não muda:
echo   sai um relatório e uma cópia sem os quebrados.
echo.
where python >nul 2>nul
if errorlevel 1 (
  echo  [ERRO] Precisa do Python instalado: https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)
python -I -u "%~dp0ferramentas\verificar.py" %*
echo.
if exist "%~dp0links-quebrados.html" start "" "%~dp0links-quebrados.html"
pause
