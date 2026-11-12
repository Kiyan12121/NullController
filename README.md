# NullController 3.4

Version 3.4 covers everything on the list. Owner-only control; players only get targeted after `/nullagree accept`.

## Army control
- Summon, dismiss, recall (`/nullcmd summon <n>`, `/nullcmd dismiss`, `/nullcmd recall`)
- Modes: GUARD, FOLLOW, HUNT (roster only), STAY, GOTO (any coordinates), AUTO-MINE, LUMBERJACK, FARM, PATROL, FORGE DUTY
- Combat: Nulls fight hostile mobs within 8 blocks. In HUNT mode they also attack roster players.

## Work
- AUTO-MINE, LUMBERJACK, FARM: each Null carries a 9-slot pack. When it's full, the Null walks to the deposit chest (`/nullcmd chest set` while looking at a chest) and empties it.
- FARM replants crops as it harvests.
- Fortify (`/nullcmd fortify`) re-places any missing blocks from the last completed build.
- Forge duty: `/nullcmd forge set` while looking at the forge block, then `/nullcmd forge`.

## Builders
- Void Citadel, Bridge, Watchtower, Barracks, Forge Gate, Beacon Spire: `/nullcmd build <type>`.
- Bedrock Cage (roster players only): `/nullcmd cage <player>`. Builds a sealed 5x5x5 cage around them.

## Consent-gated
- Bedrock Cage, Invasion, Nether ambush, and HUNT only target players on the roster.
- Invasion: `/nullcmd invade <player>` sends a timed group from about 40 blocks away. They leave when the timer runs out.
- Nether ambush: `/nullcmd ambush on|off`. Two hunters appear when a roster player enters the Nether.
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

## Decisions I made that you can change
- Totem: 3 charges, 30-minute army cooldown after it breaks, pulse radius 8.
- Succession: minting a new totem voids the old ones. Naming a Commander as successor isn't in yet, because Commanders aren't a separate NPC type yet.
- Forge Awakening: no Commander rises from the Forge. It's a squad at the Forge.
- Faction Clash: Nulls don't fight each other.
- Nether ambush: triggers on any entry into the Nether, not only through a portal.

## Known gaps
- Packs are held in memory. Anything in a pack is lost on restart. Deposited loot is safe.
- Sapling replanting for LUMBERJACK isn't in.
- Nulls use Citizens navigation, so very long walks rely on teleports.
- Nothing here has been compiled. If the Actions build goes red, send me the log.

## Build and install
1. Upload this folder to GitHub. The Actions tab builds `NullController-3.0.0.jar` (the artifact is named `NullController`).
2. Upload the jar and the Citizens jar to `/plugins` on Falixnode, then restart.
3. Set `null-skin` in config.yml to the skin you want.
4. Give the server 3 GB of RAM or more.
