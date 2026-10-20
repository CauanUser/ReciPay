@echo off
rem ============================================================
rem  Abre o Recipay usando o Python instalado no computador.
rem  Na primeira vez, prepara o ambiente e baixa as bibliotecas
rem  (precisa de internet). Depois, abre direto.
rem  Uso: dois cliques neste arquivo.
rem  Para ver mensagens de erro: rode  "Abrir Recipay.bat" /debug
rem ============================================================
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Recipay

if not exist "recipay\__main__.py" (
    echo.
    echo  Este arquivo precisa ficar na mesma pasta que a pasta "recipay".
    echo  Extraia o ZIP inteiro antes de abrir; nao abra de dentro do ZIP.
    goto :fim_erro
)

set "VENV=%LOCALAPPDATA%\Recipay\ambiente"

rem ---------- 1. Procurar o Python ----------
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY goto :sem_python
%PY% -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>nul
if errorlevel 1 goto :sem_python

rem ---------- 2. Criar o ambiente (so na primeira vez) ----------
if exist "%VENV%\Scripts\python.exe" goto :conferir_bibliotecas
echo.
echo  Preparando o Recipay pela primeira vez.
echo  Isso leva de 2 a 5 minutos e precisa de internet. Aguarde...
echo.
%PY% -m venv "%VENV%"
if errorlevel 1 (
    echo  Nao foi possivel criar o ambiente do Recipay.
    goto :fim_erro
)

:conferir_bibliotecas
rem ---------- 3. Instalar/atualizar bibliotecas quando a lista mudar ----------
fc /b "requirements.txt" "%VENV%\recipay-requirements.txt" >nul 2>nul
if not errorlevel 1 goto :abrir
echo  Instalando as bibliotecas do Recipay...
"%VENV%\Scripts\python.exe" -m pip install --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
    echo.
    echo  Nao foi possivel baixar as bibliotecas.
    echo  Confira a conexao com a internet e tente de novo.
    goto :fim_erro
)
copy /y "requirements.txt" "%VENV%\recipay-requirements.txt" >nul

:abrir
rem ---------- 4. Abrir o programa ----------
if /i "%~1"=="/debug" (
    "%VENV%\Scripts\python.exe" -m recipay
    pause
    exit /b 0
)
start "" /D "%CD%" "%VENV%\Scripts\pythonw.exe" -m recipay
exit /b 0

:sem_python
echo.
echo  O Python nao foi encontrado neste computador.
echo.
echo  1. Abra https://www.python.org/downloads/ e baixe o Python 3 para Windows.
echo  2. Na instalacao, marque "Add python.exe to PATH" e clique em "Install Now".
echo  3. Depois, de dois cliques de novo em "Abrir Recipay.bat".
echo.

:fim_erro
echo.
pause
exit /b 1
