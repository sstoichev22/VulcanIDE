package VulcanIDE;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in color themes listed under Tools > Theme. Vulcan Dark and Vulcan Light are the stock
    SettingsManager palettes; every other theme is a 25-color spec expanded onto the settings color keys. */
final class Themes {
    static final String VULCAN_DARK = "Vulcan Dark";
    static final String VULCAN_LIGHT = "Vulcan Light";

    /** Spec order: app, panel, input, editor, text, muted, selection, divider, lineNumber, highlight,
        keyword, primitive, type, function, variable, string, number, comment, macro,
        red, yellow, orange, green, purple, cyan. */
    private static final Map<String, String[]> SPECS = new LinkedHashMap<>();
    static {
        SPECS.put("Midnight", new String[] {"16161e", "1a1b26", "13141c", "0f1017", "c0caf5", "737aa2", "283457", "2f3549", "565f89", "2a3a63",
                "bb9af7", "7dcfff", "2ac3de", "7aa2f7", "c0caf5", "9ece6a", "ff9e64", "565f89", "f7768e",
                "f7768e", "e0af68", "ff9e64", "9ece6a", "bb9af7", "7dcfff"});
        SPECS.put("Arctic", new String[] {"e5e9f0", "eceff4", "f8fafc", "ffffff", "2e3440", "6b7489", "cfdcec", "c3cbd8", "8a93a6", "dbe7f7",
                "7c3aed", "1d4ed8", "0e7490", "0369a1", "1e293b", "15803d", "c2410c", "64748b", "be185d",
                "dc2626", "a16207", "c2410c", "15803d", "7c3aed", "0891b2"});
        SPECS.put("Cyberpunk", new String[] {"1b1035", "241446", "160b2e", "120826", "f0e9ff", "9d8bc7", "3d1f6e", "4a2a7f", "6c5a9c", "43206f",
                "ff2a6d", "05d9e8", "01ffc3", "fede5d", "e0d6ff", "ff8b39", "ff7edb", "848bbd", "b967ff",
                "fe4450", "fede5d", "ff8b39", "72f1b8", "b967ff", "05d9e8"});
        SPECS.put("Dracula", new String[] {"21222c", "282a36", "1e1f29", "282a36", "f8f8f2", "9ea8c7", "44475a", "44475a", "6272a4", "44475a",
                "ff79c6", "8be9fd", "8be9fd", "50fa7b", "f8f8f2", "f1fa8c", "bd93f9", "6272a4", "ff79c6",
                "ff5555", "f1fa8c", "ffb86c", "50fa7b", "bd93f9", "8be9fd"});
        SPECS.put("Monokai", new String[] {"1e1f1c", "272822", "1e1f1c", "272822", "f8f8f2", "a59f85", "49483e", "3e3d32", "90908a", "3e3d32",
                "f92672", "66d9ef", "66d9ef", "a6e22e", "f8f8f2", "e6db74", "ae81ff", "75715e", "f92672",
                "f92672", "e6db74", "fd971f", "a6e22e", "ae81ff", "66d9ef"});
        SPECS.put("One Dark", new String[] {"21252b", "282c34", "1e2227", "282c34", "abb2bf", "7f848e", "3e4451", "3b4048", "636d83", "2c313c",
                "c678dd", "e5c07b", "e5c07b", "61afef", "e06c75", "98c379", "d19a66", "5c6370", "c678dd",
                "e06c75", "e5c07b", "d19a66", "98c379", "c678dd", "56b6c2"});
        SPECS.put("Nord", new String[] {"2b303b", "2e3440", "272c36", "2e3440", "d8dee9", "9aa5b8", "434c5e", "3b4252", "4c566a", "3b4252",
                "81a1c1", "81a1c1", "8fbcbb", "88c0d0", "d8dee9", "a3be8c", "b48ead", "616e88", "5e81ac",
                "bf616a", "ebcb8b", "d08770", "a3be8c", "b48ead", "88c0d0"});
        SPECS.put("Solarized Dark", new String[] {"00212b", "002b36", "00252f", "002b36", "93a1a1", "657b83", "073642", "094050", "586e75", "073642",
                "859900", "b58900", "b58900", "268bd2", "93a1a1", "2aa198", "d33682", "586e75", "cb4b16",
                "dc322f", "b58900", "cb4b16", "859900", "6c71c4", "2aa198"});
        SPECS.put("Solarized Light", new String[] {"eee8d5", "f5efdc", "fdf6e3", "fdf6e3", "586e75", "839496", "e6dfc8", "d6cfb8", "93a1a1", "eee8d5",
                "859900", "b58900", "b58900", "268bd2", "586e75", "2aa198", "d33682", "93a1a1", "cb4b16",
                "dc322f", "b58900", "cb4b16", "859900", "6c71c4", "2aa198"});
        SPECS.put("GitHub Light", new String[] {"f6f8fa", "f6f8fa", "ffffff", "ffffff", "24292f", "57606a", "ddf4ff", "d0d7de", "8c959f", "fff8c5",
                "cf222e", "0550ae", "953800", "8250df", "24292f", "0a3069", "0550ae", "6e7781", "cf222e",
                "cf222e", "9a6700", "bc4c00", "116329", "8250df", "1b7c83"});
    }

