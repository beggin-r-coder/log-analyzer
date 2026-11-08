@echo off
rem Starts Log Analyzer in demo mode on http://localhost:8080
rem   demo.cmd                                  (offline AI analysis)
rem   set GEMINI_API_KEY=your-key ^&^& demo.cmd   (Gemini AI analysis)
cd /d "%~dp0"
if "%APP_PASSWORD%"=="" set APP_PASSWORD=admin123
set DEMO_MODE=true
echo.
echo   Log Analyzer demo  -^>  http://localhost:8080
echo   Guest:  click "Explore as guest" (read-only)
echo   Admin:  admin / %APP_PASSWORD%
if "%GEMINI_API_KEY%"=="" (echo   AI:     offline NLP mode - set GEMINI_API_KEY for Gemini) else (echo   AI:     Gemini)
echo.
call mvnw.cmd -q spring-boot:run
