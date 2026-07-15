@echo off
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
gradle -p "%DIRNAME%" %*
