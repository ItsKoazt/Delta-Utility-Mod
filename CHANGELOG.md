Delta Utility Mod v1.2.0
This update makes AutoMine truly autonomous: it can now empty its own inventory into a chest, collect its drops, mine only the blocks you want, and light the area as it goes.

New Features

* Added chest depositing: look at a chest and run `/automine chest`. When the inventory fills up, the bot walks to the chest, deposits everything except tools, food, and torches, and goes back to mining. If the chest fills up or disappears, it pauses and tells you.
* Added a drop collection sweep: when the area is fully mined, the bot walks around collecting leftover item drops before finishing.
* Added a block filter: `/automine filter add <block>`, `remove`, `list`, `clear`, and `mode include|exclude`. Include mode mines only the listed blocks (ore-only mining); exclude mode mines everything except them.
* Added a status HUD above the hotbar showing the current phase, blocks left, progress, and ETA. Toggle with `/automine hud` or in the config screen.
* Added torch placement (off by default): places torches from your inventory while mining when light gets low. Toggle with `/automine torch` or in the config screen.
* Added AutoFill replace mode: `/autofill start <block> replace` also mines out existing blocks, turning fill into a full "set this area to X" operation. Normal fill still never touches solid blocks.
* Added falling block awareness: the bot sidesteps when gravel or sand hangs unsupported above its head, and pathfinding avoids standing under floating gravel.

Camera and Outline

* The mod no longer takes over your camera while breaking or placing blocks. It only steers the view while actually walking, since mining and placing never needed it. Watching something else while it works is now comfortable.
* The selection outline is now a dense, near-solid colored line (still particles, drawn with steady dust instead of drifting sparkles).
* The outline color is configurable with hex codes: `/delta outline color 3DE1FF` or the new Outline Color field in the config screen. Default is a Delta cyan.

Fixes and Improvements

* The drop sweep now gives up on drops it can't get closer to (in holes, on ledges) and moves to the next one instead of getting stuck.
* The drop sweep is now toggleable with `/automine sweep <true|false>` or the Drop Sweep option in the config screen (on by default).
* Release builds now derive the mod version from the git tag, so the jar version can never drift from the published version.
* Path clearing now ignores the block filter, so filtered-out blocks can still be tunneled through to reach targets.
* The AutoMine start message now notes when a block filter is active.

---

Delta Utility Mod v1.1.0
This update rewrites how the mod moves through the world, makes AutoFill work in far more situations, and gives the whole mod a cleaner identity and settings screen.

Important
The mod's internal ID changed in this version. Delete the old Delta jar from your mods folder before installing this one, or the game will load both. Your settings carry over automatically.

New Features

* Added A* pathfinding for AutoMine and AutoFill. The bot now plans real routes around terrain instead of walking straight at the target.
* Added diagonal walking, one-block step-ups, and safe drops of up to 3 blocks while pathing.
* Added smart tunneling: when blocked, the bot prefers to mine through blocks inside your selection and avoids breaking blocks outside it.
* Added automatic jumping over one-block lips, freshly placed blocks, and when the bot gets wedged.
* Added Lava Safety (on by default): blocks touching lava are skipped so the bot cannot flood itself. Skipped blocks are reported when the job finishes.
* Added smooth camera rotation instead of instant snapping.
* Added a selection size limit of 262,144 blocks (64x64x64) so huge selections can no longer freeze the game.
* Added live selection size feedback when setting `/pos1` and `/pos2`.
* Added Health Safety: jobs pause automatically when your health drops to a configurable level (default 6 of 20). Set to 0 to disable.
* Added inventory-full protection: AutoMine pauses with a warning when your inventory has no free slot, so drops do not despawn.
* Added keybinds, rebindable under Controls: Set Pos1 (default [), Set Pos2 (default ]), Start / Pause AutoMine (default \), and Stop Current Job (unbound).
* Added `/delta help` for a full command list in chat.
* Added `/automine lavasafety <true|false>`.
* Added `/automine health <0-19>`.
* Added `/autofill reset`.

AutoFill Improvements

* AutoFill now fills through water and lava. Flooded spots were previously skipped; blocks are now placed directly into fluids.
* AutoFill now fills through tall grass, snow layers, and other replaceable blocks.
* AutoFill now breaks soft obstructions (flowers, saplings, torches, mushrooms) that occupy a fill spot, then places. Solid blocks are never touched.
* AutoFill now fills farthest-first within each layer so the bot cannot seal itself into a corner.
* AutoFill never places a block inside your own body; it steps out of the way first.
* AutoFill now pulls the largest matching stack from your inventory, so long jobs need fewer swaps.

Smart Tools Improvements

* If the best tool for a block is not in your hotbar, the bot now searches your whole inventory and swaps it in automatically.
* Tool swapping still respects the Min Tool Durability setting.

Mining Improvements

* Mining order is now top-down by layer.
* The bot prefers blocks near the last mined block to cut down on travel time.
* The block directly under your feet is saved for last.
* Water and lava blocks are no longer treated as mineable, so the bot no longer grinds on them for up to 9 seconds each.
* Unreachable blocks are skipped and retried later instead of stalling the whole job.

Mod Menu Settings

* Redesigned the settings screen with grouped sections: Automation, Mining, and Safety & Display.
* Added hover tooltips to every setting explaining what it does.
* Added color-coded ON/OFF states and slider values.
* Added the screen title and a version footer.
* Added a Lava Safety toggle.
* Renamed the bottom buttons to Save & Close, Cancel, and Reset.

Fixes

* Fixed the Mod Menu Finish and Cancel buttons not closing the screen reliably.
* Fixed the mod interfering with manual right-clicking while a job runs.
* Fixed jobs stopping when a chest or other container is open; they now wait instead.
* Fixed settings that fail to load or save failing silently; they are now logged.

Other Changes

* The mod is now named Delta Utility Mod everywhere, including the mod list, Mod Menu, and config screen.
* Version bumped to 1.1.0.
