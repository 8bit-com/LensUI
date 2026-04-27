function compactJsonLogLines(logs) {
    return logs.split("\n").map(line => {
        const trimmed = line.trim();

        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return line;
        }

        try {
            const entry = JSON.parse(trimmed);

            if (!Object.prototype.hasOwnProperty.call(entry, "timestamp") || !Object.prototype.hasOwnProperty.call(entry, "message")) {
                return line;
            }

            return `${entry.timestamp} ${entry.level || ""} ${entry.message}`.trim();
        } catch {
            return compactJsonLineByPattern(line);
        }
    }).join("\n");
}

function compactJsonLineByPattern(line) {
    const timestampMatch = line.match(/"timestamp"\s*:\s*"([^"]*)"/);
    const levelMatch = line.match(/"level"\s*:\s*"(TRACE|DEBUG|INFO|WARN|ERROR)"/i);
    const messageMatch = line.match(/"message"\s*:\s*("(?:\\.|[^"\\])*")/);

    if (!timestampMatch || !messageMatch) {
        return line;
    }

    let message = messageMatch[1];

    try {
        message = JSON.parse(messageMatch[1]);
    } catch {
        message = messageMatch[1].slice(1, -1);
    }

    return `${timestampMatch[1]} ${levelMatch ? levelMatch[1].toUpperCase() : ""} ${message}`.trim();
}

function colorizeLevelsOnly(logs, wrapLines = true) {
    const lines = logs.split("\n");

    return lines.map(line => {
        const content = colorizeLevelInLine(line);
        return wrapLines ? `<span class="log-line">${content}</span>` : content;
    }).join("\n");
}

function colorizeLevelInLine(line) {
    const escaped = escapeHtml(line);
    const jsonLevelPattern = /(&quot;level&quot;\s*:\s*&quot;)(TRACE|DEBUG|INFO|WARN|ERROR)(&quot;)/i;

    if (jsonLevelPattern.test(escaped)) {
        return escaped.replace(jsonLevelPattern, (_, prefix, level, suffix) =>
            `${prefix}<span class="${levelClass(level)}">${level}</span>${suffix}`);
    }

    return escaped.replace(/\b(TRACE|DEBUG|INFO|WARN|ERROR)\b/i, level =>
        `<span class="${levelClass(level)}">${level}</span>`);
}

function levelClass(level) {
    return `log-level-${String(level || "").toLowerCase()}`;
}
