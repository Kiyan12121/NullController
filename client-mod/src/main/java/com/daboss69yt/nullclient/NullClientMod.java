package com.daboss69yt.nullclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of NullController. Tap G to open the command wheel.
 * Every button sends a /nullcmd order to the server, so the server stays the only thing that decides.
 */
public final class NullClientMod implements ClientModInitializer {
    public static final String NAMESPACE = "nullclient";

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(NAMESPACE, "main"));

    private static KeyBinding wheelKey;

    @Override
    public void onInitializeClient() {
        wheelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + NAMESPACE + ".wheel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (wheelKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new CommandWheelScreen());
                }
            }
        });
    }

    /** Sends an order to the server as /nullcmd &lt;order&gt;. Does nothing if you aren't in a world. */
    public static void send(String order) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        client.getNetworkHandler().sendChatCommand("nullcmd " + order);
    }
}
