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

## Building the documentation

The documentation at `/docs/` is built separately with Antora, from the repositories listed in
`docs/antora-playbook.yml`, and copied into the site by CI.

1. Install Antora. See [Antora installation](https://docs.antora.org/antora/2.3/install/install-antora/).
2. Go to the `docs` directory and run

        antora generate antora-playbook.yml --fetch

3. The HTML for the SmallRye documentation is now in `docs/build/site`.

## Deployment

Pushing to `develop` runs `.github/workflows/build.yml`: it generates the site with the Roq
GitHub Action, builds the documentation with Antora, and pushes the result to the `main`
branch, which GitHub Pages serves. Pull requests build the site without deploying it. The
workflow also runs daily and can be started by hand from the Actions tab.

## Contributing

Please refer to our Wiki for the [Contribution Guidelines](https://github.com/smallrye/smallrye-parent/wiki/Contributing-to-SmallRye-projects).

## License

This website is licensed under the [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).
