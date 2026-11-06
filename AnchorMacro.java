package com.example.anchormacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side only. After you place a respawn anchor:
 *   1) switches to glowstone and right-clicks the anchor (charge)
 *   2) next tick switches to a totem and right-clicks it again
 *
 * Requires glowstone AND a totem in your hotbar.
 */
public class AnchorMacro implements ClientModInitializer {

    private enum Stage { IDLE, WAIT_ANCHOR, DETONATE }

    private static Stage stage = Stage.IDLE;
    private static BlockPos anchorPos = null;
    private static int timeout = 0;

    @Override
    public void onInitializeClient() {
        // Detect the moment you right-click with a respawn anchor in hand.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (stage != Stage.IDLE) return InteractionResult.PASS;
            if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) return InteractionResult.PASS;
            // Anchors don't explode in the Nether, so don't bother there.
            if (level.dimension() == Level.NETHER) return InteractionResult.PASS;

            BlockPlaceContext ctx = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit);
            anchorPos = ctx.getClickedPos();
            stage = Stage.WAIT_ANCHOR;
            timeout = 10; // ticks to wait for the server to confirm the placement
            return InteractionResult.PASS; // let vanilla placement continue
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
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
                    if (glow < 0 || totem < 0) {
                        player.displayClientMessage(
                                Component.literal("AnchorMacro: need glowstone and a totem in your hotbar"), true);
                        reset();
                        return;
                    }
                    selectSlot(player, glow);
                    interact(mc, player);
                    stage = Stage.DETONATE;
                } else if (--timeout <= 0) {
                    reset(); // placement never happened
                }
            }
            case DETONATE -> {
                int totem = findInHotbar(player, Items.TOTEM_OF_UNDYING);
                if (totem >= 0) {
                    selectSlot(player, totem);
                    interact(mc, player);
                }
                reset();
            }
            default -> reset();
        }
    }

    private static void interact(Minecraft mc, LocalPlayer player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(anchorPos), Direction.UP, anchorPos, false);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
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
