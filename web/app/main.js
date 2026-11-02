// Bundled by the Quarkus web bundler into static/bundle/ and loaded only by
// layouts that carry {#bundle /} (the post layout). Syntax colouring for code
// blocks: highlight.js core plus the languages small business and developer
// posts actually use. To add one: import it from
// 'highlight.js/lib/languages/<name>' and register it below.
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import bash from 'highlight.js/lib/languages/bash';
import xml from 'highlight.js/lib/languages/xml';
import json from 'highlight.js/lib/languages/json';
import yaml from 'highlight.js/lib/languages/yaml';
import properties from 'highlight.js/lib/languages/properties';
import graphql from 'highlight.js/lib/languages/graphql';
import markdown from 'highlight.js/lib/languages/markdown';
import javascript from 'highlight.js/lib/languages/javascript';
import css from 'highlight.js/lib/languages/css';
import sql from 'highlight.js/lib/languages/sql';
import kotlin from 'highlight.js/lib/languages/kotlin';
import python from 'highlight.js/lib/languages/python';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import plaintext from 'highlight.js/lib/languages/plaintext';

const languages = { java, bash, xml, json, yaml, properties, graphql, markdown, javascript, css, sql, kotlin, python, dockerfile, plaintext };
for (const [name, lang] of Object.entries(languages)) hljs.registerLanguage(name, lang);
hljs.registerAliases(['sh', 'shell', 'zsh', 'console'], { languageName: 'bash' });
hljs.registerAliases(['html', 'xhtml', 'svg'], { languageName: 'xml' });
hljs.registerAliases(['js', 'mjs'], { languageName: 'javascript' });
hljs.registerAliases(['yml'], { languageName: 'yaml' });
hljs.registerAliases(['md'], { languageName: 'markdown' });
hljs.registerAliases(['docker'], { languageName: 'dockerfile' });
hljs.registerAliases(['text', 'txt'], { languageName: 'plaintext' });
hljs.highlightAll();
