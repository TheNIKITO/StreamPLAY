@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Completar descrições da lista (TMDB)
echo.
echo  ============================================
echo     StreamPLAY - descrições, ano, gênero,
echo     nota e imagem de fundo (TMDB)
echo  ============================================
echo.
where python >nul 2>nul
if errorlevel 1 (
  echo  [ERRO] Precisa do Python instalado: https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)
python -I -u "%~dp0ferramentas\descricoes.py" %*
echo.
pause
