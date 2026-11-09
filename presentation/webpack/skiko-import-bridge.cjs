const path = require('node:path');

module.exports = function (source) {
    if (!source.includes("export const importObject =") || !source.includes("'./skiko.mjs':")) {
        throw new Error("Kotlin Wasm import layout changed; review the Skiko import bridge");
    }

    return source + `
import bridgeSkikoImports from ${JSON.stringify(path.join(__dirname, 'skiko-import-bridge-runtime.cjs'))};
importObject['./skiko.mjs'] = bridgeSkikoImports(importObject['./skiko.mjs']);
`;
};