    private Themes() {}

    /** Every theme name in menu order, stock themes first. */
    static List<String> names() {
        List<String> names = new java.util.ArrayList<>(List.of(VULCAN_DARK, VULCAN_LIGHT));
        names.addAll(SPECS.keySet());
        return names;
    }

    /** Settings color overrides for a non-stock theme, or null for a stock or unknown name. */
    static Map<String, Color> colors(String name) {
        String[] spec = SPECS.get(name);
        if (spec == null) return null;
        Color app = c(spec[0]), panel = c(spec[1]), input = c(spec[2]), editor = c(spec[3]), text = c(spec[4]), muted = c(spec[5]),
                selection = c(spec[6]), divider = c(spec[7]), lineNumber = c(spec[8]), highlight = c(spec[9]),
                keyword = c(spec[10]), primitive = c(spec[11]), type = c(spec[12]), function = c(spec[13]), variable = c(spec[14]),
                string = c(spec[15]), number = c(spec[16]), comment = c(spec[17]), macro = c(spec[18]),
                red = c(spec[19]), yellow = c(spec[20]), orange = c(spec[21]), green = c(spec[22]), purple = c(spec[23]), cyan = c(spec[24]);
        Map<String, Color> colors = new LinkedHashMap<>();
        colors.put("applicationBackground", app);
        colors.put("panelBackground", panel);
        colors.put("inputBackground", input);
        colors.put("text", text);
        colors.put("editorBackground", editor);
        colors.put("editorText", text);
        colors.put("tabSelected", selection);
        colors.put("tabText", text);
        colors.put("tabTextMuted", muted);
        colors.put("fileSelectionBackground", selection);
        colors.put("fileSelectionText", text);
        colors.put("splitDivider", divider);
        colors.put("lineNumber", lineNumber);
        colors.put("currentInstruction", highlight);
        colors.put("cKeyword", keyword);
        colors.put("cPrimitive", primitive);
        colors.put("cType", type);
        colors.put("cNumber", number);
        colors.put("cMacro", macro);
        colors.put("cFunction", function);
        colors.put("cVariable", variable);
        colors.put("cString", string);
        colors.put("cCharacter", string);
        colors.put("cComment", comment);
        colors.put("function", function);
        colors.put("variable", variable);
        colors.put("primitive", primitive);
        colors.put("character", yellow);
        colors.put("color1", red);       // call/jump
        colors.put("color2", yellow);    // stack
        colors.put("color3", number);    // immediate
        colors.put("color4", cyan);      // escape
        colors.put("color5", string);    // string
        colors.put("color6", primitive); // register
        colors.put("color7", purple);    // directive
        colors.put("color8", orange);    // instruction
        colors.put("color9", comment);   // comment
        colors.put("color10", text);     // label
        colors.put("color11", green);    // equate
        colors.put("color12", macro);    // section
        colors.put("equate", green);
        return colors;
    }

    /** A small menu icon: the theme's editor background holding five of its syntax colors. */
    static javax.swing.Icon swatch(String name) {
        String[] spec = SPECS.get(name);
        // Stock themes use the SettingsManager defaults: editor background, keyword, primitive, type, function, string.
        String[] colors = spec != null ? new String[] {spec[3], spec[10], spec[11], spec[12], spec[13], spec[15]}
                : name.equals(VULCAN_LIGHT) ? new String[] {"ffffff", "c586c0", "569cd6", "4ec9b0", "dcdcaa", "ce9178"}
                : new String[] {"121417", "c586c0", "569cd6", "4ec9b0", "dcdcaa", "ce9178"};
        return new javax.swing.Icon() {
            public int getIconWidth() { return 44; }
            public int getIconHeight() { return 14; }
            public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c(colors[0]));
                g.fillRoundRect(x, y, 44, 14, 6, 6);
                g.setColor(new Color(128, 128, 128, 110));
                g.drawRoundRect(x, y, 43, 13, 6, 6);
                for (int i = 1; i < colors.length; i++) {
                    g.setColor(c(colors[i]));
                    g.fillOval(x + 3 + (i - 1) * 8, y + 3, 8, 8);
                }
                g.dispose();
            }
        };
    }

    private static Color c(String hex) {
        return new Color(Integer.parseInt(hex, 16));
    }
}
