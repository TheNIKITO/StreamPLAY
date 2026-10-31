@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title Publicar lista do StreamPLAY
echo.
echo  ============================================
echo     StreamPLAY - publicar lista no Cloudflare
echo  ============================================
echo.

if not exist "public\lista.m3u" (
  echo  [ERRO] Não achei o arquivo public\lista.m3u
  goto :fim_erro
)

rem ---- quantos itens a lista tem
set "ITENS=0"
for /f %%n in ('find /c "#EXTINF" ^< "public\lista.m3u"') do set "ITENS=%%n"
echo  Lista:  public\lista.m3u  ^(!ITENS! itens^)

rem ---- canais (opcional): vão no mesmo endereço e o app junta sozinho na aba Canais
if exist "public\canais.m3u" (
  set "CANAIS=0"
  for /f %%n in ('findstr /b /c:"#EXTINF" "public\canais.m3u" ^| find /c /v ""') do set "CANAIS=%%n"
  echo  Canais: public\canais.m3u  ^(!CANAIS! canais^)
) else (
  echo  Canais: nenhum ainda ^(para criar, use o editar-canais.bat^)
)
echo.

where node >nul 2>nul || (
  echo  [ERRO] Precisa do Node.js instalado: https://nodejs.org
  goto :fim_erro
)
cd cloudflare
if not exist node_modules\wrangler (
  echo  Instalando as ferramentas do Cloudflare ^(só na primeira vez^)...
  call npm install --no-audit --no-fund || goto :fim_erro
)

rem ---- login (abre o navegador só na primeira vez; é a mesma conta do servidor do Pix)
call npx wrangler whoami 2>nul | find /i "You are logged in" >nul
if errorlevel 1 (
  echo  Vai abrir o navegador: entre na sua conta do Cloudflare e clique em Allow.
  call npx wrangler login || goto :fim_erro
)

rem ---- o servidor da lista (só precisa publicar de novo se o arquivo src\index.js mudar)
if not exist ".publicado" (
  echo  Publicando o servidor da lista ^(só na primeira vez^)...
  call npx wrangler deploy || goto :fim_erro
  >".publicado" echo ok
)

echo.
echo  Enviando a lista ^(comprimida^)...
call node enviar.mjs ..\public
if errorlevel 1 (
  echo.
  echo  [ERRO] O envio falhou. Veja a mensagem acima.
  goto :fim_erro
)

set "URL=https://streamplay-lista.streamplay-pix.workers.dev/lista.m3u"
<nul set /p "=%URL%" | clip
echo.
echo  ============================================
echo   Lista publicada com sucesso.
echo   Link: %URL%
echo   ^(já foi copiado^)
if exist "..\public\canais.m3u" echo   Os canais foram juntos, no mesmo link: o app coloca na aba Canais.
echo.
echo   O link fica no PLAYGER ^(Configurações ^> Lista do StreamPLAY^):
echo   os apps pegam de lá. Depois disso os apps abertos se atualizam
echo   sozinhos em até 10 minutos ^(ou na hora, ao reabrir^).
echo  ============================================
echo.
pause
exit /b 0

:fim_erro
echo.
pause
exit /b 1
