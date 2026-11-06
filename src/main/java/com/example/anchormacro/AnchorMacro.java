package com.example.anchormacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AnchorMacro implements ClientModInitializer {

    private static final boolean DEBUG = false; // true = show step-by-step chat messages

    private static final int MAX_DELAY = 10; // slider range: 0..10 ticks (1 tick = 50 ms)

    private enum Stage { IDLE, WAIT_ANCHOR, DETONATE }

    private static Stage stage = Stage.IDLE;
    private static BlockPos anchorPos = null;
    private static int timeout = 0;
    private static boolean enabled = false;

    private static int delay = 1;     // ticks to wait before each step (set by the slider)
    private static int stepDelay = 0; // countdown used by the state machine

    private static KeyMapping toggleKey;
    private static KeyMapping settingsKey;

    private static final Path CONFIG =
            FabricLoader.getInstance().getConfigDir().resolve("anchormacro.properties");

    private static void dbg(LocalPlayer p, String msg) {
        if (DEBUG && p != null) p.sendSystemMessage(Component.literal("[AnchorMacro] " + msg));
    }

    @Override
    public void onInitializeClient() {
        System.out.println("[AnchorMacro] 1.0.4 loaded, toggle key registered");
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("anchormacro", "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.anchormacro.toggle",
                82, // R
                category));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.anchormacro.settings",
                79, // O
                category));

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!enabled) return InteractionResult.PASS;
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) return InteractionResult.PASS;

            LocalPlayer lp = Minecraft.getInstance().player;
            dbg(lp, "right-click with anchor, hand=" + hand + ", stage=" + stage);

            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (stage != Stage.IDLE) { dbg(lp, "busy, stage=" + stage); return InteractionResult.PASS; }
            if (level.dimension() == Level.NETHER) { dbg(lp, "in Nether, skipping"); return InteractionResult.PASS; }

            BlockPlaceContext ctx = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit);
            anchorPos = ctx.getClickedPos();
            stage = Stage.WAIT_ANCHOR;
            timeout = 20 + delay;
            stepDelay = delay;
            dbg(lp, "waiting for anchor at " + anchorPos.toShortString());
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            reset();
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        Component.literal("Anchor Macro: " + (enabled ? "ON" : "OFF")));
            }
        }

        while (settingsKey.consumeClick()) {
            if (mc.gui.screen() == null) mc.gui.setScreen(new SettingsScreen());
        }

        if (stage == Stage.IDLE) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            reset();
            return;
        }

        switch (stage) {
            case WAIT_ANCHOR -> {
                if (mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                    if (stepDelay > 0) { stepDelay--; return; }

                    int glow = findInHotbar(player, Items.GLOWSTONE);
                    int totem = findInHotbar(player, Items.TOTEM_OF_UNDYING);
                    dbg(player, "anchor found. glowstone slot=" + glow + ", totem slot=" + totem);
                    if (glow < 0 || totem < 0) {
                        dbg(player, "need glowstone and a totem in the hotbar");
                        reset();
                        return;
                    }
                    selectSlot(player, glow);
                    InteractionResult r = interact(mc, player);
                    dbg(player, "charge result: " + r);
                    stage = Stage.DETONATE;
                    stepDelay = delay;
                } else if (--timeout <= 0) {
                    dbg(player, "anchor never appeared at " + anchorPos.toShortString()
                            + " (block there: " + mc.level.getBlockState(anchorPos).getBlock() + ")");
                    reset();
                }
            }
            case DETONATE -> {
                if (stepDelay > 0) { stepDelay--; return; }

                int totem = findInHotbar(player, Items.TOTEM_OF_UNDYING);
                if (totem >= 0) {
                    selectSlot(player, totem);
                    InteractionResult r = interact(mc, player);
                    dbg(player, "detonate result: " + r);
                }
                reset();
            }
            default -> reset();
        }
    }

    private static InteractionResult interact(Minecraft mc, LocalPlayer player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(anchorPos), Direction.UP, anchorPos, false);
        return mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private static int findInHotbar(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static void selectSlot(LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
    }

    private static void reset() {
        stage = Stage.IDLE;
        anchorPos = null;
        timeout = 0;
        stepDelay = 0;
    }

    // ---------- config ----------

    private static void loadConfig() {
        try {
            if (Files.exists(CONFIG)) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(CONFIG)) { p.load(in); }
                int v = Integer.parseInt(p.getProperty("delay", "1").trim());
                delay = Math.max(0, Math.min(MAX_DELAY, v));
            }
        } catch (Exception ignored) { }
    }

    private static void saveConfig() {
        try {
            Properties p = new Properties();
            p.setProperty("delay", Integer.toString(delay));
            try (var out = Files.newOutputStream(CONFIG)) { p.store(out, "Anchor Macro"); }
        } catch (Exception ignored) { }
    }

    // ---------- settings screen ----------

    private static class DelaySlider extends AbstractSliderButton {
        DelaySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), delay / (double) MAX_DELAY);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int t = (int) Math.round(this.value * MAX_DELAY);
            setMessage(Component.literal("Switch delay: " + t + (t == 1 ? " tick" : " ticks")
                    + " (" + (t * 50) + " ms)"));
        }

        @Override
        protected void applyValue() {
            delay = (int) Math.round(this.value * MAX_DELAY);
        }
    }

    private static class SettingsScreen extends Screen {
        SettingsScreen() {
            super(Component.literal("Anchor Macro"));
        }

        @Override
        protected void init() {
            addRenderableWidget(new DelaySlider(this.width / 2 - 100, this.height / 2 - 22, 200, 20));
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 - 100, this.height / 2 + 6, 200, 20)
                    .build());
        }

        @Override
        public void onClose() {
            saveConfig();
            super.onClose();
        }
    }
}
