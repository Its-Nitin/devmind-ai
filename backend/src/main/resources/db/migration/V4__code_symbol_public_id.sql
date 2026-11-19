-- M2 step 8: symbols are addressed by the read API (search results, GET .../symbols/{symbolId}), and URLs carry only
-- UUID public IDs, never internal numeric ones (API-5), as for projects and jobs. Existing rows get an ID from the
-- default, so no re-index is needed; the indexer's inserts leave it to the default too.
ALTER TABLE code_symbol ADD COLUMN public_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX code_symbol_public_id_uk ON code_symbol (public_id);
