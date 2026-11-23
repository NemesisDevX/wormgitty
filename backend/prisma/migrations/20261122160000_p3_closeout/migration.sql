-- Phase-3 closeout: real migration for everything previously pushed via
-- `prisma db push` (AppType, apk_version, notes, audit_events) plus the new
-- MVP-closeout schema (enroll tokens, credential hash, PII fields, mining).
-- Idempotent: adopts databases that already received these objects via
-- db push, and applies cleanly on a fresh database.

DO $$ BEGIN CREATE TYPE "AppType" AS ENUM ('worm', 'ghost', 'master'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "Platform" AS ENUM ('ANDROID', 'WINDOWS', 'LINUX', 'MACOS'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "MinerState" AS ENUM ('STOPPED', 'STARTING', 'RUNNING', 'STOPPING', 'ERROR'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- devices: new columns (IF NOT EXISTS absorbs prior db-push drift).
ALTER TABLE "devices"
  ADD COLUMN IF NOT EXISTS "apk_version" TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS "app_type" "AppType" NOT NULL DEFAULT 'worm',
  ADD COLUMN IF NOT EXISTS "credential_hash" TEXT,
  ADD COLUMN IF NOT EXISTS "display_name" TEXT,
  ADD COLUMN IF NOT EXISTS "last_hashrate" JSONB,
  ADD COLUMN IF NOT EXISTS "miner_state" "MinerState" NOT NULL DEFAULT 'STOPPED',
  ADD COLUMN IF NOT EXISTS "notes" TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS "phone_enc" TEXT,
  ADD COLUMN IF NOT EXISTS "phone_masked" TEXT;

-- platform: TEXT -> enum with value preservation (only when still TEXT).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'devices' AND column_name = 'platform' AND data_type <> 'USER-DEFINED'
  ) THEN
    ALTER TABLE "devices" ADD COLUMN "platform_new" "Platform" NOT NULL DEFAULT 'ANDROID';
    UPDATE "devices" SET "platform_new" = CASE upper("platform"::text)
      WHEN 'ANDROID' THEN 'ANDROID'::"Platform"
      WHEN 'WINDOWS' THEN 'WINDOWS'::"Platform"
      WHEN 'LINUX'   THEN 'LINUX'::"Platform"
      WHEN 'MACOS'   THEN 'MACOS'::"Platform"
      ELSE 'ANDROID'::"Platform" END;
    ALTER TABLE "devices" DROP COLUMN "platform";
    ALTER TABLE "devices" RENAME COLUMN "platform_new" TO "platform";
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS "enroll_tokens" (
    "id" TEXT NOT NULL,
    "jti" TEXT NOT NULL,
    "device_id" TEXT,
    "consumed_at" TIMESTAMP(3),
    "expires_at" TIMESTAMP(3) NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "enroll_tokens_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "mining_sessions" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "algo" TEXT NOT NULL DEFAULT 'rx/0',
    "pool" TEXT NOT NULL DEFAULT '',
    "wallet" TEXT NOT NULL DEFAULT '',
    "threads" INTEGER NOT NULL DEFAULT 1,
    "started_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "stopped_at" TIMESTAMP(3),
    "stop_reason" TEXT,
    CONSTRAINT "mining_sessions_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "audit_events" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "actor" TEXT NOT NULL DEFAULT 'operator',
    "action" TEXT NOT NULL,
    "detail" JSONB,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "audit_events_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "enroll_tokens_jti_key" ON "enroll_tokens"("jti");
CREATE INDEX IF NOT EXISTS "mining_sessions_device_id_started_at_idx" ON "mining_sessions"("device_id", "started_at");
CREATE INDEX IF NOT EXISTS "audit_events_device_id_created_at_idx" ON "audit_events"("device_id", "created_at");

ALTER TABLE "mining_sessions" DROP CONSTRAINT IF EXISTS "mining_sessions_device_id_fkey";
ALTER TABLE "mining_sessions" ADD CONSTRAINT "mining_sessions_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "audit_events" DROP CONSTRAINT IF EXISTS "audit_events_device_id_fkey";
ALTER TABLE "audit_events" ADD CONSTRAINT "audit_events_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;
