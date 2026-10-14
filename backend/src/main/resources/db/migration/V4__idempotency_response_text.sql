-- A fat response (a large order, a long assistant reply) serialized past VARCHAR(8000), so
-- complete() threw AFTER the action had already committed; the key was then marked FAILED and a
-- retry reclaimed it -- running the action again and duplicating the order. TEXT removes the
-- overflow entirely; the RESULT_UNKNOWN status added alongside this migration is the backstop for
-- the still-possible "store failed for some other reason" case.
ALTER TABLE idempotency_records MODIFY response_json TEXT NULL;
