package com.example.remotespeaker.gui;

import com.example.remotespeaker.SpeakerManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and opens the speaker-selection chest GUI for a sender.
 *
 * Groups receivers by case-insensitive label so a label shared by N speakers
 * appears once with a stack-count badge of N. The menu's actionUUID for that
 * group is the UUID of any one member — broadcast lookup at delivery time
 * re-fans to all sharing the label.
 */
public class SpeakerSelectMenuProvider implements MenuProvider {

    /** One row in the speaker-select menu. */
    public record MenuEntry(String displayName, int count, UUID actionUUID, BlockPos targetPos) {}

    private final List<MenuEntry> entries;
    private final BlockPos sourceMicPos;
    private final int page;

    public SpeakerSelectMenuProvider(List<SpeakerManager.SpeakerEntry> receivers,
                                     BlockPos sourceMicPos) {
        this(buildEntries(receivers), sourceMicPos, 0);
    }

    public SpeakerSelectMenuProvider(List<MenuEntry> entries, BlockPos sourceMicPos, int page) {
        this.entries = entries;
        this.sourceMicPos = sourceMicPos;
        this.page = page;
    }

    private static List<MenuEntry> buildEntries(List<SpeakerManager.SpeakerEntry> receivers) {
        // Preserve insertion order of first-seen labels for stable UI.
        Map<String, List<SpeakerManager.SpeakerEntry>> byLabel = new LinkedHashMap<>();
        List<SpeakerManager.SpeakerEntry> unlabeled = new ArrayList<>();
        for (SpeakerManager.SpeakerEntry e : receivers) {
            if (e.label == null || e.label.isEmpty()) {
                unlabeled.add(e);
            } else {
                byLabel.computeIfAbsent(e.label.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
            }
        }
        List<MenuEntry> result = new ArrayList<>();
        for (List<SpeakerManager.SpeakerEntry> group : byLabel.values()) {
            SpeakerManager.SpeakerEntry first = group.get(0);
            String name = group.size() > 1
                ? first.label + " ×" + group.size()
                : first.label;
            result.add(new MenuEntry(name, Math.min(group.size(), 64),
                first.speakerUUID, first.pos));
        }
        for (SpeakerManager.SpeakerEntry e : unlabeled) {
            result.add(new MenuEntry(e.pos.toShortString(), 1, e.speakerUUID, e.pos));
        }
        return result;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("📻 Speakers");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new SpeakerSelectMenu(syncId, playerInventory, entries, page, sourceMicPos);
    }

    public List<MenuEntry> entries() { return entries; }
    public BlockPos sourceMicPos() { return sourceMicPos; }
    public int page() { return page; }
}
