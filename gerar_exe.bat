@echo off
rem ============================================================
rem  Gera o Recipay.exe (pasta portatil) em dist\Recipay
rem  Uso: de dois cliques neste arquivo, ou rode no PowerShell:
rem        .\gerar_exe.bat
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo [1/4] Criando ambiente limpo para o build (.venv-build)...
if not exist ".venv-build\Scripts\python.exe" (
    python -m venv .venv-build || goto :erro
)

echo [2/4] Instalando dependencias e o PyInstaller...
".venv-build\Scripts\python.exe" -m pip install --upgrade pip >nul
".venv-build\Scripts\python.exe" -m pip install -r requirements.txt pyinstaller || goto :erro

echo [3/4] Rodando os testes automaticos...
".venv-build\Scripts\python.exe" -m unittest -q || goto :erro

echo [4/4] Gerando o executavel (pode levar alguns minutos)...
".venv-build\Scripts\pyinstaller.exe" --noconfirm --clean --windowed ^
    --name Recipay ^
    --icon assets\recipay.ico ^
    --add-data "assets;assets" ^
    executar.py || goto :erro

echo.
echo ============================================================
echo  Pronto! O programa esta em:  dist\Recipay\Recipay.exe
echo  Para levar a outro PC, copie a pasta dist\Recipay inteira.
echo ============================================================
pause
exit /b 0

:erro
echo.
echo *** Algo deu errado. Copie as mensagens acima e envie para analise. ***
pause
exit /b 1
