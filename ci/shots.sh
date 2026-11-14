#!/bin/bash
# Runs the real game client (software rendering) on the world the self-test left behind, with the
# screenshot harness switched on. Screenshots end up in run/screenshots.
set -u
[ -d run/world ] || { echo "no world from the self-test"; exit 1; }
mkdir -p run/saves
rm -rf run/saves/shots run/screenshots
cp -r run/world run/saves/shots
rm -f run/saves/shots/session.lock
cat > run/options.txt <<'OPT'
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:8
fov:0.25
guiScale:2
skipMultiplayerWarning:true
tutorialStep:none
joinedFirstServer:true
soundCategory_master:0.0
OPT
export SPINDLE_SHOTS=1 LIBGL_ALWAYS_SOFTWARE=1
xvfb-run -a -s "-screen 0 1280x720x24" timeout 1200 ./gradlew runClient --no-daemon \
  --args="--quickPlaySingleplayer shots --width 1280 --height 720" < /dev/null > client.log 2>&1
echo "client exit: $?"
grep -E "SHOTS |Exception|Caused by|OpenGL|GL_RENDERER|crash" client.log | grep -v "^\s*at " | head -60
ls -la run/screenshots 2>/dev/null
