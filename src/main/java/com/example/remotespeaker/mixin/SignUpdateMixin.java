package com.example.remotespeaker.mixin;

import com.example.remotespeaker.RemoteSpeakerMod;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link ServerboundSignUpdatePacket} so we can capture the typed
 * lines whenever the sender has an in-flight remote-speaker rename session.
 * No real sign block needs to exist in the world — the OpenSignEditor packet
 * we sent the client makes it open the editor; this mixin handles the reply.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignUpdateMixin {

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void remote_speaker$interceptRename(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (RemoteSpeakerMod.tryHandleRenamePacket(self.player, packet.getLines())) {
            ci.cancel();
        }
    }
}
