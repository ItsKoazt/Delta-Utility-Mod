@echo off
cd /d %~dp0
gradle --refresh-dependencies clean build
pause
