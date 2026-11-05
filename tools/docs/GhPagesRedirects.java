import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Point a project's old mike site (its gh-pages branch) at the documentation on smallrye.io.
 *
 * <pre>
 * gh-pages-redirects --project smallrye-graphql --checkout ../gh-pages [--site target/roq]
 * </pre>
 *
 * In a checkout of the project's gh-pages branch, every page of every version, and the root
 * index.html, become redirect pages to the same page on https://smallrye.io/docs/&lt;project&gt;/:
 * <ul>
 * <li>the moving aliases (latest, Latest, ...: the aliases in versions.json) go to latest, and Main or
 * main to main;</li>
 * <li>a release goes to the version smallrye.io publishes for its minor line (2.8.1 to 2.8), or else
 * its major line (1.4.0 to 1), latest when that is the same minor, or the next newer version
 * (data/docversions.yml).</li>
 * </ul>
 * Javadoc (apidocs/) is left as it is. With --site (a generated smallrye.io), a page that no longer
 * exists redirects to the version's start page instead. Commit and push the checkout yourself after looking at the result.
 */
@Command(name = "gh-pages-redirects", mixinStandardHelpOptions = true,
        description = "Point a project's old mike site (its gh-pages branch) at the documentation on smallrye.io.")
@SuppressWarnings("unchecked")
class GhPagesRedirects implements Callable<Integer> {

    @Option(names = "--project", required = true)
    String project;

    @Option(names = "--checkout", required = true, description = "a checkout of the project's gh-pages branch")
    Path checkout;

    @Option(names = "--site", description = "a generated smallrye.io (target/roq) to check pages exist")
    Path site;

    @Option(names = "--aliases", arity = "0..*", description = "alias directories (default: from versions.json)")
    List<String> aliases;

