import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Pages an older version shares with the next newer one are stored once.
 *
 * <p>
 * Most releases change a few pages, and Roq can only build so many. So the sync keeps, in each
 * release version, only the pages that read differently from the same page in the next newer version
 * (its text, the files it includes and the values of the attributes it uses, but the version in its
 * Javadoc links); the others are listed
 * in the version's {@code _inherited.yml} (page: the version that stores it). The version's start page
 * is always stored, and main and latest are always complete.
 *
 * <p>
 * After Roq, {@code inherit target/roq} writes every inherited page: the stored page, its links moved
 * to the version, with the parts that belong to the version (the version picker, the navigation, the
 * notice and the source link: between {@code <!--docs-version-->} and {@code <!--/docs-version-->} in
 * the layout) taken from that version's start page.
 */
@Command(name = "inherit", mixinStandardHelpOptions = true,
        description = "Write the pages older documentation versions share with newer ones into the generated site.")
@SuppressWarnings("unchecked")
class Inherit implements Callable<Integer> {

    @Parameters(index = "0", description = "the generated site (target/roq)")
    Path site;

    static final String FILE = "_inherited.yml";
    static final Pattern INCLUDE = Common.reM("^include::([^\\[]+)\\[");
    static final Pattern ATTRIBUTE = Common.reM("^:([\\w-]+!?):[ \\t]*(.*)$");
    static final Pattern REFERENCE = Common.re("\\{([\\w-]+)\\}|if(?:n?def)::([\\w-]+(?:[,+][\\w-]+)*)\\[");
    /** Paths that name the version: the links they make are moved to the version that inherits the page. */
    static final Set<String> LOCATIONS = Set.of("version-root", "relfileprefix", "imagesdir", "examples-dir",
            "generated-dir", "partialsdir");
    /** Attributes that change how Asciidoctor renders a page without the page naming them. */
    static final Set<String> BUILT_IN = Set.of("doctype", "toc", "toclevels", "sectnums", "sectnumlevels", "sectanchors",
            "sectlinks", "idprefix", "idseparator", "icons", "source-highlighter", "source-language", "experimental",
            "hardbreaks", "leveloffset", "xrefstyle", "table-caption", "figure-caption", "example-caption",
            "listing-caption", "caution-caption", "important-caption", "note-caption", "tip-caption",
            "warning-caption", "stem", "linkattrs", "nofooter", "showtitle", "tabsize");
    /** A Javadoc link of the version: the inherit step moves it to the version that inherits the page. */
    static final Pattern JAVADOC_LINK = Common.re("(javadoc\\.io/doc/[\\w.-]+/[\\w.-]+/)\\{javadoc-version\\}");
    static final Pattern MARKED = Pattern.compile("<!--docs-version-->.*?<!--/docs-version-->", Pattern.DOTALL);

