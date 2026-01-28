package sleepy.addon.util.traits;

import com.google.common.eventbus.EventBus;
import net.minecraft.client.MinecraftClient;

/**
 * Minimal Util trait copied from Syntaxia.
 * Provides a shared MinecraftClient instance and a Guava EventBus.
 */
public interface Util {
    MinecraftClient mc = MinecraftClient.getInstance();
    EventBus EVENT_BUS = new EventBus();
}
