// Shared by build-i18n.mjs and check-i18n.mjs.
/** Minimal java.util.Properties reader: comments, key=value, \uXXXX, line continuations. */
export function parseProperties(text) {
  const entries = {};
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i].replace(/^\s+/, '');
    if (line === '' || line.startsWith('#') || line.startsWith('!')) continue;
    while (/(^|[^\\])(\\\\)*\\$/.test(line) && i + 1 < lines.length) {
      line = line.slice(0, -1) + lines[++i].replace(/^\s+/, '');
    }
    const match = /^((?:\\.|[^=:\s\\])+)\s*[=:\s]\s*(.*)$/.exec(line);
    if (!match) continue;
    entries[unescape(match[1])] = unescape(match[2]);
  }
  return entries;
}

function unescape(value) {
  return value.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, c) => {
    if (c.startsWith('u') && c.length === 5) return String.fromCharCode(Number.parseInt(c.slice(1), 16));
    return { n: '\n', t: '\t', r: '\r' }[c] ?? c;
  });
}
