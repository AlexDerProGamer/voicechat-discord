package dev.naturecodevoid.voicechatdiscord;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import okhttp3.OkHttpClient;
import org.bspfsystems.yamlconfiguration.configuration.InvalidConfigurationException;
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Common code between Paper and Fabric.
 */
public class Common {
    public static final String REPLACE_LEGACY_FORMATTING_CODES = "§([a-z]|[0-9]|[A-Z])";
    private static final List<String> configHeader = List.of(
            "To add a bot, just copy paste the following into bots:",
            "",
            "bots:",
            "- token: DISCORD_BOT_TOKEN_HERE",
            "  vc_id: VOICE_CHANNEL_ID_HERE",
            "",
            "Example for 2 bots:",
            "",
            "bots:",
            "- token: MyFirstBotsToken",
            "  vc_id: 1234567890123456789",
            "- token: MySecondBotsToken",
            "  vc_id: 9876543210987654321",
            "",
            "If you are only using 1 bot, just replace DISCORD_BOT_TOKEN_HERE with your bot's token and replace VOICE_CHANNEL_ID_HERE with the voice channel ID.",
            "",
            "If you are reporting an issue or trying to figure out what's causing an issue, you may find the `debug_level` option helpful.",
            "It will enable debug logging according to the level:",
            "- 0 (or lower): No debug logging",
            "- 1: Some debug logging (mainly logging that won't spam the console but can be helpful)",
            "- 2: Most debug logging (will spam the console but excludes logging that is extremely verbose and usually not helpful)",
            "- 3 (or higher): All debug logging (will spam the console)",
            "",
            "By default, Simple Voice Chat Discord Bridge will check for a new update on server startup. If it finds",
            "a new update, it will always log this to the console, but as long as `alert_ops_of_updates` is true, it",
            "will also tell any operators that there is an update available when they join the server.",
            "We highly recommend you keep `alert_ops_of_updates` on since it is very important that you update the mod/plugin",
            "as soon as updates come out due to bugs popping up randomly.",
            "",
            "For more information on getting everything setup: https://github.com/naturecodevoid/voicechat-discord#readme"
    );
    public static ArrayList<DiscordBot> bots = new ArrayList<>();
    public static VoicechatServerApi api;
    public static Platform platform;
    public static int debugLevel = 0;
    public static boolean alertOpsOfUpdates = true;
    private static YamlConfiguration config;
    public static final List<SubCommands.SubCommand> SUB_COMMANDS = SubCommands.getSubCommands();

    public static void enable() {
        new Thread(UpdateChecker::checkForUpdate).start();
        loadConfig();
    }

    @SuppressWarnings({"DataFlowIssue", "unchecked", "ResultOfMethodCallIgnored"})
    protected static void loadConfig() {
        File configFile = new File(platform.getConfigPath());

        if (!configFile.getParentFile().exists())
            configFile.getParentFile().mkdirs();

        config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException e) {
            platform.debug("IOException when loading config: " + e);
        } catch (InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }

        LinkedHashMap<String, String> defaultBot = new LinkedHashMap<>();
        defaultBot.put("token", "DISCORD_BOT_TOKEN_HERE");
        defaultBot.put("vc_id", "VOICE_CHANNEL_ID_HERE");
        config.addDefault("bots", List.of(defaultBot));

        config.addDefault("alert_ops_of_updates", true);

        config.addDefault("debug_level", 0);

