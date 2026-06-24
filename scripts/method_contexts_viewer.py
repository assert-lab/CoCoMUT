#!/usr/bin/env python3
"""
Dependency-free browser viewer for CoCoMUT method_contexts JSONL outputs.

Usage examples:
    # Inspect one CoCoMUT output file.
    python3 scripts/method_contexts_viewer.py /tmp/cocomut-commons-lang-test/method_contexts__4987c243.jsonl

    # Inspect every *.jsonl file under an output directory.
    python3 scripts/method_contexts_viewer.py /tmp/cocomut-commons-lang-test

    # Inspect multiple outputs at once.
    python3 scripts/method_contexts_viewer.py \
        /tmp/cocomut-commons-lang-test/method_contexts__4987c243.jsonl \
        experiments/manual-run/outputs

    # Use an explicit port and do not open the browser automatically.
    python3 scripts/method_contexts_viewer.py \
        --data-dir experiments/manual-run/outputs \
        --port 8081 \
        --no-open

    # Then open:
    #   http://127.0.0.1:8081

    # Stop the server with Ctrl-C.

Common workflow:
    1. Run CoCoMUT and generate a method_contexts__<request-hash>.jsonl file.
    2. Start this viewer with the JSONL file or the containing output directory.
    3. Use filters to inspect tags, reference resolution, source set,
       reference scope/domain/target kind, source set, visibility, backend
       mode, call graph, method size, and documentation presence.

The viewer accepts any mix of JSONL files and directories. Directories are
searched recursively for *.jsonl files, so it works for project-wide,
package-wide, type-wide, and method-targeted CoCoMUT outputs wherever they live.
"""

from __future__ import annotations

import argparse
import http.server
import json
import os
import sys
import threading
import webbrowser
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse


HTML_PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CoCoMUT Method Context Viewer</title>
<style>
  :root {
    --bg: #ffffff;
    --panel: #f6f8fa;
    --panel-2: #ffffff;
    --border: #d0d7de;
    --text: #24292f;
    --muted: #57606a;
    --accent: #0969da;
    --danger: #b42318;
    --code-keyword: #cf222e;
    --code-type: #8250df;
    --code-string: #0a3069;
    --code-comment: #6e7781;
    --json-key: #0550ae;
    --json-string: #0a3069;
    --json-number: #953800;
    --json-bool: #8250df;
  }
  * { box-sizing: border-box; }
  body {
    margin: 14px;
    background: var(--bg);
    color: var(--text);
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  h1 {
    margin: 0 0 10px;
    font-size: 22px;
    line-height: 1.2;
  }
  h2 {
    margin: 0;
    font-size: 15px;
    line-height: 1.2;
  }
  h3 {
    margin: 12px 0 6px;
    font-size: 13px;
  }
  label {
    font-weight: 650;
    white-space: nowrap;
  }
  select, button, input {
    min-height: 32px;
    padding: 4px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: #ffffff;
    color: var(--text);
    font: inherit;
    font-size: 14px;
  }
  button { cursor: pointer; }
  button:hover { background: #f3f4f6; }
  button:disabled { color: #8c959f; cursor: not-allowed; }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin: 8px 0;
  }
  #datasetSelect { min-width: min(680px, 70vw); }
  #searchText { min-width: min(360px, 60vw); }
  #recordInfo {
    min-width: 150px;
    font-weight: 700;
  }
  #filterPanel {
    display: grid;
    grid-template-columns: repeat(4, minmax(180px, 1fr));
    gap: 10px 12px;
    align-items: end;
    margin: 8px 0 10px;
    padding: 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel);
  }
  #filterPanel label {
    display: grid;
    gap: 4px;
    color: var(--muted);
    font-size: 12px;
  }
  .filter-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .check-row {
    display: grid;
    grid-template-columns: 16px minmax(0, 1fr);
    align-items: center;
    gap: 7px;
    min-height: 24px;
    color: var(--text);
    font-size: 13px;
    line-height: 1.2;
  }
  .check-row input {
    width: 13px;
    height: 13px;
    min-height: 0;
    padding: 0;
    margin: 0;
  }
  .tag-filter {
    display: grid;
    gap: 8px;
    grid-column: span 2;
    margin: 0;
    padding: 0;
    border: 0;
  }
  .tag-filter legend {
    color: var(--muted);
    font-size: 12px;
    font-weight: 650;
  }
  .tag-filter-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }
  .tag-mode {
    display: flex;
    align-items: center;
    gap: 5px;
    color: var(--muted);
    font-size: 12px;
    font-weight: 650;
  }
  .tag-mode select {
    min-height: 28px;
    padding: 2px 6px;
    font-size: 12px;
  }
  .tag-options {
    display: grid;
    grid-template-columns: repeat(3, minmax(130px, 1fr));
    gap: 6px 12px;
  }
  #status {
    min-height: 22px;
    margin: 5px 0;
    color: var(--danger);
    font-weight: 650;
  }
  .pill {
    display: inline-flex;
    align-items: center;
    max-width: 100%;
    min-height: 22px;
    padding: 2px 8px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: #ffffff;
    color: var(--muted);
    font-size: 12px;
    white-space: nowrap;
  }
  .summary {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 8px;
    margin: 10px 0;
  }
  .summary-item {
    min-width: 0;
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel-2);
  }
  .summary-item span {
    display: block;
    margin-bottom: 3px;
    color: var(--muted);
    font-size: 12px;
  }
  .summary-item strong {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
  }
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1.08fr) minmax(0, 0.92fr);
    gap: 10px;
    height: calc(100vh - 190px);
    min-height: 620px;
  }
  .stack {
    display: grid;
    grid-template-rows: minmax(0, 1fr) minmax(170px, 0.42fr);
    gap: 10px;
    min-height: 0;
  }
  .right-stack {
    display: grid;
    grid-template-rows: minmax(230px, 0.9fr) minmax(230px, 1fr);
    gap: 10px;
    min-height: 0;
  }
  .panel {
    min-width: 0;
    min-height: 0;
    overflow: auto;
    padding: 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel);
  }
  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin: -2px 0 8px;
    padding-bottom: 7px;
    border-bottom: 1px solid var(--border);
  }
  .two-col {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  dl {
    display: grid;
    grid-template-columns: minmax(120px, 0.35fr) minmax(0, 1fr);
    gap: 6px 14px;
    margin: 0;
    font-size: 13px;
  }
  dt {
    color: var(--muted);
    font-weight: 700;
    overflow-wrap: anywhere;
  }
  dd {
    margin: 0;
    min-width: 0;
    overflow-wrap: anywhere;
    word-break: break-word;
    line-height: 1.35;
  }
  pre {
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: "Fira Code", "Cascadia Code", Consolas, "Liberation Mono", monospace;
    font-size: 13px;
    line-height: 1.45;
    tab-size: 4;
  }
  .code-block {
    padding: 9px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: #ffffff;
  }
  .doc {
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    white-space: pre-wrap;
    line-height: 1.45;
  }
  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-bottom: 8px;
  }
  .tab {
    min-height: 28px;
    padding: 3px 8px;
    font-size: 12px;
  }
  .tab.active {
    border-color: var(--accent);
    color: var(--accent);
    font-weight: 700;
  }
  .hidden { display: none !important; }
  .empty { color: var(--muted); font-style: italic; }
  .kw { color: var(--code-keyword); font-weight: 650; }
  .type { color: var(--code-type); }
  .str { color: var(--code-string); }
  .com { color: var(--code-comment); }
  .json-key { color: var(--json-key); }
  .json-string { color: var(--json-string); }
  .json-number { color: var(--json-number); }
  .json-bool { color: var(--json-bool); }
  @media (max-width: 1120px) {
    .summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    #filterPanel { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .layout, .stack, .right-stack, .two-col {
      display: block;
      height: auto;
      min-height: 0;
    }
    .panel { margin-bottom: 10px; max-height: 70vh; }
  }
