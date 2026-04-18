-- +goose Up
CREATE TABLE "user" (
    id           TEXT PRIMARY KEY,
    display_name TEXT NOT NULL
);

-- +goose Down
DROP TABLE "user";
