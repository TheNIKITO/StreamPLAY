@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Completar episódios da lista (TMDB)
echo.
echo  ============================================
echo     StreamPLAY - nome, descrição e imagem
echo     de cada episódio das séries (TMDB)
echo  ============================================
echo.
where python >nul 2>nul
if errorlevel 1 (
  echo  [ERRO] Precisa do Python instalado: https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)
python -I -u "%~dp0ferramentas\episodios.py" %*
echo.
pause
