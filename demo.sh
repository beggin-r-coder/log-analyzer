#!/usr/bin/env sh
# Starts Log Analyzer in demo mode on http://localhost:8080
#   sh demo.sh
# Error analysis uses the built-in NLP engine (Gemini is off unless AI_ENABLED=true).
cd "$(dirname "$0")" || exit 1
: "${APP_PASSWORD:=admin123}"
export APP_PASSWORD
export DEMO_MODE=true
echo ""
echo "  Log Analyzer demo  ->  http://localhost:8080"
echo "  Guest:  click 'Explore as guest' (read-only)"
echo "  Admin:  admin / $APP_PASSWORD"
echo "  Analysis: built-in NLP engine" 
echo ""
exec sh ./mvnw -q spring-boot:run
