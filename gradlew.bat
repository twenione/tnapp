@echo off
where gradle >NUL 2>NUL
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
python tools\gradle_stub.py %*
exit /b %ERRORLEVEL%

