package sleepy.addon.hud;

import sleepy.addon.SleepyAddon;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import sleepy.addon.util.SwapLimiter;

public class InvLimit extends HudElement {
    /**
     * The {@code name} parameter should be in kebab-case.
     */
    public static final HudElementInfo<InvLimit> INFO = new HudElementInfo<>(SleepyAddon.HUD_GROUP, "inv-limit", "Shows clickslot swap usage.", InvLimit::new);

    public InvLimit() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        int used = SwapLimiter.used();
        String text = "InvLimit " + used + "/76";
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));

        // Render text
        renderer.text(text, x, y, Color.WHITE, true);
    }
}