        config.getOptions().setCopyDefaults(true);
        config.getOptions().setHeader(configHeader);
        try {
            config.save(configFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!bots.isEmpty())
            bots = new ArrayList<>();

        for (LinkedHashMap<String, Object> bot : (List<LinkedHashMap<String, Object>>) config.getList("bots")) {
            try {
                bots.add(new DiscordBot((String) bot.get("token"), (Long) bot.get("vc_id")));
            } catch (ClassCastException e) {
                platform.error(
                        "Failed to load a bot. Please make sure that the vc_id property is a valid channel ID.");
            }
        }

        platform.info("Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : ""));

        try {
            alertOpsOfUpdates = (boolean) config.get("alert_ops_of_updates");
            if (!alertOpsOfUpdates)
                platform.info("Operators will not be alerted of new updates. Please make sure you check the console for new updates!");
        } catch (ClassCastException e) {
            platform.error("Please make sure the alert_ops_of_updates option is a valid boolean (true or false)");
        }

        try {
            debugLevel = (int) config.get("debug_level");
            if (debugLevel > 0) platform.info("Debug mode has been set to level " + debugLevel);
        } catch (ClassCastException e) {
            platform.error("Please make sure the debug_level option is a valid integer");
        }
    }

    public static void disable() {
        platform.info("Shutting down " + bots.size() + " bot" + (bots.size() != 1 ? "s" : ""));

        stopBots();

        platform.info("Successfully shutdown " + bots.size() + " bot" + (bots.size() != 1 ? "s" : ""));
    }

    protected static void stopBots() {
        for (DiscordBot bot : bots) {
            bot.stop();
            if (bot.jda == null)
                continue;
            bot.jda.shutdownNow();
            OkHttpClient client = bot.jda.getHttpClient();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    public static void onPlayerJoin(Object rawPlayer) {
        if (UpdateChecker.updateMessage != null) {
            if (platform.isOperator(rawPlayer)) {
                if (alertOpsOfUpdates) {
                    platform.sendMessage(api.fromServerPlayer(rawPlayer), UpdateChecker.updateMessage);
                    platform.debug("Alerted operator of new update");
                } else {
                    platform.debug("Not alerting operator of new update");
                }
            }
        }
    }

    public static void onPlayerLeave(UUID playerUuid) {
        DiscordBot bot = getBotForPlayer(playerUuid);
        if (bot != null) {
            platform.info("Stopping bot");
            bot.stop();
        }
    }

    public static DiscordBot getBotForPlayer(UUID playerUuid) {
        return getBotForPlayer(playerUuid, false);
    }

    public static DiscordBot getBotForPlayer(UUID playerUuid, boolean fallbackToAvailableBot) {
        for (DiscordBot bot : bots) {
            if (bot.player != null)
                if (bot.player.getUuid().compareTo(playerUuid) == 0)
                    return bot;
        }
        if (fallbackToAvailableBot)
            return getAvailableBot();
        return null;
    }

    public static DiscordBot getAvailableBot() {
        for (DiscordBot bot : bots) {
            if (bot.player == null)
                return bot;
        }
        return null;
    }

    private static int @Nullable [] splitVersion(String version) {
        try {
            return Arrays.stream(version.split("\\."))
                    // if there is a -pre we need to remove it
                    .limit(3)
                    .map(str -> str.split("-")[0])
                    .mapToInt(Integer::parseInt)
                    .toArray();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isSVCVersionSufficient(String version) {
        String[] splitVersion = version.split("-");
        int[] parsedVersion = splitVersion(splitVersion[splitVersion.length - 1]);
        platform.debug("parsed version: " + Arrays.toString(parsedVersion));
        int[] parsedMinVersion = Objects.requireNonNull(splitVersion(Constants.VOICECHAT_MIN_VERSION));
        platform.debug("parsed min version: " + Arrays.toString(parsedMinVersion));
        if (parsedVersion != null) {
            for (int i = 0; i < parsedMinVersion.length; i++) {
                int part = parsedMinVersion[i];
                int testPart;
                if (parsedVersion.length > i) {
                    testPart = parsedVersion[i];
                } else {
                    testPart = 0;
                }
                if (testPart < part) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Returns true if the SVC version is not new enough
     */
    public static void checkSVCVersion(@Nullable String version) {
        if (version == null || !isSVCVersionSufficient(version)) {
            String message = "Simple Voice Chat Discord Bridge requires Simple Voice Chat version " + Constants.VOICECHAT_MIN_VERSION + " or later";
            if (version != null) {
                message += " You have version " + version + ".";
            }
            platform.error(message);
            throw new RuntimeException(message);
        }
    }
}
