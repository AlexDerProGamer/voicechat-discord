package dev.naturecodevoid.voicechatdiscord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static dev.naturecodevoid.voicechatdiscord.Common.*;
import static dev.naturecodevoid.voicechatdiscord.GroupManager.*;
import static dev.naturecodevoid.voicechatdiscord.Util.getArgumentOr;

/**
 * Subcommands for /dvc
 */
public class SubCommands {
    @SuppressWarnings("unchecked")
    protected static <S> LiteralArgumentBuilder<S> build(LiteralArgumentBuilder<S> builder) {
        return (LiteralArgumentBuilder<S>) ((LiteralArgumentBuilder<Object>) builder)
                .then(literal("start").executes(wrapInTry(SubCommands::start)))
                .then(literal("stop").executes(wrapInTry(SubCommands::stop)))
                .then(literal("reloadconfig").executes(wrapInTry(SubCommands::reloadConfig)))
                .then(literal("checkforupdate").executes(wrapInTry(SubCommands::checkForUpdate)))
                .then(literal("togglewhisper").executes(wrapInTry(SubCommands::toggleWhisper)))
                .then(literal("group").executes(GroupCommands::help)
                        .then(literal("list").executes(wrapInTry(GroupCommands::list)))
                        .then(literal("create")
                                // Yeah, this is kind of a mess, all because we would have to use a mixin to add a custom ArgumentType
                                // so instead we just use literals for the group type
                                .then(argument("name", string()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                        .then(argument("password", string()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                                .then(literal("normal").executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.NORMAL))))
                                                )
                                                .then(literal("open").executes(wrapInTry(GroupCommands.create(Group.Type.OPEN)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.OPEN))))
                                                )
                                                .then(literal("isolated").executes(wrapInTry(GroupCommands.create(Group.Type.ISOLATED)))
                                                        .then(argument("persistent", bool()).executes(wrapInTry(GroupCommands.create(Group.Type.ISOLATED))))
                                                )
                                        )
                                )
                        )
                        .then(literal("join")
                                .then(argument("id", integer(1)).executes(wrapInTry(GroupCommands::join))
                                        .then(argument("password", string()).executes(wrapInTry(GroupCommands::join)))
                                )
                        )
                        .then(literal("info").executes(wrapInTry(GroupCommands::info)))
                        .then(literal("leave").executes(wrapInTry(GroupCommands::leave)))
                        .then(literal("remove")
                                .then(argument("id", integer(1)).executes(wrapInTry(GroupCommands::remove)))
                        )
                );
    }

    @SuppressWarnings("CallToPrintStackTrace")
    private static Command<Object> wrapInTry(Consumer<CommandContext<?>> function) {
        return (sender) -> {
            try {
                function.accept(sender);
                return 1;
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e); // This way the user will see "An error occurred when running this command"
            }
        };
    }

    private static void start(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "§cYou must be a player to use this command!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid(), true);

        DiscordBot botForPlayer = getBotForPlayer(player.getUuid());
        if (botForPlayer != null) {
            platform.sendMessage(player, "§cYou have already started a voice chat! §eRestarting your session...");
            botForPlayer.stop();
        }

        if (bot == null) {
            platform.sendMessage(
                    player,
                    "§cThere are currently no bots available. You might want to contact your server owner to add more."
            );
            return;
        }

        if (botForPlayer == null)
            platform.sendMessage(
                    player,
                    "§eStarting a voice chat..." + (!bot.hasLoggedIn ? " this might take a moment since we have to login to the bot." : "")
            );

        bot.player = player;
        new Thread(() -> {
            bot.login();
            bot.start();
        }).start();
    }

    private static void stop(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "§cYou must be a player to use this command!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid());
        if (bot == null) {
            platform.sendMessage(player, "§cYou must start a voice chat before you can use this command!");
            return;
        }

        platform.sendMessage(player, "§eStopping the bot...");

        new Thread(() -> {
            bot.stop();

            platform.sendMessage(sender, "§aSuccessfully stopped the bot!");
        }).start();
    }

    private static void reloadConfig(CommandContext<?> sender) {
        if (!platform.isOperator(sender) && !platform.hasPermission(
                sender,
                Constants.RELOAD_CONFIG_PERMISSION
        )) {
            platform.sendMessage(
                    sender,
                    "§cYou must be an operator or have the `" + Constants.RELOAD_CONFIG_PERMISSION + "` permission to use this command!"
            );
            return;
        }

        platform.sendMessage(sender, "§eStopping bots...");

        new Thread(() -> {
            for (DiscordBot bot : bots)
                if (bot.player != null)
                    platform.sendMessage(
                            bot.player,
                            "§cThe config is being reloaded which stops all bots. Please use §f/dvc start §cto restart your session."
                    );
            stopBots();

            platform.sendMessage(sender, "§aSuccessfully stopped bots! §eReloading config...");

            loadConfig();

            platform.sendMessage(
                    sender,
                    "§aSuccessfully reloaded config! Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "") + "."
            );
        }).start();
    }

    private static void checkForUpdate(CommandContext<?> sender) {
        if (!platform.isOperator(sender)) {
            platform.sendMessage(
                    sender,
                    "§cYou must be an operator to use this command!"
            );
            return;
        }

        platform.sendMessage(sender, "§eChecking for update...");

        new Thread(() -> {
            if (UpdateChecker.checkForUpdate())
                platform.sendMessage(sender, Objects.requireNonNullElse(UpdateChecker.updateMessage, "§cNo update found."));
            else
                platform.sendMessage(sender, "§cAn error occurred when checking for updates. Check the console for the error message.");
        }).start();
    }

    private static void toggleWhisper(CommandContext<?> sender) {
        if (!platform.isValidPlayer(sender)) {
            platform.sendMessage(sender, "§cYou must be a player to use this command!");
            return;
        }

        ServerPlayer player = platform.commandContextToPlayer(sender);

        DiscordBot bot = getBotForPlayer(player.getUuid());
        if (bot == null) {
            platform.sendMessage(player, "§cYou must start a voice chat before you can use this command!");
            return;
        }

        boolean whispering = !bot.sender.isWhispering();
        bot.sender.whispering(whispering);

        platform.sendMessage(sender, whispering ? "§aStarted whispering!" : "§aStopped whispering!");
    }

    private static class GroupCommands {
        private static int help(CommandContext<?> sender) {
            platform.sendMessage(
                    sender,
                    """
                            §cAvailable subcommands:
                             - `§f/dvc group list§c`: List groups
                             - `§f/dvc group create <name> [password] [type] [persistent]§c`: Create a group
                             - `§f/dvc group join <ID>§c`: Join a group
                             - `§f/dvc group info§c`: Get info about your current group
                             - `§f/dvc group leave§c`: Leave your current group
                             - `§f/dvc group remove <ID>§c`: Removes a persistent group if there is no one in it
                            See §fhttps://github.com/naturecodevoid/voicechat-discord#dvc-group§c for more info on how to use these commands."""
            );
            return 1;
        }

        private static void list(CommandContext<?> sender) {
            Collection<Group> apiGroups = api.getGroups();

            if (apiGroups.isEmpty())
                platform.sendMessage(sender, "§cThere are currently no groups.");
            else {
                StringBuilder groupsMessage = new StringBuilder("§aGroups:\n");

                for (Group group : apiGroups) {
                    int friendlyId = groupFriendlyIds.get(group.getId());
                    platform.debugVerbose("Friendly ID for " + group.getId() + " (" + group.getName() + ") is " + friendlyId);

                    String playersMessage = "§cNo players";
                    List<ServerPlayer> players = groupPlayers.get(group.getId());
                    if (!players.isEmpty())
                        playersMessage = players.stream().map(player -> platform.getName(player)).collect(Collectors.joining(", "));

                    groupsMessage.append("§a - ")
                            .append(group.getName())
                            .append(" (ID is ")
                            .append(friendlyId)
                            .append("): ")
                            .append(group.hasPassword() ? "§cHas password" : "§aNo password")
                            .append(group.isPersistent() ? "§e, persistent" : "")
                            .append(".§a Group type is ")
                            .append(
                                    group.getType() == Group.Type.NORMAL ? "normal" :
                                            group.getType() == Group.Type.OPEN ? "open" :
                                                    group.getType() == Group.Type.ISOLATED ? "isolated" :
                                                            "unknown"
                            )
                            .append(". Players: ")
                            .append(playersMessage)
                            .append("\n");
                }

                platform.sendMessage(sender, groupsMessage.toString().trim());
            }
        }

        private static Consumer<CommandContext<?>> create(Group.Type type) {
            return (sender) -> {
                if (!platform.isValidPlayer(sender)) {
                    platform.sendMessage(sender, "§cYou must be a player to use this command!");
                    return;
                }

                String name = sender.getArgument("name", String.class);
                String password = getArgumentOr(sender, "password", String.class, null);
                if (password != null)
                    if (password.trim().isEmpty())
                        password = null;
                Boolean persistent = getArgumentOr(sender, "persistent", Boolean.class, false);
                assert persistent != null;

                VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
                if (connection.getGroup() != null) {
                    platform.sendMessage(sender, "§cYou are already in a group!");
                    return;
                }

                Group group = api.groupBuilder()
                        .setName(name)
                        .setPassword(password)
                        .setType(type)
                        .setPersistent(persistent)
                        .build();
                connection.setGroup(group);

                platform.sendMessage(sender, "§aSuccessfully created the group!");
            };
        }

        private static void join(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "§cYou must be a player to use this command!");
                return;
            }

            Integer friendlyId = sender.getArgument("id", Integer.class);
            UUID groupId = groupFriendlyIds.getKey(friendlyId);
            if (groupId == null) {
                platform.sendMessage(sender, "§cInvalid group ID. Please use §f/dvc group list§c to see all groups.");
                return;
            }

            Group group = Objects.requireNonNull(api.getGroup(groupId));
            if (group.hasPassword()) {
                String inputPassword = getArgumentOr(sender, "password", String.class, null);
                if (inputPassword != null)
                    if (inputPassword.trim().isEmpty())
                        inputPassword = null;

                if (inputPassword == null) {
                    platform.sendMessage(sender, "§cThe group has a password, and you have not provided one. Please rerun the command, including the password.");
                    return;
                }

                String groupPassword = getPassword(group);
                if (groupPassword == null) {
                    platform.sendMessage(sender, "§cSince the group has a password, we need to check if the password you supplied is correct. However, we failed to get the password for the group (the server owner can see the error in console). You may need to update Simple Voice Chat Discord Bridge.");
                    return;
                }

                if (!inputPassword.equals(groupPassword)) {
                    platform.sendMessage(sender, "§cThe password you provided is incorrect. You may want to surround the password in quotes if the password has spaces in it.");
                    return;
                }
            }

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            if (connection.getGroup() != null) {
                platform.sendMessage(sender, "§cYou are already in a group! Leave it using §f/dvc group leave§c, then join this group.");
                return;
            }
            if (!connection.isInstalled() && getBotForPlayer(platform.commandContextToPlayer(sender).getUuid()) == null) {
                platform.sendMessage(sender, "§cYou must have the mod installed or start a voice chat before you can use this command!");
                return;
            }
            connection.setGroup(group);

            platform.sendMessage(sender, "§aSuccessfully joined group \"" + group.getName() + "\". Use §f/dvc group info§a to see info on the group, and §f/dvc group leave§a to leave the group.");
        }

        private static void info(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "§cYou must be a player to use this command!");
                return;
            }

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            Group group = connection.getGroup();
            if (group == null) {
                platform.sendMessage(sender, "§cYou are not in a group!");
                return;
            }

            List<ServerPlayer> players = groupPlayers.get(group.getId());
            String playersMessage = players.stream().map(player -> platform.getName(player)).collect(Collectors.joining(", "));
            String message = "§aYou are currently in \"" + group.getName() + "\". It " +
                    (group.hasPassword() ? "§chas a password§a" : "does not have a password") + (group.isPersistent() ? " and is persistent." : ".") +
                    " Group type is " +
                    (group.getType() == Group.Type.NORMAL ? "normal" :
                            group.getType() == Group.Type.OPEN ? "open" :
                                    group.getType() == Group.Type.ISOLATED ? "isolated" :
                                            "unknown") +
                    ". Players: " + playersMessage;

            platform.sendMessage(sender, message);
        }

        private static void leave(CommandContext<?> sender) {
            if (!platform.isValidPlayer(sender)) {
                platform.sendMessage(sender, "§cYou must be a player to use this command!");
                return;
            }

            VoicechatConnection connection = Objects.requireNonNull(api.getConnectionOf(platform.commandContextToPlayer(sender)));
            if (connection.getGroup() == null) {
                platform.sendMessage(sender, "§cYou are not in a group!");
                return;
            }
            connection.setGroup(null);

            platform.sendMessage(sender, "§aSuccessfully left the group.");
        }

        private static void remove(CommandContext<?> sender) {
            Integer friendlyId = sender.getArgument("id", Integer.class);
            UUID groupId = groupFriendlyIds.getKey(friendlyId);
            if (groupId == null) {
                platform.sendMessage(sender, "§cInvalid group ID. Please use §f/dvc group list§c to see all groups.");
                return;
            }

            if (!api.removeGroup(groupId)) {
                platform.sendMessage(sender, "§cCouldn't remove the group. This means it either has players in it or it is not persistent.");
                return;
            }

            platform.sendMessage(sender, "§aSuccessfully removed the group!");
        }
    }
}
