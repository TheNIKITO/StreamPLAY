@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title Publicar lista do StreamPLAY
echo.
echo  ============================================
echo     StreamPLAY - publicar lista no Firebase
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

rem ---- Firebase CLI (instala na primeira vez)
set "FB="
for /f "delims=" %%p in ('where firebase.cmd 2^>nul') do if not defined FB set "FB=%%p"
if not defined FB if exist "%APPDATA%\npm\firebase.cmd" set "FB=%APPDATA%\npm\firebase.cmd"
if not defined FB (
  where npm >nul 2>nul
  if errorlevel 1 (
    echo  [ERRO] Precisa do Node.js instalado: https://nodejs.org
    goto :fim_erro
  )
  echo  Instalando o Firebase CLI ^(só na primeira vez, leva 1-2 minutos^)...
  call npm install -g firebase-tools
  if exist "%APPDATA%\npm\firebase.cmd" set "FB=%APPDATA%\npm\firebase.cmd"
)
if not defined FB (
  echo  [ERRO] Não consegui instalar o Firebase CLI.
  goto :fim_erro
)

rem ---- login (abre o navegador só na primeira vez)
call "%FB%" login:list 2>nul | findstr /i /c:"Logged in as" >nul
if errorlevel 1 (
  echo  Vai abrir o navegador para você entrar com a sua conta Google do Firebase.
  echo.
  call "%FB%" login
  call "%FB%" login:list 2>nul | findstr /i /c:"Logged in as" >nul
  if errorlevel 1 (
    echo  [ERRO] O login não foi concluído.
    goto :fim_erro
  )
)

rem ---- projeto (escolhido na primeira vez; só fica salvo em projeto.txt depois de publicar com sucesso)
set "PROJ="
set "NOVO=0"
if exist "projeto.txt" set /p PROJ=<"projeto.txt"
if defined PROJ call :valida
if defined PROJ if errorlevel 1 (
  del "projeto.txt" >nul 2>nul
  set "PROJ="
)
if defined PROJ goto :tem_projeto

set "SUGESTAO=streamplay-%RANDOM%"
echo.
echo  Primeira publicação: vamos criar um projeto do Firebase só para a lista.
echo  O nome vira o endereço:  https://NOME.web.app/lista.m3u
echo  Regras do Firebase: só letras minúsculas, números e hífen, de 6 a 30 caracteres.
echo  Dica: aperte Enter para usar a sugestão.
set "TENTATIVAS=0"
:pede_nome
set /a TENTATIVAS+=1
if !TENTATIVAS! gtr 6 goto :fim_erro
echo.
set "PROJ="
set /p "PROJ=  Nome do projeto [!SUGESTAO!]: "
if not defined PROJ set "PROJ=!SUGESTAO!"
call :valida
if errorlevel 1 (
  echo  Nome inválido: "!PROJ!". Use só letras minúsculas, números e hífen ^(6 a 30^), começando com letra.
  goto :pede_nome
)
echo.
echo  Criando o projeto !PROJ! no Firebase...
call "%FB%" projects:create "!PROJ!" --display-name "StreamPLAY Lista"
if errorlevel 1 (
  echo.
  echo  Não deu para criar "!PROJ!". O mais comum é esse nome já ser de outra pessoa.
  choice /c SN /n /m "  Esse projeto já é SEU, criado por você antes? [S/N] "
  if errorlevel 2 (
    set "SUGESTAO=streamplay-!RANDOM!"
    echo  Então escolha outro nome, ou aperte Enter para usar a nova sugestão.
    goto :pede_nome
  )
)
set "NOVO=1"

:tem_projeto
echo  Projeto: %PROJ%

rem ---- garante que o site do Hosting existe (em projeto novo ele pode ainda não existir)
call "%FB%" hosting:sites:get "%PROJ%" --project "%PROJ%" >nul 2>nul
if errorlevel 1 call "%FB%" hosting:sites:create "%PROJ%" --project "%PROJ%" >nul 2>nul

echo.
echo  Publicando a lista...
call "%FB%" deploy --only hosting --project "%PROJ%" --non-interactive
if errorlevel 1 (
  echo.
  echo  [ERRO] A publicação falhou. Veja a mensagem acima.
  if "!NOVO!"=="0" echo  Se o problema for o projeto, apague o arquivo projeto.txt e rode de novo para escolher outro.
  goto :fim_erro
)
>"projeto.txt" echo %PROJ%

set "URL=https://%PROJ%.web.app/lista.m3u"
<nul set /p "=%URL%" | clip
echo.
echo  ============================================
echo   Lista publicada com sucesso.
echo   Link: %URL%
echo   ^(já foi copiado^)
if exist "public\canais.m3u" echo   Os canais foram juntos, no mesmo link: o app coloca na aba Canais.
echo.
echo   Na primeira vez, cole esse link no StreamPLAY:
echo   Configurações ^> Trocar lista ^> "ou cole o link da lista"
echo   Depois disso os apps abertos se atualizam sozinhos
echo   em até 10 minutos ^(ou na hora, ao reabrir^).
echo  ============================================
echo.
pause
exit /b 0

:fim_erro
echo.
pause
exit /b 1

rem ---- deixa PROJ em minúsculas e sem espaços e confere as regras do Firebase (errorlevel 1 = inválido)
:valida
for /f "usebackq delims=" %%x in (`powershell -NoProfile -Command "$env:PROJ.Trim().ToLower() -replace '\s+','-'"`) do set "PROJ=%%x"
powershell -NoProfile -Command "if ($env:PROJ -cmatch '^[a-z][a-z0-9-]{4,28}[a-z0-9]$') { exit 0 } else { exit 1 }"
exit /b %errorlevel%
