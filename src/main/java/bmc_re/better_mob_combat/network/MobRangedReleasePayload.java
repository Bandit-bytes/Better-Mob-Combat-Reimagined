package bmc_re.better_mob_combat.network;

import bmc_re.better_mob_combat.api.RangedWeaponKind;
import bmc_re.better_mob_combat.client.ClientPayloadHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MobRangedReleasePayload(
        int entityId,
        int weaponKind,
        boolean offHand
) {
    public static MobRangedReleasePayload create(
            int entityId,
            RangedWeaponKind kind,
            boolean offHand
    ) {
        return new MobRangedReleasePayload(entityId, kind.ordinal(), offHand);
    }

    public static MobRangedReleasePayload decode(FriendlyByteBuf buffer) {
        return new MobRangedReleasePayload(
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean()
        );
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityId);
        buffer.writeVarInt(this.weaponKind);
        buffer.writeBoolean(this.offHand);
    }

    public static void handle(MobRangedReleasePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleMobRangedRelease(payload)
        ));
        context.setPacketHandled(true);
    }
}
