-- Plaintext secret revelation was removed. Its v2 metadata no longer matches its stored fingerprint.
DELETE FROM ai_tool_contracts
WHERE source_id = 'gromozeka'
  AND logical_name = 'grz_reveal_secret';
