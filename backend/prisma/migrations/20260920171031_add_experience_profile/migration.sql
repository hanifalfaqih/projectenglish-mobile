-- AlterTable
ALTER TABLE "Conversation" ADD COLUMN     "experienceProfileId" TEXT;

-- CreateTable
CREATE TABLE "ExperienceProfile" (
    "id" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "ExperienceProfile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ExperienceItem" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "organization" TEXT,
    "role" TEXT,
    "description" TEXT NOT NULL,
    "skills" TEXT[],
    "position" INTEGER NOT NULL,

    CONSTRAINT "ExperienceItem_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "ExperienceItem_profileId_idx" ON "ExperienceItem"("profileId");

-- AddForeignKey
ALTER TABLE "Conversation" ADD CONSTRAINT "Conversation_experienceProfileId_fkey" FOREIGN KEY ("experienceProfileId") REFERENCES "ExperienceProfile"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ExperienceItem" ADD CONSTRAINT "ExperienceItem_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "ExperienceProfile"("id") ON DELETE CASCADE ON UPDATE CASCADE;
