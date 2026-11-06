package bmc_re.better_mob_combat.network;

import bmc_re.better_mob_combat.client.ClientPayloadHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MobAttackPayload(
        int entityId,
        String animationId,
        boolean offHand,
        boolean twoHanded,
        float length,
        float animationUpswing,
        float damageUpswing
) {
    public static MobAttackPayload decode(FriendlyByteBuf buffer) {
        return new MobAttackPayload(
                buffer.readVarInt(),
                buffer.readUtf(256),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat()
        );
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeUtf(animationId, 256);
        buffer.writeBoolean(offHand);
        buffer.writeBoolean(twoHanded);
        buffer.writeFloat(length);
        buffer.writeFloat(animationUpswing);
        buffer.writeFloat(damageUpswing);
    }

    public static void handle(MobAttackPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleMobAttack(payload)
        ));
        context.setPacketHandled(true);
    }
}
