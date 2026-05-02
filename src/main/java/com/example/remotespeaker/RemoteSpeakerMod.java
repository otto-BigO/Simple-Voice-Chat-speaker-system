package com.example.remotespeaker;

import com.example.remotespeaker.data.SpeakerSavedData;
import com.example.remotespeaker.gui.SpeakerSelectMenuProvider;
import com.example.remotespeaker.memo.RecordingManager;
import com.example.remotespeaker.memo.RecordingSession;
import com.example.remotespeaker.memo.VoiceMemo;
import com.example.remotespeaker.skin.SpeakerRole;
import com.example.remotespeaker.skin.SpeakerSkins;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

public class RemoteSpeakerMod implements ModInitializer {

    public static final String MOD_ID = "remote_speaker";

    /** Pending head placements to inspect on the next tick (after the block lands). */
    private static final Queue<PendingPlace> pendingPlaces = new ConcurrentLinkedQueue<>();

    /** Player UUID → speaker UUID awaiting a sign-typed rename. */
    private static final Map<UUID, UUID> activeRenames = new ConcurrentHashMap<>();

    private record PendingPlace(ServerLevel level, BlockPos clicked, BlockPos relative) {}

    @Override
    public void onInitialize() {
        registerHydrationListener();
        registerInteractionListeners();
        registerTickListener();
        registerDisconnectListener();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher));
    }

    // -------------------------------------------------------------------------
    // Hydration on server start
    // -------------------------------------------------------------------------

    private static void registerHydrationListener() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                SpeakerManager.INSTANCE.hydrateFromLevel(level);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Interactions
    // -------------------------------------------------------------------------

    private static void registerInteractionListeners() {
        UseBlockCallback.EVENT.register(RemoteSpeakerMod::onUseBlock);

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (world instanceof ServerLevel sl
                && SpeakerSavedData.get(sl).get(pos).isPresent()) {
                SpeakerManager.INSTANCE.unregister(sl, pos);
            }
            return true;
        });
    }

    private static InteractionResult onUseBlock(Player player, Level world,
                                                InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide() || !(world instanceof ServerLevel sl)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        BlockPos clicked = hit.getBlockPos();
        BlockState clickedState = sl.getBlockState(clicked);
        BlockEntity clickedBe = sl.getBlockEntity(clicked);
        SpeakerRole clickedRole = SpeakerSkins.roleOf(clickedState, clickedBe);

        // Sneak + click on a registered unit → open sign-based rename UI.
        if (sp.isShiftKeyDown() && clickedRole != null) {
            Optional<SpeakerSavedData.SpeakerRecord> rec = SpeakerSavedData.get(sl).get(clicked);
            if (rec.isPresent()) {
                openRenameSign(sp, sl, clicked, rec.get());
                return InteractionResult.SUCCESS_SERVER;
            }
        }

        // Tap on a mic = open menu OR finish recording.
        if (clickedRole == SpeakerRole.MIC) {
            Optional<SpeakerSavedData.SpeakerRecord> rec = SpeakerSavedData.get(sl).get(clicked);
            if (rec.isPresent()) {
                if (RecordingManager.INSTANCE.isRecording(sp.getUUID())) {
                    if (RecordingManager.INSTANCE.stopRecording(sp.getUUID())) {
                        sp.sendOverlayMessage(Component.literal("Voice memo sent."));
                    }
                } else {
                    openSpeakerMenu(sp, clicked);
                }
                return InteractionResult.SUCCESS_SERVER;
            }
        }

        // Tap on a speaker = no-op (don't pop a vanilla skull GUI either).
        if (clickedRole == SpeakerRole.SPEAKER) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // Held mic or speaker item → schedule a post-place check for either pos.
        ItemStack held = player.getItemInHand(hand);
        if (SpeakerSkins.roleOfItem(held) != null) {
            BlockPos relative = clicked.relative(hit.getDirection());
            pendingPlaces.add(new PendingPlace(sl, clicked, relative));
        }
        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // Sign-based rename UI
    // -------------------------------------------------------------------------

    /**
     * Sends the client an OpenSignEditor packet for a phantom sign at the
     * speaker's position. No actual block is placed — {@link com.example.remotespeaker.mixin.SignUpdateMixin}
     * intercepts the reply packet and routes the typed lines to
     * {@link #tryHandleRenamePacket}.
     */
    private static void openRenameSign(ServerPlayer sp, ServerLevel sl, BlockPos speakerPos,
                                       SpeakerSavedData.SpeakerRecord rec) {
        activeRenames.put(sp.getUUID(), rec.uuid());
        sp.connection.send(new ClientboundOpenSignEditorPacket(speakerPos, true));
    }

    /**
     * Called from the sign-update mixin. Returns true if the packet was consumed
     * (player had an active rename), in which case vanilla handling is cancelled.
     */
    public static boolean tryHandleRenamePacket(ServerPlayer player, String[] lines) {
        UUID speakerUUID = activeRenames.remove(player.getUUID());
        if (speakerUUID == null) return false;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(trimmed);
        }
        String label = out.toString();
        SpeakerManager.INSTANCE.updateLabel(speakerUUID, label);
        player.sendOverlayMessage(label.isEmpty()
            ? Component.literal("Label cleared.")
            : Component.literal("Label set: " + label));
        return true;
    }

    private static void openSpeakerMenu(ServerPlayer sp, BlockPos sourceMicPos) {
        List<SpeakerManager.SpeakerEntry> receivers =
            SpeakerManager.INSTANCE.getAllReceivers(null);

        if (receivers.isEmpty()) {
            sp.sendOverlayMessage(Component.literal(
                "No speakers placed. Use /rspeaker give speaker first."));
            return;
        }
        SpeakerSelectMenuProvider provider =
            new SpeakerSelectMenuProvider(receivers, sourceMicPos);
        if (provider.entries().size() > 54) {
            sp.sendOverlayMessage(Component.literal(
                "Showing 54 of " + provider.entries().size() + " entries."));
        }
        sp.openMenu(provider);
    }

    // -------------------------------------------------------------------------
    // Server tick — drain memos, fire scheduled playbacks, register placements,
    // refresh recording-actionbar
    // -------------------------------------------------------------------------

    private static void registerTickListener() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Inspect placements that were attempted last tick
            PendingPlace pp;
            while ((pp = pendingPlaces.poll()) != null) {
                tryRegisterPlacement(pp.level, pp.clicked);
                tryRegisterPlacement(pp.level, pp.relative);
            }

            List<VoiceMemo> ready = RecordingManager.INSTANCE.drainDeliveryQueue();
            long tick = server.getTickCount();
            for (VoiceMemo memo : ready) {
                SpeakerManager.INSTANCE.scheduleMemoDelivery(memo, tick);
            }
            SpeakerManager.INSTANCE.tick(server);

            // Refresh "● Recording → label" actionbar every 40 ticks (~2 s) so it
            // stays visible for the entire recording session.
            if (tick % 40 == 0) {
                for (RecordingSession session : RecordingManager.INSTANCE.activeSessions()) {
                    if (session.isStopped()) continue;
                    ServerPlayer sp = server.getPlayerList().getPlayer(session.playerUUID);
                    if (sp == null) continue;
                    sp.sendOverlayMessage(Component.literal(
                        "● Recording → " + session.targetDisplay
                        + "  │  Tap any mic to send"));
                }
            }
        });
    }

    private static void tryRegisterPlacement(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        SpeakerRole role = SpeakerSkins.roleOf(state, be);
        if (role == null) return;
        if (SpeakerSavedData.get(level).get(pos).isPresent()) return;
        SpeakerManager.INSTANCE.register(level, pos, UUID.randomUUID(), "", role);
    }

    // -------------------------------------------------------------------------
    // Disconnect cleanup
    // -------------------------------------------------------------------------

    private static void registerDisconnectListener() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            RecordingManager.INSTANCE.cancelRecording(uuid);
            activeRenames.remove(uuid);
        });
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rspeaker")
            .requires(src -> src.permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))

            .then(Commands.literal("label")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String text = StringArgumentType.getString(ctx, "text");
                        return labelSpeaker(ctx.getSource().getPlayerOrException(), text, ctx.getSource());
                    })))

            .then(Commands.literal("stop")
                .executes(ctx -> stopRecording(ctx.getSource().getPlayerOrException(), ctx.getSource())))

            .then(Commands.literal("cancel")
                .executes(ctx -> cancelRecording(ctx.getSource().getPlayerOrException(), ctx.getSource())))

            .then(Commands.literal("info")
                .executes(ctx -> infoSpeaker(ctx.getSource().getPlayerOrException(), ctx.getSource())))

            .then(Commands.literal("give")
                .executes(ctx -> giveItem(ctx.getSource().getPlayerOrException(),
                    ctx.getSource(), SpeakerRole.MIC))
                .then(Commands.literal("mic")
                    .executes(ctx -> giveItem(ctx.getSource().getPlayerOrException(),
                        ctx.getSource(), SpeakerRole.MIC)))
                .then(Commands.literal("speaker")
                    .executes(ctx -> giveItem(ctx.getSource().getPlayerOrException(),
                        ctx.getSource(), SpeakerRole.SPEAKER))))
        );
    }

    // -------------------------------------------------------------------------
    // Command implementations
    // -------------------------------------------------------------------------

    private static int labelSpeaker(ServerPlayer player, String text, CommandSourceStack source) {
        Optional<RaycastHit> hit = rayCastSpeaker(player);
        if (hit.isEmpty()) {
            source.sendFailure(Component.literal("Not looking at a Remote Speaker unit within 5 blocks."));
            return 0;
        }
        SpeakerSavedData.SpeakerRecord rec = hit.get().record;
        SpeakerManager.INSTANCE.updateLabel(rec.uuid(), text);
        player.sendOverlayMessage(Component.literal("Label set: " + text));
        return 1;
    }

    private static int stopRecording(ServerPlayer player, CommandSourceStack source) {
        boolean stopped = RecordingManager.INSTANCE.stopRecording(player.getUUID());
        if (stopped) {
            player.sendOverlayMessage(Component.literal("Voice memo sent."));
            return 1;
        }
        source.sendFailure(Component.literal("You are not currently recording."));
        return 0;
    }

    private static int cancelRecording(ServerPlayer player, CommandSourceStack source) {
        boolean had = RecordingManager.INSTANCE.isRecording(player.getUUID());
        RecordingManager.INSTANCE.cancelRecording(player.getUUID());
        if (had) {
            player.sendOverlayMessage(Component.literal("Recording cancelled."));
            return 1;
        }
        source.sendFailure(Component.literal("You are not currently recording."));
        return 0;
    }

    private static int infoSpeaker(ServerPlayer player, CommandSourceStack source) {
        Optional<RaycastHit> hit = rayCastSpeaker(player);
        if (hit.isEmpty()) {
            source.sendFailure(Component.literal("Not looking at a Remote Speaker unit within 5 blocks."));
            return 0;
        }
        SpeakerSavedData.SpeakerRecord rec = hit.get().record;
        String label = rec.label();
        String roleStr = rec.role() == SpeakerRole.MIC ? "Mic" : "Speaker";
        String msg = label.isEmpty()
            ? roleStr + " UUID: " + rec.uuid() + "  (no label)"
            : roleStr + ": \"" + label + "\"  [" + rec.uuid() + "]";
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int giveItem(ServerPlayer player, CommandSourceStack source, SpeakerRole role) {
        ItemStack stack = SpeakerSkins.makeItem(role);
        boolean added = player.getInventory().add(stack);
        if (!added) player.drop(stack, false);
        String label = role == SpeakerRole.MIC ? "Microphone" : "Speaker";
        player.sendOverlayMessage(Component.literal("Given 1 " + label + "."));
        return 1;
    }

    // -------------------------------------------------------------------------
    // Ray-cast helper
    // -------------------------------------------------------------------------

    private record RaycastHit(ServerLevel level, BlockPos pos, SpeakerSavedData.SpeakerRecord record) {}

    private static Optional<RaycastHit> rayCastSpeaker(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return Optional.empty();
        if (!(player.level() instanceof ServerLevel sl)) return Optional.empty();
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        if (SpeakerSkins.roleOf(sl.getBlockState(pos), sl.getBlockEntity(pos)) == null) {
            return Optional.empty();
        }
        return SpeakerSavedData.get(sl).get(pos).map(rec -> new RaycastHit(sl, pos, rec));
    }
}
