import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Write redirect pages for the documentation addresses this site published before the move, into the
 * generated site (run after Roq, before deploying):
 *
 * <pre>
 * redirects target/roq
 * </pre>
 *
 * A project's {@code redirects} (old address: new address) are written as they are. For each project
 * in data/docs.yml with {@code legacy}, every page of a version that replaces old releases
 * gets a redirect at the old address: Antora's /docs/&lt;key&gt;/&lt;release&gt;/&lt;page&gt;.html goes to
 * /docs/&lt;key&gt;/&lt;version&gt;/&lt;page&gt;/ (the #fragment is kept; docs pages map Antora's #_old_ids). GitHub
 * Pages cannot redirect on the server, so these are small HTML pages.
 */
@Command(name = "redirects", mixinStandardHelpOptions = true,
        description = "Write redirect pages for the documentation addresses this site published before the move.")
class Redirects implements Callable<Integer> {

    @Parameters(index = "0", description = "the generated site (target/roq)")
    Path site;

    static final String STUB = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Moved</title>
            <meta name="robots" content="noindex">
            <link rel="canonical" href="https://smallrye.io{url}">
            <meta http-equiv="refresh" content="0; url={url}">
            <script>location.replace({js} + location.hash)</script>
            </head>
            <body><p>This page has moved to <a href="{url}">{url}</a>.</p></body>
            </html>
            """;

    /** Page paths of a synced version, without .adoc: 'index', 'reference/retry', ... */
    static List<String> pages(String key, String version) {
        Path root = content().resolve(key).resolve(version);
        List<Path> files = new ArrayList<>(Common.files(root, ".adoc"));
        files.sort(Common.BY_PARTS);
        List<String> pages = new ArrayList<>();
        for (Path page : files) {
            Path rel = root.relativize(page);
            boolean hidden = false;
            for (Path part : rel) {
                hidden |= part.toString().startsWith("_");
            }
            if (!hidden) {
                String s = rel.toString().replace('\\', '/');
                pages.add(s.substring(0, s.length() - ".adoc".length()));
            }
        }
        return pages;
    }

    static Path content() {
        return Common.SITE.resolve("content").resolve("docs");
    }

    static void write(Path out, String old, String target) {
        Path file = out.resolve(old.replaceFirst("^/+", ""));
        if (!file.toAbsolutePath().normalize().startsWith(out.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("redirect outside the site: " + old);
        }
        String url = Common.escapeHtml(target);
        String js = "\"" + target.replace("\\", "").replace("\"", "") + "\"";
        Common.write(file, STUB.replace("{url}", url).replace("{js}", js));
    }

    /** Python's str.format with the named fields release and page. */
    static String format(String pattern, String release, String page) {
        return pattern.replace("{release}", release).replace("{page}", page);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() {
        Map<String, Object> registry = (Map<String, Object>) Common.loadYaml(Common.read(Common.SITE.resolve("data/docs.yml")));
        int count = 0;
        try {
            for (Map<String, Object> project : (List<Map<String, Object>>) registry.get("projects")) {
                Object redirects = project.get("redirects");
                if (redirects instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        write(site, String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        count++;
                    }
                }
                Map<String, Object> legacy = (Map<String, Object>) project.get("legacy");
                if (legacy == null || legacy.isEmpty()) {
                    continue;
                }
                String key = (String) project.get("key");
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) legacy.get("releases")).entrySet()) {
                    String version = String.valueOf(e.getKey());
                    if (!Files.isDirectory(content().resolve(key).resolve(version))) {
                        Common.log("WARN " + key + ": version " + version + " is not synced, no redirects for it");
                        continue;
                    }
                    for (String page : pages(key, version)) {
                        String target = "/docs/" + key + "/" + version + "/" + (page.equals("index") ? "" : page + "/");
                        for (Object release : (List<Object>) e.getValue()) {
                            write(site, format((String) legacy.get("url"), String.valueOf(release), page).replace("//", "/"), target);
                            count++;
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            Common.log(e.getMessage());
            return 1;
        }
        // Antora's start page for all components
        write(site, "/docs/index/index/index.html", "/docs/");
        count++;
        System.out.println("wrote " + count + " redirects into " + site);
        return 0;
    }
}
