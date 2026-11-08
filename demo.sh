#!/usr/bin/env sh
# Starts Log Analyzer in demo mode on http://localhost:8080
#   sh demo.sh                       (offline AI analysis)
#   GEMINI_API_KEY=your-key sh demo.sh   (Gemini AI analysis)
cd "$(dirname "$0")" || exit 1
: "${APP_PASSWORD:=admin123}"
export APP_PASSWORD
export DEMO_MODE=true
echo ""
echo "  Log Analyzer demo  ->  http://localhost:8080"
echo "  Guest:  click 'Explore as guest' (read-only)"
echo "  Admin:  admin / $APP_PASSWORD"
if [ -z "$GEMINI_API_KEY" ]; then echo "  AI:     offline NLP mode (set GEMINI_API_KEY for Gemini)"; else echo "  AI:     Gemini"; fi
echo ""
exec sh ./mvnw -q spring-boot:run