    static final String STUB = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Moved to smallrye.io/docs</title>
            <meta name="robots" content="noindex">
            <link rel="canonical" href="{url}">
            <meta http-equiv="refresh" content="0; url={url}">
            <script>location.replace({js} + location.hash)</script>
            </head>
            <body><p>This documentation has moved to <a href="{url}">{url}</a>.</p></body>
            </html>
            """;

    private static final Pattern ALIAS = Common.re("[\\w.-]+");
    private static final Pattern UNDERSCORE = Common.re("(?<=.)_");

    /** MkDocs page path (a/b_c/) -&gt; the page smallrye.io publishes (a/b-c), as mkdocs2adoc names it. */
    static String pageName(String path) {
        path = path.replaceAll("^/+|/+$", "");
        if (path.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/", -1)) {
            parts.add(UNDERSCORE.matcher(part).replaceAll("-"));
        }
        return String.join("/", parts);
    }

    static String stub(String url) {
        return STUB.replace("{url}", Common.escapeHtml(url)).replace("{js}", Common.jsonString(url));
    }

    /** *.html files under {@code dir} that are not symbolic links, relative and sorted. */
    static List<String> htmlPages(Path dir) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".html") && !Files.isSymbolicLink(p)).forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        found.sort(Common.BY_PARTS);
        return found.stream().map(p -> dir.relativize(p).toString().replace('\\', '/')).toList();
    }

    /** The version id on smallrye.io for an old release: see {@link Sync#versionFor}. */
    static String target(String release, Map<String, String> published) {
        return Sync.versionFor(release, published);
    }

    /**
     * Everything else that is still a page of an older site (an earlier generation nested in the
     * branch, a SNAPSHOT build, an old website): the version in its path picks where it goes.
     */
    int sweep(Path root, String docs, Map<String, String> published) {
        int count = 0;
        Pattern version = Common.re("^\\d+\\.\\d+(\\.\\d+)?.*$");
        for (String rel : htmlPages(root)) {
            List<String> parts = List.of(rel.split("/"));
            if (rel.equals("index.html") || parts.contains("apidocs")) {
                continue;
            }
            Path file = root.resolve(rel);
            if (Common.read(file).contains("Moved to smallrye.io/docs")) {
                continue;
            }
            String id = "latest";
            int from = 0;
            for (int i = 0; i < parts.size() - 1; i++) {
                if (version.matcher(parts.get(i)).matches()) {
                    id = target(parts.get(i), published);
                    from = i + 1;
                    break;
                }
                if (parts.get(i).equalsIgnoreCase("snapshot") && published.containsKey("main")) {
                    id = "main";
                    from = i + 1;
                    break;
                }
            }
            String base = docs + id + "/";
            String stripped = String.join("/", parts.subList(from, parts.size())).replaceFirst("(^|/)index\\.html$", "");
            if (stripped.endsWith(".html")) {
                stripped = stripped.substring(0, stripped.length() - ".html".length());
            }
            String name = from == 0 || stripped.equals("404") ? "" : pageName(stripped);
            String target = base + (name.isEmpty() ? "" : name + "/");
            if (site != null && !name.isEmpty()
                    && !Files.exists(site.resolve("docs").resolve(project).resolve(id).resolve(name).resolve("index.html"))) {
                target = base;
            }
            Common.write(file, stub(target));
            count++;
        }
        return count;
    }

    @Override
    public Integer call() throws IOException {
        Path root = checkout.toRealPath();
        String docs = "https://smallrye.io/docs/" + project + "/";
        Map<String, String> published = new java.util.LinkedHashMap<>();
        Object entry = Sync.loadState().get(project);
        if (entry instanceof Map<?, ?> m && m.get("versions") instanceof List<?> versions) {
            for (Object v : versions) {
                Map<String, Object> version = (Map<String, Object>) v;
                published.put(String.valueOf(version.get("id")), String.valueOf(version.get("ref")));
            }
        }
        if (!published.containsKey("latest")) {
            Common.log(project + " has no latest version in data/docversions.yml");
            return 1;
        }
        Path versionsFile = root.resolve("versions.json");
        List<Object> versions = Files.exists(versionsFile) ? (List<Object>) Common.loadYaml(Common.read(versionsFile))
                : List.of();
        // directory -> the version id it redirects to
        Map<String, String> folders = new java.util.TreeMap<>();
        TreeSet<String> moving = new TreeSet<>(List.of("latest", "Latest", "Main"));
        if (aliases != null) {
            moving = new TreeSet<>(aliases);
        } else {
            for (Object v : versions) {
                Object a = ((Map<String, Object>) v).get("aliases");
                if (a instanceof List<?> list) {
                    for (Object x : list) {
                        moving.add(String.valueOf(x));
                    }
                }
            }
        }
        for (String alias : moving) {
            folders.put(alias, alias.equalsIgnoreCase("main") && published.containsKey("main") ? "main" : "latest");
        }
        if (aliases == null) {
            for (Object v : versions) {
                String release = String.valueOf(((Map<String, Object>) v).get("version"));
                if (!folders.containsKey(release)) {
                    folders.put(release, release.equalsIgnoreCase("main") && published.containsKey("main") ? "main"
                            : target(release, published));
                }
            }
        }
        folders.keySet().removeIf(f -> !ALIAS.matcher(f).matches()
                || !(Files.isDirectory(root.resolve(f)) || Files.isSymbolicLink(root.resolve(f))));
        // symbolic links (mike's aliases) first, while the versions they point to still have their pages
        List<String> order = new ArrayList<>(folders.keySet());
        order.sort(java.util.Comparator.comparing((String f) -> !Files.isSymbolicLink(root.resolve(f))));
        int count = 0;
        for (String dir : order) {
            Path folder = root.resolve(dir);
            String id = folders.get(dir);
            String base = docs + id + "/";
            List<String> pages;
            String linkedVersion = null;
            if (Files.isSymbolicLink(folder)) {
                // mike's alias is a symlink to a version: replace it by a directory of redirects
                // mirroring that version's pages (only a version inside this checkout)
                Path source;
                try {
                    source = folder.toRealPath();
                } catch (IOException e) {
                    continue;
                }
                if (!Files.isDirectory(source) || !source.startsWith(root)) {
                    continue;
                }
                pages = htmlPages(source);
                linkedVersion = source.getFileName().toString();
                Files.delete(folder);
            } else {
                pages = htmlPages(folder);
            }
            for (String rel : pages) {
                Path page = folder.resolve(rel);
                // Javadoc (apidocs/) stays: static pages that external links still reach; the docs on
                // smallrye.io link to javadoc.io
                boolean javadoc = rel.startsWith("apidocs/") || rel.contains("/apidocs/");
                if (javadoc && linkedVersion != null) {
                    // the alias's Javadoc now lives only in the version it pointed to
                    Common.write(page, stub("https://smallrye.io/" + project + "/" + linkedVersion + "/" + rel));
                    count++;
                    continue;
                }
                if (javadoc) {
                    continue;
                }
                if (rel.equals("404.html")) {
                    Common.write(page, stub(base));
                    count++;
                    continue;
                }
                String stripped = rel.replaceFirst("(^|/)index\\.html$", "");
                if (stripped.endsWith(".html")) {
                    stripped = stripped.substring(0, stripped.length() - ".html".length());
                }
                String name = pageName(stripped);
                String target = base + (name.isEmpty() ? "" : name + "/");
                if (site != null && !name.isEmpty()
                        && !Files.exists(site.resolve("docs").resolve(project).resolve(id).resolve(name).resolve("index.html"))) {
                    target = base;
                }
                Common.write(page, stub(target));
                count++;
            }
        }
        count += sweep(root, docs, published);
        Common.write(root.resolve("index.html"), stub(docs + "latest/"));
        java.util.Map<String, Integer> perTarget = new java.util.TreeMap<>();
        folders.values().forEach(id -> perTarget.merge(id, 1, Integer::sum));
        System.out.println(project + ": " + (count + 1) + " redirect pages from " + folders.size() + " directories ("
                + perTarget.entrySet().stream().map(e -> e.getValue() + " to " + e.getKey()).collect(java.util.stream.Collectors.joining(", "))
                + ")");
        return 0;
    }
}
