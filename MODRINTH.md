# Delta Utility Mod

**Select an area. Walk away. Come back to find it mined out or filled in.**

Delta Utility Mod is a client-side Fabric mod that automates repetitive block work. Mark two corners, start a job, and the mod handles the rest: real pathfinding to every block, smart tool switching, eating when hungry, and safety checks so it never gets you killed doing it.

Nothing is required on the server — it works in singleplayer and on servers (where automation is allowed).

## Quick Start

1. Look at a block and run `/pos1`
2. Look at another block and run `/pos2`
3. Run `/automine start` — or `/autofill start dirt` to fill instead
4. Check progress anytime with `/automine status`

That's it. The particle outline shows your selection, and it clears itself when the job finishes.

## Highlights

- ⛏️ **AutoMine** — mines every block in the selection, top-down, nearest-first, saving the block under your feet for last
- 🧱 **AutoFill** — fills the selection bottom-up, placing straight through water, lava, tall grass, and snow, and breaking soft obstructions (flowers, saplings, torches) in the way
- 🧭 **Real A\* pathfinding** — routes around terrain, takes diagonals, steps up ledges, drops safely up to 3 blocks, jumps on its own, and tunnels through obstacles only when it has to (preferring blocks it needs to mine anyway)
- 🛠️ **Smart tools** — picks the fastest safe tool for each block from your hotbar, and pulls a better one from your inventory automatically if needed
- 🔥 **Lava safety** — skips blocks touching lava so it never floods itself; skipped blocks are reported at the end
- ❤️ **Health safety** — pauses the job if your health drops low, instead of mining on while something kills you
- 🎒 **Inventory-full protection** — pauses mining when you have no free slot, so drops don't despawn on the ground
- 🍖 **Auto eat** — eats from hotbar or inventory when hunger gets low, holding right-click like a player would
- ⌨️ **Keybinds** — set corners, start/pause, and stop from rebindable hotkeys (see Controls)
- 📊 **Progress tracking** — blocks left, percent done, and a live ETA

## How It Behaves

**AutoMine** works the selection top-down by layer, prefers blocks close to where it just mined (less back-and-forth walking), rescans the whole area before declaring itself done, and defers unreachable blocks to retry later instead of stalling. Tools below your minimum durability (default 25) are never used — if nothing safe is left, it stops and tells you rather than snapping your pickaxe.

**AutoFill** fills lowest layer first, farthest positions first, so it can never wall itself into a corner — and it will never place a block inside your own body. It keeps placing until the area is genuinely full, pulling the largest matching stacks from your inventory as the hotbar runs out, and stops cleanly if you run out of blocks.

**Pausing** (manually, or automatically from low health / full inventory) releases all movement and mouse controls immediately — the mod never fights you for the keyboard. When a job stops or finishes, it stops touching tools, food, and keys entirely.

**Selections are capped** at 262,144 blocks (64×64×64) so a misclicked corner can't freeze your game.

## Commands

| Command | Description |
|---|---|
| `/pos1`, `/pos2` | Set selection corners to the block you're looking at |
| `/automine start` / `stop` / `pause` / `resume` | Control mining |
| `/automine status` | Blocks left, progress %, ETA |
| `/automine reset` | Stop and clear the selection |
| `/autofill start <block>` | Fill the selection (e.g. `dirt` or `minecraft:dirt`) |
| `/autofill stop` / `pause` / `resume` / `status` / `reset` | Control filling |
| `/delta outline on` / `off` | Toggle the selection outline |
| `/delta help` | Full command list in chat |

**Settings commands:**

| Command | Description |
|---|---|
| `/automine range <1-6>` | Block-breaking reach |
| `/automine moverange <1-6>` | How close it walks before stopping |
| `/automine delay <1-20>` | Ticks between block actions |
| `/automine move <true\|false>` | Auto movement on/off |
| `/automine tools <true\|false>` | Smart tool switching on/off |
| `/automine eat <true\|false>` | Auto eating on/off |
| `/automine hunger <1-19>` | Hunger level that triggers eating (default 14) |
| `/automine health <0-19>` | Health level that pauses the job (default 6, 0 = off) |
| `/automine lavasafety <true\|false>` | Skip lava-adjacent blocks (default on) |
| `/automine mindurability <1-250>` | Minimum tool durability to keep (default 25) |

## Mod Menu

Every setting is also available in a Mod Menu config screen — grouped into Automation, Mining, and Safety & Display sections, with sliders for numeric values, hover tooltips explaining each option, and a one-click reset to defaults. All settings persist between sessions.

## Keybinds

Rebindable under Options → Controls, in their own category:

- **Set Pos1** (default `[`) and **Set Pos2** (default `]`)
- **Start / Pause AutoMine** (default `\`)
- **Stop Current Job** (unbound by default)

## Notes

- Requires **Fabric API**. **Mod Menu** is optional but recommended.
- This mod automates player input (movement, aiming, mining, eating). Anti-cheat systems on multiplayer servers may flag that — use it only where automation is allowed.
