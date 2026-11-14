# Spindle (Fabric 1.21.1)

A huge stalker for Minecraft. This is the creature on its own; the ARG layer comes later.

## Install
Put `spindle-vN.jar` from the [Releases](../../releases) page in your `mods` folder along with
Fabric API, on every client and on the server. Delete any older Spindle jar first.

## Testing it
- Spawn it with the Spindle Spawn Egg or `/summon spindle:spindle`.
- **Play in Survival.** It ignores Creative and Spectator players.
- It stalks for a while before it hunts. Hitting it makes it hunt you straight away.
- Operator commands, aimed at the nearest Spindle:
  - `/spindle debug` shows what it's thinking in your action bar (run again to turn off)
  - `/spindle hunt` makes it hunt you now
  - `/spindle calm` makes it drop everything and wander
  - `/spindle tension <0-100>` sets how close it is to hunting
- `/gamerule mobGriefing false` stops it breaking glass, snuffing torches and digging.

## How it behaves
- **Light and eyes pin it.** It can't move while any player is looking at it *and* it's standing in
  light. In the dark, or within about 6 blocks while hunting, looking at it does nothing.
- **So it kills the lights.** When nobody's watching it reaches out and puts out torches, lanterns,
  candles and campfires, preferring the ones behind you.
- **Stalking:** it keeps its distance, hides where you can't see it (behind cover, or far off in the
  dark), prefers to be behind you, and sometimes lets you glimpse it. Underground and in buildings it
  measures distance by walking route, waits round corners on the way you're heading, and climbs onto
  cave ceilings to drop on you.
- **Hunting:** it has to find you. It sees you, hears you walking nearby and sprinting from further
  away, and hears you break blocks. Sneaking and standing still are silent. Break line of sight quietly
  and it goes to where it last knew you were, searches further along the way you were going, sends its
  probe into nearby buildings, and eventually gives up and goes back to stalking.
- **Its body fits the space:** 5.6 blocks tall in the open, hunched in a hallway, folded flat in a
  1x2 tunnel. Its legs plant on the real ground, swing their knees out of the way of walls and ceilings,
  and telescope shorter where there's no room.
- **Getting in:** it opens wooden doors. If it can't walk to you it reaches in with a tendril through a
  window, door or gap (shattering glass). If there's no way in at all, it digs one, cracking blocks one
  at a time (not obsidian, containers, or next to water and lava).
- **The probe:** when you hide indoors it sends a mechanical probe with a glowing eye in to search room
  by room, mumbling. Moving in its view gets you spotted (sprinting instantly, sneaking slowly); standing
  still almost never does unless it's right on top of you. Walking nearby is heard through walls. It
  remembers rooms it has already searched.
- **Grabs:** the tendril either snatches what's in your hand (it turns up later on your bed, or near you)
  or drags you out. Mash sneak to break free, or have a friend hurt it.
- **Counterplay:** shoot it while it's frozen in the light and it breaks off and retreats. It also
  retreats below 30% health and comes back later. Groups of three or more make it keep its distance.
- **It never stops:** it keeps the chunks around it loaded and walks across the world to the most
  isolated player, even across server restarts.

## Development
`./gradlew build` (needs JDK 21+). Every push builds the mod and runs automated behaviour tests on a
dedicated server with simulated players (`ci/selftest.sh`): open-ground hunt, tunnel, door, window grab,
digging into a sealed hut, probe search, stalking, light freeze, ceiling ambush, retreat, cross-world
travel, a mansion-style corridor and stepped terrain. On the `dev` branch it also launches the real
client and saves screenshots of staged scenes (`ci/shots.sh`).
