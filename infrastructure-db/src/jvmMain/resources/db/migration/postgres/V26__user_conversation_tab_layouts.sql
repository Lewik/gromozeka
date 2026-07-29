DELETE FROM conversation_tab_layouts;

ALTER TABLE conversation_tab_layouts
    RENAME COLUMN id TO user_id;

ALTER TABLE conversation_tab_layouts
    ALTER COLUMN user_id TYPE VARCHAR(255);

ALTER TABLE conversation_tab_layouts
    ADD CONSTRAINT fk_conversation_tab_layouts_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
