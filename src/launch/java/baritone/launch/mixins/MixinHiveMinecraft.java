package baritone.launch.mixins;

import com.jbisb.hivetask.HiveTaskClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinHiveMinecraft {
    @Inject(method = "tick", at = @At("TAIL"))
    private void hive$tickController(CallbackInfo callback) {
        HiveTaskClient.tickClient();
    }
}
