@echo off
rem ============================================================
rem  Gera o Recipay.zip (versao que abre pelo Python, sem .exe)
rem  na Area de Trabalho, pronto para enviar.
rem  Uso: dois cliques, ou  .\gerar_pacote.bat  no PowerShell.
rem ============================================================
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "TMPPAC=%TEMP%\Recipay_pacote"
set "DEST=%TMPPAC%\Recipay"
if exist "%TMPPAC%" rmdir /s /q "%TMPPAC%"
mkdir "%DEST%"

echo [1/2] Copiando os arquivos do programa...
robocopy "recipay" "%DEST%\recipay" /E /XD __pycache__ /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto :erro
robocopy "assets" "%DEST%\assets" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto :erro
copy /y "requirements.txt" "%DEST%\" >nul || goto :erro
copy /y "Abrir Recipay.bat" "%DEST%\" >nul || goto :erro
copy /y "LEIA-ME.txt" "%DEST%\" >nul || goto :erro

echo [2/2] Compactando...
powershell -NoProfile -Command "$d = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Recipay.zip'; Compress-Archive -Path '%DEST%' -DestinationPath $d -Force; Write-Host ''; Write-Host (' Pronto! Arquivo criado: ' + $d)"
if errorlevel 1 goto :erro
rmdir /s /q "%TMPPAC%"
echo.
pause
exit /b 0

:erro
echo.
echo *** Algo deu errado. Copie as mensagens acima e envie para analise. ***
pause
exit /b 1
