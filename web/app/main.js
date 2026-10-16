// Bundled by the Quarkus web bundler into static/bundle/ and loaded only by
// layouts that carry {#bundle /} (posts and project docs). Syntax colouring for code
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
import groovy from 'highlight.js/lib/languages/groovy';

const languages = { java, bash, xml, json, yaml, properties, graphql, markdown, javascript, css, sql, kotlin, python, dockerfile, plaintext, groovy };
for (const [name, lang] of Object.entries(languages)) hljs.registerLanguage(name, lang);
hljs.registerAliases(['sh', 'shell', 'zsh', 'console'], { languageName: 'bash' });
hljs.registerAliases(['html', 'xhtml', 'svg'], { languageName: 'xml' });
hljs.registerAliases(['csv', 'conf'], { languageName: 'plaintext' });
hljs.registerAliases(['js', 'mjs'], { languageName: 'javascript' });
hljs.registerAliases(['yml'], { languageName: 'yaml' });
hljs.registerAliases(['md'], { languageName: 'markdown' });
hljs.registerAliases(['docker'], { languageName: 'dockerfile' });
hljs.registerAliases(['text', 'txt'], { languageName: 'plaintext' });
hljs.highlightAll();

// ---- Project documentation (/docs/<project>/<version>/) ----

// Version picker: go to the same page in the chosen version, or that version's start page
// when the page does not exist there.
const versionSelect = document.querySelector('select[data-docs-version]');
if (versionSelect) {
  versionSelect.addEventListener('change', async () => {
    const option = versionSelect.selectedOptions[0];
    if (option.dataset.url) {
      window.location.href = option.dataset.url;
      return;
    }
    const base = versionSelect.dataset.base;
    const rest = window.location.pathname.slice((base + versionSelect.dataset.current + '/').length);
    const target = base + versionSelect.value + '/' + rest;
    try {
      const response = await fetch(target, { method: 'HEAD' });
      window.location.href = response.ok ? target + window.location.hash : base + versionSelect.value + '/';
    } catch (e) {
      window.location.href = base + versionSelect.value + '/';
    }
  });
}

// Sidebar: the Contents button opens and closes it (remembered on wide screens), and the
// section holding the current page is unfolded.
const navButton = document.querySelector('[data-docs-nav-button]');
if (navButton) {
  const root = document.documentElement;
  navButton.setAttribute('aria-expanded', String(root.classList.contains('docs-nav-open')));
  navButton.addEventListener('click', () => {
    const open = root.classList.toggle('docs-nav-open');
    navButton.setAttribute('aria-expanded', String(open));
    if (window.matchMedia('(min-width: 60rem)').matches) {
      try { localStorage.setItem('docs-nav', open ? 'open' : 'closed'); } catch (e) { /* not remembered */ }
    }
  });
  let section = document.querySelector('.docs-nav a[aria-current="page"]')?.closest('details');
  while (section) {
    section.open = true;
    section = section.parentElement.closest('details');
  }
}

// "On this page" folds; a reader who folded it gets it folded on the next page.
const toc = document.querySelector('details[data-docs-toc]');
if (toc) {
  if (document.documentElement.classList.contains('docs-toc-closed')) {
    toc.open = false;
    document.documentElement.classList.remove('docs-toc-closed');
  }
  toc.addEventListener('toggle', () => {
    try { localStorage.setItem('docs-toc', toc.open ? 'open' : 'closed'); } catch (e) { /* not remembered */ }
  });
}

// Tabs: an example block with the "tabs" role holds a labelled list (label:: content).
document.querySelectorAll('.docs-main .exampleblock.tabs').forEach((block, blockIndex) => {
  const list = block.querySelector(':scope > .content > .dlist > dl');
  if (!list) return;
  const tabList = document.createElement('div');
  tabList.className = 'tab-list';
  tabList.setAttribute('role', 'tablist');
  const panels = [];
  [...list.querySelectorAll(':scope > dt')].forEach((dt, i) => {
    const dd = dt.nextElementSibling;
    const id = `tabs-${blockIndex}-${i}`;
    const button = document.createElement('button');
    button.type = 'button';
    button.id = id + '-tab';
    button.textContent = dt.textContent.trim();
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-controls', id);
    const panel = document.createElement('div');
    panel.id = id;
    panel.setAttribute('role', 'tabpanel');
    panel.setAttribute('aria-labelledby', button.id);
    if (dd) panel.append(...dd.childNodes);
    tabList.append(button);
    panels.push([button, panel]);
  });
  const select = (index) => panels.forEach(([button, panel], i) => {
    button.setAttribute('aria-selected', String(i === index));
    button.tabIndex = i === index ? 0 : -1;
    panel.hidden = i !== index;
  });
  panels.forEach(([button], i) => {
    button.addEventListener('click', () => select(i));
    button.addEventListener('keydown', (e) => {
      const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
      if (next === null) return;
      const n = (next + panels.length) % panels.length;
      select(n);
      panels[n][0].focus();
    });
  });
  const content = block.querySelector(':scope > .content');
  content.replaceChildren(tabList, ...panels.map(([, panel]) => panel));
  select(0);
});

// Links into the old Antora and MkDocs sites used ids like #_rate_limit; the docs now use #rate-limit.
if (document.querySelector('.docs-main') && window.location.hash) {
  const id = decodeURIComponent(window.location.hash.slice(1));
  if (!document.getElementById(id)) {
    // Asciidoctor also drops some characters ("Non-compatible Mode" is #noncompatible-mode),
    // so compare ids with punctuation ignored.
    const squash = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
    const wanted = squash(id);
    const target = document.getElementById(id.replace(/^_+/, '').replace(/_/g, '-'))
      || [...document.querySelectorAll('.docs-main [id]')].find((el) => squash(el.id) === wanted);
    if (target) {
      history.replaceState(null, '', '#' + target.id);
      target.scrollIntoView();
    }
  }
}

// Images with a dark version (the has-dark role, set by tools/docs/DarkImages.java) show it on the
// dark theme: x.dark.png next to x.png, or x-dark.png next to x-light.png. The others keep light paper.
const themedImages = [...document.querySelectorAll('.docs-main .imageblock.has-dark img')];
if (themedImages.length) {
  const dark = matchMedia('(prefers-color-scheme: dark)');
  const darkSource = (src) => (/-light\.\w+$/.test(src)
    ? src.replace(/-light(\.\w+)$/, '-dark$1') : src.replace(/(\.\w+)$/, '.dark$1'));
  const isDark = () => {
    const theme = document.documentElement.getAttribute('data-theme');
    return theme ? theme === 'dark' : dark.matches;
  };
  themedImages.forEach((img) => {
    img.dataset.light = img.getAttribute('src');
    // a dark version that does not load: back to the light one, on paper
    img.addEventListener('error', () => {
      if (img.hasAttribute('data-dark')) {
        img.removeAttribute('data-dark');
        img.src = img.dataset.light;
      }
    });
  });
  const show = () => themedImages.forEach((img) => {
    const wanted = isDark() ? darkSource(img.dataset.light) : img.dataset.light;
    if (img.getAttribute('src') !== wanted) {
      img.toggleAttribute('data-dark', isDark());
      img.src = wanted;
    }
  });
  dark.addEventListener('change', show);
  new MutationObserver(show).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  show();
}
