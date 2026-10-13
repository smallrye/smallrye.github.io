import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Puts the site's stylesheet inside every generated page, so a visitor's browser can draw
 * the page from the first file it receives instead of waiting for a second one.
 *
 * <p>Run by the deploy workflow after the site is generated and before it is published:
 * {@code java .github/InlineStyles.java target/roq}. Only the published copy changes;
 * {@code public/css/main.css} stays the file to edit. A stylesheet that is large, or that
 * cannot safely sit inside a page, is left linked. An address inside the stylesheet that is
 * relative to it, such as a font under {@code ../fonts/}, is rewritten as it moves, so it
 * still points at the same file from the page.
 * Safe to delete along with its step in .github/workflows/build.yml.
 */
public class InlineStyles {

    /** Past this, repeating the stylesheet in every page costs more than the request it saves. */
    static final int MAX_BYTES = 32 * 1024;

    static final Pattern LINK = Pattern.compile(
            "<link rel=\"stylesheet\" href=\"((?:https?://[^\"/]+)?/(?:[^\"]*/)?css/main\\.css)\"\\s*/?>");
    /** Another stylesheet pulled in by address: it would still be a second request, so leave this one alone. */
    static final Pattern IMPORT = Pattern.compile("@import");
    /** An address inside the stylesheet that is relative to it, and so moves when the CSS does. */
    static final Pattern RELATIVE_URL = Pattern.compile(
            "url\\(\\s*(['\"]?)((?!data:|https?:|//|/|#)[^)'\"\\s]+)\\1\\s*\\)");

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "target/roq");
        Path css = out.resolve("css/main.css");
        if (!Files.exists(css)) {
            System.out.println("No css/main.css in " + out + ", nothing to inline");
            return;
        }
        String style = Files.readString(css, StandardCharsets.UTF_8);
        String why = style.length() > MAX_BYTES ? "it is larger than " + MAX_BYTES / 1024 + " KB"
                : IMPORT.matcher(style).find() ? "it pulls in another stylesheet of its own"
                : style.toLowerCase().contains("</style") ? "it contains a closing style tag"
                : null;
        if (why != null) {
            System.out.println("The stylesheet stays linked: " + why);
            return;
        }
        int pages = 0;
        List<Path> html;
        try (Stream<Path> walk = Files.walk(out)) {
            // Files only: an old address like /about.html is kept working by a redirect page at
            // about.html/index.html, a folder that is not a page (cichurch-asn-au, 18 Sep 2026).
            html = walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".html")).toList();
        }
        for (Path page : html) {
            String s = Files.readString(page, StandardCharsets.UTF_8);
            Matcher m = LINK.matcher(s);
            if (!m.find()) continue;
            String block = "<style>\n" + moved(style, m.group(1)).strip() + "\n</style>";
            Files.writeString(page, s.substring(0, m.start()) + block + s.substring(m.end()), StandardCharsets.UTF_8);
            pages++;
        }
        System.out.println("Stylesheet put inside " + pages + " of " + html.size() + " pages");
    }

    /**
     * The stylesheet as it reads from inside a page: every address that was relative to the
     * stylesheet is rewritten against where the stylesheet was, so it still points at the
     * same file. {@code href} is the address the page linked, always rooted.
     */
    public static String moved(String style, String href) {
        String dir = href.substring(0, href.lastIndexOf('/') + 1);
        Matcher m = RELATIVE_URL.matcher(style);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement("url(" + m.group(1) + tidy(dir + m.group(2)) + m.group(1) + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A path with its . and .. taken out, keeping any scheme and host in front of it. */
    public static String tidy(String path) {
        int start = path.indexOf("://");
        String head = "";
        String rest = path;
        if (start >= 0) {
            int slash = path.indexOf('/', start + 3);
            head = slash < 0 ? path : path.substring(0, slash);
            rest = slash < 0 ? "/" : path.substring(slash);
        }
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String part : rest.split("/", -1)) {
            if (part.equals(".") || part.isEmpty()) continue;
            if (part.equals("..")) parts.pollLast();
            else parts.addLast(part);
        }
        return head + "/" + String.join("/", parts);
    }
}
