import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Convert a project's Antora documentation component to the Quarkus-style layout smallrye.io publishes.
 *
 * <pre>
 * antora2adoc --antora &lt;repo&gt;/doc --out &lt;repo&gt;/docs/src/main/asciidoc
 *
 * Antora                                    -&gt;  Quarkus-style layout
 *   modules/ROOT/pages/a/b.adoc             -&gt;  a/b.adoc (same path, so the same address)
 *   modules/ROOT/partials/x.adoc            -&gt;  _partials/x.adoc, included with {partialsdir}
 *   modules/ROOT/examples/x.java            -&gt;  _examples/x.java, included with {examples-dir}
 *   modules/ROOT/images/x.png               -&gt;  images/x.png
 *   modules/ROOT/nav.adoc                   -&gt;  _nav.yml
 *   antora.yml asciidoc.attributes          -&gt;  _attributes.adoc
 *   xref:other-component::page.adoc[]       -&gt;  link to /docs/other-component/latest/page/
 * </pre>
 *
 * Pages in subdirectories declare :version-root: (../ per level) before including
 * {version-root}_attributes.adoc, so paths in _attributes.adoc work from any depth.
 * Writes _link-map.json: old Antora page path (a/b.html) -&gt; new page (a/b).
 */
@Command(name = "antora2adoc", mixinStandardHelpOptions = true,
        description = "Convert a project's Antora documentation component to the Quarkus-style layout smallrye.io publishes.")
class Antora2Adoc implements Callable<Integer> {

    @Option(names = "--antora", required = true, description = "the component directory (holds antora.yml)")
    Path antora;

    @Option(names = "--out", required = true)
    Path out;

    @Override
    public Integer call() {
        try {
            convert(antora, out);
            return 0;
        } catch (IllegalArgumentException e) {
            Common.log(e.getMessage());
            return 1;
        }
    }

    /** Convert the component in {@code component} (holds antora.yml) into {@code out}. */
    static void convert(Path component, Path out) {
        new Converter(component, out).run();
    }

