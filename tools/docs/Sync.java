import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Pull project documentation (AsciiDoc) into the site, the way quarkus.io pulls the Quarkus guides.
 *
 * <pre>
 * sync --auto                          sync every version that changed upstream (CI, nightly)
 * sync --auto --project smallrye-graphql
 * sync --project smallrye-graphql --version main --ref main
 * sync --project smallrye-graphql --version pr --source ../smallrye-graphql   (local checkout)
 * </pre>
 *
 * Projects and the versions they follow are in data/docs.yml. For each synced version this writes
 * content/docs/&lt;project&gt;/&lt;version&gt;/ (the pages, images, _attributes.adoc and the included
 * examples) and data/docversions.yml (which versions exist, from which ref, and their navigation).
 * A ref whose docs are still MkDocs or Antora is converted on the fly ({@link MkDocs2Adoc},
 * {@link Antora2Adoc}).
 */
@Command(name = "sync", sortOptions = false,
        description = "Pull project documentation (AsciiDoc) into the site.")
@SuppressWarnings("unchecked")
class Sync implements Callable<Integer> {

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "Show this help message and exit.")
    boolean help;
    @Option(names = "--auto", description = "sync every followed version whose ref moved")
    boolean auto;
    @Option(names = "--force", description = "with --auto: sync even if unchanged")
    boolean force;
    @Option(names = "--project")
    String projectKey;
    @Option(names = "--version", description = "version id: main, latest, or a stream id such as 2")
    String version;
    @Option(names = "--ref", description = "branch or tag to sync from")
    String ref;
    @Option(names = "--source", description = "use this local checkout instead of cloning")
    String source;
    @Option(names = "--summary", description = "append a markdown summary of what changed to this file")
    String summary;
    @Option(names = "--strict", description = "fail when an included example is missing")
    boolean strictOption;

    static final Pattern INCLUDE = Common.re("include::\\{examples-dir\\}/([^\\[]+)\\[([^\\]]*)\\]");
    static final Map<String, Integer> QUALIFIERS = Map.of("alpha", 0, "beta", 1, "m", 1, "cr", 2, "rc", 2, "final", 3,
            "", 3);
    static final java.util.Set<String> SKIPPED = java.util.Set.of("_link-map.json", "_snippet-tags.json", "pom.xml");
    /** Project keys and version ids are part of addresses; Roq turns dots into hyphens, so none here. */
    static final Pattern SAFE_NAME = Common.re("^[a-z0-9][a-z0-9-]*$");
    static final Pattern SAFE_PAGE = Common.re("^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$");
    static final Pattern COMMENT = Common.re("^\\s*(//|#|<!--|/\\*|--|\\*)");
    static final List<String> DIAGRAM_TYPES = List.of("mermaid", "plantuml", "graphviz", "ditaa", "d2", "bpmn",
            "blockdiag", "seqdiag", "nomnoml", "svgbob", "vega", "vegalite", "wavedrom", "erd", "excalidraw",
            "structurizr", "c4plantuml");
    static final Pattern DIAGRAM = Pattern.compile(
            "^\\[(?:source,)?(" + String.join("|", DIAGRAM_TYPES) + ")(?:,([^\\],]*))?[^\\]]*\\]\\n(-{4,}|\\.{4,})\\n(.*?)\\n\\3$",
            Pattern.MULTILINE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    static final Pattern GENERATED = Common.re("include::\\{generated-dir\\}/([\\w.-]+)\\[([^\\]]*)\\]");
    static final String MAVEN = "https://repo1.maven.org/maven2";
    static final String USER_AGENT = "smallrye.io-docs-sync";

    static boolean strict;
    /** In a preview, what is looked up once: the latest release, and the release's generated files. */
    static final Map<Object, String> releases = new java.util.HashMap<>();
    static Path releaseGenerated;
    /**
     * Set by the preview: what cannot be reached (Kroki, Maven Central, GitHub) is reported and
     * left out instead of stopping the sync.
     */
    static boolean lenient;

    static Path content() {
        return Common.SITE.resolve("content/docs");
    }

    static Path registryFile() {
        return Common.SITE.resolve("data/docs.yml");
    }

    static Path stateFile() {
        return Common.SITE.resolve("data/docversions.yml");
    }

    // ---- versions -----------------------------------------------------------------------------

    /** Sort key for release tags: 3.0.0.Beta8 &lt; 3.0.0.CR1 &lt; 3.0.0 = 3.0.0.Final. */
    record VersionKey(List<Integer> numbers, int qualifier, int number) implements Comparable<VersionKey> {
        @Override
        public int compareTo(VersionKey o) {
            int c = compareLists(numbers, o.numbers);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(qualifier, o.qualifier);
            return c != 0 ? c : Integer.compare(number, o.number);
        }
    }

    static int compareLists(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    static final Pattern VERSION = Common.re("^(\\d+(?:\\.\\d+)*)(?:[.-]?([A-Za-z]+)(\\d*))?");

    static VersionKey versionKey(String tag) {
        Matcher m = VERSION.matcher(tag);
        if (!m.lookingAt()) {
            return new VersionKey(List.of(), 0, 0);
        }
        List<Integer> numbers = new ArrayList<>();
        for (String n : m.group(1).split("\\.")) {
            numbers.add(Integer.parseInt(n));
        }
        String q = m.group(2) == null ? "" : m.group(2).toLowerCase();
        int qualifier = QUALIFIERS.getOrDefault(q, 0);
        int number = m.group(3) == null || m.group(3).isEmpty() ? 0 : Integer.parseInt(m.group(3));
        return new VersionKey(numbers, qualifier, number);
    }

    static Map<String, Map<String, Object>> loadRegistry() {
        Map<String, Object> data = (Map<String, Object>) Common.loadYaml(Common.read(registryFile()));
        Map<String, Map<String, Object>> registry = new LinkedHashMap<>();
        for (Object p : (List<Object>) data.get("projects")) {
            Map<String, Object> project = (Map<String, Object>) p;
            registry.put((String) project.get("key"), project);
        }
        return registry;
    }

    static Map<String, Object> loadState() {
        if (Files.exists(stateFile())) {
            Object state = Common.loadYaml(Common.read(stateFile()));
            return state == null ? new LinkedHashMap<>() : (Map<String, Object>) state;
        }
        return new LinkedHashMap<>();
    }

    static void saveState(Map<String, Object> state) {
        String header = "# Generated by tools/docs/Docs.java sync: the documentation versions on the site, the ref each\n"
                + "# came from, and its navigation. Do not edit; run the sync instead.\n";
        Common.write(stateFile(), header + Common.dumpYaml(state));
    }

    static String run(Path cwd, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            Process p = pb.start();
            p.getOutputStream().close();
            // both streams at once: a command that fills one pipe while the other is read would hang
            var err = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new CommandFailed(String.join(" ", cmd) + ": " + err.join().strip());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static class CommandFailed extends RuntimeException {
        CommandFailed(String message) {
            super(message);
        }
    }

    /** {'heads/main': sha, 'tags/1.2.3': sha} from GitHub. */
    static Map<String, String> remoteRefs(String repo) {
        String out = run(null, "git", "ls-remote", "--heads", "--tags", "https://github.com/" + repo + ".git");
        Map<String, String> refs = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            String ref = parts[1].startsWith("refs/") ? parts[1].substring(5) : parts[1];
            if (ref.endsWith("^{}")) { // annotated tag: the commit it points to
                ref = ref.substring(0, ref.length() - 3);
            }
            refs.put(ref, parts[0]);
        }
        return refs;
    }

    record Wanted(String id, String ref, String sha) {
    }

    /** [(version id, ref, sha)] the project follows. */
    static List<Wanted> wantedVersions(Map<String, Object> project, Map<String, String> refs) {
        List<Wanted> wanted = new ArrayList<>();
        String branch = (String) project.getOrDefault("branch", "main");
        if (!truthy(project.get("archived")) && refs.containsKey("heads/" + branch)) {
            wanted.add(new Wanted("main", branch, refs.get("heads/" + branch)));
        }
        List<String> tags = refs.keySet().stream().filter(r -> r.startsWith("tags/")).map(r -> r.substring(5)).toList();
        if (project.get("latest-tag") != null) {
            String newest = newest(tags, (String) project.get("latest-tag"));
            if (newest != null) {
                wanted.add(new Wanted("latest", newest, refs.get("tags/" + newest)));
            }
        }
        for (Object s : (List<Object>) project.getOrDefault("streams", List.of())) {
            Map<String, Object> stream = (Map<String, Object>) s;
            String newest = newest(tags, (String) stream.get("tag"));
            if (newest != null) {
                wanted.add(new Wanted(String.valueOf(stream.get("id")), newest, refs.get("tags/" + newest)));
            }
        }
        return wanted;
    }

    private static String newest(List<String> tags, String regex) {
        Pattern p = Common.re(regex);
        List<String> matching = new ArrayList<>(tags.stream().filter(t -> p.matcher(t).lookingAt()).toList());
        matching.sort(Comparator.comparing(Sync::versionKey));
        return matching.isEmpty() ? null : matching.get(matching.size() - 1);
    }

    /**
     * The newest release tag, for versions (main, a pull request) whose generated files are not
     * published: the latest release's are the closest.
     */
    static String latestRelease(Map<String, Object> project) {
        if (lenient && releases.containsKey(project.get("key"))) {
            return releases.get(project.get("key"));
        }
        try {
            Map<String, String> refs = remoteRefs((String) project.get("repo"));
            String latest = wantedVersions(project, refs).stream().filter(w -> w.id().equals("latest")).map(Wanted::ref)
                    .findFirst().orElse(null);
            if (lenient) {
                releases.put((String) project.get("key"), latest);
            }
            return latest;
        } catch (RuntimeException e) {
            if (!lenient) {
                throw e;
            }
            Common.log("  WARN could not look up the latest release: " + e.getMessage());
            return null;
        }
    }

    static Path checkout(String repo, String ref, Path into) {
        run(null, "git", "clone", "--quiet", "--depth", "1", "--branch", ref, "https://github.com/" + repo + ".git",
                into.toString());
        return into;
    }

    static Path convertMkdocs(Map<String, Object> project, Path checkoutDir, Path out) {
        Map<String, Object> cfg = (Map<String, Object>) project.get("mkdocs");
        Path mkdocs = checkoutDir.resolve((String) cfg.getOrDefault("config", "mkdocs.yml"));
        if (!Files.exists(mkdocs)) {
            return null;
        }
        Path examplesRoot = checkoutDir.resolve((String) cfg.getOrDefault("examples-root", "."));
        Object prefix = cfg.get("snippet-prefix");
        List<String> prefixes = prefix instanceof String s ? List.of(s)
                : prefix == null ? List.of() : ((List<Object>) prefix).stream().map(String::valueOf).toList();
        MkDocs2Adoc.convert(mkdocs, out, checkoutDir, relpath(examplesRoot, out), (String) project.get("name"),
                prefixes);
        return out;
    }

    /** Python's os.path.relpath(path, start). */
    static String relpath(Path path, Path start) {
        String rel = start.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
        return rel.isEmpty() ? "." : rel;
    }

    static Path convertAntora(Map<String, Object> project, Path checkoutDir, Path out) {
        Path component = resolve(checkoutDir.resolve((String) project.get("antora")));
        if (!Files.exists(component.resolve("antora.yml")) || !component.startsWith(resolve(checkoutDir))) {
            return null;
        }
        Antora2Adoc.convert(component, out);
        return out;
    }

    // ---- publishing ---------------------------------------------------------------------------

    /** Python's Path.resolve(): symbolic links followed, as far as the path exists. */
    static Path resolve(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            Path parent = absolute.getParent();
            return parent == null ? absolute : resolve(parent).resolve(absolute.getFileName());
        }
    }

    /**
     * docissimo marks snippets with &lt;tag&gt; ... &lt;/tag&gt; on comment lines; AsciiDoc includes
     * need tag::tag[] ... end::tag[]. Only comment lines change, so XML elements of the same name stay.
     */
    static String convertMarkers(String text, List<String> tags) {
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

    /** Releases documented with MkDocs still carry docissimo markers in their examples. */
    static void asciidocMarkers(Path path, List<String> tags) {
        String text = Common.read(path);
        String updated = convertMarkers(text, tags.stream().filter(t -> !text.contains("tag::" + t + "[]")).toList());
        if (!updated.equals(text)) {
            Common.write(path, updated);
        }
    }

    /**
     * Copy the docs directory. Symbolic links are skipped: a pull request must not be able to pull
     * files from elsewhere on the machine into the site.
     */
    static void copyTree(Path source, Path target) throws IOException {
        Path real = resolve(source);
        for (Path path : Common.walk(source)) {
            Path rel = source.relativize(path);
            if (Files.isSymbolicLink(path) || !resolve(path).startsWith(real)) {
                Common.log("  WARN skipped symbolic link " + rel);
                continue;
            }
            String name = path.getFileName().toString();
            if (SKIPPED.contains(name) || suffix(name).equals(".md")) {
                continue;
            }
            Path dest = target.resolve(rel.toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(dest);
            } else {
                Files.createDirectories(dest.getParent());
                Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** Python's Path.suffix. */
    static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot);
    }

    /** Python's Path.stem. */
    static String stem(String name) {
        String suffix = suffix(name);
        return name.substring(0, name.length() - suffix.length());
    }

    /**
     * Diagram blocks ([mermaid], [plantuml], ..., also written [source,mermaid]) become SVG images,
     * rendered once by Kroki (KROKI_URL, https://kroki.io by default) and kept with the version's images.
     */
    static void renderDiagrams(Path target) {
        String kroki = Objects.requireNonNullElse(System.getenv("KROKI_URL"), "https://kroki.io").replaceAll("/+$", "");
        for (Path page : Common.files(target, ".adoc")) {
            String text = Common.read(page);
            if (!DIAGRAM.matcher(text).find()) {
                continue;
            }
            String updated = Common.sub(DIAGRAM, text, m -> {
                String kind = m.group(1);
                String name = m.group(2) == null ? "" : m.group(2).strip();
                String diagram = m.group(4);
                String digest = sha1(kind + "\n" + diagram).substring(0, 12);
                Path image = target.resolve("images/diagrams/" + kind + "-" + digest + ".svg");
                if (!Files.exists(image)) {
                    try {
                        HttpResponse<byte[]> response = http().send(HttpRequest.newBuilder(URI.create(kroki + "/" + kind + "/svg"))
                                .timeout(Duration.ofSeconds(60)).header("Content-Type", "text/plain")
                                .header("User-Agent", USER_AGENT)
                                .POST(HttpRequest.BodyPublishers.ofString(diagram, StandardCharsets.UTF_8)).build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() != 200) {
                            throw new IOException("Kroki answered " + response.statusCode() + " for a " + kind + " diagram");
                        }
                        Files.createDirectories(image.getParent());
                        Files.write(image, response.body());
                    } catch (IOException e) {
                        if (!lenient) {
                            throw new UncheckedIOException(e);
                        }
                        Common.log("  WARN " + target.relativize(page) + ": diagram not rendered (" + e.getMessage() + ")");
                        return m.group();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return "image::images/diagrams/" + image.getFileName() + "[" + (name.isEmpty() ? kind + " diagram" : name)
                        + "]";
            });
            Common.write(page, updated);
        }
    }

    private static HttpClient client;

    static synchronized HttpClient http() {
        if (client == null) {
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30)).build();
        }
        return client;
    }

    static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static org.w3c.dom.Document parseXml(Path file) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name == null || name.equals(e.getLocalName()))) {
                out.add(e);
            }
        }
        return out;
    }

    /** ElementTree's findtext: the text of the first child element of that name, null without one. */
    static String childText(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent();
    }

    /**
     * Files a project's build generates into its jars (e.g. Reactive Messaging's connector attribute
     * tables in META-INF/connector/) are taken from the released jars on Maven Central: no build
     * needed. {@code version} is the release whose jars to use.
     */
    static void fetchGenerated(Map<String, Object> project, Path root, String version, Path out) throws IOException {
        Map<String, Object> cfg = (Map<String, Object>) project.get("generated");
        Path pom = resolve(root.resolve((String) cfg.get("maven-dependencies-of")));
        if (!Files.isRegularFile(pom) || !pom.startsWith(resolve(root))) {
            return;
        }
        Element tree = parseXml(pom).getDocumentElement();
        String ns = tree.getNamespaceURI();
        Files.createDirectories(out);
        String extract = (String) cfg.get("extract");
        Pattern plain = Common.re("^[\\w.-]+\\.(md|adoc|txt)$");
        Pattern artifactName = Common.re("^[\\w.-]+$");
        org.w3c.dom.NodeList deps = ns == null ? tree.getElementsByTagName("dependency")
                : tree.getElementsByTagNameNS(ns, "dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String group = childText(dep, "groupId");
            String artifact = childText(dep, "artifactId");
            if (!Objects.equals(group, cfg.get("group")) || artifact == null || artifact.isEmpty()
                    || !artifactName.matcher(artifact).find()) {
                continue;
            }
            String url = MAVEN + "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                    + version + ".jar";
            byte[] data;
            try {
                HttpResponse<byte[]> response = http().send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(120)).header("User-Agent", USER_AGENT).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    continue; // not every dependency is a published jar with generated files
                }
                data = response.body();
            } catch (IOException e) {
                if (!lenient) {
                    throw e;
                }
                Common.log("  WARN could not download " + url + ": " + e.getMessage());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            try (ZipInputStream jar = new ZipInputStream(new ByteArrayInputStream(data))) {
                for (ZipEntry entry; (entry = jar.getNextEntry()) != null;) {
                    String name = entry.getName().startsWith(extract) ? entry.getName().substring(extract.length()) : null;
                    if (name != null && plain.matcher(name).find()) { // plain file names only
                        Files.write(out.resolve(name), jar.readAllBytes());
                    }
                }
            }
        }
    }

    /**
     * Attributes taken from the Maven properties of the version published ({@code maven-attributes}:
     * attribute: property), so versions the pages mention follow the build without anyone copying
     * them. Only the repository's own pom is read; a property may refer to others in it.
     */
    static Map<String, String> mavenAttributes(Map<String, Object> project, Path root) {
        Map<String, Object> cfg = (Map<String, Object>) project.get("maven-attributes");
        if (cfg == null || cfg.isEmpty()) {
            return Map.of();
        }
        String pomName = (String) cfg.getOrDefault("pom", "pom.xml");
        Path pom = resolve(root.resolve(pomName));
        if (!Files.isRegularFile(pom) || !pom.startsWith(resolve(root))) {
            Common.log("  WARN maven-attributes: no " + pomName);
            return Map.of();
        }
        Element tree = parseXml(pom).getDocumentElement();
        Map<String, String> props = new LinkedHashMap<>();
        for (Element section : children(tree, "properties")) {
            for (Element prop : children(section, null)) {
                props.put(prop.getLocalName(), prop.getTextContent().strip());
            }
        }
        Pattern reference = Common.re("\\$\\{([^}]+)\\}");
        for (int i = 0; i < 3; i++) { // properties referring to properties
            Map<String, String> previous = props;
            Map<String, String> next = new LinkedHashMap<>();
            previous.forEach((k, v) -> next.put(k, Common.sub(reference, v,
                    m -> previous.getOrDefault(m.group(1), m.group()))));
            props = next;
        }
        Pattern plainValue = Common.re("[\\w.+-]+");
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) cfg.get("attributes")).entrySet()) {
            String value = props.getOrDefault(String.valueOf(e.getValue()), "");
            // attribute values end at the line: nothing that could start another attribute or directive
            if (plainValue.matcher(value).matches()) {
                values.put(e.getKey(), value);
            } else {
                Common.log("  WARN maven-attributes: property " + e.getValue() + " not found (or not a plain version) for "
                        + e.getKey());
            }
        }
        return values;
    }

    static String markdownToAsciidoc(String text) {
        List<String> args = List.of("-f", "gfm", "-t", "asciidoc", "--wrap=preserve");
        List<String> cmd = new ArrayList<>();
        if (onPath("pandoc")) {
            cmd.add("pandoc");
        } else {
            cmd.addAll(List.of("podman", "run", "--rm", "-i", "docker.io/pandoc/core:3.6"));
        }
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var in = p.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new CommandFailed("pandoc failed");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static boolean onPath(String command) {
        for (String dir : Objects.requireNonNullElse(System.getenv("PATH"), "").split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    /** Copy the generated files the pages include into _generated/, as AsciiDoc. */
    static void publishGenerated(Path target, Path generated) {
        for (Path page : Common.files(target, ".adoc")) {
            String text = Common.read(page);
            String updated = Common.sub(GENERATED, text, m -> {
                String name = m.group(1);
                String options = m.group(2);
                Path src = generated == null ? null : generated.resolve(name);
                if (src == null || !Files.isRegularFile(src)) {
                    Common.log("  WARN " + target.relativize(page) + ": generated file " + name + " not available");
                    return "NOTE: This table is generated from the project's build and is not available for this version.";
                }
                Path dest = target.resolve("_generated/" + stem(name) + ".adoc");
                String converted;
                if (suffix(name).equals(".adoc")) {
                    converted = Common.read(src);
                } else {
                    // .md and .txt (Stork's attribute tables) are Markdown
                    try {
                        converted = markdownToAsciidoc(Common.read(src));
                    } catch (RuntimeException e) {
                        // a preview without pandoc (or podman) uses the AsciiDoc twin newer builds generate
                        Path twin = generated.resolve(stem(name) + ".adoc");
                        if (!lenient) {
                            throw e;
                        } else if (Files.isRegularFile(twin)) {
                            converted = Common.read(twin);
                        } else {
                            Common.log("  WARN " + name + ": no pandoc (or podman) to convert it, left out");
                            return "NOTE: This table is generated from the project's build and is not shown in this preview.";
                        }
                    }
                }
                Common.write(dest, converted);
                return "include::{generated-dir}/" + dest.getFileName() + "[" + options + "]";
            });
            if (!updated.equals(text)) {
                Common.write(page, updated);
            }
        }
    }

    static final Pattern INDEX_XREF = Common.re("xref:((?:[\\w-]+/)*)index\\.adoc(#[\\w-]+)?\\[");

    /**
     * xref:index.adoc (or dir/index.adoc) would point to .../index/; the start page of a directory is
     * published at the directory itself.
     */
    static void indexLinks(Path target, String base) {
        for (Path page : Common.files(target, ".adoc")) {
            String text = Common.read(page);
            String updated = Common.sub(INDEX_XREF, text,
                    m -> "link:" + base + m.group(1) + (m.group(2) == null ? "" : m.group(2)) + "[");
            if (!updated.equals(text)) {
                Common.write(page, updated);
            }
        }
    }

    static String attribute(String text, String name) {
        Matcher m = Common.reM("^:" + Pattern.quote(name) + ":[ \\t]*(.*)$").matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    static boolean underscored(Path target, Path page) {
        for (Path part : target.relativize(page)) {
            if (part.toString().startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    static final Pattern TITLE_ATTRIBUTE = Common.re("\\{([\\w-]+)\\}");

    /**
     * A page title is read before the _attributes.adoc include below it, so attributes in it stay
     * unresolved ({project-name} Documentation); Antora set them for the whole component. Write the
     * value of those defined in _attributes.adoc into the title.
     */
    static void resolveTitles(Path target, String attributes) {
        for (Path page : Common.files(target, ".adoc")) {
            if (underscored(target, page)) {
                continue;
            }
            List<String> lines = Common.lines(Common.read(page));
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                String line = lines.get(i);
                if (line.startsWith("= ")) {
                    String fixed = Common.sub(TITLE_ATTRIBUTE, line, m -> {
                        String v = attribute(attributes, m.group(1));
                        return v != null && !v.isEmpty() && !v.contains("{") ? v : m.group();
                    });
                    if (!fixed.equals(line)) {
                        lines.set(i, fixed);
                        Common.write(page, String.join("\n", lines));
                    }
                    break;
                }
            }
        }
    }

    /**
     * Copy the AsciiDoc in {@code source} to content/docs/&lt;project&gt;/&lt;version&gt;/ and return its
     * nav. {@code roots}: the checkout (and the conversion output) the docs come from; nothing else is read.
     */
    static List<Object> publish(Map<String, Object> project, String version, String ref, Path source, boolean converted,
            List<Path> roots, Path generated, Map<String, String> maven) throws IOException {
        List<Path> resolvedRoots = roots.stream().map(Sync::resolve).toList();
        java.util.function.Predicate<Path> inside = path -> resolvedRoots.stream().anyMatch(path::startsWith);
        if (!inside.test(resolve(source))) {
            throw new Failure("the docs directory is outside the repository: " + source);
        }
        String key = (String) project.get("key");
        Path target = content().resolve(key).resolve(version);
        deleteTree(target);
        copyTree(source, target);

        renderDiagrams(target);
        DarkImages.process(target);
        publishGenerated(target, generated);
        indexLinks(target, "/docs/" + key + "/" + version + "/");

        Path attributesFile = target.resolve("_attributes.adoc");
        String attributes = Files.exists(attributesFile) ? Common.read(attributesFile) : "";
        String examplesDir = attribute(attributes, "examples-dir");
        // Copy only the example files the pages include, keeping their paths under _examples/.
        List<String> missing = new ArrayList<>();
        if (examplesDir != null) {
            // examples-dir comes from the project: it may use {version-root} and must stay in the checkout
            Path base = resolve(source.resolve(examplesDir.replace("{version-root}", "")));
            if (!inside.test(base)) {
                throw new Failure("examples-dir points outside the repository: " + examplesDir);
            }
            Pattern tagsOption = Common.re("tags?=([\\w;,-]+)");
            for (Path page : Common.files(target, ".adoc")) {
                List<String> absent = new ArrayList<>();
                Matcher m = INCLUDE.matcher(Common.read(page));
                while (m.find()) {
                    String rel = m.group(1);
                    String options = m.group(2);
                    Path src = resolve(base.resolve(rel));
                    if (!src.startsWith(base)) {
                        // never copied; a pull request (--strict) fails on it like on a missing example
                        Common.log("  WARN " + page.getFileName() + ": include outside the examples directory: " + rel);
                        missing.add(page.getFileName() + ": " + rel);
                        absent.add(rel);
                        continue;
                    }
                    if (Files.isRegularFile(src)) {
                        // the destination follows the resolved file, never the raw include path
                        Path dest = target.resolve("_examples").resolve(base.relativize(src).toString());
                        Files.createDirectories(dest.getParent());
                        if (!Files.exists(dest)) {
                            Files.copy(src, dest);
                        }
                        Matcher tags = tagsOption.matcher(options);
                        if (tags.find()) {
                            asciidocMarkers(dest, List.of(tags.group(1).split("[;,]", -1)));
                        }
                    } else {
                        missing.add(page.getFileName() + ": " + rel);
                        absent.add(rel);
                    }
                }
                if (!absent.isEmpty()) {
                    // the page renders without the example instead of showing an unresolved directive
                    String text = Common.read(page);
                    String optional = Common.sub(INCLUDE, text, i -> !absent.contains(i.group(1)) ? i.group()
                            : "include::{examples-dir}/" + i.group(1) + "[" + i.group(2) + (i.group(2).isEmpty() ? "" : ",")
                                    + "opts=optional]");
                    Common.write(page, optional);
                }
            }
        }
        for (String m : new TreeSet<>(missing)) {
            Common.log("  WARN missing example " + m);
        }
        if (!missing.isEmpty() && strict) {
            throw new Failure(new LinkedHashSet<>(missing).size() + " included example file(s) not found");
        }

        attributes = Common.reM("^:examples-dir:.*$").matcher(attributes)
                .replaceAll(Matcher.quoteReplacement(":examples-dir: {version-root}_examples"));
        attributes = Common.reM("^:generated-dir:.*\\n?").matcher(attributes).replaceAll("")
                + ":generated-dir: {version-root}_generated\n";
        if (examplesDir == null) {
            attributes += ":examples-dir: {version-root}_examples\n";
        }
        if (!attributes.contains("ifndef::version-root[")) {
            attributes = "ifndef::version-root[:version-root:]\n" + attributes;
        }
        boolean isTag = !version.equals("main") && !Common.re("^(main|master)$").matcher(ref).find();
        // project-version first, so attributes defined from it ({project-version}) resolve: the release,
        // or for main (and previews) the newest release, which is what readers put in their builds
        String release = isTag ? ref : (project.get("repo") != null ? latestRelease(project) : null);
        attributes = Common.reM("^:project-version:.*\\n?").matcher(attributes).replaceAll("");
        for (String name : maven.keySet()) {
            attributes = Common.reM("^:" + Pattern.quote(name) + ":.*\\n?").matcher(attributes).replaceAll("");
        }
        if (release != null) {
            attributes = ":project-version: " + release + "\n" + attributes;
        }
        attributes += "\n// Added by smallrye.io (tools/docs/Docs.java sync)\n"
                + ":javadoc-version: " + (release != null ? release : "latest") + "\n"
                + ":relfileprefix: /docs/" + key + "/" + version + "/\n"
                + ":imagesdir: /docs/" + key + "/" + version + "\n"
                + ":docs-project: " + key + "\n"
                + ":docs-version: " + version + "\n"
                + ":docs-ref: " + ref + "\n";
        StringBuilder mavenLines = new StringBuilder();
        maven.forEach((name, value) -> mavenLines.append(':').append(name).append(": ").append(value).append('\n'));
        attributes += mavenLines;
        Common.write(attributesFile, attributes);
        resolveTitles(target, attributes);

        Path navFile = target.resolve("_nav.yml");
        List<Object> nav;
        if (Files.exists(navFile)) {
            Object data = Common.loadYaml(Common.read(navFile));
            Object items = data instanceof Map<?, ?> m ? m.get("nav") : null;
            nav = items == null ? new ArrayList<>() : new ArrayList<>((List<Object>) items);
        } else {
            nav = new ArrayList<>();
            try (var listing = Files.list(target)) {
                for (Path p : listing.filter(p -> p.getFileName().toString().endsWith(".adoc")).sorted().toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith("_")) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("page", stem(name));
                        nav.add(item);
                    }
                }
            }
        }
        nav = resolveNavTitles(nav, target);
        long pages = Common.files(target, ".adoc").stream().filter(p -> !underscored(target, p)).count();
        Common.log("  " + key + " " + version + " <- " + ref + ": " + pages + " pages" + (converted ? " (converted)" : ""));
        return nav;
    }

    static final Pattern PAGE_TITLE = Common.reM("^= (.+)$");

    /**
     * Check the navigation and fill in missing titles from each page's '= Title' line. Links must be
     * http(s) and pages plain relative names: the nav comes from the project's repository.
     */
    static List<Object> resolveNavTitles(List<Object> input, Path target) {
        List<Map<String, Object>> nav = new ArrayList<>();
        for (Object i : input) {
            if (i instanceof Map<?, ?> m) {
                nav.add((Map<String, Object>) m);
            }
        }
        for (Map<String, Object> item : List.copyOf(nav)) {
            if (item.containsKey("url") && !Common.re("^https?://").matcher(String.valueOf(item.get("url"))).find()) {
                Common.log("  WARN dropped nav link " + pyRepr(item.get("url")));
                nav.remove(item);
                continue;
            }
            if (item.containsKey("page") && !SAFE_PAGE.matcher(String.valueOf(item.get("page"))).find()) {
                Common.log("  WARN dropped nav page " + pyRepr(item.get("page")));
                nav.remove(item);
            }
        }
        for (Map<String, Object> item : List.copyOf(nav)) {
            if (item.containsKey("page") && !Files.isRegularFile(target.resolve(item.get("page") + ".adoc"))) {
                Common.log("  WARN nav entry " + pyRepr(item.get("page")) + " has no page, dropped");
                nav.remove(item);
            }
        }
        for (Map<String, Object> item : nav) {
            if (item.containsKey("page") && !truthy(item.get("title"))) {
                Path page = target.resolve(item.get("page") + ".adoc");
                String title = null;
                if (Files.exists(page)) {
                    Matcher m = PAGE_TITLE.matcher(Common.read(page));
                    title = m.find() ? m.group(1).strip() : null;
                }
                item.put("title", title != null && !title.isEmpty() ? title
                        : Common.title(String.valueOf(item.get("page")).replace("-", " ")));
            }
            if (item.containsKey("items")) {
                item.put("items", resolveNavTitles((List<Object>) item.get("items"), target));
            }
        }
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> i : nav) {
            if (!i.containsKey("items") || truthy(i.get("items")) || i.containsKey("page")) {
                out.add(i);
            }
        }
        return out;
    }

    static String pyRepr(Object value) {
        return value instanceof String s ? "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'" : String.valueOf(value);
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var s = Files.walk(path)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** A sync that cannot go on: the message is what the reader needs to know. */
    static class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    static boolean syncVersion(Map<String, Object> project, String version, String ref, String sha,
            Map<String, Object> state, Path localSource) throws IOException {
        project = forStream(project, version);
        String key = (String) project.get("key");
        if (!SAFE_NAME.matcher(version).find() || !SAFE_NAME.matcher(key).find()) {
            throw new Failure("invalid project or version id: " + pyRepr(key) + " " + pyRepr(version));
        }
        if (!Common.re("^[A-Za-z0-9][A-Za-z0-9._/-]*$").matcher(ref).find() || ref.contains("..")) {
            throw new Failure("invalid ref: " + pyRepr(ref));
        }
        Path tmp = Files.createTempDirectory("docs-sync-");
        List<Object> nav;
        boolean converted = false;
        try {
            Path root = localSource != null ? resolve(localSource) : checkout((String) project.get("repo"), ref, tmp.resolve("repo"));
            Path docs = root.resolve((String) project.get("path"));
            Path generated = null;
            if (project.get("generated") != null) {
                String release = !version.equals("main") && localSource == null ? ref : latestRelease(project);
                if (lenient && releaseGenerated != null) {
                    generated = tmp.resolve("generated");
                    copyFiles(releaseGenerated, generated);
                } else if (release != null) {
                    generated = tmp.resolve("generated");
                    fetchGenerated(project, root, release, generated);
                    if (lenient) {
                        releaseGenerated = Files.createTempDirectory("docs-preview-generated-");
                        copyFiles(generated, releaseGenerated);
                    }
                }
                if (lenient) {
                    generated = localGenerated(project, root, generated == null ? tmp.resolve("generated") : generated);
                }
            }
            if (!Files.exists(docs.resolve("_attributes.adoc")) && !hasAdoc(docs)) {
                if (project.get("mkdocs") != null && convertMkdocs(project, root, tmp.resolve("converted")) != null) {
                    docs = tmp.resolve("converted");
                    converted = true;
                } else if (project.get("antora") != null && convertAntora(project, root, tmp.resolve("converted")) != null) {
                    docs = tmp.resolve("converted");
                    converted = true;
                } else {
                    Common.log("  " + key + " " + version + " <- " + ref + ": no AsciiDoc docs at " + project.get("path")
                            + ", skipped");
                    return false;
                }
            }
            Map<String, String> maven = mavenAttributes(project, root);
            nav = publish(project, version, ref, docs, converted, List.of(root, tmp), generated, maven);
        } finally {
            deleteTree(tmp);
        }
        Map<String, Object> entry = (Map<String, Object>) state.computeIfAbsent(key, k -> new LinkedHashMap<>());
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Object v : (List<Object>) ((Map<String, Object>) entry).getOrDefault("versions", List.of())) {
            if (!version.equals(((Map<String, Object>) v).get("id"))) {
                versions.add((Map<String, Object>) v);
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", version);
        v.put("ref", ref);
        v.put("sha", sha);
        v.put("converted", converted);
        v.put("nav", nav);
        v.put("synced", LocalDate.now().toString());
        versions.add(v);
        Map<String, Integer> order = Map.of("latest", 0, "main", 99);
        versions.sort(Comparator.<Map<String, Object>> comparingInt(x -> order.getOrDefault((String) x.get("id"), 1))
                .thenComparing((a, b) -> compareLists(negated(String.valueOf(a.get("ref"))), negated(String.valueOf(b.get("ref"))))));
        entry.put("versions", versions);
        return true;
    }

    static void copyFiles(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        if (Files.isDirectory(from)) {
            try (var s = Files.list(from)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    Files.copy(f, to.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * An older release line can lay its MkDocs sources out differently: a stream's own {@code mkdocs}
     * settings override the project's for that line.
     */
    static Map<String, Object> forStream(Map<String, Object> project, String version) {
        for (Object s : (List<Object>) project.getOrDefault("streams", List.of())) {
            Map<String, Object> stream = (Map<String, Object>) s;
            if (version.equals(String.valueOf(stream.get("id"))) && stream.get("mkdocs") instanceof Map<?, ?> own) {
                Map<String, Object> copy = new LinkedHashMap<>(project);
                Map<String, Object> mkdocs = new LinkedHashMap<>((Map<String, Object>) project.getOrDefault("mkdocs", Map.of()));
                mkdocs.putAll((Map<String, Object>) own);
                copy.put("mkdocs", mkdocs);
                return copy;
            }
        }
        return project;
    }

    private static List<Integer> negated(String ref) {
        return versionKey(ref).numbers().stream().map(n -> -n).toList();
    }

    private static boolean hasAdoc(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".adoc"));
        }
    }

    /**
     * For a preview: generated files the project's own build left in its target directories
     * (target/classes/&lt;extract&gt;) are newer than the release's, so they win.
     */
    static Path localGenerated(Map<String, Object> project, Path root, Path out) throws IOException {
        String extract = (String) ((Map<String, Object>) project.get("generated")).get("extract");
        List<Path> dirs;
        try (var s = Files.find(root, 4, (p, a) -> a.isDirectory() && p.endsWith(Path.of("target", "classes"))
                && Files.isDirectory(p.resolve(extract)))) {
            dirs = s.map(p -> p.resolve(extract)).toList();
        }
        if (dirs.isEmpty()) {
            return Files.isDirectory(out) ? out : null;
        }
        Files.createDirectories(out);
        Pattern plain = Common.re("^[\\w.-]+\\.(md|adoc|txt)$");
        for (Path dir : dirs) {
            try (var s = Files.list(dir)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    if (plain.matcher(f.getFileName().toString()).find()) {
                        Files.copy(f, out.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Common.log("  using generated files from the local build (" + dirs.size() + " module(s))");
        return out;
    }

    @Override
    public Integer call() throws IOException {
        strict = strictOption;
        Map<String, Map<String, Object>> registry = loadRegistry();
        Map<String, Object> state = loadState();
        List<String> changed = new ArrayList<>();
        try {
            if (auto) {
                List<String> keys = projectKey != null ? List.of(projectKey)
                        : registry.entrySet().stream().filter(e -> truthy(e.getValue().get("path"))).map(Map.Entry::getKey)
                                .toList();
                for (String key : keys) {
                    Map<String, Object> project = registry.get(key);
                    Map<String, Object> current = new LinkedHashMap<>();
                    Object entry = state.get(key);
                    if (entry instanceof Map<?, ?> m && m.get("versions") instanceof List<?> l) {
                        for (Object v : l) {
                            current.put(String.valueOf(((Map<String, Object>) v).get("id")), ((Map<String, Object>) v).get("sha"));
                        }
                    }
                    for (Wanted w : wantedVersions(project, remoteRefs((String) project.get("repo")))) {
                        if (force || !Objects.equals(current.get(w.id()), w.sha())) {
                            if (syncVersion(project, w.id(), w.ref(), w.sha(), state, null)) {
                                changed.add(key + " " + w.id() + " (" + w.ref() + ")");
                            }
                        }
                    }
                }
            } else {
                if (projectKey == null || version == null) {
                    throw new Failure("--project and --version are required without --auto");
                }
                if (!registry.containsKey(projectKey)) {
                    throw new Failure("unknown project " + pyRepr(projectKey) + ", see data/docs.yml");
                }
                Map<String, Object> project = registry.get(projectKey);
                String r = ref != null ? ref : (source != null ? "local" : null);
                if (r == null) {
                    throw new Failure("--ref or --source is required");
                }
                String sha = null;
                if (source != null) {
                    try {
                        sha = run(Path.of(source), "git", "rev-parse", "HEAD").strip();
                    } catch (CommandFailed e) {
                        // not a git checkout
                    }
                }
                if (syncVersion(project, version, r, sha, state, source == null ? null : Path.of(source))) {
                    changed.add(projectKey + " " + version + " (" + r + ")");
                }
            }
        } catch (Failure e) {
            Common.log(e.getMessage());
            return 1;
        }
        saveState(state);
        Common.log("synced: " + (changed.isEmpty() ? "nothing changed" : String.join(", ", changed)));
        if (summary != null && !changed.isEmpty()) {
            StringBuilder s = new StringBuilder("Synced documentation:\n\n");
            changed.forEach(c -> s.append("- ").append(c).append('\n'));
            Files.writeString(Path.of(summary), s, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return 0;
    }
}
