#!/bin/bash
# Boots a dedicated server with the mod, summons two Spindles, lets them tick for 30s, saves and stops.
set -u
mkdir -p run/world/datapacks
echo "eula=true" > run/eula.txt
printf 'level-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\n' > run/server.properties
cp -r ci/testpack run/world/datapacks/
: > server.log
rm -f console.fifo && mkfifo console.fifo
(
  exec 3>console.fifo
  for i in $(seq 1 480); do
    grep -q "SPINDLE_ALIVE\|Failed to start\|Exception in server tick loop" server.log && break
    sleep 1
  done
  echo "save-all" >&3; sleep 8
  echo "stop" >&3; sleep 30
) &
timeout 600 ./gradlew runServer --no-daemon --args=nogui < console.fifo 2>&1 | tee server.log
grep -q "SPINDLE_ALIVE" server.log || { echo "::error title=Smoke test::Spindle not alive after 30s (or server never got there)"; grep -iE "exception|error|spindle" server.log | head -60; exit 1; }
grep -q "Saved the game" server.log || { echo "::error title=Smoke test::save-all did not complete"; exit 1; }
if grep -iE -A8 "Exception" server.log | grep -qi "net.spindle"; then echo "::error title=Smoke test::exception in spindle code"; grep -iE -A12 "Exception" server.log | head -80; exit 1; fi
echo "Smoke test passed"
