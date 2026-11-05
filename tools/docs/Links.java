import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Every link from one page of the generated site to another resolves.
 *
 * <p>
 * Run after {@code inherit} and {@code redirects}, so the pages older versions share and the old
 * addresses are there. A link resolves when the file exists, or the directory has an index.html, or
 * the path with .html does. Links to other sites, anchors within the page and the search index are
 * not checked.
 */
@Command(name = "links", mixinStandardHelpOptions = true,
        description = "Check that every link between pages of the generated site resolves.")
class Links implements Callable<Integer> {

    @Parameters(index = "0", description = "the generated site (target/roq)")
    Path site;

    @Option(names = "--show", description = "how many missing targets to list (default: ${DEFAULT-VALUE})")
    int show = 50;

    static final Pattern LINK = Common.re("(?:href|src)=\"([^\"]+)\"");
    static final Pattern EXTERNAL = Common.re("^(?:[a-zA-Z][\\w+.-]*:|//|#|\\{)");

    @Override
    public Integer call() {
        Path root = site.toAbsolutePath().normalize();
        // missing target -> [links to it, a page that has one]
        Map<String, Object[]> missing = new TreeMap<>();
        int pages = 0;
        for (Path page : Common.files(root, ".html")) {
            Path rel = root.relativize(page);
            if (rel.startsWith("pagefind")) {
                continue;
            }
            pages++;
            Matcher m = LINK.matcher(Common.read(page));
            while (m.find()) {
                String target = target(root, page, m.group(1));
                if (target != null && !exists(root, target)) {
                    Object[] seen = missing.computeIfAbsent(target, t -> new Object[] { 0, "/" + MkDocs2Adoc.posix(rel) });
                    seen[0] = (Integer) seen[0] + 1;
                }
            }
        }
        int links = missing.values().stream().mapToInt(v -> (Integer) v[0]).sum();
        Common.log(pages + " pages, " + missing.size() + " missing targets, " + links + " links");
        missing.entrySet().stream().limit(show)
                .forEach(e -> Common.log("  " + e.getKey() + " (" + e.getValue()[0] + ", e.g. from " + e.getValue()[1] + ")"));
        return missing.isEmpty() ? 0 : 1;
    }

    /** The site path a link points to (from the site root, with a leading /), or null when it is not checked. */
    static String target(Path root, Path page, String link) {
        if (EXTERNAL.matcher(link).find()) {
            return null;
        }
        // + is a plus in a path, not a space
        String path = URLDecoder.decode(link.replaceAll("[?#].*", "").replace("+", "%2B"), StandardCharsets.UTF_8);
        if (path.isEmpty()) {
            return null;
        }
        Path resolved = path.startsWith("/") ? root.resolve(path.substring(1)) : page.getParent().resolve(path);
        return "/" + MkDocs2Adoc.posix(root.relativize(resolved.normalize()));
    }

    static boolean exists(Path root, String target) {
        Path p = root.resolve(target.substring(1));
        return Files.isRegularFile(p) || Files.isRegularFile(p.resolve("index.html"))
                || Files.isRegularFile(p.resolveSibling(p.getFileName() + ".html"));
    }
}
