package sleepy.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import sleepy.addon.SleepyAddon;
import sleepy.addon.util.SneakDesyncTracker;

/** HUD port of Syntaxia's SneakDesyncDetector line. */
public final class SneakDesync extends HudElement {
    public static final HudElementInfo<SneakDesync> INFO = new HudElementInfo<>(
        SleepyAddon.HUD_GROUP,
        "sneak-desync",
        "Shows when the server or applied pose is sneaking without local sneak intent.",
        SneakDesync::new
    );

    public SneakDesync() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        boolean desynced = SneakDesyncTracker.isDesynced();
        String text = "Sneak Desynced: " + (desynced ? "True" : "False");
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, desynced ? Color.RED : Color.GREEN, true);
    }
}
