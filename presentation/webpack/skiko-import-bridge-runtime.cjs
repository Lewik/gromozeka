// Keep this CommonJS module non-strict: WebKit 329421 corrupts numeric arguments
// through strict-mode JS-to-Wasm tail calls. ES modules and try/catch wrappers
// still reproduce the corruption after JIT warmup on iOS 27.2.
module.exports = function bridgeSkikoImports(imports) {
    return Object.fromEntries(Object.entries(imports).map(([name, value]) => [
        name,
        typeof value === 'function' ? function (...args) {
            return value(...args);
        } : value
    ]));
};