</style>
</head>
<body>
<h1>CoCoMUT Method Context Viewer</h1>

<div class="toolbar">
  <label for="datasetSelect">JSONL:</label>
  <select id="datasetSelect"></select>
  <span id="datasetStats" class="pill"></span>
</div>

<div class="toolbar" id="navigation">
  <button id="prevBtn">Previous</button>
  <button id="nextBtn">Next</button>
  <span id="recordInfo">Record 0 / 0</span>
  <input type="number" id="jumpTo" min="1" value="1" title="Filtered record number" style="width: 86px">
  <button id="jumpBtn">Go</button>
  <input type="search" id="searchText" placeholder="URI, signature, javadoc, symbol...">
  <button id="searchBtn" title="Apply search/filter">&#128269;</button>
  <button id="copyUriBtn">Copy URI</button>
  <button id="toggleFiltersBtn">Filters</button>
</div>

<section id="filterPanel" class="hidden">
  <fieldset class="tag-filter">
    <legend>Javadoc tags</legend>
    <div class="tag-filter-header">
      <span>Select one or more tags</span>
      <label class="tag-mode">Match
        <select id="filterTagMode">
          <option value="or">OR</option>
          <option value="and">AND</option>
        </select>
      </label>
    </div>
    <div class="tag-options">
      <label class="check-row"><input type="checkbox" name="filterTag" value="see"> @see</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="link"> {@link}</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="linkplain"> {@linkplain}</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="inheritdoc"> {@inheritDoc}</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="param"> @param</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="return"> @return</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="throws"> @throws</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="since"> @since</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="deprecated"> @deprecated</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="apiNote"> @apiNote</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="implSpec"> @implSpec</label>
      <label class="check-row"><input type="checkbox" name="filterTag" value="implNote"> @implNote</label>
    </div>
  </fieldset>
  <label>Reference resolution
    <select id="filterResolution">
      <option value="">Any</option>
      <option value="resolved_method">resolved_method</option>
      <option value="resolved_inherited_method">resolved_inherited_method</option>
      <option value="resolved_type">resolved_type</option>
      <option value="resolved_field">resolved_field</option>
      <option value="resolved_inherited_field">resolved_inherited_field</option>
      <option value="external_symbol">external_symbol</option>
      <option value="overload_ambiguous">overload_ambiguous</option>
      <option value="unresolved">unresolved</option>
    </select>
  </label>
  <label>Reference scope
    <select id="filterReferenceScope">
      <option value="">Any</option>
      <option value="same_type">same_type</option>
      <option value="same_package">same_package</option>
      <option value="same_module">same_module</option>
      <option value="external">external</option>
      <option value="text">text</option>
      <option value="unknown">unknown</option>
    </select>
  </label>
  <label>Reference domain
    <select id="filterReferenceDomain">
      <option value="">Any</option>
      <option value="project">project</option>
      <option value="external_jdk">external_jdk</option>
      <option value="external_library">external_library</option>
      <option value="external_web">external_web</option>
      <option value="text">text</option>
      <option value="unresolved">unresolved</option>
    </select>
  </label>
  <label>Reference target
    <select id="filterReferenceTargetKind">
      <option value="">Any</option>
      <option value="method">method</option>
      <option value="field">field</option>
      <option value="type">type</option>
      <option value="url">url</option>
      <option value="text">text</option>
      <option value="method_or_field">method_or_field</option>
      <option value="unknown">unknown</option>
    </select>
  </label>
  <label>Source set
    <select id="filterSourceSet">
      <option value="">Any</option>
      <option value="main">main</option>
      <option value="test">test</option>
      <option value="integration_test">integration_test</option>
      <option value="generated">generated</option>
      <option value="example">example</option>
      <option value="unknown">unknown</option>
    </select>
  </label>
  <label>Visibility
    <select id="filterVisibility">
      <option value="">Any</option>
      <option value="public">public</option>
      <option value="protected">protected</option>
      <option value="package-private">package-private</option>
      <option value="private">private</option>
    </select>
  </label>
  <label>Backend mode
    <select id="filterBackend">
      <option value="">Any</option>
      <option value="classpath">classpath</option>
      <option value="classpath_limited">classpath_limited</option>
    </select>
  </label>
  <label>Call graph
    <select id="filterCallGraph">
      <option value="">Any</option>
      <option value="available">available</option>
      <option value="unavailable">unavailable</option>
      <option value="CHA">CHA</option>
      <option value="RTA">RTA</option>
    </select>
  </label>
  <label>Minimum LOC
    <input type="number" id="filterMinLoc" min="0" placeholder="0">
  </label>
  <label>Minimum CC
    <input type="number" id="filterMinCc" min="0" placeholder="0">
  </label>
  <label class="check-row"><input type="checkbox" id="filterHasJavadoc"> has Javadoc</label>
  <label class="check-row"><input type="checkbox" id="filterHasCallers"> has callers</label>
  <label class="check-row"><input type="checkbox" id="filterHasCallees"> has callees</label>
  <div class="filter-actions">
    <button id="applyFiltersBtn">Apply</button>
    <button id="clearFiltersBtn">Clear</button>
  </div>
