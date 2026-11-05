import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Preview a project's documentation the way smallrye.io shows it, from the project's checkout:
 *
 * <pre>
 * jbang docs-preview@smallrye/smallrye.github.io      (or ./mvnw -Pdocs-preview, in a project)
 * </pre>
 *
 * Finds the project in data/docs.yml, publishes its AsciiDoc as the "main" version of a local copy
 * of smallrye.io (downloaded once a day, or --site for a checkout), runs the site in Quarkus dev
 * mode and publishes again whenever a page or an included example changes.
 */
@Command(name = "preview", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Preview a project's documentation on a local smallrye.io.")
@SuppressWarnings("unchecked")
class Preview implements Callable<Integer> {

    @Option(names = "--source", defaultValue = ".", description = "the project's checkout (default: the current directory)")
    Path source;
    @Option(names = "--project", description = "the project's key in data/docs.yml (default: found from the checkout)")
    String projectKey;
    @Option(names = "--port", defaultValue = "8080", description = "HTTP port (default: 8080)")
    int port;
    @Option(names = "--site", description = "a smallrye.github.io checkout to use instead of a downloaded copy")
    Path site;
    @Option(names = "--update", description = "download the site again even if the copy is recent")
    boolean update;
    /** Where the site's sources come from: another branch or fork, to try changes to the site. */
    @Option(names = "--site-zip", hidden = true,
            defaultValue = "https://codeload.github.com/smallrye/smallrye.github.io/zip/refs/heads/develop")
    String siteZip;

    @Override
    public Integer call() throws Exception {
        Path checkout = Sync.resolve(source);
        Common.SITE = site != null ? Sync.resolve(site) : siteCopy();
        Sync.lenient = true;
        Map<String, Map<String, Object>> registry = Sync.loadRegistry();
        Map<String, Object> project = projectKey != null ? registry.get(projectKey) : detect(registry, checkout);
        if (project == null) {
            Common.log(projectKey != null ? "Unknown project " + projectKey + ", see " + Common.SITE.resolve("data/docs.yml")
                    : "Could not tell which project " + checkout + " is: pass --project <key> (see data/docs.yml)");
            return 1;
        }
        String key = (String) project.get("key");
        Path docs = checkout.resolve((String) project.get("path"));
        if (!Files.isRegularFile(docs.resolve("_attributes.adoc"))) {
            Common.log("No AsciiDoc documentation at " + docs + " (" + key + " documents itself in " + project.get("path") + ")");
            return 1;
        }
        try (ServerSocket probe = new ServerSocket(port)) {
            // free
        } catch (IOException e) {
            Common.log("Port " + port + " is in use: pass --port <another>");
            return 1;
        }

        Common.log("Publishing " + key + " from " + docs);
        publish(project, checkout);
        Path log = Common.SITE.resolve("target/docs-preview.log");
        Process server = startSite(log);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(server)));
        String url = "http://localhost:" + port + "/docs/" + key + "/main/";
        Common.log("Starting smallrye.io in dev mode (the first run downloads its dependencies, which takes a while)");
        if (!waitFor(url, server, Duration.ofMinutes(15))) {
            Common.log("The site did not start; its log is " + log);
            tail(log);
            return 1;
        }
        Common.log("");
        Common.log("  " + url);
        Common.log("");
        Common.log("Pages and included examples are published again when they change. Ctrl+C to stop.");

        List<Path> watched = watched(docs);
        String fingerprint = fingerprint(docs, watched);
        while (server.isAlive()) {
            Thread.sleep(1000);
            String now = fingerprint(docs, watched);
            if (!now.equals(fingerprint)) {
                Thread.sleep(300); // let an editor finish writing
                fingerprint = fingerprint(docs, watched);
                Instant start = Instant.now();
                try {
                    publish(project, checkout);
                    watched = watched(docs);
                    fingerprint = fingerprint(docs, watched);
                    waitFor(url, server, Duration.ofMinutes(2)); // the dev mode reloads on the next request
                    Common.log("Updated in " + Duration.between(start, Instant.now()).toSeconds() + "s: refresh the page");
                } catch (RuntimeException e) {
                    Common.log("Not updated: " + e.getMessage());
                }
            }
        }
        Common.log("The site stopped; its log is " + log);
        tail(log);
        return server.exitValue();
    }

    void publish(Map<String, Object> project, Path checkout) throws IOException {
        Map<String, Object> state = Sync.loadState();
        String sha = null;
        try {
            sha = Sync.run(checkout, "git", "rev-parse", "HEAD").strip();
        } catch (RuntimeException e) {
            // not a git checkout
        }
        Sync.syncVersion(project, "main", "local", sha, state, checkout);
        Sync.saveState(state);
    }

    /** The project whose repository the checkout's git remotes name, or whose docs directory it has. */
    static Map<String, Object> detect(Map<String, Map<String, Object>> registry, Path checkout) {
        String remotes = "";
        try {
            remotes = Sync.run(checkout, "git", "remote", "-v");
        } catch (RuntimeException e) {
            // not a git checkout
        }
        List<Map<String, Object>> byRemote = new ArrayList<>();
        List<Map<String, Object>> byPath = new ArrayList<>();
        for (Map<String, Object> project : registry.values()) {
            if (project.get("path") == null || project.get("repo") == null) {
                continue;
            }
            String name = ((String) project.get("repo")).replaceAll(".*/", "");
            if (Common.re("[/:][\\w.-]+/" + java.util.regex.Pattern.quote(name) + "(\\.git)?\\s").matcher(remotes).find()) {
                byRemote.add(project);
            }
            if (Files.isRegularFile(checkout.resolve((String) project.get("path")).resolve("_attributes.adoc"))) {
                byPath.add(project);
            }
        }
        if (byRemote.size() == 1) {
            return byRemote.get(0);
        }
        return byPath.size() == 1 ? byPath.get(0) : null;
    }

    /**
     * What a change to is published again: everything in the docs directory, and the example files
     * its pages include (the examples directory can be the whole repository).
     */
    static List<Path> watched(Path docs) {
        List<Path> files = new ArrayList<>(Common.walk(docs).stream().filter(Files::isRegularFile).toList());
        String examples = Sync.attribute(Common.read(docs.resolve("_attributes.adoc")), "examples-dir");
        if (examples != null) {
            Path base = Sync.resolve(docs.resolve(examples.replace("{version-root}", "")));
            for (Path page : Common.files(docs, ".adoc")) {
                java.util.regex.Matcher m = Sync.INCLUDE.matcher(Common.read(page));
                while (m.find()) {
                    Path example = Sync.resolve(base.resolve(m.group(1)));
                    if (example.startsWith(base) && !files.contains(example)) {
                        files.add(example);
                    }
                }
            }
        }
        return files;
    }

    /** Changes when a watched file changes, or a file is added to or removed from the docs directory. */
    static String fingerprint(Path docs, List<Path> watched) {
        StringBuilder s = new StringBuilder();
        java.util.Set<Path> all = new java.util.LinkedHashSet<>(watched);
        all.addAll(Common.walk(docs).stream().filter(Files::isRegularFile).toList());
        for (Path f : all) {
            try {
                s.append(f).append(Files.getLastModifiedTime(f).toMillis()).append(Files.size(f)).append('\n');
            } catch (IOException e) {
                s.append(f).append(" gone\n");
            }
        }
        return Sync.sha1(s.toString());
    }

    Process startSite(Path log) throws IOException {
        Files.createDirectories(log.getParent());
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>(windows ? List.of("cmd", "/c", "mvnw.cmd") : List.of("sh", "mvnw"));
        cmd.addAll(List.of("-B", "quarkus:dev", "-Dquarkus.http.port=" + port, "-Dquarkus.console.enabled=false",
                "-Dquarkus.analytics.disabled=true"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(Common.SITE.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // the site needs Java 21: the one this runs on (JBang provides it)
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        return pb.start();
    }

    static boolean waitFor(String url, Process server, Duration timeout) throws InterruptedException {
        Instant end = Instant.now().plus(timeout);
        while (Instant.now().isBefore(end) && server.isAlive()) {
            try {
                HttpResponse<Void> r = Sync.http().send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return true;
                }
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(1000);
        }
        return false;
    }

    static void stop(Process server) {
        server.descendants().forEach(ProcessHandle::destroy);
        server.destroy();
    }

    static void tail(Path log) {
        if (Files.exists(log)) {
            List<String> lines = Common.lines(Common.read(log));
            lines.subList(Math.max(0, lines.size() - 30), lines.size()).forEach(Common::log);
        }
    }

    /** A copy of smallrye.io's sources (the develop branch), downloaded again after a day. */
    Path siteCopy() throws IOException, InterruptedException {
        String cacheHome = System.getenv("XDG_CACHE_HOME");
        Path cache = cacheHome != null ? Path.of(cacheHome)
                : System.getenv("LOCALAPPDATA") != null ? Path.of(System.getenv("LOCALAPPDATA"))
                        : Path.of(System.getProperty("user.home"), ".cache");
        Path dir = cache.resolve("smallrye-docs-preview");
        Path copy = dir.resolve("smallrye.github.io-develop");
        Path stamp = dir.resolve("downloaded");
        boolean fresh = Files.exists(stamp)
                && Files.getLastModifiedTime(stamp).toInstant().isAfter(Instant.now().minus(Duration.ofDays(1)));
        if (Files.isRegularFile(copy.resolve("data/docs.yml")) && fresh && !update) {
            return copy;
        }
        Common.log("Downloading smallrye.io's sources into " + dir);
        HttpResponse<InputStream> response = Sync.http().send(HttpRequest.newBuilder(URI.create(siteZip))
                .header("User-Agent", Sync.USER_AGENT).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            if (Files.isRegularFile(copy.resolve("data/docs.yml"))) {
                Common.log("  WARN download failed (" + response.statusCode() + "), using the copy from before");
                return copy;
            }
            throw new IOException("downloading " + siteZip + " failed: " + response.statusCode());
        }
        Path unpacked = dir.resolve("unpacking");
        Sync.deleteTree(unpacked);
        Files.createDirectories(unpacked);
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Path target = unpacked.resolve(entry.getName()).normalize();
                if (!target.startsWith(unpacked)) {
                    throw new IOException("unexpected entry " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path root;
        try (var s = Files.list(unpacked)) {
            root = s.filter(Files::isDirectory).findFirst().orElseThrow(() -> new IOException("empty download"));
        }
        // keep the Maven build output: the dev mode starts faster from it
        Path oldTarget = copy.resolve("target");
        if (Files.isDirectory(oldTarget)) {
            Files.move(oldTarget, root.resolve("target"));
        }
        Sync.deleteTree(copy);
        Files.move(root, copy);
        Sync.deleteTree(unpacked);
        Files.write(stamp, new byte[0]);
        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now()));
        return copy;
    }
}
