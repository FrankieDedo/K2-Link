package k2.link.mixin;

import k2.link.K2Link;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The one hook K2 Link needs: the end of every client tick. Replaces Fabric API's tick event,
 *  so the mod runs with Fabric Loader alone. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void k2link$afterTick(CallbackInfo ci) {
        K2Link.onClientTick((Minecraft) (Object) this);
    }
}
