-- CreateEnum
CREATE TYPE "LearningDimension" AS ENUM ('content', 'clarity', 'english', 'professional_communication');

-- CreateEnum
CREATE TYPE "LearningFocus" AS ENUM ('weak_relevance', 'incomplete_content', 'weak_specificity', 'weak_organization', 'weak_coherence', 'hard_to_follow', 'grammar', 'word_choice', 'sentence_construction', 'naturalness', 'unclear_ownership', 'weak_decision_making', 'weak_professional_framing');

-- CreateTable
CREATE TABLE "PracticeTrack" (
    "id" TEXT NOT NULL,
    "experienceProfileId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "PracticeTrack_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "LearningDerivation" (
    "id" TEXT NOT NULL,
    "practiceTrackId" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "feedbackId" TEXT NOT NULL,
    "promptVersion" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "LearningDerivation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "LearningSignal" (
    "id" TEXT NOT NULL,
    "derivationId" TEXT NOT NULL,
    "practiceTrackId" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "feedbackItemIndex" INTEGER NOT NULL,
    "answerMessageId" TEXT NOT NULL,
    "questionMessageId" TEXT,
    "dimension" "LearningDimension" NOT NULL,
    "focus" "LearningFocus" NOT NULL,
    "observation" TEXT NOT NULL,
    "practiceCue" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "LearningSignal_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "PracticeTrack_experienceProfileId_key" ON "PracticeTrack"("experienceProfileId");

-- CreateIndex
CREATE UNIQUE INDEX "LearningDerivation_feedbackId_key" ON "LearningDerivation"("feedbackId");

-- CreateIndex
CREATE INDEX "LearningDerivation_practiceTrackId_idx" ON "LearningDerivation"("practiceTrackId");

-- CreateIndex
CREATE INDEX "LearningSignal_practiceTrackId_idx" ON "LearningSignal"("practiceTrackId");

-- CreateIndex
CREATE INDEX "LearningSignal_conversationId_idx" ON "LearningSignal"("conversationId");

-- CreateIndex
CREATE UNIQUE INDEX "LearningSignal_derivationId_feedbackItemIndex_focus_key" ON "LearningSignal"("derivationId", "feedbackItemIndex", "focus");

-- AddForeignKey
ALTER TABLE "PracticeTrack" ADD CONSTRAINT "PracticeTrack_experienceProfileId_fkey" FOREIGN KEY ("experienceProfileId") REFERENCES "ExperienceProfile"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningDerivation" ADD CONSTRAINT "LearningDerivation_practiceTrackId_fkey" FOREIGN KEY ("practiceTrackId") REFERENCES "PracticeTrack"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningDerivation" ADD CONSTRAINT "LearningDerivation_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "Conversation"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningDerivation" ADD CONSTRAINT "LearningDerivation_feedbackId_fkey" FOREIGN KEY ("feedbackId") REFERENCES "Feedback"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningSignal" ADD CONSTRAINT "LearningSignal_derivationId_fkey" FOREIGN KEY ("derivationId") REFERENCES "LearningDerivation"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningSignal" ADD CONSTRAINT "LearningSignal_practiceTrackId_fkey" FOREIGN KEY ("practiceTrackId") REFERENCES "PracticeTrack"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LearningSignal" ADD CONSTRAINT "LearningSignal_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "Conversation"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
