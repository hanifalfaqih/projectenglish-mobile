-- CreateEnum
CREATE TYPE "RetryFeedbackStatus" AS ENUM ('pending', 'generated', 'failed');

-- CreateTable
CREATE TABLE "RetryPractice" (
    "id" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "feedbackId" TEXT NOT NULL,
    "answerMessageId" TEXT NOT NULL,
    "questionMessageId" TEXT,
    "retryClientKey" TEXT NOT NULL,
    "retryAnswer" TEXT NOT NULL,
    "feedback" JSONB,
    "feedbackPromptVersion" TEXT,
    "feedbackStatus" "RetryFeedbackStatus" NOT NULL DEFAULT 'pending',
    "supersededAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RetryPractice_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "RetryPractice_retryClientKey_key" ON "RetryPractice"("retryClientKey");

-- CreateIndex
CREATE INDEX "RetryPractice_conversationId_answerMessageId_idx" ON "RetryPractice"("conversationId", "answerMessageId");

-- AddForeignKey
ALTER TABLE "RetryPractice" ADD CONSTRAINT "RetryPractice_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "Conversation"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RetryPractice" ADD CONSTRAINT "RetryPractice_feedbackId_fkey" FOREIGN KEY ("feedbackId") REFERENCES "Feedback"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
