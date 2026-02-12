BEGIN;

-- migrate edc-holder-credentialrequest table ----

-- 1. Add the new 'ids_and_formats' column
ALTER TABLE edc_holder_credentialrequest 
ADD COLUMN ids_and_formats json;

-- 2. Migrate data: transform types_and_formats (object) to ids_and_formats (array)
-- Old format: {"MembershipCredential":"VC1_0_JWT"}
-- New format: [{"id":"membership-credential-def-1","credentialType":"MembershipCredential","format":"VC1_0_JWT"}]
UPDATE edc_holder_credentialrequest 
SET ids_and_formats = (
    SELECT json_agg(
        json_build_object(
            'id', CASE 
                WHEN key = 'MembershipCredential' THEN 'membership-credential-def-1'
                WHEN key = 'DomainCredential' THEN 'domain-credential-def-1'
                ELSE key
            END,
            'credentialType', key,
            'format', value
        )
    )
    FROM json_each_text(types_and_formats)
)
WHERE types_and_formats IS NOT NULL 
  AND types_and_formats::text != '{}'
  AND json_typeof(types_and_formats) = 'object';

-- 3. Set empty array for NULL values before adding NOT NULL constraint
UPDATE edc_holder_credentialrequest 
SET ids_and_formats = '[]'::json 
WHERE ids_and_formats IS NULL;

-- migrate edc-holder-credentialrequest table ----

-- 1. Add NOT NULL constraint
ALTER TABLE edc_holder_credentialrequest 
ALTER COLUMN ids_and_formats SET NOT NULL;

-- 2. Drop the old types_and_formats column
ALTER TABLE edc_holder_credentialrequest 
DROP COLUMN types_and_formats;

ALTER TABLE edc_data_plane 
ADD COLUMN resource_definitions json DEFAULT '[]'::json;

-- 3. Change the vc_format in credential_resource from 3 to 1 (JWT)
UPDATE credential_resource
SET vc_format = 1 WHERE vc_format = 3;

COMMIT;