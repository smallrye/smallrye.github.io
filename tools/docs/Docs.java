///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.7
//DEPS org.yaml:snakeyaml:2.3
//SOURCES Common.java Sync.java DarkImages.java Preview.java MkDocs2Adoc.java Antora2Adoc.java Redirects.java RewriteLinks.java GhPagesRedirects.java

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The tools behind smallrye.io/docs: pull each project's AsciiDoc documentation into the site, preview
 * a project's documentation locally, and keep old addresses working.
 *
 * <pre>
 * jbang tools/docs/Docs.java sync --auto                 (CI, nightly)
 * jbang tools/docs/Docs.java preview                     (in a project checkout: its docs on a local smallrye.io)
 * jbang tools/docs/Docs.java --help
 * </pre>
 */
@Command(name = "docs", mixinStandardHelpOptions = true,
        description = "The tools behind smallrye.io/docs.",
        subcommands = { Sync.class, Preview.class, MkDocs2Adoc.class, Antora2Adoc.class, Redirects.class,
                RewriteLinks.class, GhPagesRedirects.class, DarkImages.class })
public class Docs {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Docs()).execute(args));
    }
}
