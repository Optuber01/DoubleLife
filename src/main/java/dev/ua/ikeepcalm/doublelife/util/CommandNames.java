package dev.ua.ikeepcalm.doublelife.util;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalises a typed command so restriction checks cannot be bypassed with a
 * namespace prefix ({@code /minecraft:tp}, {@code /essentials:give}) or an alias.
 * Main thread only (reads the server command map).
 */
public final class CommandNames {

    /** Vanilla Brigadier redirects that the Bukkit command map exposes as unrelated commands. */
    private static final Map<String, String> VANILLA_REDIRECTS = Map.of(
            "teleport", "tp",
            "tp", "teleport",
            "tell", "msg",
            "w", "msg");

    private CommandNames() {
    }

    /**
     * @param names every name the typed label resolves to: the label, the resolved command's
     *              name and label, and all of its aliases, lower-cased and namespace-stripped
     * @param args  the arguments after the label
     */
    public record Parsed(Set<String> names, List<String> args) {

        public boolean matchesAny(List<String> candidates) {
            if (candidates == null) {
                return false;
            }
            for (String candidate : candidates) {
                if (candidate != null && names.contains(stripNamespace(candidate.toLowerCase(Locale.ROOT)))) {
                    return true;
                }
            }
            return false;
        }

        public boolean is(String name) {
            return names.contains(name);
        }

        public String firstArg() {
            return args.isEmpty() ? null : args.getFirst();
        }
    }

    /** Parses a {@code PlayerCommandPreprocessEvent} message (with or without the leading slash). */
    public static Parsed parse(String message) {
        String text = message == null ? "" : message.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String[] parts = text.split("\\s+");
        String rawLabel = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String label = stripNamespace(rawLabel);
        List<String> args = parts.length <= 1 ? List.of() : Arrays.asList(parts).subList(1, parts.length);
        return new Parsed(resolveNames(rawLabel, label), List.copyOf(args));
    }

    private static Set<String> resolveNames(String rawLabel, String label) {
        Set<String> names = new LinkedHashSet<>();
        names.add(label);
        Command command = lookup(rawLabel);
        if (command == null && !rawLabel.equals(label)) {
            command = lookup(label);
        }
        if (command != null) {
            names.add(stripNamespace(command.getName().toLowerCase(Locale.ROOT)));
            names.add(stripNamespace(command.getLabel().toLowerCase(Locale.ROOT)));
            for (String alias : command.getAliases()) {
                names.add(stripNamespace(alias.toLowerCase(Locale.ROOT)));
            }
        }
        for (String name : Set.copyOf(names)) {
            String redirect = VANILLA_REDIRECTS.get(name);
            if (redirect != null) {
                names.add(redirect);
            }
        }
        names.remove("");
        return Set.copyOf(names);
    }

    private static Command lookup(String label) {
        if (label.isEmpty()) {
            return null;
        }
        try {
            CommandMap map = Bukkit.getCommandMap();
            return map.getCommand(label);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static String stripNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 && colon < label.length() - 1 ? label.substring(colon + 1) : label;
    }
}
