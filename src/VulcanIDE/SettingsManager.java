package VulcanIDE;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent IDE settings stored as JSON in the Vulcan home folder. */
public class SettingsManager {

    private static final Path SETTINGS_PATH = resolveSettingsPath();
    private static final Path PRESETS_DIRECTORY = SETTINGS_PATH.getParent().resolve("presets");
    private static final int SETTINGS_VERSION = 1;
    private static final Map<String, String> COLOR_ALIASES = Map.ofEntries(
            Map.entry("callJump", "color1"), Map.entry("stack", "color2"),
            Map.entry("immediate", "color3"), Map.entry("escape", "color4"),
            Map.entry("string", "color5"), Map.entry("register", "color6"),
            Map.entry("directive", "color7"), Map.entry("instruction", "color8"),
            Map.entry("comment", "color9"), Map.entry("label", "color10"),
            Map.entry("equate", "color11"), Map.entry("section", "color12")
    );

    private static Path resolveSettingsPath() {
        String configuredHome = System.getProperty("vulcan.home");
        Path base = configuredHome == null || configuredHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".vulcan")
                : Path.of(configuredHome);
        return base.resolve("settings.json").toAbsolutePath().normalize();
    }

    private static Path legacySettingsPath() {
        return Path.of(System.getProperty("user.dir"), "settings.json").toAbsolutePath().normalize();
    }

    private boolean darkMode = true;
    private String theme = Themes.VULCAN_DARK;
    private boolean syntaxHighlighting = true;
    private final Map<String, Integer> fontSizes = new LinkedHashMap<>();
    private String editorFont = java.awt.Font.MONOSPACED;
    private String applicationFont = "Segoe UI";
    private Path currentDirectory;
    private Path debuggerDirectory;
    private Path displayDirectory;
    private Path currentFile;
    private Path terminalWorkingDirectory;
    private final List<Path> openFiles = new ArrayList<>();
    private final Map<Path, Integer> fileFontSizes = new LinkedHashMap<>();
    private int selectedMainTab = 0;
    private int selectedEditorTab = 0;
    private int vramBytes = Intel8080.cpu.Memory.DEFAULT_VRAM_BYTES;
    public synchronized int getVramBytes() { return vramBytes; }
    public synchronized void setVramBytes(int bytes) { if(bytes<1024 || bytes>256*1024*1024)throw new IllegalArgumentException("VRAM size must be 1 KiB to 256 MiB.");vramBytes=bytes;save(); }
    private String displayPreset = "16x16 Video 2-bit";
    private Rectangle windowBounds;
    private boolean windowFullscreen;
    private String windowDeviceId = "";
    private int filePanelDividerLocation = 230;
    private int consoleDividerLocation = 600;
    private final Map<Path, Point> fileScrollPositions = new LinkedHashMap<>();
    private boolean settingsEditing;
    private boolean loadFailed;
    private final Map<String, Color> colors = new LinkedHashMap<>();

    /** Creates settings with default values; call load to read the saved file. */
    public SettingsManager() {
        Path defaultDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        currentDirectory = defaultDirectory;
        terminalWorkingDirectory = defaultDirectory;
        loadDefaultFontSizes();
        loadDefaultColors();
    }

    private void loadDefaultFontSizes() {
        fontSizes.put("application", 15);
        fontSizes.put("applicationTitle", 15);
        fontSizes.put("editor", 16);
        fontSizes.put("editorFilePanel", 15);
        fontSizes.put("editorFilePanelTitle", 14);
        fontSizes.put("editorFilePanelPath", 12);
        fontSizes.put("debuggerFilePanel", 15);
        fontSizes.put("debuggerFilePanelTitle", 14);
        fontSizes.put("debuggerFilePanelPath", 12);
        fontSizes.put("displayFilePanel", 15);
        fontSizes.put("displayFilePanelTitle", 14);
        fontSizes.put("displayFilePanelPath", 12);
        fontSizes.put("editorTab", 14);
        fontSizes.put("editorConsole", 15);
        fontSizes.put("displayConsole", 15);
        fontSizes.put("debuggerConsole", 15);
        fontSizes.put("terminal", 15);
        fontSizes.put("debuggerMemory", 15);
        fontSizes.put("debuggerInstructions", 15);
        fontSizes.put("debuggerRegisters", 17);
        fontSizes.put("debuggerFilename", 15);
        fontSizes.put("displayFilename", 15);
    }

    private void loadDefaultColors() {

        colors.put("cKeyword", new Color(197,134,192));
        colors.put("cPrimitive", new Color(86,156,214));
        colors.put("cType", new Color(78,201,176));
        colors.put("cNumber", new Color(181,206,168));
        colors.put("cMacro", new Color(197,134,192));
        colors.put("cFunction", new Color(220,220,170));
        colors.put("cVariable", new Color(156,220,254));
        colors.put("cString", new Color(206,145,120));
        colors.put("cCharacter", new Color(206,145,120));
        colors.put("cComment", new Color(106,153,85));
        colors.put("function", new Color(220,220,170));
        colors.put("variable", new Color(156,220,254));
        colors.put("primitive", new Color(86,156,214));
        colors.put("character", new Color(215,186,125));
        colors.put("applicationBackground", new Color(38, 41, 46));
        colors.put("panelBackground", new Color(46, 49, 55));
        colors.put("inputBackground", new Color(31, 34, 39));
        colors.put("text", new Color(225, 228, 235));
        colors.put("editorBackground", new Color(18, 20, 23));
        colors.put("editorText", new Color(225, 228, 235));
        colors.put("tabSelected", new Color(62, 66, 74));
        colors.put("tabText", new Color(235, 238, 244));
        colors.put("tabTextMuted", new Color(160, 166, 176));
        colors.put("fileSelectionBackground", new Color(68, 73, 82));
        colors.put("fileSelectionText", new Color(248, 249, 252));
        colors.put("splitDivider", new Color(72, 76, 84));
        colors.put("color1", new Color(255, 100, 100));
        colors.put("color2", new Color(255, 215, 75));
        colors.put("color3", new Color(72, 195, 225));
        colors.put("color4", new Color(65, 210, 235));
        colors.put("color5", new Color(85, 220, 135));
        colors.put("color6", new Color(92, 165, 255));
        colors.put("color7", new Color(195, 125, 255));
        colors.put("color8", new Color(255, 160, 70));
        colors.put("color9", new Color(125, 135, 145));
        colors.put("color10", colors.get("editorText"));
        colors.put("color11", new Color(175, 220, 120));
        colors.put("color12", new Color(255, 105, 180));
        colors.put("lineNumber", new Color(133, 139, 150));
        colors.put("equate", new Color(175, 220, 120));
        colors.put("stopButton", new Color(192, 54, 64));
        colors.put("currentInstruction", new Color(52, 72, 100));
    }

    /** Loads saved settings, migrating older formats; returns defaults when the file is missing or invalid. */
    public static SettingsManager load() {
        SettingsManager settings = new SettingsManager();

        try {
            if (!Files.exists(SETTINGS_PATH)) {
                Path legacyPath = legacySettingsPath();
                if (!legacyPath.equals(SETTINGS_PATH) && Files.isRegularFile(legacyPath)) {
                    Path parent = SETTINGS_PATH.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(legacyPath, SETTINGS_PATH);
                }
            }

            if (!Files.exists(SETTINGS_PATH)) {
                settings.migrateLegacyPresets();
                settings.ensurePresetsDirectory();
                settings.save();
                return settings;
            }

            String json = Files.readString(SETTINGS_PATH, StandardCharsets.UTF_8);
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            if (!hasValidJsonObject(json)) {
                settings.loadFailed = true;
                System.err.println("Vulcan: couldn't load settings (the settings file is not valid JSON).");
                return settings;
            }
            String lookJson = extractJsonObject(json, "look");
            String tabsJson = extractJsonObject(json, "tabs");
            String layoutJson = extractJsonObject(json, "layout");
            String windowJson = extractJsonObject(json, "window");
            String pathsJson = extractJsonObject(json, "paths");
            String colorsJson = extractJsonObject(json, "colors");
            settings.darkMode = readBoolean(sectionFor(lookJson, json, "darkMode"), "darkMode", settings.darkMode);
            String savedTheme = readString(sectionFor(lookJson, json, "theme"), "theme");
            settings.theme = savedTheme != null ? savedTheme : settings.darkMode ? Themes.VULCAN_DARK : Themes.VULCAN_LIGHT;
            settings.syntaxHighlighting = readBoolean(sectionFor(lookJson, json, "syntaxHighlighting"), "syntaxHighlighting", settings.syntaxHighlighting);
            String fontSizesJson = extractJsonObject(json, "fontSizes");
            for (String name : settings.fontSizes.keySet()) {
                int fallback = settings.fontSizes.get(name);
                int saved = name.equals("editor")
                        ? readInteger(fontSizesJson, name, readInteger(json, "editorFontSize", fallback))
                        : readInteger(fontSizesJson, name, fallback);
                settings.fontSizes.put(name, clampFontSize(saved));
            }
            settings.vramBytes = Math.max(1024, Math.min(256*1024*1024, readInteger(json, "vramBytes", Intel8080.cpu.Memory.DEFAULT_VRAM_BYTES)));
            settings.selectedMainTab = Math.max(0, readInteger(sectionFor(tabsJson, json, "selectedMainTab"), "selectedMainTab", 0));
            settings.selectedEditorTab = Math.max(0, readInteger(sectionFor(tabsJson, json, "selectedEditorTab"), "selectedEditorTab", 0));
            if (windowJson != null) {
                int x = readInteger(windowJson, "x", Integer.MIN_VALUE);
                int y = readInteger(windowJson, "y", Integer.MIN_VALUE);
                int width = readInteger(windowJson, "width", 0);
                int height = readInteger(windowJson, "height", 0);
                if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE &&
                        width >= 320 && height >= 240 && width <= 20000 && height <= 20000) {
                    settings.windowBounds = new Rectangle(x, y, width, height);
                }
                settings.windowFullscreen = readBoolean(windowJson, "fullscreen", false);
                String deviceId = readString(windowJson, "device");
                if (deviceId != null) settings.windowDeviceId = deviceId;
            }
            if (layoutJson != null) {
                settings.filePanelDividerLocation = Math.max(80, Math.min(5000,
                        readInteger(layoutJson, "filePanelDivider", settings.filePanelDividerLocation)));
                settings.consoleDividerLocation = Math.max(120, Math.min(5000,
                        readInteger(layoutJson, "consoleDivider", settings.consoleDividerLocation)));
            }
            String savedPreset = readString(sectionFor(tabsJson, json, "displayPreset"), "displayPreset");
            if (savedPreset != null && !savedPreset.isBlank()) settings.displayPreset = savedPreset;

            String fontFamily = readString(sectionFor(lookJson, json, "fontFamily"), "fontFamily");
            if (fontFamily != null && !fontFamily.isBlank()) settings.editorFont = fontFamily;
            String editorFont = readString(sectionFor(lookJson, json, "editorFont"), "editorFont");
            if (editorFont != null && !editorFont.isBlank()) settings.editorFont = editorFont;
            String applicationFont = readString(sectionFor(lookJson, json, "applicationFont"), "applicationFont");
            if (applicationFont != null && !applicationFont.isBlank()) settings.applicationFont = applicationFont;

            for (String name : settings.colors.keySet()) {
                String legacyName = legacyColorName(name);
                Color color = hasRgb(colorsJson, name) ? readRgb(colorsJson, name, settings.colors.get(name))
                        : hasRgb(colorsJson, legacyName) ? readRgb(colorsJson, legacyName, settings.colors.get(name))
                        : hasRgb(json, name) ? readRgb(json, name, settings.colors.get(name))
                        : readRgb(json, legacyName, settings.colors.get(name));
                settings.colors.put(name, color);
            }
            if (readInteger(json, "settingsVersion", 0) < SETTINGS_VERSION &&
                    (hasRgb(colorsJson, "immediate") || hasRgb(json, "immediate")) &&
                    (hasRgb(colorsJson, "register") || hasRgb(json, "register"))) {
                settings.swapNumberAndRegisterColors();
            }

            if (!settings.darkMode) settings.applyLightPalette();

            Path directory = readPath(sectionFor(pathsJson, json, "fileProjectDirectory"), "fileProjectDirectory");
            if (directory == null) directory = readPath(sectionFor(pathsJson, json, "currentDirectory"), "currentDirectory");
            if (directory != null && Files.isDirectory(directory)) settings.currentDirectory = directory;
            Path debuggerDirectory = readPath(sectionFor(pathsJson, json, "debuggerProjectDirectory"), "debuggerProjectDirectory");
            if (debuggerDirectory != null && Files.isDirectory(debuggerDirectory)) settings.debuggerDirectory = debuggerDirectory;
            Path displayDirectory = readPath(sectionFor(pathsJson, json, "displayProjectDirectory"), "displayProjectDirectory");
            if (displayDirectory != null && Files.isDirectory(displayDirectory)) settings.displayDirectory = displayDirectory;

            Path currentFile = readStoredPath(sectionFor(pathsJson, json, "currentFile"), "currentFile", settings.currentDirectory);
            if (currentFile != null && Files.isRegularFile(currentFile)) {
                settings.currentFile = currentFile;
                Path parent = currentFile.getParent();
                if (directory == null && parent != null && Files.isDirectory(parent)) settings.currentDirectory = parent;
            }

            String openFilesText = readString(sectionFor(pathsJson, json, "openFiles"), "openFiles");
            if (openFilesText != null) {
                for (String value : openFilesText.split("\\|", -1)) {
                    if (value.isBlank()) continue;
                    try {
                        Path candidate = Path.of(value);
                        if (!candidate.isAbsolute()) candidate = settings.currentDirectory.resolve(candidate);
                        candidate = candidate.toAbsolutePath().normalize();
                        if (Files.isRegularFile(candidate)) settings.openFiles.add(candidate);
                    } catch (RuntimeException ignored) {

                    }
                }
            }

            settings.loadFileFontSizes(json, fontSizesJson);
            settings.loadFileScrollPositions(json);
            Path terminalDirectory = readPath(sectionFor(pathsJson, json, "terminalWorkingDirectory"), "terminalWorkingDirectory");
            if (terminalDirectory != null && Files.isDirectory(terminalDirectory)) {
                settings.terminalWorkingDirectory = terminalDirectory;
            } else {
                settings.terminalWorkingDirectory = settings.currentDirectory;
            }
        } catch (IOException error) {
            settings.loadFailed = true;
            System.err.println("Vulcan: couldn't load settings (" + VulcanDialog.errorSummary(error) + ").");
            return settings;
        }

        settings.migrateLegacyPresets();
        settings.ensurePresetsDirectory();
        settings.save();
        return settings;
    }

    private void migrateLegacyPresets() {
        Path imported = PRESETS_DIRECTORY.resolve(".legacy-imported");
        if (Files.exists(imported)) return;
        Path legacyDirectory = Path.of(System.getProperty("user.dir"), "presets").toAbsolutePath().normalize();
        if (legacyDirectory.equals(PRESETS_DIRECTORY) || !Files.isDirectory(legacyDirectory)) return;
        try {
            Files.createDirectories(PRESETS_DIRECTORY);
            try (var entries = Files.list(legacyDirectory)) {
                for (Path source : entries.toList()) {
                    if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                            !source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".preset")) continue;
                    Path target = PRESETS_DIRECTORY.resolve(source.getFileName().toString());
                    if (!Files.exists(target)) Files.copy(source, target);
                }
            }

            Files.writeString(imported, "Legacy presets imported.\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Vulcan: couldn't import presets (" + VulcanDialog.errorSummary(e) + ").");
        }
    }

    /** Returns whether one complete JSON object is safe to read and normalize as settings. */
    private static boolean hasValidJsonObject(String json) {
        if (json == null) return false;
        String value = json;
        if (value.startsWith("\uFEFF")) value = value.substring(1);
        return new JsonValidator(value).isObjectDocument();
    }

    /** Validates the whole settings document before structural direct-member readers inspect it. */
    private static final class JsonValidator {
        private static final int MAX_NESTING_DEPTH = 64;
        private final String input;
        private int index;

        /** Retains the immutable source text for a single validation pass. */
        private JsonValidator(String input) {
            this.input = input;
        }

        /** Requires a root object and rejects non-whitespace content after it. */
        private boolean isObjectDocument() {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != '{' || !parseObject(1)) return false;
            skipWhitespace();
            return index == input.length();
        }

        /** Validates one JSON value and dispatches containers recursively. */
        private boolean parseValue(int containerDepth) {
            skipWhitespace();
            if (index >= input.length()) return false;
            return switch (input.charAt(index)) {
                case '{' -> parseObject(containerDepth + 1);
                case '[' -> parseArray(containerDepth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true");
                case 'f' -> parseLiteral("false");
                case 'n' -> parseLiteral("null");
                default -> parseNumber();
            };
        }

        /** Requires quoted keys, colons, values, and comma-separated members. */
        private boolean parseObject(int depth) {
            if (depth > MAX_NESTING_DEPTH) return false;
            index++;
            skipWhitespace();
            if (take('}')) return true;
            while (index < input.length()) {
                if (input.charAt(index) != '"' || !parseString()) return false;
                skipWhitespace();
                if (!take(':') || !parseValue(depth)) return false;
                skipWhitespace();
                if (take('}')) return true;
                if (!take(',')) return false;
                skipWhitespace();
            }
            return false;
        }

        /** Requires comma-separated values and rejects trailing commas. */
        private boolean parseArray(int depth) {
            if (depth > MAX_NESTING_DEPTH) return false;
            index++;
            skipWhitespace();
            if (take(']')) return true;
            while (index < input.length()) {
                if (!parseValue(depth)) return false;
                skipWhitespace();
                if (take(']')) return true;
                if (!take(',')) return false;
                skipWhitespace();
            }
            return false;
        }

        /** Validates JSON escapes and rejects unescaped control characters. */
        private boolean parseString() {
            if (!take('"')) return false;
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') return true;
                if (current < 0x20) return false;
                if (current != '\\') continue;
                if (index >= input.length()) return false;
                char escaped = input.charAt(index++);
                if ("\"\\/bfnrt".indexOf(escaped) >= 0) continue;
                if (escaped != 'u' || index + 4 > input.length()) return false;
                for (int digit = 0; digit < 4; digit++) {
                    if (!isHexDigit(input.charAt(index++))) return false;
                }
            }
            return false;
        }

        /** Accepts only the lowercase true, false, and null literals. */
        private boolean parseLiteral(String literal) {
            if (!input.startsWith(literal, index)) return false;
            index += literal.length();
            return true;
        }

        /** Validates JSON integer, fraction, and exponent syntax, including leading-zero boundaries. */
        private boolean parseNumber() {
            int start = index;
            if (take('-') && index >= input.length()) return false;
            if (take('0')) {
                if (index < input.length() && isDigit(input.charAt(index))) return false;
            } else if (index < input.length() && input.charAt(index) >= '1' && input.charAt(index) <= '9') {
                while (index < input.length() && isDigit(input.charAt(index))) index++;
            } else {
                return false;
            }
            if (take('.')) {
                int fractionStart = index;
                while (index < input.length() && isDigit(input.charAt(index))) index++;
                if (fractionStart == index) return false;
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                int exponentStart = index;
                while (index < input.length() && isDigit(input.charAt(index))) index++;
                if (exponentStart == index) return false;
            }
            return index > start;
        }

        /** Consumes one expected delimiter without advancing on mismatch. */
        private boolean take(char expected) {
            if (index >= input.length() || input.charAt(index) != expected) return false;
            index++;
            return true;
        }

        /** Skips only the four whitespace characters allowed by JSON. */
        private void skipWhitespace() {
            while (index < input.length()) {
                char current = input.charAt(index);
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') return;
                index++;
            }
        }

        /** Matches ASCII digits required by the JSON numeric grammar. */
        private boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        /** Matches ASCII hexadecimal digits required by JSON Unicode escapes. */
        private boolean isHexDigit(char value) {
            return isDigit(value) || value >= 'a' && value <= 'f' || value >= 'A' && value <= 'F';
        }
    }

    private void loadFileFontSizes(String json, String fontSizesJson) {
        fileFontSizes.clear();
        String sectionJson = extractJsonObject(fontSizesJson, "editorFiles");
        if (sectionJson == null) sectionJson = extractJsonObject(json, "fileFontSizes");
        if (sectionJson == null) return;
        for (Map.Entry<String, String> entry : jsonObjectEntries(sectionJson)) {
            try {
                Path path = Path.of(entry.getKey());
                if (!path.isAbsolute()) path = currentDirectory.resolve(path);
                path = path.toAbsolutePath().normalize();
                String sizeText = entry.getValue();
                if (sizeText.startsWith("-") || !Pattern.matches("(0|[1-9][0-9]*)", sizeText)) continue;
                int size = clampFontSize(Integer.parseInt(sizeText));
                if (size != getEditorFontSize() && openFiles.contains(path)) fileFontSizes.put(path, size);
            } catch (RuntimeException ignored) {

            }
        }
    }

    private void loadFileScrollPositions(String json) {
        fileScrollPositions.clear();
        String sectionJson = extractJsonObject(json, "fileScrollPositions");
        if (sectionJson == null) return;
        for (Map.Entry<String, String> entry : jsonObjectEntries(sectionJson)) {
            try {
                Matcher position = Pattern.compile("\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").matcher(entry.getValue());
                if (!position.matches()) continue;
                Path path = Path.of(entry.getKey());
                if (!path.isAbsolute()) path = currentDirectory.resolve(path);
                path = path.toAbsolutePath().normalize();
                if (openFiles.contains(path)) {
                    fileScrollPositions.put(path, new Point(
                            Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2))));
                }
            } catch (RuntimeException ignored) {

            }
        }
    }
    private static String sectionFor(String sectionJson, String rootJson, String name) {
        return jsonMemberValue(sectionJson, name) == null ? rootJson : sectionJson;
    }

    private static String jsonMemberValue(String objectJson, String name) {
        for (Map.Entry<String, String> entry : jsonObjectEntries(objectJson)) {
            if (entry.getKey().equals(name)) return entry.getValue();
        }
        return null;
    }

    private static List<Map.Entry<String, String>> jsonObjectEntries(String objectJson) {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        if (objectJson == null) return entries;
        int cursor = skipJsonWhitespace(objectJson, 0);
        if (cursor >= objectJson.length() || objectJson.charAt(cursor++) != '{') return entries;
        while (cursor < objectJson.length()) {
            cursor = skipJsonWhitespace(objectJson, cursor);
            if (cursor >= objectJson.length() || objectJson.charAt(cursor) == '}') return entries;
            int keyEnd = jsonStringEnd(objectJson, cursor);
            if (keyEnd < 0) return entries;
            String key = unescape(objectJson.substring(cursor + 1, keyEnd - 1));
            cursor = skipJsonWhitespace(objectJson, keyEnd);
            if (cursor >= objectJson.length() || objectJson.charAt(cursor++) != ':') return entries;
            cursor = skipJsonWhitespace(objectJson, cursor);
            int valueEnd = jsonValueEnd(objectJson, cursor);
            if (valueEnd < 0) return entries;
            entries.add(Map.entry(key, objectJson.substring(cursor, valueEnd)));
            cursor = skipJsonWhitespace(objectJson, valueEnd);
            if (cursor >= objectJson.length() || objectJson.charAt(cursor++) != ',') return entries;
        }
        return entries;
    }

    private static int jsonStringEnd(String json, int start) {
        if (start >= json.length() || json.charAt(start) != '"') return -1;
        for (int cursor = start + 1; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (current == '\\') cursor++;
            else if (current == '"') return cursor + 1;
        }
        return -1;
    }

    private static int jsonValueEnd(String json, int start) {
        if (start >= json.length()) return -1;
        char first = json.charAt(start);
        if (first == '"') return jsonStringEnd(json, start);
        if (first != '{' && first != '[') {
            int cursor = start;
            while (cursor < json.length() && !isJsonDelimiter(json.charAt(cursor))) cursor++;
            return cursor;
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int cursor = start; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{' || current == '[') depth++;
            else if (current == '}' || current == ']') {
                if (--depth == 0) return cursor + 1;
            }
        }
        return -1;
    }

    private static boolean isJsonDelimiter(char value) {
        return value == ',' || value == '}' || value == ']' || value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    private static int skipJsonWhitespace(String json, int start) {
        while (start < json.length()) {
            char current = json.charAt(start);
            if (current != ' ' && current != '\t' && current != '\n' && current != '\r') break;
            start++;
        }
        return start;
    }

    private static boolean readBoolean(String json, String name, boolean fallback) {
        String value = jsonMemberValue(json, name);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        return fallback;
    }

    private static int readInteger(String json, String name, int fallback) {
        return parseJsonInteger(jsonMemberValue(json, name), fallback);
    }

    private static int parseJsonInteger(String value, int fallback) {
        if (value == null || !Pattern.matches("-?(0|[1-9][0-9]*)", value)) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String extractJsonObject(String json, String name) {
        String value = jsonMemberValue(json, name);
        return value != null && value.startsWith("{") && value.endsWith("}") ? value : null;
    }

    private static int clampFontSize(int size) {
        return Math.max(8, Math.min(48, size));
    }

    private static String readString(String json, String name) {
        String value = jsonMemberValue(json, name);
        return value != null && value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                ? unescape(value.substring(1, value.length() - 1)) : null;
    }

    private static String legacyColorName(String key) {
        for (Map.Entry<String, String> entry : COLOR_ALIASES.entrySet()) {
            if (entry.getValue().equals(key)) return entry.getKey();
        }
        return key;
    }

    private static Color readRgb(String json, String name, Color fallback) {
        String value = jsonMemberValue(json, name);
        Matcher matcher = Pattern.compile("\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").matcher(value == null ? "" : value);
        if (!matcher.matches()) return fallback;
        try {
            int r = Integer.parseInt(matcher.group(1));
            int g = Integer.parseInt(matcher.group(2));
            int b = Integer.parseInt(matcher.group(3));
            if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return fallback;
            return new Color(r, g, b);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean hasRgb(String json, String name) {
        String value = jsonMemberValue(json, name);
        return value != null && Pattern.matches("\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]", value);
    }

    private static Path readPath(String json, String name) {
        String value = readString(json, name);
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path readStoredPath(String json, String name, Path base) {
        String value = readString(json, name);
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value);
            return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
        } catch (Exception e) { return null; }
    }

    /** Encodes string data with standard JSON escapes, including otherwise-invalid control characters. */
    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (char current : value.toCharArray()) {
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    else result.append(current);
                }
            }
        }
        return result.toString();
    }

    /** Decodes standard JSON string escapes from already syntax-validated settings text. */
    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                result.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    result.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    /** Writes settings atomically to disk. */
    public synchronized void save() {
        if (loadFailed) return;
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"settingsVersion\": ").append(SETTINGS_VERSION).append(",\n");
        json.append("  \"look\": {\n");
        json.append("    \"darkMode\": ").append(darkMode).append(",\n");
        json.append("    \"theme\": \"").append(escape(theme)).append("\",\n");
        json.append("    \"syntaxHighlighting\": ").append(syntaxHighlighting).append(",\n");
        json.append("    \"fontFamily\": \"").append(escape(editorFont)).append("\",\n");
        json.append("    \"editorFont\": \"").append(escape(editorFont)).append("\",\n");
        json.append("    \"applicationFont\": \"").append(escape(applicationFont)).append("\"\n");
        json.append("  },\n");
        appendFontSizes(json);
        json.append("  \"vramBytes\": ").append(vramBytes).append(",\n");
        json.append("  \"tabs\": {\n");
        json.append("    \"selectedMainTab\": ").append(selectedMainTab).append(",\n");
        json.append("    \"selectedEditorTab\": ").append(selectedEditorTab).append(",\n");
        json.append("    \"displayPreset\": \"").append(escape(displayPreset)).append("\"\n");
        json.append("  },\n");
        json.append("  \"layout\": {\"filePanelDivider\": ").append(filePanelDividerLocation)
                .append(", \"consoleDivider\": ").append(consoleDividerLocation).append("},\n");
        json.append("  \"window\": ");
        if (windowBounds == null) {
            json.append("null,\n");
        } else {
            json.append("{\"x\": ").append(windowBounds.x)
                    .append(", \"y\": ").append(windowBounds.y)
                    .append(", \"width\": ").append(windowBounds.width)
                    .append(", \"height\": ").append(windowBounds.height)
                    .append(", \"fullscreen\": ").append(windowFullscreen)
                    .append(", \"device\": \"").append(escape(windowDeviceId)).append("\"},\n");
        }
        appendFileScrollPositions(json);
        json.append("  \"paths\": {\n");
        json.append("    \"fileProjectDirectory\": "); appendPath(json, currentDirectory); json.append(",\n");
        json.append("    \"debuggerProjectDirectory\": "); appendPath(json, getProjectDirectory("debugger")); json.append(",\n");
        json.append("    \"displayProjectDirectory\": "); appendPath(json, getProjectDirectory("display")); json.append(",\n");
        json.append("    \"terminalWorkingDirectory\": "); appendPath(json, terminalWorkingDirectory); json.append(",\n");
        json.append("    \"currentFile\": "); appendStoredPath(json, currentFile, currentDirectory); json.append(",\n");
        json.append("    \"openFiles\": \"");
        List<String> relative = new ArrayList<>();
        for (Path path : openFiles) {
            try { relative.add(currentDirectory.relativize(path.toAbsolutePath().normalize()).toString()); }
            catch (Exception e) { relative.add(path.toAbsolutePath().normalize().toString()); }
        }
        json.append(escape(String.join("|", relative))).append("\"\n");
        json.append("  },\n");
        json.append("  \"colors\": {\n");
        int index = 0;
        for (String name : colors.keySet()) {
            Color color = colors.get(name);
            json.append("    \"").append(name).append("\": [")
                    .append(color.getRed()).append(", ").append(color.getGreen()).append(", ").append(color.getBlue()).append("]")
                    .append(++index == colors.size() ? "\n" : ",\n");
        }
        json.append("  }\n");
        json.append("}\n");
        Path temporary = null;
        try {
            Path parent = SETTINGS_PATH.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "settings-", ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, SETTINGS_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, SETTINGS_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Vulcan: couldn't save settings to " + SETTINGS_PATH + " (" + VulcanDialog.errorSummary(e) + ").");
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private void appendFontSizes(StringBuilder json) {
        json.append("  \"fontSizes\": {\n");
        for (Map.Entry<String, Integer> entry : fontSizes.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": ")
                    .append(entry.getValue()).append(",\n");
        }
        json.append("    \"editorFiles\": {");
        boolean first = true;
        for (Path path : openFiles) {
            Integer size = fileFontSizes.get(path.toAbsolutePath().normalize());
            if (size == null || size == getEditorFontSize()) continue;
            if (first) json.append("\n");
            else json.append(",\n");
            String key;
            try { key = currentDirectory.relativize(path.toAbsolutePath().normalize()).toString(); }
            catch (Exception e) { key = path.toAbsolutePath().normalize().toString(); }
            json.append("      \"").append(escape(key)).append("\": ").append(size);
            first = false;
        }
        if (!first) json.append("\n    }\n");
        else json.append("}\n");
        json.append("  },\n");
    }

    private void appendFileScrollPositions(StringBuilder json) {
        json.append("  \"fileScrollPositions\": {");
        boolean first = true;
        for (Path path : openFiles) {
            Point position = fileScrollPositions.get(path.toAbsolutePath().normalize());
            if (position == null) continue;
            if (first) json.append("\n");
            else json.append(",\n");
            String key;
            try { key = currentDirectory.relativize(path.toAbsolutePath().normalize()).toString(); }
            catch (Exception e) { key = path.toAbsolutePath().normalize().toString(); }
            json.append("    \"").append(escape(key)).append("\": [")
                    .append(Math.max(0, position.x)).append(", ").append(Math.max(0, position.y)).append("]");
            first = false;
        }
        if (!first) json.append("\n  }");
        else json.append("}");
        json.append(",\n");
    }
    private static void appendStoredPath(StringBuilder json, Path path, Path base) {
        if (path == null) { json.append("null"); return; }
        try { json.append('"').append(escape(base.relativize(path.toAbsolutePath().normalize()).toString())).append('"'); }
        catch (Exception e) { appendPath(json, path); }
    }

    private static void appendPath(StringBuilder json, Path path) {
        if (path == null) json.append("null");
        else json.append('"').append(escape(path.toString())).append('"');
    }

    /** Replaces in-memory settings with the saved file. */
    public synchronized void reloadFromDisk() {
        if (!Files.exists(SETTINGS_PATH)) return;
        try {

            SettingsManager fresh = load();
            if (fresh.loadFailed) return;
            this.darkMode = fresh.darkMode;
            this.theme = fresh.theme;
            this.syntaxHighlighting = fresh.syntaxHighlighting;
            this.fontSizes.clear();
            this.fontSizes.putAll(fresh.fontSizes);
            this.editorFont = fresh.editorFont;
            this.applicationFont = fresh.applicationFont;
            this.colors.clear();
            this.colors.putAll(fresh.colors);
            this.currentDirectory = fresh.currentDirectory;
            this.debuggerDirectory = fresh.debuggerDirectory;
            this.displayDirectory = fresh.displayDirectory;
            this.currentFile = fresh.currentFile;
            this.terminalWorkingDirectory = fresh.terminalWorkingDirectory;
            this.openFiles.clear();
            this.openFiles.addAll(fresh.openFiles);
            this.fileFontSizes.clear();
            this.fileFontSizes.putAll(fresh.fileFontSizes);
            this.fileScrollPositions.clear();
            this.fileScrollPositions.putAll(fresh.fileScrollPositions);
            this.filePanelDividerLocation = fresh.filePanelDividerLocation;
            this.consoleDividerLocation = fresh.consoleDividerLocation;
            this.selectedMainTab = fresh.selectedMainTab;
            this.selectedEditorTab = fresh.selectedEditorTab;
            this.displayPreset = fresh.displayPreset;
            this.vramBytes = fresh.vramBytes;
            this.windowBounds = fresh.windowBounds == null ? null : new Rectangle(fresh.windowBounds);
            this.windowFullscreen = fresh.windowFullscreen;
            this.windowDeviceId = fresh.windowDeviceId;
            this.loadFailed = false;
        } catch (Exception error) {
            System.err.println("Vulcan: couldn't reload settings (" + VulcanDialog.errorSummary(error) + ").");
        }
    }

    /** Marks whether the settings file is open for editing. */
    public synchronized void setSettingsEditing(boolean editing) {
        settingsEditing = editing;
    }

    /** Reports whether the settings file is open for editing. */
    public synchronized boolean isSettingsEditing() {
        return settingsEditing;
    }

    /** Reports whether the dark theme is active. */
    public boolean isDarkMode() {
        return darkMode;
    }

    /** Reports whether syntax highlighting is enabled. */
    public boolean isSyntaxHighlighting() {
        return syntaxHighlighting;
    }

    /** Returns the default editor font size. */
    public int getEditorFontSize() {
        return getFontSize("editor");
    }

    /** Returns the font size stored for a UI component key. */
    public synchronized int getFontSize(String component) {
        return component == null ? 15 : fontSizes.getOrDefault(component, 15);
    }

    /** Stores the font size for a UI component key. */
    public synchronized void setFontSize(String component, int size) {
        if (component == null || !fontSizes.containsKey(component)) return;
        fontSizes.put(component, clampFontSize(size));
    }

    /** Returns the source editor font family. */
    public String getEditorFont() {
        return editorFont;
    }

    /** Returns the application UI font family. */
    public String getApplicationFont() {
        return applicationFont;
    }

    /** Returns a named theme color. */
    public Color getColor(String name) {
        return colors.getOrDefault(COLOR_ALIASES.getOrDefault(name, name), colors.get("text"));
    }

    /** Returns the working directory. */
    public Path getCurrentDirectory() {
        return currentDirectory;
    }

    /** Returns the project folder of a view, falling back to the working directory. */
    public synchronized Path getProjectDirectory(String view) {
        if ("debugger".equalsIgnoreCase(view)) return debuggerDirectory == null ? currentDirectory : debuggerDirectory;
        if ("display".equalsIgnoreCase(view)) return displayDirectory == null ? currentDirectory : displayDirectory;
        return currentDirectory;
    }

    /** Stores the project folder of a view. */
    public synchronized void setProjectDirectory(String view, Path directory) {
        if (directory == null) return;
        Path absolute = directory.toAbsolutePath().normalize();
        if ("debugger".equalsIgnoreCase(view)) debuggerDirectory = absolute;
        else if ("display".equalsIgnoreCase(view)) displayDirectory = absolute;
        else currentDirectory = absolute;
        if (!settingsEditing) save();
    }

    /** Returns the selected file, or null. */
    public Path getCurrentFile() {
        return currentFile;
    }

    public Path getTerminalWorkingDirectory() { return terminalWorkingDirectory; }

    public synchronized List<Path> getOpenFiles() { return List.copyOf(openFiles); }

    public synchronized int getFilePanelDividerLocation() { return filePanelDividerLocation; }

    public synchronized int getConsoleDividerLocation() { return consoleDividerLocation; }

    /** Stores the editor layout divider positions. */
    public synchronized void setEditorDividerLocations(int filePanel, int console) {
        filePanelDividerLocation = Math.max(80, Math.min(5000, filePanel));
        consoleDividerLocation = Math.max(120, Math.min(5000, console));
    }

    /** Returns the remembered scroll position of a file. */
    public synchronized Point getFileScrollPosition(Path path) {
        if (path == null) return null;
        Point point = fileScrollPositions.get(path.toAbsolutePath().normalize());
        return point == null ? null : new Point(point);
    }

    /** Remembers the scroll position of a file. */
    public synchronized void setFileScrollPosition(Path path, Point position) {
        if (path == null || position == null) return;
        fileScrollPositions.put(path.toAbsolutePath().normalize(),
                new Point(Math.max(0, position.x), Math.max(0, position.y)));
    }

    /** Returns the saved main-window bounds, or null. */
    public synchronized Rectangle getWindowBounds() {
        return windowBounds == null ? null : new Rectangle(windowBounds);
    }

    /** Stores the main-window bounds. */
    public synchronized void setWindowBounds(Rectangle bounds) {
        if (bounds == null || bounds.width < 320 || bounds.height < 240) return;
        windowBounds = new Rectangle(bounds);
        if (!settingsEditing) save();
    }
    public synchronized boolean isWindowFullscreen() { return windowFullscreen; }
    public synchronized String getWindowDeviceId() { return windowDeviceId; }
    /** Stores the window bounds, fullscreen state, and monitor. */
    public synchronized void setWindowState(Rectangle bounds, boolean fullscreen, String deviceId) {
        if (bounds != null && bounds.width >= 320 && bounds.height >= 240) windowBounds = new Rectangle(bounds);
        windowFullscreen = fullscreen;
        windowDeviceId = deviceId == null ? "" : deviceId;
        if (!settingsEditing) save();
    }

    /** Returns the zoomed font size of a file, or the default for its type. */
    public synchronized int getFileFontSize(Path path) {
        if (path == null) return getEditorFontSize();
        return fileFontSizes.getOrDefault(path.toAbsolutePath().normalize(), getEditorFontSize());
    }

    /** Stores the zoomed font size of a file. */
    public synchronized void setFileFontSize(Path path, int size) {
        if (path == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        int bounded = clampFontSize(size);
        if (bounded == getEditorFontSize()) fileFontSizes.remove(normalized);
        else fileFontSizes.put(normalized, bounded);
    }

    public synchronized int getSelectedMainTab() { return selectedMainTab; }

    public synchronized int getSelectedEditorTab() { return selectedEditorTab; }

    public synchronized String getDisplayPreset() { return displayPreset; }

    public synchronized void setSelectedMainTab(int index) { selectedMainTab = Math.max(0, index); if (!settingsEditing) save(); }

    public synchronized void setSelectedEditorTab(int index) { selectedEditorTab = Math.max(0, index); if (!settingsEditing) save(); }

    public synchronized void setDisplayPreset(String preset) { if (preset != null && !preset.isBlank()) { displayPreset = preset; if (!settingsEditing) save(); } }

    /** Stores the files to reopen at startup. */
    public synchronized void setOpenFiles(List<Path> paths) {
        openFiles.clear();
        if (paths != null) for (Path path : paths) if (path != null) openFiles.add(path.toAbsolutePath().normalize());
        fileFontSizes.keySet().retainAll(openFiles);
        fileScrollPositions.keySet().retainAll(openFiles);
        if (!settingsEditing) save();
    }

    /** Resolves a settings path relative to the working directory. */
    public Path resolveLocalPath(String local) {
        if (local == null || local.isBlank()) return currentDirectory;
        Path p = Path.of(local);
        return (p.isAbsolute() ? p : currentDirectory.resolve(p)).normalize().toAbsolutePath();
    }

    private void applyLightPalette() {
        colors.put("applicationBackground", new Color(238, 240, 243));
        colors.put("panelBackground", new Color(248, 249, 251));
        colors.put("inputBackground", new Color(255, 255, 255));
        colors.put("text", new Color(35, 38, 43));
        colors.put("editorBackground", new Color(255, 255, 255));
        colors.put("editorText", new Color(30, 33, 38));
        colors.put("tabSelected", new Color(225, 229, 235));
        colors.put("tabText", new Color(25, 28, 32));
        colors.put("tabTextMuted", new Color(100, 106, 116));
        colors.put("fileSelectionBackground", new Color(210, 220, 235));
        colors.put("fileSelectionText", new Color(20, 24, 30));
        colors.put("splitDivider", new Color(190, 195, 202));
        colors.put("lineNumber", new Color(105, 112, 122));
    }

    private void swapNumberAndRegisterColors() {
        Color numberColor = colors.get("color3");
        colors.put("color3", colors.get("color6"));
        colors.put("color6", numberColor);
    }

    /** The name of the last theme chosen from Tools > Theme. */
    public synchronized String getTheme() {
        return theme;
    }

    /** Replaces every theme color with the named built-in theme (see Themes) and saves; unknown names are ignored.
        darkMode stays true except for Vulcan Light, because loading a light darkMode re-applies the Vulcan light palette. */
    public synchronized void applyTheme(String name) {
        Map<String, Color> palette = Themes.colors(name);
        if (palette == null && !name.equals(Themes.VULCAN_DARK) && !name.equals(Themes.VULCAN_LIGHT)) return;
        loadDefaultColors();
        darkMode = !name.equals(Themes.VULCAN_LIGHT);
        if (!darkMode) applyLightPalette();
        if (palette != null) colors.putAll(palette);
        theme = name;
        if (!settingsEditing) save();
    }

    /** Switches between dark and light themes. */
    public synchronized void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
        if (!settingsEditing) save();
    }

    /** Enables or disables syntax highlighting. */
    public synchronized void setSyntaxHighlighting(boolean syntaxHighlighting) {
        this.syntaxHighlighting = syntaxHighlighting;
        if (!settingsEditing) save();
    }

    /** Sets the default editor font size. */
    public synchronized void setEditorFontSize(int editorFontSize) {
        setFontSize("editor", editorFontSize);
        if (!settingsEditing) save();
    }

    /** Sets the source editor font family. */
    public synchronized void setEditorFont(String editorFont) {
        if (editorFont != null && !editorFont.isBlank()) {
            this.editorFont = editorFont;
            if (!settingsEditing) save();
        }
    }

    /** Sets the application UI font family. */
    public synchronized void setApplicationFont(String applicationFont) {
        if (applicationFont != null && !applicationFont.isBlank()) {
            this.applicationFont = applicationFont;
            if (!settingsEditing) save();
        }
    }

    /** Sets a named theme color. */
    public synchronized void setColor(String name, Color color) {
        if (name == null || color == null) return;
        String key = COLOR_ALIASES.getOrDefault(name, name);
        if (!colors.containsKey(key)) return;
        colors.put(key, color);
        if (!settingsEditing) save();
    }

    /** Sets the working directory. */
    public synchronized void setCurrentDirectory(Path currentDirectory) {
        if (currentDirectory == null) return;
        this.currentDirectory = currentDirectory.toAbsolutePath().normalize();
        if (debuggerDirectory == null) debuggerDirectory = this.currentDirectory;
        if (displayDirectory == null) displayDirectory = this.currentDirectory;
        if (!settingsEditing) save();
    }

    /** Sets the selected file. */
    public synchronized void setCurrentFile(Path currentFile) {
        this.currentFile = currentFile == null
                ? null
                : currentFile.toAbsolutePath().normalize();
        if (!settingsEditing) save();
    }

    /** Sets the terminal working directory. */
    public synchronized void setTerminalWorkingDirectory(Path terminalWorkingDirectory) {
        if (terminalWorkingDirectory == null) return;
        this.terminalWorkingDirectory = terminalWorkingDirectory.toAbsolutePath().normalize();
        if (!settingsEditing) save();
    }

    /** Returns the display preset folder, creating it when needed. */
    public Path getPresetsDirectory() {
        ensurePresetsDirectory();
        return PRESETS_DIRECTORY;
    }

    private void ensurePresetsDirectory() {
        try { Files.createDirectories(PRESETS_DIRECTORY); }
        catch (IOException error) {
            System.err.println("Vulcan: couldn't create the presets folder (" + VulcanDialog.errorSummary(error) + ").");
        }
    }

    /** Returns the settings file path. */
    public Path getPath() {
        return SETTINGS_PATH.toAbsolutePath();
    }
}
