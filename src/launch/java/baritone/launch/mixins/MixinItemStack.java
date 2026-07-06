/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.launch.mixins;

import baritone.api.utils.accessor.IItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class MixinItemStack implements IItemStack {

    @Unique
    private int baritoneHash;

    private void recalculateHash() {
        Item item = ((ItemStack) (Object) this).getItem();
        baritoneHash = item == null ? -1 : item.hashCode() + ((ItemStack) (Object) this).getDamageValue();
    }

    @Inject(
            method = "setDamageValue",
            at = @At("TAIL")
    )
    private void onItemDamageSet(CallbackInfo ci) {
        recalculateHash();
    }

    @Override
    public int getBaritoneHash() {
        if (baritoneHash == 0) recalculateHash();
        return baritoneHash;
    }
}
