@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "ntvReleaseExit=1"

pushd "%~dp0"
if errorlevel 1 goto directory_error
if not exist "scripts\build-release.ps1" goto missing_script

echo Building nTv ARM32 and ARM64 release APKs...
echo.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-release.ps1" %*
set "ntvReleaseExit=%errorlevel%"
if not "%ntvReleaseExit%"=="0" goto build_failed

echo.
echo Release build completed. See the APK paths above.
goto finish

:missing_script
echo ERROR: scripts\build-release.ps1 was not found.
goto finish

:build_failed
echo.
echo ERROR: Release build failed with exit code %ntvReleaseExit%.

:finish
echo.
pause
popd
exit /b %ntvReleaseExit%

:directory_error
echo ERROR: Unable to open the project directory.
pause
exit /b 1
