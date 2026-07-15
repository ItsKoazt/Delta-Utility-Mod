package com.delta.utilitymod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;

public class DeltaUtilityModClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("delta-utilities");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("delta-utilities.properties");

    /** Hard cap on selection volume so a bad /pos1//pos2 pair can't freeze or OOM the client. */
    private static final long MAX_SELECTION_VOLUME = 262_144L; // 64 x 64 x 64

    private enum JobMode { MINE, FILL }

    /** Sub-phase of a running mining job. */
    private enum MinePhase { WORK, GOTO_CHEST, DEPOSIT, SWEEP }

    // Selection + work queue
    private static BlockPos pos1;
    private static BlockPos pos2;
    private static final Queue<BlockPos> queue = new ArrayDeque<>();
    private static final Set<BlockPos> delayedMineTargets = new HashSet<>();

    // Job state
    private static boolean running = false;
    private static boolean paused = false;
    private static JobMode jobMode = JobMode.MINE;
    private static BlockPos currentTarget = null;
    private static BlockPos lastMinedPos = null;
    private static int initialTaskBlocks = 0;
    private static long taskStartedAtMillis = 0L;
    private static int lavaSkippedCount = 0;

    // Fill state
    private static Block fillBlock = Blocks.AIR;
    private static String fillBlockName = "air";
    private static int placeCooldown = 0;
    private static boolean fillReplace = false;

    // Mining phases (chest deposit + drop sweep)
    private static MinePhase minePhase = MinePhase.WORK;
    private static int phaseTicks = 0;
    private static int depositClickCooldown = 0;
    private static BlockPos depositChest = null;

    // Block filter (session-scoped, cleared on game restart)
    private static final Set<Block> filterBlocks = new HashSet<>();
    private static boolean filterInclude = false;

    // Torch placement
    private static int torchCooldown = 0;

    // Drop sweep progress tracking (skips unreachable drops)
    private static int sweepTargetId = -1;
    private static int sweepNoProgressTicks = 0;
    private static double sweepBestDistance = Double.MAX_VALUE;
    private static final Set<Integer> sweepSkippedDrops = new HashSet<>();

    // Camera control
    private static final float TURN_SPEED_WORK = 15.0F;  // deg/tick while mining/placing
    private static final float TURN_SPEED_WALK = 21.0F;  // deg/tick while walking
    private static final float AIM_THRESHOLD_DEG = 30.0F;
    private static float yawVelocity = 0.0F;
    private static float pitchVelocity = 0.0F;
    private static int lastFaceTick = Integer.MIN_VALUE;
    private static float expectedYaw = Float.NaN;
    private static float expectedPitch = Float.NaN;
    private static int cameraYieldTicks = 0;
    private static BlockPos lastAimTarget = null;
    private static int aimTicks = 0;

    // Settings (persisted)
    private static boolean selectionOutlineEnabled = true;
    private static boolean autoMove = true;
    private static boolean autoTools = true;
    private static boolean autoEat = true;
    private static boolean lavaSafety = true;
    private static int hungerThreshold = 14;
    private static int delayTicks = 2;
    private static double maxRange = 4.5D;
    private static double moveStopRange = 3.8D;
    private static int minToolDurability = 25;
    private static int pauseHealth = 6; // health points out of 20; 0 disables
    private static boolean autoTorch = false;
    private static boolean hudEnabled = true;
    private static boolean dropSweep = true;
    private static String outlineColorHex = "#3DE1FF";
    private static int outlineColorRgb = 0x3DE1FF;

    // Safety state
    private static boolean lowHealthTriggered = false;
    private static boolean wasInventoryFull = false;

    // Keybindings
    private static KeyMapping keySetPos1;
    private static KeyMapping keySetPos2;
    private static KeyMapping keyStartPause;
    private static KeyMapping keyStopJob;

    // Breaking state
    private static BlockPos lastBreakTarget = null;
    private static int breakTicksOnTarget = 0;
    private static int breakRestartTicks = 0;
    private static int tickCooldown = 0;

    // Movement state
    private static Vec3 lastPathPos = Vec3.ZERO;
    private static int stuckTicks = 0;
    private static int targetTicks = 0;
    private static int jumpPulseTicks = 0;
    private static int jumpCooldownTicks = 0;

    // Pathfinding state
    private static List<DeltaPathfinder.Step> path = null;
    private static int pathIndex = 0;
    private static BlockPos pathTargetKey = null;
    private static int repathCooldown = 0;
    private static int pathRefreshTicks = 0;
    private static int nearWaypointTicks = 0;

    // Eating state
    private static int eatTicks = 0;
    private static boolean wasEating = false;

    // Inventory state
    private static int inventorySwapCooldown = 0;

    // Selection outline
    private static int outlineParticleTicks = 0;

    @Override
    public void onInitializeClient() {
        loadSettings();
        registerCommands();
        registerKeyMappings();
        ClientTickEvents.END_CLIENT_TICK.register(DeltaUtilityModClient::onClientTick);
    }

    private static void registerKeyMappings() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("delta_utility_mod", "keys"));
        keySetPos1 = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.delta_utility_mod.pos1", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));
        keySetPos2 = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.delta_utility_mod.pos2", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
        keyStartPause = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.delta_utility_mod.start_pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, category));
        keyStopJob = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.delta_utility_mod.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("pos1").executes(context -> {
                String message = setPosFromLook(1);
                context.getSource().sendFeedback(Component.literal(message));
                return message.startsWith("§c") ? 0 : 1;
            }));

            dispatcher.register(ClientCommands.literal("pos2").executes(context -> {
                String message = setPosFromLook(2);
                context.getSource().sendFeedback(Component.literal(message));
                return message.startsWith("§c") ? 0 : 1;
            }));

            dispatcher.register(ClientCommands.literal("delta")
                    .then(ClientCommands.literal("help").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(String.join("\n",
                                "§b--- Delta Utilities ---",
                                "§f/pos1§7, §f/pos2 §7- select the area you are looking at",
                                "§f/automine start|pause|resume|stop|reset|status",
                                "§f/automine range|moverange|delay|hunger|health|mindurability <n>",
                                "§f/automine move|tools|eat|lavasafety|torch|hud|sweep <true|false>",
                                "§f/automine chest §7- deposit inventory into the chest you're looking at",
                                "§f/automine filter add|remove|list|clear|mode §7- choose which blocks to mine",
                                "§7Keybinds (see Controls): set pos1/pos2, start/pause, stop",
                                "§f/autofill start <block> [replace]§7, §fpause|resume|stop|reset|status",
                                "§f/delta outline on|off §7- selection outline",
                                "§f/delta outline color <hex> §7- outline color, e.g. 3DE1FF")));
                        return 1;
                    }))
                    .then(ClientCommands.literal("outline")
                            .then(ClientCommands.literal("on").executes(context -> {
                                setSelectionOutlineEnabled(true);
                                context.getSource().sendFeedback(Component.literal("§aSelection outline enabled."));
                                return 1;
                            }))
                            .then(ClientCommands.literal("off").executes(context -> {
                                setSelectionOutlineEnabled(false);
                                context.getSource().sendFeedback(Component.literal("§cSelection outline disabled."));
                                return 1;
                            }))
                            .then(ClientCommands.literal("color")
                                    .then(ClientCommands.argument("hex", StringArgumentType.word()).executes(context -> {
                                        String hex = StringArgumentType.getString(context, "hex");
                                        if (!setOutlineColorHex(hex)) {
                                            context.getSource().sendFeedback(Component.literal("§cInvalid color. Use a 6-digit hex code, e.g. §f3DE1FF §cor §fFF0044§c."));
                                            return 0;
                                        }
                                        context.getSource().sendFeedback(Component.literal("§aOutline color set to §f" + outlineColorHex));
                                        return 1;
                                    })))));

            dispatcher.register(ClientCommands.literal("autofill")
                    .then(ClientCommands.literal("start")
                            .then(ClientCommands.argument("block", StringArgumentType.word())
                                    .executes(context ->
                                            startFill(context.getSource(), StringArgumentType.getString(context, "block"), false))
                                    .then(ClientCommands.literal("replace").executes(context ->
                                            startFill(context.getSource(), StringArgumentType.getString(context, "block"), true)))))
                    .then(ClientCommands.literal("pause").executes(context -> {
                        if (!running || jobMode != JobMode.FILL) {
                            context.getSource().sendFeedback(Component.literal("§cAutoFill is not running."));
                            return 0;
                        }
                        pauseJob();
                        context.getSource().sendFeedback(Component.literal("§eAutoFill paused. Use §f/autofill resume §eto continue."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("resume").executes(context -> {
                        if (!running || jobMode != JobMode.FILL) {
                            context.getSource().sendFeedback(Component.literal("§cAutoFill is not running."));
                            return 0;
                        }
                        resumeJob();
                        context.getSource().sendFeedback(Component.literal("§aAutoFill resumed."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("stop").executes(context -> {
                        if (!running || jobMode != JobMode.FILL) {
                            context.getSource().sendFeedback(Component.literal("§cAutoFill is not running."));
                            return 0;
                        }
                        stopJob();
                        context.getSource().sendFeedback(Component.literal("§cAutoFill stopped."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("reset").executes(context -> {
                        stopJob();
                        clearSelection();
                        context.getSource().sendFeedback(Component.literal("§eAutoFill selection reset."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(formatTaskStatus("AutoFill", getBlocksLeft())));
                        return 1;
                    })));

            dispatcher.register(ClientCommands.literal("automine")
                    .then(ClientCommands.literal("start").executes(context -> {
                        String message = startAutoMineJob();
                        context.getSource().sendFeedback(Component.literal(message));
                        return message.startsWith("§c") ? 0 : 1;
                    }))
                    .then(ClientCommands.literal("pause").executes(context -> {
                        if (!running) {
                            context.getSource().sendFeedback(Component.literal("§cAutoMine is not running."));
                            return 0;
                        }
                        pauseJob();
                        context.getSource().sendFeedback(Component.literal("§eAutoMine paused. Use §f/automine resume §eto continue."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("resume").executes(context -> {
                        if (!running) {
                            context.getSource().sendFeedback(Component.literal("§cAutoMine is not running."));
                            return 0;
                        }
                        resumeJob();
                        context.getSource().sendFeedback(Component.literal("§aAutoMine resumed."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("stop").executes(context -> {
                        stopJob();
                        context.getSource().sendFeedback(Component.literal("§cAutoMine stopped."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("reset").executes(context -> {
                        stopJob();
                        clearSelection();
                        context.getSource().sendFeedback(Component.literal("§eAutoMine selection reset."));
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(formatTaskStatus("AutoMine", getBlocksLeft())));
                        return 1;
                    }))
                    .then(ClientCommands.literal("range")
                            .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, 6)).executes(context -> {
                                setMiningRange(IntegerArgumentType.getInteger(context, "blocks"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine mining range set to §f" + maxRange));
                                return 1;
                            })))
                    .then(ClientCommands.literal("moverange")
                            .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, 6)).executes(context -> {
                                setMoveStopRange(IntegerArgumentType.getInteger(context, "blocks"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine move stop range set to §f" + moveStopRange));
                                return 1;
                            })))
                    .then(ClientCommands.literal("delay")
                            .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(1, 20)).executes(context -> {
                                setDelayTicks(IntegerArgumentType.getInteger(context, "ticks"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine delay set to §f" + delayTicks + " tick(s)"));
                                return 1;
                            })))
                    .then(ClientCommands.literal("move")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setAutoMoveEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine movement set to §f" + autoMove));
                                return 1;
                            })))
                    .then(ClientCommands.literal("tools")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setAutoToolsEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine tool switching set to §f" + autoTools));
                                return 1;
                            })))
                    .then(ClientCommands.literal("eat")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setAutoEatEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine auto eat set to §f" + autoEat));
                                return 1;
                            })))
                    .then(ClientCommands.literal("lavasafety")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setLavaSafetyEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine lava safety set to §f" + lavaSafety));
                                return 1;
                            })))
                    .then(ClientCommands.literal("torch")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setAutoTorchEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine torch placement set to §f" + autoTorch));
                                return 1;
                            })))
                    .then(ClientCommands.literal("hud")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setHudEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aStatus HUD set to §f" + hudEnabled));
                                return 1;
                            })))
                    .then(ClientCommands.literal("sweep")
                            .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                setDropSweepEnabled(BoolArgumentType.getBool(context, "enabled"));
                                context.getSource().sendFeedback(Component.literal("§aDrop collection sweep set to §f" + dropSweep));
                                return 1;
                            })))
                    .then(ClientCommands.literal("chest")
                            .executes(context -> {
                                BlockPos lookingAt = getLookingAtBlock();
                                Minecraft client = Minecraft.getInstance();
                                if (lookingAt == null || client.level == null
                                        || !(client.level.getBlockEntity(lookingAt) instanceof Container)) {
                                    context.getSource().sendFeedback(Component.literal("§cLook at a chest (or barrel) and run /automine chest."));
                                    return 0;
                                }
                                depositChest = lookingAt.immutable();
                                context.getSource().sendFeedback(Component.literal("§aDeposit chest set to §f" + format(depositChest)
                                        + "§a. AutoMine will empty its inventory there when full."));
                                return 1;
                            })
                            .then(ClientCommands.literal("clear").executes(context -> {
                                depositChest = null;
                                context.getSource().sendFeedback(Component.literal("§eDeposit chest cleared. AutoMine will pause when the inventory is full."));
                                return 1;
                            })))
                    .then(ClientCommands.literal("filter")
                            .then(ClientCommands.literal("add")
                                    .then(ClientCommands.argument("block", StringArgumentType.word()).executes(context -> {
                                        Block block = resolveBlock(StringArgumentType.getString(context, "block"));
                                        if (block == null) {
                                            context.getSource().sendFeedback(Component.literal("§cUnknown block: §f" + StringArgumentType.getString(context, "block")));
                                            return 0;
                                        }
                                        filterBlocks.add(block);
                                        context.getSource().sendFeedback(Component.literal("§aFilter now has §f" + filterBlocks.size()
                                                + "§a block(s), mode: §f" + (filterInclude ? "include (mine only these)" : "exclude (mine everything but these)")));
                                        return 1;
                                    })))
                            .then(ClientCommands.literal("remove")
                                    .then(ClientCommands.argument("block", StringArgumentType.word()).executes(context -> {
                                        Block block = resolveBlock(StringArgumentType.getString(context, "block"));
                                        if (block == null || !filterBlocks.remove(block)) {
                                            context.getSource().sendFeedback(Component.literal("§cThat block is not in the filter."));
                                            return 0;
                                        }
                                        context.getSource().sendFeedback(Component.literal("§aRemoved. Filter now has §f" + filterBlocks.size() + "§a block(s)."));
                                        return 1;
                                    })))
                            .then(ClientCommands.literal("mode")
                                    .then(ClientCommands.literal("include").executes(context -> {
                                        filterInclude = true;
                                        context.getSource().sendFeedback(Component.literal("§aFilter mode: §finclude §7- only listed blocks are mined."));
                                        return 1;
                                    }))
                                    .then(ClientCommands.literal("exclude").executes(context -> {
                                        filterInclude = false;
                                        context.getSource().sendFeedback(Component.literal("§aFilter mode: §fexclude §7- listed blocks are skipped."));
                                        return 1;
                                    })))
                            .then(ClientCommands.literal("list").executes(context -> {
                                if (filterBlocks.isEmpty()) {
                                    context.getSource().sendFeedback(Component.literal("§7Filter is empty: all blocks are mined."));
                                    return 1;
                                }
                                StringBuilder names = new StringBuilder();
                                for (Block block : filterBlocks) {
                                    if (names.length() > 0) {
                                        names.append("§7, §f");
                                    }
                                    Object key = BuiltInRegistries.BLOCK.getKey(block);
                                    names.append(key == null ? "?" : key.toString());
                                }
                                context.getSource().sendFeedback(Component.literal("§bFilter (" + (filterInclude ? "include" : "exclude") + "): §f" + names));
                                return 1;
                            }))
                            .then(ClientCommands.literal("clear").executes(context -> {
                                filterBlocks.clear();
                                context.getSource().sendFeedback(Component.literal("§eFilter cleared: all blocks will be mined."));
                                return 1;
                            })))
                    .then(ClientCommands.literal("health")
                            .then(ClientCommands.argument("level", IntegerArgumentType.integer(0, 19)).executes(context -> {
                                setPauseHealth(IntegerArgumentType.getInteger(context, "level"));
                                context.getSource().sendFeedback(Component.literal(pauseHealth <= 0
                                        ? "§aHealth safety disabled."
                                        : "§aJobs will pause at health §f" + pauseHealth + "§a/20 or lower."));
                                return 1;
                            })))
                    .then(ClientCommands.literal("hunger")
                            .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 19)).executes(context -> {
                                setHungerThreshold(IntegerArgumentType.getInteger(context, "level"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine will eat at hunger level §f" + hungerThreshold + "§a or lower"));
                                return 1;
                            })))
                    .then(ClientCommands.literal("mindurability")
                            .then(ClientCommands.argument("durability", IntegerArgumentType.integer(1, 250)).executes(context -> {
                                setMinToolDurability(IntegerArgumentType.getInteger(context, "durability"));
                                context.getSource().sendFeedback(Component.literal("§aAutoMine minimum tool durability set to §f" + minToolDurability));
                                return 1;
                            })))
            );
        });
    }

    private static String setPosFromLook(int which) {
        BlockPos lookingAt = getLookingAtBlock();
        if (lookingAt == null) {
            return "§cLook at a block first.";
        }
        if (which == 1) {
            pos1 = lookingAt.immutable();
        } else {
            pos2 = lookingAt.immutable();
        }
        BlockPos set = which == 1 ? pos1 : pos2;
        return "§aPos" + which + " set to §f" + format(set) + selectionSummary();
    }

    private static String startAutoMineJob() {
        if (pos1 == null || pos2 == null) {
            return "§cSet /pos1 and /pos2 first.";
        }
        long volume = selectionVolume();
        if (volume > MAX_SELECTION_VOLUME) {
            return "§cSelection is too large: §f" + volume
                    + " §cblocks (max §f" + MAX_SELECTION_VOLUME + "§c). Pick a smaller area.";
        }
        buildMineQueue();
        startTaskTracking(queue.size());
        jobMode = JobMode.MINE;
        running = true;
        paused = false;
        currentTarget = null;
        lastMinedPos = null;
        tickCooldown = 0;
        eatTicks = 0;
        stuckTicks = 0;
        targetTicks = 0;
        breakRestartTicks = 0;
        lavaSkippedCount = 0;
        lowHealthTriggered = false;
        wasInventoryFull = false;
        minePhase = MinePhase.WORK;
        phaseTicks = 0;
        delayedMineTargets.clear();
        resetPath();
        String filterNote = filterBlocks.isEmpty() ? "" : " §7(filter active: " + filterBlocks.size() + " block(s), "
                + (filterInclude ? "include" : "exclude") + " mode)";
        return "§aAutoMine started. Blocks queued: §f" + queue.size() + filterNote;
    }

    private static void pauseJob() {
        paused = true;
        eatTicks = 0;
        wasEating = false;
        resetCameraState();
        releaseMovementKeys(Minecraft.getInstance());
    }

    private static void resumeJob() {
        paused = false;
        tickCooldown = 0;
        placeCooldown = 0;
        resetPath();
    }

    private static String selectionSummary() {
        if (pos1 == null || pos2 == null) {
            return "";
        }
        int sx = Math.abs(pos1.getX() - pos2.getX()) + 1;
        int sy = Math.abs(pos1.getY() - pos2.getY()) + 1;
        int sz = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
        long volume = selectionVolume();
        String warning = volume > MAX_SELECTION_VOLUME ? " §c(too large, max " + MAX_SELECTION_VOLUME + ")" : "";
        return " §7- selection §f" + sx + "x" + sy + "x" + sz + " §7(§f" + volume + " §7blocks)" + warning;
    }

    private static long selectionVolume() {
        if (pos1 == null || pos2 == null) {
            return 0L;
        }
        long sx = Math.abs((long) pos1.getX() - pos2.getX()) + 1;
        long sy = Math.abs((long) pos1.getY() - pos2.getY()) + 1;
        long sz = Math.abs((long) pos1.getZ() - pos2.getZ()) + 1;
        return sx * sy * sz;
    }

    // ------------------------------------------------------------------
    // Main tick
    // ------------------------------------------------------------------

    private static void onClientTick(Minecraft client) {
        tickSelectionOutline(client);
        handleKeyMappings(client);

        if (!running) {
            return;
        }
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            stopJob();
            return;
        }

        tickHud(client);

        if (paused) {
            return;
        }

        if (jumpCooldownTicks > 0) {
            jumpCooldownTicks--;
        }
        if (torchCooldown > 0) {
            torchCooldown--;
        }
        if (placeCooldown > 0) {
            placeCooldown--;
        }
        if (inventorySwapCooldown > 0) {
            inventorySwapCooldown--;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        }
        if (pathRefreshTicks > 0) {
            pathRefreshTicks--;
        }

        if (autoEat && shouldEat(client)) {
            if (tryEatFood(client)) {
                return;
            }
        } else if (wasEating) {
            // Only touch the use key if the mod itself was holding it.
            client.options.keyUse.setDown(false);
            eatTicks = 0;
            wasEating = false;
        }

        if (checkHealthSafety(client)) {
            return;
        }
        if (dodgeFallingBlocks(client)) {
            return;
        }
        if (jobMode == JobMode.MINE && minePhase == MinePhase.WORK && checkInventoryFull(client)) {
            return;
        }

        if (jobMode == JobMode.FILL) {
            onFillTick(client);
        } else {
            onMineTick(client);
        }
    }

    private static void tickHud(Minecraft client) {
        if (!hudEnabled || client.player.tickCount % 20 != 0) {
            return;
        }
        int blocksLeft = getBlocksLeft();
        String phase;
        if (paused) {
            phase = "Paused";
        } else if (minePhase == MinePhase.GOTO_CHEST) {
            phase = "To chest";
        } else if (minePhase == MinePhase.DEPOSIT) {
            phase = "Depositing";
        } else if (minePhase == MinePhase.SWEEP) {
            phase = "Collecting drops";
        } else {
            phase = jobMode == JobMode.FILL ? "Filling" : "Mining";
        }
        client.player.sendOverlayMessage(Component.literal("§b" + jobLabel() + " §7- §f" + phase
                + " §7| §f" + blocksLeft + " §7left §7| §f" + formatProgress(blocksLeft)
                + " §7| ETA §f" + formatEta(blocksLeft)));
    }

    /** Sidesteps when gravel/sand hangs unsupported above the player's head. */
    private static boolean dodgeFallingBlocks(Minecraft client) {
        if (client.player.tickCount % 5 != 0) {
            return false;
        }
        BlockPos feet = client.player.blockPosition();
        for (int dy = 2; dy <= 4; dy++) {
            BlockPos above = feet.above(dy);
            if (client.level.getBlockState(above).getBlock() instanceof FallingBlock
                    && client.level.getBlockState(above.below()).isAir()) {
                stepAside(client, feet);
                return true;
            }
        }
        return false;
    }

    private static void handleKeyMappings(Minecraft client) {
        if (client == null || client.player == null || keySetPos1 == null) {
            return;
        }
        while (keySetPos1.consumeClick()) {
            client.player.sendSystemMessage(Component.literal(setPosFromLook(1)));
        }
        while (keySetPos2.consumeClick()) {
            client.player.sendSystemMessage(Component.literal(setPosFromLook(2)));
        }
        while (keyStartPause.consumeClick()) {
            if (!running) {
                client.player.sendSystemMessage(Component.literal(startAutoMineJob()));
            } else if (paused) {
                resumeJob();
                client.player.sendSystemMessage(Component.literal("§a" + jobLabel() + " resumed."));
            } else {
                pauseJob();
                client.player.sendSystemMessage(Component.literal("§e" + jobLabel() + " paused."));
            }
        }
        while (keyStopJob.consumeClick()) {
            if (running) {
                String label = jobLabel();
                stopJob();
                client.player.sendSystemMessage(Component.literal("§c" + label + " stopped."));
            }
        }
    }

    private static String jobLabel() {
        return jobMode == JobMode.FILL ? "AutoFill" : "AutoMine";
    }

    /** Pauses the job when health drops to the configured level; resumes only by hand. */
    private static boolean checkHealthSafety(Minecraft client) {
        if (pauseHealth <= 0) {
            lowHealthTriggered = false;
            return false;
        }
        float health = client.player.getHealth();
        if (health > pauseHealth + 4.0F) {
            lowHealthTriggered = false;
        }
        if (!lowHealthTriggered && health <= pauseHealth) {
            lowHealthTriggered = true;
            String resumeCommand = jobMode == JobMode.FILL ? "/autofill resume" : "/automine resume";
            pauseJob();
            client.player.sendSystemMessage(Component.literal("§c" + jobLabel() + " paused: health is low ("
                    + (int) Math.ceil(health) + "/20). Heal up, then use §f" + resumeCommand + "§c."));
            return true;
        }
        return false;
    }

    /**
     * When the inventory fills up: head to the deposit chest if one is set,
     * otherwise pause. Only active when the player cares about drops - with
     * Drop Sweep off (and no chest configured), mining continues regardless.
     */
    private static boolean checkInventoryFull(Minecraft client) {
        if (!dropSweep && depositChest == null) {
            wasInventoryFull = false;
            return false;
        }
        if (client.player.tickCount % 20 != 0) {
            return false;
        }
        boolean full = isInventoryFullNow(client);
        if (full && !wasInventoryFull) {
            wasInventoryFull = true;
            if (depositChest != null) {
                minePhase = MinePhase.GOTO_CHEST;
                phaseTicks = 0;
                resetPath();
                client.player.sendSystemMessage(Component.literal(
                        "§bAutoMine: inventory full, heading to the deposit chest."));
                return true;
            }
            pauseJob();
            client.player.sendSystemMessage(Component.literal(
                    "§eAutoMine paused: inventory is full. Make room, then use §f/automine resume§e. Tip: set a deposit chest with §f/automine chest§e."));
            return true;
        }
        if (!full) {
            wasInventoryFull = false;
        }
        return false;
    }

    private static boolean isInventoryFullNow(Minecraft client) {
        int size = Math.min(36, client.player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (client.player.getInventory().getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Mining
    // ------------------------------------------------------------------

    private static void onMineTick(Minecraft client) {
        if (minePhase == MinePhase.GOTO_CHEST) {
            tickGotoChest(client);
            return;
        }
        if (minePhase == MinePhase.DEPOSIT) {
            tickDeposit(client);
            return;
        }
        if (minePhase == MinePhase.SWEEP) {
            tickSweep(client);
            return;
        }

        targetTicks++;

        if (currentTarget == null || !isMineable(client, currentTarget)) {
            selectNewMineTarget(client);
        }

        if (currentTarget == null) {
            int remaining = rebuildRemainingMineQueue(client);
            if (remaining > 0) {
                delayedMineTargets.clear();
                selectNewMineTarget(client);
            }
            if (currentTarget == null) {
                if (dropSweep && findNearestDrop(client) != null) {
                    if (isInventoryFullNow(client)) {
                        // Can't pick anything up while full: deposit first if
                        // possible, otherwise skip collecting entirely.
                        if (depositChest != null) {
                            minePhase = MinePhase.GOTO_CHEST;
                            phaseTicks = 0;
                            resetPath();
                            client.player.sendSystemMessage(Component.literal("§bAutoMine: depositing before collecting drops."));
                            return;
                        }
                        finishMineJob(client);
                        return;
                    }
                    minePhase = MinePhase.SWEEP;
                    phaseTicks = 0;
                    sweepTargetId = -1;
                    sweepNoProgressTicks = 0;
                    sweepBestDistance = Double.MAX_VALUE;
                    sweepSkippedDrops.clear();
                    resetPath();
                    client.player.sendSystemMessage(Component.literal("§bAutoMine: area cleared, collecting drops..."));
                    return;
                }
                finishMineJob(client);
                return;
            }
        }

        if (breakTicksOnTarget == 0) {
            tryPlaceTorch(client);
        }

        boolean inBreakRange = withinRange(client, currentTarget, maxRange);

        if (!inBreakRange && autoMove) {
            if (travelToward(client, currentTarget, true)) {
                deferTargetIfTooSlow(client);
                return;
            }
            inBreakRange = withinRange(client, currentTarget, maxRange);
        }

        if (!inBreakRange) {
            // Out of reach with autoMove off: give the target a moment, then skip it.
            // The throttle keeps this from rescanning the whole queue every tick.
            if (targetTicks > 20) {
                deferCurrentTarget(client);
            }
            return;
        }

        releaseMovementKeys(client);
        stuckTicks = 0;
        resetPath();
        faceSmooth(client, centerOf(currentTarget), TURN_SPEED_WORK);

        // Look at the block before starting to break it, like a player would.
        if (!currentTarget.equals(lastBreakTarget) && needsAim(client, currentTarget, 8)) {
            return;
        }

        if (autoTools && !switchToBestTool(client, currentTarget)) {
            stopJob();
            client.player.sendSystemMessage(Component.literal("§cAutoMine stopped: no safe tool/hand slot available. Free an empty hotbar slot or lower /automine mindurability."));
            return;
        }

        if (tickCooldown > 0) {
            tickCooldown--;
            return;
        }

        mineBlockAt(client, currentTarget);
    }

    private static void selectNewMineTarget(Minecraft client) {
        if (currentTarget != null) {
            lastMinedPos = currentTarget;
        }
        currentTarget = nextMineableBlock(client);
        resetBreakState();
    }

    private static void resetBreakState() {
        lastBreakTarget = null;
        breakTicksOnTarget = 0;
        breakRestartTicks = 0;
        targetTicks = 0;
    }

    private static void deferCurrentTarget(Minecraft client) {
        if (currentTarget == null) {
            return;
        }
        if (jobMode == JobMode.MINE) {
            delayedMineTargets.add(currentTarget);
            if (isInsideSelection(currentTarget) && isMineable(client, currentTarget)) {
                queue.add(currentTarget);
            }
            selectNewMineTarget(client);
        } else {
            queue.add(currentTarget);
            currentTarget = null;
        }
        resetPath();
    }

    private static void deferTargetIfTooSlow(Minecraft client) {
        if (targetTicks > 260 && currentTarget != null) {
            deferCurrentTarget(client);
        }
    }

    private static void finishMineJob(Minecraft client) {
        finishJob(client, lavaSkippedCount > 0
                ? "§aAutoMine finished. Skipped §f" + lavaSkippedCount + " §alava-adjacent block(s) for safety. Selection cleared."
                : "§aAutoMine finished. Full area checked. Selection cleared.");
    }

    // ------------------------------------------------------------------
    // Chest deposit
    // ------------------------------------------------------------------

    private static void tickGotoChest(Minecraft client) {
        phaseTicks++;

        if (depositChest == null || !(client.level.getBlockEntity(depositChest) instanceof Container)) {
            minePhase = MinePhase.WORK;
            pauseJob();
            client.player.sendSystemMessage(Component.literal(
                    "§eAutoMine paused: the deposit chest is gone. Set a new one with §f/automine chest§e, make room, then resume."));
            return;
        }
        if (phaseTicks > 600) {
            minePhase = MinePhase.WORK;
            pauseJob();
            client.player.sendSystemMessage(Component.literal(
                    "§eAutoMine paused: couldn't reach the deposit chest. Make room manually, then resume."));
            return;
        }

        if (!withinRange(client, depositChest, Math.max(1.5D, maxRange - 0.5D))) {
            if (!autoMove) {
                minePhase = MinePhase.WORK;
                pauseJob();
                client.player.sendSystemMessage(Component.literal(
                        "§eAutoMine paused: inventory full and Auto Move is off. Empty your inventory, then resume."));
                return;
            }
            travelToward(client, depositChest, false);
            return;
        }

        releaseMovementKeys(client);
        resetPath();
        faceSmooth(client, centerOf(depositChest), TURN_SPEED_WORK);

        if (client.player.containerMenu.containerId == 0) {
            if (phaseTicks % 10 == 1) {
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(depositChest), getHitDirection(client, depositChest), depositChest, false);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        minePhase = MinePhase.DEPOSIT;
        phaseTicks = 0;
        depositClickCooldown = 4;
    }

    private static void tickDeposit(Minecraft client) {
        phaseTicks++;

        if (client.player.containerMenu.containerId == 0) {
            finishDeposit(client);
            return;
        }
        if (phaseTicks > 400) {
            client.player.closeContainer();
            finishDeposit(client);
            return;
        }
        if (depositClickCooldown > 0) {
            depositClickCooldown--;
            return;
        }

        var menu = client.player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != client.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || shouldKeepItem(stack)) {
                continue;
            }
            client.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, client.player);
            depositClickCooldown = 2;
            return;
        }

        client.player.closeContainer();
        finishDeposit(client);
    }

    private static void finishDeposit(Minecraft client) {
        minePhase = MinePhase.WORK;
        phaseTicks = 0;
        boolean stillFull = isInventoryFullNow(client);
        wasInventoryFull = stillFull;
        if (stillFull) {
            pauseJob();
            client.player.sendSystemMessage(Component.literal(
                    "§eAutoMine paused: the deposit chest is full (or items wouldn't fit). Make room, then use §f/automine resume§e."));
        } else {
            client.player.sendSystemMessage(Component.literal("§aItems deposited. Back to mining."));
        }
    }

    /** Items the bot never deposits: tools/armor, food, and torches. */
    private static boolean shouldKeepItem(ItemStack stack) {
        return stack.isDamageableItem() || isFoodStack(stack) || stack.is(Items.TORCH);
    }

    // ------------------------------------------------------------------
    // Drop sweep
    // ------------------------------------------------------------------

    private static void tickSweep(Minecraft client) {
        phaseTicks++;

        if (phaseTicks > 600 || !autoMove || !dropSweep) {
            finishMineJob(client);
            return;
        }
        // Filled up mid-sweep: deposit and come back, or stop if we can't.
        if (isInventoryFullNow(client)) {
            if (depositChest != null) {
                minePhase = MinePhase.GOTO_CHEST;
                phaseTicks = 0;
                resetPath();
                return;
            }
            finishMineJob(client);
            return;
        }
        ItemEntity drop = findNearestDrop(client);
        if (drop == null) {
            finishMineJob(client);
            return;
        }

        // Track approach progress per drop; give up on drops we can't get closer to
        // (stuck in a hole, on a ledge, behind something) instead of grinding on them.
        if (drop.getId() != sweepTargetId) {
            sweepTargetId = drop.getId();
            sweepNoProgressTicks = 0;
            sweepBestDistance = Double.MAX_VALUE;
        }
        double distance = drop.position().distanceTo(client.player.position());
        if (distance < sweepBestDistance - 0.05D) {
            sweepBestDistance = distance;
            sweepNoProgressTicks = 0;
        } else {
            sweepNoProgressTicks++;
        }
        if (sweepNoProgressTicks > 80) {
            sweepSkippedDrops.add(drop.getId());
            sweepTargetId = -1;
            resetPath();
            return;
        }

        BlockPos dropPos = drop.blockPosition();
        if (!travelToward(client, dropPos, false)) {
            // Within break range already: walk the last few meters directly.
            legacyMoveTowardTarget(client, dropPos, false);
        }
    }

    private static ItemEntity findNearestDrop(Minecraft client) {
        if (pos1 == null || pos2 == null) {
            return null;
        }
        AABB bounds = new AABB(
                Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()) + 1, Math.max(pos1.getY(), pos2.getY()) + 1, Math.max(pos1.getZ(), pos2.getZ()) + 1
        ).inflate(4.0D);

        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item) || item.getItem().isEmpty()) {
                continue;
            }
            if (sweepSkippedDrops.contains(item.getId()) || !bounds.contains(item.position())) {
                continue;
            }
            double distance = item.position().distanceToSqr(client.player.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Torch placement
    // ------------------------------------------------------------------

    private static void tryPlaceTorch(Minecraft client) {
        if (!autoTorch || torchCooldown > 0 || !client.player.onGround()) {
            return;
        }
        BlockPos feet = client.player.blockPosition();
        if (client.level.getBrightness(LightLayer.BLOCK, feet) >= 6) {
            return;
        }
        if (!client.level.getBlockState(feet).isAir() || !DeltaPathfinder.solid(client, feet.below())) {
            return;
        }

        int slot = findTorchSlot(client);
        if (slot == -1) {
            torchCooldown = 200; // no torches; don't rescan every block
            return;
        }
        if (!makeSlotUsable(client, slot)) {
            torchCooldown = 20;
            return;
        }

        BlockPos ground = feet.below();
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(ground).add(0.0D, 0.5D, 0.0D), Direction.UP, ground, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        torchCooldown = 40;
    }

    private static int findTorchSlot(Minecraft client) {
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            if (client.player.getInventory().getItem(slot).is(Items.TORCH)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Picks the next block to mine. Prefers (in order): blocks already in reach,
     * higher layers (top-down mining avoids falling-block surprises), blocks near
     * the previous target (locality keeps travel short), exposed faces, and it
     * defers the block directly under the player's feet as long as possible.
     */
    private static BlockPos nextMineableBlock(Minecraft client) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int topY = Math.max(pos1 == null ? 0 : pos1.getY(), pos2 == null ? 0 : pos2.getY());
        BlockPos support = client.player == null ? null : client.player.blockPosition().below();

        for (BlockPos pos : queue) {
            if (!isMineable(client, pos)) {
                continue;
            }

            double score = Math.sqrt(distanceSq(client, pos));
            score += (topY - pos.getY()) * 2.5D;

            if (withinRange(client, pos, maxRange)) {
                score -= 30.0D;
            }
            if (isExposed(client, pos)) {
                score -= 4.0D;
            }
            if (lastMinedPos != null && manhattan(pos, lastMinedPos) <= 2) {
                score -= 8.0D;
            }
            // Prefer blocks near the current view direction so the camera sweeps
            // across the wall instead of ping-ponging between far-apart targets.
            score += angularErrorTo(client, centerOf(pos)) * 0.12D;
            if (pos.equals(support)) {
                score += 300.0D;
            }
            if (delayedMineTargets.contains(pos)) {
                score += 400.0D;
            }

            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }

        if (best != null) {
            queue.remove(best);
            delayedMineTargets.remove(best);
        }
        return best;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static void mineBlockAt(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.gameMode == null || pos == null) {
            return;
        }

        Direction hitSide = getHitDirection(client, pos);

        if (!pos.equals(lastBreakTarget) || breakRestartTicks <= 0) {
            client.gameMode.startDestroyBlock(pos, hitSide);
            lastBreakTarget = pos.immutable();
            breakRestartTicks = 14;
        }

        client.gameMode.continueDestroyBlock(pos, hitSide);
        if (breakTicksOnTarget % 3 == 0) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        breakTicksOnTarget++;
        breakRestartTicks--;

        BlockState state = client.level.getBlockState(pos);
        float speed = client.player.getMainHandItem().getDestroySpeed(state);
        int timeout = speed > 8.0F ? 80 : speed > 4.0F ? 120 : 180;

        if (breakTicksOnTarget > timeout) {
            lastBreakTarget = null;
            breakTicksOnTarget = 0;
            breakRestartTicks = 0;
            tickCooldown = Math.max(1, delayTicks);
        }
    }

    // ------------------------------------------------------------------
    // Travel (A* pathfinding with legacy fallback)
    // ------------------------------------------------------------------

    /**
     * Moves toward the target using A* pathfinding, mining path blockers when
     * allowed. Returns true when the tick was spent traveling (or clearing) and
     * false when the target is already in break range.
     */
    private static boolean travelToward(Minecraft client, BlockPos target, boolean allowMining) {
        if (withinRange(client, target, maxRange)) {
            resetPath();
            return false;
        }

        boolean stuck = updateStuckState(client);
        boolean needRepath = path == null
                || pathTargetKey == null
                || !pathTargetKey.equals(target)
                || pathIndex >= path.size()
                || pathRefreshTicks <= 0;

        if (stuck) {
            needRepath = true;
            stuckTicks = 0;
        }

        if (needRepath && repathCooldown <= 0) {
            computePath(client, target, allowMining);
        }

        if (path == null) {
            legacyMoveTowardTarget(client, target, stuck);
            return true;
        }

        advanceWaypoints(client);

        if (pathIndex >= path.size()) {
            // Path exhausted without reaching break range: force a fresh search.
            resetPath();
            legacyMoveTowardTarget(client, target, stuck);
            return true;
        }

        DeltaPathfinder.Step step = path.get(pathIndex);
        BlockPos clearBlock = nextClearBlock(client, step);
        if (clearBlock != null) {
            if (withinRange(client, clearBlock, maxRange)) {
                releaseMovementKeys(client);
                faceSmooth(client, centerOf(clearBlock), TURN_SPEED_WORK);
                if (!clearBlock.equals(lastBreakTarget) && needsAim(client, clearBlock, 6)) {
                    return true;
                }
                if (autoTools) {
                    switchToBestTool(client, clearBlock);
                }
                if (tickCooldown > 0) {
                    tickCooldown--;
                    return true;
                }
                mineBlockAt(client, clearBlock);
                return true;
            }
            walkToward(client, step);
            return true;
        }

        walkToward(client, step);
        return true;
    }

    private static void computePath(Minecraft client, BlockPos target, boolean allowMining) {
        // Walk until the target is within moveStopRange (never past what we can break from).
        double reach = Mth.clamp(moveStopRange, 1.5D, Math.max(1.5D, maxRange - 0.5D));
        path = DeltaPathfinder.find(
                client,
                client.player.blockPosition(),
                centerOf(target),
                reach,
                allowMining,
                DeltaUtilityModClient::isInsideSelection,
                pos -> isBreakable(client, pos),
                3500);
        pathIndex = 0;
        pathTargetKey = target.immutable();
        pathRefreshTicks = 100;
        repathCooldown = path == null ? 30 : 8;
    }

    private static void resetPath() {
        path = null;
        pathIndex = 0;
        pathTargetKey = null;
        nearWaypointTicks = 0;
    }

    private static void advanceWaypoints(Minecraft client) {
        while (pathIndex < path.size()) {
            DeltaPathfinder.Step step = path.get(pathIndex);
            Vec3 playerPos = client.player.position();
            double dx = step.feet.getX() + 0.5D - playerPos.x;
            double dz = step.feet.getZ() + 0.5D - playerPos.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double dy = playerPos.y - step.feet.getY();
            if (horizontal < 0.6D && dy > -0.9D && dy < 1.25D) {
                pathIndex++;
                nearWaypointTicks = 0;
            } else {
                break;
            }
        }

        // Orbit breaker: hovering near a waypoint without ever "arriving" means
        // the steering is circling it - throw the path away and plan fresh.
        if (pathIndex < path.size()) {
            DeltaPathfinder.Step step = path.get(pathIndex);
            Vec3 playerPos = client.player.position();
            double dx = step.feet.getX() + 0.5D - playerPos.x;
            double dz = step.feet.getZ() + 0.5D - playerPos.z;
            if (dx * dx + dz * dz < 1.0D) {
                nearWaypointTicks++;
                if (nearWaypointTicks > 30) {
                    resetPath();
                }
            } else {
                nearWaypointTicks = 0;
            }
        }
    }

    /** First path-blocker of the current step that still needs breaking, or null. */
    private static BlockPos nextClearBlock(Minecraft client, DeltaPathfinder.Step step) {
        for (BlockPos pos : step.toClear) {
            if (isBreakable(client, pos)) {
                return pos;
            }
        }
        return null;
    }

    private static void walkToward(Minecraft client, DeltaPathfinder.Step step) {
        Vec3 waypoint = new Vec3(step.feet.getX() + 0.5D, step.feet.getY() + 0.9D, step.feet.getZ() + 0.5D);
        Vec3 playerPos = client.player.position();

        double dx = waypoint.x - playerPos.x;
        double dz = waypoint.z - playerPos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // Steer (yaw) toward the immediate waypoint, blending toward the next
        // one as we get close so corners round off - but never blend into a
        // descending step, and never enough to orbit the waypoint.
        // Keep the eyes (pitch) on a point a few waypoints ahead so the camera
        // looks down the path instead of nodding at each waypoint underfoot.
        DeltaPathfinder.Step next = path.get(Math.min(path.size() - 1, pathIndex + 1));
        double blend = next.feet.getY() < step.feet.getY() ? 0.0D
                : Mth.clamp(1.0D - horizontal / 1.9D, 0.0D, 0.5D);
        Vec3 steerPoint = new Vec3(
                Mth.lerp(blend, waypoint.x, next.feet.getX() + 0.5D),
                waypoint.y,
                Mth.lerp(blend, waypoint.z, next.feet.getZ() + 0.5D));
        DeltaPathfinder.Step ahead = path.get(Math.min(path.size() - 1, pathIndex + 3));
        Vec3 lookAhead = new Vec3(ahead.feet.getX() + 0.5D, ahead.feet.getY() + 1.55D, ahead.feet.getZ() + 0.5D);
        float steerYaw = yawToward(client, steerPoint);
        faceAngles(client, steerYaw, pitchToward(client, lookAhead), TURN_SPEED_WALK);

        // Anti-orbit: don't move forward while badly misaligned - turn in place
        // first. (Movement follows the camera, so walking mid-turn arcs sideways.)
        float alignError = Math.abs(Mth.wrapDegrees(steerYaw - client.player.getYRot()));
        boolean wantForward = horizontal > 0.08D && alignError < 60.0F;

        // Cliff guard: never walk toward a drop deeper than a safe fall.
        if (wantForward && isDangerousDropAhead(client)) {
            releaseMovementKeys(client);
            resetPath();
            return;
        }

        client.options.keyUp.setDown(wantForward);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);

        boolean climbing = step.feet.getY() > client.player.blockPosition().getY();
        int remainingSteps = path.size() - pathIndex;
        client.options.keySprint.setDown(wantForward && !climbing && remainingSteps > 5 && horizontal > 1.5D);

        // Jump when climbing to a higher waypoint, when a one-block lip sits in the
        // walking line (e.g. a freshly placed fill block), or when wedged in place.
        boolean obstacleAhead = false;
        if (horizontal > 0.05D) {
            double nx = dx / horizontal;
            double nz = dz / horizontal;
            obstacleAhead = needsJumpAt(client, nx, nz, 0.65D) || needsJumpAt(client, nx, nz, 1.05D);
        }
        boolean wedged = stuckTicks > 8 && horizontal > 0.3D;

        if (((climbing && horizontal < 2.2D) || obstacleAhead || wedged)
                && client.player.onGround() && jumpCooldownTicks <= 0) {
            jumpPulseTicks = 5;
            jumpCooldownTicks = 8;
        }

        if (jumpPulseTicks > 0) {
            client.options.keyJump.setDown(true);
            jumpPulseTicks--;
        } else {
            client.options.keyJump.setDown(false);
        }
    }

    /**
     * True when walking forward (in the direction the player faces, which is
     * where movement goes) would step over a drop deeper than 3 blocks or into
     * lava. Checks two probe points just ahead of the feet.
     */
    private static boolean isDangerousDropAhead(Minecraft client) {
        if (client == null || client.player == null || !client.player.onGround()) {
            return false;
        }
        double yawRadians = Math.toRadians(client.player.getYRot());
        double nx = -Math.sin(yawRadians);
        double nz = Math.cos(yawRadians);

        BlockPos ahead = BlockPos.containing(
                client.player.getX() + nx * 0.75D,
                client.player.getY(),
                client.player.getZ() + nz * 0.75D);
        if (ahead.equals(client.player.blockPosition()) || !DeltaPathfinder.passable(client, ahead)) {
            return false; // same column, or a wall - not a hole
        }

        for (int down = 1; down <= 5; down++) {
            BlockPos below = ahead.below(down);
            if (DeltaPathfinder.solid(client, below)) {
                // Feet would land one above the solid block: fall height is down-1.
                BlockPos landingFeet = ahead.below(down - 1);
                boolean lava = client.level.getBlockState(landingFeet).getFluidState().is(FluidTags.LAVA);
                return down > 4 || lava;
            }
        }
        return true; // nothing solid within 5 blocks below: treat as a cliff
    }

    private static boolean updateStuckState(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        Vec3 now = client.player.position();
        double moved = now.distanceToSqr(lastPathPos);
        if (moved < 0.0009D) {
            stuckTicks++;
        } else {
            stuckTicks = Math.max(0, stuckTicks - 2);
        }
        lastPathPos = now;
        return stuckTicks > 18;
    }

    /** Direct-line movement used only when A* fails (unloaded chunks, budget exhausted). */
    private static void legacyMoveTowardTarget(Minecraft client, BlockPos target, boolean stuck) {
        if (client == null || client.player == null) {
            return;
        }
        Vec3 targetCenter = centerOf(target);
        Vec3 playerPos = client.player.position();
        faceSmooth(client, targetCenter, TURN_SPEED_WALK);

        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        boolean wantForward = horizontal > 0.40D && !isDangerousDropAhead(client);
        client.options.keyUp.setDown(wantForward);
        client.options.keyShift.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keySprint.setDown(wantForward && horizontal > 4.0D && !stuck);

        if (stuck) {
            boolean goLeft = ((client.player.tickCount / 20) % 2) == 0;
            client.options.keyLeft.setDown(goLeft);
            client.options.keyRight.setDown(!goLeft);
        } else {
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
        }

        if (shouldAutoJump(client, target, dx, dz, horizontal, stuck) && jumpCooldownTicks <= 0) {
            jumpPulseTicks = stuck ? 8 : 5;
            jumpCooldownTicks = stuck ? 14 : 9;
        }

        if (jumpPulseTicks > 0) {
            client.options.keyJump.setDown(true);
            jumpPulseTicks--;
        } else {
            client.options.keyJump.setDown(false);
        }
    }

    private static boolean shouldAutoJump(Minecraft client, BlockPos target, double dx, double dz, double horizontal, boolean stuck) {
        if (client == null || client.player == null || client.level == null) {
            return false;
        }
        if (!client.player.onGround() || horizontal < 0.65D) {
            return false;
        }

        int playerY = client.player.blockPosition().getY();
        if (target.getY() >= playerY + 1 && horizontal < 4.0D) {
            return true;
        }
        if (stuck && horizontal > 0.6D) {
            return true;
        }

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.0001D) {
            return false;
        }
        double nx = dx / len;
        double nz = dz / len;
        return needsJumpAt(client, nx, nz, 0.65D) || needsJumpAt(client, nx, nz, 1.05D);
    }

    private static boolean needsJumpAt(Minecraft client, double nx, double nz, double distance) {
        BlockPos feetAhead = BlockPos.containing(
                client.player.getX() + nx * distance,
                client.player.getY(),
                client.player.getZ() + nz * distance
        );
        BlockPos headAhead = feetAhead.above();
        BlockPos groundAhead = feetAhead.below();
        BlockPos landingGround = BlockPos.containing(
                client.player.getX() + nx * (distance + 1.05D),
                client.player.getY() - 1.0D,
                client.player.getZ() + nz * (distance + 1.05D)
        );

        boolean stepUpObstacle = DeltaPathfinder.solid(client, feetAhead) && DeltaPathfinder.passable(client, headAhead);
        boolean safeSmallGap = DeltaPathfinder.passable(client, groundAhead) && DeltaPathfinder.solid(client, landingGround);
        return stepUpObstacle || safeSmallGap;
    }

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    private static boolean switchToBestTool(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.level == null) {
            return true;
        }

        BlockState state = client.level.getBlockState(pos);
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        int bestSlot = -1;
        float bestScore = 1.0F;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || isSafeDurability(stack)) {
                float score = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
                if (score > bestScore || bestSlot == -1) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
        }

        // The hotbar has nothing effective for this block: check the main inventory
        // and swap in a strictly better safe tool if one exists.
        int bestInvSlot = -1;
        float bestInvScore = Math.max(bestScore, 1.0F);
        int containerSize = Math.min(36, client.player.getInventory().getContainerSize());
        for (int slot = 9; slot < containerSize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !isSafeDurability(stack)) {
                continue;
            }
            float score = stack.getDestroySpeed(state);
            if (score > bestInvScore + 0.01F) {
                bestInvScore = score;
                bestInvSlot = slot;
            }
        }
        if (bestInvSlot != -1 && makeSlotUsable(client, bestInvSlot)) {
            return true;
        }

        if (bestSlot == -1 || bestScore <= 1.0F) {
            int fallback = findSafeHandSlot(client);
            if (fallback != -1) {
                selectHotbarSlot(client, fallback);
                return true;
            }
            ItemStack selected = client.player.getInventory().getItem(selectedSlot);
            return isSafeDurability(selected);
        }

        selectHotbarSlot(client, bestSlot);
        return true;
    }

    private static void selectHotbarSlot(Minecraft client, int slot) {
        if (client == null || client.player == null || slot < 0 || slot > 8) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != slot) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private static int findSafeHandSlot(Minecraft client) {
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (!client.player.getInventory().getItem(slot).isDamageableItem()) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isSafeDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return true;
        }
        int durabilityLeft = stack.getMaxDamage() - stack.getDamageValue();
        return durabilityLeft > minToolDurability;
    }

    // ------------------------------------------------------------------
    // Eating
    // ------------------------------------------------------------------

    private static boolean shouldEat(Minecraft client) {
        if (client == null || client.player == null || client.options == null) {
            return false;
        }
        return client.player.getFoodData().getFoodLevel() <= hungerThreshold;
    }

    private static boolean tryEatFood(Minecraft client) {
        int slot = findFoodSlot(client);
        if (slot == -1 || !makeSlotUsable(client, slot)) {
            if (wasEating) {
                client.options.keyUse.setDown(false);
                wasEating = false;
            }
            eatTicks = 0;
            return false;
        }

        releaseMovementKeysExceptUse(client);
        client.options.keyUse.setDown(true);
        wasEating = true;
        eatTicks++;

        if (eatTicks == 1 || eatTicks % 8 == 0) {
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private static int findFoodSlot(Minecraft client) {
        if (client == null || client.player == null) {
            return -1;
        }
        // Prefer hotbar food (no inventory swap needed).
        for (int slot = 0; slot < 9; slot++) {
            if (isFoodStack(client.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        for (int slot = 9; slot < client.player.getInventory().getContainerSize(); slot++) {
            if (isFoodStack(client.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isFoodStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    // ------------------------------------------------------------------
    // Filling
    // ------------------------------------------------------------------

    private static int startFill(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String blockName, boolean replace) {
        if (pos1 == null || pos2 == null) {
            source.sendFeedback(Component.literal("§cSet /pos1 and /pos2 first."));
            return 0;
        }
        long volume = selectionVolume();
        if (volume > MAX_SELECTION_VOLUME) {
            source.sendFeedback(Component.literal("§cSelection is too large: §f" + volume
                    + " §cblocks (max §f" + MAX_SELECTION_VOLUME + "§c). Pick a smaller area."));
            return 0;
        }

        Block block = resolveBlock(blockName);
        if (block == null) {
            source.sendFeedback(Component.literal("§cUnknown block: §f" + blockName + "§7. Example: /autofill start dirt or /autofill start minecraft:dirt"));
            return 0;
        }

        fillBlock = block;
        fillBlockName = blockName.contains(":") ? blockName : "minecraft:" + blockName;
        fillReplace = replace;
        buildFillQueue();
        startTaskTracking(queue.size());
        jobMode = JobMode.FILL;
        running = true;
        paused = false;
        currentTarget = null;
        tickCooldown = 0;
        placeCooldown = 0;
        lowHealthTriggered = false;
        wasInventoryFull = false;
        resetPath();
        source.sendFeedback(Component.literal(replace
                ? "§aAutoFill (replace mode) started with §f" + fillBlockName + "§a. §eExisting blocks in the area will be mined and replaced. §aBlocks queued: §f" + queue.size()
                : "§aAutoFill started with §f" + fillBlockName + "§a. Blocks queued: §f" + queue.size()));
        return 1;
    }

    private static Block resolveBlock(String name) {
        String idText = (name.contains(":") ? name : "minecraft:" + name).toLowerCase();
        for (Block block : BuiltInRegistries.BLOCK) {
            Object key = BuiltInRegistries.BLOCK.getKey(block);
            if (key != null && key.toString().equals(idText)) {
                return block == Blocks.AIR ? null : block;
            }
        }
        return null;
    }

    private static void onFillTick(Minecraft client) {
        if (fillBlock == null || fillBlock == Blocks.AIR) {
            stopJob();
            client.player.sendSystemMessage(Component.literal("§cAutoFill stopped: no block selected."));
            return;
        }

        if (currentTarget == null || !needsFillWork(client, currentTarget)) {
            currentTarget = nextFillBlock(client);
            placeCooldown = 0;
        }

        if (currentTarget == null) {
            int remaining = rebuildRemainingFillQueue(client);
            if (remaining > 0) {
                currentTarget = nextFillBlock(client);
                placeCooldown = 0;
            }
            if (currentTarget == null) {
                finishJob(client, "§aAutoFill complete. The selected area was checked and filled. Selection cleared.");
                return;
            }
        }

        // Never place a block inside the player: step out of the way first.
        if (client.player.getBoundingBox().intersects(new AABB(currentTarget))) {
            stepAside(client, currentTarget);
            return;
        }

        boolean inPlaceRange = withinRange(client, currentTarget, maxRange);

        if (!inPlaceRange && autoMove) {
            if (travelToward(client, currentTarget, false)) {
                deferTargetIfTooSlow(client);
                return;
            }
            inPlaceRange = withinRange(client, currentTarget, maxRange);
        }

        if (!inPlaceRange) {
            // Unreachable for now: push it back and try another spot.
            queue.add(currentTarget);
            currentTarget = null;
            return;
        }

        releaseMovementKeys(client);
        stuckTicks = 0;
        resetPath();
        faceSmooth(client, centerOf(currentTarget), TURN_SPEED_WORK);

        // An obstruction sits where the block goes: break it first, then place
        // on a later tick. In replace mode this can be any solid block, so use
        // the proper tool for it.
        if (isFillObstruction(client, currentTarget)) {
            if (!currentTarget.equals(lastBreakTarget) && needsAim(client, currentTarget, 6)) {
                return;
            }
            if (fillReplace && autoTools && !switchToBestTool(client, currentTarget)) {
                stopJob();
                client.player.sendSystemMessage(Component.literal("§cAutoFill stopped: no safe tool for clearing. Lower /automine mindurability or add tools."));
                return;
            }
            if (tickCooldown > 0) {
                tickCooldown--;
                return;
            }
            mineBlockAt(client, currentTarget);
            return;
        }

        int slot = findBlockSlot(client, fillBlock);
        if (slot == -1) {
            stopJob();
            client.player.sendSystemMessage(Component.literal("§cAutoFill stopped: no §f" + fillBlockName + " §cin your hotbar/inventory."));
            return;
        }
        if (!makeSlotUsable(client, slot)) {
            if (inventorySwapCooldown > 0 || client.player.containerMenu.containerId != 0) {
                return; // A swap is settling or another container is open; retry next tick.
            }
            stopJob();
            client.player.sendSystemMessage(Component.literal("§cAutoFill stopped: put §f" + fillBlockName + " §cin your hotbar. I found it in inventory but could not move it."));
            return;
        }

        if (placeCooldown > 0) {
            return;
        }
        if (needsAim(client, currentTarget, 6)) {
            return;
        }

        BlockHitResult hit = findPlacementHit(client, currentTarget);
        if (hit == null) {
            // Nothing to place against yet: retry later.
            queue.add(currentTarget);
            currentTarget = null;
            placeCooldown = Math.max(2, delayTicks);
            return;
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        placeCooldown = Math.max(2, delayTicks);
    }

    private static void stepAside(Minecraft client, BlockPos blockingPos) {
        Vec3 away = client.player.position().subtract(centerOf(blockingPos));
        Vec3 flat = new Vec3(away.x, 0.0D, away.z);
        if (flat.lengthSqr() < 0.001D) {
            flat = new Vec3(1.0D, 0.0D, 0.0D);
        }
        Vec3 direction = flat.normalize();
        Vec3 lookPoint = client.player.position().add(direction.scale(2.0D)).add(0.0D, 1.0D, 0.0D);
        faceSmooth(client, lookPoint, TURN_SPEED_WALK);
        client.options.keyUp.setDown(!isDangerousDropAhead(client));
        client.options.keySprint.setDown(false);

        // Hop out if the escape direction has a one-block lip in the way.
        if (needsJumpAt(client, direction.x, direction.z, 0.65D)
                && client.player.onGround() && jumpCooldownTicks <= 0) {
            jumpPulseTicks = 5;
            jumpCooldownTicks = 8;
        }
        if (jumpPulseTicks > 0) {
            client.options.keyJump.setDown(true);
            jumpPulseTicks--;
        } else {
            client.options.keyJump.setDown(false);
        }
    }

    /** Position we can fill by placing directly: air, fluids (water/lava), and replaceables like tall grass or snow. */
    private static boolean needsFill(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.is(fillBlock)) {
            return false;
        }
        if (state.isAir() || state.canBeReplaced()) {
            return true;
        }
        return !state.getFluidState().isEmpty() && state.getCollisionShape(client.level, pos).isEmpty();
    }

    /**
     * A block occupying a fill spot that must be mined before placing. Normally
     * only soft, instantly-breakable blocks (flowers, saplings, torches...);
     * in replace mode, any breakable block that isn't the fill block.
     */
    private static boolean isFillObstruction(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.is(fillBlock) || state.canBeReplaced()) {
            return false;
        }
        if (fillReplace) {
            return isBreakable(client, pos);
        }
        return state.getDestroySpeed(client.level, pos) == 0.0F;
    }

    private static boolean needsFillWork(Minecraft client, BlockPos pos) {
        return needsFill(client, pos) || isFillObstruction(client, pos);
    }

    /**
     * Picks the next position to fill: lowest layer first, and within a layer the
     * farthest position first so the bot never seals itself into a corner.
     */
    private static BlockPos nextFillBlock(Minecraft client) {
        BlockPos best = null;
        int bestY = Integer.MAX_VALUE;
        double bestDist = -1.0D;

        for (BlockPos pos : queue) {
            if (!needsFillWork(client, pos)) {
                continue;
            }
            double dist = distanceSq(client, pos);
            if (pos.getY() < bestY || (pos.getY() == bestY && dist > bestDist)) {
                bestY = pos.getY();
                bestDist = dist;
                best = pos;
            }
        }

        if (best != null) {
            queue.remove(best);
        }
        return best;
    }

    private static int findBlockSlot(Minecraft client, Block block) {
        if (client == null || client.player == null || block == null) {
            return -1;
        }
        for (int slot = 0; slot < Math.min(9, client.player.getInventory().getContainerSize()); slot++) {
            if (isBlockStack(client.player.getInventory().getItem(slot), block)) {
                return slot;
            }
        }
        // Nothing in the hotbar: pull the largest matching stack from the inventory
        // so long fill jobs need as few swaps as possible.
        int bestSlot = -1;
        int bestCount = 0;
        for (int slot = 9; slot < Math.min(36, client.player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (isBlockStack(stack, block) && stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static boolean isBlockStack(ItemStack stack, Block block) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == block;
    }

    private static boolean makeSlotUsable(Minecraft client, int slot) {
        if (client == null || client.player == null || slot < 0) {
            return false;
        }

        if (slot < 9) {
            selectHotbarSlot(client, slot);
            return true;
        }

        if (inventorySwapCooldown > 0) {
            return false;
        }
        // Only touch the player's own inventory menu, never an open container.
        if (client.player.containerMenu.containerId != 0) {
            return false;
        }

        int hotbarSlot = chooseReplacementHotbarSlot(client);
        client.gameMode.handleContainerInput(
                client.player.containerMenu.containerId, slot, hotbarSlot, ContainerInput.SWAP, client.player);
        selectHotbarSlot(client, hotbarSlot);
        inventorySwapCooldown = 10;
        return true;
    }

    private static int chooseReplacementHotbarSlot(Minecraft client) {
        int selected = client.player.getInventory().getSelectedSlot();
        ItemStack selectedStack = client.player.getInventory().getItem(selected);
        if (selectedStack.isEmpty() || !selectedStack.isDamageableItem()) {
            return selected;
        }

        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (!client.player.getInventory().getItem(slot).isDamageableItem()) {
                return slot;
            }
        }
        return selected;
    }

    private static BlockHitResult findPlacementHit(Minecraft client, BlockPos target) {
        if (client == null || client.level == null || target == null) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.relative(direction);
            BlockState neighborState = client.level.getBlockState(neighbor);
            if (!neighborState.isAir() && !neighborState.getCollisionShape(client.level, neighbor).isEmpty()) {
                Direction face = direction.getOpposite();
                Vec3 hitPos = Vec3.atCenterOf(neighbor).add(
                        face.getStepX() * 0.5D,
                        face.getStepY() * 0.5D,
                        face.getStepZ() * 0.5D
                );
                return new BlockHitResult(hitPos, face, neighbor, false);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Queues + selection
    // ------------------------------------------------------------------

    private static boolean isInsideSelection(BlockPos pos) {
        if (pos1 == null || pos2 == null || pos == null) {
            return false;
        }
        return pos.getX() >= Math.min(pos1.getX(), pos2.getX()) && pos.getX() <= Math.max(pos1.getX(), pos2.getX())
                && pos.getY() >= Math.min(pos1.getY(), pos2.getY()) && pos.getY() <= Math.max(pos1.getY(), pos2.getY())
                && pos.getZ() >= Math.min(pos1.getZ(), pos2.getZ()) && pos.getZ() <= Math.max(pos1.getZ(), pos2.getZ());
    }

    private static boolean isExposed(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            BlockState state = client.level.getBlockState(neighbor);
            if (state.isAir() || state.getCollisionShape(client.level, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLavaAdjacent(Minecraft client, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (client.level.getBlockState(neighbor).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /** Base check: the block physically can and may be broken (ignores the target filter). */
    private static boolean isBreakable(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // Pure fluids (water/lava sources and flows) can't be mined; skip them
        // instead of grinding the break timeout on each one.
        if (!state.getFluidState().isEmpty() && state.getCollisionShape(client.level, pos).isEmpty()) {
            return false;
        }
        if (state.getDestroySpeed(client.level, pos) < 0.0F) {
            return false;
        }
        return !lavaSafety || !isLavaAdjacent(client, pos);
    }

    /** Job-target check: breakable AND allowed by the block filter. */
    private static boolean isMineable(Minecraft client, BlockPos pos) {
        return isBreakable(client, pos) && matchesFilter(client, pos);
    }

    private static boolean matchesFilter(Minecraft client, BlockPos pos) {
        if (filterBlocks.isEmpty()) {
            return true;
        }
        boolean inSet = filterBlocks.contains(client.level.getBlockState(pos).getBlock());
        return filterInclude == inSet;
    }

    /** Like isMineable but ignoring lava safety; used to count skipped blocks. */
    private static boolean isMineableIgnoringLava(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty() && state.getCollisionShape(client.level, pos).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(client.level, pos) >= 0.0F;
    }

    private static void buildMineQueue() {
        queue.clear();
        forEachSelectionPos(pos -> queue.add(pos), true);
    }

    private static int rebuildRemainingMineQueue(Minecraft client) {
        queue.clear();
        lavaSkippedCount = 0;
        if (pos1 == null || pos2 == null || client == null || client.level == null) {
            return 0;
        }
        forEachSelectionPos(pos -> {
            if (isMineable(client, pos)) {
                queue.add(pos);
            } else if (lavaSafety && matchesFilter(client, pos)
                    && isMineableIgnoringLava(client, pos) && isLavaAdjacent(client, pos)) {
                lavaSkippedCount++;
            }
        }, true);
        return queue.size();
    }

    private static void buildFillQueue() {
        queue.clear();
        forEachSelectionPos(pos -> queue.add(pos), false);
    }

    private static int rebuildRemainingFillQueue(Minecraft client) {
        queue.clear();
        if (pos1 == null || pos2 == null || client == null || client.level == null
                || fillBlock == null || fillBlock == Blocks.AIR) {
            return 0;
        }
        forEachSelectionPos(pos -> {
            if (needsFillWork(client, pos)) {
                queue.add(pos);
            }
        }, false);
        return queue.size();
    }

    private interface PosConsumer {
        void accept(BlockPos pos);
    }

    private static void forEachSelectionPos(PosConsumer consumer, boolean topDown) {
        if (pos1 == null || pos2 == null) {
            return;
        }
        if (selectionVolume() > MAX_SELECTION_VOLUME) {
            return;
        }

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        if (topDown) {
            for (int y = maxY; y >= minY; y--) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        consumer.accept(new BlockPos(x, y, z));
                    }
                }
            }
        } else {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        consumer.accept(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    private static boolean withinRange(Minecraft client, BlockPos pos, double range) {
        return distanceSq(client, pos) <= range * range;
    }

    private static double distanceSq(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null) {
            return Double.MAX_VALUE;
        }
        return client.player.getEyePosition().distanceToSqr(centerOf(pos));
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** Rotates the camera toward a world position with eased, human-looking motion. */
    private static void faceSmooth(Minecraft client, Vec3 target, float maxStep) {
        if (client == null || client.player == null) {
            return;
        }
        faceAngles(client, yawToward(client, target), pitchToward(client, target), maxStep);
    }

    /**
     * Camera core. Velocity-based easing: the turn rate accelerates toward
     * 40% of the remaining angle (capped at maxStep) and never overshoots,
     * giving an ease-in / ease-out arc with no velocity jumps between ticks.
     * The renderer interpolates rotation across each tick, so the result is
     * fluid at any framerate.
     *
     * If the player's rotation isn't where we left it, the user moved their
     * mouse: the bot yields the camera entirely for a moment instead of
     * fighting them for it.
     */
    private static void faceAngles(Minecraft client, float desiredYaw, float desiredPitch, float maxStep) {
        int now = client.player.tickCount;
        if (now - lastFaceTick > 2) {
            // We haven't controlled the camera recently: start from rest.
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            expectedYaw = Float.NaN;
        }
        lastFaceTick = now;

        if (!Float.isNaN(expectedYaw)) {
            float userMoved = Math.abs(Mth.wrapDegrees(client.player.getYRot() - expectedYaw))
                    + Math.abs(client.player.getXRot() - expectedPitch);
            // Threshold generous enough that server rotation nudges don't
            // false-trigger and freeze the camera mid-motion.
            if (userMoved > 4.0F) {
                cameraYieldTicks = 10;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
            }
        }
        if (cameraYieldTicks > 0) {
            cameraYieldTicks--;
            expectedYaw = Float.NaN;
            return;
        }

        float yawError = Mth.wrapDegrees(desiredYaw - client.player.getYRot());
        float pitchError = Mth.wrapDegrees(desiredPitch - client.player.getXRot());

        yawVelocity = approachVelocity(yawVelocity, yawError, maxStep);
        pitchVelocity = approachVelocity(pitchVelocity, pitchError, maxStep);

        float newYaw = client.player.getYRot() + yawVelocity;
        float newPitch = Mth.clamp(client.player.getXRot() + pitchVelocity, -90.0F, 90.0F);
        client.player.setYRot(newYaw);
        client.player.setXRot(newPitch);
        expectedYaw = newYaw;
        expectedPitch = newPitch;
    }

    private static float approachVelocity(float velocity, float error, float maxStep) {
        // Chase a velocity proportional to the remaining angle. No hard brake at
        // the endpoint: motion is allowed to glide through with a touch of
        // overshoot and correct itself, which flows between targets instead of
        // stopping dead on each one.
        float targetVelocity = Mth.clamp(error * 0.38F, -maxStep, maxStep);
        velocity += (targetVelocity - velocity) * 0.55F;
        if (Math.abs(error) < 0.35F && Math.abs(velocity) < 0.8F) {
            return error; // settled: lock on exactly
        }
        return velocity;
    }

    private static float yawToward(Minecraft client, Vec3 target) {
        Vec3 eye = client.player.getEyePosition();
        return (float) (Math.toDegrees(Math.atan2(target.z - eye.z, target.x - eye.x)) - 90.0D);
    }

    private static float pitchToward(Minecraft client, Vec3 target) {
        Vec3 eye = client.player.getEyePosition();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(target.y - eye.y, horizontal)));
    }

    /** How far (degrees) the current view is from centering the given point. */
    private static float angularErrorTo(Minecraft client, Vec3 target) {
        float yawError = Math.abs(Mth.wrapDegrees(yawToward(client, target) - client.player.getYRot()));
        float pitchError = Math.abs(Mth.wrapDegrees(pitchToward(client, target) - client.player.getXRot()));
        return Math.max(yawError, pitchError);
    }

    /**
     * Aim gate: true while the camera should finish turning toward the block
     * before acting on it. Bounded so a gate can never stall the job, and
     * disabled while the user has the camera.
     */
    private static boolean needsAim(Minecraft client, BlockPos pos, int maxWaitTicks) {
        if (!pos.equals(lastAimTarget)) {
            lastAimTarget = pos.immutable();
            aimTicks = 0;
        }
        if (cameraYieldTicks > 0) {
            return false;
        }
        if (angularErrorTo(client, centerOf(pos)) <= AIM_THRESHOLD_DEG || aimTicks >= maxWaitTicks) {
            return false;
        }
        aimTicks++;
        return true;
    }

    private static void resetCameraState() {
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        expectedYaw = Float.NaN;
        expectedPitch = Float.NaN;
        cameraYieldTicks = 0;
        lastAimTarget = null;
        aimTicks = 0;
    }

    private static Direction getHitDirection(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null) {
            return Direction.UP;
        }

        Vec3 eye = client.player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5D);
        double dy = eye.y - (pos.getY() + 0.5D);
        double dz = eye.z - (pos.getZ() + 0.5D);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------
    // Selection outline
    // ------------------------------------------------------------------

    private static void tickSelectionOutline(Minecraft client) {
        if (!selectionOutlineEnabled || client == null || client.level == null || client.player == null || pos1 == null || pos2 == null) {
            return;
        }

        outlineParticleTicks++;
        if (outlineParticleTicks < 8) {
            return;
        }
        outlineParticleTicks = 0;

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        double step = calculateOutlineStep(maxX - minX, maxY - minY, maxZ - minZ);
        drawParticleLine(client, minX, minY, minZ, maxX, minY, minZ, step);
        drawParticleLine(client, minX, minY, maxZ, maxX, minY, maxZ, step);
        drawParticleLine(client, minX, maxY, minZ, maxX, maxY, minZ, step);
        drawParticleLine(client, minX, maxY, maxZ, maxX, maxY, maxZ, step);

        drawParticleLine(client, minX, minY, minZ, minX, minY, maxZ, step);
        drawParticleLine(client, maxX, minY, minZ, maxX, minY, maxZ, step);
        drawParticleLine(client, minX, maxY, minZ, minX, maxY, maxZ, step);
        drawParticleLine(client, maxX, maxY, minZ, maxX, maxY, maxZ, step);

        drawParticleLine(client, minX, minY, minZ, minX, maxY, minZ, step);
        drawParticleLine(client, maxX, minY, minZ, maxX, maxY, minZ, step);
        drawParticleLine(client, minX, minY, maxZ, minX, maxY, maxZ, step);
        drawParticleLine(client, maxX, minY, maxZ, maxX, maxY, maxZ, step);
    }

    private static double calculateOutlineStep(int sizeX, int sizeY, int sizeZ) {
        int largest = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (largest > 96) {
            return 3.0D;
        }
        if (largest > 64) {
            return 1.5D;
        }
        if (largest > 32) {
            return 0.75D;
        }
        return 0.4D;
    }

    private static void drawParticleLine(Minecraft client, double x1, double y1, double z1, double x2, double y2, double z2, double step) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int points = Math.max(1, (int) Math.ceil(distance / Math.max(0.25D, step)));

        // Dust particles hold still and take any RGB color, so a tight spacing
        // reads as a near-solid line in the configured color.
        DustParticleOptions dust = new DustParticleOptions(outlineColorRgb, 1.0F);
        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            client.level.addParticle(dust, x1 + dx * t, y1 + dy * t, z1 + dz * t, 0.0D, 0.0D, 0.0D);
        }
    }

    // ------------------------------------------------------------------
    // Settings accessors (used by the Mod Menu screen)
    // ------------------------------------------------------------------

    public static boolean isSelectionOutlineEnabled() {
        return selectionOutlineEnabled;
    }

    public static void setSelectionOutlineEnabled(boolean enabled) {
        selectionOutlineEnabled = enabled;
        saveSettings();
    }

    public static boolean isAutoMoveEnabled() {
        return autoMove;
    }

    public static void setAutoMoveEnabled(boolean enabled) {
        autoMove = enabled;
        if (!autoMove) {
            releaseMovementKeys(Minecraft.getInstance());
            resetPath();
        }
        saveSettings();
    }

    public static boolean isAutoToolsEnabled() {
        return autoTools;
    }

    public static void setAutoToolsEnabled(boolean enabled) {
        autoTools = enabled;
        saveSettings();
    }

    public static boolean isAutoEatEnabled() {
        return autoEat;
    }

    public static void setAutoEatEnabled(boolean enabled) {
        autoEat = enabled;
        if (!autoEat) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.options != null && wasEating) {
                client.options.keyUse.setDown(false);
            }
            eatTicks = 0;
            wasEating = false;
        }
        saveSettings();
    }

    public static boolean isLavaSafetyEnabled() {
        return lavaSafety;
    }

    public static void setLavaSafetyEnabled(boolean enabled) {
        lavaSafety = enabled;
        saveSettings();
    }

    public static int getHungerThreshold() {
        return hungerThreshold;
    }

    public static void setHungerThreshold(int value) {
        hungerThreshold = Mth.clamp(value, 1, 19);
        saveSettings();
    }

    public static int getDelayTicks() {
        return delayTicks;
    }

    public static void setDelayTicks(int value) {
        delayTicks = Mth.clamp(value, 1, 20);
        saveSettings();
    }

    public static int getMiningRange() {
        return (int) Math.round(maxRange);
    }

    public static void setMiningRange(int value) {
        maxRange = Mth.clamp(value, 1, 6);
        saveSettings();
    }

    public static int getMoveStopRange() {
        return (int) Math.round(moveStopRange);
    }

    public static void setMoveStopRange(int value) {
        moveStopRange = Mth.clamp(value, 1, 6);
        saveSettings();
    }

    public static int getMinToolDurability() {
        return minToolDurability;
    }

    public static void setMinToolDurability(int value) {
        minToolDurability = Mth.clamp(value, 1, 250);
        saveSettings();
    }

    public static int getPauseHealth() {
        return pauseHealth;
    }

    public static void setPauseHealth(int value) {
        pauseHealth = Mth.clamp(value, 0, 19);
        saveSettings();
    }

    public static boolean isAutoTorchEnabled() {
        return autoTorch;
    }

    public static void setAutoTorchEnabled(boolean enabled) {
        autoTorch = enabled;
        saveSettings();
    }

    public static boolean isHudEnabled() {
        return hudEnabled;
    }

    public static void setHudEnabled(boolean enabled) {
        hudEnabled = enabled;
        saveSettings();
    }

    public static boolean isDropSweepEnabled() {
        return dropSweep;
    }

    public static void setDropSweepEnabled(boolean enabled) {
        dropSweep = enabled;
        saveSettings();
    }

    public static String getOutlineColorHex() {
        return outlineColorHex;
    }

    /** Accepts "RRGGBB" or "#RRGGBB"; returns false (keeping the old color) if invalid. */
    public static boolean setOutlineColorHex(String hex) {
        Integer parsed = parseHexColor(hex);
        if (parsed == null) {
            return false;
        }
        outlineColorRgb = parsed;
        outlineColorHex = "#" + String.format("%06X", parsed);
        saveSettings();
        return true;
    }

    private static Integer parseHexColor(String hex) {
        if (hex == null) {
            return null;
        }
        String cleaned = hex.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Settings persistence
    // ------------------------------------------------------------------

    private static void loadSettings() {
        if (!Files.exists(CONFIG_PATH)) {
            saveSettings();
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
            selectionOutlineEnabled = readBoolean(properties, "selectionOutline", selectionOutlineEnabled);
            autoMove = readBoolean(properties, "autoMove", autoMove);
            autoTools = readBoolean(properties, "autoTools", autoTools);
            autoEat = readBoolean(properties, "autoEat", autoEat);
            lavaSafety = readBoolean(properties, "lavaSafety", lavaSafety);
            hungerThreshold = readInt(properties, "hungerThreshold", hungerThreshold, 1, 19);
            maxRange = readDouble(properties, "miningRange", maxRange, 1.0D, 6.0D);
            moveStopRange = readDouble(properties, "moveStopRange", moveStopRange, 1.0D, 6.0D);
            delayTicks = readInt(properties, "delayTicks", delayTicks, 1, 20);
            minToolDurability = readInt(properties, "minToolDurability", minToolDurability, 1, 250);
            pauseHealth = readInt(properties, "pauseHealth", pauseHealth, 0, 19);
            autoTorch = readBoolean(properties, "autoTorch", autoTorch);
            hudEnabled = readBoolean(properties, "hudEnabled", hudEnabled);
            dropSweep = readBoolean(properties, "dropSweep", dropSweep);
            Integer color = parseHexColor(properties.getProperty("outlineColor", outlineColorHex));
            if (color != null) {
                outlineColorRgb = color;
                outlineColorHex = "#" + String.format("%06X", color);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load Delta Utilities settings, rewriting defaults", e);
            saveSettings();
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties properties = new Properties();
            properties.setProperty("selectionOutline", Boolean.toString(selectionOutlineEnabled));
            properties.setProperty("autoMove", Boolean.toString(autoMove));
            properties.setProperty("autoTools", Boolean.toString(autoTools));
            properties.setProperty("autoEat", Boolean.toString(autoEat));
            properties.setProperty("lavaSafety", Boolean.toString(lavaSafety));
            properties.setProperty("hungerThreshold", Integer.toString(hungerThreshold));
            properties.setProperty("miningRange", Double.toString(maxRange));
            properties.setProperty("moveStopRange", Double.toString(moveStopRange));
            properties.setProperty("delayTicks", Integer.toString(delayTicks));
            properties.setProperty("minToolDurability", Integer.toString(minToolDurability));
            properties.setProperty("pauseHealth", Integer.toString(pauseHealth));
            properties.setProperty("autoTorch", Boolean.toString(autoTorch));
            properties.setProperty("hudEnabled", Boolean.toString(hudEnabled));
            properties.setProperty("dropSweep", Boolean.toString(dropSweep));
            properties.setProperty("outlineColor", outlineColorHex);
            try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "Delta Utilities settings");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save Delta Utilities settings", e);
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
            return Mth.clamp(value, min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double readDouble(Properties properties, String key, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
            return Mth.clamp(value, min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Task tracking + status
    // ------------------------------------------------------------------

    private static void startTaskTracking(int totalBlocks) {
        initialTaskBlocks = Math.max(0, totalBlocks);
        taskStartedAtMillis = System.currentTimeMillis();
    }

    private static int getBlocksLeft() {
        return queue.size() + (currentTarget == null ? 0 : 1);
    }

    private static String formatTaskStatus(String label, int blocksLeft) {
        return "§b" + label
                + " §7| Blocks: §f" + blocksLeft
                + " §7| Progress: §f" + formatProgress(blocksLeft)
                + " §7| ETA: §f" + formatEta(blocksLeft);
    }

    private static String formatProgress(int blocksLeft) {
        if (initialTaskBlocks <= 0) {
            return blocksLeft <= 0 ? "100%" : "0%";
        }
        int done = Math.max(0, initialTaskBlocks - blocksLeft);
        int percent = Mth.clamp((int) Math.round((done * 100.0D) / initialTaskBlocks), 0, 100);
        return percent + "%";
    }

    private static String formatEta(int blocksLeft) {
        if (blocksLeft <= 0) {
            return "0s";
        }

        int completed = Math.max(0, initialTaskBlocks - blocksLeft);
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - taskStartedAtMillis);

        int totalSeconds;
        if (completed > 0 && elapsedMillis > 1000L) {
            double millisPerBlock = elapsedMillis / (double) completed;
            totalSeconds = Math.max(1, (int) Math.ceil((blocksLeft * millisPerBlock) / 1000.0D));
        } else {
            int ticksPerBlock = Math.max(1, delayTicks + 4);
            totalSeconds = Math.max(1, (int) Math.ceil((blocksLeft * ticksPerBlock) / 20.0D));
        }

        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    // ------------------------------------------------------------------
    // Lifecycle helpers
    // ------------------------------------------------------------------

    private static BlockPos getLookingAtBlock() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.hitResult == null) {
            return null;
        }
        if (client.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static void finishJob(Minecraft client, String message) {
        stopJob();
        clearSelection();
        client.player.sendSystemMessage(Component.literal(message));
    }

    private static void clearSelection() {
        pos1 = null;
        pos2 = null;
        currentTarget = null;
        queue.clear();
        delayedMineTargets.clear();
        outlineParticleTicks = 0;
    }

    private static void stopJob() {
        running = false;
        paused = false;
        currentTarget = null;
        lastBreakTarget = null;
        lastMinedPos = null;
        breakTicksOnTarget = 0;
        tickCooldown = 0;
        placeCooldown = 0;
        jumpPulseTicks = 0;
        jumpCooldownTicks = 0;
        stuckTicks = 0;
        targetTicks = 0;
        breakRestartTicks = 0;
        delayedMineTargets.clear();
        eatTicks = 0;
        wasEating = false;
        inventorySwapCooldown = 0;
        initialTaskBlocks = 0;
        taskStartedAtMillis = 0L;
        lowHealthTriggered = false;
        wasInventoryFull = false;
        minePhase = MinePhase.WORK;
        phaseTicks = 0;
        depositClickCooldown = 0;
        torchCooldown = 0;
        fillReplace = false;
        sweepTargetId = -1;
        sweepNoProgressTicks = 0;
        sweepBestDistance = Double.MAX_VALUE;
        sweepSkippedDrops.clear();
        resetCameraState();
        resetPath();
        releaseMovementKeys(Minecraft.getInstance());
    }

    private static void releaseMovementKeys(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyUse.setDown(false);
    }

    private static void releaseMovementKeysExceptUse(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keySprint.setDown(false);
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
