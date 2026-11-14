say SPINDLE_TEST_START
forceload add -32 -32 32 32
summon spindle:spindle 0 -60 0
summon spindle:spindle 8 -60 8
schedule function spindletest:check 600t