    /** The release versions of a project, newest first: the order pages are inherited in (main never is). */
    static List<String> chain(Map<String, Object> state, String key) {
        List<String> ids = new ArrayList<>();
        if (state.get(key) instanceof Map<?, ?> entry && entry.get("versions") instanceof List<?> versions) {
            for (Object v : versions) {
                String id = String.valueOf(((Map<String, Object>) v).get("id"));
                if (!id.equals("main") && Files.isDirectory(dir(key, id))) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    static Path dir(String key, String id) {
        return Sync.content().resolve(key).resolve(id);
    }

    /** page (without .adoc) -&gt; the version storing it. */
    static Map<String, String> inherited(String key, String id) {
        Path file = dir(key, id).resolve(FILE);
        Map<String, String> pages = new TreeMap<>();
        if (Files.exists(file) && Common.loadYaml(Common.read(file)) instanceof Map<?, ?> m) {
            m.forEach((page, from) -> pages.put(String.valueOf(page), String.valueOf(from)));
        }
        return pages;
    }

    /** Stored pages of a version (without .adoc), outside the _ directories. */
    static List<String> stored(String key, String id) {
        Path root = dir(key, id);
        List<String> pages = new ArrayList<>();
        for (Path page : Common.files(root, ".adoc")) {
            String rel = root.relativize(page).toString().replace('\\', '/');
            if (!Common.re("(^|/)_").matcher(rel).find()) {
                pages.add(rel.substring(0, rel.length() - ".adoc".length()));
            }
        }
        return pages;
    }

    /** Every page of a version, stored or inherited. */
    static List<String> pages(String key, String id) {
        TreeSet<String> all = new TreeSet<>(stored(key, id));
        all.addAll(inherited(key, id).keySet());
        return new ArrayList<>(all);
    }

    static void checkPage(String page) {
        if (!Sync.SAFE_PAGE.matcher(page).find()) {
            throw new Sync.Failure("invalid inherited page: " + Sync.pyRepr(page));
        }
    }

    /** Put the inherited pages back into each version, so the sync sees complete versions. */
    static void expand(Map<String, Object> state, String key) throws IOException {
        for (String id : chain(state, key)) {
            Map<String, String> pages = inherited(key, id);
            for (Map.Entry<String, String> e : pages.entrySet()) {
                checkPage(e.getKey());
                if (!Sync.VERSION_ID.matcher(e.getValue()).find()) {
                    throw new Sync.Failure("invalid version in " + key + "/" + id + "/" + FILE + ": " + e.getValue());
                }
                Path from = dir(key, e.getValue()).resolve(e.getKey() + ".adoc");
                if (!Files.isRegularFile(from)) {
                    throw new Sync.Failure(key + " " + id + ": " + e.getKey() + " is inherited from " + e.getValue()
                            + ", which does not have it");
                }
                Path to = dir(key, id).resolve(e.getKey() + ".adoc");
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(dir(key, id).resolve(FILE));
        }
    }

    /**
     * Drop from each version (oldest first, while the next newer one is still complete) the pages that
     * read the same as in the next newer version, then point each inherited page at the version that
     * stores it.
     */
    static int compact(Map<String, Object> state, String key) throws IOException {
        List<String> ids = chain(state, key);
        Map<String, Map<String, String>> found = new LinkedHashMap<>();
        int count = 0;
        for (int i = ids.size() - 1; i > 0; i--) {
            String id = ids.get(i);
            String newer = ids.get(i - 1);
            Path root = dir(key, id);
            Path newerRoot = dir(key, newer);
            Map<String, String> same = new TreeMap<>();
            for (String page : stored(key, id)) {
                if (!page.equals("index") && Files.isRegularFile(newerRoot.resolve(page + ".adoc"))
                        && fingerprint(root, page).equals(fingerprint(newerRoot, page))) {
                    same.put(page, newer);
                }
            }
            found.put(id, same);
        }
        for (String id : ids) {
            Map<String, String> same = found.getOrDefault(id, Map.of());
            for (Map.Entry<String, String> e : same.entrySet()) {
                // newer versions are resolved first: follow the page to the version that stores it
                Map<String, String> next = found.getOrDefault(e.getValue(), Map.of());
                if (next.containsKey(e.getKey())) {
                    e.setValue(next.get(e.getKey()));
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> v : found.entrySet()) {
            Path root = dir(key, v.getKey());
            for (String page : v.getValue().keySet()) {
                Files.delete(root.resolve(page + ".adoc"));
                count++;
            }
            if (!v.getValue().isEmpty()) {
                Common.write(root.resolve(FILE), "# Written by tools/docs/Docs.java sync: pages stored in a newer version"
                        + " (see Inherit.java)\n" + Common.dumpYaml(new TreeMap<>(v.getValue())));
            }
        }
        return count;
    }

    /** What the page renders from: its text, the files it includes and the attributes it uses. */
    static String fingerprint(Path root, String page) {
        StringBuilder text = new StringBuilder();
        List<Path> seen = new ArrayList<>();
        collect(root, root.resolve(page + ".adoc"), text, seen, 0);
        Map<String, String> attributes = attributes(root);
        TreeSet<String> used = new TreeSet<>(BUILT_IN);
        Matcher m = REFERENCE.matcher(JAVADOC_LINK.matcher(text).replaceAll("$1<javadoc-version>"));
        while (m.find()) {
            if (m.group(1) != null) {
                used.add(m.group(1));
            } else {
                used.addAll(List.of(m.group(2).split("[,+]")));
            }
        }
        StringBuilder print = new StringBuilder(JAVADOC_LINK.matcher(text).replaceAll("$1<javadoc-version>"));
        for (String name : used) {
            if (!LOCATIONS.contains(name)) {
                print.append("\n:").append(name).append(": ").append(attributes.get(name));
            }
        }
        return Sync.sha1(print.toString());
    }

    /** The text of a page and of the files it includes (but the attributes file), in order. */
    static void collect(Path root, Path file, StringBuilder text, List<Path> seen, int depth) {
        if (depth > 10 || seen.contains(file)) {
            return;
        }
        seen.add(file);
        if (!Files.isRegularFile(file)) {
            text.append("\n<missing ").append(root.relativize(file)).append(">\n");
            return;
        }
        String content = Common.read(file);
        text.append(content).append('\n');
        if (depth > 0 && !file.getFileName().toString().endsWith(".adoc")) {
            return;
        }
        Matcher m = INCLUDE.matcher(content);
        while (m.find()) {
            String target = m.group(1).strip();
            if (target.contains("://")) {
                continue;
            }
            String rel = target.replace("{examples-dir}", "{version-root}_examples")
                    .replace("{generated-dir}", "{version-root}_generated")
                    .replace("{partialsdir}", "{version-root}_partials");
            Path resolved = rel.startsWith("{version-root}") ? root.resolve(rel.substring("{version-root}".length()))
                    : file.getParent().resolve(rel);
            resolved = resolved.normalize();
            if (!resolved.startsWith(root) || resolved.equals(root.resolve("_attributes.adoc"))) {
                continue;
            }
            text.append("\n<include ").append(root.relativize(resolved)).append(">\n");
            collect(root, resolved, text, seen, depth + 1);
        }
    }

    /** The attributes of a version, their values expanded. */
    static Map<String, String> attributes(Path root) {
        Path file = root.resolve("_attributes.adoc");
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return attributes;
        }
        Matcher m = ATTRIBUTE.matcher(Common.read(file));
        while (m.find()) {
            String value = m.group(2);
            for (Map.Entry<String, String> a : attributes.entrySet()) {
                value = value.replace("{" + a.getKey() + "}", a.getValue());
            }
            attributes.put(m.group(1), value);
        }
        return attributes;
    }

    /** The stored page, moved into the version that inherits it. */
    static String page(String html, String frame, String key, String from, String id, String page) {
        String own = "/docs/" + key + "/" + id + "/";
        html = html.replace("/docs/" + key + "/" + from + "/", own);
        String fromJavadoc = attributes(dir(key, from)).get("javadoc-version");
        String ownJavadoc = attributes(dir(key, id)).get("javadoc-version");
        if (fromJavadoc != null && ownJavadoc != null && !fromJavadoc.equals(ownJavadoc)) {
            html = Common.re("(javadoc\\.io/doc/[\\w.-]+/[\\w.-]+/)" + Pattern.quote(fromJavadoc) + "(?=[/\"#])")
                    .matcher(html).replaceAll("$1" + Matcher.quoteReplacement(ownJavadoc));
        }
        // Roq's previous and next page in the collection belong to the version stored
        html = Common.re("[ \\t]*<link rel=\"(?:prev|next)\"[^>]*>\\n?").matcher(html).replaceAll("");
        List<String> parts = new ArrayList<>();
        Matcher f = MARKED.matcher(frame);
        while (f.find()) {
            parts.add(f.group());
        }
        StringBuilder out = new StringBuilder();
        Matcher m = MARKED.matcher(html);
        int i = 0;
        while (m.find()) {
            if (i >= parts.size()) {
                throw new Sync.Failure(key + " " + id + ": the start page has fewer marked parts than " + page);
            }
            String part = parts.get(i++)
                    .replace(" aria-current=\"page\"", "")
                    .replace("href=\"" + own + page + "/\"", "href=\"" + own + page + "/\" aria-current=\"page\"")
                    .replace("/index.adoc\"", "/" + page + ".adoc\"");
            m.appendReplacement(out, Matcher.quoteReplacement(part));
        }
        m.appendTail(out);
        if (i != parts.size()) {
            throw new Sync.Failure(key + " " + id + ": " + page + " has fewer marked parts than the start page");
        }
        return out.toString();
    }

    @Override
    public Integer call() {
        Map<String, Object> state = Sync.loadState();
        int count = 0;
        try {
            for (String key : state.keySet()) {
                if (!Sync.SAFE_NAME.matcher(key).find()) {
                    continue;
                }
                for (String id : chain(state, key)) {
                    Map<String, String> pages = inherited(key, id);
                    if (pages.isEmpty()) {
                        continue;
                    }
                    Path versionDir = site.resolve("docs").resolve(key).resolve(id);
                    String frame = Common.read(versionDir.resolve("index.html"));
                    for (Map.Entry<String, String> e : pages.entrySet()) {
                        checkPage(e.getKey());
                        Path from = site.resolve("docs").resolve(key).resolve(e.getValue()).resolve(e.getKey())
                                .resolve("index.html");
                        if (!Files.isRegularFile(from)) {
                            throw new Sync.Failure(key + " " + id + ": " + e.getKey() + " is not in " + e.getValue());
                        }
                        Common.write(versionDir.resolve(e.getKey()).resolve("index.html"),
                                page(Common.read(from), frame, key, e.getValue(), id, e.getKey()));
                        count++;
                    }
                }
            }
        } catch (Sync.Failure e) {
            Common.log(e.getMessage());
            return 1;
        }
        System.out.println("wrote " + count + " inherited pages into " + site);
        return 0;
    }
}
