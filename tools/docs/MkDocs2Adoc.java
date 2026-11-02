import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Convert a project's MkDocs documentation to AsciiDoc, laid out the way Quarkus lays out its guides.
 *
 * <pre>
 * mkdocs2adoc --mkdocs &lt;repo&gt;/mkdocs.yml --out &lt;repo&gt;/docs/src/main/asciidoc \
 *     [--snippet-prefix docs/snippets/=] [--project-name "SmallRye GraphQL"] [--javadoc-version org.apache.kafka=kafka-version]
 * </pre>
 *
 * For every page in the MkDocs nav it writes &lt;name&gt;.adoc next to:
 * <ul>
 * <li>_attributes.adoc: attributes every page includes (ids, examples-dir, project values)</li>
 * <li>_nav.yml: the sidebar, from the MkDocs nav</li>
 * <li>_link-map.json: old MkDocs page path -&gt; new page name, for redirects and link rewriting</li>
 * </ul>
 * MkDocs-only syntax is turned into its AsciiDoc equivalent:
 * <ul>
 * <li>!!! note "Title" -&gt; NOTE admonition with a title</li>
 * <li>??? / ???+ note -&gt; collapsible block</li>
 * <li>=== "Tab" -&gt; tabs (an example block with the "tabs" role)</li>
 * <li>--8&lt;-- "path" -&gt; include::{examples-dir}/path[]</li>
 * <li>{{ insert('f', 't') }} (docissimo) -&gt; include::{examples-dir}/f[tags=t]</li>
 * <li>{{ javadoc('a.b.C', false, 'group/artifact') }} (docissimo) -&gt; a javadoc.io link at {javadoc-version}, or the
 * attribute --javadoc-version names for the group</li>
 * <li>{{ attributes.x }} / {{ attributes['x'] }} -&gt; {x}</li>
 * <li>[text](page.md#a) -&gt; xref:page.adoc#a[text]</li>
 * </ul>
 * Pandoc does the Markdown to AsciiDoc conversion; it runs locally if installed, else in a container.
 */
@Command(name = "mkdocs2adoc", mixinStandardHelpOptions = true,
        description = "Convert a project's MkDocs documentation to AsciiDoc, laid out like the Quarkus guides.")
class MkDocs2Adoc implements Callable<Integer> {

    @Option(names = "--mkdocs", required = true, description = "path to mkdocs.yml")
    Path mkdocs;

    @Option(names = "--out", required = true, description = "output directory, e.g. docs/src/main/asciidoc")
    Path out;

    @Option(names = "--repo", description = "repository root (default: the directory of mkdocs.yml)")
    Path repo;

    @Option(names = "--snippet-prefix", description = "OLD=NEW: rewrite snippet paths starting with OLD to NEW "
            + "(relative to examples-dir); re:PATTERN=NEW replaces the part matching PATTERN")
    List<String> snippetPrefix = new ArrayList<>();

    @Option(names = "--examples-dir", defaultValue = "../java",
            description = "value of :examples-dir:, relative to the output directory")
    String examplesDir;

    @Option(names = "--project-name")
    String projectName;

    @Option(names = "--javadoc-version", description = "GROUP=ATTRIBUTE: javadoc links of the group's artifacts use "
            + "{ATTRIBUTE} (latest unless set) instead of the project's version, e.g. org.apache.kafka=kafka-version")
    Map<String, String> javadocVersions = new LinkedHashMap<>();

    @Option(names = "--rewrite-snippets",
            description = "rewrite docissimo <tag> markers in the example sources to AsciiDoc tag:: markers")
    boolean rewriteSnippets;

    @Override
    public Integer call() {
        new Converter(mkdocs, out, repo, examplesDir, projectName, snippetPrefix, javadocVersions, rewriteSnippets).run();
        return 0;
    }

    /** The conversion the sync runs for a release whose documentation is still MkDocs. */
    static void convert(Path mkdocs, Path out, Path repo, String examplesDir, String projectName,
            List<String> snippetPrefixes, Map<String, String> javadocVersions) {
        new Converter(mkdocs, out, repo, examplesDir, projectName, snippetPrefixes, javadocVersions, false).run();
    }

    static final Map<String, String> ADMONITION_TYPES = Map.ofEntries(
            Map.entry("note", "NOTE"), Map.entry("abstract", "NOTE"), Map.entry("summary", "NOTE"),
            Map.entry("tldr", "NOTE"), Map.entry("info", "NOTE"), Map.entry("todo", "NOTE"),
            Map.entry("question", "NOTE"), Map.entry("help", "NOTE"), Map.entry("faq", "NOTE"),
            Map.entry("example", "NOTE"), Map.entry("quote", "NOTE"), Map.entry("cite", "NOTE"),
            Map.entry("tip", "TIP"), Map.entry("hint", "TIP"), Map.entry("success", "TIP"),
            Map.entry("check", "TIP"), Map.entry("done", "TIP"), Map.entry("important", "IMPORTANT"),
            Map.entry("warning", "WARNING"), Map.entry("caution", "CAUTION"), Map.entry("attention", "WARNING"),
            Map.entry("failure", "CAUTION"), Map.entry("fail", "CAUTION"), Map.entry("missing", "CAUTION"),
            Map.entry("danger", "CAUTION"), Map.entry("error", "CAUTION"), Map.entry("bug", "CAUTION"));

    /** Placeholders survive pandoc as plain text and are swapped for AsciiDoc afterwards. */
    static final String MARK = "ZZADOC";

    static final String PANDOC_FROM = "markdown+fenced_divs+pipe_tables+backtick_code_blocks+raw_html-smart-tex_math_dollars";

    static String pandoc(String markdown) {
        return runPandoc(markdown, List.of("-f", PANDOC_FROM, "-t", "asciidoc", "--wrap=preserve",
                "--shift-heading-level-by=-1", "-s"));
    }

    /** A piece of a page (no title, no header), e.g. a table another page includes. */
    static String pandocFragment(String markdown) {
        return runPandoc(markdown, List.of("-f", PANDOC_FROM, "-t", "asciidoc", "--wrap=preserve"));
    }

    private static String runPandoc(String input, List<String> args) {
        List<String> cmd = new ArrayList<>();
        if (onPath("pandoc")) {
            cmd.add("pandoc");
        } else {
            cmd.addAll(List.of("podman", "run", "--rm", "-i", "docker.io/pandoc/core:3.6"));
        }
        cmd.addAll(args);
        try {
            Process process = new ProcessBuilder(cmd).start();
            Thread writer = new Thread(() -> {
                try (OutputStream in = process.getOutputStream()) {
                    in.write(input.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // the process ended early: its exit code says why
                }
            });
            writer.start();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errors = new Thread(() -> {
                try (InputStream e = process.getErrorStream()) {
                    e.transferTo(err);
                } catch (IOException e) {
                    // ignored
                }
            });
            errors.start();
            byte[] output;
            try (InputStream o = process.getInputStream()) {
                output = o.readAllBytes();
            }
            int code = process.waitFor();
            writer.join();
            errors.join();
            if (code != 0) {
                throw new IllegalStateException("pandoc failed (" + code + "): " + err.toString(StandardCharsets.UTF_8));
            }
            return universalNewlines(new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir.isEmpty() ? "." : dir, program);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Collect the indented body that follows lines[start-1]; return the body and the next index. */
    static int dedentBlock(List<String> lines, int start, int indent, List<String> body) {
        String spaces = " ".repeat(indent);
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (strip(line).isEmpty() || line.startsWith(spaces) || line.startsWith("\t")) {
                body.add(line.startsWith(spaces) ? line.substring(Math.min(indent, line.length()))
                        : line.startsWith("\t") ? lstripChars(line, "\t") : "");
                i++;
            } else {
                break;
            }
        }
        while (!body.isEmpty() && strip(body.get(body.size() - 1)).isEmpty()) {
            body.remove(body.size() - 1);
            i--;
        }
        return i;
    }

    static final Pattern COMMENT = p("^\\s*(//|#|<!--|/\\*|--|\\*)");

    /**
     * docissimo marks snippets with &lt;tag&gt; ... &lt;/tag&gt; on comment lines; AsciiDoc includes need
     * tag::tag[] ... end::tag[]. Only comment lines change, so XML elements of the same name stay.
     */
    static String convertMarkers(String text, Collection<String> tags) {
        List<String> out = new ArrayList<>();
        for (String line : Common.lines(text)) {
            if (COMMENT.matcher(line).lookingAt()) {
                for (String tag : tags) {
                    line = line.replace("</" + tag + ">", "end::" + tag + "[]").replace("<" + tag + ">",
                            "tag::" + tag + "[]");
                }
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    static final class Converter {
        final Path mkdocsFile;
        final Path repo;
        final Map<String, Object> config;
        final Path docsDir;
        final Path out;
        final String examplesDir;
        final String projectName;
        final boolean rewriteSnippets;
        final List<String[]> snippetPrefixes = new ArrayList<>();
        final Map<String, String> javadocVersions; // artifact group -> the attribute holding its version
        List<String> raw = new ArrayList<>(); // AsciiDoc fragments to restore after pandoc
        final List<String> warnings = new ArrayList<>();
        final Map<String, Set<String>> snippetTags = new LinkedHashMap<>(); // example path -> docissimo tags the pages include
        final Set<String> attrRefs = new HashSet<>(); // attributes the pages use (were jinja expressions)
        String pageDir = "";
        String pageRel = "";
        Map<?, ?> front = Map.of();

        Converter(Path mkdocs, Path out, Path repo, String examplesDir, String projectName, List<String> snippetPrefixes,
                Map<String, String> javadocVersions, boolean rewriteSnippets) {
            this.mkdocsFile = resolve(mkdocs);
            this.repo = repo != null ? resolve(repo) : mkdocsFile.getParent();
            Object loaded = mkdocsYaml().load(Common.read(mkdocsFile));
            this.config = loaded instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
            this.docsDir = resolve(mkdocsFile.getParent().resolve(str(get(config, "docs_dir", "docs"))));
            this.out = resolve(out);
            this.examplesDir = examplesDir;
            this.projectName = projectName;
            this.rewriteSnippets = rewriteSnippets;
            this.javadocVersions = javadocVersions;
            for (String prefix : snippetPrefixes) {
                this.snippetPrefixes.add(prefix.split("=", 2));
            }
        }

        /** A real file inside the docs (no symbolic link, nothing resolving outside the repository). */
        boolean contained(Path path) {
            if (Files.isSymbolicLink(path) || !resolve(path).startsWith(repo)) {
                return false;
            }
            for (Path p = path.getParent(); p != null; p = p.getParent()) {
                if (p.startsWith(repo) && Files.isSymbolicLink(p)) {
                    return false;
                }
            }
            return true;
        }

        // ---------- placeholders ----------
        String keep(String adoc) {
            raw.add(adoc);
            return MARK + (raw.size() - 1) + MARK;
        }

        String restore(String text) {
            // pandoc may escape or wrap the marker; match it loosely
            Pattern pattern = p(MARK + "(\\d+)" + MARK);
            for (int n = 0; n < 5; n++) { // nested placeholders
                String updated = Common.sub(pattern, text, m -> raw.get(Integer.parseInt(m.group(1))));
                if (updated.equals(text)) {
                    break;
                }
                text = updated;
            }
            return text;
        }

        // ---------- includes ----------
        String includeTarget(String path) {
            path = stripChars(strip(path), "'\"");
            for (String[] prefix : snippetPrefixes) {
                String old = prefix[0];
                String replacement = prefix[1];
                if (old.startsWith("re:")) { // re:<pattern>=<replacement for the matched part>
                    Pattern pattern = p(old.substring(3));
                    if (pattern.matcher(path).find()) {
                        return subFirst(pattern, path, m -> replacement);
                    }
                } else if (path.startsWith(old)) {
                    return replacement + path.substring(old.length());
                }
            }
            return path;
        }

        Path snippetRoot() {
            Map<String, Object> extra = extra();
            return resolve(mkdocsFile.getParent().resolve(str(get(extra, "snippet_dir", get(config, "docs_dir", "docs")))));
        }

        /** A docissimo insert of a Markdown file from the docs: that file is a partial. */
        String partialOf(String insertPath) {
            Path target = resolve(snippetRoot().resolve(stripChars(strip(insertPath), "'\"")));
            if (suffix(target).equals(".md") && target.startsWith(docsDir) && Files.isRegularFile(target)) {
                String rel = posix(docsDir.relativize(target));
                return rel.substring(0, rel.length() - ".md".length());
            }
            return null;
        }

        Map<String, Object> plantumlConfig() {
            Object plugins = config.get("plugins");
            if (plugins instanceof List<?> list) {
                for (Object plugin : list) {
                    if (plugin instanceof Map<?, ?> m && m.containsKey("build_plantuml")) {
                        Object cfg = m.get("build_plantuml");
                        return truthy(cfg) && cfg instanceof Map<?, ?> c ? cast(c) : new LinkedHashMap<>();
                    }
                }
            }
            return null;
        }

        /**
         * A .puml file with its !include lines inlined (only files inside the docs). Includes are
         * relative to the including file or, as PlantUML runs there, to the diagram root.
         */
        String plantumlSource(Path path, Path root, List<Path> seen) {
            if (!Files.isRegularFile(path) || !contained(path) || seen.contains(path)) {
                warnings.add("diagram include " + path.getFileName() + " not found");
                return "";
            }
            List<String> result = new ArrayList<>();
            Pattern skinparam = Pattern.compile("^\\s*skinparam\\s+padding\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | FLAGS);
            Pattern include = p("^\\s*!include\\s+(\\S+)\\s*$");
            for (String line : splitlines(Common.read(path))) {
                if (skinparam.matcher(line).lookingAt()) {
                    continue; // deprecated: current PlantUML prints a warning band into the diagram
                }
                Matcher m = include.matcher(line);
                if (m.lookingAt() && !p("^(https?:|<)").matcher(m.group(1)).lookingAt()) {
                    List<Path> candidates = List.of(resolve(path.getParent().resolve(m.group(1))),
                            resolve(root.resolve(m.group(1))));
                    Path found = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(candidates.get(0));
                    List<Path> next = new ArrayList<>(seen);
                    next.add(path);
                    result.add(plantumlSource(found, root, next));
                } else {
                    result.add(line);
                }
            }
            return String.join("\n", result);
        }

        /**
         * The build_plantuml plugin renders diagrams/&lt;name&gt;.puml to &lt;output_folder&gt;/&lt;name&gt;.svg:
         * such an image becomes a [plantuml] block, rendered by the sync (light theme; the
         * #only-dark variant is dropped).
         */
        String plantumlImages(String md) {
            Map<String, Object> cfg = plantumlConfig();
            if (cfg == null) {
                return md;
            }
            Path root = resolve(mkdocsFile.getParent().resolve(str(get(cfg, "diagram_root", "docs"))));
            String output = str(get(cfg, "output_folder", "out"));
            String inputs = str(get(cfg, "input_folder", "diagrams"));
            Path theme = null;
            if (truthy(cfg.get("theme_enabled")) && truthy(cfg.get("theme_light"))) {
                theme = root.resolve(inputs).resolve(str(get(cfg, "theme_folder", "include/themes")))
                        .resolve(str(cfg.get("theme_light")));
            }
            Path themeFile = theme;
            Pattern imageName = p("^(?:\\.\\./)*" + Pattern.quote(output) + "/([\\w-]+)\\.(svg|png)$");
            return Common.sub(p("!\\[([^\\]]*)\\]\\(([^)#\\s]+)(?:#only-(light|dark))?\\)"), md, m -> {
                String target = m.group(2);
                String variant = m.group(3);
                Matcher name = imageName.matcher(target);
                if (!name.lookingAt()) {
                    return m.group();
                }
                if ("dark".equals(variant)) {
                    return "";
                }
                Path source = root.resolve(inputs).resolve(name.group(1) + ".puml");
                if (!Files.isRegularFile(source)) {
                    warnings.add("diagram source " + source.getFileName() + " not found");
                    return m.group();
                }
                String text = plantumlSource(resolve(source), root, List.of());
                if (themeFile != null && Files.isRegularFile(themeFile)) {
                    text = subFirst(pM("^(@startuml.*)$"), text,
                            s -> s.group(1) + "\n" + plantumlSource(resolve(themeFile), root, List.of()));
                }
                return "\n" + keep("[plantuml," + name.group(1) + ",svg]\n----\n" + text + "\n----") + "\n";
            });
        }

        Set<String> findPartials(List<Path> pages) {
            Set<String> partials = new HashSet<>();
            Pattern insert = p("\\{\\{\\s*insert\\(\\s*['\"]([^'\"]+\\.md)['\"]");
            for (Path page : pages) {
                Matcher m = insert.matcher(Common.read(page));
                while (m.find()) {
                    String partial = partialOf(m.group(1));
                    if (partial != null) {
                        partials.add(partial);
                    }
                }
            }
            return partials;
        }

        // ---------- markdown pre-processing ----------
        /** Image paths are relative to the version root on the site (imagesdir). */
        String imagePath(String path) {
            if (p("^(https?:)?//").matcher(path).lookingAt() || path.startsWith("/")) {
                return path;
            }
            return normpath(join(pageDir, path));
        }

        String preprocess(String md, Path page) {
            md = md.replace("\r\n", "\n");
            pageRel = posix(docsDir.relativize(page));
            pageDir = dirname(pageRel);
            // front matter
            front = Map.of();
            if (md.startsWith("---\n")) {
                int end = md.indexOf("\n---", 4);
                if (end > 0) {
                    Object data = Common.loadYaml(md.substring(4, end));
                    front = truthy(data) && data instanceof Map<?, ?> m ? m : Map.of();
                    md = lstripChars(md.substring(end + 4), "\n");
                }
            }
            md = jinja(md);
            md = plantumlImages(md);
            // Material theme light/dark image pairs: keep the light one (the docs pages show x-dark.png, copied
            // with the other images, instead of x-light.png on the dark theme)
            md = p("!\\[[^\\]]*\\]\\([^)\\s]+#only-dark\\)\\n?").matcher(md).replaceAll("");
            md = p("(!\\[[^\\]]*\\]\\([^)\\s]+)#only-light\\)").matcher(md).replaceAll("$1)");
            // Material icon shortcodes and the tags plugin's placeholder have no equivalent
            md = p(":(material|fontawesome|octicons|simple)-[\\w-]+:\\s?").matcher(md).replaceAll("");
            md = pM("^\\s*\\[TAGS\\]\\s*$").matcher(md).replaceAll("");
            // <br> in table cells: pandoc drops it; AsciiDoc has hard line breaks
            List<String> rows = new ArrayList<>();
            Pattern br = p("<br\\s*/?>");
            for (String l : Common.lines(md)) {
                rows.add(lstrip(l).startsWith("|") ? Common.sub(br, l, x -> keep(" +\n")) : l);
            }
            md = String.join("\n", rows);
            // Material buttons ([text](url){ .md-button }): a link with the "button" role
            md = p("(\\]\\([^)\\s]+\\))\\{[^}]*\\.md-button\\b[^}]*\\}").matcher(md).replaceAll("$1ZZBUTTON");
            md = cards(md);
            return String.join("\n", blocks(Common.lines(md)));
        }

        /**
         * A grid of &lt;div markdown&gt; cards (&lt;div class="...grid..." markdown&gt;) becomes an open block
         * with the "cards" role holding a sidebar block per card; their headings are discrete.
         */
        String cards(String md) {
            List<String> result = new ArrayList<>();
            List<String> stack = new ArrayList<>();
            Pattern div = p("<div(?:\\s+class=\"([^\"]*)\")?\\s+markdown(?:=\"1\")?\\s*>");
            Pattern heading = p("#+\\s");
            for (String line : Common.lines(md)) {
                String tag = strip(line);
                Matcher opening = div.matcher(tag);
                boolean isOpening = opening.matches();
                String cls = isOpening ? opening.group(1) : null;
                String last = stack.isEmpty() ? null : stack.get(stack.size() - 1);
                if (isOpening && (cls == null ? "" : cls).contains("grid")) {
                    stack.add("cards");
                    result.addAll(List.of("", keep("[.cards]\n--"), ""));
                } else if (isOpening && (cls == null || cls.isEmpty()) && "cards".equals(last)) {
                    stack.add("card");
                    result.addAll(List.of("", keep("****"), ""));
                } else if (tag.equals("</div>") && !stack.isEmpty()) {
                    String kind = stack.remove(stack.size() - 1);
                    if (!kind.equals("other")) {
                        result.addAll(List.of("", keep(kind.equals("card") ? "****" : "--"), ""));
                    } else {
                        result.add(line);
                    }
                } else if (tag.startsWith("<div") && !stack.isEmpty()) {
                    stack.add("other");
                    result.add(line);
                } else if ("card".equals(last) && heading.matcher(line).lookingAt()) {
                    result.addAll(List.of("", keep("[discrete]"), "", line));
                } else {
                    result.add(line);
                }
            }
            return String.join("\n", result);
        }

        String jinja(String md) {
            // docissimo: {{ insert('path', 'tag') }} as the only content of a code block line
            // insert('file') / insert('file', 'tag') / insert('file', )
            md = Common.sub(p("\\{\\{\\s*insert\\(\\s*['\"]([^'\"]+)['\"]\\s*(?:,\\s*(?:['\"]([^'\"]*)['\"])?\\s*)?\\)\\s*\\}\\}"),
                    md, m -> {
                        String partial = partialOf(m.group(1));
                        if (partial != null) {
                            return keep("include::{partialsdir}/" + partial + ".adoc[]");
                        }
                        String path = includeTarget(m.group(1));
                        String tag = m.group(2) == null || m.group(2).isEmpty() ? null : m.group(2);
                        if (tag != null && !path.startsWith("{")) {
                            snippetTags.computeIfAbsent(path, k -> new HashSet<>()).add(tag);
                        }
                        // docissimo dedented what it inserted; indent=0 does the same
                        String attrs = tag != null ? "tags=" + tag + ";indent=0" : "indent=0";
                        String base = path.startsWith("{") ? "" : "{examples-dir}/";
                        return keep("include::" + base + path + "[" + attrs.replace(";", ",") + "]");
                    });
            // docissimo: {{ javadoc('a.b.C', full, 'group/artifact') }} -> a javadoc.io link
            md = Common.sub(p("\\{\\{\\s*javadoc\\((.+?)\\)\\s*\\}\\}"), md, m -> {
                List<Object> args;
                try { // the arguments are Python literals: 'a.b.C', False, 'group/artifact'
                    String jinja = Common.sub(p("\\b(true|false|none)\\b"), m.group(1), b -> capitalize(b.group(1)));
                    args = new PyLiteral("(" + jinja + ",)").tuple();
                } catch (IllegalArgumentException e) {
                    warnings.add("unparsable javadoc(" + m.group(1) + ")");
                    return m.group();
                }
                String clazz = pyStr(args.get(0));
                boolean full = args.size() > 1 && Boolean.TRUE.equals(args.get(1));
                Object artifact = args.size() > 2 && truthy(args.get(2)) ? args.get(2) : extra().get("javadoc_artifact");
                if (!truthy(artifact)) {
                    warnings.add("javadoc(" + clazz + ") without an artifact");
                    return "`" + clazz + "`";
                }
                String name = full ? clazz : clazz.substring(clazz.lastIndexOf('.') + 1);
                // another project's artifact (Kafka's clients) has its own version, not the project's
                String version = javadocVersions.getOrDefault(pyStr(artifact).split("/")[0], "javadoc-version");
                return keep("link:https://javadoc.io/doc/" + pyStr(artifact) + "/{" + version + "}/"
                        + clazz.replace('.', '/') + ".html[`" + name + "`]");
            });
            // docissimo: {{ image('images/x.png', 'Title') }} -> a titled image
            md = Common.sub(p("\\{\\{\\s*image\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)\\s*\\}\\}"), md,
                    m -> "\n" + keep("." + m.group(2) + "\nimage::" + imagePath(m.group(1)) + "[" + m.group(2) + "]") + "\n");
            // {{ attributes.x.y }} / {{ attributes['x'] }} / {{ x }}
            md = Common.sub(p("\\{\\{\\s*([^}]+?)\\s*\\}\\}"), md, m -> {
                String expr = strip(m.group(1));
                expr = p("\\[\\s*['\"]([^'\"]+)['\"]\\s*\\]").matcher(expr).replaceAll(".$1");
                expr = p("^(attributes|config\\.extra|extra)\\.").matcher(expr).replaceAll("");
                if (expr.equals("version.current") || expr.equals("config.extra.version.current")) {
                    expr = "project-version";
                }
                String name = expr.replace(".", "-").replace("_", "-");
                if (!p("[A-Za-z0-9-]+").matcher(name).matches()) {
                    warnings.add("unconverted jinja expression {{ " + m.group(1) + " }}");
                    return m.group();
                }
                attrRefs.add(name);
                return keep("{" + name + "}");
            });
            if (p("\\{%").matcher(md).find()) {
                warnings.add("jinja statements ({% ... %}) left as-is");
            }
            return md;
        }

        List<String> blocks(List<String> lines) {
            List<String> result = new ArrayList<>();
            int i = 0;
            String fence = null;
            Pattern fencePattern = p("(`{3,}|~{3,})");
            Pattern admonition = p("(!!!|\\?\\?\\?\\+?)\\s*([\\w-]+)(?:\\s+\"([^\"]*)\")?\\s*$");
            Pattern tab = p("===\\s+\"([^\"]*)\"\\s*$");
            Pattern anyTab = p("\\s*===\\s+\"([^\"]*)\"\\s*$");
            Pattern nextTab = p("\\s*===\\s+\"");
            while (i < lines.size()) {
                String line = lines.get(i);
                String stripped = lstrip(line);
                String indent = line.substring(0, line.length() - stripped.length());
                // inside a fenced code block only snippet includes are rewritten
                Matcher fm = fencePattern.matcher(stripped);
                boolean isFence = fm.lookingAt();
                if (fence != null) {
                    if (stripped.startsWith(fence)) {
                        fence = null;
                    } else if (stripped.startsWith("--8<--")) {
                        String target = includeTarget(strip(stripped.substring(6)));
                        line = indent + keep("include::{examples-dir}/" + target + "[]");
                    }
                    result.add(line);
                    i++;
                    continue;
                }
                if (stripped.startsWith("--8<--")) {
                    // pymdownx snippet outside a code block: the file's content is part of the page
                    String target = includeTarget(strip(stripped.substring(6)));
                    String base = target.startsWith("{") ? "" : "{examples-dir}/";
                    result.addAll(List.of("", indent + keep("include::" + base + target + "[]"), ""));
                    i++;
                    continue;
                }
                if (isFence) {
                    fence = fm.group(1);
                    String info = strip(stripped.substring(fence.length()));
                    String lang = !info.isEmpty() && !info.startsWith("{") && !info.startsWith("title=")
                            ? splitWhitespace(info).get(0)
                            : "";
                    Matcher title = p("title=\"([^\"]*)\"").matcher(info);
                    if (title.find()) { // pymdownx block title, kept as the AsciiDoc block title
                        result.addAll(List.of(indent + keep("." + title.group(1)), ""));
                    }
                    result.add(indent + fence + lang); // pandoc does not know pymdownx fence options
                    i++;
                    continue;
                }
                Matcher m = admonition.matcher(stripped);
                if (m.lookingAt()) {
                    List<String> body = new ArrayList<>();
                    i = dedentBlock(lines, i + 1, indent.length() + 4, body);
                    if (body.isEmpty()) {
                        // "!!!note" followed by an unindented paragraph: that paragraph is the note
                        while (i < lines.size() && !strip(lines.get(i)).isEmpty()) {
                            String l = lines.get(i);
                            body.add(l.startsWith(indent) ? l.substring(indent.length()) : l);
                            i++;
                        }
                    }
                    List<String> indented = new ArrayList<>();
                    for (String b : body) {
                        indented.add(b.isEmpty() ? b : indent + b);
                    }
                    body = blocks(indented);
                    String kind = ADMONITION_TYPES.getOrDefault(m.group(2).toLowerCase(java.util.Locale.ROOT), "NOTE");
                    String title = m.group(3);
                    boolean collapsible = m.group(1).startsWith("???");
                    result.addAll(wrapBlock(kind, title, body, collapsible, indent));
                    continue;
                }
                m = tab.matcher(stripped);
                if (m.lookingAt()) {
                    List<Map.Entry<String, List<String>>> tabs = new ArrayList<>();
                    while (i < lines.size()) {
                        m = anyTab.matcher(lines.get(i));
                        if (!m.lookingAt()) {
                            break;
                        }
                        List<String> body = new ArrayList<>();
                        i = dedentBlock(lines, i + 1, indent.length() + 4, body);
                        tabs.add(Map.entry(m.group(1), blocks(body)));
                        while (i < lines.size() && strip(lines.get(i)).isEmpty() && i + 1 < lines.size()
                                && nextTab.matcher(lines.get(i + 1)).lookingAt()) {
                            i++;
                        }
                    }
                    result.addAll(tabs(tabs, indent));
                    continue;
                }
                result.add(line);
                i++;
            }
            return result;
        }

        List<String> wrapBlock(String kind, String title, List<String> body, boolean collapsible, String indent) {
            // The body stays Markdown (pandoc converts it); the delimiters are AsciiDoc placeholders.
            String head;
            if (collapsible) {
                head = (title != null && !title.isEmpty() ? "." + title + "\n" : "." + Common.title(kind) + "\n")
                        + "[%collapsible]\n====";
            } else {
                head = "[" + kind + "]\n" + (title != null && !title.isEmpty() ? "." + title + "\n" : "") + "====";
            }
            List<String> result = new ArrayList<>(List.of("", indent + keep(head), ""));
            result.addAll(body);
            result.addAll(List.of("", indent + keep("===="), ""));
            return result;
        }

        List<String> tabs(List<Map.Entry<String, List<String>>> tabs, String indent) {
            // An example block with the "tabs" role holding a labelled list: readable as is, and the
            // docs script on smallrye.io turns it into tabs.
            List<String> result = new ArrayList<>(List.of("", indent + keep("[.tabs]\n===="), ""));
            for (Map.Entry<String, List<String>> tab : tabs) {
                result.addAll(List.of(indent + keep(tab.getKey() + "::\n+\n--"), ""));
                result.addAll(tab.getValue());
                result.addAll(List.of("", indent + keep("--"), ""));
            }
            result.addAll(List.of(indent + keep("===="), ""));
            return result;
        }

        // ---------- AsciiDoc post-processing ----------
        String postprocess(String adoc, String title) {
            adoc = restore(adoc);
            List<String> lines = Common.lines(adoc);
            // pandoc's standalone header: "= Title" and attribute lines; replace with ours
            int bodyStart = 0;
            String docTitle = title;
            if (!lines.isEmpty() && lines.get(0).startsWith("= ")) {
                docTitle = strip(lines.get(0).substring(2));
                bodyStart = 1;
                while (bodyStart < lines.size() && lines.get(bodyStart).startsWith(":")) {
                    bodyStart++;
                }
            }
            String body = stripChars(String.join("\n", lines.subList(bodyStart, lines.size())), "\n");
            // links to other pages: link:page.md[..] / link:page.md#a[..] / link:./page.md
            body = Common.sub(p("link:((?:\\./)?[\\w./-]*\\.md)(#[^\\[\\s]+)?\\[([^\\]]*)\\]"), body, m -> {
                String target = m.group(1);
                String anchor = unquote(m.group(2) == null ? "" : m.group(2));
                String text = m.group(3);
                if (!anchor.isEmpty() && !p("#[\\w-]+").matcher(anchor).matches()) { // heading text: the heading's id
                    anchor = "#" + stripChars(p("[^\\w]+").matcher(anchor.substring(1).toLowerCase(java.util.Locale.ROOT))
                            .replaceAll("-"), "-");
                }
                // MkDocs links are relative to the page; xrefs on the site are relative to the version root
                String md = normpath(join(pageDir, target));
                if (!md.startsWith("..") && !Files.isRegularFile(docsDir.resolve(md))) {
                    warnings.add(pageRel + ": link to " + target + ", which is not in the docs, kept as text");
                    return text;
                }
                target = pageName(md) + ".adoc";
                return "xref:" + target + anchor + "[" + text + "]";
            });
            // links to HTML pages kept next to the pages (an API report): they become pages too, see htmlPages
            body = Common.sub(p("link:((?:\\./)?[\\w./-]*\\.html)(#[^\\[\\s]+)?\\["), body, m -> {
                String target = normpath(join(pageDir, m.group(1)));
                if (target.startsWith("..") || !htmlPage(docsDir.resolve(target))) {
                    return m.group();
                }
                return "xref:" + target.substring(0, target.length() - ".html".length()) + ".adoc"
                        + (m.group(2) == null ? "" : m.group(2)) + "[";
            });
            // links to a page without .md: page, page/ or /page (from the docs root, as MkDocs never resolved it)
            // become xrefs; a relative one to nothing in the docs was broken on MkDocs too, so it is text
            body = Common.sub(p("link:(/?[\\w.-][\\w./-]*?)/?(#[\\w-]+)?\\[([^\\]]*)\\]"), body, m -> {
                String path = m.group(1);
                if (path.equals(".") || suffix(Path.of(path)).matches("\\.(md|html|adoc)")) {
                    return m.group();
                }
                String target = path.startsWith("/") ? normpath(path.substring(1)) : normpath(join(pageDir, path));
                if (target.startsWith("..")) {
                    return m.group();
                }
                for (String md : List.of(target + ".md", target + "/index.md")) {
                    if (Files.isRegularFile(docsDir.resolve(md)) && contained(docsDir.resolve(md))) {
                        return "xref:" + pageName(md) + ".adoc" + (m.group(2) == null ? "" : m.group(2)) + "[" + m.group(3) + "]";
                    }
                }
                if (!path.startsWith("/") && !Files.exists(docsDir.resolve(target))) {
                    warnings.add(pageRel + ": link to " + path + ", which is not in the docs, kept as text");
                    return m.group(3);
                }
                return m.group();
            });
            body = p("link:(\\./)?(#[\\w-]+)\\[").matcher(body).replaceAll("<<$2[").replace("<<#", "<<");
            // <<id[text]> is invalid; turn internal anchors into xref
            body = p("<<([\\w-]+)\\[([^\\]]*)\\]").matcher(body).replaceAll("xref:#$1[$2]");
            // pandoc wraps placeholder lines in passthroughs when they start with special chars
            body = p("\\+\\+\\+(include::[^+]+?)\\+\\+\\+").matcher(body).replaceAll("$1");
            body = fixSectionLevels(body);
            body = pM("^\\[discrete\\]\\n\\n").matcher(body).replaceAll("[discrete]\n");
            // diagram fences (```mermaid) are diagram blocks, which the sync renders
            body = pM("^\\[source,(mermaid|plantuml)\\]$").matcher(body).replaceAll("[$1]");
            body = Common.sub(p("\\[([^\\]]*)\\]ZZBUTTON"), body, m -> "[" + m.group(1) + ",role=button]");
            // a block title placeholder stays directly above its block
            body = pM("^(\\.[^\\s.][^\\n]*)\\n\\n(\\[source)").matcher(body).replaceAll("$1\n$2");
            // listings that use attributes need attribute substitution, which AsciiDoc skips there
            body = Common.sub(Pattern.compile("^(\\[source[^\\]]*\\])(\\n-{4,}\\n.*?\\n-{4,})$",
                    Pattern.MULTILINE | Pattern.DOTALL | FLAGS), body, m -> {
                        String head = m.group(1);
                        String content = m.group(2);
                        if (attrRefs.stream().anyMatch(a -> content.contains("{" + a + "}")) && !head.contains("subs=")) {
                            head = head.substring(0, head.length() - 1) + ",subs=\"attributes+\"]";
                        }
                        return head + content;
                    });
            body = Common.sub(p("(image::?)([^\\[\\s:]+)\\["), body, m -> m.group(1) + imagePath(m.group(2)) + "[");
            int depth = pageDir.isEmpty() ? 0 : count(pageDir, '/') + 1;
            List<String> header = new ArrayList<>();
            header.add("= " + docTitle);
            if (depth > 0) {
                header.add(":version-root: " + "../".repeat(depth));
                header.add("include::{version-root}_attributes.adoc[]");
            } else {
                header.add("include::_attributes.adoc[]");
            }
            Object summary = truthy(front.get("description")) ? front.get("description") : front.get("summary");
            if (truthy(summary)) {
                header.add(":summary: " + pyStr(summary));
            }
            Object tags = front.get("tags");
            if (truthy(tags)) {
                List<String> topics = new ArrayList<>();
                for (Object t : tags instanceof List<?> l ? l : List.of(tags)) {
                    topics.add(pyStr(t));
                }
                header.add(":topics: " + String.join(",", topics));
            }
            return String.join("\n", header) + "\n\n" + body + "\n";
        }

        /** Markdown lets a page skip heading levels (# then ###); AsciiDoc does not. Close the gaps. */
        static String fixSectionLevels(String body) {
            List<String> result = new ArrayList<>();
            String fence = null;
            int previous = 1;
            Pattern delimiter = p("^(----|\\.\\.\\.\\.|````*)\\s*$");
            Pattern section = p("^(={2,6}) (\\S.*)$");
            for (String line : Common.lines(body)) {
                if (delimiter.matcher(line).lookingAt()) {
                    String s = strip(line);
                    fence = s.equals(fence) ? null : (fence != null ? fence : s);
                }
                Matcher m = fence != null ? null : section.matcher(line);
                if (m != null && m.lookingAt()) {
                    int level = Math.min(m.group(1).length(), previous + 1);
                    previous = level;
                    line = "=".repeat(level) + " " + m.group(2);
                }
                result.add(line);
            }
            return String.join("\n", result);
        }

        /**
         * The MkDocs attributes file (extra.attributes_path), flattened: versions.camel becomes
         * versions-camel. Maven filters it at build time; its ${...} placeholders are resolved here
         * from the poms' properties and version, as far as they can be.
         */
        Map<String, String> projectAttributes() {
            Map<String, Object> extra = extra();
            Object pathValue = extra.get("attributes_path");
            if (!truthy(pathValue)) {
                return Map.of();
            }
            String path = pyStr(pathValue);
            Path base = mkdocsFile.getParent();
            List<Path> candidates = List.of(base.resolve(path),
                    base.resolve(p("^target/classes/").matcher(path).replaceFirst("src/main/resources/")));
            Path source = candidates.stream().filter(c -> Files.exists(c) && !parts(c).contains("target")).findFirst()
                    .orElse(candidates.stream().filter(Files::exists).findFirst().orElse(null));
            if (source == null) {
                warnings.add("attributes file " + path + " not found");
                return Map.of();
            }
            Object loaded = Common.loadYaml(Common.read(source));
            Object data = truthy(loaded) && loaded instanceof Map<?, ?> m ? m.get("attributes") : null;
            if (data == null && !(truthy(loaded) && loaded instanceof Map<?, ?> m2 && m2.containsKey("attributes"))) {
                data = new LinkedHashMap<>();
            }
            Map<String, String> props = mavenProperties();
            Map<String, String> flat = new LinkedHashMap<>();
            walk("", data, flat);
            Map<String, String> resolved = new LinkedHashMap<>();
            Pattern placeholder = p("\\$\\{([^}]+)\\}");
            Pattern parsedVersion = p("(\\$\\{parsedVersion\\.\\w+\\}\\.?)+");
            for (Map.Entry<String, String> e : flat.entrySet()) {
                String value = e.getValue();
                String v = Common.sub(placeholder, value, m -> {
                    String key = m.group(1);
                    if (key.equals("project.version")) {
                        return !flat.containsKey("project-version") || !flat.get("project-version").equals(value)
                                ? "{project-version}"
                                : props.getOrDefault(key, "");
                    }
                    return props.getOrDefault(key, m.group());
                });
                if (parsedVersion.matcher(v).matches()) {
                    v = "{project-version}"; // major.minor.incremental of the release
                }
                if (v.contains("${")) {
                    warnings.add("attribute " + e.getKey() + ": unresolved " + v);
                    continue;
                }
                resolved.put(e.getKey(), v);
            }
            return resolved;
        }

        private static void walk(String prefix, Object value, Map<String, String> flat) {
            if (value instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    walk(prefix.isEmpty() ? pyStr(e.getKey()) : prefix + "-" + pyStr(e.getKey()), e.getValue(), flat);
                }
            } else {
                flat.put(prefix.replace("_", "-"), pyStr(value));
            }
        }

        /** Properties and version from the poms between the docs and the repository root. */
        Map<String, String> mavenProperties() {
            Map<String, String> props = new LinkedHashMap<>();
            Path folder = mkdocsFile.getParent();
            List<Path> poms = new ArrayList<>();
            while (true) {
                if (Files.exists(folder.resolve("pom.xml"))) {
                    poms.add(folder.resolve("pom.xml"));
                }
                if (folder.equals(repo) || folder.getParent() == null || !folder.startsWith(repo)) {
                    break;
                }
                folder = folder.getParent();
            }
            for (int n = poms.size() - 1; n >= 0; n--) { // the closest pom wins
                Element root = parseXml(poms.get(n));
                Element properties = child(root, "properties");
                if (properties != null) {
                    for (Node node = properties.getFirstChild(); node != null; node = node.getNextSibling()) {
                        if (node instanceof Element prop) {
                            props.put(localName(prop), strip(text(prop)));
                        }
                    }
                }
                Element version = child(root, "version");
                if (version == null) {
                    Element parent = child(root, "parent");
                    version = parent == null ? null : child(parent, "version");
                }
                if (version != null && !text(version).isEmpty()) {
                    props.put("project.version", strip(text(version)));
                }
            }
            Matcher parts = p("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(props.getOrDefault("project.version", ""));
            if (parts.lookingAt()) {
                props.put("parsedVersion.majorVersion", parts.group(1));
                props.put("parsedVersion.minorVersion", parts.group(2));
                props.put("parsedVersion.incrementalVersion", parts.group(3));
            }
            Pattern placeholder = p("\\$\\{([^}]+)\\}");
            for (int n = 0; n < 3; n++) { // properties referring to properties
                Map<String, String> previous = props;
                Map<String, String> next = new LinkedHashMap<>();
                previous.forEach((k, v) -> next.put(k,
                        Common.sub(placeholder, v, m -> previous.getOrDefault(m.group(1), m.group()))));
                props = next;
            }
            return props;
        }

        /**
         * docissimo marks snippets with &lt;tag&gt; ... &lt;/tag&gt; comments; AsciiDoc uses tag::tag[] and
         * end::tag[]. Rewrite the markers of the tags the pages use, in place.
         */
        int rewriteSnippetMarkers(Path examplesRoot) {
            int changed = 0;
            for (Map.Entry<String, Set<String>> e : snippetTags.entrySet()) {
                Path f = examplesRoot.resolve(e.getKey());
                if (!Files.isRegularFile(f) || Files.isSymbolicLink(f) || !resolve(f).startsWith(examplesRoot)) {
                    warnings.add("snippet " + e.getKey() + " not found under " + examplesRoot);
                    continue;
                }
                String text = Common.read(f);
                String updated = convertMarkers(text, e.getValue());
                if (!updated.equals(text)) {
                    Common.write(f, updated);
                    changed++;
                }
            }
            return changed;
        }

        // ---------- nav ----------
        List<Map<String, Object>> nav() {
            Object nav = config.get("nav");
            if (!truthy(nav)) {
                List<Object> pages = new ArrayList<>();
                for (Path p : sortedPython(rglob(docsDir, ".md"))) {
                    pages.add(posix(docsDir.relativize(p)));
                }
                nav = pages;
            }
            return walkNav(nav);
        }

        private List<Map<String, Object>> walkNav(Object items) {
            List<Map<String, Object>> result = new ArrayList<>();
            if (!(items instanceof List<?> list)) {
                return result;
            }
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(entry("page", pageName(s)));
                    continue;
                }
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object title = e.getKey();
                    Object value = e.getValue();
                    if (value instanceof List<?>) {
                        Map<String, Object> section = entry("title", title);
                        section.put("items", walkNav(value));
                        result.add(section);
                    } else if (value instanceof String s && p("https?://").matcher(s).lookingAt()) {
                        Map<String, Object> link = entry("title", title);
                        link.put("url", s);
                        result.add(link);
                    } else if (value instanceof String s && p("(^|/)apidocs(/|$)").matcher(s).find()) {
                        // the aggregated javadoc built at release: javadoc.io has it
                        Object artifact = extra().get("javadoc_artifact");
                        if (truthy(artifact)) {
                            Map<String, Object> link = entry("title", title);
                            link.put("url", "https://javadoc.io/doc/" + pyStr(artifact));
                            result.add(link);
                        }
                    } else if (value instanceof String s && !s.endsWith(".md")) {
                        warnings.add("nav entry " + pyStr(title) + ": " + s + " is not a page, dropped");
                    } else if (value instanceof String s) {
                        Map<String, Object> page = entry("title", title);
                        page.put("page", pageName(s));
                        result.add(page);
                    }
                }
            }
            return result;
        }

        private static Map<String, Object> entry(String key, Object value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(key, value);
            return m;
        }

        static String pageName(String mdPath) {
            // Quarkus-style file names: hyphens, not underscores (server_configuration -> server-configuration)
            String name = p("\\.md$").matcher(p("^(\\./)+").matcher(mdPath).replaceAll("")).replaceAll("");
            List<String> parts = new ArrayList<>();
            for (String part : name.split("/", -1)) {
                parts.add(p("(?<=.)_").matcher(part).replaceAll("-"));
            }
            return String.join("/", parts);
        }

        @SuppressWarnings("unchecked")
        private void navPages(List<Map<String, Object>> nav, Map<String, Object> titles) {
            for (Map<String, Object> item : nav) {
                if (item.containsKey("page")) {
                    titles.put((String) item.get("page"), item.get("title"));
                }
                Object items = item.get("items");
                navPages(items instanceof List<?> ? (List<Map<String, Object>>) items : List.of(), titles);
            }
        }

        /** A complete HTML document (not a theme template), which MkDocs publishes as it is. */
        boolean htmlPage(Path file) {
            if (!Files.isRegularFile(file) || !suffix(file).equals(".html") || !contained(file) || isParent(out, file)) {
                return false;
            }
            String head = Common.read(file).stripLeading().toLowerCase(java.util.Locale.ROOT);
            return head.startsWith("<!doctype html") || head.startsWith("<html");
        }

        /**
         * HTML pages next to the Markdown (such as a generated API compatibility report): each becomes a page
         * of its own, with the document's content passed through, so the links to it keep working.
         */
        void htmlPages(Map<String, String> linkMap) {
            for (Path file : sortedPython(rglob(docsDir, ".html"))) {
                if (!htmlPage(file)) {
                    continue;
                }
                String html = Common.read(file);
                String rel = posix(docsDir.relativize(file));
                String name = rel.substring(0, rel.length() - ".html".length());
                Matcher title = p("<title>(.*?)</title>").matcher(html);
                // an Asciidoctor document: its content without the header and footer; else the body
                Matcher content = Pattern.compile("<div id=\"content\">(.*)</div>\\s*<div id=\"footer\">", Pattern.DOTALL).matcher(html);
                Matcher body = Pattern.compile("<body[^>]*>(.*)</body>", Pattern.DOTALL).matcher(html);
                String passed = content.find() ? content.group(1) : body.find() ? body.group(1) : html;
                int depth = count(name, '/');
                List<String> lines = new ArrayList<>();
                lines.add("= " + (title.find() ? title.group(1).strip().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                        : Common.title(name.substring(name.lastIndexOf('/') + 1).replace("-", " "))));
                if (depth > 0) {
                    lines.add(":version-root: " + "../".repeat(depth));
                    lines.add("include::{version-root}_attributes.adoc[]");
                } else {
                    lines.add("include::_attributes.adoc[]");
                }
                lines.add("");
                lines.add("++++");
                lines.add(passed.strip());
                lines.add("++++");
                Common.write(out.resolve(name + ".adoc"), String.join("\n", lines) + "\n");
                linkMap.put(rel, name);
            }
        }

        // ---------- main ----------
        void run() {
            try {
                Files.createDirectories(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<Map<String, Object>> nav = nav();
            Map<String, Object> titles = new LinkedHashMap<>();
            navPages(nav, titles);
            List<Path> pages = sortedPython(rglob(docsDir, ".md").stream()
                    .filter(p -> !p.getFileName().toString().startsWith("_") && !isParent(out, p) && contained(p))
                    .toList());
            Map<String, String> linkMap = new LinkedHashMap<>();
            Set<String> partials = findPartials(pages);
            for (Path page : pages) {
                String rel = posix(docsDir.relativize(page));
                String stem = rel.substring(0, rel.length() - ".md".length());
                if (partials.contains(stem)) {
                    raw = new ArrayList<>();
                    String body = restore(pandocFragment(preprocess(Common.read(page), page)));
                    Common.write(out.resolve("_partials").resolve(stem + ".adoc"), body);
                    continue;
                }
                String name = pageName(rel);
                if (rel.toLowerCase(java.util.Locale.ROOT).equals("readme.md") && !titles.containsKey(name)) {
                    continue;
                }
                raw = new ArrayList<>();
                String md = preprocess(Common.read(page), page);
                String adoc = pandoc(md);
                Object title = truthy(front.get("title")) ? front.get("title")
                        : truthy(titles.get(name)) ? titles.get(name) : Common.title(name.replace("-", " "));
                adoc = postprocess(adoc, pyStr(title));
                Common.write(out.resolve(name + ".adoc"), adoc);
                // MkDocs directory URLs: page.md -> page/, index.md -> ""
                String orig = p("\\.md$").matcher(rel).replaceAll("");
                String old = orig.equals("index") ? ""
                        : orig.endsWith("/index") ? orig.substring(0, orig.length() - "index".length()) : orig + "/";
                linkMap.put(old, name);
                if (adoc.contains(MARK)) {
                    warnings.add(rel + ": unresolved placeholder");
                }
            }
            htmlPages(linkMap);
            // images and other assets next to the pages
            Set<String> images = Set.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp");
            for (Path asset : walkAll(docsDir)) {
                if (isParent(out, asset) || !contained(asset)) {
                    continue; // the output may sit inside docs_dir; symbolic links are never followed
                }
                if (Files.isRegularFile(asset) && images.contains(suffix(asset).toLowerCase(java.util.Locale.ROOT))) {
                    Path dest = out.resolve(docsDir.relativize(asset));
                    try {
                        Files.createDirectories(dest.getParent());
                        Files.copy(asset, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            Map<String, Object> navFile = new LinkedHashMap<>();
            navFile.put("title", projectName != null && !projectName.isEmpty() ? projectName : config.get("site_name"));
            navFile.put("nav", nav);
            Common.write(out.resolve("_nav.yml"), Common.dumpYamlInOrder(navFile));
            Path attributes = out.resolve("_attributes.adoc");
            if (!Files.exists(attributes)) {
                List<String> lines = new ArrayList<>(List.of(
                        "// Attributes shared by every page. smallrye.io overrides the ones it needs when it publishes.",
                        "// Pages in subdirectories set :version-root: (../ per level) before including this file.",
                        "ifndef::version-root[:version-root:]",
                        ":idprefix:", ":idseparator: -", ":sectanchors:",
                        ":examples-dir: {version-root}" + examplesDir,
                        ":partialsdir: {version-root}_partials",
                        ":project-name: " + (projectName != null && !projectName.isEmpty() ? projectName
                                : pyStr(get(config, "site_name", ""))),
                        ":javadoc-version: latest"));
                // until smallrye.io sets them (maven-attributes), other projects' javadoc is their latest
                new TreeSet<>(javadocVersions.values()).forEach(a -> lines.add(":" + a + ": latest"));
                projectAttributes().forEach((k, v) -> lines.add(":" + k + ": " + v));
                Common.write(attributes, String.join("\n", lines) + "\n");
            }
            Common.write(out.resolve("_link-map.json"), json(linkMap, 0) + "\n");
            Map<String, Object> tags = new TreeMap<>();
            snippetTags.forEach((k, v) -> tags.put(k, new ArrayList<>(new TreeSet<>(v))));
            Common.write(out.resolve("_snippet-tags.json"), json(tags, 0) + "\n");
            if (rewriteSnippets) {
                Path root = resolve(out.resolve(examplesDir));
                System.out.println("rewrote snippet markers in " + rewriteSnippetMarkers(root) + " file(s) under " + root);
            }
            for (String w : warnings) {
                Common.log("WARN: " + w);
            }
            System.out.println("converted " + linkMap.size() + " pages into " + out);
        }

        Map<String, Object> extra() {
            Object extra = config.get("extra");
            return truthy(extra) && extra instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        }
    }

    // ---------- Python semantics ----------

    /** Python's re: Unicode classes, and only \n ends a line. */
    static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    static Pattern p(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    static Pattern pM(String regex) {
        return Pattern.compile(regex, FLAGS | Pattern.MULTILINE);
    }

    /** re.sub(pattern, function, text, count=1). */
    static String subFirst(Pattern pattern, String text, Function<MatchResult, String> replacement) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        return text.substring(0, m.start()) + replacement.apply(m.toMatchResult()) + text.substring(m.end());
    }

    static String universalNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static String strip(String s) {
        return lstrip(rstrip(s));
    }

    static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    static String rstrip(String s) {
        int i = s.length();
        while (i > 0 && isSpace(s.charAt(i - 1))) {
            i--;
        }
        return s.substring(0, i);
    }

    static String stripChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    static String lstripChars(String s, String chars) {
        int start = 0;
        while (start < s.length() && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        return s.substring(start);
    }

    static List<String> splitWhitespace(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (isSpace(c)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** str.splitlines(). */
    static List<String> splitlines(String s) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c' || c == '\u001d'
                    || c == '\u001e' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                result.add(s.substring(start, i));
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < s.length()) {
            result.add(s.substring(start));
        }
        return result;
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }

    static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return !s.isEmpty();
        }
        if (o instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (o instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (o instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** Python's str() of a YAML or literal value. */
    static String pyStr(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (o instanceof Double d) {
            if (d.isInfinite()) {
                return d > 0 ? "inf" : "-inf";
            }
            if (d.isNaN()) {
                return "nan";
            }
            return Double.toString(d);
        }
        if (o instanceof Date date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format(date);
        }
        if (o instanceof List<?> l) {
            List<String> items = new ArrayList<>();
            for (Object x : l) {
                items.add(x instanceof String s ? "'" + s + "'" : pyStr(x));
            }
            return "[" + String.join(", ", items) + "]";
        }
        return String.valueOf(o);
    }

    static Object get(Map<String, Object> map, String key, Object defaultValue) {
        return map.containsKey(key) ? map.get(key) : defaultValue;
    }

    static String str(Object o) {
        return o instanceof String s ? s : pyStr(o);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    static String posix(Path p) {
        return p.toString().replace(java.io.File.separatorChar, '/');
    }

    static List<String> parts(Path p) {
        List<String> parts = new ArrayList<>();
        for (Path part : p) {
            parts.add(part.toString());
        }
        return parts;
    }

    static boolean isParent(Path parent, Path p) {
        return p.startsWith(parent) && !p.equals(parent);
    }

    /** Path.suffix. */
    static String suffix(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i) : "";
    }

    /** Path.resolve(): absolute, symbolic links resolved, also for paths that do not exist. */
    static Path resolve(Path p) {
        Path abs = p.toAbsolutePath();
        Path current = abs.getRoot();
        for (Path name : abs) {
            String n = name.toString();
            if (n.equals(".")) {
                continue;
            }
            if (n.equals("..")) {
                current = current.getParent() == null ? current : current.getParent();
                continue;
            }
            Path next = current.resolve(n);
            if (Files.isSymbolicLink(next)) {
                try {
                    next = next.toRealPath();
                } catch (IOException e) {
                    try {
                        next = current.resolve(Files.readSymbolicLink(next)).normalize();
                    } catch (IOException e2) {
                        // keep it as it is
                    }
                }
            }
            current = next;
        }
        return current;
    }

    /** Python's ordering of paths: part by part. */
    static List<Path> sortedPython(List<Path> paths) {
        List<Path> sorted = new ArrayList<>(paths);
        sorted.sort(Common.BY_PARTS);
        return sorted;
    }

    /** Everything under root, without following symbolic links (Path.rglob('*')). */
    static List<Path> walkAll(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> !p.equals(root)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Path.rglob('*' + suffix), files only. */
    static List<Path> rglob(Path root, String suffix) {
        return walkAll(root).stream().filter(p -> p.getFileName().toString().endsWith(suffix))
                .filter(p -> Files.isRegularFile(p) || Files.isSymbolicLink(p)).toList();
    }

    static String normpath(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        int initialSlashes = path.startsWith("/") ? (path.startsWith("//") && !path.startsWith("///") ? 2 : 1) : 0;
        List<String> parts = new ArrayList<>();
        for (String comp : path.split("/")) {
            if (comp.isEmpty() || comp.equals(".")) {
                continue;
            }
            if (!comp.equals("..") || (initialSlashes == 0 && parts.isEmpty())
                    || (!parts.isEmpty() && parts.get(parts.size() - 1).equals(".."))) {
                parts.add(comp);
            } else if (!parts.isEmpty()) {
                parts.remove(parts.size() - 1);
            }
        }
        String result = "/".repeat(initialSlashes) + String.join("/", parts);
        return result.isEmpty() ? "." : result;
    }

    static String join(String a, String b) {
        if (b.startsWith("/") || a.isEmpty()) {
            return b;
        }
        return a.endsWith("/") ? a + b : a + "/" + b;
    }

    static String dirname(String path) {
        int i = path.lastIndexOf('/') + 1;
        String head = path.substring(0, i);
        if (!head.isEmpty() && !head.equals("/".repeat(head.length()))) {
            head = head.replaceAll("/+$", "");
        }
        return head;
    }

    /** urllib.parse.unquote: %xx escapes as UTF-8, '+' stays. */
    static String unquote(String s) {
        if (!s.contains("%")) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1))
                    && isHex(s.charAt(i + 2))) {
                bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 3;
                continue;
            }
            if (bytes.size() > 0) {
                out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                bytes.reset();
            }
            out.append(c);
            i++;
        }
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0 && c < 128;
    }

    static Element parseXml(Path path) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element e && localName(e).equals(name)
                    && java.util.Objects.equals(e.getNamespaceURI(), parent.getNamespaceURI())) {
                return e;
            }
        }
        return null;
    }

    /** ElementTree's .text: the character data before the first child element. */
    static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node node = e.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                break;
            }
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(node.getNodeValue());
            }
        }
        return sb.toString();
    }

    /** mkdocs.yml uses tags such as !ENV and !!python/name; their values do not matter here. */
    static Yaml mkdocsYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> true);
        SafeConstructor constructor = new SafeConstructor(options) {
            {
                AbstractConstruct ignored = new AbstractConstruct() {
                    @Override
                    public Object construct(org.yaml.snakeyaml.nodes.Node node) {
                        return null;
                    }
                };
                this.yamlMultiConstructors.put("", ignored);
                this.yamlMultiConstructors.put("!", ignored);
            }
        };
        return new Yaml(constructor, new Representer(new DumperOptions()), new DumperOptions(), options);
    }

    /** json.dumps(data, indent=2) (ASCII only, like Python's default). */
    static String json(Object o, int level) {
        String pad = "  ".repeat(level + 1);
        String end = "  ".repeat(level);
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            List<String> items = new ArrayList<>();
            m.forEach((k, v) -> items.add(pad + Common.jsonString(String.valueOf(k)) + ": " + json(v, level + 1)));
            return "{\n" + String.join(",\n", items) + "\n" + end + "}";
        }
        if (o instanceof List<?> l) {
            if (l.isEmpty()) {
                return "[]";
            }
            List<String> items = new ArrayList<>();
            l.forEach(v -> items.add(pad + json(v, level + 1)));
            return "[\n" + String.join(",\n", items) + "\n" + end + "]";
        }
        if (o == null) {
            return "null";
        }
        if (o instanceof Boolean || o instanceof Number) {
            return String.valueOf(o).toLowerCase(java.util.Locale.ROOT);
        }
        return Common.jsonString(String.valueOf(o));
    }

    /** The part of ast.literal_eval the docissimo javadoc() arguments need: a tuple of literals. */
    static final class PyLiteral {
        final String s;
        int i;

        PyLiteral(String s) {
            this.s = s;
        }

        List<Object> tuple() {
            skip();
            Object v = value();
            skip();
            if (i != s.length() || !(v instanceof List<?>)) {
                throw new IllegalArgumentException("not a tuple");
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) v;
            return list;
        }

        private void skip() {
            while (i < s.length() && isSpace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '(' || c == '[') {
                char close = c == '(' ? ')' : ']';
                i++;
                List<Object> items = new ArrayList<>();
                skip();
                if (i < s.length() && s.charAt(i) == close) {
                    i++;
                    return items;
                }
                while (true) {
                    items.add(value());
                    skip();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        skip();
                        if (i < s.length() && s.charAt(i) == close) {
                            i++;
                            return items;
                        }
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == close) {
                        i++;
                        if (c == '(' && items.size() == 1) {
                            return items.get(0); // (x) is not a tuple
                        }
                        return items;
                    }
                    throw new IllegalArgumentException("bad sequence");
                }
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                while (i < s.length() && (s.charAt(i) == '\'' || s.charAt(i) == '"')) { // adjacent strings concatenate
                    sb.append(string());
                    skip();
                }
                return sb.toString();
            }
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "._+-".indexOf(s.charAt(i)) >= 0)) {
                i++;
            }
            String word = s.substring(start, i);
            switch (word) {
                case "True":
                    return Boolean.TRUE;
                case "False":
                    return Boolean.FALSE;
                case "None":
                    return null;
                default:
                    try {
                        return Long.parseLong(word.replace("_", ""));
                    } catch (NumberFormatException e) {
                        try {
                            return Double.parseDouble(word.replace("_", ""));
                        } catch (NumberFormatException e2) {
                            throw new IllegalArgumentException("not a literal: " + word);
                        }
                    }
            }
        }

        private String string() {
            char quote = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == quote) {
                    return sb.toString();
                }
                if (c == '\n') {
                    throw new IllegalArgumentException("unterminated string");
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\', '\'', '"' -> sb.append(e);
                        case '\n' -> {
                        }
                        default -> sb.append('\\').append(e);
                    }
                    continue;
                }
                sb.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }
    }
}
