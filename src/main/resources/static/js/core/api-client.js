async function api(path, options = {}) {
    const response = await fetch(path, options);
    if (!response.ok) {
        const text = await response.text();
        let message = text;
        try {
            message = JSON.parse(text).message || text;
        } catch {
            message = text;
        }
        throw new Error(message || `HTTP ${response.status}`);
    }
    return response;
}
