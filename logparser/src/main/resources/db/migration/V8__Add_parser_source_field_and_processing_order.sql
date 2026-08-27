ALTER TABLE parsers ADD COLUMN source_field VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_parsers_messagetype_priority
    ON parsers(messagetype, priority);

CREATE INDEX IF NOT EXISTS idx_transforms_messagetype_priority
    ON transforms(messagetype, priority);

-- Existing configurations used independent parser/transform priorities.
-- Normalize them into one parser-first processing order while preserving the
-- previous order within each component type.
CREATE TEMP TABLE processing_step_order AS
SELECT 'PARSER' AS kind,
       p.id AS component_id,
       p.messagetype AS messagetype,
       ROW_NUMBER() OVER (
           PARTITION BY p.messagetype
           ORDER BY COALESCE(p.priority, 2147483647), p.id
       ) * 10 AS new_priority
FROM parsers p
UNION ALL
SELECT 'TRANSFORM' AS kind,
       t.id AS component_id,
       t.messagetype AS messagetype,
       (
           SELECT COUNT(*) * 10
           FROM parsers p
           WHERE p.messagetype = t.messagetype
       ) + ROW_NUMBER() OVER (
           PARTITION BY t.messagetype
           ORDER BY COALESCE(t.priority, 2147483647), t.id
       ) * 10 AS new_priority
FROM transforms t;

UPDATE parsers
SET priority = (
    SELECT new_priority
    FROM processing_step_order o
    WHERE o.kind = 'PARSER' AND o.component_id = parsers.id
);

UPDATE transforms
SET priority = (
    SELECT new_priority
    FROM processing_step_order o
    WHERE o.kind = 'TRANSFORM' AND o.component_id = transforms.id
);

DROP TABLE processing_step_order;
