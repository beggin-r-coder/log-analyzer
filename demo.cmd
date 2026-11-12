@echo off
rem Starts Log Analyzer in demo mode on http://localhost:8080
rem   demo.cmd
rem Error analysis uses the built-in NLP engine (Gemini is off unless AI_ENABLED=true).
cd /d "%~dp0"
if "%APP_PASSWORD%"=="" set APP_PASSWORD=admin123
set DEMO_MODE=true
echo.
echo   Log Analyzer demo  -^>  http://localhost:8080
echo   Guest:  click "Explore as guest" (read-only)
echo   Admin:  admin / %APP_PASSWORD%
echo   Analysis: built-in NLP engine
echo.
call mvnw.cmd -q spring-boot:run
