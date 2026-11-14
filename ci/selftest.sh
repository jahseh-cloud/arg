#!/bin/bash
# Boots a dedicated server with the mod in self-test mode. The mod builds test arenas, spawns Spindles
# and simulated players, runs the scenarios, logs SELFTEST lines and stops the server.
set -u
rm -rf run/world
mkdir -p run
echo "eula=true" > run/eula.txt
printf 'level-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\nmax-tick-time=-1\n' > run/server.properties
SPINDLE_SELFTEST=1 timeout 900 ./gradlew runServer --no-daemon --args=nogui < /dev/null 2>&1 | tee server.log
echo "================ SELFTEST SUMMARY"
grep -E "SELFTEST (RESULT|DONE|CRASH)" server.log | sed 's/.*SELFTEST //'
grep -q "SELFTEST DONE" server.log || { echo "::error title=Selftest::server never finished the tests"; grep -iE -A12 "exception|error" server.log | grep -v "^\s*at " | head -60; exit 1; }
if grep -iE -A8 "Exception" server.log | grep -qi "net.spindle"; then echo "::error title=Selftest::exception in spindle code"; grep -iE -A12 "Exception" server.log | head -60; exit 1; fi
exit 0
