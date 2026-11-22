package sh.btx.archipelago;

import com.destroystokyo.paper.VersionHistoryManager;
import com.destroystokyo.paper.util.VersionFetcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Replaces Paper's version fetcher, which compares against Paper's builds and links to Paper's downloads.
 */
public final class ArchipelagoVersionFetcher implements VersionFetcher {

    @Override
    public long getCacheTime() {
        return 720000;
    }

    @Override
    public Component getVersionMessage() {
        final Component message = Component.text("Update checks are not available for Archipelago Paper", NamedTextColor.GRAY);

        final VersionHistoryManager.VersionData history = VersionHistoryManager.INSTANCE.getVersionData();
        if (history == null || history.getOldVersion() == null) {
            return message;
        }

        return Component.textOfChildren(
            message,
            Component.newline(),
            Component.text("Previous version: " + history.getOldVersion(), NamedTextColor.GRAY, TextDecoration.ITALIC)
        );
    }
}
