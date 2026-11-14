# Spindle (Fabric 1.21.1)

A big stalker entity. This is just the creature for now; the ARG layer comes later.

## Build
Needs JDK 21. From this folder:

    ./gradlew build        (Windows: gradlew.bat build)

The mod jar ends up in `build/libs/spindle-0.1.0.jar`. Put it in `mods/` along with Fabric API, on every client and the server.

## Testing it
- Spawn it: the Spindle Spawn Egg, or `/summon spindle:spindle`.
- **Test in Survival.** It ignores Creative and Spectator players (they still count as "watching" though).
- Hitting it makes it hunt you right away. Otherwise it stalks first; tension builds faster when you're alone,
  in the dark, underground, and at night.
- `/gamerule mobGriefing false` stops it breaking glass and torches (it can still open wooden doors).

## How it behaves
- **Light + eyes pin it.** It can't move while any player sees it *and* it's standing in light (level 7+).
  In the dark, or within ~6 blocks while it's hunting, looking at it does nothing.
- **So it kills the lights.** When nobody's watching it reaches out with a tendril and puts out torches,
  lanterns, candles and campfires, preferring the ones behind you.
- **Stalking:** keeps a distance that shrinks as tension rises, picks hiding spots nobody can see, prefers
  behind you and in the dark. Sometimes it deliberately lets you glimpse it. Look at it, look away, and it's moved.
- **Hunting:** picks the most vulnerable player (alone, dark, hurt, underground), avoids groups of 3+,
  gives up in open daylight.
- **Folding:** it's 5.6 blocks tall standing, but folds down to fit a 1x2 tunnel when it needs to.
  Climbs walls like a spider.
- **Tendrils:** reaches up to 16 blocks through windows, doors and gaps. Shatters glass, pushes open wooden
  doors, trapdoors and gates. On contact it either snatches the item in your hand (it turns up later on
  your bed) or grabs you and drags you out.
  - Break free: mash sneak, or have a friend hurt it while it's holding you.
  - Get dragged into a wall and you get slammed and dropped.
- **Counterplay:** hurt it while it's frozen in the light (bows) and it breaks and retreats. It also retreats
  below 30% health and comes back later.
