ALTER TABLE user_message_delivery_preferences
    DROP CONSTRAINT user_message_delivery_preferences_delivery_mode_check;

UPDATE user_message_delivery_preferences
SET delivery_mode = 'QUEUE'
WHERE delivery_mode = 'AFTER_CURRENT_TURN';

ALTER TABLE user_message_delivery_preferences
    ADD CONSTRAINT user_message_delivery_preferences_delivery_mode_check
        CHECK (delivery_mode IN ('STEER', 'QUEUE'));
