@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0RAC-Print-Windows.exe" (
  echo ERRO: mantenha RAC-Print-Windows.exe na mesma pasta deste instalador.
  pause
  exit /b 1
)
echo Iniciando RAC Print...
start "" "%~dp0RAC-Print-Windows.exe" --background
echo.
echo Para abrir automaticamente ao ligar o Windows, sera criado um atalho de inicializacao.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path [Environment]::GetFolderPath('Startup') 'RAC Print.lnk')); $s.TargetPath='%~dp0RAC-Print-Windows.exe'; $s.WorkingDirectory='%~dp0'; $s.Arguments='--background'; $s.Save()"
if errorlevel 1 (
  echo Atalho automatico nao criado. Voce podera abrir o RAC Print manualmente.
) else (
  echo Atalho de inicializacao criado para este usuario.
)
echo A chave existente foi preservada. Para ver os dados, abra o EXE sem argumentos.
pause
