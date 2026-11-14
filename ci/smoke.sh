#!/bin/bash
# Boots a dedicated server with the mod, summons two Spindles, lets them tick for 30s, saves and stops.
set -u
mkdir -p run/world/datapacks
echo "eula=true" > run/eula.txt
printf 'level-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\n' > run/server.properties
cp -r ci/testpack run/world/datapacks/
timeout 600 ./gradlew runServer --no-daemon --args=nogui 2>&1 | tee server.log
grep -q "SPINDLE_ALIVE" server.log || { echo "::error title=Smoke test::Spindle not alive after 30s (or server never got there)"; grep -iE "exception|error|spindle" server.log | head -60; exit 1; }
if grep -iE "Exception" server.log | grep -qi "spindle"; then echo "::error title=Smoke test::exception mentioning spindle"; grep -iE -A5 "Exception" server.log | head -80; exit 1; fi
echo "Smoke test passed"
