@echo off
rem Abre a lista de canais (public\canais.m3u). Na primeira vez cria o arquivo a partir do modelo.
cd /d "%~dp0"
if not exist "public" mkdir "public"
if not exist "public\canais.m3u" copy /y "modelo-canais.m3u" "public\canais.m3u" >nul
start "" notepad.exe "%~dp0public\canais.m3u"