</section>

<div id="status"></div>

<section class="summary" id="summary">
  <div class="summary-item"><span>Selection</span><strong id="selectionInfo"></strong></div>
  <div class="summary-item"><span>Method</span><strong id="methodName"></strong></div>
  <div class="summary-item"><span>Qualified name</span><strong id="qualifiedName"></strong></div>
  <div class="summary-item"><span>Location / size</span><strong id="sizeInfo"></strong></div>
  <div class="summary-item"><span>Backend / graph</span><strong id="backendInfo"></strong></div>
</section>

<main class="layout" id="display">
  <section class="stack">
    <div class="panel">
      <div class="panel-header">
        <h2>Method Source</h2>
        <span id="sourcePill" class="pill"></span>
      </div>
      <pre class="code-block"><code id="methodCode"></code></pre>
    </div>
    <div class="panel">
      <div class="panel-header">
        <h2>Javadoc</h2>
        <span id="docPill" class="pill"></span>
      </div>
      <div id="javadocText" class="doc"></div>
    </div>
  </section>

  <section class="right-stack">
    <div class="panel">
      <div class="panel-header">
        <h2>Structured Context</h2>
        <span id="schemaPill" class="pill"></span>
      </div>
      <div class="two-col">
        <div>
          <h3>MUT</h3>
          <dl id="mutDetails"></dl>
        </div>
        <div>
          <h3>Metadata / Provenance</h3>
          <dl id="metaDetails"></dl>
        </div>
      </div>
      <h3>Source Context</h3>
      <pre class="code-block"><code id="sourceContext"></code></pre>
      <h3>Javadoc References</h3>
      <pre class="code-block"><code id="javadocReferences"></code></pre>
      <h3>Structured Javadoc Tags</h3>
      <pre class="code-block"><code id="structuredTags"></code></pre>
    </div>

    <div class="panel">
      <div class="panel-header">
        <h2>Graph / Metrics / Raw JSON</h2>
        <span id="graphPill" class="pill"></span>
      </div>
      <div class="tabs">
        <button class="tab active" data-tab="callers">Callers</button>
        <button class="tab" data-tab="callees">Callees</button>
        <button class="tab" data-tab="metrics">Doc Metrics</button>
        <button class="tab" data-tab="docmeta">Javadoc Metadata</button>
        <button class="tab" data-tab="dynamic">Dynamic</button>
        <button class="tab" data-tab="raw">Raw Record</button>
      </div>
      <pre class="code-block tab-pane" id="pane-callers"><code id="callers"></code></pre>
      <pre class="code-block tab-pane hidden" id="pane-callees"><code id="callees"></code></pre>
      <pre class="code-block tab-pane hidden" id="pane-metrics"><code id="metrics"></code></pre>
      <pre class="code-block tab-pane hidden" id="pane-docmeta"><code id="docmeta"></code></pre>
      <pre class="code-block tab-pane hidden" id="pane-dynamic"><code id="dynamic"></code></pre>
      <pre class="code-block tab-pane hidden" id="pane-raw"><code id="raw"></code></pre>
    </div>
  </section>
</main>

<script>
let datasets = [];
let currentDataset = "";
let totalRecords = 0;
let filteredRecords = 0;
let currentIndex = 0;
let currentPosition = 0;
let currentRecord = null;

const $ = (id) => document.getElementById(id);

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function showStatus(message) {
  $("status").textContent = message || "";
}

function asText(value, fallback = "") {
  if (value === undefined || value === null || value === "") return fallback;
  if (Array.isArray(value)) return value.length ? value.join(", ") : fallback;
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function pretty(value) {
  if (value === undefined || value === null) return "(missing)";
  return JSON.stringify(value, null, 2);
}

function highlightJava(code) {
  const keywords = new Set("abstract assert boolean break byte case catch char class const continue default do double else enum exports extends final finally float for if implements import instanceof int interface long module native new non-sealed null open opens package permits private protected public record requires return sealed short static strictfp super switch synchronized this throw throws to transient transitive try uses var void volatile while with yield".split(" "));
  const text = code || "(missing method source)";
  const token = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$-]*/g;
  let out = "";
  let last = 0;
  for (const match of text.matchAll(token)) {
    out += escapeHtml(text.slice(last, match.index));
    const value = match[0];
    if (value.startsWith("/*") || value.startsWith("//")) {
      out += '<span class="com">' + escapeHtml(value) + '</span>';
    } else if (value.startsWith('"') || value.startsWith("'")) {
      out += '<span class="str">' + escapeHtml(value) + '</span>';
    } else if (keywords.has(value)) {
      out += '<span class="kw">' + escapeHtml(value) + '</span>';
    } else {
      out += escapeHtml(value);
    }
    last = match.index + value.length;
  }
  out += escapeHtml(text.slice(last));
  return out;
}

