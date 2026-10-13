-- Optimistic lock for orders. changeStatus validates the transition on a plain (snapshot)
-- read, so the write must carry the version it validated against; without it, two racing
-- transitions both pass the in-memory check and the second silently overwrites the first.
ALTER TABLE orders
    ADD COLUMN version INT NOT NULL DEFAULT 0;
