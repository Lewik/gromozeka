ALTER TABLE runtime_catalog_configuration
    ADD COLUMN web_tools_json TEXT NOT NULL DEFAULT '{}';
