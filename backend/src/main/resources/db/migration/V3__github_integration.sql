-- Real GitHub PAT storage (AES-256-GCM encrypted) and live published PR comment URL
ALTER TABLE tenants ADD COLUMN github_token_encrypted TEXT;
ALTER TABLE pull_request_reviews ADD COLUMN github_comment_url VARCHAR(500);
