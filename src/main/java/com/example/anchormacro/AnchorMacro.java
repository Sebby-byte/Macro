package com.example.anchormacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

public class AnchorMacro implements ClientModInitializer {

    private static final boolean DEBUG = true; // set to false to hide the chat messages

    private enum Stage { IDLE, WAIT_ANCHOR, DETONATE }

    private static Stage stage = Stage.IDLE;
    private static BlockPos anchorPos = null;
    private static int timeout = 0;
    private static boolean enabled = false;

    private static KeyMapping toggleKey;

    private static void dbg(LocalPlayer p, String msg) {
        if (DEBUG && p != null) p.sendSystemMessage(Component.literal("[AnchorMacro] " + msg));
    }

    @Override
    public void onInitializeClient() {
        System.out.println("[AnchorMacro] 1.0.2 loaded, toggle key registered");

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("anchormacro", "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.anchormacro.toggle",
                82, // GLFW key code for R
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
            timeout = 20;
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

        if (stage == Stage.IDLE) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            reset();
            return;
        }

        switch (stage) {
            case WAIT_ANCHOR -> {
                if (mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
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
                } else if (--timeout <= 0) {
                    dbg(player, "anchor never appeared at " + anchorPos.toShortString()
                            + " (block there: " + mc.level.getBlockState(anchorPos).getBlock() + ")");
                    reset();
                }
            }
            case DETONATE -> {
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
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return result;
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
    }
}
