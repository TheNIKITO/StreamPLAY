@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Adicionar episódios e filmes na lista
echo.
echo  ============================================
echo     StreamPLAY - adicionar episódios e filmes
echo  ============================================
echo.
where python >nul 2>nul
if errorlevel 1 (
  echo  [ERRO] Precisa do Python instalado: https://www.python.org/downloads/
  goto :fim
)
rem cria o episodios-novos.txt (ou troca a explicação antiga dele pela nova, se ele estiver vazio)
python -I "%~dp0ferramentas\adicionar.py" --modelo >nul 2>nul
echo  Abrindo o episodios-novos.txt no Bloco de Notas.
echo  Série: o nome e embaixo os episódios ^(S04E01 link^).
echo  Filmes: uma linha FILMES e embaixo cada filme ^(nome e link^).
echo  Depois
echo  salve com Ctrl+S e FECHE o Bloco de Notas para continuar.
start "" /wait notepad.exe "%~dp0episodios-novos.txt"
echo.
python -I -u "%~dp0ferramentas\adicionar.py"
if errorlevel 1 goto :fim

echo.
choice /c SN /n /m "  Completar pelo TMDB agora (descrição, capa e imagens dos filmes e episódios novos)? [S/N] "
if not errorlevel 2 (
  python -I -u "%~dp0ferramentas\descricoes.py"
  python -I -u "%~dp0ferramentas\episodios.py"
)
echo.
choice /c SN /n /m "  Publicar a lista agora? [S/N] "
if not errorlevel 2 (
  call "%~dp0publicar-lista.bat"
  exit /b 0
)

:fim
echo.
pause
