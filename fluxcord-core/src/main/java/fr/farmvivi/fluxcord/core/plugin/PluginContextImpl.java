package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLoader;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import org.slf4j.Logger;

/**
 * Implementation of PluginContext that provides access to core services.
 */
public record PluginContextImpl(
        String getPluginId,
        String getPluginName,
        String getPluginVersion,
        Logger getLogger,
        EventManager getEventManager,
        DiscordAPI getDiscordAPI,
        Configuration getConfiguration,
        String getDataFolder,
        PluginLoader getPluginLoader,
        ClassLoader getClassLoader,
        LanguageManager getLanguageManager,
        DataStorageManager getDataStorageManager,
        BinaryStorageManager getBinaryStorageManager,
        PermissionManager getPermissionManager,
        AudioService getAudioService,
        CommandService getCommandService,
        PluginCommandAdapter getCommands,
        PluginPermissionAdapter getPermissions,
        PluginLanguageAdapter getLanguage,
        PluginDataStorageAdapter getStorage,
        PluginBinaryStorageAdapter getBinaryStorage
) implements PluginContext {
}