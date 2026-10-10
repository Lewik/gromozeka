CREATE TABLE user_message_delivery_preferences (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    delivery_mode VARCHAR(32) NOT NULL CHECK (delivery_mode IN ('STEER', 'AFTER_CURRENT_TURN'))
);
