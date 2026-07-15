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
