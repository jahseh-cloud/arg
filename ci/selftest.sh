#!/bin/bash
# Usage: ci/selftest.sh behavior|world
# Boots a dedicated server with the mod in self-test mode; the mod runs its scenarios, logs SELFTEST
# lines and stops the server. "behavior" uses a flat world; "world" a normal seeded world.
set -u
MODE=${1:-behavior}
rm -rf run/world
mkdir -p run
echo "eula=true" > run/eula.txt
if [ "$MODE" = "world" ]; then
  printf 'level-seed=spindle\nonline-mode=false\nspawn-protection=0\nmax-tick-time=-1\ngenerate-structures=true\n' > run/server.properties
else
  printf 'level-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\nmax-tick-time=-1\n' > run/server.properties
fi
SPINDLE_SELFTEST=$MODE timeout 1500 ./gradlew runServer --no-daemon --args=nogui < /dev/null > server-$MODE.log 2>&1
echo "================ SELFTEST SUMMARY ($MODE)"
grep -E "SELFTEST (RESULT|DONE|CRASH)" server-$MODE.log | sed 's/.*SELFTEST //'
grep "SELFTEST" server-$MODE.log >> selftest.log
grep -q "SELFTEST DONE" server-$MODE.log || { echo "::error title=Selftest $MODE::server never finished the tests"; grep -iE -A12 "exception|error" server-$MODE.log | grep -v "^\s*at " | head -60; exit 1; }
if grep -iE -A8 "Exception" server-$MODE.log | grep -qi "net.spindle"; then echo "::error title=Selftest $MODE::exception in spindle code"; grep -iE -B2 -A14 "Exception" server-$MODE.log | grep -v "^\s*at net.minecraft\|^\s*at java" | head -60; exit 1; fi
exit 0
