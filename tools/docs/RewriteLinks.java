import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Point links in blog posts (or any AsciiDoc/Markdown files) at the documentation's new addresses.
 *
 * <pre>
 * rewrite-links [--check target/roq] content/posts/*.adoc
 * </pre>
 *
 * Uses the {@code legacy} entries of data/docs.yml: an old Antora address such as
 * https://smallrye.io/docs/smallrye-fault-tolerance/5.2.0/usage/extra.html#_non_compatible_mode
 * becomes https://smallrye.io/docs/smallrye-fault-tolerance/5/usage/extra/#non-compatible-mode
 * (Antora generated ids like _this; the docs now use Quarkus-style ids like-this). URL attributes
 * (:name: https://smallrye.io/docs/...) are expanded where they are used, so every link is rewritten.
 * With --check, each rewritten link is verified against the generated site, anchor included.
 */
@Command(name = "rewrite-links", mixinStandardHelpOptions = true,
        description = "Point links in blog posts (or any AsciiDoc/Markdown files) at the documentation's new addresses.")
class RewriteLinks implements Callable<Integer> {

    @Parameters(arity = "1..*")
    List<Path> files;

    @Option(names = "--check", description = "generated site to verify the new links against")
    Path check;

    private static final Pattern URL = Common.re("https?://(?:www\\.)?smallrye\\.io(/docs/[^\\s\\[\\]\"<>)]+)");
    private static final Pattern URL_ATTRIBUTE = Common.reM("^:([\\w-]+):\\s*(https?://(?:www\\.)?smallrye\\.io/docs/\\S+)\\s*$");
    private static final Pattern ID = Common.re("id=\"([^\"]+)\"");

    record Rule(String before, String after, String base, Map<String, String> renamed) {
    }

    /** [(prefix of an old address, suffix, new base, renamed anchors)], longest prefix first. */
    @SuppressWarnings("unchecked")
    static List<Rule> legacyMap() {
        List<Rule> rules = new ArrayList<>();
        Map<String, Object> registry = (Map<String, Object>) Common.loadYaml(Common.read(Common.SITE.resolve("data/docs.yml")));
        for (Map<String, Object> project : (List<Map<String, Object>>) registry.get("projects")) {
            Map<String, Object> legacy = (Map<String, Object>) project.get("legacy");
            if (legacy == null || legacy.isEmpty()) {
                continue;
            }
            Map<String, String> anchors = new LinkedHashMap<>();
            Object a = legacy.get("anchors");
            if (a instanceof Map<?, ?> map) {
                map.forEach((k, v) -> anchors.put(String.valueOf(k), String.valueOf(v)));
            }
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) legacy.get("releases")).entrySet()) {
                for (Object release : (List<Object>) e.getValue()) {
                    String prefix = Redirects.format((String) legacy.get("url"), String.valueOf(release), "\0").replace("//", "/");
                    int split = prefix.indexOf('\0');
                    rules.add(new Rule(prefix.substring(0, split), prefix.substring(split + 1),
                            "/docs/" + project.get("key") + "/" + e.getKey() + "/", anchors));
                }
            }
        }
        // longest prefix first, so /5.2.0/ wins over an unversioned prefix
        rules.sort(Comparator.comparingInt(r -> -r.before().length()));
        return rules;
    }

    static String newAnchor(String anchor, Map<String, String> renamed) {
        if (renamed.containsKey(anchor)) {
            return renamed.get(anchor);
        }
        return anchor.startsWith("_") ? anchor.replaceFirst("^_+", "").replace("_", "-") : anchor;
    }

    static String squash(String anchor) {
        return anchor.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String rewritePath(String path, List<Rule> rules) {
        int hash = path.indexOf('#');
        String page = hash < 0 ? path : path.substring(0, hash);
        String anchor = hash < 0 ? "" : path.substring(hash + 1);
        for (Rule r : rules) {
            if (page.startsWith(r.before()) && (page.endsWith(r.after()) || rstripSlash(page).concat("/").equals(r.before())
                    || page.equals(rstripSlash(r.before())))) {
                String name = page.substring(r.before().length());
                name = !r.after().isEmpty() && name.endsWith(r.after()) ? name.substring(0, name.length() - r.after().length())
                        : rstripSlash(name);
                name = name.isEmpty() || name.equals("index") ? "" : name + "/";
                return r.base() + name + (anchor.isEmpty() ? "" : "#" + newAnchor(anchor, r.renamed()));
            }
        }
        return null;
    }

    static String rstripSlash(String s) {
        return s.replaceFirst("/+$", "");
    }

    /** Replace {name} by its value where :name: is an absolute smallrye.io/docs URL, and drop the entry. */
    static String expandUrlAttributes(String text) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher m = URL_ATTRIBUTE.matcher(text);
        while (m.find()) {
            attrs.put(m.group(1), m.group(2));
        }
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            text = Common.reM("^:" + Pattern.quote(e.getKey()) + ":.*\\n").matcher(text).replaceAll("");
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    static String check(Path site, String path) {
        int hash = path.indexOf('#');
        String page = hash < 0 ? path : path.substring(0, hash);
        String anchor = hash < 0 ? "" : path.substring(hash + 1);
        Path f = site.resolve(page.replaceFirst("^/+", "")).resolve("index.html");
        if (!Files.isRegularFile(f)) {
            return "no page " + page;
        }
        if (!anchor.isEmpty() && !Common.read(f).contains("id=\"" + anchor + "\"")) {
            return "no #" + anchor + " on " + page;
        }
        return null;
    }

    /**
     * Asciidoctor drops some characters when it makes ids ("Non-compatible Mode" is
     * noncompatible-mode): use the page's id that matches once punctuation is ignored.
     */
    static String resolveAnchor(Path site, String path) {
        int hash = path.indexOf('#');
        String page = hash < 0 ? path : path.substring(0, hash);
        String anchor = hash < 0 ? "" : path.substring(hash + 1);
        Path f = site.resolve(page.replaceFirst("^/+", "")).resolve("index.html");
        if (anchor.isEmpty() || !Files.isRegularFile(f) || Common.read(f).contains("id=\"" + anchor + "\"")) {
            return path;
        }
        List<String> match = new ArrayList<>();
        Matcher m = ID.matcher(Common.read(f));
        while (m.find()) {
            if (squash(m.group(1)).equals(squash(anchor))) {
                match.add(m.group(1));
            }
        }
        return match.size() == 1 ? page + "#" + match.get(0) : path;
    }

    @Override
    public Integer call() {
        List<Rule> rules = legacyMap();
        int[] problems = { 0 };
        int changed = 0;
        for (Path f : files) {
            String text = Common.read(f);
            String expanded = expandUrlAttributes(text);
            String updated = Common.sub(URL, expanded, m -> {
                String target = rewritePath(m.group(1), rules);
                if (target == null) {
                    return m.group();
                }
                if (check != null) {
                    target = resolveAnchor(check, target);
                    String issue = check(check, target);
                    if (issue != null) {
                        Common.log(f.getFileName() + ": " + m.group(1) + " -> " + target + ": " + issue);
                        problems[0]++;
                    }
                }
                return "https://smallrye.io" + target;
            });
            if (!updated.equals(text)) {
                Common.write(f, updated);
                changed++;
            }
        }
        System.out.println("rewrote links in " + changed + " file(s); " + problems[0] + " problem(s)");
        return problems[0] > 0 ? 1 : 0;
    }
}
