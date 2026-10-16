-- Run once for an empty development database; application requests never modify the schema.
CREATE TABLE IF NOT EXISTS h34s_notes (
  id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  text text NOT NULL CHECK (length(trim(text)) BETWEEN 1 AND 2000)
);
