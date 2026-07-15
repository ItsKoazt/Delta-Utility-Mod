package com.delta.utilitymod;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DeltaUtilityModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return DeltaUtilityModConfigScreen::new;
    }

    private static final class DeltaUtilityModConfigScreen extends Screen {
        private static final int COLUMN_WIDTH = 150;
        private static final int ROW_HEIGHT = 20;
        private static final int ROW_GAP = 24;
        private static final int SECTION_GAP = 28;

        private static final int COLOR_TITLE = 0xFFFFFFFF;
        private static final int COLOR_HEADER = 0xFFFFCF5A;
        private static final int COLOR_DIVIDER = 0x30FFFFFF;
        private static final int COLOR_FOOTER = 0xFF808080;

        private final Screen parent;

        private boolean outline;
        private boolean autoMove;
        private boolean autoTools;
        private boolean autoEat;
        private boolean lavaSafety;
        private int hunger;
        private int miningRange;
        private int moveRange;
        private int delay;
        private int durability;

        // Layout anchors shared between init() and extractRenderState().
        private int contentTop;
        private int leftX;
        private int rightX;
        private int automationHeaderY;
        private int miningHeaderY;
        private int safetyHeaderY;

        private DeltaUtilityModConfigScreen(Screen parent) {
            super(Component.literal("Delta Utility Mod"));
            this.parent = parent;
            loadCurrentSettings();
        }

        private void loadCurrentSettings() {
            outline = DeltaUtilityModClient.isSelectionOutlineEnabled();
            autoMove = DeltaUtilityModClient.isAutoMoveEnabled();
            autoTools = DeltaUtilityModClient.isAutoToolsEnabled();
            autoEat = DeltaUtilityModClient.isAutoEatEnabled();
            lavaSafety = DeltaUtilityModClient.isLavaSafetyEnabled();
            hunger = DeltaUtilityModClient.getHungerThreshold();
            miningRange = DeltaUtilityModClient.getMiningRange();
            moveRange = DeltaUtilityModClient.getMoveStopRange();
            delay = DeltaUtilityModClient.getDelayTicks();
            durability = Math.max(1, DeltaUtilityModClient.getMinToolDurability());
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            leftX = center - COLUMN_WIDTH - 5;
            rightX = center + 5;
            contentTop = Math.max(26, this.height / 2 - 102);

            int y = contentTop;

            automationHeaderY = y;
            y += 13;
            addToggle(leftX, y, "Auto Move", autoMove,
                    "Automatically walks and pathfinds to blocks that are out of reach.",
                    value -> autoMove = value);
            addToggle(rightX, y, "Smart Tools", autoTools,
                    "Picks the fastest safe tool from your hotbar or inventory for each block.",
                    value -> autoTools = value);
            y += ROW_GAP;
            addToggle(leftX, y, "Auto Eat", autoEat,
                    "Eats food from your inventory when you get hungry.",
                    value -> autoEat = value);
            addSlider(rightX, y, "Eat At Hunger", 1, 19, hunger,
                    "Hunger level (out of 20) that triggers auto eating.",
                    value -> hunger = value);
            y += SECTION_GAP;

            miningHeaderY = y;
            y += 13;
            addSlider(leftX, y, "Mining Range", 1, 6, miningRange,
                    "Maximum distance for breaking and placing blocks.",
                    value -> miningRange = value);
            addSlider(rightX, y, "Move Stop Range", 1, 6, moveRange,
                    "How close Auto Move walks toward a block before stopping.",
                    value -> moveRange = value);
            y += ROW_GAP;
            addSlider(leftX, y, "Delay Ticks", 1, 20, delay,
                    "Pause between block actions. Higher is slower but gentler.",
                    value -> delay = value);
            addSlider(rightX, y, "Min Tool Durability", 1, 250, durability,
                    "Tools at or below this remaining durability are never used.",
                    value -> durability = value);
            y += SECTION_GAP;

            safetyHeaderY = y;
            y += 13;
            addToggle(leftX, y, "Lava Safety", lavaSafety,
                    "Skips mining blocks that touch lava so the bot can't flood itself.",
                    value -> lavaSafety = value);
            addToggle(rightX, y, "Selection Outline", outline,
                    "Shows particle edges around the /pos1 - /pos2 selection box.",
                    value -> outline = value);
            y += ROW_GAP + 6;

            addRenderableWidget(Button.builder(Component.literal("Save & Close"), button -> {
                        applySettings();
                        openParent();
                    })
                    .tooltip(Tooltip.create(Component.literal("Apply and save all settings.")))
                    .bounds(leftX, y, COLUMN_WIDTH, ROW_HEIGHT).build());

            addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> openParent())
                    .tooltip(Tooltip.create(Component.literal("Discard changes.")))
                    .bounds(rightX, y, 72, ROW_HEIGHT).build());

            addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
                        outline = true;
                        autoMove = true;
                        autoTools = true;
                        autoEat = true;
                        lavaSafety = true;
                        hunger = 14;
                        miningRange = 5;
                        moveRange = 4;
                        delay = 2;
                        durability = 25;
                        this.clearWidgets();
                        init();
                    })
                    .tooltip(Tooltip.create(Component.literal("Restore default values (press Save & Close to keep them).")))
                    .bounds(rightX + 78, y, 72, ROW_HEIGHT).build());
        }

        private interface BoolConsumer {
            void accept(boolean value);
        }

        private void addToggle(int x, int y, String label, boolean initial, String tooltip, BoolConsumer consumer) {
            boolean[] state = {initial};
            addRenderableWidget(Button.builder(toggleText(label, initial), button -> {
                state[0] = !state[0];
                consumer.accept(state[0]);
                button.setMessage(toggleText(label, state[0]));
            }).tooltip(Tooltip.create(Component.literal(tooltip))).bounds(x, y, COLUMN_WIDTH, ROW_HEIGHT).build());
        }

        private void addSlider(int x, int y, String label, int min, int max, int initial, String tooltip, IntValueConsumer consumer) {
            IntSettingSlider slider = new IntSettingSlider(x, y, COLUMN_WIDTH, ROW_HEIGHT, label, min, max, initial, consumer);
            slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
            addRenderableWidget(slider);
        }

        private static Component toggleText(String label, boolean value) {
            return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
            super.extractRenderState(extractor, mouseX, mouseY, tickDelta);

            extractor.centeredText(this.font, this.title, this.width / 2, Math.max(10, contentTop - 16), COLOR_TITLE);

            drawSectionHeader(extractor, "Automation", automationHeaderY);
            drawSectionHeader(extractor, "Mining", miningHeaderY);
            drawSectionHeader(extractor, "Safety & Display", safetyHeaderY);

            extractor.centeredText(this.font, Component.literal("Delta Utility Mod v" + modVersion()),
                    this.width / 2, this.height - 12, COLOR_FOOTER);
        }

        private void drawSectionHeader(GuiGraphicsExtractor extractor, String label, int y) {
            extractor.text(this.font, label, leftX, y, COLOR_HEADER);
            int textWidth = this.font.width(label);
            extractor.fill(leftX + textWidth + 6, y + 4, rightX + COLUMN_WIDTH, y + 5, COLOR_DIVIDER);
        }

        private static String modVersion() {
            return FabricLoader.getInstance().getModContainer("delta_utility_mod")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
        }

        private void applySettings() {
            DeltaUtilityModClient.setSelectionOutlineEnabled(outline);
            DeltaUtilityModClient.setAutoMoveEnabled(autoMove);
            DeltaUtilityModClient.setAutoToolsEnabled(autoTools);
            DeltaUtilityModClient.setAutoEatEnabled(autoEat);
            DeltaUtilityModClient.setLavaSafetyEnabled(lavaSafety);
            DeltaUtilityModClient.setHungerThreshold(hunger);
            DeltaUtilityModClient.setMiningRange(miningRange);
            DeltaUtilityModClient.setMoveStopRange(moveRange);
            DeltaUtilityModClient.setDelayTicks(delay);
            DeltaUtilityModClient.setMinToolDurability(durability);
        }

        @Override
        public void onClose() {
            openParent();
        }

        private void openParent() {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(parent);
            }
        }
    }

    private interface IntValueConsumer {
        void accept(int value);
    }

    private static final class IntSettingSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final IntValueConsumer consumer;
        private int currentValue;

        private IntSettingSlider(int x, int y, int width, int height, String label, int min, int max, int currentValue, IntValueConsumer consumer) {
            super(x, y, width, height, Component.empty(), toSliderValue(min, max, currentValue));
            this.label = label;
            this.min = min;
            this.max = max;
            this.consumer = consumer;
            this.currentValue = clamp(currentValue, min, max);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.currentValue = fromSliderValue(min, max, this.value);
            this.setMessage(Component.literal(label + ": §b" + currentValue));
        }

        @Override
        protected void applyValue() {
            this.currentValue = fromSliderValue(min, max, this.value);
            consumer.accept(this.currentValue);
        }

        private static double toSliderValue(int min, int max, int value) {
            if (max <= min) {
                return 0.0D;
            }
            return (clamp(value, min, max) - min) / (double) (max - min);
        }

        private static int fromSliderValue(int min, int max, double value) {
            if (max <= min) {
                return min;
            }
            return clamp(min + (int) Math.round(value * (max - min)), min, max);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
