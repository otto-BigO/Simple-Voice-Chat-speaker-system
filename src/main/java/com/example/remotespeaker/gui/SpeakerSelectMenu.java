package com.example.remotespeaker.gui;

import com.example.remotespeaker.gui.SpeakerSelectMenuProvider.MenuEntry;
import com.example.remotespeaker.memo.RecordingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Server-side speaker-selection menu rendered as a vanilla GENERIC_9x3 chest.
 *
 * Layout (27 slots):
 *   slots  0..23  → entry slots (rows 1–2 + first 6 of row 3)
 *   slot   24     → ← previous-page arrow (only if page > 0)
 *   slot   25     → page indicator (PAPER, "Page X / Y")
 *   slot   26     → → next-page arrow (only if more entries follow)
 *
 * Each entry is a NOTE_BLOCK whose stack count badges the number of speakers
 * sharing that label. Clicking a slot starts a voice-memo recording targeting
 * that group.
 */
public class SpeakerSelectMenu extends AbstractContainerMenu {

    private static final int ENTRIES_PER_PAGE = 24;
    private static final int SLOT_PREV = 24;
    private static final int SLOT_PAGE = 25;
    private static final int SLOT_NEXT = 26;

    private final List<MenuEntry> allEntries;
    private final int page;
    private final BlockPos sourceMicPos;
    private final SimpleContainer container;

    public SpeakerSelectMenu(int syncId, Inventory playerInventory,
                             List<MenuEntry> allEntries, int page, BlockPos sourceMicPos) {
        super(MenuType.GENERIC_9x3, syncId);
        this.allEntries = allEntries;
        this.page = page;
        this.sourceMicPos = sourceMicPos;

        this.container = new SimpleContainer(27);
        int totalPages = Math.max(1, (allEntries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(allEntries.size(), start + ENTRIES_PER_PAGE);

        for (int i = start; i < end; i++) {
            MenuEntry e = allEntries.get(i);
            ItemStack stack = new ItemStack(Items.NOTE_BLOCK, Math.max(1, e.count()));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(e.displayName()));
            container.setItem(i - start, stack);
        }

        // Navigation row
        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("← Previous page"));
            container.setItem(SLOT_PREV, prev);
        }
        ItemStack pageItem = new ItemStack(Items.PAPER);
        pageItem.set(DataComponents.CUSTOM_NAME,
            Component.literal("Page " + (page + 1) + " / " + totalPages));
        container.setItem(SLOT_PAGE, pageItem);
        if (end < allEntries.size()) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("Next page →"));
            container.setItem(SLOT_NEXT, next);
        }

        // Register the 27 chest slots.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                final int slotIndex = row * 9 + col;
                addSlot(new Slot(container, slotIndex, 8 + col * 18, 18 + row * 18) {

                    @Override
                    public boolean mayPickup(Player player) {
                        return container.getItem(slotIndex).isEmpty() ? false : true;
                    }

                    @Override
                    public boolean mayPlace(ItemStack stack) { return false; }

                    @Override
                    public void onTake(Player player, ItemStack stack) {
                        if (player.containerMenu != null) {
                            player.containerMenu.setCarried(ItemStack.EMPTY);
                        }
                        container.setItem(slotIndex, stack);
                        if (player instanceof ServerPlayer sp) {
                            handleSlotClick(sp, slotIndex);
                        }
                    }
                });
            }
        }
        // Player inventory + hotbar
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                    8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    private void handleSlotClick(ServerPlayer sp, int slotIndex) {
        if (slotIndex == SLOT_PREV && page > 0) {
            sp.closeContainer();
            sp.openMenu(new SpeakerSelectMenuProvider(allEntries, sourceMicPos, page - 1));
            return;
        }
        if (slotIndex == SLOT_NEXT) {
            int totalPages = Math.max(1, (allEntries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
            if (page + 1 < totalPages) {
                sp.closeContainer();
                sp.openMenu(new SpeakerSelectMenuProvider(allEntries, sourceMicPos, page + 1));
            }
            return;
        }
        if (slotIndex == SLOT_PAGE) return;

        int entryIdx = page * ENTRIES_PER_PAGE + slotIndex;
        if (entryIdx < 0 || entryIdx >= allEntries.size()) return;
        MenuEntry entry = allEntries.get(entryIdx);

        sp.closeContainer();

        // Walkie-talkie key-down click — broadcast so room-mates hear it.
        sp.level().playSound(null, sp.blockPosition(),
            SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.4f, 1.4f);

        RecordingManager.INSTANCE.startRecording(
            sp.getUUID(),
            sp.getName().getString(),
            entry.actionUUID(),
            entry.targetPos(),
            sourceMicPos,
            entry.displayName()
        );

        sp.sendOverlayMessage(Component.literal(
            "● Recording → " + entry.displayName()
            + "  │  Tap any mic to send"));
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
