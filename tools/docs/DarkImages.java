import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Dark versions of a version's drawings, so they read on the dark theme without a light block behind them.
 *
 * <pre>
 * dark-images content/docs/&lt;project&gt;/&lt;version&gt;    (sync does this; run it again after changing it)
 * </pre>
 *
 * For each block image of the pages (image::path[...]) the dark version sits next to it:
 * <ul>
 * <li>one the project drew itself: x.dark.png, or x-dark.png next to x-light.png;</li>
 * <li>else a diagram (images/diagrams/, rendered by sync) gets its colours turned around: x.dark.svg;</li>
 * <li>else a PNG that looks like a drawing (mostly white or transparent, darker lines) is recoloured:
 * the white becomes transparent and every colour keeps its hue with its lightness turned around.</li>
 * </ul>
 * The page marks such an image with the has-dark role, and the docs pages show the dark file in dark
 * mode. A project opts an image out with the no-dark role, or in with dark-invert (an SVG, or a PNG the
 * check passes over). Screenshots and photos fail the check and keep the light paper behind them.
 */
@Command(name = "dark-images", mixinStandardHelpOptions = true, hidden = true,
        description = "Make dark versions of the drawings of a synced version (content/docs/<project>/<version>).")
class DarkImages implements Callable<Integer> {

    @Parameters(paramLabel = "<version directory>")
    List<Path> targets;

    @Override
    public Integer call() {
        for (Path target : targets) {
            System.out.println(target + ": " + process(target) + " images with a dark version");
        }
        return 0;
    }

    static final Pattern IMAGE = Common.re("^image::([^\\[\\s]+)\\[(.*)\\]\\s*$");
    static final Pattern ATTRIBUTE_LINE = Common.re("^(\\[[^\\]]*\\]|\\.[^.\\s].*)$");