function highlightJson(value) {
  const text = typeof value === "string" ? value : pretty(value);
  return escapeHtml(text).replace(
    /(&quot;(?:\\.|[^&])*?&quot;)(\s*:)?|\b(true|false|null)\b|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/g,
    (match, stringValue, colon, boolValue) => {
      if (stringValue && colon) return '<span class="json-key">' + stringValue + '</span>' + colon;
      if (stringValue) return '<span class="json-string">' + stringValue + '</span>';
      if (boolValue) return '<span class="json-bool">' + boolValue + '</span>';
      return '<span class="json-number">' + match + '</span>';
    }
  );
}

function setHtml(id, html) {
  $(id).innerHTML = html;
}

function setText(id, value) {
  $(id).textContent = value;
}

function setJson(id, value) {
  setHtml(id, highlightJson(value));
}

function fillDl(id, rows) {
  const dl = $(id);
  dl.replaceChildren();
  for (const [label, value] of rows) {
    const dt = document.createElement("dt");
    const dd = document.createElement("dd");
    dt.textContent = label;
    dd.textContent = asText(value, "(missing)");
    dl.appendChild(dt);
    dl.appendChild(dd);
  }
}

function callGraphMeta(record) {
  const metadata = record.metadata || {};
  const cg = metadata.call_graph || {};
  return {
    available: cg.available ?? metadata.call_graph_available,
    tool: cg.tool ?? metadata.call_graph_tool,
    algorithm: cg.algorithm ?? metadata.call_graph_algorithm,
    confidence: cg.confidence ?? metadata.call_graph_confidence,
  };
}

function docFlags(metrics) {
  return [
    metrics.has_summary ? "summary" : "no summary",
    metrics.has_param_tags ? "params" : "no params",
    metrics.has_return_tag ? "return" : "no return",
    metrics.has_throws_tag ? "throws" : "",
    metrics.has_see_tag ? "see" : "",
    metrics.uses_inheritdoc ? "inheritDoc" : "",
  ].filter(Boolean).join(" / ");
}

async function loadDatasets() {
  const resp = await fetch("/api/datasets");
  if (!resp.ok) throw new Error(await resp.text());
  datasets = await resp.json();
  const select = $("datasetSelect");
  select.replaceChildren();
  if (datasets.length === 0) {
    select.appendChild(new Option("No JSONL files found", ""));
    showStatus("No JSONL files found. Start the viewer with a JSONL file or a directory containing JSONL files.");
    return;
  }
  for (const dataset of datasets) {
    const label = `${dataset.label} (${dataset.records} records, ${dataset.size_mb} MB)`;
    select.appendChild(new Option(label, dataset.id));
  }
  await loadDataset(datasets[0].id);
}

async function loadDataset(id) {
  currentDataset = id;
  showStatus("Indexing JSONL...");
  const resp = await fetch(`/api/file-info?id=${encodeURIComponent(id)}`);
  if (!resp.ok) throw new Error(await resp.text());
  const info = await resp.json();
  totalRecords = info.records;
  filteredRecords = info.records;
  $("datasetStats").textContent = `${info.records} records, ${info.size_mb} MB`;
  currentIndex = 0;
  currentPosition = 0;
  await applyFilters(0);
}

function collectFilters() {
  return {
    q: $("searchText").value.trim(),
    tags: Array.from(document.querySelectorAll('input[name="filterTag"]:checked')).map((input) => input.value),
    tag_mode: $("filterTagMode").value,
    resolution: $("filterResolution").value,
    reference_scope: $("filterReferenceScope").value,
    reference_domain: $("filterReferenceDomain").value,
    reference_target_kind: $("filterReferenceTargetKind").value,
    source_set: $("filterSourceSet").value,
    visibility: $("filterVisibility").value,
    backend: $("filterBackend").value,
    call_graph: $("filterCallGraph").value,
    min_loc: $("filterMinLoc").value,
    min_cc: $("filterMinCc").value,
    has_javadoc: $("filterHasJavadoc").checked,
    has_callers: $("filterHasCallers").checked,
    has_callees: $("filterHasCallees").checked,
  };
}

function filtersQuery(position) {
  return new URLSearchParams({
    id: currentDataset,
    position: String(position),
    filters: JSON.stringify(collectFilters()),
  }).toString();
}

async function applyFilters(position = 0) {
  if (!currentDataset) return;
  showStatus("Applying filters...");
  const resp = await fetch(`/api/filtered-record?${filtersQuery(position)}`);
  if (!resp.ok) throw new Error(await resp.text());
  const payload = await resp.json();
  filteredRecords = payload.filtered_records;
  if (filteredRecords === 0) {
    currentIndex = -1;
    currentPosition = 0;
    currentRecord = null;
    $("recordInfo").textContent = `Record 0 / 0`;
    $("jumpTo").value = 0;
    $("prevBtn").disabled = true;
    $("nextBtn").disabled = true;
    showStatus(`No records match the active filters. Dataset size: ${totalRecords}.`);
    return;
  }
  currentIndex = payload.index;
  currentPosition = payload.position;
  currentRecord = payload.record;
  renderRecord(currentRecord);
  showStatus(activeFilterLabel());
}

async function loadFilteredRecord(position) {
  if (!currentDataset || filteredRecords === 0) return;
  if (position < 0 || position >= filteredRecords) return;
  await applyFilters(position);
}

