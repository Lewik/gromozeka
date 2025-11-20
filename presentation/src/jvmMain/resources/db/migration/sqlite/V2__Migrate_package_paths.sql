-- Migrate package paths from com.gromozeka.shared.domain to com.gromozeka.domain.model
-- This migration fixes deserialization errors caused by package refactoring

UPDATE messages 
SET message_json = replace(message_json, 'com.gromozeka.shared.domain', 'com.gromozeka.domain.model')
WHERE message_json LIKE '%com.gromozeka.shared.domain%';
