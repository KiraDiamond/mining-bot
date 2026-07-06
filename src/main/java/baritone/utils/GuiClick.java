/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils;

import baritone.api.utils.Helper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Headless 26.2 port: click-selection GUI is disabled. */
public class GuiClick extends Screen implements Helper {

    public GuiClick() {
        super(Component.literal("CLICK"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void onRender(PoseStack modelViewStack, Matrix4f projectionMatrix) {}
}