async function loadRecord(index) {
  if (!currentDataset || totalRecords === 0) return;
  if (index < 0 || index >= totalRecords) return;
  const resp = await fetch(`/api/record?id=${encodeURIComponent(currentDataset)}&index=${index}`);
  if (!resp.ok) throw new Error(await resp.text());
  const payload = await resp.json();
  currentIndex = payload.index;
  currentPosition = payload.index;
  currentRecord = payload.record;
  filteredRecords = totalRecords;
  renderRecord(currentRecord);
  showStatus("");
}

function activeFilterLabel() {
  const filters = collectFilters();
  const active = [];
  for (const [key, value] of Object.entries(filters)) {
    if (value === true || (typeof value === "string" && value.trim())) {
      active.push(`${key}=${value}`);
    } else if (Array.isArray(value) && value.length) {
      active.push(`${key}=${value.join("|")}`);
    }
  }
  if (active.length === 0) return "";
  return `Filtered ${filteredRecords} / ${totalRecords}: ${active.join(", ")}`;
}

function renderRecord(record) {
  const mut = record.MUT || {};
  const metadata = record.metadata || {};
  const provenance = record.provenance || {};
  const metrics = record.documentation_metrics || {};
  const javadocMeta = record.javadoc_metadata || {};
  const selection = record.selection || {};
  const graph = callGraphMeta(record);

  setText("selectionInfo", `${asText(selection.kind, "unknown")} / ${asText(selection.selector || selection.uri, "unknown")}`);
  setText("methodName", asText(mut.method_name, "(missing)"));
  setText("qualifiedName", asText(mut.qualified_name || mut.signature, "(missing)"));
  setText("sizeInfo", `line ${asText(mut.line_number, "?")} / LOC ${asText(mut.lines_of_code, "?")} / CC ${asText(mut.cyclomatic_complexity, "?")}`);
  setText("backendInfo", `${asText(metadata.source_backend_mode, "?")} / ${asText(graph.algorithm, "N/A")}`);
  setText("sourcePill", `${asText(mut.source_set, "unknown")} / ${asText(mut.erased_return_type || mut.return_type, "return ?")}`);
  setText("schemaPill", `schema ${asText(metadata.schema_version, "?")}`);
  setText("docPill", docFlags(metrics));
  setText("graphPill", `${(record.callers || []).length} callers / ${(record.callees || []).length} callees`);

  setHtml("methodCode", highlightJava(mut.code));
  setText("javadocText", mut.javadoc || "(missing method javadoc)");

  fillDl("mutDetails", [
    ["method_uri", mut.method_uri],
    ["signature", mut.signature],
    ["return_type", mut.return_type],
    ["erased_return_type", mut.erased_return_type],
    ["source_set", mut.source_set],
    ["line_number", mut.line_number],
    ["parameters", (mut.parameters || []).map((p) => `${p.type || "?"} ${p.name || "?"}`)],
    ["annotations", mut.annotations],
    ["throws", mut.throws],
    ["class", mut.class_hierarchy && mut.class_hierarchy.hierarchy_detail],
    ["class_javadoc", mut.class_javadoc],
  ]);

  fillDl("metaDetails", [
    ["selection.kind", selection.kind],
    ["selection.uri", selection.uri],
    ["selection.selector", selection.selector],
    ["schema_version", metadata.schema_version],
    ["source_backend", metadata.source_backend],
    ["source_backend_mode", metadata.source_backend_mode],
    ["method_identity", metadata.method_identity],
    ["type_resolution", metadata.type_resolution],
    ["call_graph.available", graph.available],
    ["call_graph.tool", graph.tool],
    ["call_graph.algorithm", graph.algorithm],
    ["call_graph.confidence", graph.confidence],
    ["failure_codes", metadata.failure_codes || record.failure_codes],
  ]);

  setJson("sourceContext", mut.source_context || {});
  setJson("javadocReferences", javadocMeta.javadoc_references || {
    see: javadocMeta.see || [],
    inline_links: javadocMeta.inline_links || [],
    inherited_javadoc_candidates: javadocMeta.inherited_javadoc_candidates || [],
    file_references: javadocMeta.file_references || [],
  });
  setJson("structuredTags", javadocMeta.structured_tags || {});
  setJson("callers", record.callers || []);
  setJson("callees", record.callees || []);
  setJson("metrics", metrics);
  setJson("docmeta", javadocMeta);
  setJson("dynamic", record.dynamic_features || []);
  setJson("raw", record);

  $("recordInfo").textContent = `Record ${currentPosition + 1} / ${filteredRecords}`;
  $("jumpTo").value = currentPosition + 1;
  $("jumpTo").max = filteredRecords;
  $("prevBtn").disabled = currentPosition === 0;
  $("nextBtn").disabled = currentPosition >= filteredRecords - 1;
}

function activateTab(name) {
  for (const button of document.querySelectorAll(".tab")) {
    button.classList.toggle("active", button.dataset.tab === name);
  }
  for (const pane of document.querySelectorAll(".tab-pane")) {
    pane.classList.toggle("hidden", pane.id !== "pane-" + name);
  }
}