    /** Mark the pages' images that have (or now get) a dark version; returns how many. */
    static int process(Path target) {
        System.setProperty("java.awt.headless", "true");
        Path root = target.toAbsolutePath().normalize();
        Map<Path, Boolean> done = new HashMap<>();
        int count = 0;
        for (Path page : Common.files(target, ".adoc")) {
            List<String> lines = new ArrayList<>(List.of(Common.read(page).split("\n", -1)));
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = IMAGE.matcher(lines.get(i));
                if (!m.matches() || m.group(1).contains("{") || m.group(1).contains(":")) {
                    continue;
                }
                // the block attribute lines above it (title, id, roles) belong to it
                int first = i;
                while (first > 0 && ATTRIBUTE_LINE.matcher(lines.get(first - 1)).matches()) {
                    first--;
                }
                String attributes = String.join("\n", lines.subList(first, i)) + "\n" + m.group(2);
                if (attributes.contains("no-dark") || attributes.contains("has-dark")) {
                    continue;
                }
                // imagesdir is the version's root; nothing outside it is read or written
                Path image = root.resolve(m.group(1)).normalize();
                if (!image.startsWith(root) || !Files.isRegularFile(image) || Files.isSymbolicLink(image)) {
                    continue;
                }
                boolean force = attributes.contains("dark-invert");
                Boolean dark = done.computeIfAbsent(image, img -> darkVersion(root, img, force));
                if (dark) {
                    lines.add(i, "[.has-dark]");
                    i++;
                    changed = true;
                    count++;
                }
            }
            if (changed) {
                Common.write(page, String.join("\n", lines));
            }
        }
        return count;
    }

    /** The file the docs pages show in dark mode instead of {@code image}. Mirrors darkSource() in web/app/main.js. */
    static Path darkFile(Path image) {
        String name = image.getFileName().toString();
        Matcher m = Common.re("^(.*)-light(\\.[A-Za-z0-9]+)$").matcher(name);
        if (m.matches()) {
            return image.resolveSibling(m.group(1) + "-dark" + m.group(2));
        }
        String suffix = Sync.suffix(name);
        return image.resolveSibling(Sync.stem(name) + ".dark" + suffix);
    }

    static boolean darkVersion(Path root, Path image, boolean force) {
        Path dark = darkFile(image);
        if (Files.isRegularFile(dark) && !Files.isSymbolicLink(dark)) {
            return true;
        }
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".svg") && (force || root.relativize(image).startsWith("images/diagrams"))) {
                Common.write(dark, svg(Common.read(image)));
                return true;
            }
            if (name.endsWith(".png")) {
                BufferedImage source = ImageIO.read(image.toFile());
                if (source == null || !(force || drawing(source))) {
                    return false;
                }
                ImageIO.write(png(source), "png", dark.toFile());
                return true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            Common.log("  WARN " + root.relativize(image) + ": no dark version (" + e.getMessage() + ")");
        }
        return false;
    }

    // ---- colours ----

    /**
     * The dark-theme colour for an sRGB colour (0..1): same hue and colourfulness (chroma), lightness
     * turned around. Keeping the chroma rather than the saturation keeps near-black ink with a tint
     * from becoming a pastel, and pale fills from becoming deep colours.
     */
    static double[] turn(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double chroma = max - min;
        double h = 0;
        if (chroma > 0) {
            h = max == r ? (g - b) / chroma + (g < b ? 6 : 0) : max == g ? (b - r) / chroma + 2 : (r - g) / chroma + 4;
            h /= 6;
        }
        // the eye barely sees the tint of very dark colours: keep it barely visible once light
        chroma *= Math.min(1, l / 0.25);
        // black becomes the theme's light text colour rather than pure white
        l = 0.08 + 0.84 * (1 - l);
        double s = Math.min(1, chroma / (1 - Math.abs(2 * l - 1)));
        if (s < 1e-6) {
            return new double[] { l, l, l };
        }
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new double[] { hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3) };
    }

    private static double hue(double p, double q, double t) {
        t = t < 0 ? t + 1 : t > 1 ? t - 1 : t;
        if (t < 1.0 / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5) {
            return q;
        }
        if (t < 2.0 / 3) {
            return p + (q - p) * (2.0 / 3 - t) * 6;
        }
        return p;
    }

    // ---- PNG ----

    /**
     * A drawing rather than a screenshot or photo: at least half of it is background (transparent or
     * white) and a good part of the rest is darker lines or text, which would vanish on a dark page.
     */
    static boolean drawing(BufferedImage image) {
        long background = 0;
        long ink = 0;
        long dark = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int a = argb >>> 24;
                int r = argb >> 16 & 0xff;
                int g = argb >> 8 & 0xff;
                int b = argb & 0xff;
                if (a < 26 || Math.min(r, Math.min(g, b)) >= 245) {
                    background++;
                    continue;
                }
                ink++;
                if ((Math.max(r, Math.max(g, b)) + Math.min(r, Math.min(g, b))) / 2 < 128) {
                    dark++;
                }
            }
        }
        long total = (long) image.getWidth() * image.getHeight();
        return background * 2 >= total && ink > 0 && dark * 10 >= ink * 3;
    }

    static BufferedImage png(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                double a = (argb >>> 24) / 255.0;
                double r = (argb >> 16 & 0xff) / 255.0;
                double g = (argb >> 8 & 0xff) / 255.0;
                double b = (argb & 0xff) / 255.0;
                // white to transparent, keeping anti-aliased edges (GIMP's colour to alpha)
                double w = Math.max(1 - r, Math.max(1 - g, 1 - b));
                if (a == 0 || w < 1.0 / 255) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                r = 1 - (1 - r) / w;
                g = 1 - (1 - g) / w;
                b = 1 - (1 - b) / w;
                double[] c = turn(r, g, b);
                int alpha = (int) Math.round(a * w * 255);
                out.setRGB(x, y, alpha << 24 | channel(c[0]) << 16 | channel(c[1]) << 8 | channel(c[2]));
            }
        }
        return out;
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    // ---- SVG ----

    /** Colour values of fills, strokes, text and backgrounds, as attributes or CSS declarations. */
    static final Pattern SVG_PAINT = Pattern.compile(
            "((?:fill|stroke|stop-color|flood-color|lighting-color|color|background|background-color|border|border-color"
                    + "|border-(?:top|right|bottom|left)(?:-color)?|outline|outline-color)"
                    + "(?:\\s*=\\s*[\"']|\\s*:\\s*))([^\"';}<>]*)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern COLOUR = Pattern.compile(
            "#[0-9a-fA-F]{8}\\b|#[0-9a-fA-F]{6}\\b|#[0-9a-fA-F]{3,4}\\b|(?:rgba?|hsla?)\\([^)]*\\)|\\b[a-zA-Z]+\\b");
    static final Pattern ROOT_BACKGROUND = Common.re("(<svg\\b[^>]*?style=\"[^\"]*?background(?:-color)?:)\\s*[^;\"]+");

    static String svg(String svg) {
        // a background painted on the whole drawing goes: the page is the background
        svg = Common.sub(ROOT_BACKGROUND, svg, m -> m.group(1) + "transparent");
        return Common.sub(SVG_PAINT, svg, m -> m.group(1) + Common.sub(COLOUR, m.group(2), c -> {
            String turned = svgColour(c.group());
            return turned == null ? c.group() : turned;
        }));
    }

    /** The dark-theme value of one CSS colour, or null for what is not a colour (none, url(...), 1px, ...). */
    static String svgColour(String value) {
        double[] rgba = parse(value);
        if (rgba == null) {
            return null;
        }
        double[] c = turn(rgba[0], rgba[1], rgba[2]);
        String hex = String.format("#%02x%02x%02x", channel(c[0]), channel(c[1]), channel(c[2]));
        if (rgba[3] >= 1) {
            return hex;
        }
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%s)", channel(c[0]), channel(c[1]), channel(c[2]),
                trim(rgba[3]));
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** r, g, b, alpha (0..1) of a CSS colour, or null. */
    static double[] parse(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.startsWith("#")) {
            String hex = v.substring(1);
            if (hex.length() == 3 || hex.length() == 4) {
                hex = hex.replaceAll("(.)", "$1$1");
            }
            double a = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) / 255.0 : 1;
            return new double[] { Integer.parseInt(hex.substring(0, 2), 16) / 255.0,
                    Integer.parseInt(hex.substring(2, 4), 16) / 255.0, Integer.parseInt(hex.substring(4, 6), 16) / 255.0, a };
        }
        Matcher f = Common.re("^(rgba?|hsla?)\\(([^)]*)\\)$").matcher(v);
        if (f.matches()) {
            String[] parts = f.group(2).trim().split("\\s*[,/]\\s*|\\s+");
            if (parts.length < 3) {
                return null;
            }
            try {
                double a = parts.length > 3 ? number(parts[3], 1) : 1;
                if (f.group(1).startsWith("rgb")) {
                    return new double[] { number(parts[0], 255), number(parts[1], 255), number(parts[2], 255), a };
                }
                double h = Double.parseDouble(parts[0].replace("deg", "")) / 360;
                h -= Math.floor(h);
                double s = number(parts[1], 1);
                double l = number(parts[2], 1);
                double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
                double p = 2 * l - q;
                return new double[] { hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3), a };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Integer named = NAMED.get(v);
        return named == null ? null
                : new double[] { (named >> 16 & 0xff) / 255.0, (named >> 8 & 0xff) / 255.0, (named & 0xff) / 255.0, 1 };
    }

    /** A CSS number: a percentage, or a plain number out of {@code scale}. */
    private static double number(String s, double scale) {
        return s.endsWith("%") ? Double.parseDouble(s.substring(0, s.length() - 1)) / 100 : Double.parseDouble(s) / scale;
    }

    /** The CSS named colours. */
    static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String names = "aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc "
                + "bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a "
                + "burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed "
                + "cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b "
                + "darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b "
                + "darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a "
                + "darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 "
                + "darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff "
                + "firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff "
                + "gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 "
                + "hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa "
                + "lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 "
                + "lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 "
                + "lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 "
                + "lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 "
                + "magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 "
                + "mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a "
                + "mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 "
                + "moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 "
                + "orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 "
                + "paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb "
                + "plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f "
                + "royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee "
                + "sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 "
                + "snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 "
                + "turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 "
                + "yellowgreen 9acd32";
        String[] parts = names.split(" ");
        for (int i = 0; i < parts.length; i += 2) {
            NAMED.put(parts[i], Integer.parseInt(parts[i + 1], 16));
        }
    }
}
