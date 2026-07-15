package baritone.launch.mixins;

import com.jbisb.hivetask.MiningSafety;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinHivePlayerController {
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void hive$guardDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!MiningSafety.canBreak(pos)) callback.setReturnValue(false);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void hive$guardStartDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        if (!MiningSafety.canBreak(pos)) callback.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void hive$guardContinueDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        if (!MiningSafety.canBreak(pos)) callback.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void hive$guardPlacement(
        LocalPlayer player,
        InteractionHand hand,
        BlockHitResult hit,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (!MiningSafety.canUseItem(player.getItemInHand(hand), hit.getBlockPos())) {
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }
}
