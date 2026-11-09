#!/usr/bin/env bash
# Build the documentation search: a Pagefind index per project (target/roq/pagefind/<project>/),
# its pages filtered by version (data-pagefind-filter in templates/layouts/doc.html). One index
# for every project together drops pages for common words, so each project gets its own.
#
#   tools/docs/search-index.sh [target/roq]
set -euo pipefail
site="${1:-target/roq}"
for dir in "$site"/docs/*/; do
  key="$(basename "$dir")"
  [[ "$key" =~ ^[a-z0-9-]+$ ]] || continue
  echo "search index: $key"
  npx -y pagefind@1.4.0 --site "$site" --glob "docs/$key/**/*.html" --output-subdir "pagefind/$key" \
    | grep -E 'Indexed [0-9]+ pages' || true
done
