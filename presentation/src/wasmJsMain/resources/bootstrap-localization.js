// Generated from localization/*.json by scripts/generate-native-localization.py.
(() => {
    const translations = {"en":{"locale":"en","name":"English","direction":"ltr","initializing":"Initializing…"},"ru":{"locale":"ru","name":"Русский","direction":"ltr","initializing":"Инициализация…"},"he":{"locale":"he","name":"עברית","direction":"rtl","initializing":"מאתחל…"},"es":{"locale":"es","name":"Español","direction":"ltr","initializing":"Inicializando…"},"pt-BR":{"locale":"pt-BR","name":"Português (Brasil)","direction":"ltr","initializing":"Inicializando…"},"ja":{"locale":"ja","name":"日本語","direction":"ltr","initializing":"初期化中…"},"zh-Hans":{"locale":"zh-Hans","name":"简体中文","direction":"ltr","initializing":"正在初始化…"},"zh-Hant":{"locale":"zh-Hant","name":"繁體中文","direction":"ltr","initializing":"正在初始化…"},"de":{"locale":"de","name":"Deutsch","direction":"ltr","initializing":"Wird initialisiert…"},"fr":{"locale":"fr","name":"Français","direction":"ltr","initializing":"Initialisation…"},"ko":{"locale":"ko","name":"한국어","direction":"ltr","initializing":"초기화 중…"},"ar":{"locale":"ar","name":"العربية","direction":"rtl","initializing":"جارٍ التهيئة…"},"id":{"locale":"id","name":"Bahasa Indonesia","direction":"ltr","initializing":"Menginisialisasi…"}};
    function matchLocale(locale) {
        const normalized = locale.replaceAll("_", "-").toLowerCase();
        const exact = Object.keys(translations).find(key => key.toLowerCase() === normalized);
        if (exact) return exact;
        const parts = normalized.split("-");
        if (parts[0] === "zh") return parts.some(part => ["hant", "tw", "hk", "mo"].includes(part)) ? "zh-Hant" : "zh-Hans";
        if (parts[0] === "iw") return "he";
        return Object.keys(translations).find(key => key.split("-")[0].toLowerCase() === parts[0]) || "en";
    }
    let locale = navigator.language || "en";
    try {
        const settings = JSON.parse(localStorage.getItem("gromozeka.remoteClientSettings") || "null");
        if (settings && typeof settings.bootstrapLocale === "string") locale = settings.bootstrapLocale;
    } catch (_) {}
    const translation = translations[matchLocale(locale)];
    document.documentElement.lang = translation.locale;
    document.documentElement.dir = translation.direction;
    const loader = document.getElementById("bootstrapLoader");
    if (loader) loader.setAttribute("aria-label", translation.initializing);
})();
