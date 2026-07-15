# Delta Utility Mod

Client-side Fabric utility mod for Minecraft 26.2: select an area, then let the mod mine it out or fill it in for you.

## Features

- **Area selection** with `/pos1` and `/pos2` (look at a block and run the command), with a solid-looking colored outline of the selected box (hex-configurable color) and live size feedback.
- **Hands-off camera** — the mod only steers your view while walking; breaking and placing leave the camera alone.
- **AutoMine** — mines every block in the selection, top-down, preferring nearby and exposed blocks and saving the block under your feet for last.
- **AutoFill** — fills the selection with a chosen block, bottom-up and farthest-first so it never seals you into a corner, and never places a block inside you. Fills straight through water, lava, tall grass, and snow, and breaks soft obstructions (flowers, saplings, torches) that occupy a fill spot.
- **A\* pathfinding** — the bot walks real paths to out-of-reach blocks: it routes around terrain, takes diagonals, steps up, drops safely (max 3 blocks), and tunnels through blocks only when needed (cheaply inside the selection, reluctantly outside). Falls back to direct walking if no path is found.
- **Smart tools** — picks the fastest safe tool in your hotbar, and if a better one is sitting in your inventory it swaps it into the hotbar automatically. Stops before any tool drops below the configured minimum durability.
- **Auto eat** — eats from your hotbar or inventory when hunger drops below the threshold.
- **Lava safety** (on by default) — skips blocks that touch lava so the bot never floods itself; skipped blocks are reported when the job finishes.
- **Health safety** — pauses the job when your health drops to a configurable level (default 6/20), so it never mines on while you're dying. Set to 0 to disable.
- **Inventory-full pause** — AutoMine pauses with a warning when your inventory has no free slot, so drops don't despawn on the ground.
- **Keybinds** — set Pos1/Pos2 (default `[` / `]`), start/pause AutoMine (default `\`), and stop the current job, all rebindable under Controls.
- **Chest depositing** — set a chest with `/automine chest`; when the inventory fills, the bot deposits everything (except tools, food, and torches) and resumes mining.
- **Drop sweep** — collects leftover item drops in the area before finishing a mining job.
- **Block filter** — `/automine filter` include/exclude lists for ore-only mining or leaving certain blocks alone.
- **Status HUD** — live phase/progress/ETA above the hotbar while a job runs.
- **Torch placement** (opt-in) — lights the area from your torch supply as light levels drop.
- **AutoFill replace mode** — `/autofill start <block> replace` mines out existing blocks too, fully converting the area.
- **Falling-block awareness** — sidesteps unsupported gravel/sand overhead and avoids pathing under it.
- **Safety cap** — selections above 262,144 blocks (64³) are refused instead of freezing the game.
- **Smooth aim** — camera rotation is rate-limited instead of snapping.
- Progress/ETA status, pause/resume that leaves your manual controls alone, and settings that persist in `config/delta-utilities.properties`.
- Optional **Mod Menu** config screen with all settings (toggles + sliders).

## Commands

| Command | What it does |
| --- | --- |
| `/pos1`, `/pos2` | Set the selection corners to the block you're looking at |
| `/automine start` | Mine everything in the selection |
| `/automine pause` / `resume` / `stop` | Control the current job |
| `/automine reset` | Stop and clear the selection |
| `/automine status` | Blocks left, progress %, ETA |
| `/automine range <1-6>` | Max block-breaking distance |
| `/automine moverange <1-6>` | How close the bot walks before stopping |
| `/automine delay <1-20>` | Ticks between block breaks |
| `/automine move <true\|false>` | Toggle automatic walking |
| `/automine tools <true\|false>` | Toggle smart tool switching |
| `/automine eat <true\|false>` | Toggle auto eating |
| `/automine hunger <1-19>` | Hunger level that triggers eating |
| `/automine health <0-19>` | Health level that pauses the job (0 = off) |
| `/automine lavasafety <true\|false>` | Toggle skipping lava-adjacent blocks |
| `/automine mindurability <1-250>` | Minimum tool durability to keep |
| `/automine chest` / `chest clear` | Set/clear the deposit chest (look at a chest) |
| `/automine filter add\|remove <block>` | Edit the block filter |
| `/automine filter mode include\|exclude` / `list` / `clear` | Manage the filter |
| `/automine torch <true\|false>` | Auto torch placement (default off) |
| `/automine hud <true\|false>` | Status HUD above the hotbar (default on) |
| `/autofill start <block>` | Fill the selection (e.g. `dirt` or `minecraft:dirt`) |
| `/autofill start <block> replace` | Fill and also mine out existing blocks |
| `/autofill pause` / `resume` / `stop` / `reset` / `status` | Control AutoFill |
| `/delta outline on` / `off` | Toggle the selection outline |
| `/delta outline color <hex>` | Outline color, e.g. `3DE1FF` |
| `/automine sweep <true\|false>` | Drop collection sweep (default on) |
| `/delta help` | Command summary in chat |

## Building

Requires JDK 25.

```
gradle build
```

The jar is written to `build/libs/Delta.jar`.

## Notes

- This mod automates player input (movement, aiming, mining, eating). Anti-cheat plugins on multiplayer servers are likely to flag that; use it only where automation is allowed.
- The mod is fully client-side; nothing needs to be installed on the server.
