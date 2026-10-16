# SmallRye.io website

The source of [smallrye.io](https://smallrye.io), built with [Quarkus Roq](https://iamroq.dev/),
a static site generator. Blog posts and most pages are AsciiDoc, rendered by Asciidoctor.

## Running locally

Requires Java 21 or newer. Maven comes with the project (`./mvnw`).

```bash
./mvnw quarkus:dev
```

Then browse to http://localhost:8080. Pages, posts, templates and styles reload as you edit them.

To generate the static site the way CI does:

```bash
QUARKUS_ROQ_GENERATOR_BATCH=true ./mvnw package quarkus:run
```

The output lands in `target/roq/`.

## Where things are

| What | Where |
| --- | --- |
| Blog posts | `content/posts/YYYY-MM-DD-name.adoc`, published at `/blog/name/` |
| Pages | `content/*.adoc` and `content/*.html` |
| Post authors | `data/authors.yml` |
| Layouts and partials | `templates/` |
| Styles | `public/css/main.css` |
| Images and other static files | `public/` (served from the site root) |
| Site configuration | `config/application.properties` |
| Code highlighting | `web/app/main.js` (highlight.js, from mvnpm) |

## Writing a blog post

Add `content/posts/YYYY-MM-DD-name.adoc`. The header carries the post's details:

```asciidoc
= Fault Tolerance 7.0.0
:page-layout: post
:page-synopsis: SmallRye Fault Tolerance 7.0.0 released!
:page-tags: [announcement, microprofile]
:page-date: 2026-09-16 15:00:00.000 +0100
:page-author: lthon
```

`:page-synopsis:` is the summary on the blog list and the description search engines show.
`:page-author:` is a key in `data/authors.yml`; add yourself there first. A post dated in the
future is published by the first build on or after that date (the workflow runs daily).

## Project documentation

Each project keeps its documentation in its own repository, as AsciiDoc laid out like the
Quarkus guides (`docs/src/main/asciidoc/`: one page per `.adoc` file, `_nav.yml` for the sidebar,
`_attributes.adoc` for shared attributes, examples included from compiled sources). This site
pulls it in and publishes it at `/docs/<project>/<version>/`.

| What | Where |
| --- | --- |
| The projects, their repositories and the versions followed | `data/docs.yml` |
| The synced pages, one directory per version | `content/docs/<project>/<version>/` |
| Synced versions, their refs and navigation (generated) | `data/docversions.yml` |
| The documentation layout and its sidebar | `templates/layouts/doc.html`, `templates/partials/docs-nav.html` |
| The `/docs/` hub | `content/docs/index.html` |
| Sync and conversion tools | `tools/docs/` |

The tools are one [JBang](https://www.jbang.dev) program, `tools/docs/Docs.java`: Java and JBang are
all they need (pandoc, or podman to run it in a container, only to convert releases still
documented with MkDocs).

`sync` copies a project's docs from a branch or tag (or a local checkout) into `content/docs/`.
The `Sync project documentation` workflow runs it nightly: each project's `main` branch becomes
`main`, and its newest release tag becomes `latest`; the commit to `develop` then deploys. A release
whose docs are still MkDocs or Antora is converted on the fly by `mkdocs2adoc` or `antora2adoc`,
which are also the tools to convert a project's sources once.

```bash
jbang tools/docs/Docs.java sync --auto                             # what the nightly job does
jbang tools/docs/Docs.java sync --project smallrye-graphql --version main --source ../smallrye-graphql
jbang tools/docs/Docs.java --help                                  # every tool
```

### Previewing a project's documentation

In a project's checkout, `jbang docs-preview@smallrye/smallrye.github.io` (or `./mvnw -Pdocs-preview`
where the project has that profile) shows its documentation on a local smallrye.io at
`http://localhost:8080/docs/<project>/main/`, and publishes it again whenever a page or an included
example changes. It downloads this site's sources once a day; `--site ../smallrye.github.io` uses a
checkout instead, to try changes to the site itself.

Projects call `.github/workflows/docs-check.yml` from their pull requests: it renders their docs
with this site and fails on a broken include or a page that does not render.

Converting a project once (then commit the result in the project's repository):

```bash
jbang tools/docs/Docs.java mkdocs2adoc --mkdocs ../project/mkdocs.yml --out ../project/docs/src/main/asciidoc
jbang tools/docs/Docs.java antora2adoc --antora ../project/doc --out ../project/docs/src/main/asciidoc
```

Diagram blocks (`[mermaid]`, `[plantuml]`, ...) are rendered to SVG during the sync, by a Kroki
server (`KROKI_URL`, https://kroki.io by default; the workflows run their own).

On the dark theme, the docs pages show a dark version of each drawing (`DarkImages.java`, part of the
sync), so diagrams do not sit on a light block:

- a dark version the project drew itself, next to the image: `x.dark.png` for `x.png`, or `x-dark.png`
  for `x-light.png`;
- otherwise the sync makes one: diagrams get their colours turned around (same hue, lightness inverted),
  and so do PNGs that look like drawings (mostly white or transparent, with darker lines), their white
  becoming transparent. Screenshots and photos keep a light paper behind them;
- a project opts an image out with the `no-dark` role (`image::x.png[...,role=no-dark]`), or in with
  `dark-invert` (an SVG, or a PNG the check passes over).

`jbang tools/docs/Docs.java dark-images content/docs/<project>/<version>` makes them again for a
synced version.

### Old addresses

The documentation addresses this site published before (Antora's
`/docs/<project>/<release>/<page>.html`) are listed under `legacy` in `data/docs.yml`. The build
writes a redirect page for each of them (`Docs.java redirects`), and
`Docs.java rewrite-links --check target/roq content/posts/*.adoc` points blog links at the new
addresses, checking every page and anchor against the generated site.

The projects' old MkDocs sites (their `gh-pages` branches, at `smallrye.io/<project>/<version>/`) are
replaced by redirect pages: `Docs.java gh-pages-redirects --project <key> --checkout <gh-pages checkout>
--site target/roq` sends every page of every version to the same page of the version this site
publishes for that major line (older majors are streams in `data/docs.yml`). Javadoc stays as it is.

## Deployment

Pushing to `develop` runs `.github/workflows/build.yml`: it generates the site with the Roq
GitHub Action (project docs included), adds the redirects for old documentation addresses, and pushes the result to the `main`
branch, which GitHub Pages serves. Pull requests build the site without deploying it. The
workflow also runs daily and can be started by hand from the Actions tab.

## Contributing

Please refer to our Wiki for the [Contribution Guidelines](https://github.com/smallrye/smallrye-parent/wiki/Contributing-to-SmallRye-projects).

## License

This website is licensed under the [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).
