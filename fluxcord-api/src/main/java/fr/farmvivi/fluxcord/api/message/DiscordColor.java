package fr.farmvivi.fluxcord.api.message;

import java.awt.*;

public enum DiscordColor {
    DISCORD_BLURPLE(new Color(88, 101, 242)),
    DISCORD_GREEN(new Color(87, 242, 135)),
    DISCORD_YELLOW(new Color(254, 231, 92)),
    DISCORD_FUCHSIA(new Color(235, 69, 158)),
    DISCORD_RED(new Color(237, 66, 69)),
    DISCORD_WHITE(new Color(255, 255, 255)),
    DISCORD_BLACK(new Color(0, 0, 0)),
    GREEN(new Color(109, 224, 16)),
    RED(new Color(224, 16, 16)),
    ORANGE(new Color(234, 184, 6)),
    BLURPLE(new Color(88, 101, 242)),
    DARK_RED(new Color(224, 0, 0));

    private final Color color;

    DiscordColor(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }
}