$("datasetSelect").addEventListener("change", (event) => loadDataset(event.target.value).catch((e) => showStatus(e.message)));
$("prevBtn").addEventListener("click", () => loadFilteredRecord(currentPosition - 1).catch((e) => showStatus(e.message)));
$("nextBtn").addEventListener("click", () => loadFilteredRecord(currentPosition + 1).catch((e) => showStatus(e.message)));
$("jumpBtn").addEventListener("click", () => {
  const value = Number.parseInt($("jumpTo").value, 10);
  if (!Number.isNaN(value)) loadFilteredRecord(value - 1).catch((e) => showStatus(e.message));
});
$("jumpTo").addEventListener("keydown", (event) => {
  if (event.key === "Enter") $("jumpBtn").click();
});
$("searchBtn").addEventListener("click", () => applyFilters(0).catch((e) => showStatus(e.message)));
$("searchText").addEventListener("keydown", (event) => {
  if (event.key === "Enter") $("searchBtn").click();
});
$("toggleFiltersBtn").addEventListener("click", () => $("filterPanel").classList.toggle("hidden"));
$("applyFiltersBtn").addEventListener("click", () => applyFilters(0).catch((e) => showStatus(e.message)));
$("clearFiltersBtn").addEventListener("click", () => {
  $("searchText").value = "";
  for (const input of document.querySelectorAll('input[name="filterTag"]')) {
    input.checked = false;
  }
  $("filterTagMode").value = "or";
  for (const id of [
    "filterResolution",
    "filterReferenceScope",
    "filterReferenceDomain",
    "filterReferenceTargetKind",
    "filterSourceSet",
    "filterVisibility",
    "filterBackend",
    "filterCallGraph"
  ]) {
    $(id).value = "";
  }
  for (const id of ["filterMinLoc", "filterMinCc"]) {
    $(id).value = "";
  }
  for (const id of ["filterHasJavadoc", "filterHasCallers", "filterHasCallees"]) {
    $(id).checked = false;
  }
  applyFilters(0).catch((e) => showStatus(e.message));
});
for (const id of [
  "filterTagMode",
  "filterResolution",
  "filterReferenceScope",
  "filterReferenceDomain",
  "filterReferenceTargetKind",
  "filterSourceSet",
  "filterVisibility",
  "filterBackend",
  "filterCallGraph"
]) {
  $(id).addEventListener("change", () => applyFilters(0).catch((e) => showStatus(e.message)));
}
for (const input of document.querySelectorAll('input[name="filterTag"]')) {
  input.addEventListener("change", () => applyFilters(0).catch((e) => showStatus(e.message)));
}
$("copyUriBtn").addEventListener("click", async () => {
  const uri = currentRecord && currentRecord.MUT && currentRecord.MUT.method_uri;
  if (!uri) return;
  try {
    await navigator.clipboard.writeText(uri);
    showStatus("Copied method URI.");
    window.setTimeout(() => showStatus(""), 1400);
  } catch {
    showStatus(uri);
  }
});
for (const button of document.querySelectorAll(".tab")) {
  button.addEventListener("click", () => activateTab(button.dataset.tab));
}

