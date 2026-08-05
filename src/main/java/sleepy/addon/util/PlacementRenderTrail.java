package sleepy.addon.util;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class PlacementRenderTrail {
    private final Map<BlockPos, Long> entries = new HashMap<>();

    public void clear() {
        entries.clear();
    }

    public void record(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (BlockPos pos : positions) {
            if (pos != null) entries.put(pos.toImmutable(), now);
        }
    }

    public void render(Render3DEvent event, boolean enabled, int fadeTimeMs, SettingColor sideColor, SettingColor lineColor) {
        if (!enabled || event == null || entries.isEmpty() || sideColor == null || lineColor == null) return;

        long now = System.currentTimeMillis();
        long visibleMs = Math.max(50L, fadeTimeMs);

        for (Iterator<Map.Entry<BlockPos, Long>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> entry = it.next();
            long ageMs = now - entry.getValue();
            if (ageMs >= visibleMs) {
                it.remove();
                continue;
            }

            double alphaFactor = fadeTimeMs <= 0 ? 1.0 : 1.0 - (ageMs / (double) visibleMs);
            int sideAlpha = MathHelper.clamp((int) Math.round(sideColor.a * alphaFactor), 0, 255);
            int lineAlpha = MathHelper.clamp((int) Math.round(lineColor.a * alphaFactor), 0, 255);
            Color fill = new Color(sideColor.r, sideColor.g, sideColor.b, sideAlpha);
            Color outline = new Color(lineColor.r, lineColor.g, lineColor.b, lineAlpha);

            if (fill.a <= 0 && outline.a <= 0) continue;
            event.renderer.box(entry.getKey(), fill, outline, ShapeMode.Both, 0);
        }
    }
}
