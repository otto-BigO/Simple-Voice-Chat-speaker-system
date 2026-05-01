package com.example.remotespeaker.skin;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Identity holder for the two head skins used to mark Remote Speaker units:
 *  • MIC      — microphone skin   (sender side)
 *  • SPEAKER  — speaker-cone skin (receiver side)
 *
 * Texture sources (minecraft-heads.com):
 *   MIC      — http://textures.minecraft.net/texture/4d79df89b785267c26db130ff9660afcda5087027f9d222fb1e186a9bf8eb37d
 *   SPEAKER  — http://textures.minecraft.net/texture/b4890fd9509bd0c51aa4989c3746accd3fb36fc18d5a01f63647b00e295ca85a
 */
public final class SpeakerSkins {

    private static final String MIC_TEXTURE_VALUE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGQ3OWRmODliNzg1MjY3YzI2ZGIxMzBmZjk2NjBhZmNkYTUwODcwMjdmOWQyMjJmYjFlMTg2YTliZjhlYjM3ZCJ9fX0=";

    private static final String SPEAKER_TEXTURE_VALUE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjQ4OTBmZDk1MDliZDBjNTFhYTQ5ODljMzc0NmFjY2QzZmIzNmZjMThkNWEwMWY2MzY0N2IwMGUyOTVjYTg1YSJ9fX0=";

    private static final UUID MIC_PROFILE_UUID     = UUID.fromString("a5d6e8c2-9f3b-4e7a-8c1d-5f2b9a8e0c4d");
    private static final UUID SPEAKER_PROFILE_UUID = UUID.fromString("b6e7f9d3-0a4c-5f8b-9d2e-6a3c0b9f1d5e");
    private static final String MIC_PROFILE_NAME     = "RemoteMic";
    private static final String SPEAKER_PROFILE_NAME = "RemoteSpeaker";

    private static final ResolvableProfile MIC_PROFILE     =
        buildProfile(MIC_PROFILE_UUID, MIC_PROFILE_NAME, MIC_TEXTURE_VALUE);
    private static final ResolvableProfile SPEAKER_PROFILE =
        buildProfile(SPEAKER_PROFILE_UUID, SPEAKER_PROFILE_NAME, SPEAKER_TEXTURE_VALUE);

    private SpeakerSkins() {}

    private static ResolvableProfile buildProfile(UUID uuid, String name, String value) {
        PropertyMap props = new PropertyMap(ImmutableMultimap.of(
            "textures", new Property("textures", value)));
        return ResolvableProfile.createResolved(new GameProfile(uuid, name, props));
    }

    public static ResolvableProfile profileFor(SpeakerRole role) {
        return role == SpeakerRole.MIC ? MIC_PROFILE : SPEAKER_PROFILE;
    }

    /** A new PLAYER_HEAD ItemStack pre-set with the given role's profile. */
    public static ItemStack makeItem(SpeakerRole role) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, profileFor(role));
        return stack;
    }

    /** Returns the role of the given placed head, or null if it is not one of ours. */
    @Nullable
    public static SpeakerRole roleOf(BlockState state, BlockEntity be) {
        if (state == null || be == null) return null;
        if (!state.is(Blocks.PLAYER_HEAD) && !state.is(Blocks.PLAYER_WALL_HEAD)) return null;
        if (!(be instanceof SkullBlockEntity skull)) return null;
        ResolvableProfile profile = skull.getOwnerProfile();
        return profile == null ? null : matchTexture(profile);
    }

    /** Returns the role of the given player-head item stack, or null if not one of ours. */
    @Nullable
    public static SpeakerRole roleOfItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (!stack.is(Items.PLAYER_HEAD)) return null;
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        return profile == null ? null : matchTexture(profile);
    }

    @Nullable
    private static SpeakerRole matchTexture(ResolvableProfile profile) {
        GameProfile gp = profile.partialProfile();
        if (gp == null) return null;
        for (Property p : gp.properties().get("textures")) {
            if (MIC_TEXTURE_VALUE.equals(p.value())) return SpeakerRole.MIC;
            if (SPEAKER_TEXTURE_VALUE.equals(p.value())) return SpeakerRole.SPEAKER;
        }
        return null;
    }
}