loadDatasets().catch((error) => showStatus("Failed to initialize viewer: " + error.message));
</script>
</body>
</html>
"""


@dataclass(frozen=True)
class Dataset:
    id: str
    path: Path
    label: str


class JsonlIndex:
    def __init__(self, datasets: list[Dataset]):
        self.datasets = {dataset.id: dataset for dataset in datasets}
        self._cache: dict[str, dict[str, Any]] = {}
        self._filter_cache: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()

    def _dataset(self, dataset_id: str) -> Dataset:
        dataset = self.datasets.get(dataset_id)
        if dataset is None:
            raise ValueError(f"unknown dataset id: {dataset_id}")
        return dataset

    def _build(self, path: Path) -> dict[str, Any]:
        stat = path.stat()
        key = str(path)
        with self._lock:
            cached = self._cache.get(key)
            if cached and cached["mtime_ns"] == stat.st_mtime_ns and cached["size"] == stat.st_size:
                return cached

            offsets: list[int] = []
            with path.open("rb") as handle:
                while True:
                    offset = handle.tell()
                    line = handle.readline()
                    if not line:
                        break
                    if line.strip():
                        offsets.append(offset)

            entry = {
                "path": path,
                "mtime_ns": stat.st_mtime_ns,
                "size": stat.st_size,
                "offsets": offsets,
            }
            self._cache[key] = entry
            return entry

    def list_datasets(self) -> list[dict[str, Any]]:
        rows = []
        for dataset in self.datasets.values():
            entry = self._build(dataset.path)
            rows.append({
                "id": dataset.id,
                "label": dataset.label,
                "path": str(dataset.path),
                "records": len(entry["offsets"]),
                "size": entry["size"],
                "size_mb": round(entry["size"] / (1024 * 1024), 2),
            })
        return rows

    def info(self, dataset_id: str) -> dict[str, Any]:
        dataset = self._dataset(dataset_id)
        entry = self._build(dataset.path)
        return {
            "id": dataset.id,
            "label": dataset.label,
            "path": str(dataset.path),
            "records": len(entry["offsets"]),
            "size": entry["size"],
            "size_mb": round(entry["size"] / (1024 * 1024), 2),
        }

    def record(self, dataset_id: str, index: int) -> dict[str, Any]:
        dataset = self._dataset(dataset_id)
        entry = self._build(dataset.path)
        offsets = entry["offsets"]
        if index < 0 or index >= len(offsets):
            raise IndexError("record index out of range")
        with dataset.path.open("rb") as handle:
            handle.seek(offsets[index])
            raw = handle.readline().decode("utf-8")
        return json.loads(raw)

    def filtered_record(self, dataset_id: str, filters: dict[str, Any], position: int) -> dict[str, Any]:
        indexes = self.filtered_indexes(dataset_id, filters)
        if not indexes:
            return {
                "position": 0,
                "index": -1,
                "filtered_records": 0,
                "record": None,
            }
        position = max(0, min(position, len(indexes) - 1))
        index = indexes[position]
        return {
            "position": position,
            "index": index,
            "filtered_records": len(indexes),
            "record": self.record(dataset_id, index),
        }

    def filtered_indexes(self, dataset_id: str, filters: dict[str, Any]) -> list[int]:
        dataset = self._dataset(dataset_id)
        entry = self._build(dataset.path)
        normalized = normalize_filters(filters)
        cache_key = json.dumps({
            "dataset": dataset_id,
            "mtime_ns": entry["mtime_ns"],
            "size": entry["size"],
            "filters": normalized,
        }, sort_keys=True)
        with self._lock:
            cached = self._filter_cache.get(cache_key)
            if cached is not None:
                return list(cached["indexes"])

        indexes: list[int] = []
        with dataset.path.open("rb") as handle:
            for index, offset in enumerate(entry["offsets"]):
                handle.seek(offset)
                line = handle.readline().decode("utf-8", errors="replace")
                if line_matches_filters(line, normalized):
                    indexes.append(index)

        with self._lock:
            self._filter_cache[cache_key] = {"indexes": indexes}
            if len(self._filter_cache) > 32:
                first = next(iter(self._filter_cache))
                self._filter_cache.pop(first, None)
        return indexes

    def search(self, dataset_id: str, query: str, start: int) -> tuple[int, dict[str, Any] | None]:
        dataset = self._dataset(dataset_id)
        entry = self._build(dataset.path)
        offsets = entry["offsets"]
        if not offsets:
            return -1, None

        query_lower = query.lower()
        start = max(0, min(start, len(offsets)))
        ranges = (range(start, len(offsets)), range(0, start))
        with dataset.path.open("rb") as handle:
            for search_range in ranges:
                for index in search_range:
                    handle.seek(offsets[index])
                    line = handle.readline().decode("utf-8", errors="replace")
                    if query_lower in line.lower():
                        return index, json.loads(line)
        return -1, None


def normalize_filters(filters: dict[str, Any] | None) -> dict[str, Any]:
    filters = filters or {}
    normalized: dict[str, Any] = {}
    for key in [
        "q",
        "tag_mode",
        "resolution",
        "reference_scope",
        "reference_domain",
        "reference_target_kind",
        "source_set",
        "visibility",
        "backend",
        "call_graph",
        "min_loc",
        "min_cc",
    ]:
        value = filters.get(key, "")
        normalized[key] = str(value).strip() if value is not None else ""
    tags = filters.get("tags", [])
    if not tags and filters.get("tag"):
        tags = [filters.get("tag")]
    if isinstance(tags, str):
        tags = [tags]
    normalized["tags"] = [str(tag).strip() for tag in tags if str(tag).strip()]
    if normalized["tag_mode"].lower() not in {"and", "or"}:
        normalized["tag_mode"] = "or"
    for key in ["has_javadoc", "has_callers", "has_callees"]:
        normalized[key] = bool(filters.get(key, False))
    return normalized


def line_matches_filters(line: str, filters: dict[str, Any]) -> bool:
    if filters.get("q") and filters["q"].lower() not in line.lower():
        return False
    try:
        record = json.loads(line)
    except json.JSONDecodeError:
        return False

    mut = record.get("MUT") or {}
    metadata = record.get("metadata") or {}
    metrics = record.get("documentation_metrics") or {}
    javadoc_meta = record.get("javadoc_metadata") or {}
    call_graph = metadata.get("call_graph") or {}

    if filters.get("tags"):
        tag_matches = [has_javadoc_tag(tag, metrics, javadoc_meta) for tag in filters["tags"]]
        if filters.get("tag_mode") == "and":
            if not all(tag_matches):
                return False
        elif not any(tag_matches):
            return False
    if filters.get("resolution") and not has_reference_resolution(javadoc_meta, filters["resolution"]):
        return False
    if filters.get("reference_scope") and not has_reference_field(javadoc_meta, "reference_scope", filters["reference_scope"]):
        return False
    if filters.get("reference_domain") and not has_reference_field(javadoc_meta, "reference_domain", filters["reference_domain"]):
        return False
    if filters.get("reference_target_kind") and not has_reference_field(
            javadoc_meta, "reference_target_kind", filters["reference_target_kind"]):
        return False
    if filters.get("source_set") and mut.get("source_set") != filters["source_set"]:
        return False
    if filters.get("visibility") and (mut.get("visibility") or inferred_visibility(mut.get("code") or "")) != filters["visibility"]:
        return False
    if filters.get("backend") and metadata.get("source_backend_mode") != filters["backend"]:
        return False
    if filters.get("call_graph") and not matches_call_graph_filter(call_graph, metadata, filters["call_graph"]):
        return False
    if filters.get("min_loc") and int_value(mut.get("lines_of_code")) < int_value(filters["min_loc"]):
        return False
    if filters.get("min_cc") and int_value(mut.get("cyclomatic_complexity")) < int_value(filters["min_cc"]):
        return False
    has_method_javadoc = bool((mut.get("javadoc") or "").strip())
    if filters.get("has_javadoc") and not has_method_javadoc:
        return False
    if filters.get("has_callers") and not record.get("callers"):
        return False
    if filters.get("has_callees") and not record.get("callees"):
        return False
    return True


def int_value(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def inferred_visibility(code: str) -> str:
    prefix = code.strip().split("{", 1)[0]
    tokens = set(prefix.replace("(", " ").replace("\n", " ").split())
    if "public" in tokens:
        return "public"
    if "protected" in tokens:
        return "protected"
    if "private" in tokens:
        return "private"
    return "package-private"


def has_javadoc_tag(tag: str, metrics: dict[str, Any], javadoc_meta: dict[str, Any]) -> bool:
    tag = tag.strip()
    structured = javadoc_meta.get("structured_tags") or {}
    refs = javadoc_meta.get("javadoc_references") or []
    if tag == "see":
        return bool(metrics.get("has_see_tag") or javadoc_meta.get("see")
                    or any(ref.get("tag") == "see" for ref in refs if isinstance(ref, dict)))
    if tag in {"link", "linkplain"}:
        return any(ref.get("tag") == tag for ref in refs if isinstance(ref, dict))
    if tag == "inheritdoc":
        return bool(metrics.get("uses_inheritdoc") or javadoc_meta.get("uses_inheritdoc"))
    metric_map = {
        "param": "has_param_tags",
        "return": "has_return_tag",
        "throws": "has_throws_tag",
        "since": "has_since_tag",
        "deprecated": "deprecated",
    }
    if tag in metric_map and metrics.get(metric_map[tag]):
        return True
    if tag == "deprecated" and javadoc_meta.get("deprecated"):
        return True
    return bool(structured.get(tag) or structured.get(tag + "s"))


def has_reference_resolution(javadoc_meta: dict[str, Any], resolution: str) -> bool:
    refs = javadoc_meta.get("javadoc_references") or []
    return any(isinstance(ref, dict) and ref.get("resolution") == resolution for ref in refs)


def has_reference_field(javadoc_meta: dict[str, Any], field: str, value: str) -> bool:
    refs = javadoc_meta.get("javadoc_references") or []
    return any(isinstance(ref, dict) and ref.get(field) == value for ref in refs)


def matches_call_graph_filter(call_graph: dict[str, Any], metadata: dict[str, Any], wanted: str) -> bool:
    available = call_graph.get("available")
    if available is None:
        available = metadata.get("call_graph_available")
    algorithm = call_graph.get("algorithm") or metadata.get("call_graph_algorithm") or ""
    if wanted == "available":
        return bool(available)
    if wanted == "unavailable":
        return not bool(available)
    return str(algorithm).upper() == wanted.upper()


class ViewerHandler(http.server.SimpleHTTPRequestHandler):
    index: JsonlIndex

    def _send_json(self, payload: Any, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, message: str, status: int = 400) -> None:
        body = message.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self) -> None:
        body = HTML_PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        try:
            if parsed.path in ("", "/"):
                self._send_html()
                return

            if parsed.path == "/api/datasets":
                self._send_json(self.index.list_datasets())
                return

            if parsed.path == "/api/file-info":
                dataset_id = params.get("id", [""])[0]
                self._send_json(self.index.info(dataset_id))
                return

            if parsed.path == "/api/record":
                dataset_id = params.get("id", [""])[0]
                index = int(params.get("index", ["0"])[0])
                self._send_json({"index": index, "record": self.index.record(dataset_id, index)})
                return

            if parsed.path == "/api/filtered-record":
                dataset_id = params.get("id", [""])[0]
                position = int(params.get("position", ["0"])[0])
                raw_filters = params.get("filters", ["{}"])[0]
                filters = json.loads(raw_filters) if raw_filters else {}
                self._send_json(self.index.filtered_record(dataset_id, filters, position))
                return

            if parsed.path == "/api/search":
                dataset_id = params.get("id", [""])[0]
                query = params.get("q", [""])[0]
                start = int(params.get("start", ["0"])[0])
                if not query:
                    self._send_json({"index": -1, "record": None})
                    return
                index, record = self.index.search(dataset_id, query=query, start=start)
                self._send_json({"index": index, "record": record})
                return

            self._send_text("not found", status=404)
        except (ValueError, IndexError, json.JSONDecodeError) as exc:
            self._send_text(str(exc), status=400)
        except OSError as exc:
            self._send_text(str(exc), status=500)

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s - - [%s] %s\n" % (
            self.address_string(),
            self.log_date_time_string(),
            fmt % args,
        ))


def discover_jsonl(paths: list[Path]) -> list[Path]:
    found: list[Path] = []
    seen: set[Path] = set()
    for raw in paths:
        path = raw.expanduser().resolve()
        if path.is_file() and path.suffix == ".jsonl":
            candidates = [path]
        elif path.is_dir():
            candidates = sorted(child for child in path.rglob("*.jsonl") if child.is_file())
        else:
            raise SystemExit(f"Not a JSONL file or directory: {raw}")

        for candidate in candidates:
            resolved = candidate.resolve()
            if resolved not in seen:
                seen.add(resolved)
                found.append(resolved)
    return found


def make_datasets(files: list[Path]) -> list[Dataset]:
    if not files:
        return []
    common = Path(os.path.commonpath([str(path.parent) for path in files]))
    datasets = []
    used_labels: dict[str, int] = {}
    for index, path in enumerate(files):
        try:
            label = str(path.relative_to(common))
        except ValueError:
            label = str(path)
        if label == path.name and path.parent.name:
            label = f"{path.parent.name}/{path.name}"
        count = used_labels.get(label, 0)
        used_labels[label] = count + 1
        if count:
            label = f"{label} [{count + 1}]"
        datasets.append(Dataset(id=str(index), path=path, label=label))
    return datasets


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Serve a dependency-free web viewer for CoCoMUT method context JSONL files."
    )
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help="JSONL files or directories to scan recursively for *.jsonl files.",
    )
    parser.add_argument(
        "--jsonl",
        action="append",
        type=Path,
        default=[],
        help="JSONL file to include. Can be passed multiple times.",
    )
    parser.add_argument(
        "--data-dir",
        action="append",
        type=Path,
        default=[],
        help="Directory to scan recursively for JSONL files. Can be passed multiple times.",
    )
    parser.add_argument("--host", default="127.0.0.1", help="Host interface to bind. Default: 127.0.0.1.")
    parser.add_argument("--port", type=int, default=8080, help="HTTP port. Default: 8080.")
    parser.add_argument("--no-open", action="store_true", help="Do not open the viewer in the default browser.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [*args.paths, *args.jsonl, *args.data_dir]
    if not inputs:
        inputs = [Path.cwd()]

    files = discover_jsonl(inputs)
    datasets = make_datasets(files)
    if not datasets:
        print("No .jsonl files found in the supplied paths.", file=sys.stderr)
        return 2

    ViewerHandler.index = JsonlIndex(datasets)
    server = http.server.ThreadingHTTPServer((args.host, args.port), ViewerHandler)
    url = f"http://{args.host}:{args.port}"

    print(f"Serving {len(datasets)} JSONL file(s) at {url}")
    for dataset in datasets[:20]:
        print(f"  [{dataset.id}] {dataset.label} -> {dataset.path}")
    if len(datasets) > 20:
        print(f"  ... {len(datasets) - 20} more")
    print("Press Ctrl-C to stop.")

    if not args.no_open:
        webbrowser.open(url)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
