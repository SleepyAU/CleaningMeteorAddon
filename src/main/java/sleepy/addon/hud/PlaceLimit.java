package sleepy.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import sleepy.addon.SleepyAddon;
import sleepy.addon.manager.PlacementManager;

public class PlaceLimit extends HudElement {
    public static final HudElementInfo<PlaceLimit> INFO = new HudElementInfo<>(SleepyAddon.HUD_GROUP, "place-limit", "Shows placements in the last second.", PlaceLimit::new);

    public PlaceLimit() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        int used = PlacementManager.get().getPlacedLastSecond();
        String text = "PlaceLimit " + used + "/20";
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));

        renderer.text(text, x, y, Color.WHITE, true);
    }
}
