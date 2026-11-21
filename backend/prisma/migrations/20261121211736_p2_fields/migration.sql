-- AlterTable
ALTER TABLE "devices" ADD COLUMN     "formatted_at" TIMESTAMP(3),
ADD COLUMN     "gender" TEXT NOT NULL DEFAULT '',
ADD COLUMN     "stats" JSONB;

-- CreateTable
CREATE TABLE "device_photos" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "hash" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "category" TEXT NOT NULL DEFAULT 'original',
    "size" INTEGER NOT NULL DEFAULT 0,
    "pulled_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "device_photos_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "downloaded_files" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "kind" TEXT NOT NULL DEFAULT 'photo',
    "category" TEXT NOT NULL DEFAULT '',
    "path" TEXT NOT NULL,
    "sha256" TEXT NOT NULL DEFAULT '',
    "bytes" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "downloaded_files_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "device_photos_device_id_category_idx" ON "device_photos"("device_id", "category");

-- CreateIndex
CREATE UNIQUE INDEX "device_photos_device_id_hash_key" ON "device_photos"("device_id", "hash");

-- CreateIndex
CREATE INDEX "downloaded_files_device_id_idx" ON "downloaded_files"("device_id");

-- CreateIndex
CREATE INDEX "downloaded_files_sha256_idx" ON "downloaded_files"("sha256");

-- AddForeignKey
ALTER TABLE "device_photos" ADD CONSTRAINT "device_photos_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "downloaded_files" ADD CONSTRAINT "downloaded_files_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;
