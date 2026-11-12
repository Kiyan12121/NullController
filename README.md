# NullController 4.0

## Hunting and the Null Civilisation
- `/nullcmd hunt <player>`: the whole army hunts one roster player until you change the order. Use `/nullcmd hunt any` to go back to hunting whoever on the roster is nearest.
- `/nullcmd build city` (End only): a walled Null Civilisation about 61 blocks square. It has a central Hall with a throne for you, four corner towers, a spire with an End Rod, and sixteen five-by-five quarters for the Nulls. It takes a couple of minutes to finish. Raise `build-blocks-per-tick` in config to build faster.

Owner-only control. Players are only ever targeted after they run `/nullagree accept`.

## What's new in 4.0
- `/nullcmd help` lists every order. `/nullcmd ranks` shows each Null's rank, health and state. Tab completion works for commands, build types and events.
- Forge Awakening: a Null that holds at the Forge for 60 seconds rises one rank (D to I, then I to A). You get a chat message when it happens.
- Alarm: when 5 or more hostile mobs crowd within 16 blocks of you (`alarm-threshold`), you get an ALARM title, and the army's reach doubles for 30 seconds.
- PvP: hunters pick the roster player who is near and weak, not just the nearest one.
- Lore title cards on each event and on the Bedrock Cage.
- The `/nullui` panel gained Ranks and Help buttons.

## Army control
- Summon, dismiss, recall: `/nullcmd summon <n>`, `/nullcmd dismiss`, `/nullcmd recall`.
- Modes: GUARD, FOLLOW, HUNT (roster only), STAY, GOTO (any coordinates), MINE, LUMBER, FARM, PATROL, FORGE.
- Roaming: Nulls drift around the leader (or you) instead of stacking on you. They only teleport if they fall more than 64 blocks behind.
- Leader and ranks: the first Null is R (leader). A and I are officers, the rest are D. Rank scales command radius and strength (R 1.5x, A 1.25x, I 1.0x, D 0.75x). `/nullcmd status` shows the counts.
- Combat: Nulls fight hostile mobs within 8 blocks, focusing the weakest in range. A Null under 6 HP falls back to the group until it heals.
- Guard duty and patrols: idle Nulls on GUARD or PATROL keep swinging their arms.

## Work
- MINE, LUMBER, FARM: each Null carries a 9-slot pack. When it's full, the Null walks to the deposit chest (`/nullcmd chest set` while looking at a chest) and empties it.
- FARM replants crops as it harvests.
- LUMBER replants the sapling when a tree's trunk base is cut, if the ground below is dirt or grass. No sapling is used up.
- Forge duty: `/nullcmd forge set` while looking at the forge block, then `/nullcmd forge`.

## Builders
- Void Citadel, Bridge, Watchtower, Barracks, Forge Gate, Beacon Spire: `/nullcmd build <type>`.
- Preview: `/nullcmd build preview <type>` shows the footprint with white particles for 5 seconds before anything is placed.
- Undo: `/nullcmd build undo` removes the last finished build.
- Fortify: `/nullcmd fortify` re-places missing blocks from the last build. Auto-repair also does this every 3 minutes when idle.
- Bedrock Cage (roster players only): `/nullcmd cage <player>`. Builds a sealed 5x5x5 cage, then the Nulls guard it from a ring outside. Running `/nullcmd guard` releases the cage guard.

## Consent-gated
- Bedrock Cage, Invasion, Nether ambush, and HUNT only target players on the roster.
- Invasion: `/nullcmd invade <player>` sends a timed group from about 40 blocks away.
- Nether ambush: `/nullcmd ambush on|off`. Two hunters appear when a roster player enters the Nether.
- Ender pearls: when a roster player uses an ender pearl, any hunters on them teleport to the landing spot.
- Revoking: remove the UUID from `consented-players` and run `/nullreload`. Hunters stop on their next tick.

## Totem of NULL
- `/nullcmd totem` issues the active totem. Right-click it to pulse hostile mobs and roster players within 8 blocks (knockback, blindness, slowness). It never affects you or the Nulls.
- Each pulse uses a charge. Charges default to 3. When the last one is spent, the army disbands and can't be resummoned for 30 minutes.
- `/nullcmd succession` mints a new totem and makes every earlier totem inert.

## Lore events
- `/nullcmd event forge`: squad of 5, Forge Duty. Needs a Forge set first.
- `/nullcmd event capital`: squad of 8 guards you, and a watchtower is built.
- `/nullcmd event end`: squad of 5 and a barracks. Only works in The End. No lightning.
- `/nullcmd event clash`: squad of 8 that engages mobs. Nulls don't fight each other.

## Decisions you can change
- Totem: 3 charges, 30-minute army cooldown after it breaks, pulse radius 8.
- Ranks: R is the leader, A is the next two, I is the next four, D is everyone else. Change the cut-offs in `NullArmy.recomputeRanks`.
- Ender pearls: only hunters follow a pearl. Idle Nulls don't.
- Sapling replanting is free. It doesn't use anything from the pack.
- Nether ambush triggers on any entry into the Nether, not only through a portal.

## Known gaps
- Succession by naming a Commander, and a night watch rotation, are not in yet.
- Skin: the black skin PNG is in `skins/`, but Citizens can't load a skin from a URL through its public API from this plugin. Set `null-skin` in config.yml to a real Minecraft username whose skin is black, and Citizens fetches it by name. The default `NULL` is not a real account, so Nulls show as Steve until you change it.
- Packs are held in memory. Anything in a pack is lost on restart. Deposited loot is safe.
- Only the lowest log of a tree is replanted. Big 2x2 trees such as dark oak won't regrow from one sapling.
- Nulls use Citizens navigation, so very long walks rely on teleports.

## Build and install
1. Push this folder to GitHub. The Actions tab builds `NullController-3.5.0.jar` (the artifact is named `NullController`).
2. Upload the jar and the Citizens jar to `/plugins` on Falixnode, then restart.
3. Set `null-skin` in config.yml to the skin you want.
4. Give the server 3 GB of RAM or more.
