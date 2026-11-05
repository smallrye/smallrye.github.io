import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/** Helpers the docs tools share: files, regular expressions with computed replacements, YAML. */
final class Common {

    private Common() {
    }

    /**
     * The site: the directory holding data/docs.yml (the tools live in its tools/docs/). The preview
     * points it at a downloaded copy of the site when it runs from a project.
     */
    static Path SITE = findSite();

    private static Path findSite() {
        String source = System.getProperty("jbang.source");
        List<Path> candidates = new ArrayList<>();
        if (source != null) {
            candidates.add(Path.of(source).toAbsolutePath().getParent());
        }
        candidates.add(Path.of("").toAbsolutePath());
        for (Path start : candidates) {
            for (Path p = start; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve("data/docs.yml"))) {
                    return p;
                }
            }
        }
        return Path.of("").toAbsolutePath();
    }

    static void log(String message) {
        System.err.println(message);
    }

    /** Python's Path.read_text(): \r\n and \r read as \n. */
    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path path, String text) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Python's ordering of paths: part by part, so a/x comes before a-b/x. */
    static final java.util.Comparator<Path> BY_PARTS = (a, b) -> {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            int c = a.getName(i).toString().compareTo(b.getName(i).toString());
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    };

    /** Files and directories under {@code root}, sorted like Python's sorted(rglob('*')). */
    static List<Path> walk(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> !p.equals(root)).sorted(BY_PARTS).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Regular files named *.{suffix} under {@code root}, sorted. */
    static List<Path> files(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        return walk(root).stream().filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix))
                .toList();
    }

    /**
     * Python's re.sub with a function: the function's result is used literally (Java's own
     * replaceAll(Function) would read $ and \ in it as group references).
     */
    static String sub(Pattern pattern, String text, Function<MatchResult, String> replacement) {
        Matcher m = pattern.matcher(text);
        return m.replaceAll(r -> Matcher.quoteReplacement(replacement.apply(r)));
    }

    /** A pattern that reads like Python's: \\w is Unicode, and only \\n ends a line. */
    static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    /** {@link #re} with re.M. */
    static Pattern reM(String regex) {
        return Pattern.compile(regex, Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    /** Python's str.split('\n'): keeps trailing empty strings. */
    static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    /** Python's str.title(). */
    static String title(String s) {
        StringBuilder out = new StringBuilder();
        boolean previousCased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(previousCased ? Character.toLowerCase(c) : Character.toUpperCase(c));
            previousCased = Character.isLetter(c);
        }
        return out.toString();
    }

    static Object loadYaml(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    /** yaml.safe_dump(data, sort_keys=True, allow_unicode=True, width=1000), byte for byte. */
    static String dumpYaml(Object data) {
        DumperOptions d = new DumperOptions();
        d.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        d.setIndent(2);
        d.setIndicatorIndent(0);
        d.setIndentWithIndicator(false);
        d.setWidth(1000);
        d.setAllowUnicode(true);
        d.setSplitLines(false);
        return new Yaml(new Representer(d), d).dump(sorted(data));
    }

    /** yaml.safe_dump(data, sort_keys=False, allow_unicode=True, width=1000). */
    static String dumpYamlInOrder(Object data) {
        DumperOptions d = new DumperOptions();
        d.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        d.setIndent(2);
        d.setIndicatorIndent(0);
        d.setIndentWithIndicator(false);
        d.setWidth(1000);
        d.setAllowUnicode(true);
        d.setSplitLines(false);
        return new Yaml(new Representer(d), d).dump(data);
    }

    /** json.dumps of a string (ensure_ascii=True). */
    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7f) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** html.escape(s, quote=True). */
    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private static Object sorted(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> t = new TreeMap<>();
            m.forEach((k, v) -> t.put(String.valueOf(k), sorted(v)));
            return t;
        }
        if (o instanceof List<?> l) {
            List<Object> r = new ArrayList<>();
            l.forEach(x -> r.add(sorted(x)));
            return r;
        }
        return o;
    }
}
