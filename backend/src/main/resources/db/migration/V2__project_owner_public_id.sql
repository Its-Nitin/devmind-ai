-- M1 step 7: project.owner_id references app_user(public_id) (uuid) instead of app_user(id).
-- The project module only knows the identity module's public UserId, so ownership checks need no lookup into
-- identity, and the FK still enforces integrity. This is the one FK that departs from the "bigint FKs internally"
-- convention (architecture §4; decision in docs/devlog.md, 2026-11-10).
-- No project can exist yet (there was no create endpoint before this step), so dropping the column loses no data.

ALTER TABLE project DROP CONSTRAINT project_owner_name_uk;
ALTER TABLE project DROP COLUMN owner_id;

ALTER TABLE project
    ADD COLUMN owner_id uuid NOT NULL REFERENCES app_user (public_id) ON DELETE CASCADE;

-- Also serves as the owner_id FK index (leading column).
ALTER TABLE project ADD CONSTRAINT project_owner_name_uk UNIQUE (owner_id, name);