    // Python's re: '.' and '$' only know '\n' as a line end; \w and \s are Unicode
    private static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    private static Pattern reM(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES | Pattern.MULTILINE);
    }

    private static final Pattern PAGE_ANCHOR = re("\\A(\\s*)\\[\\[[\\w-]+\\]\\]\\s*\\n(?=\\s*= )");
    private static final Pattern CROSS = re("<<([\\w-]+)(?:,([^>]*))?>>");
    private static final Pattern NAV_INCLUDE = reM("^include::[\\w./-]*nav\\.adoc\\[[^\\]]*\\]\\n?");
    private static final Pattern PARTIAL = re("include::partial\\$([^\\[]+)\\[");
    private static final Pattern EXAMPLE = re("include::example\\$([^\\[]+)\\[");
    private static final Pattern PAGE_INCLUDE = re("include::([\\w./-]+\\.adoc)\\[");
    private static final Pattern XREF = re("xref:([\\w.@-]*:[\\w.-]*:?[\\w./-]+\\.adoc)((?:#[\\w-]+)?\\[)");
    private static final Pattern IMAGE = re("(image::?)(?!https?:|/|images/)([^\\[\\s:]+)\\[");
    private static final Pattern XREF_GENERATED_ID = re("(xref:[\\w./-]*\\.adoc)#_([\\w]+)\\[");
    private static final Pattern REF_GENERATED_ID = re("<<_([\\w]+)([,>])");
    private static final Pattern IDS = re("\\[\\[([\\w-]+)(?:,[^\\]]*)?\\]\\]|\\[#([\\w-]+)");
    private static final Pattern NAV_ITEM = re("^(\\*+)\\s+(.*)$");
    private static final Pattern NAV_SECTION = re("^\\.(\\S.*)$");
    private static final Pattern NAV_XREF = re("^xref:([\\w./-]+)\\.adoc(?:#[\\w-]+)?\\[(.*)\\]$");

    /**
     * Put the attribute includes right after the document title, or first when the page has
     * lines of its own above the title (an anchor, attributes, includes that need ours).
     */
    static String pageHeader(String text, int depth) {
        List<String> lines = Common.lines(text);
        Integer title = null;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("= ")) {
                title = i;
                break;
            }
        }
        List<String> insert = new ArrayList<>();
        if (depth > 0) {
            insert.add(":version-root: " + "../".repeat(depth));
        }
        insert.add(depth > 0 ? "include::{version-root}_attributes.adoc[]" : "include::_attributes.adoc[]");
        boolean ownLines = false;
        if (title != null) {
            for (String l : lines.subList(0, title)) {
                if (!l.strip().isEmpty() && !l.startsWith("[[")) {
                    ownLines = true;
                }
            }
        }
        List<String> result = new ArrayList<>();
        if (title == null || ownLines) {
            result.addAll(insert);
            result.addAll(lines);
        } else {
            result.addAll(lines.subList(0, title + 1));
            result.addAll(insert);
            result.addAll(lines.subList(title + 1, lines.size()));
        }
        return String.join("\n", result);
    }

    /**
     * A 'book' page has several level-0 titles; on the site a page has one, so later ones become
     * sections. Listings are left alone.
     */
    static String singleTitle(String text) {
        List<String> out = new ArrayList<>();
        String fence = null;
        boolean seen = false;
        for (String line : Common.lines(text)) {
            String s = line.strip();
            if (s.equals("----") || s.equals("....") || s.equals("++++") || s.equals("____") || s.startsWith("```")) {
                fence = s.equals(fence) ? null : (fence != null ? fence : s);
            }
            if (fence == null || fence.isEmpty()) {
                if (s.equals(":doctype: book")) {
                    continue;
                }
                if (line.startsWith("= ")) {
                    if (seen) {
                        line = "=" + line;
                    }
                    seen = true;
                }
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    /** json.dumps(map, indent=2) of a map of strings. */
    static String json(Map<String, String> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringBuilder b = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            b.append("  ").append(Common.jsonString(e.getKey())).append(": ").append(Common.jsonString(e.getValue()));
            b.append(++i < map.size() ? ",\n" : "\n");
        }
        return b.append("}").toString();
    }

    /** Python's str() of a YAML scalar. */
    static String str(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean bool) {
            return bool ? "True" : "False";
        }
        if (v instanceof Date date) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(date);
        }
        return String.valueOf(v);
    }

    private static final class Converter {
        final Path src;
        final Path out;
        final Map<String, Object> descriptor;
        final String component;
        final Path module;
        final List<String> warnings = new ArrayList<>();
        Map<String, Set<String>> ids = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Converter(Path antora, Path out) {
            this.src = real(antora);
            this.out = out.toAbsolutePath().normalize();
            if (!contained(src.resolve("antora.yml")) || !contained(src.resolve("modules"))) {
                throw new IllegalArgumentException("antora.yml and modules/ must be real files in the component");
            }
            this.descriptor = (Map<String, Object>) Common.loadYaml(Common.read(src.resolve("antora.yml")));
            this.component = (String) descriptor.get("name");
            this.module = src.resolve("modules").resolve("ROOT");
        }

        static Path real(Path p) {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                return p.toAbsolutePath().normalize();
            }
        }

        /**
         * A real file inside the component: symbolic links are never followed, so a pull request
         * cannot pull files from elsewhere on the machine into the site.
         */
        boolean contained(Path path) {
            if (Files.isSymbolicLink(path) || !real(path).startsWith(src)) {
                return false;
            }
            for (Path p = path.getParent(); p != null; p = p.getParent()) {
                if (p.toAbsolutePath().normalize().startsWith(src) && Files.isSymbolicLink(p)) {
                    return false;
                }
            }
            return true;
        }

        // ---------- content ----------
        String rewrite(String text, String pageRel, Set<String> includedPages) {
            int depth = (int) pageRel.chars().filter(c -> c == '/').count();
            // an anchor right above the document title names the page itself: the page address does that
            text = Common.sub(PAGE_ANCHOR, text, m -> m.group(1));
            // <<id,text>> whose id lives on another page becomes an xref to that page
            Set<String> own = ids.getOrDefault(pageRel, Set.of());
            text = Common.sub(CROSS, text, m -> {
                String target = m.group(1);
                String label = m.group(2);
                if (own.contains(target)) {
                    return m.group();
                }
                List<String> pages = new ArrayList<>();
                ids.forEach((p, set) -> {
                    if (set.contains(target)) {
                        pages.add(p);
                    }
                });
                if (pages.size() != 1) {
                    return m.group();
                }
                return "xref:" + pages.get(0) + "#" + target + "[" + (label == null ? "" : label).strip() + "]";
            });
            // includes
            // the site has a sidebar: pages that listed the navigation do not need to
            text = NAV_INCLUDE.matcher(text).replaceAll("");
            text = singleTitle(text);
            text = PARTIAL.matcher(text).replaceAll("include::{partialsdir}/$1[");
            text = EXAMPLE.matcher(text).replaceAll("include::{examples-dir}/$1[");
            text = Common.sub(PAGE_INCLUDE, text, m -> {
                String target = m.group(1);
                if (includedPages.contains(target)) {
                    return "include::{partialsdir}/" + Path.of(target).getFileName() + "[";
                }
                return m.group();
            });
            // xrefs: version@component:module:page, component::page, module:page
            text = Common.sub(XREF, text, m -> {
                String spec = m.group(1);
                String rest = m.group(2);
                int at = spec.lastIndexOf('@');
                if (at >= 0) {
                    spec = spec.substring(at + 1);
                }
                String[] parts = spec.split(":", -1);
                String page = parts[parts.length - 1];
                String comp = parts.length == 3 ? parts[0] : component;
                if (parts.length == 2 && !parts[0].isEmpty() && !parts[0].equals("ROOT")) {
                    warnings.add(pageRel + ": xref to module " + parts[0] + " kept as " + page);
                }
                if (comp.isEmpty() || comp.equals(component)) {
                    return "xref:" + page + rest;
                }
                String target = page.replaceFirst("(index)?\\.adoc$", "");
                int bracket = rest.indexOf('[');
                String anchor = rest.substring(0, bracket);
                return "link:/docs/" + comp + "/latest/" + target + (!target.isEmpty() && !target.endsWith("/") ? "/" : "")
                        + anchor + "[" + rest.substring(bracket + 1);
            });
            // images: Antora resolves them from the module's images/; on the site imagesdir is the version root
            text = IMAGE.matcher(text).replaceAll("$1images/$2[");
            // references to Antora's generated ids (_like_this) use the new style (like-this)
            text = Common.sub(XREF_GENERATED_ID, text, m -> m.group(1) + "#" + m.group(2).replace("_", "-") + "[");
            text = Common.sub(REF_GENERATED_ID, text, m -> "<<" + m.group(1).replace("_", "-") + m.group(2));
            return pageHeader(text, depth);
        }

        // ---------- nav ----------
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nav() {
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("items", items);
            List<Object[]> stack = new ArrayList<>();
            stack.add(new Object[] { 0.0, root });
            for (String raw : Common.read(module.resolve("nav.adoc")).split("\r\n|\r|\n")) {
                String line = raw.stripTrailing();
                Matcher m = NAV_ITEM.matcher(line);
                Matcher section = NAV_SECTION.matcher(line);
                if (section.find()) { // ".Title" starts a top-level section
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("title", section.group(1).strip());
                    entry.put("items", new ArrayList<>());
                    stack = new ArrayList<>();
                    stack.add(new Object[] { 0.0, root });
                    ((List<Object>) root.get("items")).add(entry);
                    stack.add(new Object[] { 0.5, entry });
                    continue;
                }
                if (!m.find()) {
                    continue;
                }
                double level = m.group(1).length();
                String content = m.group(2).strip();
                Matcher x = NAV_XREF.matcher(content);
                boolean isXref = x.find();
                String title = isXref ? x.group(2) : null;
                if (isXref && x.group(1).equals("index") && (title == null || title.equals("Index") || title.equals("index"))) {
                    title = "Overview";
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                if (isXref) {
                    entry.put("title", title);
                    entry.put("page", x.group(1));
                } else {
                    entry.put("title", content.replaceFirst("\\s*\\.\\.\\.$", ""));
                    entry.put("items", new ArrayList<>());
                }
                while (!stack.isEmpty() && (double) stack.get(stack.size() - 1)[0] >= level) {
                    stack.remove(stack.size() - 1);
                }
                Map<String, Object> parent = (Map<String, Object>) stack.get(stack.size() - 1)[1];
                ((List<Object>) parent.computeIfAbsent("items", k -> new ArrayList<>())).add(entry);
                stack.add(new Object[] { level, entry });
            }
            return clean(items);
        }

        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> clean(List<Map<String, Object>> entries) {
            for (Map<String, Object> e : entries) {
                if (e.get("title") == null) {
                    e.remove("title");
                }
                if (e.containsKey("items")) {
                    List<Map<String, Object>> children = (List<Map<String, Object>>) e.get("items");
                    if (!children.isEmpty()) {
                        clean(children);
                    } else if (e.containsKey("page")) {
                        e.remove("items");
                    }
                }
            }
            return entries;
        }

        static List<Path> filesUnder(Path dir, String suffix) {
            try (Stream<Path> s = Files.walk(dir)) {
                return s.filter(p -> !p.equals(dir)).filter(p -> suffix == null || p.getFileName().toString().endsWith(suffix))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // ---------- main ----------
        @SuppressWarnings("unchecked")
        void run() {
            try {
                Files.createDirectories(out);
                Path pagesDir = module.resolve("pages");
                List<Path> pages = new ArrayList<>(filesUnder(pagesDir, ".adoc").stream().filter(this::contained).toList());
                pages.sort(Common.BY_PARTS);
                // pages included by other pages are partials in disguise (e.g. attributes.adoc)
                Set<String> included = new HashSet<>();
                List<Path> resolvedPages = pages.stream().map(Converter::real).toList();
                for (Path p : pages) {
                    Matcher m = PAGE_INCLUDE.matcher(Common.read(p));
                    while (m.find()) {
                        String target = m.group(1);
                        if (resolvedPages.contains(real(p.getParent().resolve(target)))) {
                            included.add(target);
                        }
                    }
                }
                for (Path p : pages) {
                    Set<String> found = new HashSet<>();
                    Matcher m = IDS.matcher(Common.read(p));
                    while (m.find()) {
                        found.add(m.group(1) != null ? m.group(1) : m.group(2));
                    }
                    ids.put(rel(pagesDir, p), found);
                }
                Set<String> includedNames = new HashSet<>();
                for (String i : included) {
                    includedNames.add(Path.of(i).getFileName().toString());
                }
                Map<String, String> linkMap = new LinkedHashMap<>();
                for (Path p : pages) {
                    String rel = rel(pagesDir, p);
                    if (includedNames.contains(p.getFileName().toString())) {
                        Common.write(out.resolve("_partials").resolve(p.getFileName().toString()), Common.read(p));
                        continue;
                    }
                    String name = rel.substring(0, rel.length() - ".adoc".length());
                    Common.write(out.resolve(rel), rewrite(Common.read(p), rel, included));
                    linkMap.put(name + ".html", name);
                }
                for (String[] kt : new String[][] { { "partials", "_partials" }, { "examples", "_examples" },
                        { "images", "images" } }) {
                    Path folder = module.resolve(kt[0]);
                    if (Files.exists(folder)) {
                        for (Path f : filesUnder(folder, null)) {
                            if (Files.isRegularFile(f) && contained(f)) {
                                Path dest = out.resolve(kt[1]).resolve(folder.relativize(f).toString());
                                Files.createDirectories(dest.getParent());
                                if (kt[0].equals("partials") && f.getFileName().toString().endsWith(".adoc")) {
                                    Common.write(dest, PARTIAL.matcher(Common.read(f)).replaceAll("include::{partialsdir}/$1["));
                                } else {
                                    Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                                }
                            }
                        }
                    }
                }
                Path navFile = module.resolve("nav.adoc");
                List<Map<String, Object>> nav = Files.exists(navFile) && contained(navFile) ? nav() : List.of();
                Map<String, Object> navDoc = new LinkedHashMap<>();
                navDoc.put("title", descriptor.get("title"));
                navDoc.put("nav", nav);
                Common.write(out.resolve("_nav.yml"), Common.dumpYamlInOrder(navDoc));
                Object asciidoc = descriptor.get("asciidoc");
                Object attributes = asciidoc instanceof Map<?, ?> a ? a.get("attributes") : null;
                Map<String, Object> attrs = attributes instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
                List<String> lines = new ArrayList<>(List.of(
                        "// Attributes shared by every page. smallrye.io overrides the ones it needs when it publishes.",
                        "// Pages in subdirectories set :version-root: (../ per level) before including this file.",
                        "ifndef::version-root[:version-root:]",
                        ":idprefix:", ":idseparator: -", ":sectanchors:",
                        ":partialsdir: {version-root}_partials",
                        ":examples-dir: {version-root}_examples"));
                attrs.forEach((k, v) -> lines.add(Boolean.FALSE.equals(v) ? ":" + k + "!:"
                        : ":" + k + ": " + (Boolean.TRUE.equals(v) ? "" : str(v))));
                Common.write(out.resolve("_attributes.adoc"), String.join("\n", lines) + "\n");
                Common.write(out.resolve("_link-map.json"), json(linkMap) + "\n");
                for (String w : warnings) {
                    Common.log("WARN: " + w);
                }
                System.out.println("converted " + linkMap.size() + " pages into " + out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static String rel(Path base, Path p) {
            return base.relativize(p).toString().replace('\\', '/');
        }
    }
}
